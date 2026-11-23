package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tint channel's numbers, checked against vanilla's own arithmetic rather than against a
 * screenshot. Every constant in {@link RigTint} is derived; these tests are the derivation.
 */
class RigTintTest {

    @Test
    @DisplayName("a multiply by NONE changes nothing — that is what makes it 'no tint'")
    void noneIsTheIdentity() {
        for (int rgb : new int[] {0x000000, 0xFFFFFF, 0x3F7FBF, 0xA06540}) {
            assertEquals(rgb, RigTint.multiply(rgb, RigTint.NONE),
                    "white must be the identity multiply for " + Integer.toHexString(rgb));
        }
    }

    @Test
    @DisplayName("the hurt flash reproduces vanilla's GREEN and BLUE exactly")
    void greenAndBlueAreExact() {
        // The whole claim of the emulation: vanilla's overlay is
        //   c' = red*(1-a) + c*a,  and red contributes 0 to g and b,
        // so g and b are a straight multiply by a -- which is what a multiply CAN do.
        for (int rgb : new int[] {0x000000, 0xFFFFFF, 0x3F7FBF, 0x804020, 0x10FF80}) {
            int ours = RigTint.multiply(rgb, RigTint.HURT_FLASH);
            int vanilla = RigTint.vanillaHurt(rgb);
            assertEquals((vanilla >> 8) & 0xFF, (ours >> 8) & 0xFF, 1,
                    "green must match vanilla for " + Integer.toHexString(rgb));
            assertEquals(vanilla & 0xFF, ours & 0xFF, 1,
                    "blue must match vanilla for " + Integer.toHexString(rgb));
        }
    }

    @Test
    @DisplayName("...and the RED shortfall is the whole of the gap, zero on an already-red creature")
    void redIsTheOnlyGap() {
        // A multiply cannot brighten, so it cannot add red. State the size of that rather than
        // calling the row 'an emulation' and leaving it there.
        assertEquals(0f, RigTint.redShortfall(1f), 1e-6,
                "a fully red creature loses nothing -- there is no red left to add");
        assertEquals(0.302f, RigTint.redShortfall(0f), 0.001f,
                "a creature with no red at all loses the full overlay contribution");
        int white = 0xFFFFFF;
        assertEquals(0xFF, (RigTint.multiply(white, RigTint.HURT_FLASH) >> 16) & 0xFF,
                "red is passed through untouched");
    }

    @Test
    @DisplayName("the flash is on for the WHOLE death, not just the killing blow")
    void dyingIsRedThroughout() {
        assertEquals(RigTint.NONE, RigTint.hurtFlash(0, 0), "an unharmed creature is untinted");
        assertEquals(RigTint.HURT_FLASH, RigTint.hurtFlash(9, 0), "hurtTime alone flashes");
        for (int t = 1; t <= RigFlop.DEATH_TICKS; t++) {
            assertEquals(RigTint.HURT_FLASH, RigTint.hurtFlash(0, t),
                    "deathTime " + t + " must still be red -- vanilla's condition is "
                            + "hurtTime > 0 || deathTime > 0, so a body is red all the way down");
        }
    }

    @Test
    @DisplayName("undyed leather is never a colour we leave in place")
    void theLeatherDefaultIsNeverShipped() {
        // The trap this guards: a dyeable carrier with tintindex 0 and NO dyed_color component is
        // multiplied by 0xA06540. The bug would be in the ABSENCE of a component.
        assertNotEquals(RigTint.LEATHER_DEFAULT, RigTint.NONE);
        int grey = 0x808080;
        assertTrue(RigTint.multiply(grey, RigTint.LEATHER_DEFAULT) != grey,
                "the leather default is a real colour shift, which is why NONE has to be written");
    }

    @Test
    @DisplayName("the inventions are labelled as inventions and are all distinct")
    void theInventionsAreDistinct() {
        int[] all = {RigTint.POISON, RigTint.WITHER, RigTint.FROST, RigTint.HURT_FLASH,
                RigTint.NONE};
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                assertNotEquals(all[i], all[j], "two states must not share a colour");
            }
        }
    }
}
