package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Gate A for the Builder's geometry — the part that is pure arithmetic and cheap to get exactly right. */
class BuildPlanTest {

    private static BuildShape shape(String json) {
        return BuildShape.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    private static Set<String> positions(BuildShape s) {
        Set<String> out = new HashSet<>();
        s.forEachBlock((x, y, z) -> out.add(x + "," + y + "," + z));
        return out;
    }

    // ===================== box =====================

    @Test
    void aSolidBoxIsEveryBlockInside() {
        BuildShape s = shape("{\"kind\":\"box\",\"width\":3,\"height\":4,\"depth\":5}");
        assertEquals(3 * 4 * 5, s.volume());
    }

    @Test
    void aHollowBoxIsAShellWithARoomInside() {
        BuildShape s = shape("{\"kind\":\"box\",\"width\":5,\"height\":5,\"depth\":5,\"hollow\":true}");
        assertEquals(5 * 5 * 5 - 3 * 3 * 3, s.volume(), "a 5³ shell leaves a 3³ room");
        assertFalse(positions(s).contains("2,2,2"), "the middle must be empty — it's a room");
        assertTrue(positions(s).contains("0,0,0"), "corners are wall");
    }

    @Test
    void hollowIsWhatKeepsABuildingAffordable() {
        // The practical point: agents that forget `hollow` blow the budget on solid rock.
        BuildShape solid = shape("{\"kind\":\"box\",\"width\":40,\"height\":40,\"depth\":40}");
        BuildShape hollow =
                shape("{\"kind\":\"box\",\"width\":40,\"height\":40,\"depth\":40,\"hollow\":true}");
        assertTrue(hollow.volume() * 4 < solid.volume(),
                "hollow should be far cheaper: " + hollow.volume() + " vs " + solid.volume());
    }

    // ===================== cylinder =====================

    @Test
    void aCylinderIsRoundNotSquare() {
        BuildShape s = shape("{\"kind\":\"cylinder\",\"width\":9,\"height\":1,\"depth\":9}");
        Set<String> p = positions(s);
        assertTrue(p.contains("4,0,4"), "the centre is inside");
        assertFalse(p.contains("0,0,0"), "a corner is OUTSIDE a circle — this is what makes it round");
        assertTrue(s.volume() < 9 * 9, "and so it uses fewer blocks than a square");
    }

    @Test
    void aHollowCylinderIsATowerWithAFloorAndCeiling() {
        BuildShape s = shape(
                "{\"kind\":\"cylinder\",\"width\":9,\"height\":6,\"depth\":9,\"hollow\":true}");
        Set<String> p = positions(s);
        assertFalse(p.contains("4,3,4"), "the middle of a tower must be walkable, not solid");
        assertTrue(p.contains("4,0,4"), "but it needs a floor");
        assertTrue(p.contains("4,5,4"), "and a ceiling to put a roof on");
    }

    // ===================== pyramid =====================

    @Test
    void aPyramidTapersToATip() {
        BuildShape s = shape("{\"kind\":\"pyramid\",\"width\":9,\"height\":5,\"depth\":9}");
        int bottom = 0;
        int top = 0;
        for (String pos : positions(s)) {
            String[] parts = pos.split(",");
            if (parts[1].equals("0")) {
                bottom++;
            }
            if (parts[1].equals("4")) {
                top++;
            }
        }
        assertTrue(bottom > top, "a roof is wide at the bottom and narrow at the top: "
                + bottom + " vs " + top);
        assertTrue(top > 0, "and it should still have a tip");
    }

    // ===================== sphere =====================

    @Test
    void aSphereIsRoundInEveryAxis() {
        BuildShape s = shape("{\"kind\":\"sphere\",\"width\":9,\"height\":9,\"depth\":9}");
        Set<String> p = positions(s);
        assertTrue(p.contains("4,4,4"), "centre");
        assertFalse(p.contains("0,0,0"), "corners are outside a sphere");
        assertTrue(s.volume() < 9 * 9 * 9);
    }

    // ===================== clamps =====================

    @Test
    void absurdSizesAreClamped() {
        BuildShape s = shape(
                "{\"kind\":\"box\",\"width\":99999,\"height\":-4,\"depth\":9999,"
                        + "\"dx\":100000,\"dy\":-99999,\"dz\":0}");
        assertEquals(BuildShape.MAX_DIM, s.width());
        assertEquals(1, s.height(), "a negative size becomes the minimum, not zero or negative");
        assertEquals(BuildShape.MAX_OFFSET, s.dx());
        assertEquals(-BuildShape.MAX_OFFSET, s.dy());
    }

    @Test
    void anOversizedPlanIsRefusedNotTruncated() {
        // Silently building half a castle would be worse than saying no.
        List<BuildShape> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(shape("{\"kind\":\"box\",\"width\":96,\"height\":96,\"depth\":96}"));
        }
        BuildPlan plan = new BuildPlan("huge", "Huge", many);
        assertFalse(plan.fitsBudget());
        assertTrue(plan.volume() > BuildPlan.MAX_BLOCKS);
    }

    @Test
    void shapeCountIsBounded() {
        List<BuildShape> many = new ArrayList<>();
        for (int i = 0; i < BuildPlan.MAX_SHAPES + 20; i++) {
            many.add(shape("{\"kind\":\"box\",\"width\":1,\"height\":1,\"depth\":1}"));
        }
        assertEquals(BuildPlan.MAX_SHAPES, new BuildPlan("x", "X", many).shapes().size());
    }

    // ===================== plan =====================

    @Test
    void shapesArePlacedInOrderSoLaterOnesCarveDoorways() {
        // The doorway trick: a wall, then a small box of air through it. Order is the mechanism.
        BuildPlan plan = BuildPlan.fromJson(JsonParser.parseString("""
            {"id":"hut","name":"Hut","shapes":[
              {"kind":"box","width":5,"height":4,"depth":5,"block":"minecraft:stone_bricks","hollow":true},
              {"kind":"box","dx":2,"dy":1,"dz":0,"width":1,"height":2,"depth":1,"block":"minecraft:air"}]}
            """).getAsJsonObject());
        assertNotNull(plan);
        List<String> order = new ArrayList<>();
        plan.forEachBlock((x, y, z, block) -> {
            if (x == 2 && y == 1 && z == 0) {
                order.add(block);
            }
        });
        assertEquals(List.of("minecraft:stone_bricks", "minecraft:air"), order,
                "the wall goes down first, then air carves the door through it");
    }

    @Test
    void blockIdsAreListedForValidation() {
        BuildPlan plan = BuildPlan.fromJson(JsonParser.parseString("""
            {"id":"t","shapes":[
              {"kind":"box","block":"minecraft:stone"},
              {"kind":"box","block":"minecraft:glass"},
              {"kind":"box","block":"minecraft:stone"}]}
            """).getAsJsonObject());
        assertEquals(List.of("minecraft:stone", "minecraft:glass"), plan.blockIds(),
                "distinct ids only, so each is resolved once");
    }

    @Test
    void roundTripsThroughJson() {
        BuildPlan plan = BuildPlan.fromJson(JsonParser.parseString("""
            {"id":"castle","name":"Castle","shapes":[
              {"kind":"cylinder","dx":3,"dy":0,"dz":4,"width":9,"height":20,"depth":9,
               "block":"minecraft:stone_bricks","hollow":true}]}
            """).getAsJsonObject());
        BuildPlan back = BuildPlan.fromJson(plan.toJson());
        assertNotNull(back);
        assertEquals(plan.id(), back.id());
        assertEquals(plan.volume(), back.volume(), "a saved building must rebuild identically");
        assertEquals(plan.shapes().get(0).hollow(), back.shapes().get(0).hollow());
    }

    @Test
    void survivesRubbishInput() {
        assertNull(BuildPlan.fromJson(null));
        assertNull(BuildPlan.fromJson(JsonParser.parseString("{}").getAsJsonObject()),
                "no id and no shapes is not a building");
        assertNull(BuildPlan.fromJson(JsonParser.parseString(
                "{\"id\":\"x\",\"shapes\":[]}").getAsJsonObject()), "no shapes is not a building");
        BuildPlan odd = BuildPlan.fromJson(JsonParser.parseString(
                "{\"id\":\"  MY Castle \",\"shapes\":[{\"kind\":\"nonsense\"}]}").getAsJsonObject());
        assertNotNull(odd);
        assertEquals("my_castle", odd.id(), "ids are normalised so wishbuild can find them");
        assertEquals(BuildShape.Kind.BOX, odd.shapes().get(0).kind(), "an unknown kind falls back");
    }
}
