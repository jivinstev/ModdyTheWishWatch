package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/** an earlier issue's decision, without the game: what the rig's outline should be for a carrier's glow. */
class RigGlowTest {

    @Test
    void eitherSourceTurnsItOnAndNeitherIsOff() {
        assertEquals(new RigGlow(true, -1), RigGlow.of(true, false, -1));
        assertEquals(new RigGlow(true, -1), RigGlow.of(false, true, -1));
        assertEquals(new RigGlow(true, -1), RigGlow.of(true, true, -1));
        assertEquals(RigGlow.OFF, RigGlow.of(false, false, -1));
    }

    @Test
    void theTeamColourBecomesTheOverride() {
        assertEquals(new RigGlow(true, 0xFF5555), RigGlow.of(true, false, 0xFF5555));
    }

    /** A team change on a creature that is not glowing must not look like a change. */
    @Test
    void offIgnoresTheColourSoATeamChangeWritesNothing() {
        assertEquals(RigGlow.of(false, false, -1), RigGlow.of(false, false, 0x5555FF));
    }

    @Test
    void aRecolourWhileGlowingIsAChange() {
        assertNotEquals(RigGlow.of(true, false, 0xFF5555), RigGlow.of(true, false, 0x5555FF));
    }

    /** An alpha byte on the input must not make two identical colours differ. */
    @Test
    void onlyTheRgbIsKept() {
        assertEquals(RigGlow.of(true, false, 0x00FF5555), RigGlow.of(true, false, 0xFFFF5555));
    }
}
