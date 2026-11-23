package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.builder.BuildGrounding;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.wish.RealBuilds;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import com.moddingfromamod.UsesCorpus;

/**
 * <b>The grounding corpus</b>: {@link BuildGrounding} measured on
 * <ul>
 *   <li>(a) {@code corpus/build-grounding/hand-fixed-pairs.json}: the six buildings fixed by hand, before and
 *       after, at every size step;</li>
 *   <li>(b) every building type the Builder offers, at every size step, and the 19 real agent builds
 *       ({@link RealBuilds});</li>
 *   <li>(c) the 337 archived real {@code build_structure} calls ({@code corpus/build-calls/}).</li>
 * </ul>
 * At the size each was made (size 2): every pre-fix plan the hand fix grounded is flagged, every fixed plan and every
 * control is clean. The other sizes are the scaling half; their counts are printed, and
 * {@code build/build-grounding-report.txt} holds the whole table.
 */
@UsesCorpus
class BuildGroundingCorpusTest {

    static final Path PAIRS = Path.of("corpus/build-grounding/hand-fixed-pairs.json");
    /** The archived build_structure calls, through CorpusSource: the real archive here, a fixture of presets and hand-made faults in the public repo. */
    static final Path CALLS = com.moddingfromamod.CorpusSource.path("build-calls/agent-build-calls.jsonl.gz");

    /** The pairs whose hand fix grounded something at size 2 (the barn's and the island's showed only once scaled). */
    static final List<String> FLOATING_AS_MADE = List.of("mushroom_village", "jungle_temple", "stone_well", "pirate_ship");

    static BuildPlan plan(JsonArray shapes, int size) {
        return plan(shapes, size, false);
    }

    static BuildPlan plan(JsonArray shapes, int size, boolean byParts) {
        JsonArray s = shapes.deepCopy();
        double f = BuilderLowering.BUILDING_SCALE[size - 1];
        if (f != 1.0) {
            BuilderLowering.scaleAll(s, f, byParts);
        }
        JsonObject o = new JsonObject();
        o.addProperty("id", "corpus");
        o.add("shapes", s);
        return BuildPlan.fromJson(o);
    }

    static List<String> describe(BuildGrounding.Report r) {
        List<String> out = new ArrayList<>();
        r.floating().forEach(f -> out.add(f.describe()));
        return out;
    }

    @Test
    void theCorpusMeasured() throws IOException {
        StringBuilder rep = new StringBuilder("# #2097 grounding corpus — BuildGrounding\n");
        List<String> bad = new ArrayList<>();

        rep.append("\n## (a) hand-fixed pairs: floating lumps before -> after, by size\n");
        JsonArray pairs = JsonParser.parseString(Files.readString(PAIRS)).getAsJsonObject().getAsJsonArray("pairs");
        for (JsonElement e : pairs) {
            JsonObject p = e.getAsJsonObject();
            String id = p.get("id").getAsString();
            for (int size = 1; size <= 4; size++) {
                BuildGrounding.Report before = BuildGrounding.of(plan(p.getAsJsonObject("before").getAsJsonArray("shapes"), size));
                BuildGrounding.Report after = BuildGrounding.of(plan(p.getAsJsonObject("after").getAsJsonArray("shapes"), size));
                rep.append(String.format("%-18s size %d: %2d -> %2d   before %s   after %s%n", id, size, before.floating().size(),
                        after.floating().size(), describe(before), describe(after)));
                if (size == 2 && FLOATING_AS_MADE.contains(id) && before.clean()) {
                    bad.add("(a) " + id + ": the pre-fix plan floats as made, and detection did not flag it");
                }
                if (size == 2 && !after.clean()) {
                    bad.add("(a) " + id + ": the hand-fixed plan is flagged as made: " + describe(after));
                }
            }
        }

        rep.append("\n## (b) every building type, by size (nothing outside)\n");
        int[] typeFlags = new int[5];
        for (BuilderBuildings.Type t : BuilderBuildings.TYPES) {
            JsonArray shapes = LibraryPresets.SHIPPED.read(t.preset()).getAsJsonArray("shapes");
            for (int size = 1; size <= 4; size++) {
                BuildGrounding.Report r = BuildGrounding.of(plan(shapes, size));
                typeFlags[size] += r.floating().size();
                if (!r.clean()) {
                    rep.append(String.format("%-18s size %d: %s%n", t.id(), size, describe(r)));
                }
                if (size == 2 && !r.clean()) {
                    bad.add("(b) " + t.id() + " is flagged as made: " + describe(r));
                }
            }
        }
        rep.append(String.format("floating lumps over all %d types: size1 %d, size2 %d, size3 %d, size4 %d%n",
                BuilderBuildings.TYPES.size(), typeFlags[1], typeFlags[2], typeFlags[3], typeFlags[4]));

        rep.append("\n## (b) the real agent builds (RealBuilds)\n");
        int realFlagged = 0;
        for (RealBuilds.Real r : com.moddingfromamod.CorpusSource.realBuilds()) {
            BuildGrounding.Report g = BuildGrounding.of(r.plan());
            if (!g.clean()) {
                realFlagged++;
                rep.append(String.format("%-28s %s%s%n", r.id(), g.airborne() ? "(airborne) " : "", describe(g)));
            }
        }
        rep.append(String.format("flagged: %d of %d%n", realFlagged, com.moddingfromamod.CorpusSource.realBuilds().size()));

        rep.append("\n## (c) archived real build_structure calls (each wish's calls replayed, an edit merged onto its"
                + " base; the final building of each id)\n");
        int calls = 0;
        int partial = 0;
        java.util.Map<String, BuildPlan> finals = new java.util.LinkedHashMap<>();
        java.util.Map<String, java.util.Map<String, BuildPlan>> byWish = new java.util.HashMap<>();
        try (InputStream in = new GZIPInputStream(Files.newInputStream(CALLS));
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank()) {
                    continue;
                }
                JsonObject row = JsonParser.parseString(line).getAsJsonObject();
                String wish = row.has("wish") ? row.get("wish").getAsString() : "?";
                JsonObject args = row.getAsJsonObject("args");
                BuildPlan sent;
                try {
                    sent = args == null ? null : BuildPlan.fromJson(args);
                } catch (RuntimeException e) {
                    continue;
                }
                if (sent == null || sent.shapes().isEmpty()) {
                    continue;
                }
                calls++;
                var built = byWish.computeIfAbsent(wish, k -> new java.util.HashMap<>());
                BuildPlan plan = sent;
                if (args.has("start_from") && args.get("start_from").isJsonPrimitive()) {
                    BuildPlan base = built.get(args.get("start_from").getAsString().trim().toLowerCase(java.util.Locale.ROOT)
                            .replace(' ', '_'));
                    if (base == null) {
                        partial++;   // an edit whose base is not in the archive: only its added parts, not a building
                        continue;
                    }
                    {
                        java.util.Set<String> remove = new java.util.LinkedHashSet<>();
                        if (args.has("remove") && args.get("remove").isJsonArray()) {
                            args.getAsJsonArray("remove").forEach(e -> remove.add(e.getAsString()));
                        }
                        plan = sent.withShapes(com.moddingfromamod.builder.BuildBase.merge(base.shapes(), sent.shapes(), remove));
                    }
                }
                built.put(plan.id(), plan);
                finals.put(wish + "|" + plan.id(), plan);
            }
        }
        int flagged = 0;
        int lumps = 0;
        int airborne = 0;
        for (var e : finals.entrySet()) {
            BuildGrounding.Report g = BuildGrounding.of(e.getValue());
            airborne += g.airborne() ? 1 : 0;
            if (!g.clean()) {
                flagged++;
                lumps += g.floating().size();
                rep.append(String.format("%-28s %s%s%n", e.getValue().id(), g.airborne() ? "(airborne) " : "", describe(g)));
            }
        }
        rep.append(String.format("flagged: %d of %d final buildings (%d calls, %d of them edits whose base is not archived;"
                + " %d airborne), %d floating lumps%n", flagged, finals.size(), calls, partial, airborne, lumps));

        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/build-grounding-report.txt"), rep.toString());
        System.out.println(rep);
        assertTrue(calls >= (com.moddingfromamod.CorpusSource.real() ? 300 : 30), "the archived calls did not load (" + calls + ")");
        assertFalse(pairs.isEmpty());
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    /** The block map as it stands (doors hung, no air). */
    static java.util.Map<Long, String> stands(BuildPlan p) {
        java.util.Map<Long, String> c = com.moddingfromamod.builder.BuildDoors.applied(p, com.moddingfromamod.builder.BuildDoors.of(p));
        c.values().removeIf(java.util.Objects::isNull);
        return c;
    }

    /** How many cells two block maps disagree on. */
    static int diff(java.util.Map<Long, String> a, java.util.Map<Long, String> b) {
        java.util.Set<Long> keys = new java.util.HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        int n = 0;
        for (long k : keys) {
            n += java.util.Objects.equals(a.get(k), b.get(k)) ? 0 : 1;
        }
        return n;
    }

    @Test
    void theFixMeasured() throws IOException {
        StringBuilder rep = new StringBuilder("# #2097 grounding corpus — BuildGrounding.fix\n");
        List<String> bad = new ArrayList<>();
        rep.append("\n## (a) hand-fixed pairs: still floating after the automatic fix, and how it differs from the hand fix\n");
        rep.append("(cells: blocks the fix changed; vs hand: blocks where the automatically fixed plan and the hand-fixed plan"
                + " disagree, and where the pre-fix and hand-fixed plans disagree, for scale)\n");
        JsonArray pairs = JsonParser.parseString(Files.readString(PAIRS)).getAsJsonObject().getAsJsonArray("pairs");
        for (JsonElement e : pairs) {
            JsonObject p = e.getAsJsonObject();
            String id = p.get("id").getAsString();
            for (int size = 1; size <= 4; size++) {
                BuildPlan beforePlan = plan(p.getAsJsonObject("before").getAsJsonArray("shapes"), size);
                java.util.Map<Long, String> before = stands(beforePlan);
                java.util.Map<Long, String> hand = stands(plan(p.getAsJsonObject("after").getAsJsonArray("shapes"), size));
                BuildGrounding.Fixed fx = BuildGrounding.compute(beforePlan);
                java.util.Map<Long, String> auto = BuildGrounding.applied(before, fx);
                // Nothing outside the floating lumps and their new support changes.
                java.util.Set<Long> lumps = new java.util.HashSet<>();
                fx.before().floating().forEach(f -> lumps.addAll(f.cells()));
                for (long k : fx.cells().keySet()) {
                    if (before.containsKey(k) && !lumps.contains(k)) {
                        bad.add("(a) " + id + " size " + size + ": the fix changed a block outside every floating lump");
                        break;
                    }
                }
                rep.append(String.format("%-18s size %d: %d -> %d floating; cells %d; vs hand %d (pre-fix vs hand %d)%n    %s%n",
                        id, size, fx.before().floating().size(), fx.after().floating().size(), fx.cells().size(),
                        diff(auto, hand), diff(before, hand), String.join("\n    ", fx.actions())));
                rep.append("    differs from the hand fix: ").append(differs(auto, hand)).append('\n');
                if (size == 2) {
                    dump(id + "-before", before, lumps);
                    dump(id + "-auto", auto, fx.cells().keySet());
                    dump(id + "-hand", hand, java.util.Set.of());
                }
                if (size == 2 && !fx.after().clean()) {   // the other sizes are the scaling half
                    bad.add("(a) " + id + " size " + size + ": still floating after the fix: " + describe(fx.after()));
                }
            }
        }
        rep.append("\n## (b) every building type: blocks the fix changes, by size\n");
        for (BuilderBuildings.Type t : BuilderBuildings.TYPES) {
            JsonArray shapes = LibraryPresets.SHIPPED.read(t.preset()).getAsJsonArray("shapes");
            StringBuilder row = new StringBuilder(String.format("%-18s", t.id()));
            for (int size = 1; size <= 4; size++) {
                BuildGrounding.Fixed fx = BuildGrounding.compute(plan(shapes, size));
                row.append(String.format("  size %d: %3d cells%s", size, fx.cells().size(),
                        fx.after().clean() ? "" : " (" + fx.after().floating().size() + " left)"));
                if (size == 2 && !fx.cells().isEmpty()) {
                    bad.add("(b) " + t.id() + " as made: the fix changed " + fx.cells().size() + " blocks");
                }
                if (!fx.actions().isEmpty() && size != 2) {
                    row.append(" ").append(fx.actions());
                }
            }
            rep.append(row).append('\n');
        }
        rep.append("\n## (b) the real agent builds, and (c) real build_structure calls: what the fix did\n");
        int fixedAll = 0;
        int left = 0;
        int changed = 0;
        int flaggedBuilds = 0;
        List<BuildPlan> realish = new ArrayList<>();
        com.moddingfromamod.CorpusSource.realBuilds().forEach(r -> realish.add(r.plan()));
        realish.addAll(finals());
        for (BuildPlan p : realish) {
            BuildGrounding.Fixed fx = BuildGrounding.compute(p);
            if (fx.before().clean()) {
                continue;
            }
            flaggedBuilds++;
            java.util.Map<Long, String> was = stands(p);
            java.util.Set<Long> floats = new java.util.HashSet<>();
            fx.before().floating().forEach(f -> floats.addAll(f.cells()));
            String name = String.format("real%02d-%s", flaggedBuilds, p.id());
            dump(name + "-before", was, floats);
            dump(name + "-auto", BuildGrounding.applied(was, fx), fx.cells().keySet());
            changed += fx.cells().size();
            fixedAll += fx.after().clean() ? 1 : 0;
            left += fx.after().floating().size();
            rep.append(String.format("%-30s %d -> %d floating, %d cells: %s%n", p.id(), fx.before().floating().size(),
                    fx.after().floating().size(), fx.cells().size(), fx.actions()));
        }
        rep.append(String.format("flagged %d buildings: %d wholly grounded by the fix, %d lumps left, %d blocks changed%n",
                flaggedBuilds, fixedAll, left, changed));
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/build-grounding-fix-report.txt"), rep.toString());
        System.out.println(rep);
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    /** (c) the final building of each id in each wish, edits merged onto their archived base. */
    static List<BuildPlan> finals() throws IOException {
        java.util.Map<String, BuildPlan> finals = new java.util.LinkedHashMap<>();
        java.util.Map<String, java.util.Map<String, BuildPlan>> byWish = new java.util.HashMap<>();
        try (InputStream in = new GZIPInputStream(Files.newInputStream(CALLS));
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank()) {
                    continue;
                }
                JsonObject row = JsonParser.parseString(line).getAsJsonObject();
                String wish = row.has("wish") ? row.get("wish").getAsString() : "?";
                JsonObject args = row.getAsJsonObject("args");
                BuildPlan sent;
                try {
                    sent = args == null ? null : BuildPlan.fromJson(args);
                } catch (RuntimeException ex) {
                    continue;
                }
                if (sent == null || sent.shapes().isEmpty()) {
                    continue;
                }
                var built = byWish.computeIfAbsent(wish, k -> new java.util.HashMap<>());
                BuildPlan plan = sent;
                if (args.has("start_from") && args.get("start_from").isJsonPrimitive()) {
                    BuildPlan base = built.get(args.get("start_from").getAsString().trim().toLowerCase(java.util.Locale.ROOT)
                            .replace(' ', '_'));
                    if (base == null) {
                        continue;
                    }
                    java.util.Set<String> remove = new java.util.LinkedHashSet<>();
                    if (args.has("remove") && args.get("remove").isJsonArray()) {
                        args.getAsJsonArray("remove").forEach(x -> remove.add(x.getAsString()));
                    }
                    plan = sent.withShapes(com.moddingfromamod.builder.BuildBase.merge(base.shapes(), sent.shapes(), remove));
                }
                built.put(plan.id(), plan);
                finals.put(wish + "|" + plan.id(), plan);
            }
        }
        return new ArrayList<>(finals.values());
    }

    /** Where two block maps disagree, grouped: "auto X where hand Y, at y a..b: n". */
    static String differs(java.util.Map<Long, String> auto, java.util.Map<Long, String> hand) {
        java.util.Map<String, int[]> g = new java.util.TreeMap<>();
        java.util.Set<Long> keys = new java.util.HashSet<>(auto.keySet());
        keys.addAll(hand.keySet());
        for (long k : keys) {
            String a = auto.get(k);
            String h = hand.get(k);
            if (java.util.Objects.equals(a, h)) {
                continue;
            }
            String key = "auto " + shortName(a) + " / hand " + shortName(h);
            int y = (int) ((k >> 20) & 0xFFFFF) - 512;
            int[] v = g.computeIfAbsent(key, x -> new int[] {0, Integer.MAX_VALUE, Integer.MIN_VALUE});
            v[0]++;
            v[1] = Math.min(v[1], y);
            v[2] = Math.max(v[2], y);
        }
        if (g.isEmpty()) {
            return "none";
        }
        List<String> out = new ArrayList<>();
        g.forEach((k, v) -> out.add(k + " x" + v[0] + " (y " + v[1] + ".." + v[2] + ")"));
        return String.join("; ", out);
    }

    static String shortName(String b) {
        return b == null ? "air" : b.replaceFirst("^minecraft:", "");
    }

    /** A block map for tools/render-blocks.py: [[x,y,z,block,marked],…] in build/build-grounding-cells/. */
    static void dump(String name, java.util.Map<Long, String> c, java.util.Set<Long> marked) throws IOException {
        Path dir = Path.of("build/build-grounding-cells");
        Files.createDirectories(dir);
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (var e : c.entrySet()) {
            long k = e.getKey();
            if (!first) {
                b.append(',');
            }
            first = false;
            b.append('[').append((int) ((k >> 40) & 0xFFFFF) - 512).append(',').append((int) ((k >> 20) & 0xFFFFF) - 512)
                    .append(',').append((int) (k & 0xFFFFF) - 512).append(",\"").append(e.getValue().replace("\"", ""))
                    .append("\",").append(marked.contains(k) ? 1 : 0).append(']');
        }
        Files.writeString(dir.resolve(name.replaceAll("[^A-Za-z0-9_.-]", "_") + ".json"), b.append(']').toString());
    }
}
