package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** an earlier issue's decision, without the game: the light a rig is drawn in, and when it is resent. */
class RigLightTest {

    /** {@code EntityRenderer.getBlockLightLevel}: a burning mob is drawn at block light 15. */
    @Test
    void aBurningCarrierIsDrawnFullyBlockLitAsVanillaDrawsABurningMob() {
        assertEquals(new RigLight(15, 4), RigLight.of(2, 4, true));
        assertEquals(new RigLight(2, 4), RigLight.of(2, 4, false));
    }

    @Test
    void levelsAreClampedToWhatALightLevelCanBe() {
        assertEquals(new RigLight(0, 15), RigLight.of(-1, 16, false));
    }

    /** The first call for a rig always writes: an adopted rig's saved override may be stale. */
    @Test
    void theFirstLookAlwaysWrites() {
        assertEquals(new RigLight(0, 15), RigLight.next(null, new RigLight(0, 15)));
    }

    /** A creature standing still in steady light costs nothing, every cycle after the first. */
    @Test
    void anUnchangedLightWritesNothing() {
        assertNull(RigLight.next(new RigLight(7, 0), new RigLight(7, 0)));
    }

    /** The bug: a room lit after the creature appeared in it. */
    @Test
    void aRoomLitAfterTheCreatureAppearedIsAChange() {
        assertEquals(new RigLight(8, 0), RigLight.next(new RigLight(0, 0), new RigLight(8, 0)));
    }

    /** And the other direction: in from the sun, into a dim room. */
    @Test
    void walkingIndoorsIsAChange() {
        assertEquals(new RigLight(2, 0), RigLight.next(new RigLight(0, 15), new RigLight(2, 0)));
    }

    /**
     * Nightfall needs no write: the sky value is the stored LEVEL, which is 15 under open sky at any
     * hour, and the client's lightmap darkens it — as it does for the mob. The cost probe measured
     * 0 writes over a day → night ramp.
     */
    @Test
    void nightfallIsNotAChangeBecauseTheSkyLevelDoesNotMove() {
        RigLight noon = RigLight.of(0, 15, false);
        RigLight midnight = RigLight.of(0, 15, false);
        assertNull(RigLight.next(noon, midnight));
    }

    /** Looked at on the animator's write ticks, so a change rides the pose's packet. */
    @Test
    void itLooksOnTheAnimatorsWriteTicks() {
        int interval = RigAnimator.DEFAULT_INTERVAL;
        for (long t = 0; t < 30; t++) {
            assertEquals(RigAnimator.writesOn(t, interval), RigLight.sampleOn(t, interval), "tick " + t);
        }
        assertTrue(RigLight.sampleOn(0, interval));
        assertFalse(RigLight.sampleOn(1, interval));
    }

    @Test
    void theSeamIsOnByDefault() {
        assertTrue(RigLight.FOLLOW);
    }
}
