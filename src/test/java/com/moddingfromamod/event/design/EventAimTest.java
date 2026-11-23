package com.moddingfromamod.event.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.place.PlaceResolver;
import com.moddingfromamod.place.PlaceResolverTest;
import org.junit.jupiter.api.Test;

/** Gate A: where an event aimed at a named place happens, per motion. */
class EventAimTest {

    /** Flat ground whose top block is y 63; the hall from PlaceResolverTest stands at 100,64,200. */
    private static int ground(int x, int z) {
        return 63;
    }

    private static EventAim.Aim aim(String where, EventDesign.Motion m) {
        PlaceResolver.Resolved r = PlaceResolver.resolve(PlaceResolverTest.hall(), where, "top");
        return EventAim.of(r, true, m, 104.5, 230.5, 180f, 20, EventAimTest::ground);
    }

    @Test
    void aMeteorAtTheHallLandsOnItsRoof() {
        EventAim.Aim a = aim("", EventDesign.Motion.FALLING_BODY);
        assertEquals(104, a.x());
        assertEquals(69, a.y(), "the roof block (64 + 5): the meteor lands ON the hall");
        assertEquals(204, a.z());
    }

    @Test
    void aroundIsTheGroundBesideItNeverItsRoof() {
        EventAim.Aim a = aim("around", EventDesign.Motion.LAYER);
        assertEquals(104, a.x(), "its middle");
        assertEquals(204, a.z());
        assertEquals(63, a.y(), "a flood rises from the ground round it, not from its roof");
    }

    @Test
    void aroundIsTheTerrainOnTheRingWhateverHeightTheMiddleSpotWasLiftedTo() {
        // A hollow tower's floor lifts its middle spot three blocks; the flood round it must still
        // start on the terrain (measured, the at-e3 grant: it hung in the air).
        PlaceResolver.Resolved r = PlaceResolver.resolve(PlaceResolverTest.hall(), "around", "top");
        PlaceResolver.Resolved lifted = new PlaceResolver.Resolved(r.x(), r.y() + 3, r.z(), r.yaw(), r.ring(),
                r.link(), r.spot(), r.top(), r.why());
        assertEquals(63, EventAim.of(lifted, true, EventDesign.Motion.LAYER, 104.5, 230.5, 180f, 20,
                EventAimTest::ground).y());
    }

    @Test
    void aSideIsTheGroundThereAndARoomIsItsFloor() {
        assertEquals(63, aim("north", EventDesign.Motion.IMPACT_STAMP).y());
        assertEquals(64, aim("inside", EventDesign.Motion.FROM_THE_SKY).y(), "the hall's floor block");
    }

    @Test
    void aWaveIsBornBeyondItAndRollsOverItTowardHim() {
        // He stands south of the hall (z 230), so the wave is born north of it and heads south.
        EventAim.Aim a = aim("", EventDesign.Motion.MOVING_FRONT);
        assertTrue(a.z() < 204 - 15, "born beyond the hall, on the far side from him: " + a);
        double r = Math.toRadians(a.yaw());
        assertEquals(0.0, -Math.sin(r), 1e-6);
        assertEquals(1.0, Math.cos(r), 1e-6, "heading south, toward him");
    }
}
