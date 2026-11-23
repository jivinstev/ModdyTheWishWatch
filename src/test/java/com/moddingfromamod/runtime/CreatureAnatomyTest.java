package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * Gate A for reading a sculpted creature's shape back out.
 *
 * <p>The numbers asserted here are Spikor's REAL bones, copied from the model that shipped, because
 * this maths existing but being subtly wrong is exactly the failure it is meant to prevent: the
 * agent guessed his mouth position and missed by half a block, and nothing caught it until a child
 * watched fire pour out of thin air.
 */
class CreatureAnatomyTest {

    /**
     * A trimmed slice of the real {@code spikor} geometry — the bones that decide height, width and
     * the aiming landmarks. Coordinates are verbatim from
     * {@code ~/.moddingfromamod/models/spikor/geometry.json}.
     */
    private static BedrockGeometry spikor() {
        String json = """
            {"format_version":"1.12.0","minecraft:geometry":[{
              "description":{"identifier":"geometry.sculpted","texture_width":256,"texture_height":256},
              "bones":[
                {"name":"legL","pivot":[-5.5,13,0],"cubes":[{"origin":[-9,0,-5],"size":[7,13,10],"uv":[0,0]}]},
                {"name":"body","pivot":[0,13,0],"cubes":[{"origin":[-10,13,-6],"size":[20,19,12],"uv":[0,0]}]},
                {"name":"armL","pivot":[0,16,0],"cubes":[{"origin":[-16,16,-4],"size":[6,15,8],"uv":[0,0]}]},
                {"name":"armR","pivot":[0,16,0],"cubes":[{"origin":[10,16,-4],"size":[6,15,8],"uv":[0,0]}]},
                {"name":"bandspikeL","parent":"armL","pivot":[0,23,0],"cubes":[{"origin":[-19,23,-2],"size":[2,2,4],"uv":[0,0]}]},
                {"name":"bandspikeR","parent":"armR","pivot":[0,23,0],"cubes":[{"origin":[17,23,-2],"size":[2,2,4],"uv":[0,0]}]},
                {"name":"head","pivot":[0,34,-1],"cubes":[{"origin":[-9,34,-8],"size":[18,12,14],"uv":[0,0]}]},
                {"name":"snout","parent":"head","pivot":[0,35,0],"cubes":[{"origin":[-6,35,-14],"size":[12,7,6],"uv":[0,0]}]},
                {"name":"mouth","parent":"head","pivot":[0,35,0],"cubes":[{"origin":[-6,35,-14],"size":[12,1,6],"uv":[0,0]}]},
                {"name":"eyeL","parent":"head","pivot":[0,42,0],"cubes":[{"origin":[-7,42,-9],"size":[4,3,1],"uv":[0,0]}]},
                {"name":"eyeR","parent":"head","pivot":[0,42,0],"cubes":[{"origin":[3,42,-9],"size":[4,3,1],"uv":[0,0]}]},
                {"name":"hairB","parent":"head","pivot":[0,46,0],"cubes":[{"origin":[-2,46,0],"size":[4,10,6],"uv":[0,0]}]}
              ]}]}
            """;
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return BedrockGeometry.fromJson(root);
    }

    private static final double SCALE = 2.6;   // spikor's live scale, from the world's species.json

    private static Optional<CreatureAnatomy.Landmark> mark(List<CreatureAnatomy.Landmark> all,
                                                           String name) {
        return all.stream().filter(m -> m.name().equals(name)).findFirst();
    }

    @Test
    void measuresTheModelsRealBoundingBox() {
        CreatureAnatomy.Extents e = CreatureAnatomy.extents(spikor());
        assertFalse(e.isEmpty(), "a model with cubes must measure to something");
        assertEquals(0f, e.minY(), 0.001f, "feet sit at y=0 by convention");
        assertEquals(56f, e.maxY(), 0.001f, "hairB tops out at 46+10 = 56 units");
        assertEquals(-19f, e.minX(), 0.001f, "the left arm band spike is the widest point");
        assertEquals(19f, e.maxX(), 0.001f, "and mirrors on the right");
        // Spikor's mouth is a slab flush across the front of his snout. Until the parser grew
        // it by FightingFaces.EPSILON, so the hitbox measured a hair the rig never drew; now the
        // parser returns the geometry as authored and the separation lives in the rig
        // (RigSeparation), so the anatomy says exactly where the snout is.
        assertEquals(-14f, e.minZ(), 0.001f,
                "the snout is the furthest-forward box");
    }

    /**
     * <b>A creature that uses {@code inflate} must not get a hitbox smaller than itself.</b>
     *
     * <p>Inflate grows a cube on every face at render time, so measuring origin+size alone reports
     * a model narrower and shorter than the one on screen. The sculptor allows up to 4 units of it,
     * which is <b>half a block</b> missing from each dimension — the same class of bug as the fixed
     * player-sized box this file was written to replace, arriving by a different door.
     */
    @Test
    void inflateCountsTowardTheHitbox() {
        BedrockGeometry g = BedrockGeometry.fromJson(JsonParser.parseString("""
            {"minecraft:geometry":[{"bones":[
              {"name":"body","pivot":[0,0,0],"cubes":[
                 {"origin":[-8,0,-8],"size":[16,32,16],"inflate":4,"uv":[0,0]}]}
            ]}]}
            """).getAsJsonObject());
        CreatureAnatomy.Extents e = CreatureAnatomy.extents(g);
        assertEquals(-12f, e.minX(), 0.001f, "8 units of box plus 4 of inflate");
        assertEquals(12f, e.maxX(), 0.001f);
        assertEquals(36f, e.maxY(), 0.001f, "32 tall plus 4 of inflate on the top face");
        assertEquals(-4f, e.minY(), 0.001f, "and 4 below the feet");

        // 36 units / 16 = 2.25 blocks, against the 2.0 the un-inflated box would have claimed.
        assertEquals(2.25f, CreatureAnatomy.heightBlocks(e), 0.001f);
        assertEquals(1.5f, CreatureAnatomy.widthBlocks(e), 0.001f, "2 x 12 units / 16");
    }

    /**
     * ...but inflate must NOT move a landmark, because it grows a cube symmetrically and a landmark
     * is a cube's CENTRE. This is what keeps the hitbox fix from quietly re-aiming every creature's
     * fire breath, and it is the reason that fix could be made narrowly.
     */
    @Test
    void inflateDoesNotMoveALandmark() {
        String bones = """
            {"minecraft:geometry":[{"bones":[
              {"name":"head","pivot":[0,0,0],"cubes":[
                 {"origin":[-6,20,-6],"size":[12,12,12],%s"uv":[0,0]}]}
            ]}]}
            """;
        double plain = mark(CreatureAnatomy.landmarks(BedrockGeometry.fromJson(
                JsonParser.parseString(bones.formatted("")).getAsJsonObject()), 1.0), "head")
                .orElseThrow().up();
        double fat = mark(CreatureAnatomy.landmarks(BedrockGeometry.fromJson(
                JsonParser.parseString(bones.formatted("\"inflate\":3,")).getAsJsonObject()), 1.0),
                "head").orElseThrow().up();
        assertEquals(plain, fat, 0.001, "a symmetric grow leaves the centre exactly where it was");
    }

    @Test
    void heightIsTheModelHeightNotThePlayerBox() {
        // The whole point: 56 units at scale 2.6 is 9.10 blocks. The old fixed hitbox said 5.07,
        // which left Spikor's head and shoulders with no collision at all.
        float base = CreatureAnatomy.heightBlocks(CreatureAnatomy.extents(spikor()));
        assertEquals(3.5f, base, 0.001f, "56 units / 16 = 3.5 blocks before scaling");
        assertEquals(9.10, base * SCALE, 0.01, "and 9.10 blocks at his live scale");
        assertTrue(base * SCALE > 1.95 * SCALE, "must exceed the old player-sized box");
    }

    @Test
    void widthSpansTheWidestPointOnEitherHorizontalAxis() {
        // A hitbox is a square centred on the entity, so the widest reach decides it: 19 units out
        // on X, doubled, is 38 units = 2.375 blocks before scaling.
        float base = CreatureAnatomy.widthBlocks(CreatureAnatomy.extents(spikor()));
        assertEquals(2.375f, base, 0.001f, "2 x 19 units / 16");
    }

    @Test
    void findsTheMouthWhereItActuallyIs() {
        // The number the agent got wrong. mouth spans y 35..36 and z -14..-8, so its centre is
        // y=35.5, z=-11 -> 5.77 blocks up and 1.79 forward at scale 2.6.
        CreatureAnatomy.Landmark m = mark(CreatureAnatomy.landmarks(spikor(), SCALE), "mouth")
                .orElseThrow(() -> new AssertionError("the mouth bone must be found"));
        assertEquals(5.77, m.up(), 0.01, "mouth height above the feet, in blocks");
        assertEquals(1.79, m.forward(), 0.01, "and how far out in front");
    }

    @Test
    void forwardIsPositiveOutOfTheFace() {
        // The model faces -Z, so a forward-of-the-body part must come back POSITIVE. Getting this
        // sign wrong would aim every effect out of the creature's back.
        List<CreatureAnatomy.Landmark> all = CreatureAnatomy.landmarks(spikor(), SCALE);
        double snout = mark(all, "snout").orElseThrow().forward();
        double head = mark(all, "head").orElseThrow().forward();
        assertTrue(snout > 0, "the snout sticks out in FRONT: " + snout);
        assertTrue(snout > head, "and further forward than the head it grows from");
    }

    @Test
    void mergesLeftAndRightPairsOntoTheCentreLine() {
        // eyeL + eyeR must report once, as "eyes" - an aimed effect belongs on the centre line, and
        // two entries would make the agent pick one eye and fire off-axis.
        List<CreatureAnatomy.Landmark> all = CreatureAnatomy.landmarks(spikor(), SCALE);
        assertEquals(1, all.stream().filter(m -> m.name().equals("eyes")).count(),
                "the two eye bones collapse to one landmark");
        assertEquals(7.07, mark(all, "eyes").orElseThrow().up(), 0.01,
                "eyes centre at y=43.5 units -> 7.07 blocks at scale 2.6");
    }

    @Test
    void aBoneIsNotDoubleCountedUnderALaterLabel() {
        // "mouth" also sits inside the snout/head region. If it were folded into those averages too,
        // every landmark would drift toward the jaw.
        List<CreatureAnatomy.Landmark> all = CreatureAnatomy.landmarks(spikor(), SCALE);
        assertEquals(6.50, mark(all, "head").orElseThrow().up(), 0.01,
                "the head landmark is its own cube's centre (y=40), undragged by mouth/snout/eyes");
    }

    @Test
    void scaleMultipliesEveryLandmark() {
        double atOne = mark(CreatureAnatomy.landmarks(spikor(), 1.0), "mouth").orElseThrow().up();
        double atScale = mark(CreatureAnatomy.landmarks(spikor(), SCALE), "mouth").orElseThrow().up();
        assertEquals(atOne * SCALE, atScale, 0.02, "landmarks are reported at the LIVE scale");
    }

    @Test
    void anEmptyOrMissingModelDegradesInsteadOfThrowing() {
        // This data comes off disk and out of Blockbench; a broken model must not stop a creature
        // existing, it must fall back to the registered hitbox.
        assertTrue(CreatureAnatomy.extents(null).isEmpty(), "no geometry measures to nothing");
        assertEquals(List.of(), CreatureAnatomy.landmarks(null, 2.0), "and yields no landmarks");
    }

    @Test
    void anAbsurdModelIsClampedToAPlayableHitbox() {
        // A creature whose box dwarfs the chunks around it gets stuck in terrain and suffocates.
        CreatureAnatomy.Extents huge = new CreatureAnatomy.Extents(-9000, 9000, 0, 9000, -9000, 9000);
        assertEquals(CreatureAnatomy.MAX_SIZE_BLOCKS, CreatureAnatomy.heightBlocks(huge), 0.001f);
        assertEquals(CreatureAnatomy.MAX_SIZE_BLOCKS, CreatureAnatomy.widthBlocks(huge), 0.001f);
        CreatureAnatomy.Extents flat = new CreatureAnatomy.Extents(0, 0, 0, 0, 0, 0);
        assertEquals(CreatureAnatomy.MIN_SIZE_BLOCKS, CreatureAnatomy.heightBlocks(flat), 0.001f);
    }
}
