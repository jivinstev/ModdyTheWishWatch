package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The confidence bar for granting a wish offline.
 *
 * <p>Every "misfires" case below is a real thing the watch used to do. The engine asked
 * {@code hay.contains(term)}, so one incidental letter-sequence inside a sentence was treated as
 * certainty: a child who asked for a knight got a spooky night, and one who asked for Spikor's
 * airship was simply made to fly. Nine of ten realistic phrases misfired against the shipped
 * templates alone.
 *
 * <p>Those templates are deleted from the jar and survive here only as
 * {@link OldBuiltInsFixture}: the matcher is unchanged, and they are what it was measured against.
 *
 * <p>The rule these tests pin down is asymmetric on purpose. Sending a good wish to the agent costs
 * a few seconds; granting the wrong wish costs his trust — and because the agent caches its answers
 * as templates, a wrong answer can be served instantly and wrongly forever after. So when in doubt,
 * escalate.
 */
class WishConfidenceTest {

    /** The deleted built-ins, kept as the matcher's fixture (the matcher itself is). */
    private static final WishTemplates TEMPLATES = OldBuiltInsFixture.templates();

    private static boolean confident(String wish) {
        return TEMPLATES.matchConfident(WishText.sanitize(wish)).isPresent();
    }

    private static String grantedBy(String wish) {
        return TEMPLATES.matchConfident(WishText.sanitize(wish))
                .map(WishTemplates.Confident::templateId).orElse(null);
    }

    // ===================== the bugs this exists to prevent =====================

    @Test
    @DisplayName("a word that merely CONTAINS a template term is not that wish")
    void substringsDoNotMatch() {
        // Each of these granted the named template before whole-word matching.
        assertFalse(confident("Create a Spikor's airship flying in the sky"), "flying != fly");
        assertFalse(confident("build me a knight in shining armour"), "knight != night");
        assertFalse(confident("can we have breakfast at a big table"), "breakfast != fast");
        assertFalse(confident("build a cathedral with stained glass"), "cathedral != cat");
        assertFalse(confident("a catapult that launches pumpkins"), "catapult != cat");
        assertFalse(confident("i want a butterfly garden"), "butterfly != fly");
        assertFalse(confident("make a dragonfly"), "dragonfly != fly");
        assertFalse(confident("make a flying carpet"), "flying != fly");
    }

    @Test
    @DisplayName("a real keyword is still not enough when the wish is about something else")
    void unexplainedWordsBlockAMatch() {
        // "treasure" IS in this wish as a whole word, so whole-word matching alone would grant it.
        // What makes it wrong is everything else: a map, a secret, an island.
        assertFalse(confident("i want a treasure map to a secret island"));
        assertFalse(confident("a chicken that lays diamond eggs on the moon"));
    }

    @Test
    @DisplayName("a wish that points at something already built is never confident")
    void addingToAnExistingThingEscalates() {
        // The lighthouse case: this used to rebuild a whole new castle.
        assertFalse(confident("that's a cool castle in the sky, can you add a lighthouse on top"));
    }

    // ===================== what must STILL be instant =====================

    @Test
    @DisplayName("a template's own wishes grant offline")
    void aTemplatesOwnWordsGrant() {
        assertEquals("rain_chickens", grantedBy("make it rain chickens"));
        assertEquals("treasure", grantedBy("give me treasure"));
        assertEquals("super_fast", grantedBy("make me super fast"));
        assertEquals("night_spooky", grantedBy("make it night and spooky"));
        assertEquals("tower", grantedBy("build me a tower"));
        assertEquals("fly", grantedBy("make me fly"));
    }

    @Test
    @DisplayName("every preset button's text is confidently granted")
    void everyPresetIsConfident() {
        // A button must never escalate: the child tapped a shipped wish, and waiting ten seconds
        // for something the watch already knows would be a plain regression.
        for (WishCatalog.Preset p : OldBuiltInsFixture.PRESETS) {
            assertTrue(confident(p.wishText()),
                    "preset button '" + p.label() + "' (" + p.wishText() + ") must grant offline");
        }
    }

    @Test
    @DisplayName("plurals and a child's phrasing still match")
    void pluralsAndPhrasingSurvive() {
        assertEquals("rain_chickens", grantedBy("make it rain chicken"));
        assertEquals("zombies", grantedBy("spawn a zombie"));
        assertEquals("diamonds", grantedBy("give me diamonds please"));
        assertEquals("cats", grantedBy("i want some kittens"));
    }

    @Test
    @DisplayName("a countable wish keeps its number")
    void numbersDoNotBlockAMatch() {
        // "30" must not read as an unexplained word, or counting wishes would all escalate.
        assertEquals("rain_chickens", grantedBy("make it rain 30 chickens"));
    }

    // ===================== the hint handed to the agent =====================

    @Test
    @DisplayName("the loose guess survives as a HINT, with its unexplained words")
    void hintReportsWhyItWasNotTrusted() {
        var hint = TEMPLATES.hint(WishText.sanitize("Create a Spikor's airship flying in the sky"));
        assertTrue(hint.isPresent(), "the loose matcher still has a guess to offer");
        assertEquals("fly", hint.get().templateId());
        assertTrue(hint.get().unexplained().contains("spikor"),
                "the agent should be told WHAT the template couldn't explain: "
                        + hint.get().unexplained());
        assertTrue(hint.get().unexplained().contains("airship"));
    }

    @Test
    @DisplayName("the loose matcher is unchanged, so the offline fallback still answers")
    void looseMatchingStillWorks() {
        // When no agent can be reached we fall back to this deliberately generous matcher —
        // a best guess beats a dead watch. It must keep its old (wrong, but useful) behaviour.
        assertTrue(TEMPLATES.matchDetailed(
                WishText.sanitize("Create a Spikor's airship flying in the sky")).isPresent());
    }
}
