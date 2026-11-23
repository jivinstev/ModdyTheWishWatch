package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * a wish worker's first turn waits until the endpoint has sent it its tools. The CLI fixes a turn's tools
 * when the prompt is submitted; a worker whose tool list landed later had no tools for its only turn and went
 * deaf. Its {@code --settings} carries a {@code UserPromptSubmit} hook that waits for the marker the endpoint
 * touches — bounded, silent (a prompt hook's output would join the prompt), and reset for a replacement.
 */
class WorkerWaitsForItsToolsTest {

    @Test
    void theSettingsCarryASilentPromptHookOnThisSlotsMarker(@TempDir Path dir) throws Exception {
        Path settings = AgentSession.writeToolsWait(dir, "wish-granter-2");
        JsonObject s = JsonParser.parseString(Files.readString(settings, StandardCharsets.UTF_8)).getAsJsonObject();
        String command = s.getAsJsonObject("hooks").getAsJsonArray("UserPromptSubmit").get(0).getAsJsonObject()
                .getAsJsonArray("hooks").get(0).getAsJsonObject().get("command").getAsString();
        assertTrue(command.contains("wait-for-tools.sh"), command);
        assertTrue(command.contains(AgentSession.toolsListedMarker(dir, "wish-granter-2").toString()), command);
        assertTrue(Files.isRegularFile(dir.resolve("wait-for-tools.sh")));
    }

    @Test
    void aReplacementWaitsAgainBecauseTheOldMarkerIsGone(@TempDir Path dir) throws Exception {
        Path marker = AgentSession.toolsListedMarker(dir, "wish-granter-1");
        Files.writeString(marker, "");
        AgentSession.writeToolsWait(dir, "wish-granter-1");
        assertFalse(Files.exists(marker), "a replacement in the same slot must wait for ITS tools");
    }

    @Test
    void theHookReturnsSoonAfterTheToolsAreListedAndSaysNothing(@TempDir Path dir) throws Exception {
        AgentSession.writeToolsWait(dir, "wish-granter-1");
        Path marker = AgentSession.toolsListedMarker(dir, "wish-granter-1");
        long start = System.nanoTime();
        Process p = new ProcessBuilder("/bin/sh", dir.resolve("wait-for-tools.sh").toString(), marker.toString())
                .redirectErrorStream(true).start();
        Thread.sleep(400);
        assertTrue(p.isAlive(), "it must still be waiting: the tools have not been listed");
        Files.writeString(marker, "");                    // the endpoint sent this worker its tools
        assertTrue(p.waitFor(5, TimeUnit.SECONDS), "it must let the first turn go once they are");
        long ms = (System.nanoTime() - start) / 1_000_000L;
        assertEquals(0, p.exitValue());
        assertEquals("", new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                "a UserPromptSubmit hook's output joins the prompt; this one must print nothing");
        assertTrue(ms < 2_500, "waited " + ms + " ms after the tools were listed");
    }

    @Test
    void theWaitIsBounded() {
        assertTrue(AgentSession.TOOLS_WAIT_DECISECONDS <= 150, "a hook must never hold a worker for long");
        assertTrue(AgentSession.WAIT_SCRIPT.contains("-lt " + AgentSession.TOOLS_WAIT_DECISECONDS));
    }
}
