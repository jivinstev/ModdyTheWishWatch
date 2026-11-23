package com.moddingfromamod.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Where the film's onlooker stands: by the creature's size, beside it, still unless it drifts. */
class FilmOnlookerTest {

    @Test
    void aBiggerCreatureIsWatchedFromFartherAndNeverFromCloserThanFourBlocks() {
        assertEquals(4.0, FilmOnlooker.distance(0.6, 1.0), 1e-9);
        double last = 0;
        for (double h = 0.5; h <= 12; h += 0.5) {
            double d = FilmOnlooker.distance(1.0, h);
            assertTrue(d >= last && d >= 4.0, "distance must grow with size: " + h + " -> " + d);
            last = d;
        }
        // The starter T-rex's box: about ten blocks off, its body filling the middle of the frame.
        assertEquals(8.5, FilmOnlooker.distance(3.2, 5.0), 1e-9);
    }

    @Test
    void heStandsBesideItAtTheHeightOfItsMiddleNeverBelowAChildsEyes() {
        Vec3 at = new Vec3(10, 64, -3);
        Vec3 s = FilmOnlooker.spot(at, 3.2, 5.0, new Vec3(1, 0, 0));
        assertEquals(10 + 8.5, s.x, 1e-9);
        assertEquals(-3, s.z, 1e-9);
        assertEquals(64 + 5.0 * 0.45 - 1.62, s.y, 1e-9);   // his feet, so his EYES are at 0.45 of it
        assertEquals(1.6, FilmOnlooker.eyeHeight(0.4), 1e-9);
    }

    @Test
    void heMovesOnlyWhenTheCreatureHasDriftedAQuarterOfHisDistance() {
        Vec3 spot = new Vec3(0, 64, 0);
        assertFalse(FilmOnlooker.drifted(new Vec3(1.9, 64, 0), spot, 8.0), "a small drift is panned, not walked");
        assertTrue(FilmOnlooker.drifted(new Vec3(2.1, 64, 0), spot, 8.0));
        assertTrue(FilmOnlooker.drifted(new Vec3(0, 66, 0), spot, 8.0), "too far above or below his spot");
    }
}
