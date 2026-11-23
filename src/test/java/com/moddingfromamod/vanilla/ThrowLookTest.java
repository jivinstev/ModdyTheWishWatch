package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link ThrowLook}: dark sprites get the look of what they are, light ones are left alone. */
class ThrowLookTest {

    @Test
    void aFireballBurnsAndTrailsFlames() {
        for (String id : new String[]{"minecraft:fire_charge", "minecraft:blaze_powder", "minecraft:magma_cream",
                "minecraft:blaze_rod", "fire_charge"}) {
            ThrowLook.Look l = ThrowLook.of(id);
            assertTrue(l.burning(), id);
            assertEquals(ThrowLook.Trail.FLAME, l.trail(), id);
        }
    }

    @Test
    void aWindChargeIsNotAFire() {
        assertEquals(ThrowLook.Look.PLAIN, ThrowLook.of("minecraft:wind_charge"));
    }

    @Test
    void anEnderPearlShimmers() {
        ThrowLook.Look l = ThrowLook.of("minecraft:ender_pearl");
        assertFalse(l.burning());
        assertEquals(ThrowLook.Trail.PORTAL, l.trail());
        assertEquals(ThrowLook.Trail.PORTAL, ThrowLook.of("minecraft:ender_eye").trail());
    }

    @Test
    void redstoneTrailsRedDust() {
        ThrowLook.Look l = ThrowLook.of("minecraft:redstone");
        assertEquals(ThrowLook.Trail.DUST, l.trail());
        assertTrue(((l.rgb() >> 16) & 0xFF) > 0xC0, "red");
    }

    @Test
    void lightBallsAndCoalKeepTheirOwnLook() {
        for (String id : new String[]{"minecraft:snowball", "minecraft:egg", "minecraft:slime_ball",
                "minecraft:coal", "", "minecraft:honeycomb"}) {
            assertEquals(ThrowLook.Look.PLAIN, ThrowLook.of(id), id);
        }
        assertEquals(ThrowLook.Look.PLAIN, ThrowLook.of(null));
    }
}
