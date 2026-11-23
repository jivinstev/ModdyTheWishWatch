package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * <b>A pronoun is not the only way to point at something.</b>
 *
 * <p>Found by a real client test rather than by reasoning. {@code "make the stone tower bigger"} has
 * no pronoun, so it was handed to the offline template matcher, which matched on the word "tower"
 * and <b>built a second tower</b> — the exact failure {@link WishReference} exists to prevent,
 * arriving through the one door it was not watching. The ladder resolved it perfectly and never ran,
 * because the wish had already been granted.
 *
 * <p>The rule needs BOTH halves: naming a thing he owns, AND a word that changes it.
 */
class WishReferenceChangeTest {

    @Test
    void aChangeWordIsRecognisedWithoutAPronoun() {
        assertTrue(WishReference.mentionsAChange("make the stone tower bigger"));
        assertTrue(WishReference.mentionsAChange("paint the castle red"));
        assertTrue(WishReference.mentionsAChange("add a lighthouse to the castle"));
    }

    @Test
    void aBrandNewWishIsNotAChange() {
        // The other half of the rule lives in WishService (does it NAME something he owns?), but
        // these must not even reach it: a wish that changes nothing can always be served offline.
        assertFalse(WishReference.mentionsAChange("build me a castle"));
        assertFalse(WishReference.mentionsAChange("i want a giant robot buddy that stomps around"));
        assertFalse(WishReference.mentionsAChange("make it rain chickens"),
                "the flagship built-in wishes must stay instant — 'rain' and 'chickens' name a "
                        + "thing to do, not a change to something existing");
    }

    @Test
    void theOldPronounPathStillWorks() {
        assertTrue(WishReference.isContextual("make it bigger"));
        assertTrue(WishReference.isContextual("add a lighthouse on top of it"));
        assertFalse(WishReference.isContextual("make it rain chickens"),
                "three of the most-used built-ins contain 'it' and are not contextual at all");
    }

    @Test
    void everyWayAChildSaysDestroyReachesTheResolver() {
        // These skipped the resolver entirely: mentionsAChange knew only "remove" and "delete",
        // while WishVerb classes twenty words as irreversible. So the ladder's ASK rung — which
        // exists precisely to stop before doing something undoable to the wrong thing — could not
        // be reached by any phrasing a child actually uses for it.
        for (String said : new String[]{
                "get rid of the dragon", "destroy my castle", "kill the big one",
                "wipe the tower", "demolish it", "take away the spider",
                "erase the lighthouse", "swap the dragon"}) {
            assertTrue(WishReference.mentionsAChange(said),
                    "'" + said + "' acts on something he already owns, so it must be contextual");
        }
    }

    @Test
    void askingForSomethingNewIsStillNotAChange() {
        // The other half: this must not swallow every wish. Building a new thing names no referent
        // and must keep routing to the offline matcher.
        for (String said : new String[]{
                "make me a dragon", "build me a castle", "i want a lighthouse"}) {
            assertFalse(WishReference.mentionsAChange(said),
                    "'" + said + "' is a brand-new thing, not a change to an old one");
        }
    }
}
