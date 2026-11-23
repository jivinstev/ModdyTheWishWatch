package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.builder.BuildDoors;
import com.moddingfromamod.builder.BuildGrounding;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.WalkThrough;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * <b>Does a building still WORK at every size?</b> (.) For every type the Builder offers, at every size step, the
 * three checks against size 2 (its own size): what floats (with and without the grounding fix), the things a player
 * USES (doors, beds, chests, trapdoors, a single gold treasure), and the rooms a player walks into. The answer is
 * {@code building-sizes.json}, which {@link BuilderSizes} offers sizes from; this test fails when the presets and that
 * file disagree, and writes the right file to {@code build/building-sizes.json}. The grid is
 * {@code build/build-scaling-report.txt}.
 */
class BuildScalingCorpusTest {

    /** The things a player uses, by count. Water, lava, ladders and trim are volumes: they grow with the size. */
    static final String[] ITEMS = {"door", "bed", "chest", "trapdoor", "gold"};

    /**
     * Cells the design check passes and a real walk does not ({@code ./tools/record-client.sh walkfilm}, partscale=on):
     * not offered, with the child's-words reason. Each is a disagreement between the walker and the game to resolve
     * (an earlier issue's walker-agreement test); remove a row when its film walks end to end.
     */
    static final Map<String, String> FILMED_FAILING = Map.of(
            "lighthouse:1", "its balcony would be too narrow");

    static Map<String, Integer> items(Map<Long, String> c) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String f : ITEMS) {
            out.put(f, 0);
        }
        for (String b : c.values()) {
            String n = b.replaceFirst("\\[.*", "").replaceFirst("^minecraft:", "");
            String hit = n.endsWith("_trapdoor") ? "trapdoor" : n.endsWith("_door") ? "door" : n.endsWith("_bed") ? "bed"
                    : n.endsWith("chest") || n.equals("barrel") ? "chest" : n.equals("gold_block") ? "gold" : null;
            if (hit != null) {
                out.merge(hit, 1, Integer::sum);
            }
        }
        return out;
    }

    record Measured(int blocks, int floats, int floatsFixed, int reached, int rooms, Map<String, Integer> items) {
    }

    static Measured measure(JsonArray shapes, int size, boolean byParts) {
        BuildPlan p = BuildGroundingCorpusTest.plan(shapes, size, byParts);
        Map<Long, String> c = BuildDoors.applied(p, BuildDoors.of(p));
        c.values().removeIf(Objects::isNull);
        BuildGrounding.Fixed fx = BuildGrounding.compute(p);
        // With the new scaler comes the water-entry rewrite (BuildWaterEntry): both are switched on together.
        String was = System.getProperty("moddingfromamod.waterentry");
        if (byParts) {
            System.setProperty("moddingfromamod.waterentry", "on");
        }
        WalkThrough.Report r;
        try {
            r = WalkThrough.of(p);
        } finally {
            if (was == null) {
                System.clearProperty("moddingfromamod.waterentry");
            } else {
                System.setProperty("moddingfromamod.waterentry", was);
            }
        }
        int reached = r.tooBig() ? -1 : (int) r.rooms().stream().filter(WalkThrough.Room::reached).count();
        return new Measured(c.size(), fx.before().floating().size(), fx.after().floating().size(), reached,
                r.tooBig() ? -1 : r.rooms().size(), items(c));
    }

    static String itemsWhy(Map<String, Integer> at, Map<String, Integer> ref) {
        return itemsWhy(at, ref, false);
    }

    /** {@code doorsMayCome}: a doorway that takes a door at this size and had none at size 2 is fine. */
    static String itemsWhy(Map<String, Integer> at, Map<String, Integer> ref, boolean doorsMayCome) {
        for (String f : ITEMS) {
            if (f.equals("gold") && ref.get(f) != 1) {
                continue;   // gold is counted only as a single treasure (the maze's, the ship's), never as trim
            }
            int a = at.get(f);
            int b = ref.get(f);
            if (a < b) {
                return f.equals("gold") ? "its treasure would go" : "it would lose its " + f + (b - a > 1 ? "s" : "");
            }
            if (a > b && !(doorsMayCome && f.equals("door"))) {
                return f.equals("gold") ? "it would get extra treasure" : "it would get extra " + f + "s";
            }
        }
        return "";
    }

    static JsonObject computed(StringBuilder rep) {
        return computed(rep, false);
    }

    static JsonObject computed(StringBuilder rep, boolean byParts) {
        JsonObject out = new JsonObject();
        rep.append("# #2097: every ready-made building at every size against size 2 (nothing outside)\n");
        rep.append("# FLOAT = lumps on nothing (after the grounding fix); WALK = rooms reached/rooms; ITEMS = doors, beds,\n");
        rep.append("# chests, trapdoors, a single gold treasure. Offered = the run of working sizes holding 2.\n");
        for (BuilderBuildings.Type t : BuilderBuildings.TYPES) {
            JsonArray shapes = LibraryPresets.SHIPPED.read(t.preset()).getAsJsonArray("shapes");
            Measured ref = measure(shapes, 2, byParts);
            JsonArray cells = new JsonArray();
            rep.append('\n').append(t.id()).append('\n');
            for (int size = 1; size <= 4; size++) {
                Measured m = size == 2 ? ref : measure(shapes, size, byParts);
                JsonObject c = new JsonObject();
                c.addProperty("size", size);
                // A real player's walk overrules the design check (docs/BUILD_CIRCULATION_PLAN.md §12).
                String filmed = byParts ? FILMED_FAILING.get(t.id() + ":" + size) : null;
                boolean walks = m.reached() >= ref.reached() && filmed == null;
                c.addProperty("walks", walks);
                String why = itemsWhy(m.items(), ref.items(), byParts);
                c.addProperty("keepsItems", why.isEmpty());
                c.addProperty("floats", m.floats());
                c.addProperty("floatsFixed", m.floatsFixed());
                if (!walks) {
                    c.addProperty("walkWhy", filmed != null ? filmed : m.reached() == 0 ? "you couldn't walk into it" : "you couldn't reach its rooms");
                }
                if (!why.isEmpty()) {
                    c.addProperty("itemsWhy", why);
                }
                c.addProperty("measured", String.format("%d blocks; floats %d (fixed %d); walk %d/%d; %s", m.blocks(),
                        m.floats(), m.floatsFixed(), m.reached(), m.rooms(), m.items()));
                cells.add(c);
                rep.append(String.format("  size %d: %-4s %s%n", size,
                        walks && why.isEmpty() && m.floatsFixed() == 0 ? "PASS" : "FAIL", c.get("measured").getAsString()
                                + (why.isEmpty() ? "" : " — " + why) + (walks ? "" : " — walk (size 2: " + ref.reached() + ")")));
            }
            out.add(t.id(), cells);
        }
        return out;
    }

    @Test
    void theSizesOfferedAreTheSizesThatWork() throws IOException {
        StringBuilder rep = new StringBuilder();
        JsonObject want = computed(rep);
        Files.createDirectories(Path.of("build"));
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(want) + "\n";
        Files.writeString(Path.of("build/building-sizes.json"), json);
        for (BuilderBuildings.Type t : BuilderBuildings.TYPES) {
            rep.append(String.format("%s offered %s (fix off) / %s (fix on)%n", t.id(),
                    BuilderSizes.offered(t.id(), false), BuilderSizes.offered(t.id(), true)));
        }
        Files.writeString(Path.of("build/build-scaling-report.txt"), rep.toString());
        String have;
        try (InputStream in = BuilderSizes.class.getResourceAsStream(BuilderSizes.RESOURCE)) {
            have = in == null ? "{}" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(want, JsonParser.parseString(have),
                "the presets changed how a building works at some size: cp build/building-sizes.json "
                        + "src/main/resources" + BuilderSizes.RESOURCE + " (and read build/build-scaling-report.txt)");
    }

    /** The same table for {@code partscale=on} (BuildScale): what the Builder offers when that scaler is switched on. */
    @Test
    void theSizesOfferedByPartsAreTheSizesThatWork() throws IOException {
        StringBuilder rep = new StringBuilder();
        JsonObject want = computed(rep, true);
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/building-sizes-parts.json"), new GsonBuilder().setPrettyPrinting().create().toJson(want) + "\n");
        Files.writeString(Path.of("build/build-scaling-report-parts.txt"), rep.toString());
        String have;
        try (InputStream in = BuilderSizes.class.getResourceAsStream(BuilderSizes.RESOURCE_PARTS)) {
            have = in == null ? "{}" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(want, JsonParser.parseString(have),
                "BuildScale changed how a building works at some size: cp build/building-sizes-parts.json "
                        + "src/main/resources" + BuilderSizes.RESOURCE_PARTS + " (and read build/build-scaling-report-parts.txt)");
    }

    @Test
    void sizeTwoIsAlwaysOfferedAndEveryLineFitsADialog() {
        for (BuilderBuildings.Type t : BuilderBuildings.TYPES) {
            assertTrue(BuilderSizes.table().containsKey(t.id()), t.id() + " has no row in building-sizes.json");
            for (boolean fix : new boolean[] {false, true}) {
                assertTrue(BuilderSizes.offered(t.id(), fix).contains(2), t.id());
                for (String l : BuilderSizes.lines(t.id(), fix)) {
                    assertTrue(l.length() <= 60, l);
                }
            }
        }
    }
}
