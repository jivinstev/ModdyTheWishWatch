package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** F1: a creature wider than it is tall (a spider) is framed from outside its legs, not on its height alone. */
class FramingWidthTest {

    @Test
    void aCreatureAboutAsWideAsItIsTallIsFramedAsBefore() {
        assertEquals(1.0, Framing.widthScale(2.0, 2.0), 1e-9);
        assertEquals(1.0, Framing.widthScale(2.0, 0.0), 1e-9, "an unknown width changes nothing");
    }

    @Test
    void aWideLowCreatureStandsTheCameraOutsideItsLegs() {
        // The tarantula: about 1.5 blocks tall, legs spanning about 7 across at scale 3.
        double h = 1.5;
        double span = 7.0;
        double dist = OrbitCamera.distanceFor(h) * Framing.widthScale(h, span);
        assertTrue(dist > span / 2 + 1, "the camera stands outside the legs: " + dist);
        assertTrue(Framing.widthScale(h, span) <= 4.0, "and never absurdly far");
    }
}
