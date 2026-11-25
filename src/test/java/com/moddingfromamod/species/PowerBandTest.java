package com.moddingfromamod.species;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** {@link PowerBand}: the size bands, their edges and defaults (#99, #100). */
class PowerBandTest {

    @Test
    void bandsRunSmallestToLargestWithNoGapAndDefaultsInsideTheirRanges() {
        double from = -1;
        double lastHealth = 0;
        for (PowerBand.Band b : PowerBand.BANDS) {
            assertTrue(b.fromVolume() > from, b.name() + " starts after the band before it");
            assertTrue(b.healthFits(b.health()) && b.damageFits(b.damage()), b.name() + "'s default is in its range");
            assertTrue(b.health() > lastHealth, b.name() + " is tougher than the band before it");
            from = b.fromVolume();
            lastHealth = b.health();
        }
        assertEquals(0.0, PowerBand.BANDS.get(0).fromVolume(), "the smallest band starts at nothing");
    }

    @Test
    void eachEdgeBelongsToTheBandItStarts() {
        assertEquals("tiny", PowerBand.forVolume(0).name());
        assertEquals("tiny", PowerBand.forVolume(0.2999).name());
        assertEquals("small", PowerBand.forVolume(0.3).name());
        assertEquals("small", PowerBand.forVolume(0.9999).name());
        assertEquals("medium", PowerBand.forVolume(1.0).name());
        assertEquals("large", PowerBand.forVolume(3.0).name());
        assertEquals("huge", PowerBand.forVolume(15.0).name());
        assertEquals("huge", PowerBand.forVolume(1e9).name());
    }

    @Test
    void aVolumeThatCannotBeReadIsSmall() {
        assertEquals("small", PowerBand.forVolume(Double.NaN).name());
        assertEquals("small", PowerBand.forVolume(-1).name());
        assertEquals("small", PowerBand.forVolume(Double.POSITIVE_INFINITY).name());
    }

    @Test
    void volumeIsTheBoxesInBlocksTimesScaleCubed() {
        // one 16-pixel cube is one block; twice the size is eight
        List<double[]> cube = List.<double[]>of(new double[] {0, 0, 0, 16, 16, 16});
        assertEquals(1.0, PowerBand.volume(cube, 1.0), 1e-9);
        assertEquals(8.0, PowerBand.volume(cube, 2.0), 1e-9);
        // boxes written either way round count the same; no scale reads as 1
        assertEquals(1.0, PowerBand.volume(List.<double[]>of(new double[] {16, 16, 16, 0, 0, 0}), 0), 1e-9);
        assertEquals(0.0, PowerBand.volume(null, 1.0), 1e-9);
    }

    @Test
    void aBossHasMoreHealthAndAVehicleNoHit() {
        PowerBand.Band huge = PowerBand.forVolume(100);
        assertEquals(huge.health() * PowerBand.BOSS_HEALTH, PowerBand.defaultHealth(huge, true), 1e-9);
        assertEquals(huge.health(), PowerBand.defaultHealth(huge, false), 1e-9);
        assertTrue(PowerBand.defaultHealth(huge, true) <= Species.MAX_HEALTH);
        assertEquals(0.0, PowerBand.defaultDamage(huge, true), 1e-9);
        assertEquals(huge.damage(), PowerBand.defaultDamage(huge, false), 1e-9);
    }

    @Test
    void theAnchorsSitInTheBandsTheirBodiesWouldBeIn() {
        // a zombie's body holds about half a cubic block: small, 18 health 3 damage; a ravager's about five: large
        PowerBand.Band zombie = PowerBand.forVolume(0.5);
        assertTrue(zombie.healthFits(20) && zombie.damageFits(3), "a zombie (20/3) fits " + zombie.describe());
        PowerBand.Band ravager = PowerBand.forVolume(5);
        assertTrue(ravager.healthFits(100) && ravager.damageFits(12), "a ravager (100/12) fits " + ravager.describe());
    }
}
