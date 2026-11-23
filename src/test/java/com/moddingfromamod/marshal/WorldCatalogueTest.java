package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gate A for the catalogue the agent is handed on every wish (items 2 and 3).
 *
 * <p>The rules under test are all about what the agent is allowed to conclude from the text. A
 * catalogue that quietly omits things is worse than none at all: the agent reads "he owns these"
 * and decides a wish is for something NEW, which is the exact failure was opened about, only
 * running in the other direction.
 */
class WorldCatalogueTest {

    private static WorldCatalogue.Entry e(String id, String kind, String name, String... aliases) {
        return new WorldCatalogue.Entry(id, kind, name, List.of(aliases));
    }

    @Test
    @DisplayName("a child who owns nothing gets no catalogue, not an empty heading")
    void anEmptyWorldRendersNothing() {
        assertTrue(WorldCatalogue.render(List.of()).isEmpty());
        assertTrue(WorldCatalogue.render(null).isEmpty());
    }

    @Test
    @DisplayName("every thing he owns is named, with its kind and what it answers to")
    void everythingIsListed() {
        var r = WorldCatalogue.render(List.of(
                e("king_kroak", "species", "King Kroak", "spikor", "kroak"),
                e("stone_tower", "build", "stone tower"),
                e("zap_wand", "item", "Zap Wand", "wand")));
        assertEquals(3, r.listed());
        assertEquals(0, r.dropped());
        for (String id : List.of("king_kroak", "stone_tower", "zap_wand")) {
            assertTrue(r.text().contains(id), "the catalogue must name " + id + ":\n" + r.text());
        }
        assertTrue(r.text().contains("spikor"), "an alias is how a child refers to a thing");
        assertTrue(r.text().contains("species") && r.text().contains("build")
                        && r.text().contains("item"),
                "the KIND is what tells a new wish from a refinement");
    }

    @Test
    @DisplayName("it says out loud that this is the whole world, not the nearby part of it")
    void theTextClaimsCompleteness() {
        String text = WorldCatalogue.render(List.of(e("a", "build", "a"))).text();
        assertTrue(text.contains("whole list"),
                "the agent has to know this is not a proximity list, or it will re-derive it: "
                        + text);
        assertTrue(text.contains("NEW"),
                "#136's recorded failure is a new wish read as a modification, so the note must "
                        + "say that deciding between the two is the agent's job: " + text);
    }

    @Test
    @DisplayName("the world #136 actually measured renders whole")
    void theMeasuredWorldIsNotTruncated() {
        // "48 artifacts in his world today; compact catalogue (kind:name + aliases) = 1110
        // chars". The only real world anyone has counted.
        List<WorldCatalogue.Entry> world = new ArrayList<>();
        for (int i = 0; i < 48; i++) {
            world.add(e("artifact_number_" + i, i % 2 == 0 ? "species" : "build",
                    "artifact number " + i, "thing" + i));
        }
        var r = WorldCatalogue.render(world);
        assertEquals(48, r.listed(), "the measured world must render whole");
        assertEquals(0, r.dropped());
    }

    /**
     * <b>NOTHING IS EVER OMITTED, at any size.</b>
     *
     * <p>The first cut carried a 4000-character ceiling with a "… and N more not shown" line when
     * it bit. Both halves were wrong. The number was invented rather than derived — P9's exact
     * prohibition — against a pathological world nobody has ever seen; and a disclosure line does
     * not undo the damage, because the agent that reads a short list and concludes the child is
     * asking for something NEW has committed an earlier issue's recorded failure through the fix for it.
     *
     * <p>Token cost is not the constraint here (277 tokens against a $2.21–$5.29 wish). If a world
     * ever gets big enough to matter, the answer is a measurement and then a decision about WHICH
     * things to leave out — not a character count that drops whatever sorted last.
     */
    @Test
    @DisplayName("a world 80x the measured one is still rendered in full, with nothing elided")
    void nothingIsEverTruncated() {
        List<WorldCatalogue.Entry> huge = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            huge.add(e("thing_" + i, "build", "thing " + i));
        }
        var r = WorldCatalogue.render(huge);
        assertEquals(4000, r.listed(), "every entry is listed");
        assertEquals(0, r.dropped(), "nothing is dropped, at any size");
        assertTrue(r.text().contains("thing_0") && r.text().contains("thing_3999"),
                "the first and last entries must both be present");
        assertFalse(r.text().contains("not shown"), "there is nothing to disclose");
        assertFalse(r.text().contains("…"), "and nothing elided: " + r.text().length() + " chars");
    }

    @Test
    @DisplayName("the order the stores gave is the order rendered, and no recency is claimed")
    void orderIsPreservedAndNotDressedUpAsRecency() {
        var r = WorldCatalogue.render(List.of(
                e("first", "build", "first"), e("second", "build", "second"),
                e("third", "build", "third")));
        assertTrue(r.text().indexOf("first") < r.text().indexOf("second"));
        assertTrue(r.text().indexOf("second") < r.text().indexOf("third"));
        // ArtifactIndex passes a literal 0L for lastTouchedMs on builds, machines, trains and
        // items, so a "most recent" marker here would be authoritative-looking noise on four of
        // the five kinds. Asserted rather than left to a comment: a later well-meaning edit that
        // adds one is a lie the agent cannot detect.
        String lowered = r.text().toLowerCase(java.util.Locale.ROOT);
        assertFalse(lowered.contains("most recent"),
                "nothing here knows what is most recent: " + r.text());
        assertFalse(lowered.contains("oldest"), "nor what is oldest: " + r.text());
    }

    @Test
    @DisplayName("a thing whose name is its id is not printed twice, and neither are its aliases")
    void nothingIsRepeated() {
        String text = WorldCatalogue.render(List.of(
                e("dragon", "species", "dragon", "dragon", "DRAGON", "smaug"))).text();
        assertEquals(1, text.split("dragon", -1).length - 1 + text.split("DRAGON", -1).length - 1,
                "the id, an identical name and case-variant aliases collapse to one: " + text);
        assertTrue(text.contains("smaug"), "a genuinely different alias survives: " + text);
    }
}
