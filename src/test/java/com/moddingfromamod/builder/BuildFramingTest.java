package com.moddingfromamod.builder;

import java.util.List;

import com.moddingfromamod.runtime.OrbitCamera;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Camera framing for a building, tested without a game.
 *
 * <p>Neither of the two implementations this replaces had a single test, and both were wrong in
 * ways only a photograph revealed: one aimed at the wrong site, the other framed a building using
 * creature-model geometry and produced a close-up of a wall.
 */
class BuildFramingTest {

    private static BuildShape box(int dx, int dy, int dz, int w, int h, int d, String block,
                                  boolean hollow) {
        return new BuildShape(BuildShape.Kind.BOX, dx, dy, dz, w, h, d, block, hollow);
    }

    private static BuildPlan plan(BuildShape... shapes) {
        return new BuildPlan("keep", "The Keep", List.of(shapes));
    }

    /** A castle you can walk into: hollow walls with a doorway cut through. */
    private static final BuildPlan HOLLOW = plan(
            box(0, 0, 0, 21, 12, 21, "minecraft:stone", true),
            box(10, 1, 0, 1, 3, 1, "minecraft:air", false));

    private static final BuildPlan SOLID = plan(box(0, 0, 0, 21, 12, 21, "minecraft:stone", false));

    @Test
    @DisplayName("bounds come from the plan's own blocks, not from a bounding box of its shapes")
    void boundsComeFromTheComposedBlocks() {
        // The shape list claims 0..20; what stands is what matters, and here they agree — the
        // point is that a plan whose last act is to carve is measured after the carving.
        BuildFraming.Bounds b = BuildFraming.bounds(HOLLOW);
        assertEquals(21, b.width());
        assertEquals(12, b.height());
        assertEquals(21, b.span());
    }

    @Test
    @DisplayName("a plan that composes to nothing has nowhere to stand")
    void nothingToPhotograph() {
        assertEquals(null, BuildFraming.bounds(new BuildPlan("x", "x", List.of())));
        assertTrue(BuildFraming.orbit(new BuildPlan("x", "x", List.of()), 0, 0, 0).isEmpty());
    }

    // ───────────────────────── the four outside ─────────────────────────

    @Test
    @DisplayName("four orbit poses, all outside the building, all aimed at it")
    void fourOrbitPosesOutside() {
        List<BuildFraming.Pose> poses = BuildFraming.orbit(HOLLOW, 100, 64, 200);
        assertEquals(OrbitCamera.STOPS, poses.size());
        double cx = 100 + 10, cz = 200 + 10;
        for (BuildFraming.Pose p : poses) {
            double away = Math.hypot(p.x() - cx, p.z() - cz);
            assertTrue(away > 21 / 2.0,
                    p.name() + " must stand outside the building, was " + away + " from the middle");
            assertTrue(p.y() > 64 + 12, p.name() + " must be ABOVE THE ROOF (12 tall), not beside "
                    + "it — an eye-height camera ends up under a stilted castle or inside a moat; "
                    + "was y=" + p.y());
            assertTrue(p.pitch() > 5, p.name() + " must look DOWN at it, was pitch " + p.pitch());
        }
        assertEquals(List.of("face", "left", "back", "right"),
                poses.stream().map(BuildFraming.Pose::name).toList());
    }

    @Test
    @DisplayName("the four bearings are genuinely four different sides")
    void theBearingsAreFourDifferentSides() {
        List<BuildFraming.Pose> poses = BuildFraming.orbit(HOLLOW, 0, 0, 0);
        for (int i = 0; i < poses.size(); i++) {
            for (int j = i + 1; j < poses.size(); j++) {
                double d = Math.hypot(poses.get(i).x() - poses.get(j).x(),
                                      poses.get(i).z() - poses.get(j).z());
                assertTrue(d > 1.0, "stops " + i + " and " + j + " are the same place");
            }
        }
    }

    /** How far stop 0 stands from the middle of a plan that starts at the origin. */
    private static double standoff(BuildPlan p, double cx, double cz) {
        BuildFraming.Pose pose = BuildFraming.orbit(p, 0, 0, 0).get(0);
        return Math.hypot(pose.x() - cx, pose.z() - cz);
    }

    @Test
    @DisplayName("a long low hall is framed on its LENGTH, or both ends fall out of shot")
    void aLongLowHallIsFramedOnItsLength() {
        // The bug OrbitCamera.distanceForBuild exists for. Framed on height alone, a 60x6 hall puts
        // the camera a few blocks away and photographs its middle.
        double hall = standoff(plan(box(0, 0, 0, 50, 6, 8, "minecraft:stone", true)), 25, 4);
        double tower = standoff(plan(box(0, 0, 0, 8, 6, 8, "minecraft:stone", true)), 4, 4);
        assertTrue(hall > tower, "the same-height hall must be shot from further back: hall "
                + hall + " vs tower " + tower);

        // …and it must keep tracking the length rather than merely clearing the minimum once.
        // 96, not 120: BuildShape.MAX_DIM clamps a shape at 96, so a 120-long hall would measure
        // the CAP rather than this rule, and pass or fail for the wrong reason.
        double longer = standoff(plan(box(0, 0, 0, 96, 6, 8, "minecraft:stone", true)), 48, 4);
        assertTrue(longer > hall * 1.8,
                "doubling the length must roughly double the standoff: " + hall + " -> " + longer);
    }

    @Test
    @DisplayName("the camera stands clear of the footprint before it frames anything")
    void theCameraStandsClearOfTheFootprint() {
        // A real 120-wide Spikor castle framed on span alone put the lens 63 blocks from the
        // middle — three blocks from its own corner tower, which then filled half the picture with
        // the rest of the castle hidden behind it. Distance from the middle is not distance from
        // the building.
        BuildPlan wide = plan(box(0, 0, 0, 96, 20, 40, "minecraft:stone", true));
        double d = standoff(wide, 47.5, 19.5);
        assertTrue(d > 48 + 20, "must clear the 48-block half-span with room to spare: " + d);
    }

    @Test
    @DisplayName("a buried foundation is not framed for — nobody can see it")
    void aBuriedFoundationIsNotFramedFor() {
        // Real builds dig: a lake needs a bed 10 deep to hold its water, a moat is a hole,
        // foundations go down. Counting that as height backs the camera off for stone nobody can
        // see AND raises the aim point, so the building sits high in frame under a lot of sky.
        // One real castle measured 76 tall and was 66 of anything photographable.
        BuildPlan above = plan(box(0, 0, 0, 30, 40, 30, "minecraft:stone", true));
        BuildPlan withCellar = plan(box(0, 0, 0, 30, 40, 30, "minecraft:stone", true),
                                    box(0, -12, 0, 30, 12, 30, "minecraft:stone", false));
        BuildFraming.Pose plain = BuildFraming.orbit(above, 0, 64, 0).get(0);
        BuildFraming.Pose dug = BuildFraming.orbit(withCellar, 0, 64, 0).get(0);
        assertEquals(plain.y(), dug.y(), 0.001, "the buried half must not move the camera");
        assertEquals(standoff(above, 14.5, 14.5), standoff(withCellar, 14.5, 14.5), 0.001);

        // …but a build that is entirely underground still has to be framed on something.
        BuildPlan bunker = plan(box(0, -20, 0, 30, 12, 30, "minecraft:stone", true));
        assertTrue(!BuildFraming.orbit(bunker, 0, 64, 0).isEmpty());
    }

    @Test
    @DisplayName("a shed is not photographed from three blocks away")
    void aShedIsNotPhotographedFromThreeBlocksAway() {
        // Purely proportional framing puts the lens inside the porch of anything small, and at
        // that range a 70-degree lens bends a straight wall. There is a floor, and it binds here.
        // 20 rather than the floor's own 24: the floor is a SLANT distance, and the camera spends
        // part of it climbing, so the horizontal standoff is the floor times cos(elevation).
        assertTrue(standoff(plan(box(0, 0, 0, 5, 4, 5, "minecraft:stone", true)), 2, 2) >= 20.0);
    }

    @Test
    @DisplayName("it looks slightly DOWN at the building, not down ON it")
    void itLooksSlightlyDownAtTheBuilding() {
        // The rule this replaced stood above the ROOFLINE, which on a real 66x76 castle worked out
        // as an 84-block climb and a 38-degree tilt: a map view, in which the towers, the facade
        // and the doorway are all foreshortened into a plan. Anything past about 25 degrees has
        // stopped being a photograph of a building.
        for (BuildPlan p : List.of(
                plan(box(0, 0, 0, 66, 76, 66, "minecraft:stone", true)),
                plan(box(0, 0, 0, 60, 6, 8, "minecraft:stone", true)),
                plan(box(0, 0, 0, 5, 40, 5, "minecraft:stone", true)))) {
            for (BuildFraming.Pose pose : BuildFraming.orbit(p, 0, 64, 0)) {
                assertTrue(pose.pitch() > 5f && pose.pitch() < 25f,
                        "tilt out of range: " + pose.pitch());
            }
        }
    }

    @Test
    @DisplayName("the camera is well clear of the ground, so an overhang cannot swallow it")
    void theCameraIsWellClearOfTheGround() {
        // Two real builds broke the eye-height version: a floating castle on stilts (the lens ended
        // up UNDER it, photographing the underside) and a Spikor castle whose lava moat flowed out
        // across the world (the lens stood in the lava). Both were in air, so a solid-block guard
        // passed them. Rising from the MIDDLE of the building rather than from its feet is what
        // makes both impossible without a special case for either.
        BuildPlan onStilts = plan(box(0, 0, 0, 40, 60, 40, "minecraft:stone", true));
        for (BuildFraming.Pose pose : BuildFraming.orbit(onStilts, 0, 64, 0)) {
            assertTrue(pose.y() > 64 + 30, "must be at least mid-height up: " + pose.y());
        }
    }

    // ───────────────────────── the four inside ─────────────────────────

    @Test
    @DisplayName("four interior poses, inside the walls, looking four ways")
    void fourInteriorPoses() {
        List<BuildFraming.Pose> in = BuildFraming.inside(HOLLOW, 100, 64, 200);
        assertEquals(4, in.size());
        for (BuildFraming.Pose p : in) {
            assertTrue(p.x() > 100 && p.x() < 100 + 21, "inside on x: " + p.x());
            assertTrue(p.z() > 200 && p.z() < 200 + 21, "inside on z: " + p.z());
        }
        assertEquals(List.of(0f, 90f, 180f, 270f), in.stream().map(BuildFraming.Pose::yaw).toList());
        assertEquals(1, in.stream().map(p -> p.x() + "," + p.y() + "," + p.z()).distinct().count(),
                "all four look from ONE spot — it is a person turning on the spot, not four rooms");
    }

    @Test
    @DisplayName("a SOLID building reports no interior rather than a camera inside a block")
    void aSolidBuildingHasNoInterior() {
        // A camera in a block photographs black, which is indistinguishable from a dark room —
        // i.e. from the exact defect the interior shots are meant to expose. Better to say there
        // is nothing to photograph.
        assertTrue(BuildFraming.inside(SOLID, 0, 0, 0).isEmpty(),
                "a solid keep has no inside to stand in");
    }

    @Test
    @DisplayName("the interior camera stands where the plan is not, with head room above it")
    void theInteriorCameraStandsInAir() {
        List<BuildFraming.Pose> in = BuildFraming.inside(HOLLOW, 0, 0, 0);
        assertTrue(!in.isEmpty());
        BuildGeometry g = BuildGeometry.of(HOLLOW);
        int x = (int) Math.floor(in.get(0).x());
        int y = (int) in.get(0).y();
        int z = (int) Math.floor(in.get(0).z());
        assertTrue(!g.isSolid(x, y, z), "the camera is inside a block");
        assertTrue(!g.isSolid(x, y + 1, z), "no head room above the camera");
    }

    @Test
    @DisplayName("it stands low, where the floor and the door are")
    void itStandsLowNotUpInTheRoof() {
        List<BuildFraming.Pose> in = BuildFraming.inside(HOLLOW, 0, 0, 0);
        BuildFraming.Bounds b = BuildFraming.bounds(HOLLOW);
        double up = (in.get(0).y() - b.minY()) / (double) b.height();
        assertTrue(up < 0.5, "a castle's upper half is roof; stand low, was " + up);
    }

    @Test
    @DisplayName("it stands in the middle of the room, not with its nose against a wall")
    void itStandsInTheMiddleOfTheRoom() {
        // What the first version actually shipped. It took the first air cell found spiralling out
        // from the centre of the BOUNDING BOX, which on a real castle was a cell hard against the
        // bookshelves at the top of the keep: all four interior photographs were of that wall from
        // one block away, and every assertion above still passed.
        BuildGeometry g = BuildGeometry.of(HOLLOW);
        BuildFraming.Pose p = BuildFraming.inside(HOLLOW, 0, 0, 0).get(0);
        int x = (int) Math.floor(p.x());
        int y = (int) p.y();
        int z = (int) Math.floor(p.z());
        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int clear = 0;
            while (clear < 6 && !g.isSolid(x + d[0] * (clear + 1), y, z + d[1] * (clear + 1))) {
                clear++;
            }
            assertTrue(clear >= 3, "only " + clear + " block(s) of room to the "
                    + d[0] + "/" + d[1] + " — that is a picture of a wall");
        }
    }

    @Test
    @DisplayName("it stands on a floor, not in the empty air beside a spire")
    void itStandsOnAFloor() {
        // A hall with something tall on it — a lighthouse, a spire, a mast — is the common shape,
        // and it makes the bounding box mostly EMPTY AIR. That air is by far the roomiest place in
        // the box, so scored on openness alone the "interior" camera floats twenty blocks up beside
        // the tower and photographs the sky. Requiring a floor under it is what keeps it indoors.
        // …and the canopy is the OTHER half of it. A high platform on legs — a floating castle, a
        // walkway, a battlement — leaves air that has something over it but nothing under it, which
        // a ceiling test alone happily calls indoors.
        BuildPlan withSpire = plan(
                box(0, 0, 0, 11, 5, 11, "minecraft:stone", true),
                box(5, 5, 5, 1, 60, 1, "minecraft:stone", false),
                box(0, 50, 0, 11, 1, 11, "minecraft:stone", false));
        BuildGeometry g = BuildGeometry.of(withSpire);
        BuildFraming.Pose p = BuildFraming.inside(withSpire, 0, 0, 0).get(0);
        int x = (int) Math.floor(p.x());
        int z = (int) Math.floor(p.z());
        boolean floor = false;
        for (int d = 1; d <= 4; d++) {
            floor |= g.isSolid(x, (int) p.y() - d, z);
        }
        assertTrue(floor, "nothing under the camera at " + x + "," + p.y() + "," + z);
        assertTrue(p.y() < 6, "it climbed out of the room: y=" + p.y());
    }

    @Test
    @DisplayName("it picks a real room over a thing that merely looks like one")
    void itPicksARealRoomOverThingsThatLookLikeOne() {
        // The decoy is roomier than the answer, so anything scoring on openness alone takes it.
        //
        //  - THE FLOORLESS HALL is what an air run with a ceiling and walls but nothing underneath
        //    looks like: a pavilion, a building on stilts, the space under a raised walkway.
        // (The other decoy this test used to carry — a hall taller than some cap — is gone. A
        // 28-block hall is a great hall, not a decoy, and capping it reported NO INTERIOR for a
        // real castle. What that cap was standing in for, a castle roof under a balcony, is now
        // excluded properly by probing the roof in all eight directions; see aBridgeIsNotARoof.)
        BuildPlan mixed = plan(
                box(0, 0, 0, 25, 12, 25, "minecraft:stone", true),
                box(1, 0, 1, 23, 1, 23, "minecraft:air", false),
                box(40, 0, 0, 11, 6, 11, "minecraft:stone", true));
        BuildFraming.Pose p = BuildFraming.inside(mixed, 0, 0, 0).get(0);
        assertTrue(p.x() > 40 && p.x() < 51,
                "it went for a decoy instead of the one real room: x=" + p.x());
    }

    @Test
    @DisplayName("you cannot stand on a lava moat, however room-like it is")
    void aLiquidIsNotAFloor() {
        // A real Spikor castle. Its moat is enclosed by the castle's own towers with an arch over
        // it, so by floor-ceiling-walls it was the best room in the building, and all four interior
        // photographs were taken standing in the lava.
        // The moat is deliberately the ROOMIER of the two, or the small dry room would win for a
        // reason that has nothing to do with the lava and the rule would go untested.
        BuildPlan moated = plan(
                box(0, 0, 0, 15, 10, 15, "minecraft:stone", true),
                box(40, 0, 0, 31, 14, 31, "minecraft:stone", true),
                box(41, 0, 1, 29, 1, 29, "minecraft:lava", false));
        BuildFraming.Pose p = BuildFraming.inside(moated, 0, 0, 0).get(0);
        assertTrue(p.x() < 16, "it is standing in the lava: x=" + p.x());
    }

    @Test
    @DisplayName("a courtyard with a bridge over it is still outdoors")
    void aBridgeIsNotARoof() {
        // A real Spikor castle. At exactly the column the bridge crosses there is a floor below,
        // walls either side and a ceiling above, so all four "interior" shots were taken outdoors
        // in an alley with the sky in them. Again the decoy is deliberately the roomier one.
        BuildPlan courtyard = plan(
                box(0, 0, 0, 15, 10, 15, "minecraft:stone", true),
                box(40, 0, 0, 31, 1, 31, "minecraft:stone", false),
                box(40, 1, 0, 1, 12, 31, "minecraft:stone", false),
                box(70, 1, 0, 1, 12, 31, "minecraft:stone", false),
                box(40, 1, 0, 31, 12, 1, "minecraft:stone", false),
                box(40, 1, 30, 31, 12, 1, "minecraft:stone", false),
                box(40, 12, 14, 31, 1, 3, "minecraft:stone", false));
        BuildFraming.Pose p = BuildFraming.inside(courtyard, 0, 0, 0).get(0);
        assertTrue(p.x() < 16, "it is standing outdoors under the bridge: x=" + p.x());
    }

    @Test
    @DisplayName("a moat is grounds, not building — the castle must not shrink to fit it")
    void aWideApronDoesNotShrinkTheBuilding() {
        // The real Spikor castle: a 52-wide keep with towers, standing in a 96-wide lava moat with
        // four bridges out of it. Framed on all of that, the castle photographs as a smudge in the
        // middle of a car park — complete, correctly framed, and not a picture of a castle.
        BuildPlan keep = plan(box(22, 0, 22, 52, 60, 52, "minecraft:stone", true));
        BuildPlan withMoat = plan(
                box(0, 0, 0, 96, 2, 96, "minecraft:lava", false),
                box(22, 0, 22, 52, 60, 52, "minecraft:stone", true));
        double alone = standoff(keep, 47.5, 47.5);
        double moated = standoff(withMoat, 47.5, 47.5);
        assertTrue(moated < alone * 1.25,
                "the moat pushed the camera from " + alone + " back to " + moated);

        // …but a thing that is MEANT to be flat still gets framed, rather than being told it is
        // all apron and photographed from its own doorstep.
        assertTrue(standoff(plan(box(0, 0, 0, 90, 2, 90, "minecraft:stone", false)), 45, 45) > 30,
                "a plaza is a subject too");
    }

    @Test
    @DisplayName("the whole building is inside the frame, roof and base both")
    void thewholeBuildingIsInsideTheFrame() {
        // The check that was missing while three constants were being tuned by eye. Tilting down
        // spends the lens: at 18° only 17° of frame is left ABOVE the view axis, and a real
        // castle's spire ended up four blocks past the top edge — so the photograph had no spire
        // in it, and every other assertion was green.
        double halfFov = Math.toRadians(35);
        for (BuildPlan p : List.of(
                plan(box(0, 0, 0, 52, 153, 52, "minecraft:stone", true)),
                plan(box(0, 0, 0, 66, 76, 66, "minecraft:stone", true)),
                plan(box(0, 0, 0, 60, 6, 8, "minecraft:stone", true)),
                plan(box(0, 0, 0, 5, 40, 5, "minecraft:stone", true)))) {
            BuildFraming.Bounds b = BuildFraming.bounds(p);
            BuildFraming.Pose pose = BuildFraming.orbit(p, 0, 64, 0).get(0);
            double cx = (b.minX() + b.maxX()) / 2.0;
            double cz = (b.minZ() + b.maxZ()) / 2.0;
            double d = Math.hypot(pose.x() - cx, pose.z() - cz);
            double axis = Math.toRadians(pose.pitch());          // downward, from horizontal
            for (double y : new double[] {64 + b.maxY(), 64 + b.minY()}) {
                // Angle from the view axis to this point, positive either way.
                double toPoint = Math.atan2(pose.y() - y, d);    // downward, from horizontal
                assertTrue(Math.abs(toPoint - axis) < halfFov,
                        "y=" + y + " is outside the frame for a " + b.span() + "x" + b.height()
                                + " build at " + d + " blocks");
            }
        }
    }

    @Test
    @DisplayName("given the choice, it photographs the room with a light in it")
    void itPrefersALitRoom() {
        // A real floating castle's roomiest interior was an unlit corridor, and the photograph came
        // back at brightness 19 out of 255 — a true picture of the inside and no use to anybody.
        // The big room here is deliberately the bigger one, so preferring the lit one costs
        // something and the rule is doing real work.
        BuildPlan twoRooms = plan(
                box(0, 0, 0, 31, 8, 31, "minecraft:stone", true),
                box(40, 0, 0, 21, 8, 21, "minecraft:stone", true),
                box(50, 6, 10, 1, 1, 1, "minecraft:lantern", false));
        BuildFraming.Pose p = BuildFraming.inside(twoRooms, 0, 0, 0).get(0);
        assertTrue(p.x() > 40, "it went for the big dark room: x=" + p.x());

        // …but a building with no light at all is still photographed, not skipped.
        assertTrue(!BuildFraming.inside(
                plan(box(0, 0, 0, 21, 8, 21, "minecraft:stone", true)), 0, 0, 0).isEmpty());
    }

    @Test
    @DisplayName("all() is the eight a wish card wants, outside first")
    void allIsEight() {
        List<BuildFraming.Pose> all = BuildFraming.all(HOLLOW, 0, 0, 0);
        assertEquals(8, all.size());
        assertEquals("face", all.get(0).name());
        assertTrue(all.get(7).name().startsWith("in"));
    }

    @Test
    @DisplayName("the anchor moves the whole rig, so the fixture and the game agree")
    void theAnchorMovesEverything() {
        // The fixture's site bug in one assertion: framing that ignores the anchor photographs
        // empty ground next to the building.
        var atOrigin = BuildFraming.orbit(HOLLOW, 0, 0, 0).get(0);
        var moved = BuildFraming.orbit(HOLLOW, 1000, 64, -500).get(0);
        assertEquals(1000, moved.x() - atOrigin.x(), 0.001);
        assertEquals(64, moved.y() - atOrigin.y(), 0.001);
        assertEquals(-500, moved.z() - atOrigin.z(), 0.001);
    }
}
