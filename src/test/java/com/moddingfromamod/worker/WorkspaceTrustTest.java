package com.moddingfromamod.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The wish workers' directory is marked trusted for the CLI — measured on the cloud rig, where a
 * fresh machine had no {@code ~/.claude.json} and every worker died with "Workspace not trusted".
 */
class WorkspaceTrustTest {

    @TempDir
    Path tmp;

    @Test
    void aMachineWithNoConfigGetsOneThatTrustsTheDirectory() {
        String out = WorkspaceTrust.withTrust(null, "/srv/moddy/.moddingfromamod/session-25585");
        assertTrue(WorkspaceTrust.isTrusted(out, "/srv/moddy/.moddingfromamod/session-25585"));
    }

    @Test
    void theRestOfTheClisConfigSurvives() {
        String existing = "{\"oauthAccount\":{\"email\":\"x\"},\"projects\":{\"/other\":"
                + "{\"hasTrustDialogAccepted\":false,\"allowedTools\":[\"Bash\"]},"
                + "\"/w\":{\"mcpServers\":{\"a\":1}}},\"numStartups\":7}";
        var o = JsonParser.parseString(WorkspaceTrust.withTrust(existing, "/w")).getAsJsonObject();
        assertEquals(7, o.get("numStartups").getAsInt(), "top-level keys kept");
        assertEquals("x", o.getAsJsonObject("oauthAccount").get("email").getAsString());
        var projects = o.getAsJsonObject("projects");
        assertFalse(projects.getAsJsonObject("/other").get("hasTrustDialogAccepted").getAsBoolean(),
                "another directory's answer is not ours to change");
        assertTrue(projects.getAsJsonObject("/w").has("mcpServers"), "the entry's own keys kept");
        assertTrue(projects.getAsJsonObject("/w").get("hasTrustDialogAccepted").getAsBoolean());
    }

    @Test
    void aFileThatIsNotTheClisJsonIsNeverOverwritten() {
        assertNull(WorkspaceTrust.withTrust("{not json", "/w"));
        assertNull(WorkspaceTrust.withTrust("[1,2]", "/w"));
    }

    @Test
    void ensureWritesOnceAndThenLeavesItAlone() throws Exception {
        Path config = tmp.resolve("home/.claude.json");
        Path work = tmp.resolve("session");
        Files.createDirectories(work);
        assertEquals(WorkspaceTrust.Outcome.WRITTEN, WorkspaceTrust.ensure(config, work));
        String first = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(WorkspaceTrust.isTrusted(first, work.toAbsolutePath().normalize().toString()));
        assertEquals(WorkspaceTrust.Outcome.ALREADY, WorkspaceTrust.ensure(config, work));
        assertEquals(first, Files.readString(config, StandardCharsets.UTF_8), "idempotent");
    }

    @Test
    void ensureLeavesAnUnreadableFileExactlyAsItWas() throws Exception {
        Path config = tmp.resolve(".claude.json");
        Files.writeString(config, "{broken", StandardCharsets.UTF_8);
        assertEquals(WorkspaceTrust.Outcome.UNREADABLE, WorkspaceTrust.ensure(config, tmp));
        assertEquals("{broken", Files.readString(config, StandardCharsets.UTF_8));
    }
}
