package com.moddingfromamod.builder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.CorpusSource;
import com.moddingfromamod.wish.RealBuilds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>an earlier issue's census, kept: light shapes as DRAWN against light that SURVIVES composition.</b>
 *
 * <p>The issue's method, made a test so it cannot regress: compose each plan the way the builder
 * places it ({@link ComposedPlan}, i.e. {@link BuildPlan#forEachBlock} with later shapes winning
 * and air removing), and count, per plan, the cells a light-emitting shape declares and how many
 * of them a later shape overwrote. Then assert that the lighting backstop sees exactly the
 * survivors: no overwritten cell is ever a light to {@link BuildLighting#litNearby}.
 *
 * <p>The table it prints is the evidence; the pinned rows are the builds the issue named that are
 * committed here (the starter castle and the castle granted; a named real castle is pinned beside the
 * private corpus, in {@code BuildLightingRealBuildsTest}).
 */
class ComposedLightCensusTest {

    /** One plan's row. */
    record Row(String name, BuildPlan plan, int declared, int overwritten) {
    }

    /** Positions a light-emitting shape declares, whatever happens to them later. */
    static Set<Long> declaredLightCells(BuildPlan plan) {
        Set<Long> out = new HashSet<>();
        for (BuildShape s : plan.shapes()) {
            if (DarkInterior.emission(s.block()) > 0) {
                s.forEachBlock((x, y, z) -> out.add(ComposedPlan.key(x, y, z)));
            }
        }
        return out;
    }

    static Row census(String name, BuildPlan plan) {
        ComposedPlan built = ComposedPlan.of(plan);
        Set<Long> survives = new HashSet<>();
        for (int[] l : built.lights()) {
            survives.add(ComposedPlan.key(l[0], l[1], l[2]));
        }
        Set<Long> declared = declaredLightCells(plan);
        int overwritten = 0;
        for (long k : declared) {
            if (!survives.contains(k)) {
                overwritten++;
            }
        }
        return new Row(name, plan, declared.size(), overwritten);
    }

    /**
     * Every plan committed here: the real builds (through {@link CorpusSource}: the 19 real ones here, the shipped
     * presets in the public repo) and the 5 starters (an earlier issue's granted castle is checked in GrantedCastleTest).
     */
    static List<Row> allRows() throws Exception {
        List<Row> rows = new ArrayList<>();
        for (RealBuilds.Real r : CorpusSource.realBuilds()) {
            rows.add(census("real: " + r.id(), r.plan()));
        }
        for (String starter : List.of("floating_island", "lava_moat_castle", "mushroom_village",
                "secret_treehouse", "underwater_dome")) {
            rows.add(census("starter: " + starter, starter(starter)));
        }
        rows.add(census("hand-made: lava under the floor", BURIED_LAVA));
        return rows;
    }

    /**
     * A light drawn and then written over, made by hand so every dataset holds one: a lava sheet that the
     * stone floor laid after it replaces cell for cell, under a roofed room. As drawn it lights the room; as built,
     * nothing does.
     */
    static final BuildPlan BURIED_LAVA = new BuildPlan("buried_lava", "Buried lava", List.of(
            new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, 21, 1, 21, "minecraft:lava", false),
            new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, 21, 1, 21, "minecraft:stone", false),
            new BuildShape(BuildShape.Kind.BOX, 0, 1, 0, 21, 6, 21, "minecraft:stone_bricks", true)));

    static BuildPlan starter(String id) throws Exception {
        String path = "/starter-library/buildings/" + id + "/build.json";
        try (InputStream in = ComposedLightCensusTest.class.getResourceAsStream(path)) {
            assertTrue(in != null, "missing " + path);
            JsonObject o = JsonParser.parseString(new String(in.readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            return BuildPlan.fromJson(o);
        }
    }

    @Test
    @DisplayName("#1386 census: the overwritten light cells, per build, and the pinned rows")
    void theCensus() throws Exception {
        List<Row> rows = allRows();
        assertTrue(rows.size() >= 24, "the corpus did not load: " + rows.size() + " plans");
        Map<String, Row> by = new LinkedHashMap<>();
        int withLights = 0;
        int withOverwritten = 0;
        StringBuilder table = new StringBuilder("\n| build | light cells declared | overwritten |\n"
                + "|---|---:|---:|\n");
        for (Row r : rows) {
            by.put(r.name(), r);
            if (r.declared() == 0) {
                continue;
            }
            withLights++;
            if (r.overwritten() > 0) {
                withOverwritten++;
            }
            table.append("| ").append(r.name()).append(" | ").append(r.declared()).append(" | ")
                 .append(r.overwritten()).append(" |\n");
        }
        table.append("\n").append(withOverwritten).append(" of ").append(withLights)
             .append(" plans with a light shape have light cells overwritten.\n");
        System.out.println(table);

        // The rows named that are committed here. Exact: a change to composition or to what
        // counts as a light moves them, and then this says so rather than drifting quietly.
        assertEquals(5445, by.get("starter: lava_moat_castle").declared(), table.toString());   // +29 lights set into its floor (2026-11-12), +4 in its tower rooms
        assertEquals(3548, by.get("starter: lava_moat_castle").overwritten(), table.toString());
    }

    @Test
    @DisplayName("#1386: litNearby agrees with an independent composition, in every build")
    void theBackstopSeesOnlyWhatSurvives() throws Exception {
        int[] n = backstop(allRows());
        int checked = n[0];
        int disagreedWithTheShapes = n[1];
        System.out.println("#1386: " + checked + " cells checked; " + disagreedWithTheShapes
                + " of them read as lit by the shapes as drawn and are dark by what survives");
        assertTrue(disagreedWithTheShapes > 0,
                "the corpus no longer exercises an overwritten light: this test would be vacuous");
    }

    /** {cells checked, cells lit by the shapes as drawn and dark by what survives}, failing on any disagreement. */
    static int[] backstop(List<Row> rows) {
        int checked = 0;
        int disagreedWithTheShapes = 0;
        for (Row r : rows) {
            if (r.declared() == 0) {
                continue;
            }
            // The oracle: BuildPlan.forEachBlock straight into a map, last write wins, air empties.
            // Written here, not borrowed from ComposedPlan, so a bug there cannot agree with itself.
            Map<String, String> last = new java.util.HashMap<>();
            r.plan().forEachBlock((x, y, z, b) -> {
                if ("minecraft:air".equals(b)) {
                    last.remove(x + "," + y + "," + z);
                } else {
                    last.put(x + "," + y + "," + z, b);
                }
            });
            List<int[]> survivors = new ArrayList<>();
            last.forEach((k, b) -> {
                if (DarkInterior.emission(b) > 0) {
                    String[] p = k.split(",");
                    survivors.add(new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                            Integer.parseInt(p[2])});
                }
            });
            Set<Long> declared = declaredLightCells(r.plan());
            ComposedPlan built = ComposedPlan.of(r.plan());
            BuildFraming.Bounds b = BuildFraming.bounds(built);
            int step = Math.max(1, BuildLighting.SPACING / 2);
            for (int x = b.minX(); x <= b.maxX(); x += step) {
                for (int y = b.minY(); y <= b.maxY(); y += step) {
                    for (int z = b.minZ(); z <= b.maxZ(); z += step) {
                        boolean want = near(survivors, x, y, z);
                        assertEquals(want, BuildLighting.litNearby(built, x, y, z),
                                r.name() + " at " + x + "," + y + "," + z);
                        checked++;
                        if (!want && nearDeclared(declared, x, y, z)) {
                            disagreedWithTheShapes++;
                        }
                    }
                }
            }
        }
        return new int[]{checked, disagreedWithTheShapes};
    }

    private static boolean near(List<int[]> lights, int x, int y, int z) {
        int r = BuildLighting.SPACING;
        for (int[] l : lights) {
            if (Math.abs(l[0] - x) <= r && Math.abs(l[1] - y) <= r && Math.abs(l[2] - z) <= r) {
                return true;
            }
        }
        return false;
    }

    private static boolean nearDeclared(Set<Long> declared, int x, int y, int z) {
        int r = BuildLighting.SPACING;
        for (long k : declared) {
            if (Math.abs(ComposedPlan.kx(k) - x) <= r && Math.abs(ComposedPlan.ky(k) - y) <= r
                    && Math.abs(ComposedPlan.kz(k) - z) <= r) {
                return true;
            }
        }
        return false;
    }
}
