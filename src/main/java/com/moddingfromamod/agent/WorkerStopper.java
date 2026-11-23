package com.moddingfromamod.agent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Stopping a wish worker with what STARTED it, never with what is chosen now</b>.
 *
 * <p>A worker is stopped later than it is started, and between the two the host can change everything the old stop
 * path read: switch the Magician from Claude to Muse picks (which also turns the Magician off), so
 * {@code ClaudeCli.resolve()} answers null and {@code AgentSession.workerCliDir()} names the folder of the NEW wish
 * model. The old path then logged "could not find the CLI to stop it" on the switch and again on quit, and the
 * {@code claude --bg} session, its pty host and its daemon outlived the game. So every launch records a {@link Launch}
 * (the program path and the CLI config folder it was started with) and every stop uses exactly that.
 *
 * <p>How each kind is stopped:
 * <ul>
 *   <li><b>A Claude Code worker</b> is a {@code --bg} session owned by a daemon, not by this JVM: its launcher exits
 *       within seconds. It is stopped by {@code <recorded program> stop <id>} with {@code CLAUDE_CONFIG_DIR} set to the
 *       recorded folder (or removed, when it ran on the machine's own sign-in).</li>
 *   <li><b>A Muse Code worker</b> is this JVM's own child. It is stopped by {@link #killTree}: every descendant and the
 *       process itself get {@code destroy()}, and whatever is alive after a short grace gets {@code destroyForcibly()}.
 *       A plain {@code destroy()} of the parent alone left its children running.</li>
 * </ul>
 *
 * <p><b>The daemon — the decision</b> ({@link Daemon}). A {@code claude daemon run} (and the {@code bg-pty-host}
 * processes under it) is stopped ONLY when Moddy started it, and only when nobody else is using it:
 * <ol>
 *   <li><b>Moddy started it</b> means: it was not running just before one of this game's own Claude launches (pid and
 *       start time), it was running just after, and its {@code --spawned-by} names the pool's own working folder as
 *       the folder it was spawned from (only Moddy launches {@code claude --bg} there). A daemon the player was
 *       already running is never recorded, so it is never touched.</li>
 *   <li>It is considered only on a WHOLE-pool stop ({@code AgentSession.stop()}: a provider switch, the idle reap, a
 *       server stop, the game quitting), after every worker's own session was stopped by id. A daemon whose last
 *       session went exits by itself (measured: Claude Code 2.1.330, a transient daemon was gone within a second of
 *       {@code claude stop}); one still alive after the grace is asked {@code agents --json} (its recorded program and
 *       folder), and if it lists any session that is not one of ours and not {@code done} — the player started his
 *       own {@code claude --bg} on the daemon our worker brought up — it is LEFT RUNNING, said in the log.
 *       Otherwise it and its descendants are stopped with {@link #killTree}.</li>
 *   <li>Its descendants are snapshotted BEFORE the sessions are stopped, because a spare {@code bg-pty-host} is
 *       re-parented to init when the daemon exits (measured: one lingered ~15 s orphaned). Those leftovers are killed
 *       with it, under the same rule.</li>
 * </ol>
 *
 * <p>No Minecraft here, so the process mechanics are Gate A tested with stand-in programs ({@code WorkerStopTest}).
 */
public final class WorkerStopper {

    private static final Logger LOGGER = LoggerFactory.getLogger(com.moddingfromamod.ModdingFromAModMod.MODID);

    /** How long a destroyed process gets to exit before it is force-killed. */
    static volatile long graceMs = 2_000L;

    /** How long a {@code stop}/{@code agents} CLI call may take. */
    static final long CLI_TIMEOUT_MS = 10_000L;

    private WorkerStopper() {
    }

    /**
     * How one worker was started: its program, its CLI config folder ({@code null} = this machine's own sign-in, the
     * default folder), whether it is a Muse Code worker (this JVM's child) and the process this JVM launched (a Claude
     * launcher, usually exited already; a Muse worker itself).
     */
    public record Launch(String program, Path configDir, boolean muse, Process process) {
    }

    /** A {@code claude daemon run} this game started, with the program and folder of the launch that brought it up. */
    public record Daemon(ProcessHandle handle, String program, Path configDir, List<ProcessHandle> children) {
        Daemon withChildren(List<ProcessHandle> now) {
            return new Daemon(handle, program, configDir, now);
        }
    }

    // ── one worker ─────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Stop the worker {@code id} that {@code launch} started. Never asks which CLI is chosen now.
     *
     * @return true when the stop was carried out (the CLI answered, or the process tree is gone)
     */
    public static boolean stop(String id, Launch launch) {
        if (launch == null) {
            LOGGER.warn("wish agent {} has no record of how it was started, so it cannot be stopped (#2589)", id);
            return false;
        }
        if (launch.muse()) {
            if (launch.process() != null) {
                killTree(launch.process().toHandle());
            }
            return true;
        }
        if (launch.program() == null || launch.program().isBlank()) {
            LOGGER.warn("wish agent {} was started with no recorded program, so it cannot be stopped", id);
            return false;
        }
        LOGGER.info("stopping the wish agent session {} with {} (config {})", id, launch.program(),
                launch.configDir() == null ? "default" : launch.configDir());
        return runCli(List.of(launch.program(), "stop", id), launch.configDir()) != null;
    }

    /**
     * Destroy {@code root} and every descendant: {@code destroy()} first, {@code destroyForcibly()} on whatever is
     * still alive after {@link #graceMs}. The descendants are listed BEFORE anything is destroyed, since a child whose
     * parent died is re-parented and no longer a descendant.
     */
    public static void killTree(ProcessHandle root) {
        if (root == null) {
            return;
        }
        List<ProcessHandle> all = new ArrayList<>();
        try {
            all.addAll(root.descendants().toList());
        } catch (RuntimeException ignored) {
            // a process that just exited has no descendants to list
        }
        all.add(root);
        killAll(all);
    }

    /** {@link #killTree}'s second half, for a list already taken. */
    static void killAll(List<ProcessHandle> handles) {
        List<ProcessHandle> alive = new ArrayList<>();
        for (ProcessHandle h : handles) {
            if (h != null && h.isAlive() && h.pid() != ProcessHandle.current().pid()) {
                h.destroy();
                alive.add(h);
            }
        }
        long until = System.currentTimeMillis() + graceMs;
        for (ProcessHandle h : alive) {
            long left = until - System.currentTimeMillis();
            if (left > 0) {
                try {
                    h.onExit().get(left, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                    // timed out, or interrupted: forced below
                }
            }
            if (h.isAlive()) {
                h.destroyForcibly();
            }
        }
    }

    /** True when {@code h} has exited within {@code ms} (a killed process can be a zombie for a moment). */
    static boolean exits(ProcessHandle h, long ms) {
        try {
            h.onExit().get(ms, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // still running
        }
        return !h.isAlive();
    }

    // ── the daemon ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** The pids of every {@code claude daemon run} alive now: taken just before a Claude launch. */
    public static Set<String> daemonsNow() {
        Set<String> out = new HashSet<>();
        ProcessHandle.allProcesses().forEach(h -> {
            if (isDaemon(tokens(h))) {
                out.add(key(h));
            }
        });
        return out;
    }

    /**
     * The daemons alive now that were not in {@code before} and were spawned from {@code workDir}: the ones a launch
     * of ours brought up. See the class javadoc for why both conditions.
     */
    public static List<ProcessHandle> startedSince(Set<String> before, Path workDir) {
        List<ProcessHandle> out = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(h -> {
            List<String> t = tokens(h);
            if (isDaemon(t) && !before.contains(key(h)) && spawnedFrom(t, workDir)) {
                out.add(h);
            }
        });
        return out;
    }

    /** Pid plus start time: a pid alone can be reused by an unrelated process. */
    static String key(ProcessHandle h) {
        return h.pid() + "@" + h.info().startInstant().map(Object::toString).orElse("?");
    }

    /** The command and its arguments, as the OS reports them (empty when it will not say). */
    static List<String> tokens(ProcessHandle h) {
        List<String> out = new ArrayList<>();
        ProcessHandle.Info info = h.info();
        Optional<String[]> args = info.arguments();
        if (args.isPresent()) {
            info.command().ifPresent(out::add);
            out.addAll(List.of(args.get()));
        } else {
            info.commandLine().ifPresent(c -> out.addAll(List.of(c.split(" "))));
        }
        return out;
    }

    /** {@code … claude daemon run …}: a token named {@code claude…} followed later by {@code daemon run}. */
    static boolean isDaemon(List<String> tokens) {
        boolean claude = false;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (claude && "daemon".equals(t) && i + 1 < tokens.size() && "run".equals(tokens.get(i + 1))) {
                return true;
            }
            String name = t.replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1);
            if (name.startsWith("claude")) {
                claude = true;
            }
        }
        return false;
    }

    /** Does the daemon's {@code --spawned-by {"cwd": …}} name {@code workDir}? */
    static boolean spawnedFrom(List<String> tokens, Path workDir) {
        if (workDir == null) {
            return false;
        }
        Optional<String> cwd = spawnedByCwd(tokens);
        if (cwd.isEmpty()) {
            return false;
        }
        try {
            return Path.of(cwd.get()).toAbsolutePath().normalize().equals(workDir.toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The {@code cwd} of the JSON after {@code --spawned-by}, if the daemon was given one. */
    static Optional<String> spawnedByCwd(List<String> tokens) {
        int at = tokens.indexOf("--spawned-by");
        if (at < 0 || at + 1 >= tokens.size()) {
            return Optional.empty();
        }
        // commandLine() splits on spaces; rejoin what follows so a JSON with a space in its folder still parses.
        String json = String.join(" ", tokens.subList(at + 1, tokens.size()));
        int end = json.lastIndexOf('}');
        if (!json.startsWith("{") || end < 0) {
            return Optional.empty();
        }
        try {
            var reader = new com.google.gson.stream.JsonReader(new java.io.StringReader(json.substring(0, end + 1)));
            reader.setLenient(true);
            var el = com.google.gson.JsonParser.parseReader(reader);
            if (el.isJsonObject() && el.getAsJsonObject().has("cwd")) {
                return Optional.of(el.getAsJsonObject().get("cwd").getAsString());
            }
        } catch (RuntimeException ignored) {
            // not the JSON we expected: not ours to judge
        }
        return Optional.empty();
    }

    /** Snapshot a daemon's descendants now, before its sessions are stopped (see the class javadoc, point 3). */
    public static Daemon withChildrenNow(Daemon d) {
        try {
            return d.withChildren(d.handle().descendants().toList());
        } catch (RuntimeException e) {
            return d;
        }
    }

    /**
     * After every worker of ours was stopped by id: stop the daemon Moddy started, unless somebody else's session is
     * on it. {@code ours} recognises our workers' names. Returns what was done, for the log and the tests.
     */
    public static String stopDaemon(Daemon d, Predicate<String> ours) {
        ProcessHandle h = d.handle();
        if (!exits(h, graceMs)) {
            String listed = runCli(List.of(d.program(), "agents", "--json"), d.configDir());
            if (listed == null) {
                LOGGER.info("the claude daemon {} that Moddy started is still running and could not be asked who is "
                        + "on it, so it is left running", h.pid());
                return "left: could not ask";
            }
            if (othersOn(listed, ours)) {
                LOGGER.info("the claude daemon {} that Moddy started now also runs somebody else's session, so it is "
                        + "left running", h.pid());
                return "left: in use";
            }
            LOGGER.info("stopping the claude daemon {} that Moddy started (no other session on it)", h.pid());
        }
        List<ProcessHandle> all = new ArrayList<>(d.children());
        all.add(h);
        try {
            all.addAll(h.descendants().toList());
        } catch (RuntimeException ignored) {
            // exited already
        }
        boolean anyAlive = all.stream().anyMatch(ProcessHandle::isAlive);
        killAll(all);
        return anyAlive ? "stopped" : "gone";
    }

    /** Does an {@code agents --json} listing hold a session that is not ours and not finished? */
    static boolean othersOn(String agentsJson, Predicate<String> ours) {
        try {
            var arr = com.google.gson.JsonParser.parseString(agentsJson);
            if (!arr.isJsonArray()) {
                return true;   // unreadable: assume somebody is there
            }
            for (var el : arr.getAsJsonArray()) {
                if (!el.isJsonObject()) {
                    continue;
                }
                var o = el.getAsJsonObject();
                String name = o.has("name") ? o.get("name").getAsString() : "";
                String state = o.has("state") ? o.get("state").getAsString() : "";
                if (!ours.test(name) && !"done".equals(state)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    // ── the CLI ────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Run {@code argv} with the CLI config folder a worker was started with; its output, or null when it could not be
     * run or did not finish in time.
     */
    static String runCli(List<String> argv, Path configDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            // The folder the worker ran in, and nothing else: none means this machine's default folder, so an inherited
            // CLAUDE_CONFIG_DIR (a game started from a Claude session) must not point the stop elsewhere.
            pb.environment().remove(WorkerCliConfig.VAR);
            WorkerCliConfig.applyTo(pb.environment(), configDir);
            Path out = java.nio.file.Files.createTempFile("moddy-cli", ".out");
            try {
                Process p = pb.redirectErrorStream(true)
                        .redirectOutput(out.toFile())
                        .redirectInput(new File("/dev/null"))
                        .start();
                if (!p.waitFor(CLI_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                    LOGGER.warn("{} did not finish in {} s", argv, CLI_TIMEOUT_MS / 1000);
                    return null;
                }
                return java.nio.file.Files.readString(out, StandardCharsets.UTF_8);
            } finally {
                java.nio.file.Files.deleteIfExists(out);
            }
        } catch (IOException e) {
            LOGGER.warn("could not run {}: {}", argv, e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
