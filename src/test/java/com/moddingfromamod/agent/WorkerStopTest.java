package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.config.ModdySettings;
import com.moddingfromamod.config.PlayerSettings;
import com.moddingfromamod.config.SettingsCatalog;
import com.moddingfromamod.config.SupportedModels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>every worker Moddy started is stopped, on a provider switch and on quit, with what STARTED it.</b>
 *
 * <p>Stand-in workers throughout: a fake {@code claude} that records how it was asked to stop (its arguments and
 * {@code CLAUDE_CONFIG_DIR}), and a worker process that has a child of its own. The Magician is off in a unit test,
 * so {@code ClaudeCli.resolve()} is null here — exactly the state the switch leaves a real game in, and the one the
 * old stop path gave up on ("could not find the CLI to stop it").
 */
class WorkerStopTest {

    @TempDir
    Path dir;

    private Path cli;
    private Path calls;
    private java.util.function.Supplier<String> providerWas;
    private long graceWas;

    @BeforeEach
    void fakeCli() throws Exception {
        calls = dir.resolve("calls.txt");
        cli = dir.resolve("claude");
        Files.writeString(cli, "#!/bin/sh\necho \"$* dir=${CLAUDE_CONFIG_DIR:-default}\" >> " + calls + "\n"
                + "[ \"$1\" = agents ] && echo '[]'\nexit 0\n");
        Files.setPosixFilePermissions(cli, PosixFilePermissions.fromString("rwxr-xr-x"));
        providerWas = AgentSession.currentProvider;
        graceWas = WorkerStopper.graceMs;
        WorkerStopper.graceMs = 1_000L;
        AgentSession.stop();
    }

    @AfterEach
    void restore() {
        AgentSession.currentProvider = providerWas;
        AgentSession.stop();
        WorkerStopper.graceMs = graceWas;
        ModdySettings.overridesForTest(null);
        PlayerSettings.shared().clearForTest();
    }

    /** A process with a child: {@code sh} that starts a {@code sleep} and waits on it. */
    private static Process workerWithAChild() throws Exception {
        Process p = new ProcessBuilder("sh", "-c", "sleep 300 & wait").start();
        long until = System.currentTimeMillis() + 5_000;
        while (p.toHandle().descendants().findAny().isEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        assertTrue(p.toHandle().descendants().findAny().isPresent(), "the stand-in worker never started its child");
        return p;
    }

    /**
     * Has it exited? Waits a moment: a killed process is a zombie until its parent (or init) reaps it, and the OS
     * reports a zombie as alive.
     */
    private static boolean gone(ProcessHandle h) {
        try {
            h.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // still alive: reported below
        }
        return !h.isAlive();
    }

    private List<String> calls() throws Exception {
        return Files.isRegularFile(calls) ? Files.readAllLines(calls, StandardCharsets.UTF_8) : List.of();
    }

    @Test
    void aSwitchToMuseStopsTheClaudeWorkerWithItsOwnProgramAndFolder() throws Exception {
        assertEquals(null, ClaudeCli.resolve(), "precondition: the CLI cannot be resolved (Magician off)");
        Path folder = dir.resolve("worker-cli");
        Process launcher = workerWithAChild();
        List<ProcessHandle> tree = new java.util.ArrayList<>(launcher.toHandle().descendants().toList());
        AgentSession.currentProvider = () -> AgentSession.PROVIDER_CLAUDE;
        AgentSession.adoptForTest("wish-granter", "abc12345",
                new WorkerStopper.Launch(cli.toString(), folder, false, launcher), AgentSession.PROVIDER_CLAUDE);

        assertFalse(AgentSession.stopIfProviderChangedNow(), "same provider: the pool stays");
        assertEquals(1, AgentSession.workerCount());

        AgentSession.currentProvider = () -> AgentSession.PROVIDER_MUSE;
        assertTrue(AgentSession.stopIfProviderChangedNow(), "the picks moved to Muse: the Claude pool is stopped");
        assertEquals(List.of("stop abc12345 dir=" + folder), calls(),
                "stopped through the program and config folder it was STARTED with");
        assertEquals(0, AgentSession.workerCount());
        assertTrue(gone(launcher.toHandle()), "its launcher is gone");
        tree.forEach(h -> assertTrue(gone(h), "the launcher's child " + h.pid() + " outlived the switch"));
    }

    @Test
    void startReplacesAPoolStartedForTheOtherProvider() throws Exception {
        Process launcher = workerWithAChild();
        AgentSession.adoptForTest("wish-granter", "def67890",
                new WorkerStopper.Launch(cli.toString(), null, false, launcher), AgentSession.PROVIDER_CLAUDE);
        AgentSession.currentProvider = () -> AgentSession.PROVIDER_MUSE;
        AgentSession.start("/not/used");   // used to return early while any worker existed
        assertEquals(List.of("stop def67890 dir=default"), calls(),
                "start() stopped the Claude pool (default folder: CLAUDE_CONFIG_DIR unset) instead of keeping it");
        assertEquals(0, AgentSession.workerCount());
    }

    @Test
    void quittingKillsEveryWorkerAndItsChildrenMuseIncluded() throws Exception {
        Process muse = workerWithAChild();
        List<ProcessHandle> museTree = new java.util.ArrayList<>(muse.toHandle().descendants().toList());
        Process claudeLauncher = workerWithAChild();
        List<ProcessHandle> claudeTree = new java.util.ArrayList<>(claudeLauncher.toHandle().descendants().toList());
        AgentSession.adoptForTest("wish-granter", "musew-1",
                new WorkerStopper.Launch("/usr/bin/muse-not-asked", null, true, muse), AgentSession.PROVIDER_MUSE);
        AgentSession.adoptForTest("wish-granter-2", "aaaa1111",
                new WorkerStopper.Launch(cli.toString(), dir.resolve("cfg"), false, claudeLauncher),
                AgentSession.PROVIDER_MUSE);

        AgentSession.stop();   // what the shutdown hook and the server stop call

        assertTrue(gone(muse.toHandle()), "the Muse worker outlived quit");
        museTree.forEach(h -> assertTrue(gone(h), "the Muse worker's child " + h.pid() + " outlived quit"));
        claudeTree.forEach(h -> assertTrue(gone(h), "the Claude launcher's child outlived quit"));
        assertEquals(List.of("stop aaaa1111 dir=" + dir.resolve("cfg")), calls(),
                "the Claude worker is stopped by id with its folder; the Muse one never asks a CLI");
        assertEquals(0, AgentSession.workerCount());
    }

    @Test
    void aWorkerThatIgnoresTermIsForceKilled() throws Exception {
        Process stubborn = new ProcessBuilder("sh", "-c", "trap '' TERM; sleep 300 & wait; sleep 300").start();
        Thread.sleep(200);
        long t0 = System.currentTimeMillis();
        WorkerStopper.killTree(stubborn.toHandle());
        assertTrue(gone(stubborn.toHandle()), "destroy() alone did not end it, destroyForcibly() must");
        assertTrue(System.currentTimeMillis() - t0 < 5_000, "the grace is short");
    }

    /** A pick change through the settings reaches the pool, off the calling thread. */
    @Test
    void aPickChangeThroughTheSettingsStopsTheOldProvidersPool() throws Exception {
        ModdySettings.overridesForTest(List.of());
        PlayerSettings.shared().clearForTest();
        PlayerSettings p = PlayerSettings.shared();
        p.set(SettingsCatalog.MAIN_MODEL, SupportedModels.OPUS);
        p.set(SettingsCatalog.MAIN_MODE, "api");
        p.set(SettingsCatalog.CHEAP_MODEL, SupportedModels.HAIKU);
        p.set(SettingsCatalog.CHEAP_MODE, "api");
        assertEquals(AgentSession.PROVIDER_CLAUDE, providerWas.get(), "Opus picks run Claude Code");
        AgentSession.currentProvider = providerWas;   // the real one, read from the settings
        Process launcher = workerWithAChild();
        AgentSession.adoptForTest("wish-granter", "bbbb2222",
                new WorkerStopper.Launch(cli.toString(), null, false, launcher), AgentSession.PROVIDER_CLAUDE);

        ModdySettings.Change c = ModdySettings.shared().change(SettingsCatalog.MAIN_MODEL, SupportedModels.WONDER,
                new ModdySettings.Who("host", true, false));
        assertTrue(c.done(), c.line());
        long until = System.currentTimeMillis() + 15_000;
        while (calls().isEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        assertEquals(0, AgentSession.workerCount(), "the Claude pool outlived a switch to Muse picks");
        assertEquals(List.of("stop bbbb2222 dir=default"), calls());
    }

    @Test
    void aPickChangeRunsTheHookAndNothingElseDoes(@TempDir Path settings) {
        AtomicInteger ran = new AtomicInteger();
        PlayerSettings player = new PlayerSettings(settings.resolve("s.json"));
        ModdySettings s = new ModdySettings(List::of, k -> java.util.Optional.empty(), () -> false, player)
                .afterPickChange(ran::incrementAndGet);
        ModdySettings.Who host = new ModdySettings.Who("host", true, false);
        assertTrue(s.change(SettingsCatalog.MAIN_MODEL, SupportedModels.OPUS, host).done());
        assertEquals(1, ran.get());
        s.change(SettingsCatalog.MAIN_MODEL, SupportedModels.OPUS, host);
        assertEquals(1, ran.get(), "the same value is not a change");
        s.change(SettingsCatalog.MAIN_MODE, "api", host);
        assertEquals(2, ran.get(), "how it is paid is a pick too");
    }

    // ── the daemon ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aDaemonIsRecognisedAndItsSpawningFolderRead() {
        List<String> real = List.of("/opt/claude-code/bin/claude", "daemon", "run", "--origin", "transient",
                "--spawned-by", "{\"label\":\"claude --bg\",\"cwd\":\"/game/moddingfromamod/agent\"}");
        assertTrue(WorkerStopper.isDaemon(real));
        assertTrue(WorkerStopper.spawnedFrom(real, Path.of("/game/moddingfromamod/agent")));
        assertFalse(WorkerStopper.spawnedFrom(real, Path.of("/home/player/project")), "the player's daemon");
        // commandLine() splits on spaces, a folder with a space included
        List<String> split = List.of("claude", "daemon", "run", "--spawned-by", "{\"label\":\"claude", "--bg\",",
                "\"cwd\":\"/My", "Games/agent\"}");
        assertEquals("/My Games/agent", WorkerStopper.spawnedByCwd(split).orElseThrow());
        assertFalse(WorkerStopper.isDaemon(List.of("claude", "bg-pty-host", "--bg-pty-host", "x")));
        assertFalse(WorkerStopper.isDaemon(List.of("/usr/bin/python3", "daemon", "run")));
    }

    @Test
    void onlyADaemonNobodyElseUsesIsStopped() {
        java.util.function.Predicate<String> ours = n -> ReapRule.isOurs(n, "wish-granter");
        assertFalse(WorkerStopper.othersOn("[]", ours));
        assertFalse(WorkerStopper.othersOn("[{\"name\":\"wish-granter-2\",\"state\":\"blocked\"}]", ours));
        assertFalse(WorkerStopper.othersOn("[{\"name\":\"my-thing\",\"state\":\"done\"}]", ours), "finished");
        assertTrue(WorkerStopper.othersOn("[{\"name\":\"my-thing\",\"state\":\"working\"}]", ours),
                "the player's own session is on it: leave it");
        assertTrue(WorkerStopper.othersOn("not json", ours), "unreadable: assume somebody is there");
    }

    /**
     * A daemon Moddy started (a stand-in: a process with a child of its own) is stopped with the pool, its children
     * too; the fake {@code agents --json} lists nobody else.
     */
    @Test
    void aDaemonModdyStartedIsStoppedOnQuit() throws Exception {
        Process daemon = workerWithAChild();
        List<ProcessHandle> kids = new java.util.ArrayList<>(daemon.toHandle().descendants().toList());
        AgentSession.adoptDaemonForTest(new WorkerStopper.Daemon(daemon.toHandle(), cli.toString(), null, List.of()));
        AgentSession.stop();
        assertTrue(gone(daemon.toHandle()), "Moddy's daemon outlived quit");
        kids.forEach(h -> assertTrue(gone(h), "a pty host under Moddy's daemon outlived quit"));
        assertEquals(List.of("agents --json dir=default"), calls(), "asked who else is on it first");
    }

    @Test
    void aDaemonSomebodyElseUsesIsLeftRunning() throws Exception {
        Files.writeString(cli, "#!/bin/sh\necho \"$* dir=${CLAUDE_CONFIG_DIR:-default}\" >> " + calls + "\n"
                + "echo '[{\"name\":\"players-own\",\"state\":\"working\"}]'\n");
        Process daemon = workerWithAChild();
        try {
            AgentSession.adoptDaemonForTest(new WorkerStopper.Daemon(daemon.toHandle(), cli.toString(), null,
                    List.of()));
            AgentSession.stop();
            assertTrue(daemon.isAlive(), "a daemon running the player's own session must be left alone");
        } finally {
            WorkerStopper.killTree(daemon.toHandle());
        }
    }
}
