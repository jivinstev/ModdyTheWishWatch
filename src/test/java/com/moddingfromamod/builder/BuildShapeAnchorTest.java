package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * {@code anchor: corner|centre} — the fix.
 *
 * <p>The defect was not that the offsets were wrong, it was that nobody said which end they were.
 * So the tests that matter here are the ones about <b>meaning being preserved</b>: an old plan must
 * still mean exactly what it meant, and a new plan must mean what it says.
 */
class BuildShapeAnchorTest {

    private static BuildShape shape(String json) {
        return BuildShape.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    private static Set<String> positions(BuildShape s) {
        Set<String> out = new HashSet<>();
        s.forEachBlock((x, y, z) -> out.add(x + "," + y + "," + z));
        return out;
    }

    // ===================== nothing that already exists may move =====================

    @Test
    void aShapeWithNoAnchorIsACornerShapeExactlyAsBefore() {
        BuildShape s = shape("""
                {"kind":"box","dx":5,"dy":0,"dz":7,"width":4,"height":3,"depth":6,
                 "block":"minecraft:stone","hollow":false}""");
        assertEquals(BuildShape.Anchor.CORNER, s.anchor());
        assertEquals(5, s.originX());
        assertEquals(7, s.originZ());
        assertTrue(positions(s).contains("5,0,7"), "the corner block must still be at dx,dy,dz");
    }

    @Test
    void theEightArgumentConstructorStillMeansCorner() {
        assertEquals(BuildShape.Anchor.CORNER,
                new BuildShape(BuildShape.Kind.BOX, 1, 2, 3, 4, 5, 6, "minecraft:stone", false)
                        .anchor());
    }

    @Test
    void aCornerShapeSerialisesWithoutAnAnchorKey() {
        // Every plan saved before this existed round-trips byte-identical, so the corpus diff shows
        // real changes rather than a new key on all 396 shapes.
        assertFalse(shape("""
                {"kind":"box","dx":0,"dy":0,"dz":0,"width":3,"height":3,"depth":3,
                 "block":"minecraft:stone","hollow":false}""").toJson().has("anchor"));
    }

    // ===================== centre means what it says =====================

    @Test
    void aCentredShapeIsCentredOnItsOffset() {
        BuildShape s = shape("""
                {"kind":"box","dx":0,"dy":0,"dz":0,"width":9,"height":3,"depth":9,
                 "block":"minecraft:stone","hollow":false,"anchor":"centre"}""");
        assertEquals(-4, s.originX());
        assertEquals(-4, s.originZ());
        assertTrue(positions(s).contains("0,0,0"), "dx,dz should be inside the shape, at its middle");
    }

    @Test
    void dyIsStillTheBaseWhenCentred() {
        // A vertical centre would bury half of every shape underground, which is never what
        // "put the tower here" means.
        BuildShape s = shape("""
                {"kind":"box","dx":0,"dy":0,"dz":0,"width":5,"height":20,"depth":5,
                 "block":"minecraft:stone","hollow":false,"anchor":"centre"}""");
        int lowest = positions(s).stream().mapToInt(p -> Integer.parseInt(p.split(",")[1])).min()
                .orElseThrow();
        assertEquals(0, lowest);
    }

    @Test
    void theAmericanSpellingWorksToo() {
        // The agent writes whichever it feels like; refusing one of them would be a silent
        // fallback to corner, which is the exact bug being fixed.
        for (String spelling : List.of("centre", "center", "middle", "CENTRE", " Center ")) {
            assertEquals(BuildShape.Anchor.CENTRE, BuildShape.Anchor.parse(spelling), spelling);
        }
        assertEquals(BuildShape.Anchor.CORNER, BuildShape.Anchor.parse("corner"));
        assertEquals(BuildShape.Anchor.CORNER, BuildShape.Anchor.parse(null));
        assertEquals(BuildShape.Anchor.CORNER, BuildShape.Anchor.parse("nonsense"));
    }

    @Test
    void anAnchoredShapeSurvivesASaveAndLoad() {
        BuildShape before = shape("""
                {"kind":"cylinder","dx":3,"dy":1,"dz":-2,"width":11,"height":8,"depth":11,
                 "block":"minecraft:deepslate","hollow":true,"anchor":"centre"}""");
        JsonObject json = before.toJson();
        assertEquals("centre", json.get("anchor").getAsString());
        BuildShape after = BuildShape.fromJson(json);
        assertEquals(before, after);
        assertEquals(positions(before), positions(after));
    }

    // ===================== the actual defect =====================

    @Test
    void twoCentredShapesOfDifferentSizesReallyAreConcentric() {
        // The whole point. Same offsets, different widths: as corners they are flush on one edge
        // and eat two walls; as centres one sits inside the other.
        BuildShape wall = shape("""
                {"kind":"cylinder","dx":0,"dy":0,"dz":0,"width":44,"height":12,"depth":44,
                 "block":"minecraft:deepslate_bricks","hollow":true,"anchor":"centre"}""");
        BuildShape hollowOut = shape("""
                {"kind":"cylinder","dx":0,"dy":0,"dz":0,"width":40,"height":14,"depth":40,
                 "block":"minecraft:air","hollow":false,"anchor":"centre"}""");
        assertEquals(-22, wall.originX());
        assertEquals(-20, hollowOut.originX());

        BuildGeometry g = BuildGeometry.of(new BuildPlan("t", "t", List.of(wall, hollowOut)));
        assertEquals(1, g.rooms().size());
        assertTrue(g.rooms().get(0).missingSides(BuildGeometry.WALL_MISSING).isEmpty(),
                "a centred wall hollowed out by a centred air cylinder must keep all four walls");
        assertTrue(g.weakestWall() > 0.9, "weakest wall was " + g.weakestWall());
    }

    @Test
    void theSameTwoShapesAsCornersStillLoseTwoWalls() {
        // The counterpart, kept so the fix is demonstrably a fix and not a no-op: identical
        // numbers, corner semantics, and the west and north walls go.
        BuildShape wall = shape("""
                {"kind":"cylinder","dx":0,"dy":0,"dz":0,"width":44,"height":12,"depth":44,
                 "block":"minecraft:deepslate_bricks","hollow":true}""");
        BuildShape hollowOut = shape("""
                {"kind":"cylinder","dx":0,"dy":0,"dz":0,"width":40,"height":14,"depth":40,
                 "block":"minecraft:air","hollow":false}""");
        BuildGeometry g = BuildGeometry.of(new BuildPlan("t", "t", List.of(wall, hollowOut)));
        assertEquals(List.of("north", "west"),
                g.rooms().get(0).missingSides(BuildGeometry.WALL_MISSING));
    }

    @Test
    void aPlanCanMixAnchorsWithoutOneLeakingIntoTheOther() {
        BuildPlan plan = BuildPlan.fromJson(JsonParser.parseString("""
                {"id":"t","name":"t","shapes":[
                 {"kind":"box","dx":0,"dy":0,"dz":0,"width":4,"height":2,"depth":4,
                  "block":"minecraft:stone","hollow":false},
                 {"kind":"box","dx":0,"dy":2,"dz":0,"width":4,"height":2,"depth":4,
                  "block":"minecraft:oak_planks","hollow":false,"anchor":"centre"}]}
                """).getAsJsonObject());
        assertNotNull(plan);
        assertEquals(0, plan.shapes().get(0).originX());
        assertEquals(-2, plan.shapes().get(1).originX());
    }
}
