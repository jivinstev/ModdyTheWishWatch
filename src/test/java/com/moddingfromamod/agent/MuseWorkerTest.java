package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** the public Muse Code wish worker's command line, config, environment and sign-in handling. */
class MuseWorkerTest {

    @Test
    void theLockdownIsTheHousesOwn() {
        assertEquals(List.of("--yolo", "--preset", "empty", "--disable-shell", "--disable-write", "--disable-web-tools"),
                MuseWorker.LOCKDOWN);
        assertEquals(List.of("--no-foreign-personal-context", "--disable-reminders"), MuseWorker.LOCKDOWN_IF_SUPPORTED);
    }

    @Test
    void theExtraLocksAreAddedOnlyWhenTheBinaryListsThem() {
        String help = "      --disable-reminders\n          Disable reminder agents\n      --no-session-log\n";
        assertEquals(List.of("--disable-reminders"), MuseWorker.supportedLocks(help));
        assertEquals(List.of("--no-foreign-personal-context", "--disable-reminders"),
                MuseWorker.supportedLocks("  --no-foreign-personal-context\n  --disable-reminders, x\n"));
        assertTrue(MuseWorker.supportedLocks(null).isEmpty(), "a binary that will not answer --help gets neither");
        assertTrue(MuseWorker.supportedLocks("--disable-remindersX").isEmpty());
    }

    @Test
    void aSignInWorkerSendsNoKeyAndAKeyWorkerReadsItFromStdin() {
        Path prompt = Path.of("/run/prompt.txt");
        List<String> sub = MuseWorker.argv("/bin/muse", false, List.of(), prompt, "muse:muse-wonder-1.0", "low",
                "s-1");
        assertFalse(sub.contains("--api-key-stdin"));
        assertEquals("exec", sub.get(1));
        assertTrue(sub.containsAll(MuseWorker.LOCKDOWN));
        assertEquals("muse-wonder-1.0", sub.get(sub.indexOf("--model") + 1), "the provider prefix is dropped");
        assertEquals("low", sub.get(sub.indexOf("--reasoning-effort") + 1));
        assertEquals("s-1", sub.get(sub.indexOf("--session-id") + 1));
        assertEquals(prompt.toString(), sub.get(sub.indexOf("--prompt-file") + 1));
        List<String> api = MuseWorker.argv("/bin/muse", true, List.of("--disable-reminders"), prompt,
                "muse:muse-spark-1.2-contributor", "max", "s-2");
        assertEquals("--api-key-stdin", api.get(2));
        assertTrue(api.contains("--disable-reminders"));
        assertEquals("ultra", api.get(api.indexOf("--reasoning-effort") + 1), "Claude's max is Muse's ultra");
        assertFalse(MuseWorker.argv("/bin/muse", true, List.of(), prompt, "muse:x", "bogus", "s").contains(
                "--reasoning-effort"), "an effort Muse would ignore is never passed");
    }

    @Test
    void theConfigHoldsOnlyOurToolServer() {
        var root = JsonParser.parseString(MuseWorker.settingsJson(4711, "tok", "wish-granter-2", 900_000L))
                .getAsJsonObject();
        var servers = root.getAsJsonObject("mcpServers");
        assertEquals(1, servers.size());
        var s = servers.getAsJsonObject("wishwatch");
        assertEquals("http://127.0.0.1:4711" + McpHttpServer.PATH, s.get("url").getAsString());
        assertEquals("Bearer tok", s.getAsJsonObject("headers").get("Authorization").getAsString());
        assertEquals("wish-granter-2", s.getAsJsonObject("headers").get(AgentSession.WORKER_HEADER).getAsString());
        assertEquals(900_000L, s.get("timeout").getAsLong());
    }

    @Test
    void theEnvironmentCarriesNoKeyAndPointsAtTheRunsOwnConfig() {
        Map<String, String> env = new HashMap<>(Map.of("MUSE_API_KEY", "k", "META_API_KEY", "m",
                "ANTHROPIC_API_KEY", "a", "ANTHROPIC_AUTH_TOKEN", "t", "CLAUDE_CODE_SESSION_ID", "x",
                "CLAUDE_CONFIG_DIR", "/c", "XDG_CONFIG_HOME", "/home/me/.config", "PATH", "/bin"));
        MuseWorker.applyEnv(env, Path.of("/run/w1"));
        assertEquals("/run/w1", env.get("XDG_CONFIG_HOME"));
        assertEquals("/bin", env.get("PATH"));
        for (String k : List.of("MUSE_API_KEY", "META_API_KEY", "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN",
                "CLAUDE_CODE_SESSION_ID", "CLAUDE_CONFIG_DIR")) {
            assertFalse(env.containsKey(k), k);
        }
    }

    @Test
    void thePlayersConfigRootFollowsXdg() {
        assertEquals(Path.of("/x/muse"), MuseWorker.playerConfigRoot(k -> k.equals("XDG_CONFIG_HOME") ? "/x" : null,
                "/home/me"));
        assertEquals(Path.of("/home/me/.config/muse"), MuseWorker.playerConfigRoot(k -> null, "/home/me"));
    }

    private static final String OAUTH = "{\"providers\":{\"meta\":{\"mechanism\":\"oauth\",\"token\":\"%s\"}}}";

    @Test
    void aRefreshedTokenIsWrittenBackAndASignedOutCopyNever(@TempDir Path dir) throws Exception {
        Path player = Files.createDirectories(dir.resolve("player/muse"));
        Path run = Files.createDirectories(dir.resolve("run/muse"));
        Files.writeString(player.resolve("auth.json"), OAUTH.formatted("old"));
        long copied = MuseWorker.carryLogin(player, run);
        Path copy = run.resolve("auth.json");
        assertEquals(OAUTH.formatted("old"), Files.readString(copy));
        assertFalse(MuseWorker.writeBack(copy, copied, player), "nothing refreshed, nothing written");

        Files.writeString(copy, OAUTH.formatted("new"));
        Files.setLastModifiedTime(copy, FileTime.fromMillis(copied + 5_000));
        assertTrue(MuseWorker.writeBack(copy, copied, player));
        assertEquals(OAUTH.formatted("new"), Files.readString(player.resolve("auth.json")));

        Files.writeString(copy, "{\"providers\":{\"meta\":{\"mechanism\":\"api_key\"}}}");
        Files.setLastModifiedTime(copy, FileTime.fromMillis(copied + 10_000));
        assertFalse(MuseWorker.writeBack(copy, copied, player), "a copy that is no longer a sign-in");
        Files.writeString(copy, "not json");
        Files.setLastModifiedTime(copy, FileTime.fromMillis(copied + 15_000));
        assertFalse(MuseWorker.writeBack(copy, copied, player));
        assertEquals(OAUTH.formatted("new"), Files.readString(player.resolve("auth.json")));
    }

    @Test
    void noSignInFileIsNotCopied(@TempDir Path dir) throws Exception {
        assertEquals(-1, MuseWorker.carryLogin(dir.resolve("none"), Files.createDirectories(dir.resolve("run"))));
    }

    @Test
    void aWorkerIdIsNeverAClaudeSessionId() {
        assertTrue(MuseWorker.isMuseId("musewabc123"));
        assertFalse(MuseWorker.isMuseId("a1b2c3d4"));
        assertFalse(MuseWorker.isMuseId(null));
    }
}
