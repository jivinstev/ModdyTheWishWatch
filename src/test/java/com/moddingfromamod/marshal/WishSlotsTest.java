package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The A/B seam itself. If this is wrong, every concurrency finding after it is unfalsifiable —
 * "failed at 3, passed at 1" only means something if the flag really moves the number.
 */
class WishSlotsTest {

    @AfterEach
    void reset() {
        WishSlots.override(null);
        System.clearProperty(WishSlots.PROPERTY);
        System.clearProperty(WishSlots.CAP_PROPERTY);
    }

    @Test
    void theCameraOnTheVmCapsTenAtSix() {
        // A world's manifest may ask for 10; while its camera runs on its own
        // machine the launcher passes the manifest's camera-on-VM number as a CEILING.
        System.setProperty(WishSlots.PROPERTY, "10");
        System.setProperty(WishSlots.CAP_PROPERTY, "6");
        assertEquals(6, WishSlots.slots(), "10 asked, 6 allowed while the camera shares the VM");
        System.clearProperty(WishSlots.CAP_PROPERTY);
        assertEquals(10, WishSlots.slots(), "camera off the VM (no cap passed): the full 10");
    }

    @Test
    void theCapIsACeilingNeverAFloor() {
        System.setProperty(WishSlots.PROPERTY, "3");
        System.setProperty(WishSlots.CAP_PROPERTY, "6");
        assertEquals(3, WishSlots.slots(), "a cap above the asked number changes nothing");
    }

    @Test
    void anUnreadableCapIsNoCapAndAnOutOfRangeOneIsClamped() {
        assertEquals(WishSlots.MAX, WishSlots.cap(null));
        assertEquals(WishSlots.MAX, WishSlots.cap(" "));
        assertEquals(WishSlots.MAX, WishSlots.cap("six"));
        assertEquals(1, WishSlots.cap("0"), "a cap of nothing would grant nothing: one, never zero");
        assertEquals(WishSlots.MAX, WishSlots.cap("99"));
        assertEquals(6, WishSlots.cap(" 6 "));
    }

    @Test
    void theDefaultGrantsSeveralAtOnce() {
        // This was 1 for the whole of development, so that every gate written before concurrency
        // existed stayed green. It then STAYED 1 after concurrency was proven, which meant every
        // parallel measurement came from a harness passing wishslots=2 while real play was always
        // serial — Steve made three wishes at once, watched them come out one by one, and asked why.
        // Then 3, and since D10 10 — the pool keeps one spare, so the default is a ceiling
        // on how many can be busy, not on how many sit idle.
        assertEquals(10, WishSlots.DEFAULT);
        assertEquals(10, WishSlots.slots(), "out of the box, without any flag");
        assertFalse(WishSlots.serial(), "a child firing three wishes must not be granted them in a queue");
    }

    @Test
    void thePropertyMovesIt() {
        System.setProperty(WishSlots.PROPERTY, "3");
        assertEquals(3, WishSlots.slots());
        assertFalse(WishSlots.serial());
    }

    @Test
    void itIsClampedToTheDocumentedRange() {
        System.setProperty(WishSlots.PROPERTY, "99");
        assertEquals(WishSlots.MAX, WishSlots.slots(), "the pool has a ceiling and it is honoured");
        System.setProperty(WishSlots.PROPERTY, "0");
        assertEquals(1, WishSlots.slots(), "zero slots would grant nothing at all");
        System.setProperty(WishSlots.PROPERTY, "-4");
        assertEquals(1, WishSlots.slots());
    }

    @Test
    void aTypoFallsBackToTheDefaultRatherThanToTheCeiling() {
        // The property this has always been about: a mistyped flag must never silently buy MORE
        // concurrency than anyone asked for. It used to read "falls back to serial" because the
        // default happened to be 1; the guarantee is about the ceiling, not about the number.
        System.setProperty(WishSlots.PROPERTY, "three");
        try {
            // Since D10 the default IS the ceiling (10/10), so "not the maximum" can no longer be
            // the check. The guarantee it stood for still holds: a typo buys exactly what a server
            // with no flag at all runs, never more.
            assertEquals(WishSlots.DEFAULT, WishSlots.slots(),
                    "a typo must fall back to what an unflagged server runs, never above it");
        } finally {
            System.clearProperty(WishSlots.PROPERTY);
        }
    }

    @Test
    void anOverrideTakesEffectImmediatelyAndCanBeRemoved() {
        System.setProperty(WishSlots.PROPERTY, "2");
        WishSlots.override(3);
        assertEquals(3, WishSlots.slots(), "an override beats the property");
        WishSlots.override(null);
        assertEquals(2, WishSlots.slots(), "and removing it restores the property, not a stale value");
    }
}
