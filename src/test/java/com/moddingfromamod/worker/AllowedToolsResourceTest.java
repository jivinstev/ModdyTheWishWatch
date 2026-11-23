package com.moddingfromamod.worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.moddingfromamod.agent.AgentSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated tool list must equal the live registry (HO-6).
 *
 * <p>A worker container cannot call {@code AgentSession.allowedTools()} — it derives from
 * {@code WishAgent.buildTools()}, which imports Minecraft, and dragging the game onto a worker's
 * classpath to read fifty strings is not a trade worth making. So the list is generated and
 * committed, and this is what makes that safe: <b>if the registry changes and the resource does
 * not, the build goes red with the command to fix it.</b>
 *
 * <p>Without this the failure would be silent in the worst way: a tool added to the registry and
 * missing from the resource is, under {@code --permission-mode dontAsk}, not prompted for but
 * <b>silently DENIED</b> — so the capability would look like it simply does not work, on the
 * worker path only.
 */
class AllowedToolsResourceTest {

    private static final Path FILE =
            Path.of("src", "main", "resources", "agent", "allowed-tools.txt");

    @Test
    void theGeneratedListEqualsTheLiveRegistry() throws IOException {
        String live = AgentSession.allowedTools();
        assertFalse(live.isBlank(), "the registry produced no tools at all");

        if (!Files.exists(FILE)) {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, live + "\n", StandardCharsets.UTF_8);
        }
        String committed = Files.readString(FILE, StandardCharsets.UTF_8).trim();

        assertEquals(live, committed,
                "the committed tool list has drifted from WishAgent.buildTools(). A tool missing "
                        + "here is SILENTLY DENIED on the worker path under --permission-mode "
                        + "dontAsk, so the capability looks broken rather than blocked. "
                        + "Regenerate: delete " + FILE + " and re-run this test.");
    }

    @Test
    void everyEntryIsOneOfOurMcpToolsAndSurvivesTheWorkersOwnFilter() {
        // WorkerArgv.safe() drops anything that is not mcp__wishwatch__*. Nothing should ever be
        // dropped — the point is that the one thing this list must never carry, a shell, cannot
        // arrive through it whatever wrote the file.
        String live = AgentSession.allowedTools();
        assertEquals(live, WorkerArgv.safe(live),
                "the live list does not survive the worker's own filter unchanged");
        assertTrue(live.startsWith(WorkerArgv.PREFIX), "expected our tool prefix: " + live);
    }
}
