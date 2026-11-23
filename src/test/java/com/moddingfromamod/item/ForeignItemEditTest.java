package com.moddingfromamod.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** which of the three routes a start_from that we did not mint should take. */
class ForeignItemEditTest {

    @Test
    @DisplayName("one of our own designs is untouched by any of this")
    void ourOwnDesignsStillMint() {
        // The existing path must not move. Every item the engine made goes on working exactly as
        // it did, whether or not anybody happens to be carrying one.
        assertEquals(ForeignItemEdit.Route.MINT, ForeignItemEdit.decide(true, false, false));
        assertEquals(ForeignItemEdit.Route.MINT, ForeignItemEdit.decide(true, true, true));
    }

    @Test
    @DisplayName("a real item somebody is carrying is EDITED, never copied")
    void theWholePointOfTheIssue() {
        assertEquals(ForeignItemEdit.Route.EDIT_IN_PLACE,
                ForeignItemEdit.decide(false, true, true));
    }

    @Test
    @DisplayName("a real item nobody is carrying is a copy, and must say so")
    void reforgeIsAllowedButNeverSilent() {
        assertEquals(ForeignItemEdit.Route.REFORGE, ForeignItemEdit.decide(false, true, false));
        String n = ForeignItemEdit.reforgeNotice("minecraft:diamond_sword");
        assertTrue(n.contains("NEW item"), "a copy called an edit is the failure being fixed");
        assertTrue(n.contains("loses whatever that mod does in its own code"),
                "the child must be told what a copy costs, or 'it looks correct' is the whole "
                + "problem restated");
        assertTrue(n.contains("wish again"),
                "and the way to get the real edit has to be named, or the notice is just a "
                + "complaint");
    }

    @Test
    @DisplayName("a made-up id is refused with BOTH routes named")
    void aRefusalThatTeaches() {
        assertEquals(ForeignItemEdit.Route.REFUSE, ForeignItemEdit.decide(false, false, false));
        String r = ForeignItemEdit.refusal("bloop_slayer");
        assertTrue(r.contains("inspect_item"), "route one: something we made");
        assertTrue(r.contains("registry id"), "route two: something a mod made");
        assertTrue(r.contains("HOLD it"),
                "and the condition on route two, or the agent retries the same call forever");
        assertTrue(r.contains("do not invent an id"),
                "the original refusal's best line — minting a second item when he asked to change "
                + "the first is the failure this parameter exists to prevent");
    }

    @Test
    @DisplayName("what an edit may promise is bounded, and the bound is a sentence not a caveat")
    void theReachIsStatedToTheAgent() {
        // A component is the source of truth for component-driven properties only, and is ignored
        // by anything a mod computes in Java. That does not argue against editing — a reforged copy
        // cannot reach it either, AND loses it — but it bounds what the note may claim, and
        // is an entire issue about notes claiming more than the mod can support.
        assertTrue(ForeignItemEdit.REACH.contains("keeps everything it could do before"));
        assertTrue(ForeignItemEdit.REACH.contains("do not promise that part is different"),
                "the agent needs the bound as an instruction, not as a fact to infer");
    }
}
