package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Gate A 's build spots: parse, merge, the default front, the checks, the store shape. */
class BuildSpotsTest {

    /** A 9x6x9 hollow stone hall, a gold throne at its back, an air doorway in the south wall. */
    static BuildPlan hall(boolean door) {
        List<BuildShape> shapes = new ArrayList<>();
        shapes.add(new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, 9, 6, 9, "minecraft:stone_bricks", true));
        shapes.add(new BuildShape(BuildShape.Kind.BOX, 4, 1, 1, 1, 1, 1, "minecraft:gold_block", false));
        if (door) {
            shapes.add(new BuildShape(BuildShape.Kind.BOX, 4, 1, 8, 1, 2, 1, "minecraft:oak_door", false));
        }
        return new BuildPlan("hall", "Hall", shapes);
    }

    @Test
    void atNamesABuildingAndASpot() {
        assertEquals(new BuildSpots.Target("castle", "throne"), BuildSpots.parseAt("castle:throne"));
        assertEquals(new BuildSpots.Target("castle", "throne_room"), BuildSpots.parseAt(" Castle:Throne Room "));
        assertEquals(new BuildSpots.Target("castle", "front"), BuildSpots.parseAt("castle"));
        assertEquals(new BuildSpots.Target("fort~1a2b3c4d", "gate"), BuildSpots.parseAt("fort~1a2b3c4d:gate"));
        assertNull(BuildSpots.parseAt(""));
        assertNull(BuildSpots.parseAt(null));
        assertNull(BuildSpots.parseAt(":throne"));
    }

    @Test
    void spotsRoundTripThroughTheStoreAndAPlanWithoutThemStaysByteIdentical() {
        BuildPlan plain = hall(false);
        assertTrue(!plain.toJson().has("spots"), "a building with no spots writes no spots field");
        BuildPlan seated = plain.withSpots(List.of(new BuildSpot("throne", 4, 2, 1, "south")));
        JsonObject j = seated.toJson();
        BuildPlan back = BuildPlan.fromJson(JsonParser.parseString(j.toString()).getAsJsonObject());
        assertEquals(seated, back);
        assertEquals(List.of(new BuildSpot("throne", 4, 2, 1, "south")), back.spots());
        // and the owner and fork keep them
        assertEquals(seated.spots(), seated.withOwner("abc").spots());
        assertEquals(seated.spots(), seated.forkedAs("hall~1", "abc").spots());
        assertEquals(seated.spots(), seated.withShapes(plain.shapes()).spots());
    }

    @Test
    void anEditKeepsTheSpotsItDoesNotMentionAndMovesTheOnesItNames() {
        List<BuildSpot> had = List.of(new BuildSpot("throne", 4, 2, 1, ""), new BuildSpot("roof", 4, 6, 4, ""));
        List<BuildSpot> merged = BuildSpots.merge(had, List.of(new BuildSpot("throne", 4, 3, 1, "south"),
                new BuildSpot("gate", 4, 1, 10, "")), Set.of("roof"));
        assertEquals(List.of("throne", "gate"), merged.stream().map(BuildSpot::name).toList());
        assertEquals(3, merged.get(0).dy());
    }

    @Test
    void everyBuildingHasAFrontOutsideItsDoor() {
        BuildSpot front = BuildSpots.defaultFront(hall(true), 0, 0);
        assertEquals("front", front.name());
        assertEquals(4, front.dx());
        assertEquals(1, front.dy(), "at the door's own floor");
        assertEquals(8 + BuildSpots.FRONT_GAP, front.dz(), "outside the south wall the door is in");
        assertEquals("south", front.facing());
    }

    @Test
    void withNoDoorTheFrontIsTheWallThatFacesHim() {
        BuildSpot west = BuildSpots.defaultFront(hall(false), -20, 4);
        assertEquals(-BuildSpots.FRONT_GAP, west.dx());
        assertEquals("west", west.facing());
        BuildSpot north = BuildSpots.defaultFront(hall(false), 4, -30);
        assertEquals(-BuildSpots.FRONT_GAP, north.dz());
        BuildSpot nobody = BuildSpots.defaultFront(hall(false), 0, 0);
        assertEquals("south", nobody.facing(), "nobody knows where he stood: the south wall");
        assertEquals(0, nobody.dy(), "at ground level, never under it");
    }

    @Test
    void aNamedFrontReplacesTheDefaultAndUnknownSpotsAreNull() {
        BuildPlan p = hall(true).withSpots(List.of(new BuildSpot("front", 1, 1, 1, "")));
        assertEquals(new BuildSpot("front", 1, 1, 1, ""), BuildSpots.spot(p, "front", 0, 0));
        assertEquals(1, BuildSpots.all(p, 0, 0).stream().filter(s -> s.name().equals("front")).count(),
                "its own front, and no automatic one beside it");
        assertNull(BuildSpots.spot(p, "throne", 0, 0));
        assertNotNull(BuildSpots.spot(hall(true), "front", 0, 0));
        // every building also has the automatic spots, and the words beside/behind.
        assertEquals("front, top, inside, north, east, south, west, around, beside, behind",
                BuildSpots.names(hall(true)));
    }

    @Test
    void aSpotInsideTheSeatIsLiftedOntoItAndSaid() {
        List<String> notes = new ArrayList<>();
        // the gold throne is at 4,1,1; the agent named the block itself, not where the feet go
        List<BuildSpot> out = BuildSpots.checked(hall(false),
                List.of(new BuildSpot("throne", 4, 1, 1, "south")), notes);
        assertEquals(2, out.get(0).dy());
        assertTrue(notes.get(0).contains("INSIDE"), notes.toString());
    }

    @Test
    void aSpotOnTheSeatIsLeftAlone() {
        List<String> notes = new ArrayList<>();
        List<BuildSpot> out = BuildSpots.checked(hall(false),
                List.of(new BuildSpot("throne", 4, 2, 1, "south")), notes);
        assertEquals(2, out.get(0).dy());
        assertTrue(notes.isEmpty(), notes.toString());
    }

    @Test
    void aSpotInMidAirInsideTheHallAndOneFarAwayAreSaid() {
        List<String> notes = new ArrayList<>();
        BuildSpots.checked(hall(false), List.of(new BuildSpot("ghost", 4, 4, 4, ""),
                new BuildSpot("moon", 4, 1, 90, "")), notes);
        assertTrue(notes.stream().anyMatch(n -> n.contains("nothing under it")), notes.toString());
        assertTrue(notes.stream().anyMatch(n -> n.contains("outside the building")), notes.toString());
    }

    @Test
    void theSpotsArrayDropsNamelessRowsAndCapsTheCount() {
        StringBuilder sb = new StringBuilder("[{\"dx\":1},");
        for (int i = 0; i < 30; i++) {
            sb.append("{\"name\":\"s").append(i).append("\",\"dx\":").append(i).append("},");
        }
        sb.append("{\"name\":\"s0\",\"dx\":99}]");
        List<BuildSpot> spots = BuildSpots.parse(JsonParser.parseString(sb.toString()));
        assertEquals(BuildSpots.MAX_SPOTS, spots.size());
        assertTrue(spots.stream().noneMatch(s -> s.name().isEmpty()));
    }

    @Test
    void facingIsAYaw() {
        assertEquals(0f, new BuildSpot("a", 0, 0, 0, "south").yaw());
        assertEquals(180f, new BuildSpot("a", 0, 0, 0, "N").yaw());
        assertNull(new BuildSpot("a", 0, 0, 0, "up").yaw());
    }

    @Test
    void wishspotSplitsItsSpotFromItsCommand() {
        assertEquals(List.of("castle:roof", "summon minecraft:ender_dragon ~ ~ ~"),
                List.of(SpotCommand.split("castle:roof run summon minecraft:ender_dragon ~ ~ ~")));
        assertNull(SpotCommand.split("castle:roof summon x"));
        assertNull(SpotCommand.split("castle:roof run "));
        assertNull(SpotCommand.split(""));
    }
}
