package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonParser;
import com.moddingfromamod.builder.BuildFooting.Action;
import com.moddingfromamod.builder.BuildFooting.Column;
import com.moddingfromamod.builder.BuildFooting.Decision;
import com.moddingfromamod.builder.BuildFooting.Fill;
import org.junit.jupiter.api.Test;

/**
 * The placement rule for a building, one row per surface: a
 * slope gets a footing, water an island, a long drop a set-down, lava an obsidian footing — never
 * anything under the parts designed to be up high, and nothing at all for a floated build.
 */
class BuildFootingTest {

    /** What is under a column, as a function of where it is: the terrain of one row. */
    interface Terrain {
        Column at(int x, int z);
    }

    /**
     * The world, as {@link BuildFooting.World} reads it: what is below {@code fromY}. A column
     * whose ground reaches up past {@code fromY} answers with the block just under it — the build
     * is sunk into it there, which is what the real scan reports.
     */
    static BuildFooting.World world(Terrain t) {
        return (x, z, fromY) -> {
            Column c = t.at(x, z);
            if (c.under() == BuildFooting.Under.NOTHING || c.under() == BuildFooting.Under.UNLOADED) {
                return c;
            }
            if (c.top() >= fromY) {
                return new Column(c.under(), fromY - 1, c.bottom(), c.block(), c.leaves());
            }
            return c;
        };
    }

    static BuildShape box(String name, int dx, int dy, int dz, int w, int h, int d, String block, boolean hollow) {
        return new BuildShape(BuildShape.Kind.BOX, dx, dy, dz, w, h, d, block, hollow, BuildShape.Anchor.CORNER, name);
    }

    /** A 5×5 house standing on its floor at y=0: what the garage and the carwash are. */
    static BuildPlan house() {
        return new BuildPlan("house", "house", List.of(
                box("floor", 0, 0, 0, 5, 1, 5, "minecraft:oak_planks", false),
                box("walls", 0, 1, 0, 5, 4, 5, "minecraft:oak_planks", true)));
    }

    static Decision decide(Terrain t, BuildPlan plan, int ay) {
        return BuildFooting.decide(world(t), 100, ay, 200, BuildFooting.footprint(plan), false, true);
    }

    static long count(Decision d, String block) {
        return d.fills().stream().filter(f -> f.block().equals(block)).count();
    }

    // ── the rows ────────────────────────────────────────────────────────────────────────────

    @Test
    void flatGroundNeedsNothing() {
        Decision d = decide((x, z) -> Column.ground(63, "minecraft:grass_block"), house(), 64);
        assertEquals(Action.NONE, d.action());
        assertTrue(d.fills().isEmpty());
        assertEquals("", BuildFooting.forTheAgent(d, 0, 0, 0));
    }

    @Test
    void aSlopeGetsAFootingOfTheTerrainItStandsOn() {
        // The hill falls away one block per column east of the anchor: 63, 62, 61, 60, 59.
        Decision d = decide((x, z) -> Column.ground(63 - (x - 100), "minecraft:grass_block"), house(), 64);
        assertEquals(Action.FILLED, d.action());
        assertEquals(64, d.anchorY(), "a footing never moves the build");
        // Columns 1..4 east are 1..4 short: (1+2+3+4) × 5 rows of dirt, the soil under grass.
        assertEquals(50, d.fills().size());
        assertEquals(50, count(d, BuildFooting.DIRT));
        assertEquals(20, d.footing());
        for (Fill f : d.fills()) {
            assertTrue(f.y() < 64 && f.y() > 63 - (f.x() - 100), "a fill outside the gap: " + f);
        }
        assertTrue(BuildFooting.forTheAgent(d, 0, 0, 0).contains("filled them down to the ground"));
    }

    @Test
    void aDipOnStoneIsFilledWithStone() {
        Decision d = decide((x, z) -> x == 102 && z == 202 ? Column.ground(61, "minecraft:stone")
                : Column.ground(63, "minecraft:stone"), house(), 64);
        assertEquals(Action.FILLED, d.action());
        assertEquals(2, d.fills().size());
        assertEquals(2, count(d, "minecraft:stone"));
    }

    @Test
    void waterGetsAnIslandFromTheSeabedToSeaLevelWithABeach() {
        // Sea surface at 62, seabed at 55: a wish on the water stands at 63.
        Decision d = decide((x, z) -> Column.water(62, 55, "minecraft:gravel"), house(), 63);
        assertEquals(Action.FILLED, d.action());
        assertEquals(63, d.anchorY(), "an island never moves the build");
        Set<String> top = new HashSet<>();
        int under = 0, shore = 0;
        for (Fill f : d.fills()) {
            assertTrue(f.y() >= 56 && f.y() <= 62, "the island reaches from the seabed to sea level: " + f);
            boolean inFootprint = f.x() >= 100 && f.x() < 105 && f.z() >= 200 && f.z() < 205;
            if (f.y() == 62) {
                top.add(f.block());
                if (inFootprint) {
                    under++;
                } else {
                    shore++;
                }
            }
        }
        assertEquals(25, under, "every column under the build is filled to sea level");
        assertTrue(shore >= 4 * 5 * BuildFooting.SHORE, "a beach at least two wide round it: " + shore);
        assertEquals(Set.of(BuildFooting.SAND), top, "the island's top is sand");
        // Sand never goes in before what holds it up.
        Fill prev = null;
        for (Fill f : d.fills()) {
            assertTrue(prev == null || prev.y() <= f.y(), "fills must go bottom first");
            prev = f;
        }
        for (Fill f : d.fills()) {
            if (f.block().equals(BuildFooting.SAND) && f.y() > 56) {
                long x = f.x(), z = f.z(), y = f.y();
                assertTrue(d.fills().stream().anyMatch(g -> g.x() == x && g.z() == z && g.y() == y - 1),
                        "sand at " + f + " has nothing under it");
            }
        }
        assertTrue(BuildFooting.forTheAgent(d, 0, 0, 0).contains("little island"));
    }

    @Test
    void aDeepOceanIslandIsCapped() {
        Decision d = decide((x, z) -> Column.water(62, Integer.MIN_VALUE, ""), house(), 63);
        int lowest = d.fills().stream().mapToInt(Fill::y).min().orElseThrow();
        assertEquals(62 - BuildFooting.ISLAND_DEPTH + 1, lowest);
        assertTrue(d.fills().stream().filter(f -> f.y() == lowest).allMatch(f -> f.block().equals(BuildFooting.STONE)),
                "an island that stops short of the seabed stands on stone, which does not fall");
    }

    @Test
    void aTreetopIsSetDownOnTheGroundBelow() {
        // Aimed at a tree: the spot settled on its top log at 74 (y=75); the ground is at 63 and
        // the look down passed through the canopy everywhere but the trunk.
        Terrain tree = (x, z) -> x == 102 && z == 202 ? Column.ground(74, "minecraft:oak_log")
                : Column.ground(63, "minecraft:grass_block").throughLeaves();
        Decision d = decide(tree, house(), 75);
        assertEquals(Action.SET_DOWN, d.action());
        assertTrue(d.moved());
        assertEquals(64, d.anchorY(), "it stands on the ground, not on the tree");
        assertEquals(11, d.dropped());
        assertTrue(d.fills().isEmpty(), "no tower of fill: " + d.fills().size());
        assertEquals("Your house is just below — the spot you picked was up in the treetops!",
                BuildFooting.told("house", d));
        assertTrue(BuildFooting.forTheAgent(d, 100, 64, 200).contains("set it down on the ground below"));
    }

    @Test
    void aBuildInTheSkyAboveTheGroundIsSetDown() {
        Decision d = decide((x, z) -> Column.ground(63, "minecraft:grass_block"), house(), 90);
        assertEquals(Action.SET_DOWN, d.action());
        assertEquals(64, d.anchorY());
        assertEquals("Your castle is down on the ground below — the spot you picked was up in the air "
                + "with nothing under it!", BuildFooting.told("castle", d));
    }

    @Test
    void highAboveTheSeaIsSetDownOntoTheWaterAndGivenItsIsland() {
        Decision d = decide((x, z) -> Column.water(62, 55, "minecraft:sand"), house(), 80);
        assertEquals(Action.SET_DOWN, d.action());
        assertEquals(63, d.anchorY());
        assertTrue(d.island() >= 25);
        assertTrue(d.fills().stream().allMatch(f -> f.y() <= 62));
    }

    @Test
    void lavaGetsAnObsidianFooting() {
        Decision d = decide((x, z) -> Column.lava(40), house(), 41);
        assertEquals(Action.FILLED, d.action());
        assertEquals(25, d.lava());
        assertEquals(25, d.fills().size());
        assertTrue(d.fills().stream().allMatch(f -> f.y() == 40 && f.block().equals(BuildFooting.OBSIDIAN)));
        assertTrue(BuildFooting.forTheAgent(d, 0, 0, 0).contains("obsidian"));
    }

    @Test
    void aCliffEdgeStaysOnTheClifftopAndTheOverhangIsReportedNotFilled() {
        // Three of five columns east are on the cliff; the last two hang over a 20-block drop.
        Decision d = decide((x, z) -> x < 103 ? Column.ground(63, "minecraft:stone")
                : Column.ground(43, "minecraft:stone"), house(), 64);
        assertEquals(64, d.anchorY(), "most of it is on the clifftop: it stays");
        assertFalse(d.moved());
        assertEquals(10, d.hanging());
        assertTrue(d.fills().isEmpty(), "no tower of fill under the overhang");
        assertTrue(BuildFooting.forTheAgent(d, 0, 0, 0).contains("still over a drop"));
    }

    @Test
    void theVoidIsNothingBelowAndNothingMoves() {
        Decision d = decide((x, z) -> Column.nothing(), house(), 64);
        assertEquals(Action.NOTHING_BELOW, d.action());
        assertEquals(64, d.anchorY());
        assertTrue(d.fills().isEmpty());
    }

    @Test
    void unloadedWorldDecidesNothing() {
        Decision d = decide((x, z) -> x == 104 ? Column.unloaded() : Column.water(62, 55, ""), house(), 63);
        assertEquals(Action.UNLOADED, d.action());
        assertTrue(d.fills().isEmpty());
    }

    @Test
    void aRaisedAnchorFloatsOnPurpose() {
        Decision d = BuildFooting.decide(world((x, z) -> Column.ground(40, "minecraft:dirt")), 0, 90, 0,
                BuildFooting.footprint(house()), true, true);
        assertEquals(Action.FLOATED, d.action());
        assertEquals(90, d.anchorY());
        assertTrue(d.fills().isEmpty());
    }

    @Test
    void aRebuildInPlaceIsFilledButNeverMoved() {
        Decision d = BuildFooting.decide(world((x, z) -> Column.ground(70, "minecraft:dirt")), 0, 75, 0,
                BuildFooting.footprint(house()), false, false);
        assertEquals(75, d.anchorY());
        assertFalse(d.moved());
        assertEquals(25 * 4, d.fills().size(), "a four-deep gap is still a footing");
    }

    // ── what is designed to touch the ground ────────────────────────────────────────────────

    @Test
    void aSkyCastleIsAllAboveGroundAndHasNoFootprint() {
        BuildPlan sky = new BuildPlan("sky_castle", "Sky Castle", List.of(
                box("base", 0, 20, 0, 9, 1, 9, "minecraft:quartz_block", false),
                box("keep", 2, 21, 2, 5, 6, 5, "minecraft:quartz_block", true)));
        assertTrue(BuildFooting.allAboveGround(sky));
        assertTrue(BuildFooting.footprint(sky).isEmpty());
        Decision d = decide((x, z) -> Column.water(62, 50, ""), sky, 63);
        assertEquals(Action.NONE, d.action(), "nothing of a sky castle is designed to touch the sea");
        assertTrue(d.fills().isEmpty());
    }

    @Test
    void anAirCarveAtTheFloorIsNoBlockAndARingEnclosesItsMiddle() {
        BuildPlan ring = new BuildPlan("ring", "ring", List.of(
                box("tower", 0, 0, 0, 5, 6, 5, "minecraft:stone_bricks", true),
                box("floor_hole", 1, 0, 1, 3, 1, 3, "minecraft:air", false),
                box("door", 2, 0, 0, 1, 2, 1, "minecraft:air", false)));
        List<int[]> fp = BuildFooting.footprint(ring);
        Set<String> cols = new HashSet<>();
        for (int[] c : fp) {
            cols.add(c[0] + "," + c[1]);
        }
        assertTrue(cols.contains("2,2"), "the inside of a ring is under the build too");
        assertTrue(cols.contains("1,1") && cols.contains("3,3"), "a doorway does not open the inside up");
        assertFalse(cols.contains("-1,2"));
        BuildPlan strip = new BuildPlan("strip", "strip", List.of(
                box("path", 0, 0, 0, 5, 1, 1, "minecraft:gravel", false),
                box("gap", 4, 0, 0, 1, 1, 1, "minecraft:air", false)));
        assertEquals(4, BuildFooting.footprint(strip).size(), "an air carve at the floor is no block");
    }

    @Test
    void aBuildDugIntoTheGroundIsMeasuredFromItsBottom() {
        // A floating-island wish: rock hanging to y=-4 under a grass top. Over the sea it digs into
        // the water, and what is filled is the water UNDER its rock, never over it.
        BuildPlan island = new BuildPlan("island", "island", List.of(
                box("rock", 1, -4, 1, 3, 4, 3, "minecraft:stone", false),
                box("top", 0, 0, 0, 5, 1, 5, "minecraft:grass_block", false)));
        List<int[]> fp = BuildFooting.footprint(island);
        assertTrue(fp.stream().anyMatch(c -> c[0] == 2 && c[1] == 2 && c[2] == -4));
        Decision d = decide((x, z) -> Column.water(62, 50, ""), island, 63);
        assertEquals(63, d.anchorY());
        for (Fill f : d.fills()) {
            boolean rock = f.x() >= 101 && f.x() <= 103 && f.z() >= 201 && f.z() <= 203;
            if (rock) {
                assertTrue(f.y() <= 63 - 4 - 1, "filled where its own rock goes: " + f);
            }
        }
    }

    /**
     * <b>The treehouse </b>, the starter that asked the question: on a slope, its trunk and
     * the foot of its front ladder get a footing down to the ground; the 15-wide platform at
     * {@code y = 12} gets NOTHING under it, so it keeps its height.
     */
    @Test
    void theTreehouseOnASlopeGetsFootingsUnderItsTrunkAndLaddersOnly() throws Exception {
        BuildPlan th = BuildPlan.fromJson(JsonParser.parseString(Files.readString(
                Path.of("src/main/resources/starter-library/buildings/secret_treehouse/build.json")))
                .getAsJsonObject());
        assertFalse(BuildFooting.allAboveGround(th));
        Set<String> ground = new HashSet<>();
        for (int[] c : BuildFooting.footprint(th)) {
            assertEquals(0, c[2]);
            ground.add(c[0] + "," + c[1]);
        }
        assertTrue(ground.contains("7,-1"), "the front ladder's foot is designed to touch the ground");
        assertTrue(ground.contains("7,7"), "the trunk is");
        assertTrue(ground.contains("7,0"), "and the log post the front ladder hangs on (#1374)");
        assertFalse(ground.contains("0,0"), "the platform's corner is up at y=12, not on the ground");
        assertEquals(27, ground.size(), "the 5×5 trunk, the front ladder and its post: " + ground);

        // A slope falling away to the south: 1 block per 4.
        Decision d = decide((x, z) -> Column.ground(63 - Math.max(0, (z - 200)) / 4, "minecraft:grass_block"), th, 64);
        assertEquals(Action.FILLED, d.action());
        assertEquals(64, d.anchorY());
        for (Fill f : d.fills()) {
            String col = (f.x() - 100) + "," + (f.z() - 200);
            assertTrue(ground.contains(col), "filled under a part designed to be up high: " + col);
        }
        assertTrue(d.fills().size() > 0);
    }

    @Test
    void theTreehouseAimedAtARealTreeIsSetDownNotStoodOnTheCanopy() throws Exception {
        BuildPlan th = BuildPlan.fromJson(JsonParser.parseString(Files.readString(
                Path.of("src/main/resources/starter-library/buildings/secret_treehouse/build.json")))
                .getAsJsonObject());
        Decision d = decide((x, z) -> Column.ground(63, "minecraft:grass_block").throughLeaves(), th, 72);
        assertEquals(Action.SET_DOWN, d.action());
        assertEquals(64, d.anchorY());
        assertTrue(BuildFooting.told("Your Secret Treehouse".replace("Your ", ""), d).contains("treetops"));
    }

    // ── matching the terrain ────────────────────────────────────────────────────────────────

    @Test
    void theFootingMatchesTheGround() {
        assertEquals(BuildFooting.DIRT, BuildFooting.material("minecraft:grass_block"));
        assertEquals(BuildFooting.DIRT, BuildFooting.material("minecraft:podzol"));
        assertEquals("minecraft:stone", BuildFooting.material("minecraft:stone"));
        assertEquals("minecraft:sand", BuildFooting.material("minecraft:sand"));
        assertEquals("minecraft:red_terracotta", BuildFooting.material("minecraft:red_terracotta"));
        assertEquals("minecraft:deepslate", BuildFooting.material("deepslate"));
        assertEquals(BuildFooting.DIRT, BuildFooting.material("minecraft:oak_log"), "a trunk is no terrain");
        assertEquals(BuildFooting.DIRT, BuildFooting.material("create:andesite_casing"));
        assertEquals(BuildFooting.DIRT, BuildFooting.material(null));
    }

    @Test
    void nothingIsSaidWhenNothingMoved() {
        assertNull(BuildFooting.told("house", decide((x, z) -> Column.water(62, 55, ""), house(), 63)));
        assertNull(BuildFooting.told("house", null));
    }

    @Test
    void everyFillIsUnderTheFloorAndNoColumnIsFilledTwice() {
        List<Terrain> rows = new ArrayList<>(List.of(
                (x, z) -> Column.ground(63 - (x - 100), "minecraft:grass_block"),
                (x, z) -> Column.water(62, 55, ""),
                (x, z) -> Column.lava(40),
                (x, z) -> x < 102 ? Column.water(62, 58, "") : Column.ground(60, "minecraft:sand")));
        for (Terrain t : rows) {
            Decision d = decide(t, house(), 63);
            Set<String> seen = new HashSet<>();
            for (Fill f : d.fills()) {
                assertTrue(f.y() < d.anchorY(), "a fill at or above the floor: " + f);
                assertTrue(seen.add(f.x() + "," + f.y() + "," + f.z()), "filled twice: " + f);
            }
        }
    }
}
