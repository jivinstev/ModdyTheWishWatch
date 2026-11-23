package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * the REAL launch ({@link MuseWorker#start}) against a fake {@code muse} that writes down what it was given —
 * its arguments, its environment, its standard input and the config it found — so what a player's worker receives is
 * checked end to end, for free.
 */
@DisabledOnOs(OS.WINDOWS)
class MuseWorkerLaunchTest {

    private static Path fakeMuse(Path dir) throws IOException {
        Path out = dir.resolve("seen");
        Path fake = dir.resolve("muse");
        Files.writeString(fake, "#!/bin/sh\n"
                + "if [ \"$2\" = \"--help\" ]; then echo '  --disable-reminders'; exit 0; fi\n"
                + "printf '%s\\n' \"$@\" > " + out.resolve("argv") + "\n"
                + "env > " + out.resolve("env") + "\n"
                + "cat > " + out.resolve("stdin") + "\n"
                + "cp \"$XDG_CONFIG_HOME/muse/settings.json\" " + out.resolve("settings.json") + "\n"
                + "[ -f \"$XDG_CONFIG_HOME/muse/auth.json\" ] && cp \"$XDG_CONFIG_HOME/muse/auth.json\" "
                + out.resolve("auth.json") + "\n"
                + "exit 0\n");
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.createDirectories(out);
        return fake;
    }

    private static Path run(Path dir, boolean apiKey, Map<String, String> env) throws Exception {
        Path fake = fakeMuse(dir);
        Path pool = Files.createDirectories(dir.resolve("pool"));
        var started = MuseWorker.start(fake.toString(), pool, pool.resolve("agent.log"), "wish-granter", "THE PROMPT",
                4711, "tok", apiKey ? "muse:muse-spark-1.2-contributor" : "muse:muse-wonder-1.0", "low", apiKey,
                900_000L, env::get, dir.resolve("home").toString());
        assertTrue(started.getKey().waitFor(20, TimeUnit.SECONDS));
        assertTrue(MuseWorker.isMuseId(started.getValue().id()));
        return dir.resolve("seen");
    }

    @Test
    void anApiKeyWorkerGetsTheKeyOnStdinAndNowhereElse(@TempDir Path dir) throws Exception {
        Path seen = run(dir, true, Map.of("MUSE_API_KEY", "LLM_secret", "META_API_KEY", "m", "PATH", "/usr/bin:/bin"));
        List<String> argv = Files.readAllLines(seen.resolve("argv"));
        assertEquals("exec", argv.get(0));
        assertTrue(argv.contains("--api-key-stdin"));
        assertTrue(argv.containsAll(MuseWorker.LOCKDOWN));
        assertTrue(argv.contains("--disable-reminders"), "a lock the binary lists is added");
        assertFalse(argv.contains("--no-foreign-personal-context"), "a lock the binary does not list is not");
        assertEquals("muse-spark-1.2-contributor", argv.get(argv.indexOf("--model") + 1));
        assertEquals("LLM_secret", Files.readString(seen.resolve("stdin")));
        String env = Files.readString(seen.resolve("env"));
        assertFalse(env.contains("LLM_secret"), "the key reached the environment");
        assertFalse(env.contains("META_API_KEY="));
        assertFalse(String.join(" ", argv).contains("LLM_secret"), "the key reached the command line");
        assertTrue(Files.readString(seen.resolve("settings.json")).contains("127.0.0.1:4711"));
        assertFalse(Files.exists(seen.resolve("auth.json")), "an API-key worker carries no sign-in");
        assertEquals("THE PROMPT", Files.readString(Path.of(argv.get(argv.indexOf("--prompt-file") + 1))));
    }

    @Test
    void aSignInWorkerCarriesThePlayersOwnLoginAndNoKey(@TempDir Path dir) throws Exception {
        Path player = Files.createDirectories(dir.resolve("xdg/muse"));
        Files.writeString(player.resolve("auth.json"), "{\"providers\":{\"meta\":{\"mechanism\":\"oauth\"}}}");
        Files.writeString(player.resolve("settings.json"), "{\"mcpServers\":{\"theirs\":{\"url\":\"http://x\"}}}");
        Path seen = run(dir, false, Map.of("MUSE_API_KEY", "LLM_secret", "PATH", "/usr/bin:/bin",
                "XDG_CONFIG_HOME", dir.resolve("xdg").toString()));
        List<String> argv = Files.readAllLines(seen.resolve("argv"));
        assertFalse(argv.contains("--api-key-stdin"));
        assertEquals("muse-wonder-1.0", argv.get(argv.indexOf("--model") + 1));
        assertEquals("", Files.readString(seen.resolve("stdin")));
        assertFalse(Files.readString(seen.resolve("env")).contains("LLM_secret"), "a key would out-rank the sign-in");
        assertTrue(Files.exists(seen.resolve("auth.json")), "the player's own sign-in was carried in");
        String settings = Files.readString(seen.resolve("settings.json"));
        assertFalse(settings.contains("theirs"), "the player's own MCP servers reached a child-facing worker");
        assertTrue(settings.contains("wishwatch"));
    }

    @Test
    void apiKeyModeWithNoKeyRefusesToStart(@TempDir Path dir) throws Exception {
        Path fake = fakeMuse(dir);
        assertThrows(IOException.class, () -> MuseWorker.start(fake.toString(), dir, dir.resolve("a.log"), "w", "p",
                1, "t", "muse:muse-spark-1.2", "low", true, 1L, k -> null, dir.toString()));
    }
}
