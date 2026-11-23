package com.moddingfromamod.marshal;

import com.moddingfromamod.testing.SourceRoots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.moddingfromamod.worker.WorkerArgv;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b> stays true as tools and MCP servers are added.</b> The pickup and plan ratchets hold a
 * wish's tool calls until its safety verdicts land, and they can only hold what passes through
 * {@code McpProtocol}. So the guarantee rests on three facts, each pinned here, each failing with
 * what to do instead of silently widening what runs before a verdict:
 * <ol>
 *   <li>every tool on our server waits unless it is on the frozen read-only list (a NEW tool waits
 *       by default — {@code PickupGatedToolsTest.unknownWaits});</li>
 *   <li>a worker is given no built-in that can act (only {@code ToolSearch}, which loads schemas);</li>
 *   <li>a worker is given no MCP server but ours, on either harness — a second server's tools would
 *       never reach {@code McpProtocol}, so nothing could park them.</li>
 * </ol>
 */
class PickupRatchetCoverageTest {

    /**
     * Tools that may run BEFORE the child's wish has been safety-checked. Each must neither put a word
     * in front of the child nor change the world. Adding one is a safety decision: say why it is pure
     * reading in its own line here, and add a case to PickupRatchetGameTest if it reads anything a
     * child typed. (verify_datapack is deliberately absent: `run: true` executes a pack.)
     */
    private static final Set<String> FROZEN_READ_ONLY = Set.of(
            "await_wish",                        // takes the wish; the ratchet opens here
            "look_around", "find_content", "cbscript_help",
            "find_ready_made", "describe_ready_made",   //: read the ready-made index; say nothing, change nothing
            "list_creatures", "list_builds", "list_items", "list_paintings", "list_events",
            "list_templates", "list_wishes", "list_datapacks",
            "inspect_creature", "inspect_build", "inspect_item", "inspect_event", "inspect_painting",
            "inspect_player");

    @Test
    @DisplayName("the read-only list only changes on purpose")
    void theReadOnlyListIsFrozen() {
        assertEquals(FROZEN_READ_ONLY, PickupGatedTools.READ_ONLY,
                "PickupGatedTools.READ_ONLY changed. Every name on it RUNS before the safety verdict "
                + "lands (#1660). If the new name truly only reads, add it to FROZEN_READ_ONLY with why; "
                + "otherwise remove it and let it wait like every other tool.");
    }

    @Test
    @DisplayName("a worker gets no built-in that could act outside the ratchet")
    void noBuiltInButToolSearch() {
        assertEquals("ToolSearch", WorkerArgv.BUILT_INS,
                "A built-in tool runs inside the CLI, never through McpProtocol, so no ratchet can hold "
                + "it until the safety verdict. Serve the capability as a wishwatch tool instead.");
    }

    @Test
    @DisplayName("every MCP config a worker is given names our server and no other")
    void onlyOurMcpServer() throws IOException {
        Pattern servers = Pattern.compile("mcpServers\\\\\":\\{\\\\\"([a-zA-Z0-9_-]+)\\\\\"");
        List<String> seen = new ArrayList<>();
        try (Stream<Path> files = SourceRoots.walk(Path.of("src/main/java"))) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                if (!src.contains("mcpServers")) {
                    continue;
                }
                Matcher m = servers.matcher(src);
                while (m.find()) {
                    seen.add(f.getFileName() + ":" + m.group(1));
                    assertEquals("wishwatch", m.group(1), f + " gives a worker the MCP server '" + m.group(1)
                            + "'. Its tools would never pass McpProtocol, so #1660's ratchet could not hold "
                            + "them until the safety verdict. Serve them through wishwatch instead.");
                    int end = src.indexOf("}}}}", m.end());
                    String rest = end < 0 ? "" : src.substring(m.end(), end);
                    assertTrue(!rest.contains("\\\"command\\\"") && !rest.contains("\\\"type\\\":\\\"stdio"),
                            f + ": a second server rides in the same config");
                }
            }
        }
        assertTrue(seen.size() >= 3, "the config writers were not found, so this test checks nothing: " + seen);
    }
}
