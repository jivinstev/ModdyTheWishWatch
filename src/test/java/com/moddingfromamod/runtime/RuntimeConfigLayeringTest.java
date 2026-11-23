package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>Layering invariant:</b> the engine names no specific mod repo in its checked-in code. The
 * persist/publish target resolves ONLY from machine-local config (property / env / config file), so
 * the dependency arrow only ever points <i>mod repo → engine</i>. If nothing is configured there is
 * no built-in fall-back to any particular repo — publishing is simply off.
 */
class RuntimeConfigLayeringTest {

    private static final String HOME = "moddingfromamod.home";
    private static final String PDIR = "moddingfromamod.persistDir";

    @AfterEach
    void reset() {
        System.clearProperty(HOME);
        System.clearProperty(PDIR);
        System.clearProperty("forge.gameTestServer");
        RuntimeConfig.invalidate();
    }

    /**
     * A TEST run must never resolve the config-file Mod Repo — otherwise gametests that persist a
     * template/datapack would publish into the real repo. An explicit override still wins, so a test
     * that isolates its own temp repo still works.
     */
    @Test
    void testRunsIgnoreTheConfigFileRepo(@TempDir Path home) throws Exception {
        System.clearProperty(PDIR);
        System.setProperty(HOME, home.toString());
        Files.writeString(home.resolve("config.properties"),
                "persist_dir=" + home.resolve("realrepo") + "\n");
        Files.createDirectories(home.resolve("realrepo"));
        System.setProperty("forge.gameTestServer", "1"); // makes RunContext.isTestRun() true
        RuntimeConfig.invalidate();

        assertNull(RuntimeConfig.persistDir(),
                "a test run must ignore the config-file Mod Repo so tests can't publish to it");

        // ...but an explicit property override still wins, even in a test run.
        Path tmp = home.resolve("tmp");
        Files.createDirectories(tmp);
        System.setProperty(PDIR, tmp.toString());
        RuntimeConfig.invalidate();
        assertEquals(tmp, RuntimeConfig.persistDir());
    }

    /** With nothing configured, persistDir must NOT fall back to any hardcoded mod repo. */
    @Test
    void noHardcodedRepoDefault(@TempDir Path emptyHome) {
        System.setProperty(HOME, emptyHome.toString()); // no config.properties inside
        System.clearProperty(PDIR);
        RuntimeConfig.invalidate();

        Path d = RuntimeConfig.persistDir();
        // Either null (nothing set) or, if the CI env happens to set the env var, some other path —
        // but never a baked-in mod repo name (the coupling this test forbids).
        assertTrue(d == null || !d.toString().toLowerCase().contains("battle"),
                "engine must not hardcode any specific mod repo, got: " + d);
    }

    /** The configured target resolves from the system property (the same seam env/config use). */
    @Test
    void resolvesFromConfig(@TempDir Path repo) {
        System.setProperty(HOME, repo.resolve("home").toString());
        System.setProperty(PDIR, repo.toString());
        RuntimeConfig.invalidate();

        assertEquals(repo, RuntimeConfig.persistDir());
        assertTrue(RuntimeConfig.persistEnabled(), "an existing configured dir is enabled");
    }

    /**
     * The PRODUCTION path: a running game has no system property, it reads
     * {@code ~/.moddingfromamod/config.properties}. Points home at a temp dir, writes the config
     * there, and asserts the repo resolves — the exact mechanism that keeps this machine pointed at
     * its configured mod repo after the hardcoded default was removed.
     */
    @Test
    void resolvesFromConfigFile(@TempDir Path home) throws Exception {
        // A test run resolves persistDir to null on purpose, so a gate can never publish into the
        // real Mod Repo. This test points at a temp directory and is checking the layering itself,
        // so it opts out explicitly.
        System.setProperty(com.moddingfromamod.RunContext.FORCE_REAL_RUN, "true");
        System.clearProperty(PDIR);
        System.setProperty(HOME, home.toString());
        java.nio.file.Files.writeString(home.resolve("config.properties"),
                "persist_dir=" + home.resolve("myrepo") + "\npersist_modid=myrepomod\n");
        java.nio.file.Files.createDirectories(home.resolve("myrepo"));
        RuntimeConfig.invalidate();

        assertEquals(home.resolve("myrepo"), RuntimeConfig.persistDir());
        assertEquals("myrepomod", RuntimeConfig.persistModId());
        assertTrue(RuntimeConfig.persistEnabled());
        System.clearProperty(com.moddingfromamod.RunContext.FORCE_REAL_RUN);
    }

    @Test
    @DisplayName("a test run never resolves a real Mod Repo, however the config is set")
    void aTestRunNeverPublishes(@TempDir Path home) throws Exception {
        // The guard this seam exists to preserve. Without it a unit test could publish artifacts
        // into the child's actual repo.
        System.clearProperty(com.moddingfromamod.RunContext.FORCE_REAL_RUN);
        System.clearProperty(PDIR);
        System.setProperty(HOME, home.toString());
        java.nio.file.Files.writeString(home.resolve("config.properties"),
                "persist_dir=" + home.resolve("myrepo") + "\n");
        java.nio.file.Files.createDirectories(home.resolve("myrepo"));
        RuntimeConfig.invalidate();
        org.junit.jupiter.api.Assertions.assertNull(RuntimeConfig.persistDir(),
                "a test run must not resolve a Mod Repo to publish into");
    }
}
