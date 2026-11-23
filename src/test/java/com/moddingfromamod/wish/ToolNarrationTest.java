package com.moddingfromamod.wish;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What to say while the model is thinking. */
class ToolNarrationTest {

    private static final long STALE = ToolNarration.AGENT_LINE_FRESH_MS + 1;

    @Test
    @DisplayName("the model's dead air is narrated — author_datapack is 40% of a wish")
    void theSlowToolsGetLines() {
        for (String tool : new String[]{"author_datapack", "sculpt_creature", "plan_wish",
                                        "build_structure"}) {
            assertTrue(ToolNarration.narratable(tool), tool + " is a slow tool with nothing to say");
            assertNotNull(ToolNarration.lineFor(tool, 5_000, STALE),
                    tool + " said nothing after five seconds of silence");
        }
    }

    @Test
    @DisplayName("a tool that returns in milliseconds is NOT narrated")
    void instantToolsSayNothing() {
        // A line that flickers on and off faster than a child can read it is noise dressed as
        // reassurance. These execute in single-digit milliseconds.
        for (String tool : new String[]{"set_status", "run_command", "give_items", "notify_player",
                                        "list_creatures", "await_wish"}) {
            assertFalse(ToolNarration.narratable(tool), tool + " should not be narrated");
            assertNull(ToolNarration.lineFor(tool, 30_000, STALE));
        }
    }

    @Test
    @DisplayName("THE RULE: the mod stays quiet while the agent's own line is still fresh")
    void aSpecificLineIsNeverTalkedOver() {
        // The agent's words are wish-specific; a generic line replacing a fresh one is a downgrade
        // wearing the clothes of an improvement.
        assertNull(ToolNarration.lineFor("author_datapack", 30_000, 500),
                "the agent spoke half a second ago");
        assertNull(ToolNarration.lineFor("author_datapack", 30_000,
                ToolNarration.AGENT_LINE_FRESH_MS - 1));
        assertNotNull(ToolNarration.lineFor("author_datapack", 30_000, STALE));
    }

    @Test
    @DisplayName("the line MOVES as the wait grows — a frozen status is the bug being fixed")
    void theLineAdvances() {
        // "Measuring his mouth" sitting on screen for 52 seconds is what started this.
        String at3 = ToolNarration.lineFor("author_datapack", 3_000, STALE).doing();
        String at20 = ToolNarration.lineFor("author_datapack", 20_000, STALE).doing();
        String at40 = ToolNarration.lineFor("author_datapack", 40_000, STALE).doing();
        assertNotEquals(at3, at20);
        assertNotEquals(at20, at40);
    }

    @Test
    @DisplayName("HONESTY: no line ever claims progress the mod cannot see")
    void nothingClaimsProgressItCannotObserve() {
        // The mod knows the tool and the elapsed time. It does not know how far through the model
        // is, and saying so would be guardrail L1's exact violation.
        String[] forbidden = {"%", "percent", "almost done", "nearly done", "step ", "half way",
                              "halfway", "finished", "done!"};
        for (String tool : new String[]{"author_datapack", "sculpt_creature", "plan_wish",
                                        "build_structure", "photograph"}) {
            for (long t : new long[]{3_000, 12_000, 25_000, 45_000, 120_000}) {
                var line = ToolNarration.lineFor(tool, t, STALE);
                if (line == null) {
                    continue;
                }
                String said = line.doing().toLowerCase();
                for (String bad : forbidden) {
                    assertFalse(said.contains(bad),
                            tool + " at " + t + "ms claims something it cannot know: " + said);
                }
                assertFalse(line.phase().isBlank(), "every line needs an icon");
            }
        }
    }

    @Test
    @DisplayName("a very long wait keeps the LAST line rather than running out of things to say")
    void itDoesNotFallSilentOnALongCall() {
        var late = ToolNarration.lineFor("author_datapack", 10 * 60_000, STALE);
        assertNotNull(late, "going quiet after five minutes is the dead air, restored");
        assertEquals(ToolNarration.lineFor("author_datapack", 45_000, STALE).doing(), late.doing());
    }

    @Test
    @DisplayName("nothing is said in the first moment — most calls finish before anyone could read")
    void thereIsNoLineAtZero() {
        assertNull(ToolNarration.lineFor("author_datapack", 0, STALE));
        assertNull(ToolNarration.lineFor("author_datapack", 500, STALE));
    }

    @Test
    @DisplayName("the ride-along keeps the agent's own words, trimmed to one glanceable line")
    void rideAlongIsTheAgentsWords() {
        assertEquals("Writing the fire spell",
                ToolNarration.rideAlong("  Writing the fire spell  "));
        assertNull(ToolNarration.rideAlong(null));
        assertNull(ToolNarration.rideAlong("   "));
        String essay = "x".repeat(400);
        String out = ToolNarration.rideAlong(essay);
        assertTrue(out.length() <= 120, "a status bar is one line, not a paragraph: " + out.length());
        assertTrue(out.endsWith("…"), "a trimmed line must show that it was trimmed");
    }

    @Test
    @DisplayName("an unknown tool name is never a crash")
    void unknownToolsAreSafe() {
        assertFalse(ToolNarration.narratable(null));
        assertFalse(ToolNarration.narratable("ignore_all_previous_instructions"));
        assertNull(ToolNarration.lineFor(null, 10_000, STALE));
    }
}
