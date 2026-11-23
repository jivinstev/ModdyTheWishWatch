package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * <b>Mutation tests for the buildings oracle: every gate is broken on purpose and must fail.</b>
 *
 * <p>This exists because the check it replaces was a tautology. {@code enclosureFaults} looked at
 * one hollow box at a time and asked whether its walls were present — which they are, by
 * construction, for every hollow box that can be expressed. Brute-forced over all 4,913 hollow
 * boxes from 3³ to 19³ it produced zero faults, and it announced itself as checked on all 84
 * buildings cases. A working component wired to nothing produces the same number as a broken one,
 * so from here on every gate has to be shown breaking something.
 */
class BuildGeometryTest {

    private static BuildPlan plan(String shapesJson) {
        return BuildPlan.fromJson(JsonParser.parseString(
                "{\"id\":\"t\",\"name\":\"t\",\"shapes\":" + shapesJson + "}").getAsJsonObject());
    }

    /** A plain 9x6x9 room of stone: four walls, a floor and a ceiling. */
    private static final String GOOD_ROOM = """
            [{"kind":"box","dx":0,"dy":0,"dz":0,"width":9,"height":6,"depth":9,
              "block":"minecraft:stone","hollow":true}]""";

    // ===================== the old check really was incapable of failing =====================

    @Test
    void theOldPerShapeCheckCouldNeverFail_soEveryHollowBoxLooksPerfectInIsolation() {
        // The exact predicate the retired check used, over the same space it was asked about.
        int examined = 0;
        for (int w = 3; w < 20; w++) {
            for (int d = 3; d < 20; d++) {
                for (int h = 3; h < 20; h++) {
                    BuildShape s = new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, w, h, d,
                            "minecraft:stone", true);
                    java.util.Set<String> placed = new java.util.HashSet<>();
                    s.forEachBlock((x, y, z) -> placed.add(x + "," + y + "," + z));
                    int y = h / 2;
                    boolean fault = !placed.contains("0," + y + ",0")
                            || placed.contains(w / 2 + "," + y + "," + d / 2);
                    assertFalse(fault, "the retired per-shape check fired at " + w + "x" + h + "x" + d
                            + " — if this ever becomes reachable the history in BuildGeometry's "
                            + "javadoc is wrong and should be corrected rather than deleted");
                    examined++;
                }
            }
        }
        assertEquals(4913, examined);
    }

    // ===================== four walls, over the COMPOSED plan =====================

    @Test
    void anIntactRoomHasFourWholeWalls() {
        BuildGeometry g = BuildGeometry.of(plan(GOOD_ROOM));
        assertEquals(1, g.rooms().size(), "a hollow 9x6x9 box is a room and should be seen as one");
        assertEquals(1.0, g.weakestWall(), 1e-9);
        assertTrue(g.rooms().get(0).missingSides(0.5).isEmpty());
    }

    @Test
    void aLaterShapeThatFlattensOneWallIsCaught() {
        // The two-walled-shell defect, expressed the way it actually happens: a subsequent shape
        // carves the whole face away. Nothing about the room's own BuildShape changes, which is why
        // a per-shape check cannot see this.
        BuildPlan broken = plan("""
                [{"kind":"box","dx":0,"dy":0,"dz":0,"width":9,"height":6,"depth":9,
                  "block":"minecraft:stone","hollow":true},
                 {"kind":"box","dx":0,"dy":1,"dz":0,"width":9,"height":4,"depth":1,
                  "block":"minecraft:air","hollow":false}]""");
        BuildGeometry g = BuildGeometry.of(broken);
        List<String> missing = g.rooms().get(0).missingSides(0.5);
        assertEquals(List.of("north"), missing, "the whole north face was carved away");
        assertEquals(0.0, g.rooms().get(0).north(), 1e-9);
        assertEquals(1.0, g.rooms().get(0).south(), 1e-9);
    }

    @Test
    void twoMissingWallsAreBothNamed() {
        BuildPlan broken = plan("""
                [{"kind":"box","dx":0,"dy":0,"dz":0,"width":9,"height":6,"depth":9,
                  "block":"minecraft:stone","hollow":true},
                 {"kind":"box","dx":0,"dy":1,"dz":0,"width":9,"height":4,"depth":1,
                  "block":"minecraft:air","hollow":false},
                 {"kind":"box","dx":0,"dy":1,"dz":0,"width":1,"height":4,"depth":9,
                  "block":"minecraft:air","hollow":false}]""");
        BuildGeometry g = BuildGeometry.of(broken);
        assertEquals(List.of("north", "west"), g.rooms().get(0).missingSides(0.5));
    }

    @Test
    void aDoorwayIsNotAMissingWall() {
        // The reason wall health is a fraction and not a boolean. A door is two blocks of one face.
        BuildPlan withDoor = plan("""
                [{"kind":"box","dx":0,"dy":0,"dz":0,"width":9,"height":6,"depth":9,
                  "block":"minecraft:stone","hollow":true},
                 {"kind":"box","dx":4,"dy":1,"dz":0,"width":1,"height":2,"depth":1,
                  "block":"minecraft:air","hollow":false}]""");
        BuildGeometry g = BuildGeometry.of(withDoor);
        assertTrue(g.weakestWall() > 0.9,
                "a doorway took the weakest wall down to " + g.weakestWall()
                        + "; a gate that calls a door a defect would fail every real building");
        assertTrue(g.rooms().get(0).missingSides(0.5).isEmpty());
    }

    @Test
    void aRoundTowerIsMeasuredLikeAnyOtherRoom() {
        // The skin is taken from the shape, not its bounding box, so a cylinder works unchanged.
        BuildGeometry g = BuildGeometry.of(plan("""
                [{"kind":"cylinder","dx":0,"dy":0,"dz":0,"width":11,"height":8,"depth":11,
                  "block":"minecraft:stone_bricks","hollow":true}]"""));
        assertEquals(1, g.rooms().size());
        assertEquals(1.0, g.weakestWall(), 1e-9);
    }

    @Test
    void aSolidBlockIsNotARoomAndIsNotJudgedAsOne() {
        assertTrue(BuildGeometry.of(plan("""
                [{"kind":"box","dx":0,"dy":0,"dz":0,"width":9,"height":6,"depth":9,
                  "block":"minecraft:stone","hollow":false}]""")).rooms().isEmpty());
    }

    // ===================== composition: air carves, later wins =====================

    @Test
    void airCarvesRatherThanFills() {
        BuildGeometry g = BuildGeometry.of(plan("""
                [{"kind":"box","dx":0,"dy":0,"dz":0,"width":3,"height":3,"depth":3,
                  "block":"minecraft:stone","hollow":false},
                 {"kind":"box","dx":1,"dy":1,"dz":1,"width":1,"height":1,"depth":1,
                  "block":"minecraft:air","hollow":false}]"""));
        assertEquals(26, g.solidCount(), "the air block should have removed one of the 27");
        assertFalse(g.isSolid(1, 1, 1));
    }

    @Test
    void boundsComeFromWhatSurvivesNotFromTheShapeList() {
        // A plan whose last act is to erase its own top is shorter than its shapes claim.
        BuildGeometry g = BuildGeometry.of(plan("""
                [{"kind":"box","dx":0,"dy":0,"dz":0,"width":3,"height":9,"depth":3,
                  "block":"minecraft:stone","hollow":false},
                 {"kind":"box","dx":0,"dy":5,"dz":0,"width":3,"height":4,"depth":3,
                  "block":"minecraft:air","hollow":false}]"""));
        assertEquals(4, g.maxY());
        assertEquals(5, g.height());
    }

    // ===================== the roof question, and why it is narrow =====================

    @Test
    void aTallBoxWithAFullLidReadsAsUnfinished() {
        BuildGeometry g = BuildGeometry.of(plan("""
                [{"kind":"box","dx":0,"dy":0,"dz":0,"width":8,"height":40,"depth":8,
                  "block":"minecraft:stone","hollow":true}]"""));
        assertEquals(1.0, g.topCoverage(), 1e-9);
        assertTrue(g.looksUnfinished(), "a 40-tall, 8-wide lidded box is the unfinished-box defect");
    }

    @Test
    void aTowerWithARoofIsFine() {
        BuildGeometry g = BuildGeometry.of(plan("""
                [{"kind":"box","dx":0,"dy":0,"dz":0,"width":8,"height":40,"depth":8,
                  "block":"minecraft:stone","hollow":true},
                 {"kind":"pyramid","dx":0,"dy":40,"dz":0,"width":8,"height":8,"depth":8,
                  "block":"minecraft:deepslate","hollow":false}]"""));
        assertFalse(g.looksUnfinished());
    }

    @Test
    void anArenaFloorIsFlatOnTopAndThatIsCorrect() {
        // THE ANTI-CORRELATION GUARD. Measured on real agent output, a bare "the top must not be
        // flat" fires on the battle arena, the crystal arena and two ocean trenches — all of which
        // are floors. This is that case in miniature, and it must pass.
        BuildGeometry g = BuildGeometry.of(plan("""
                [{"kind":"box","dx":0,"dy":0,"dz":0,"width":60,"height":2,"depth":60,
                  "block":"minecraft:sandstone","hollow":false}]"""));
        assertEquals(1.0, g.topCoverage(), 1e-9);
        assertFalse(g.looksUnfinished(),
                "a wide flat arena floor is not an unfinished tower; a gate that says otherwise is "
                        + "backwards, which is worse than being noisy");
    }

    // ===================== the caps =====================

    @Test
    void aShapeSittingExactlyOnALimitIsFlagged() {
        assertEquals(1, BuildGeometry.capBoundaries(plan("""
                [{"kind":"box","dx":0,"dy":0,"dz":0,"width":96,"height":10,"depth":10,
                  "block":"minecraft:stone","hollow":true}]""")).size());
    }

    @Test
    void anOrdinaryShapeIsNotFlagged() {
        assertTrue(BuildGeometry.capBoundaries(plan(GOOD_ROOM)).isEmpty());
    }

    @Test
    void aSilentlyClampedRequestIsStillCaughtByTheRequestComparison() {
        // The 150-block tower that shipped as 96. capBoundaries() can only SUSPECT this from the
        // built plan; proving it needs the request, which is why the action log had to stop
        // clipping arguments at 600 characters.
        var json = JsonParser.parseString("""
                {"id":"t","name":"t","shapes":[{"kind":"box","dx":0,"dy":0,"dz":0,
                 "width":10,"height":150,"depth":10,"block":"minecraft:stone","hollow":true}]}
                """).getAsJsonObject();
        BuildPlan built = BuildPlan.fromJson(json);
        assertNotNull(built);
        assertEquals(96, built.shapes().get(0).height());
        assertFalse(built.clampNotes(BuildPlan.shapesAsRequested(json)).isEmpty(),
                "asking for 150 and getting 96 must be reported in words");
        assertFalse(BuildGeometry.capBoundaries(built).isEmpty());
    }

    @Test
    void anEmptyPlanIsHandledRatherThanThrowing() {
        BuildGeometry g = BuildGeometry.of(new BuildPlan("t", "t", List.of()));
        assertTrue(g.isEmpty());
        assertEquals(0, g.solidCount());
        assertEquals(0.0, g.topCoverage(), 1e-9);
        assertFalse(g.looksUnfinished());
        assertEquals(1.0, g.weakestWall(), 1e-9);
    }

    // ----: can a child get IN? ----------------------------------------------------------

    private static BuildShape shell(String name, int dx, int dy, int dz,
                                    int w, int h, int d, String block, boolean hollow) {
        return new BuildShape(BuildShape.Kind.BOX, dx, dy, dz, w, h, d, block, hollow,
                BuildShape.Anchor.CORNER, name);
    }

    @Test
    void aSealedShellReportsItsTrappedRoom() {
        // A hollow box with no door: four walls, a lid, and nowhere to walk in from.
        var g = BuildGeometry.of(new BuildPlan("vault", "The Vault",
                List.of(shell("shell", 0, 0, 0, 10, 8, 10, "minecraft:stone", true))));
        var sealed = g.sealedInterior();
        assertFalse(sealed.tooBig());
        assertEquals(8 * 6 * 8, sealed.volume(), "the whole interior is unreachable");
    }

    @Test
    void aDoorwayOpensIt() {
        // P6 -- the check must be SILENT on a healthy building, or the agent learns to skip it.
        var g = BuildGeometry.of(new BuildPlan("vault", "The Vault",
                List.of(shell("shell", 0, 0, 0, 10, 8, 10, "minecraft:stone", true),
                        shell("door", 4, 1, 0, 2, 3, 1, "minecraft:air", false))));
        assertEquals(0, g.sealedInterior().volume(), "a building with a door is not sealed");
    }

    @Test
    void theOneBlockWallIsNamedSoTheAgentCanActOnIt() {
        // P2 -- "there is no way in" is a symptom; "the wall is one block thick HERE" is an
        // instruction. A shell's wall is one block, so a breach must always be found.
        var g = BuildGeometry.of(new BuildPlan("vault", "The Vault",
                List.of(shell("shell", 0, 0, 0, 10, 8, 10, "minecraft:stone", true))));
        int[] breach = g.sealedInterior().breach();
        assertNotNull(breach, "a one-block wall must be locatable");
        assertTrue(g.isSolid(breach[0], breach[1], breach[2]),
                "the named block must be the WALL, not the air either side of it");
    }

    @Test
    void aSolidBlockHasNoInteriorToSeal() {
        // Not hollow: there is no room, so there is nothing to be shut out of.
        var g = BuildGeometry.of(new BuildPlan("rock", "Rock",
                List.of(shell("rock", 0, 0, 0, 6, 6, 6, "minecraft:stone", false))));
        assertEquals(0, g.sealedInterior().volume());
    }

    // ── SEPARATE ROOMS — ─────────────────────────────────────────────────────────────
    //
    // The total was always right; the ADVICE built from it was wrong. `breach` was whichever
    // sealed cell the scan reached first, and the note read "a 1x2 hole there would open it",
    // which is only true of a building with exactly one pocket. The transcript of that going
    // wrong is: a real lighthouse reported 742 sealed blocks and one thinnest spot, the
    // agent cut a doorway through exactly that wall six seconds later, and was answered with 619
    // still sealed at a different spot six blocks up.

    /** The lighthouse's shape: one shell, one floor across the middle, no stairs. */
    private static BuildPlan twoStoreys() {
        return new BuildPlan("tower", "Tower",
                List.of(shell("shell", 0, 0, 0, 10, 12, 10, "minecraft:stone", true),
                        shell("floor", 1, 6, 1, 8, 1, 8, "minecraft:stone", false)));
    }

    @Test
    void twoStoreysWithNoStairsAreTwoSealedRooms() {
        var sealed = BuildGeometry.of(twoStoreys()).sealedInterior();
        assertEquals(2, sealed.rooms().size(),
                "a floor across the middle makes two rooms, not one bigger one");
        assertEquals(sealed.volume(),
                sealed.rooms().stream().mapToInt(BuildGeometry.Sealed.Room::volume).sum(),
                "the rooms must account for every sealed block, or the total is a different fact "
                + "from the parts");
    }

    @Test
    void eachRoomIsBreachedSeparately() {
        // The point of the change: one doorway per room, in ONE call. Sharing a single breach is
        // what made the agent fix one room per round trip and then give up.
        var sealed = BuildGeometry.of(twoStoreys()).sealedInterior();
        var breaches = sealed.rooms().stream().map(BuildGeometry.Sealed.Room::breach).toList();
        for (int[] b : breaches) {
            assertNotNull(b, "every room of a one-block-walled tower has a reachable thin spot");
        }
        assertNotEquals(java.util.Arrays.toString(breaches.get(0)),
                java.util.Arrays.toString(breaches.get(1)),
                "two rooms handed the same doorway is the bug, restated");
    }

    @Test
    void theBiggestRoomIsTheOneNamedFirst() {
        // `breach()` is what every existing caller reads, so it must point at the room most worth
        // opening rather than whichever one the scan order happened to reach first.
        // NB `BuildGeometry.rooms()` is a different list entirely -- the interiors of hollow
        // SHAPES -- and dropping the `.sealedInterior()` here compiled into a comparison against
        // those instead. It failed to compile rather than passing quietly, which is the only
        // reason the two types are allowed to share the word "room".
        var sealed = BuildGeometry.of(new BuildPlan("tower", "Tower",
                List.of(shell("shell", 0, 0, 0, 10, 14, 10, "minecraft:stone", true),
                        shell("floor", 1, 3, 1, 8, 1, 8, "minecraft:stone", false))))
                .sealedInterior();
        assertEquals(2, sealed.rooms().size());
        assertTrue(sealed.rooms().get(0).volume() >= sealed.rooms().get(1).volume(),
                "rooms are biggest-first");
        assertEquals(java.util.Arrays.toString(sealed.rooms().get(0).breach()),
                java.util.Arrays.toString(sealed.breach()),
                "the headline breach is the biggest room's");
    }

    @Test
    void oneRoomStillReportsExactlyOneRoom() {
        // P6, from the other side: the new count must not turn every ordinary sealed shell into a
        // multi-room warning, or the sentence that means something gets skipped with the rest.
        var sealed = BuildGeometry.of(new BuildPlan("vault", "The Vault",
                List.of(shell("shell", 0, 0, 0, 10, 8, 10, "minecraft:stone", true))))
                .sealedInterior();
        assertEquals(1, sealed.rooms().size());
        assertEquals(sealed.volume(), sealed.rooms().get(0).volume());
    }

    @Test
    void aBuildingWithAWayInHasNoRooms() {
        var sealed = BuildGeometry.of(new BuildPlan("vault", "The Vault",
                List.of(shell("shell", 0, 0, 0, 10, 8, 10, "minecraft:stone", true),
                        shell("door", 4, 1, 0, 2, 3, 1, "minecraft:air", false))))
                .sealedInterior();
        assertEquals(0, sealed.volume());
        assertTrue(sealed.rooms().isEmpty(), "no sealed volume means no sealed rooms to list");
        assertNull(sealed.breach(), "and nowhere to send a doorway");
    }

    @Test
    void openingOneRoomLeavesTheOtherReported() {
        // The lighthouse, exactly: cut the door the note named, and the building is still shut.
        // Before this change that second room was invisible until the next build_structure call.
        var withDoor = new BuildPlan("tower", "Tower",
                List.of(shell("shell", 0, 0, 0, 10, 12, 10, "minecraft:stone", true),
                        shell("floor", 1, 6, 1, 8, 1, 8, "minecraft:stone", false),
                        shell("door", 4, 1, 0, 2, 3, 1, "minecraft:air", false)));
        var sealed = BuildGeometry.of(withDoor).sealedInterior();
        assertEquals(1, sealed.rooms().size(),
                "the ground floor is open; the upper one is not");
        assertTrue(sealed.volume() > 0, "and it is still worth saying so");
    }

    // ── A DOOR IS A WAY IN, not a wall — ─────────────────────────────────────────────
    //
    // The hazard the SEALED note created for itself. Told a house had no way in, an agent cut a
    // 1x2 hole and hung a real oak door in it — the correct answer, and the one the building
    // lesson asks for — and was told the house was still sealed, because a closed door is not air.
    // Its next call put minecraft:air where the door's upper half belonged, which satisfies the
    // check and ships half a door. Both calls are in the transcript, 66 seconds apart.

    /** The house from that transcript: a shell, a hole cut in it, and a door hung in the hole. */
    private static BuildPlan houseWithADoor(String doorBlock) {
        return new BuildPlan("house", "House",
                List.of(shell("walls", 0, 0, 0, 13, 6, 11, "minecraft:oak_planks", true),
                        shell("doorway", 6, 1, 0, 1, 2, 1, "minecraft:air", false),
                        shell("door_lower", 6, 1, 0, 1, 1, 1, doorBlock, false),
                        shell("door_upper", 6, 2, 0, 1, 1, 1, doorBlock, false)));
    }

    @Test
    void aHouseWithADoorIsNotSealed() {
        assertEquals(0, BuildGeometry.of(houseWithADoor("minecraft:oak_door")).sealedInterior()
                        .volume(),
                "a child opens a door and walks in; reporting that as no way in punishes the one "
                + "correct answer to the complaint");
    }

    @Test
    void theBlockstateFormIsTheSameDoor() {
        // The transcript's exact string. A door written with its properties must not read as some
        // unknown solid block -- that is the difference between the two calls that were made.
        assertEquals(0, BuildGeometry.of(houseWithADoor(
                        "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false]"))
                        .sealedInterior().volume());
    }

    @Test
    void aDoorStillCountsAsABlockThatWasPlaced() {
        // Only the REACHABILITY question changes. A door is a block: it is built, it is counted,
        // and it is part of the building's own bounds.
        var g = BuildGeometry.of(houseWithADoor("minecraft:oak_door"));
        assertTrue(g.isSolid(6, 1, 0), "the door is a placed block");
        assertFalse(g.blocksAccess(6, 1, 0), "and it is not a barrier");
    }

    @Test
    void glassIsNotAWayIn() {
        // Deliberately narrow. You can see through a window, which is not the same as getting in,
        // and having to break the blocks yourself is the complaint this whole family answers.
        var sealed = BuildGeometry.of(new BuildPlan("box", "Box",
                List.of(shell("walls", 0, 0, 0, 10, 8, 10, "minecraft:stone", true),
                        shell("window", 4, 2, 0, 2, 2, 1, "minecraft:glass", false))))
                .sealedInterior();
        assertTrue(sealed.volume() > 0, "a window is not a door");
    }

    @Test
    void aDoorBuiltOverByAWallIsAWall() {
        // Later shapes overwrite earlier ones, so the opening has to be cleared as well as set.
        // Keeping it would make a bricked-up doorway read as a way in — the failure this check
        // exists for, reintroduced by the fix for it.
        var sealed = BuildGeometry.of(new BuildPlan("house", "House",
                List.of(shell("walls", 0, 0, 0, 13, 6, 11, "minecraft:oak_planks", true),
                        shell("doorway", 6, 1, 0, 1, 2, 1, "minecraft:air", false),
                        shell("door", 6, 1, 0, 1, 2, 1, "minecraft:oak_door", false),
                        shell("bricked_up", 6, 1, 0, 1, 2, 1, "minecraft:stone", false))))
                .sealedInterior();
        assertTrue(sealed.volume() > 0, "the doorway was filled in; there is no way in again");
    }

    @Test
    void aTrapdoorAndAFenceGateAreWaysInToo() {
        for (String b : new String[] {"minecraft:oak_trapdoor", "minecraft:spruce_fence_gate"}) {
            assertEquals(0, BuildGeometry.of(houseWithADoor(b)).sealedInterior().volume(), b);
        }
    }

    @Test
    void theDoorRuleReadsTheBlockNameAndNothingElse() {
        assertTrue(BuildGeometry.isWalkThrough("minecraft:oak_door"));
        assertTrue(BuildGeometry.isWalkThrough("MINECRAFT:IRON_DOOR"));
        assertTrue(BuildGeometry.isWalkThrough("minecraft:oak_door[half=upper]"));
        assertFalse(BuildGeometry.isWalkThrough("minecraft:stone"));
        assertFalse(BuildGeometry.isWalkThrough(null));
        // Not a door, and the suffix test must not be fooled by a name that merely ends in one.
        assertFalse(BuildGeometry.isWalkThrough("minecraft:cobblestone"));
    }

    // ── STOREYS vs VAULTS — the answer to an earlier issue's "how many of these are deliberate" ────────
    //
    // Nine of the fourteen sealed real builds are towers, and in every one the trapped rooms are
    // the gaps between decorative bands. real_05_purple_crystal_lighthouse: glass bands at
    // y=8,16,24…64, sealed rooms of 148 blocks at y=12-15, 20-23, 28-31… — eight storeys, none
    // reachable from the one below. A band across a hollow tower is a FLOOR, and nothing said so.

    /** A tower ringed by a solid band every 8 blocks: the lighthouse shape, minimised. */
    private static BuildPlan bandedTower(int bands) {
        List<BuildShape> shapes = new java.util.ArrayList<>();
        shapes.add(shell("shaft", 0, 0, 0, 9, 8 * bands + 10, 9, "minecraft:stone", true));
        for (int i = 1; i <= bands; i++) {
            shapes.add(shell("band" + i, 1, 8 * i, 1, 7, 1, 7, "minecraft:glass", false));
        }
        return new BuildPlan("tower", "Tower", shapes);
    }

    @Test
    void aTowerCutIntoStoreysByItsOwnBandsIsReportedAsStacked() {
        var sealed = BuildGeometry.of(bandedTower(4)).sealedInterior();
        assertTrue(sealed.rooms().size() >= BuildGeometry.MIN_STOREYS, sealed.rooms().toString());
        assertTrue(sealed.stackedStoreys(),
                "a tower whose rooms sit one above another is a tower with no stairs, and a "
                + "doorway in the outside wall fixes exactly one of them");
    }

    @Test
    void roomsSideBySideAreNotStoreys() {
        // real_08_spikor_castle's four sealed corner towers all span y=4..57. Calling those
        // storeys would send the agent hunting for stairs between things that are nowhere near
        // each other, which is the cry-wolf failure this distinction exists to avoid.
        var sealed = BuildGeometry.of(new BuildPlan("castle", "Castle",
                List.of(shell("t1", 0, 0, 0, 9, 20, 9, "minecraft:stone", true),
                        shell("t2", 20, 0, 0, 9, 20, 9, "minecraft:stone", true),
                        shell("t3", 40, 0, 0, 9, 20, 9, "minecraft:stone", true),
                        shell("t4", 60, 0, 0, 9, 20, 9, "minecraft:stone", true))))
                .sealedInterior();
        assertEquals(4, sealed.rooms().size());
        assertFalse(sealed.stackedStoreys(), "four towers side by side are not four storeys");
    }

    @Test
    void twoStackedRoomsAreNotYetATower() {
        // P6. Two is a cellar under a hall often enough that saying "no stairs" would be noise.
        var sealed = BuildGeometry.of(twoStoreys()).sealedInterior();
        assertEquals(2, sealed.rooms().size());
        assertFalse(sealed.stackedStoreys(), "two is a coincidence; three in a column is a pattern");
    }

    @Test
    void everyStoreyIsStillBreachedSeparately() {
        // The storey verdict must not cost the per-room detail — a ladder needs a place to go.
        var sealed = BuildGeometry.of(bandedTower(4)).sealedInterior();
        assertTrue(sealed.stackedStoreys());
        for (var r : sealed.rooms()) {
            assertTrue(r.maxY() >= r.minY(), "a room must know where it is: " + r);
        }
    }
}
