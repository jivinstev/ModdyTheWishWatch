package com.moddingfromamod.provenance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Pure record: JSON round-trips, refinements append without bloating, and the design note sticks. */
class WishProvenanceTest {

    @Test
    void jsonRoundTrip() {
        WishProvenance p = WishProvenance
                .first("ice_bomb", "datapack", "make an ice bomb", "blue dye + snowballs for a "
                        + "wintry look", 123L, "wish-datapacks/ice_bomb")
                .withRefinement("make it bluer");
        assertEquals(p, WishProvenance.fromJson(p.toJson()));
        assertEquals("blue dye + snowballs for a wintry look", p.designNote());
    }

    @Test
    void refinementAppendsAndDedups() {
        WishProvenance p = WishProvenance.first("x", "datapack", "first", "", 0L, "");
        assertEquals(List.of(), p.refinements());

        p = p.withRefinement("first");   // equals the intent -> no-op
        assertEquals(List.of(), p.refinements());

        p = p.withRefinement("second");
        assertEquals(List.of("second"), p.refinements());

        p = p.withRefinement("second");  // duplicate of last -> no-op
        assertEquals(List.of("second"), p.refinements());

        p = p.withRefinement("   ");     // blank -> no-op
        assertEquals(List.of("second"), p.refinements());

        p = p.withRefinement("third");
        assertEquals(List.of("second", "third"), p.refinements());
        assertEquals("first", p.intent(), "intent is never overwritten by a refinement");
    }

    @Test
    void designNoteFirstNonBlankWins() {
        // Empty at first; a later grant that supplies a note fills it in.
        WishProvenance p = WishProvenance.first("y", "build", "a castle", "", 0L, "build.json");
        assertEquals("", p.designNote());
        p = p.withRefinement("taller", "made it taller for drama");
        assertEquals("made it taller for drama", p.designNote(), "a blank note gets filled later");
        assertEquals(List.of("taller"), p.refinements());

        // Once set, a later note does NOT overwrite the original rationale.
        p = p.withRefinement("bluer", "changed my mind, blue");
        assertEquals("made it taller for drama", p.designNote(), "first non-blank note is kept");
        assertEquals(List.of("taller", "bluer"), p.refinements());
    }

    @Test
    void aNoteCanArriveWithNoNewRefinement() {
        // Re-running the same wish (dup prompt) but finally supplying a note still records the note.
        WishProvenance p = WishProvenance.first("z", "species", "make spikor", "", 0L, "sculpt.json");
        p = p.withRefinement("make spikor", "green kroak shell + boss stats");
        assertEquals("green kroak shell + boss stats", p.designNote());
        assertEquals(List.of(), p.refinements(), "a duplicate prompt still doesn't bloat the lineage");
    }
}
