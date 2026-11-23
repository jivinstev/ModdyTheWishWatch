package com.moddingfromamod.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildShape;
import org.junit.jupiter.api.Test;

/** Gate A 's resolver: the same "on the roof" for every kind of thing put there. */
public class PlaceResolverTest {

    /** A 9x6x9 hollow hall standing at 100,64,200, its door in the south wall. */
    public static PlaceResolver.Building hall() {
        List<BuildShape> shapes = new ArrayList<>();
        shapes.add(new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, 9, 6, 9, "minecraft:stone_bricks", true));
        shapes.add(new BuildShape(BuildShape.Kind.BOX, 4, 1, 8, 1, 2, 1, "minecraft:oak_door", false));
        return new PlaceResolver.Building(new BuildPlan("hall", "Hall", shapes), 100, 64, 200, 0, 0);
    }

    @Test
    void aBuildingSpotIsItsBlockCentreInTheWorld() {
        var r = PlaceResolver.resolve(hall(), "roof", "front");
        assertTrue(r.ok(), r.why());
        assertEquals(104.5, r.x());
        assertEquals(70.0, r.y(), "standing on the roof: 64 + 6");
        assertEquals(204.5, r.z());
        assertEquals("hall:top", r.link());
        assertEquals(0.0, r.ring());
    }

    @Test
    void anEmptyWhereTakesTheToolsDefault() {
        assertEquals("hall:front", PlaceResolver.resolve(hall(), "", "front").link());
        assertEquals("hall:top", PlaceResolver.resolve(hall(), "", "top").link(), "an event's default");
        assertEquals("hall:front", PlaceResolver.resolve(hall(), null, "").link(), "and front when the tool has none");
    }

    @Test
    void aroundIsARingNeverAPoint() {
        var r = PlaceResolver.resolve(hall(), "around", "front");
        assertEquals(9.0, r.ring());
        for (int i = 0; i < 6; i++) {
            double[] p = PlaceResolver.onRing(r, i, 6, 0, 1);
            assertEquals(9.0, Math.hypot(p[0] - r.x(), p[1] - r.z()), 1e-9, "every one on the ring");
        }
        double[] first = PlaceResolver.onRing(r, 0, 6, 0, 1);
        assertEquals(r.z() + 9.0, first[1], 1e-9, "the first on HIS side: start yaw 0 is south");
        assertEquals(0.0, first[2], 1e-9, "facing out");
    }

    @Test
    void anUnknownSpotSaysWhatTheBuildingHas() {
        var r = PlaceResolver.resolve(hall(), "dungeon", "front");
        assertFalse(r.ok());
        assertTrue(r.why().contains("no spot called 'dungeon'") && r.why().contains("inside"), r.why());
    }

    /** Him: standing at 0,64,0 looking south (yaw 0), 0.6 wide and 1.8 tall. */
    static PlaceResolver.Body me() {
        return new PlaceResolver.Body("me", true, 0, 64, 0, 0f, 0.6, 1.8);
    }

    @Test
    void inFrontOfMeIsAheadOfHimFacingHim() {
        var r = PlaceResolver.resolve(me(), "front", "front");
        assertEquals(0.0, r.x(), 1e-9);
        assertEquals(1.8, r.z(), 1e-9, "half his width plus the gap, the way he looks (south)");
        assertEquals(180f, Math.abs(r.yaw()), 1e-4, "facing back at him");
        assertEquals("me:front", r.link());
    }

    @Test
    void besideMeIsOnHisRightBehindIsBehind() {
        var beside = PlaceResolver.resolve(me(), "next_to", "front");
        assertEquals(-1.8, beside.x(), 1e-9, "looking south, his right hand is west");
        var behind = PlaceResolver.resolve(me(), "behind", "front");
        assertEquals(-1.8, behind.z(), 1e-9);
        var top = PlaceResolver.resolve(me(), "on_top", "front");
        assertEquals(65.8, top.y(), 1e-9, "on his head");
    }

    @Test
    void aCreatureHasNoInside() {
        var r = PlaceResolver.resolve(new PlaceResolver.Body("dragon", false, 0, 64, 0, 90f, 3, 4), "inside", "front");
        assertFalse(r.ok());
        assertTrue(r.why().contains("only a BUILDING has an inside"), r.why());
    }

    /** A column whose blocks are solid at exactly these heights. */
    private static java.util.function.IntPredicate column(int... solid) {
        java.util.Set<Integer> s = new java.util.HashSet<>();
        for (int y : solid) {
            s.add(y);
        }
        return s::contains;
    }

    @Test
    void anOutsideSpotIsLiftedOutOfTheGroundButNeverDroppedOffAnEdge() {
        // ground: solid up to 63, so 64 is the first free block
        java.util.function.IntPredicate ground = y -> y <= 63;
        assertEquals(64.0, PlaceFinder.feet(60, ground), "foundations: feet buried at 60 are lifted to 64");
        assertEquals(64.0, PlaceFinder.feet(64, ground), "already on the ground: unchanged");
        assertEquals(64.0, PlaceFinder.feet(66, ground), "a doorstep down onto the ground");
        assertEquals(72.0, PlaceFinder.feet(72, ground), "a raised hall's front keeps its door's height (8 above)");
        assertEquals(64.0, PlaceFinder.feet(64, column(63, 70, 71)),
                "something OVERHEAD (a tree, an overhang, another build) never lifts a spot that is free");
        assertEquals(66.0, PlaceFinder.feet(64, column(63, 64, 65)), "a low wall in the way: over it");
        assertEquals(100.0, PlaceFinder.feet(100, ground), "a sky castle's front stays at its door");
    }

    @Test
    void aHerdOnAPointStandsRoundIt() {
        var r = PlaceResolver.resolve(hall(), "inside", "front");
        double[] a = PlaceResolver.onRing(r, 0, 3, 0, 1);
        double[] b = PlaceResolver.onRing(r, 1, 3, 0, 1);
        assertEquals(r.x(), a[0]);
        assertEquals(1.0, Math.hypot(b[0] - a[0], b[1] - a[1]), 1e-9);
    }
}
