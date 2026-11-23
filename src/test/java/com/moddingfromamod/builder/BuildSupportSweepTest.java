package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.google.gson.JsonParser;
import com.moddingfromamod.wish.RealBuilds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Every building we already have can stand where it was designed to stand.</b>
 *
 * <p>The library's starters are served to every child, so one with a ladder that hangs on nothing
 * ships that ladder to all of them. Before this test both the Secret Treehouse (three scaffolding
 * columns, two standing in mid-air off a slope or a tree) and the Underwater Dome (7 of its 22
 * ladder cells with nothing behind them) did exactly that, and nothing could notice.
 *
 * <p>The real builds are FINISHED wishes and are never repaired here (CLAUDE.md, "improve the
 * generator, never the artifact"); a real build that does not stand is named in {@link #KNOWN}
 * with its count, so the sweep is green, the defect is on record, and a NEW one fails.
 */
class BuildSupportSweepTest {

    private static final Path STARTERS = Path.of("src/main/resources/starter-library/buildings");

    /**
     * Real builds that do not stand, by id → how many blocks. Measured 2026-10-04 (an earlier issue's census):
     * the July battle arena's red sand floats over air and falls the moment it is placed.
     */
    private static final Map<String, Integer> KNOWN = Map.of("real_18_battle_arena", 68);

    static List<BuildPlan> starters() throws IOException {
        List<BuildPlan> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(STARTERS)) {
            for (Path d : dirs.sorted().toList()) {
                Path f = d.resolve("build.json");
                if (Files.exists(f)) {
                    out.add(BuildPlan.fromJson(JsonParser.parseString(Files.readString(f))
                            .getAsJsonObject()));
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("every starter-library building stands: no ladder without a wall, nothing that falls")
    void everyStarterStands() throws IOException {
        List<BuildPlan> plans = starters();
        assertTrue(plans.size() >= 5, "the starter buildings did not load: " + plans.size());
        Map<String, String> bad = new TreeMap<>();
        for (BuildPlan p : plans) {
            List<BuildSupport.Problem> problems = BuildSupport.check(p);
            if (!problems.isEmpty()) {
                bad.put(p.id(), problems.size() + ": " + problems.get(0).describe());
            }
            assertEquals(0, BuildSupport.groundOnlyScaffolding(p),
                    p.id() + " climbs on scaffolding that stands only on the ground under it");
            assertEquals(p, BuildSupport.autoFace(p).plan(),
                    p.id() + " has a ladder or wall torch with no facing; a starter names its own");
        }
        assertEquals(Map.of(), bad, "a starter building has parts that cannot stand");
    }

    @Test
    @DisplayName("every Builder preset building stands too: nothing that falls, no ladder on nothing")
    void everyBuilderPresetStands() throws IOException {
        // The owner, grading the presets (2026-11-10): the old treehouse "fell out the bottom" when it was wished in
        // the sky. This sweep covered the starters only; the Builder's own presets are served to every child as well.
        List<BuildPlan> plans = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(Path.of("src/main/resources/builder-library/buildings"))) {
            for (Path d : dirs.sorted().toList()) {
                Path f = d.resolve("build.json");
                if (Files.exists(f)) {
                    plans.add(BuildPlan.fromJson(JsonParser.parseString(Files.readString(f)).getAsJsonObject()));
                }
            }
        }
        assertTrue(plans.size() >= 10, "the Builder presets did not load: " + plans.size());
        Map<String, String> bad = new TreeMap<>();
        for (BuildPlan p : plans) {
            List<BuildSupport.Problem> problems = BuildSupport.check(p);
            if (!problems.isEmpty()) {
                bad.put(p.id(), problems.size() + ": " + problems.get(0).describe());
            }
            if (BuildSupport.groundOnlyScaffolding(p) != 0) {
                bad.put(p.id(), "climbs on scaffolding that stands only on the ground under it");
            }
        }
        assertEquals(Map.of(), bad, "a Builder preset has parts that cannot stand");
    }

    @Test
    @DisplayName("every starter that has a ladder has a ladder you can climb end to end")
    void startersWithLaddersHaveThem() throws IOException {
        // The treehouse and the dome are the two that climb. If either stops naming a ladder the
        // sweep above would go quiet about the very thing it exists for.
        for (BuildPlan p : starters()) {
            if (p.id().equals("secret_treehouse") || p.id().equals("underwater_dome")) {
                assertTrue(p.shapes().stream().anyMatch(s -> BuildSupport.base(s.block()).equals("ladder")),
                        p.id() + " no longer has a ladder");
                assertTrue(p.shapes().stream().noneMatch(s -> BuildSupport.base(s.block()).equals("scaffolding")),
                        p.id() + " climbs on scaffolding again, which falls (#1374)");
            }
        }
    }

    @Test
    @DisplayName("every real build stands, except the ones on record — and those still fail as recorded")
    void everyRealBuildStandsOrIsOnRecord() {
        com.moddingfromamod.PrivateCorpora.require("real-builds.jsonl");   // private: skipped in the public cut
        List<RealBuilds.Real> real = RealBuilds.load();
        assertTrue(real.size() >= 15, "the real-build corpus did not load: " + real.size());
        Map<String, Integer> got = new TreeMap<>();
        for (RealBuilds.Real r : real) {
            int n = BuildSupport.check(r.plan()).size();
            if (n > 0) {
                got.put(r.id(), n);
            }
        }
        assertEquals(new TreeMap<>(KNOWN), got,
                "the real builds that cannot stand changed. A new id is a new defect; a known one "
                        + "vanishing means the check stopped seeing it");
    }

    @Test
    @DisplayName("the check would have caught both starters as they shipped before #1374")
    void theOldStartersAreCaught() {
        // The two defects, reproduced as they were in the templates.
        BuildPlan oldTreehouse = new BuildPlan("old", "old", List.of(
                new BuildShape(BuildShape.Kind.BOX, 5, 0, 5, 5, 12, 5, "minecraft:oak_log", true),
                new BuildShape(BuildShape.Kind.BOX, 0, 12, 0, 15, 1, 15, "minecraft:oak_planks", false),
                new BuildShape(BuildShape.Kind.BOX, 7, 13, 10, 1, 6, 1, "minecraft:scaffolding", false),
                // the attic column over the open room, its foot standing on the plank floor, is fine;
                // a column with its foot in the air is not:
                new BuildShape(BuildShape.Kind.BOX, 7, 5, -1, 1, 8, 1, "minecraft:scaffolding", false)));
        assertEquals(8, BuildSupport.check(oldTreehouse).size());
        // As it really shipped, both columns started on the plan's lowest layer: sound on flat
        // ground, so not a design fault — and the advisory is what names it.
        BuildPlan shipped = new BuildPlan("old", "old", List.of(
                new BuildShape(BuildShape.Kind.BOX, 5, 0, 5, 5, 12, 5, "minecraft:oak_log", true),
                new BuildShape(BuildShape.Kind.BOX, 0, 12, 0, 15, 1, 15, "minecraft:oak_planks", false),
                new BuildShape(BuildShape.Kind.BOX, 7, 0, -1, 1, 13, 1, "minecraft:scaffolding", false),
                new BuildShape(BuildShape.Kind.BOX, 7, 0, 7, 1, 13, 1, "minecraft:scaffolding", false)));
        assertEquals(List.of(), BuildSupport.check(shipped));
        assertEquals(26, BuildSupport.groundOnlyScaffolding(shipped), "the census's 26");
        BuildPlan oldDome = new BuildPlan("old", "old", List.of(
                new BuildShape(BuildShape.Kind.BOX, -9, -23, -9, 19, 1, 19, "minecraft:stone", false),
                new BuildShape(BuildShape.Kind.BOX, -1, -14, -1, 3, 15, 3, "minecraft:glass", true),
                new BuildShape(BuildShape.Kind.BOX, 0, -14, 0, 1, 16, 1, "minecraft:air", false),
                new BuildShape(BuildShape.Kind.BOX, 0, -21, 0, 1, 22, 1,
                        "minecraft:ladder[facing=north]", false)));
        assertEquals(7, BuildSupport.check(oldDome).size(), "7 of the dome's 22 ladder cells");
    }

    /**
     * The water cells a player standing on any ladder in this plan can walk into: a flood through
     * the plan's own air and ladder cells, from every ladder cell, stopping at anything else. A
     * cell outside the plan is the world, not the building, and is never entered.
     */
    static List<List<Integer>> waterReachableFromLadders(BuildPlan p) {
        Map<List<Integer>, String> cells = new java.util.HashMap<>();
        p.forEachBlock((x, y, z, block) -> cells.put(List.of(x, y, z), block));
        java.util.ArrayDeque<List<Integer>> todo = new java.util.ArrayDeque<>();
        java.util.Set<List<Integer>> seen = new java.util.HashSet<>();
        cells.forEach((at, block) -> {
            if (BuildSupport.base(block).equals("ladder") && seen.add(at)) {
                todo.add(at);
            }
        });
        java.util.Set<List<Integer>> wet = new java.util.TreeSet<>(
                java.util.Comparator.comparing((List<Integer> l) -> l.toString()));
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!todo.isEmpty()) {
            List<Integer> at = todo.poll();
            for (int[] d : steps) {
                List<Integer> next = List.of(at.get(0) + d[0], at.get(1) + d[1], at.get(2) + d[2]);
                String block = cells.get(next);
                if (block == null) {
                    continue;
                }
                String base = BuildSupport.base(block);
                if (base.equals("water")) {
                    wet.add(next);
                } else if ((base.equals("air") || base.equals("ladder")) && seen.add(next)) {
                    todo.add(next);
                }
            }
        }
        return new ArrayList<>(wet);
    }

    @Test
    @DisplayName("no starter's ladder room is open to the starter's own water")
    void noStarterFloodsItsOwnLadderRoom() throws IOException {
        // The Underwater Dome's air sphere and glass sphere were given the SAME dy. A shape's dy is
        // its base (anchor "centre" is horizontal only), so the 17-wide air sat one block lower in
        // the 19-wide glass than concentric, its bottom cap replaced the glass's, and the sea came
        // in: 388 water sources on the dome floor, round the ladder's foot, on both targets, in the
        // very photographs that shipped. Nothing that climbs could see it, because a
        // player climbs out of shallow water. Glass at dy-22 puts both centres at -13.
        Map<String, Integer> flooded = new TreeMap<>();
        for (BuildPlan p : starters()) {
            int n = waterReachableFromLadders(p).size();
            if (n > 0) {
                flooded.put(p.id(), n);
            }
        }
        assertEquals(Map.of(), flooded, "a starter's ladder room is open to its own water");
    }

    @Test
    @DisplayName("the flood check catches the dome as it shipped, with spheres sharing a dy")
    void theFloodedDomeIsCaught() {
        java.util.function.IntFunction<BuildPlan> dome = glassDy -> {
            BuildShape glass = new BuildShape(BuildShape.Kind.SPHERE, 0, glassDy, 0, 19, 19, 19,
                    "minecraft:glass", true, BuildShape.Anchor.CENTRE);
            BuildShape air = new BuildShape(BuildShape.Kind.SPHERE, 0, -21, 0, 17, 17, 17,
                    "minecraft:air", false, BuildShape.Anchor.CENTRE);
            return new BuildPlan("dome", "dome", List.of(
                    new BuildShape(BuildShape.Kind.BOX, -16, -22, -16, 33, 22, 33, "minecraft:water", false),
                    glass, air,
                    new BuildShape(BuildShape.Kind.BOX, -8, -22, -8, 17, 1, 17, "minecraft:smooth_quartz", false),
                    new BuildShape(BuildShape.Kind.BOX, 0, -21, 0, 1, 1, 1, "minecraft:ladder[facing=north]", false)));
        };
        assertTrue(waterReachableFromLadders(dome.apply(-21)).size() > 0, "spheres sharing a dy leak");
        assertEquals(List.of(), waterReachableFromLadders(dome.apply(-22)), "concentric spheres hold");
    }
}
