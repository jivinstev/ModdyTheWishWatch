package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 *. Every case here is taken from the five real grants that produced three rival templates for
 * one wish, so the rule is measured against what actually happened rather than against invented
 * inputs.
 */
class TemplateOverlapTest {

    // ⚠ THE REAL CHRONOLOGY, not the final file. The first draft of this fixture used the terms
    // learned.json held AFTER all five runs and asserted them against run 2 — but run 5 had
    // REPLACED that template by then, so the test compared a rival to a version of its neighbour
    // that did not exist yet, and failed against correct code. Each constant below is what the
    // named run actually passed to add_template, read off the action log.
    /** Run 1 taught this id. */
    private static final List<String> WISH_V1 =
            List.of("crusher", "crush", "crushes", "grind", "grinder");
    /** Run 2, without calling list_templates first — the first rival. */
    private static final List<String> BUILD_TERMS =
            List.of("crusher", "crush", "grind", "millstone");
    /** Run 3 listed first and correctly re-taught run 1's id, widening it. */
    private static final List<String> WISH_V3 =
            List.of("crush", "crusher", "grind", "millstone");
    /** Run 4, again without listing — the second rival. */
    private static final List<String> CHEST_TERMS =
            List.of("crusher", "crush", "rock crusher", "millstone");

    private static Map<String, List<String>> existing(Object... pairs) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            List<String> terms = (List<String>) pairs[i + 1];
            m.put((String) pairs[i], terms);
        }
        return m;
    }

    @Test
    @DisplayName("the real second run's rival is caught")
    void theMeasuredRivalIsCaught() {
        // Run 2 taught rock_crusher_chest_build without listing first. It shares crusher/crush/grind
        // with what run 1 had already taught.
        var rivals = TemplateOverlap.rivals("rock_crusher_chest_build", BUILD_TERMS,
                existing("rock_crusher_chest_wish", WISH_V1));
        assertEquals(1, rivals.size());
        assertEquals("rock_crusher_chest_wish", rivals.get(0).id());
        assertEquals(List.of("crusher", "crush", "grind"), rivals.get(0).sharedTerms());
    }

    @Test
    @DisplayName("and the real fourth run's, against BOTH of its predecessors")
    void bothPredecessorsAreNamed() {
        var rivals = TemplateOverlap.rivals("rock_crusher_chest", CHEST_TERMS,
                existing("rock_crusher_chest_wish", WISH_V3,
                         "rock_crusher_chest_build", BUILD_TERMS));
        assertEquals(2, rivals.size());
        // Most-overlapping first, so the agent's eye lands on the one worth widening.
        assertEquals("rock_crusher_chest_build", rivals.get(0).id());
        assertEquals(List.of("crusher", "crush", "millstone"), rivals.get(0).sharedTerms());
    }

    @Test
    @DisplayName("re-teaching the SAME id is the wanted move and is never flagged")
    void reusingAnIdIsNotARival() {
        // Runs 3 and 5 did exactly this after calling list_templates. Flagging it would turn the
        // one correct behaviour into a warning, and the agent would learn to ignore the warning.
        var rivals = TemplateOverlap.rivals("rock_crusher_chest_wish", WISH_V3,
                existing("rock_crusher_chest_wish", WISH_V1));
        assertTrue(rivals.isEmpty());
    }

    @Test
    @DisplayName("id comparison ignores case and surrounding space")
    void idMatchIsForgiving() {
        assertTrue(TemplateOverlap.rivals("  Rock_Crusher_Chest_Wish ", CHEST_TERMS,
                existing("rock_crusher_chest_wish", WISH_V1)).isEmpty());
    }

    @Test
    @DisplayName("a genuinely different wish is left alone")
    void unrelatedTemplatesDoNotCompete() {
        assertTrue(TemplateOverlap.rivals("rainbow_bridge", List.of("rainbow", "bridge"),
                existing("rock_crusher_chest_wish", WISH_V1)).isEmpty());
    }

    @Test
    @DisplayName("terms match on the word, not on its spelling in the call")
    void termsAreNormalised() {
        var rivals = TemplateOverlap.rivals("b", List.of("  CRUSH  ", "Millstone"),
                existing("a", List.of("crush")));
        assertEquals(List.of("crush"), rivals.get(0).sharedTerms());
    }

    @Test
    @DisplayName("nothing to compare is not an overlap")
    void emptyInputsAreSafe() {
        assertTrue(TemplateOverlap.rivals("a", List.of(), existing("b", WISH_V1)).isEmpty());
        assertTrue(TemplateOverlap.rivals("a", WISH_V1, null).isEmpty());
        assertTrue(TemplateOverlap.rivals("a", WISH_V1, existing()).isEmpty());
    }

    @Test
    @DisplayName("the message names the id to reuse, not just the problem")
    void theMessageIsActionable() {
        String msg = TemplateOverlap.describe(TemplateOverlap.rivals(
                "rock_crusher_chest", CHEST_TERMS,
                existing("rock_crusher_chest_wish", WISH_V3,
                         "rock_crusher_chest_build", BUILD_TERMS)));
        assertTrue(msg.contains("rock_crusher_chest_build"), msg);
        assertTrue(msg.contains("rock_crusher_chest_wish"), msg);
        // The fix is one more add_template with that id; a message that omits which id needs a
        // second tool call before it can be acted on.
        assertTrue(msg.contains("add_template"), msg);
        assertTrue(msg.contains("same id replaces"), msg);
        assertTrue(msg.contains("2 existing templates"), msg);
    }

    @Test
    @DisplayName("no rivals says nothing at all")
    void silenceWhenThereIsNothingToSay() {
        assertEquals("", TemplateOverlap.describe(List.of()));
        assertEquals("", TemplateOverlap.describe(null));
    }

    @Test
    @DisplayName("one rival reads as one, not as '1 existing templates'")
    void singularReadsProperly() {
        String msg = TemplateOverlap.describe(TemplateOverlap.rivals(
                "b", BUILD_TERMS, existing("a", WISH_V1)));
        assertTrue(msg.contains("an existing template"), msg);
        assertFalse(msg.contains("1 existing"), msg);
    }
}
