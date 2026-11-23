package com.moddingfromamod.builder;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The default that was missing: a building a child can walk into is not left pitch dark. */
class BuildLightingTest {

    static BuildShape box(int dx, int dy, int dz, int w, int h, int d, String block,
                                  boolean hollow) {
        return new BuildShape(BuildShape.Kind.BOX, dx, dy, dz, w, h, d, block, hollow);
    }

    static BuildPlan plan(BuildShape... shapes) {
        return new BuildPlan("keep", "The Keep", List.of(shapes));
    }

    /** A room: hollow walls with a doorway through them. */
    private static final BuildPlan ROOM = plan(
            box(0, 0, 0, 21, 10, 21, "minecraft:stone", true),
            box(10, 1, 0, 1, 3, 1, "minecraft:air", false));

    @Test
    @DisplayName("a dark room gets light")
    void aDarkRoomGetsLight() {
        List<BuildShape> lights = BuildLighting.lightsFor(ROOM);
        assertTrue(!lights.isEmpty(), "a hollow castle with no light source must be lit");
        for (BuildShape s : lights) {
            assertEquals(BuildLighting.LIGHT_BLOCK, s.block());
            assertEquals(1, s.width());
            assertTrue(s.name().startsWith("light_"),
                    "named, so the agent can replace them with something nicer: " + s.name());
        }
    }

    @Test
    @DisplayName("a room the agent already lit is left alone")
    void anAlreadyLitRoomIsLeftAlone() {
        // One lantern means it was thinking about light. Adding more is second-guessing a design.
        List<BuildShape> withLantern = new ArrayList<>(ROOM.shapes());
        withLantern.add(box(10, 8, 10, 1, 1, 1, "minecraft:lantern", false));
        assertTrue(BuildLighting.lightsFor(new BuildPlan("k", "K", withLantern)).isEmpty());
    }

    @Test
    @DisplayName("a lava moat OUTSIDE does not count as lighting the rooms inside")
    void lightIsJudgedWhereItStands() {
        // A real castle from the build corpus. Judged over the whole plan, one ring of lava around the outside
        // marked the entire castle as lit, and every room in it photographed pitch black. Light is
        // a local thing and has to be judged where the dark is.
        List<BuildShape> moated = new ArrayList<>(ROOM.shapes());
        moated.add(box(-30, 0, -30, 16, 1, 81, "minecraft:lava", false));
        moated.add(box(35, 0, -30, 16, 1, 81, "minecraft:lava", false));
        assertTrue(!BuildLighting.lightsFor(new BuildPlan("k", "K", moated)).isEmpty(),
                "the moat is outside; the hall is still dark");

        // …while lava in the room itself still counts, so a lava-lit hall is left alone.
        assertTrue(!BuildLighting.litNearby(ComposedPlan.of(new BuildPlan("k", "K", moated)), 10, 8, 10),
                "the moat is more than SPACING away from the middle of the hall");
    }

    @Test
    @DisplayName("a solid lump gets nothing — there is no inside to be dark")
    void aSolidLumpGetsNothing() {
        assertTrue(BuildLighting.lightsFor(
                plan(box(0, 0, 0, 12, 8, 12, "minecraft:stone", false))).isEmpty());
    }

    @Test
    @DisplayName("it never spends more of the shape budget than it has")
    void itRespectsTheShapeBudget() {
        assertTrue(BuildLighting.lightsFor(ROOM).size() <= BuildLighting.MAX_LIGHTS);

        // A building already at the cap gets none rather than being refused for length later.
        List<BuildShape> full = new ArrayList<>();
        full.add(box(0, 0, 0, 21, 10, 21, "minecraft:stone", true));
        while (full.size() < BuildPlan.MAX_SHAPES) {
            full.add(box(full.size(), 0, 0, 1, 1, 1, "minecraft:stone", false));
        }
        assertTrue(BuildLighting.lightsFor(new BuildPlan("k", "K", full)).isEmpty(),
                "no budget left, so it stays out of the way");
    }

    @Test
    @DisplayName("the lights STAND ON THE FLOOR, where a person is")
    void theLightsStandOnTheFloor() {
        // CHANGED DELIBERATELY. They used to hang from the ceiling, and that is what made a
        // 27-block-high great hall photograph at brightness 3–10 out of 255: light falls off one
        // level per block, so a lantern in the ceiling arrives at head height as nothing. A lantern
        // resting on a solid floor is also a legal placement, where one floating mid-air is not.
        BuildGeometry g = BuildGeometry.of(ROOM);
        for (BuildShape s : BuildLighting.lightsFor(ROOM)) {
            assertTrue(!g.isSolid(s.dx(), s.dy(), s.dz()),
                    "a lantern inside a wall lights nothing");
            assertTrue(g.isSolid(s.dx(), s.dy() - 1, s.dz()),
                    "it needs a floor to stand on — a floating lantern is not a placement");
        }
    }

    @Test
    @DisplayName("a big hall gets more light than a small room")
    void theCountFollowsTheBuilding() {
        // The flat cap of 8 was one lantern per ~350 square blocks in a 52-wide keep.
        List<BuildShape> small = new ArrayList<>();
        small.add(box(0, 0, 0, 12, 8, 12, "minecraft:stone", true));
        List<BuildShape> big = new ArrayList<>();
        big.add(box(0, 0, 0, 52, 12, 52, "minecraft:stone", true));

        int inSmall = BuildLighting.lightsFor(new BuildPlan("s", "S", small)).size();
        int inBig = BuildLighting.lightsFor(new BuildPlan("b", "B", big)).size();
        assertTrue(inBig > inSmall,
                "a 52-wide keep got " + inBig + " lanterns and a 12-wide room got " + inSmall);
        assertTrue(inBig <= BuildLighting.MAX_LIGHTS_CEILING,
                "every lantern is a shape, and the shape budget is the real constraint");
    }

    @Test
    @DisplayName("they are spread out, not piled in one corner")
    void theyAreSpreadOut() {
        List<BuildShape> lights = BuildLighting.lightsFor(
                plan(box(0, 0, 0, 40, 10, 40, "minecraft:stone", true)));
        for (int i = 0; i < lights.size(); i++) {
            for (int j = i + 1; j < lights.size(); j++) {
                double d = Math.hypot(lights.get(i).dx() - lights.get(j).dx(),
                                      lights.get(i).dz() - lights.get(j).dz());
                assertTrue(d >= BuildLighting.SPACING - 1,
                        "two lanterns " + d + " apart is one lantern and a spare");
            }
        }
    }

    @Test
    @DisplayName("nothing is added silently — the agent is told, and told how to change it")
    void nothingIsAddedSilently() {
        // This codebase's own rule: a cap or an edit nobody is told about is how a child ends up
        // with a building nobody meant. Three of BuildPlan's four caps were silent once.
        String note = BuildLighting.note(BuildLighting.lightsFor(ROOM), ROOM);
        assertTrue(note.contains("lantern"), note);
        assertTrue(note.contains("light_"), "it names them so they can be replaced: " + note);
        assertTrue(note.contains("monsters"), "and says why it matters: " + note);
        assertEquals("", BuildLighting.note(List.of(), ROOM), "silent when it did nothing");
    }

    @Test
    @DisplayName("an open-air structure is not 'inside' anything")
    void anOpenAirStructureIsNotInside() {
        // A wall and a floor, no roof: an arena, a plaza, a bandstand. Hanging lanterns off the
        // sky is not what "light the inside" means, and it spends the budget.
        assertTrue(BuildLighting.lightsFor(
                plan(box(0, 0, 0, 30, 1, 30, "minecraft:stone", false))).isEmpty());
    }

    /**
     * <b>: the lighting note used to congratulate itself on a room nobody can enter.</b>
     *
     * <p>13 of 19 real buildings enclose a sealed room, and this method fires <i>precisely</i>
     * when a building encloses a space — so on the common case it hung lanterns in the
     * unreachable part and reported a building "a child walks into" as brighter. Every other
     * signal agreed: four walls, a solid lid, and now light.
     */
    @Test
    @DisplayName("lighting a room with no way in says so, instead of reporting an improvement")
    void aSealedRoomIsNotReportedAsAWalkInBuilding() {
        // The same room, with the doorway taken out. Nothing else differs.
        BuildPlan sealed = plan(box(0, 0, 0, 21, 10, 21, "minecraft:stone", true));
        List<BuildShape> lights = BuildLighting.lightsFor(sealed);
        assertTrue(!lights.isEmpty(),
                "the premise of this test is that lighting still fires on a sealed room");

        String note = BuildLighting.note(lights, sealed);
        assertTrue(note.contains("NO WAY IN"),
                "the note must not leave the agent believing the room is reachable: " + note);
        assertTrue(note.contains("doorway"), "and must say what to do about it: " + note);

        // And the control, which is what stops this becoming noise on every building (P6): the
        // room that HAS a doorway must not be warned about.
        String open = BuildLighting.note(BuildLighting.lightsFor(ROOM), ROOM);
        assertFalse(open.contains("NO WAY IN"),
                "a building with a door is not sealed and must not be told it is: " + open);
    }

    /**
     * <b>The half that was written, tested, and never called.</b>
     *
     * <p>{@code note(added, plan)} carried the size-vs-lantern-count sentence, and both production
     * call sites used a one-argument overload that passed {@code null} — so the fact never reached
     * an agent. The overload is gone, which makes this a compile error rather than a rule to
     * remember; this asserts the sentence itself still arrives.
     */
    @Test
    @DisplayName("the note says how big the room is per lantern, not just how many were hung")
    void theNoteSaysWhetherItWasEnough() {
        String note = BuildLighting.note(BuildLighting.lightsFor(ROOM), ROOM);
        assertTrue(note.contains("square blocks"),
                "how big the room is per light is the fact the agent cannot see: " + note);
        assertTrue(note.contains("-wide building"), note);
    }

    // ──: light is counted from the building as it will stand, not from the shapes drawn ──

    /**
     * The old reading, kept here as the control: every cell a light shape DECLARES, from the exact
     * set {@code BuildLighting} matched. A plan whose lights all survive must get the
     * same answer from it as from the composed reading.
     */
    private static final java.util.Set<String> DECLARED_LIGHTS = java.util.Set.of(
            "minecraft:lantern", "minecraft:soul_lantern", "minecraft:torch",
            "minecraft:soul_torch", "minecraft:redstone_torch", "minecraft:glowstone",
            "minecraft:sea_lantern", "minecraft:shroomlight", "minecraft:froglight",
            "minecraft:ochre_froglight", "minecraft:verdant_froglight",
            "minecraft:pearlescent_froglight", "minecraft:jack_o_lantern",
            "minecraft:campfire", "minecraft:soul_campfire", "minecraft:candle",
            "minecraft:end_rod", "minecraft:beacon", "minecraft:conduit",
            "minecraft:magma_block", "minecraft:lava", "minecraft:crying_obsidian",
            "minecraft:redstone_lamp", "minecraft:copper_bulb", "minecraft:amethyst_cluster");

    static boolean litAsDrawn(BuildPlan plan, int x, int y, int z) {
        return litAsDrawn(plan, x, y, z, DECLARED_LIGHTS::contains);
    }

    /** The same shape-by-shape reading with another idea of what a light is. */
    static boolean litAsDrawn(BuildPlan plan, int x, int y, int z,
                                      java.util.function.Predicate<String> isLight) {
        boolean[] near = {false};
        for (BuildShape s : plan.shapes()) {
            if (!isLight.test(s.block()) || near[0]) {
                continue;
            }
            s.forEachBlock((lx, ly, lz) -> {
                if (Math.abs(lx - x) <= BuildLighting.SPACING && Math.abs(ly - y) <= BuildLighting.SPACING
                        && Math.abs(lz - z) <= BuildLighting.SPACING) {
                    near[0] = true;
                }
            });
        }
        return near[0];
    }

    static boolean nearThrone(BuildShape l) {
        return Math.abs(l.dx()) <= 6 && Math.abs(l.dz() - 32) <= 3;
    }

    @Test
    @DisplayName("#1386: the starter castle (the template, before any wish) is lit the same way")
    void theStarterCastle() throws Exception {
        BuildPlan starter = ComposedLightCensusTest.starter("lava_moat_castle");
        // Since 2026-11-12 the starter brings its own lights, set into its floor (the owner found it dark on film:
        // its only lights hung a storey over the hall). Whatever the lighter still adds goes on the floor too.
        List<BuildShape> lights = BuildLighting.lightsFor(starter);
        assertTrue(lights.stream().allMatch(l -> l.dy() <= 2), "on the floor: " + lights);
        assertTrue(DarkInterior.of(starter).areas().stream().noneMatch(a -> a.y() <= 2),
                "no dark stretch of floor where he walks: " + DarkInterior.note(DarkInterior.of(starter)));
    }

    @Test
    @DisplayName("#1386: a plan whose light shapes all survive gets exactly the answer it got before")
    void intactLightsAreUnchanged() {
        // Lanterns and glowstone, all on top of everything, nothing written over them.
        BuildPlan intact = plan(
                box(0, 0, 0, 41, 10, 41, "minecraft:stone_bricks", true),
                box(20, 1, 0, 1, 3, 1, "minecraft:air", false),
                box(5, 1, 5, 1, 1, 1, "minecraft:lantern", false),
                box(30, 8, 30, 3, 1, 3, "minecraft:glowstone", false));
        ComposedPlan built = ComposedPlan.of(intact);
        for (int x = -2; x <= 42; x++) {
            for (int y = 0; y <= 10; y++) {
                for (int z = -2; z <= 42; z++) {
                    assertEquals(litAsDrawn(intact, x, y, z), BuildLighting.litNearby(built, x, y, z),
                            "at " + x + "," + y + "," + z);
                }
            }
        }
        // And over the real corpus: every build with no light cell overwritten reads the same.
        // Same idea of a light on both sides (what is ON once placed), so this isolates the
        // composition: the lit-state half is pinned separately, below.
        int same = 0;
        for (var r : com.moddingfromamod.CorpusSource.realBuilds()) {
            if (ComposedLightCensusTest.census(r.id(), r.plan()).overwritten() != 0) {
                continue;
            }
            ComposedPlan c = ComposedPlan.of(r.plan());
            BuildFraming.Bounds b = BuildFraming.bounds(c);
            if (b == null) {
                continue;
            }
            for (int x = b.minX(); x <= b.maxX(); x += 3) {
                for (int y = b.minY(); y <= b.maxY(); y += 3) {
                    for (int z = b.minZ(); z <= b.maxZ(); z += 3) {
                        assertEquals(litAsDrawn(r.plan(), x, y, z, b2 -> DarkInterior.emission(b2) > 0),
                                BuildLighting.litNearby(c, x, y, z),
                                r.id() + " at " + x + "," + y + "," + z);
                    }
                }
            }
            same++;
        }
        System.out.println("#1386: " + same + " intact builds read the same");
        assertTrue(same >= (com.moddingfromamod.CorpusSource.real() ? 10 : FIXTURE_INTACT), "the corpus of intact builds did not load: " + same);
    }

    /** The intact builds the public fixture (every shipped preset) holds: its own floor, measured. */
    static final int FIXTURE_INTACT = 15;

    @Test
    @DisplayName("#1386: a lamp the plan lights is a light (a real build placed unlit is in BuildLightingRealBuildsTest)")
    void aLitLampIsALight() {
        assertTrue(BuildLighting.litNearby(ComposedPlan.of(plan(
                box(0, 0, 0, 1, 1, 1, "minecraft:redstone_lamp[lit=true]", false))), 0, 1, 0),
                "…and one the plan lights does");
        assertFalse(BuildLighting.litNearby(ComposedPlan.of(plan(
                box(0, 0, 0, 1, 1, 1, "minecraft:redstone_lamp", false))), 0, 1, 0),
                "an unpowered lamp lights nothing");
    }
}
