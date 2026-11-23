package com.moddingfromamod.vanilla;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #515b's rule and its ledger, without a game.
 *
 * <p>The rule used to be tested as a comparison against {@code PackAudience}, the pack rig's push
 * rule, so the two could not drift. deleted the push, so the rule is tested here directly.
 */
class RigVeilTest {

    @BeforeEach
    void clean() {
        RigVeil.forget();
    }

    @Test
    @DisplayName("the whole truth table: hidden only from a stock client, while a camera looks, before the reveal")
    void theTruthTable() {
        // Written out rather than restated as a formula: a formula here would be the same code
        // twice, and agree with itself whatever it said. Moved from PackAudienceTest.
        for (boolean modded : List.of(true, false)) {
            for (boolean camera : List.of(true, false)) {
                for (boolean revealed : List.of(true, false)) {
                    boolean expect = !modded && camera && !revealed;
                    assertEquals(expect, RigVeil.hiddenFrom(modded, camera, revealed),
                            "modded=" + modded + " camera=" + camera + " revealed=" + revealed);
                }
            }
        }
    }

    @Test
    @DisplayName("revealed is a fact about ONE reveal state, not a boolean for ever")
    void revealedIsAboutOneState() {
        String a = "a97c50d31529b0510fff6f756e2a47f750effb36";
        String b = "b395e98e4144de8f91d5dfbc9c89e1e821d2b3b9";
        assertTrue(RigVeil.alreadyRevealed(a, a));
        assertFalse(RigVeil.alreadyRevealed(b, a), "a NEW sculpt after a reveal must be hidden again");
        assertFalse(RigVeil.alreadyRevealed(a, null));
        assertFalse(RigVeil.alreadyRevealed(null, a));
        assertFalse(RigVeil.alreadyRevealed("", a));
        assertTrue(RigVeil.alreadyRevealed(a.toUpperCase(java.util.Locale.ROOT), a));
    }

    @Test
    @DisplayName("only a withheld stock client is hidden from")
    void theCameraAndTheLoneChildBothSeeIt() {
        // The camera: modded, so always shown -- it is the client that has to photograph it.
        assertFalse(RigVeil.hiddenFrom(true, true, false));
        // The CHILDS_EYES arm: no camera serving, so the child IS the viewpoint and must see it.
        assertFalse(RigVeil.hiddenFrom(false, false, false));
        // After the reveal nobody is hidden from, whatever else is true.
        assertFalse(RigVeil.hiddenFrom(false, true, true));
        // The one case the feature exists for.
        assertTrue(RigVeil.hiddenFrom(false, true, false));
    }

    @Test
    @DisplayName("the ledger accumulates across sculpts and clears in one step")
    void theLedgerSpansEveryInterimSculpt() {
        assertFalse(RigVeil.isVeiling());
        RigVeil.veil(List.of(7, 8, 9), 100L);
        RigVeil.veil(List.of(9, 10), 140L);   // a re-sculpt: overlapping, and the repeat is free
        assertTrue(RigVeil.isVeiling());
        assertArrayEquals(new int[] { 7, 8, 9, 10 }, RigVeil.veiled(),
                "insertion order, and no duplicates -- the reveal walks this list once per part");

        var freed = RigVeil.unveil();
        assertArrayEquals(new int[] { 7, 8, 9, 10 },
                freed.keySet().stream().mapToInt(Integer::intValue).toArray(),
                "unveil must REPORT what it cleared: a reveal that cleared first would re-send "
                        + "nothing, and one that asked first could be raced by a sculpt landing "
                        + "in between and leave a part hidden for ever");
        assertFalse(RigVeil.isVeiling());
        assertEquals(0, RigVeil.veiled().length);
    }

    @Test
    @DisplayName("a re-veil keeps the FIRST tick, so the per-tick sweep cannot age a rig forward")
    void aRepeatDoesNotMoveTheTick() {
        // ATOM-P3c. The reveal reaps every rig of a species older than that species' newest tick,
        // so an id whose tick could drift LATER would make its rig outlive the creature that
        // superseded it -- the residue defect, restored by an accident of bookkeeping. The sweep
        // does not call veil() today; this pins the ledger so it stays safe if it ever does.
        RigVeil.veil(List.of(1, 2), 100L);
        RigVeil.veil(List.of(2, 3), 500L);
        var freed = RigVeil.unveil();
        assertEquals(100L, freed.get(1));
        assertEquals(100L, freed.get(2), "id 2 was first veiled at tick 100 and has not moved");
        assertEquals(500L, freed.get(3));
    }

    @Test
    @DisplayName("the tick rides with the id, so the reveal can tell a re-spawn from a herd")
    void everyIdCarriesTheTickItWasVeiledAt() {
        // A herd -- one command, five rigs, ONE tick -- against a retry: same species, four
        // seconds (80 ticks) apart. Nothing here knows what a herd or a species is; it hands the
        // caller the only fact that can separate them.
        RigVeil.veil(List.of(10, 11), 200L);   // rig A, tick 200
        RigVeil.veil(List.of(12, 13), 200L);   // rig B, same command, same tick
        RigVeil.veil(List.of(14, 15), 280L);   // a retry, 80 ticks later
        var freed = RigVeil.unveil();
        assertEquals(200L, freed.get(10));
        assertEquals(200L, freed.get(13));
        assertEquals(280L, freed.get(15));
        assertEquals(2, freed.values().stream().distinct().count(),
                "two distinct ticks over three rigs -- the herd's two rigs share one");
    }

    @Test
    @DisplayName("a second wish veils again after the first reveal")
    void aLaterWishIsHiddenAgain() {
        RigVeil.veil(List.of(1, 2), 10L);
        RigVeil.unveil();
        RigVeil.veil(List.of(3), 20L);
        assertTrue(RigVeil.isVeiling());
        assertArrayEquals(new int[] { 3 }, RigVeil.veiled(),
                "the ledger must not carry the previous wish's ids into this one -- those "
                        + "entities are long reaped and re-sending them would spawn nothing");
    }

    @Test
    void aReshapeInPlaceIsTheNewestWordAndVeilsNothingNew() {
        // two copies veiled on different ticks; the older one is reshaped in place
        RigVeil.veil(java.util.List.of(1, 2), 100L);
        RigVeil.veil(java.util.List.of(3, 4), 105L);
        RigVeil.restamp(java.util.List.of(1, 2, 99), 110L);
        var ledger = RigVeil.unveil();
        assertEquals(110L, ledger.get(1), "kept displays take the reshape's tick, as a respawn's would");
        assertEquals(105L, ledger.get(3), "the other copy is untouched");
        assertFalse(ledger.containsKey(99), "restamp never veils what was not veiled");
        RigVeil.veil(java.util.List.of(5), 200L);
        RigVeil.restamp(java.util.List.of(5), 150L);
        assertEquals(200L, RigVeil.unveil().get(5), "and never moves a tick backwards");
    }
}
