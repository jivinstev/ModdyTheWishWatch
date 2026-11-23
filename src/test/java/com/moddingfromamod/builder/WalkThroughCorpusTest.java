package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.testing.ledger.GateLedger;
import com.moddingfromamod.wish.RealBuilds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.moddingfromamod.UsesCorpus;

/**
 * <b>The walk-through checker and the door rewrite, measured on everything we have.</b>
 *
 * <p>The owner's rule: measure every change on the archived builds before shipping it. So this
 * walks the Builder presets, the starters, the real builds ({@link RealBuilds}) and every archived agent
 * {@code build_structure} call ({@code corpus/build-calls/}, harvested by {@code tools/harvest-build-calls.py},
 * with {@code start_from} edits merged onto their base exactly as the tool does), each twice — as drawn,
 * and as {@link BuildDoors} puts it up — and writes the table to {@code build/walkthrough/report.md}.
 *
 * <p>It pins what must not regress: every Builder preset's rooms can be walked into, or the room is named
 * in {@link #PRESET_EXCEPTIONS} with why; the door rewrite never makes a reachable room unreachable; and
 * the corpus is the size it was measured at, so a harvest that silently lost calls goes red.
 */
@UsesCorpus
class WalkThroughCorpusTest {

    static final Path PRESETS = Path.of("src/main/resources/builder-library/buildings");
    static final Path STARTERS = Path.of("src/main/resources/starter-library/buildings");
    /** The archived build_structure calls, through CorpusSource: the real archive here, a fixture of presets and hand-made faults in the public repo. */
    static final Path CALLS = com.moddingfromamod.CorpusSource.path("build-calls/agent-build-calls.jsonl.gz");

    /**
     * Builder preset rooms nobody walks into ON PURPOSE, by preset → floor y of the room. Decided
     * 2026-11-10: none — every preset room is meant to be entered.
     */
    static final Map<String, Set<Integer>> PRESET_EXCEPTIONS = Map.of();

    static List<BuildPlan> dir(Path root) throws IOException {
        List<BuildPlan> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path d : dirs.sorted().toList()) {
                Path f = d.resolve("build.json");
                if (Files.exists(f)) {
                    out.add(BuildPlan.fromJson(JsonParser.parseString(Files.readString(f)).getAsJsonObject()));
                }
            }
        }
        return out;
    }

    /** One archived call, resolved to the building it left (its {@code start_from} base merged in). */
    record Call(String wish, String id, BuildPlan plan) {
    }

    static List<Call> agentCalls() throws IOException {
        List<Call> out = new ArrayList<>();
        Map<String, Map<String, BuildPlan>> byWish = new HashMap<>();
        try (InputStream in = new GZIPInputStream(Files.newInputStream(CALLS))) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                JsonObject row = JsonParser.parseString(line).getAsJsonObject();
                String wish = row.get("wish").getAsString();
                JsonObject args = row.getAsJsonObject("args");
                BuildPlan sent;
                try {
                    sent = BuildPlan.fromJson(args);
                } catch (RuntimeException e) {
                    continue;
                }
                if (sent == null || sent.shapes().isEmpty()) {
                    continue;
                }
                Map<String, BuildPlan> built = byWish.computeIfAbsent(wish, k -> new HashMap<>());
                BuildPlan plan = sent;
                if (args.has("start_from") && args.get("start_from").isJsonPrimitive()) {
                    String baseId = args.get("start_from").getAsString().trim().toLowerCase(java.util.Locale.ROOT)
                            .replace(' ', '_');
                    BuildPlan base = built.get(baseId);
                    if (base != null) {
                        Set<String> remove = new LinkedHashSet<>();
                        if (args.has("remove") && args.get("remove").isJsonArray()) {
                            args.getAsJsonArray("remove").forEach(e -> remove.add(e.getAsString()));
                        }
                        plan = sent.withShapes(BuildBase.merge(base.shapes(), sent.shapes(), remove));
                    }
                }
                built.put(plan.id(), plan);
                out.add(new Call(wish, plan.id(), plan));
            }
        }
        return out;
    }

    /** The last building of each id in each wish: what the child was left with. */
    static List<BuildPlan> finals(List<Call> calls) {
        Map<String, BuildPlan> last = new LinkedHashMap<>();
        for (Call c : calls) {
            last.put(c.wish() + "|" + c.id(), c.plan());
        }
        return new ArrayList<>(last.values());
    }

    /** The table for one set of buildings, one arm. */
    static final class Tally {
        int builds;
        int tooBig;
        int withRooms;
        int withUnreachable;
        int mainWalkable;
        int rooms;
        int unreachableRooms;
        final Map<String, Integer> causes = new TreeMap<>();
        final Map<String, Integer> sizes = new TreeMap<>();
        int notSealedUnreachable;      // what the new note adds to the existing SEALED note

        void add(WalkThrough.Report r) {
            builds++;
            if (r.tooBig()) {
                tooBig++;
                return;
            }
            if (!r.rooms().isEmpty()) {
                withRooms++;
                if (r.mainWalkable()) {
                    mainWalkable++;
                }
            }
            rooms += r.rooms().size();
            List<WalkThrough.Room> un = r.unreachable();
            if (!un.isEmpty()) {
                withUnreachable++;
            }
            boolean added = false;
            for (WalkThrough.Room room : un) {
                unreachableRooms++;
                causes.merge(room.cause().name(), 1, Integer::sum);
                int c = room.columns();
                sizes.merge(c <= 9 ? "a. 4-9 cols" : c <= 24 ? "b. 10-24 cols" : c <= 80 ? "c. 25-80 cols" : "d. 81+ cols",
                        1, Integer::sum);
                added |= room.cause() != WalkThrough.Cause.SEALED;
            }
            if (added) {
                notSealedUnreachable++;
            }
        }

        String row(String name) {
            return String.format("| %s | %d | %d | %d (%.0f%%) | %d of %d | %d | %d | %s | %s |", name, builds, withRooms,
                    withUnreachable, withRooms == 0 ? 0.0 : 100.0 * withUnreachable / withRooms,
                    mainWalkable, withRooms, unreachableRooms, notSealedUnreachable, causes, sizes);
        }
    }

    /** Door statistics for one set. */
    static final class Doors {
        int buildsWithDoors;
        int doorCells;
        int malformedRuns;
        int faced;
        int iron;
        int auto;
        int buildsAuto;
        int dropped;

        void add(BuildPlan p, BuildDoors.Fixed f) {
            int[] n = {0};
            ComposedPlan.of(p).forEach((x, y, z, b) -> {
                if (BuildSupport.base(b).endsWith("_door")) {
                    n[0]++;
                }
            });
            if (n[0] > 0) {
                buildsWithDoors++;
                doorCells += n[0];
            }
            malformedRuns += f.halves();
            faced += f.faced();
            iron += f.ironSwapped();
            auto += f.autoDoors();
            dropped += f.dropped();
            if (f.autoDoors() > 0) {
                buildsAuto++;
            }
        }

        String row(String name) {
            return String.format("| %s | %d | %d | %d | %d | %d | %d in %d builds | %d |", name, buildsWithDoors,
                    doorCells, malformedRuns, faced, iron, auto, buildsAuto, dropped);
        }
    }

    static final List<String> TOWERISH = List.of("tower", "turret", "spire", "chimney", "pillar", "column", "post",
            "silo", "tank", "minaret", "steeple", "belfry", "obelisk", "statue", "pole", "mast", "trunk", "leg");
    static final List<String> UPPER = List.of("roof", "attic", "loft", "floor2", "upper", "second", "storey",
            "story", "top", "level", "tier", "deck", "balcony", "crow");

    /** "towerish", "upper storey / roof", "body / room / hall", or "unnamed" — from the smallest enclosing shape. */
    static String partKind(BuildPlan p, WalkThrough.Room room) {
        double x = (room.minX() + room.maxX()) / 2.0;
        double z = (room.minZ() + room.maxZ()) / 2.0;
        int y = room.floorY();
        BuildShape best = null;
        for (BuildShape sh : p.shapes()) {
            if (BuildSupport.isAir(sh.block())) {
                continue;
            }
            if (x >= sh.originX() && x < sh.originX() + sh.width() && z >= sh.originZ() && z < sh.originZ() + sh.depth()
                    && y >= sh.dy() && y < sh.dy() + sh.height() + 1
                    && (best == null || sh.volume() < best.volume())) {
                best = sh;
            }
        }
        String n = best == null ? "" : best.name();
        if (n.isEmpty()) {
            return "unnamed part";
        }
        for (String t : TOWERISH) {
            if (n.contains(t)) {
                return "towerish (" + t + ")";
            }
        }
        for (String t : UPPER) {
            if (n.contains(t)) {
                return "upper storey / roof";
            }
        }
        return "a body, room or hall";
    }

    @Test
    @DisplayName("every Builder preset room can be walked into, or is a named exception")
    void everyPresetIsWalkable() throws IOException {
        List<BuildPlan> presets = dir(PRESETS);
        assertTrue(presets.size() >= 10, "the Builder presets did not load: " + presets.size());
        Map<String, String> bad = new TreeMap<>();
        for (BuildPlan p : presets) {
            WalkThrough.Report r = WalkThrough.of(p);
            assertTrue(!r.tooBig(), p.id() + " is too big to walk");
            for (WalkThrough.Room room : r.unreachable()) {
                if (PRESET_EXCEPTIONS.getOrDefault(p.id(), Set.of()).contains(room.floorY())) {
                    continue;
                }
                bad.merge(p.id(), room.describe() + " — " + room.cause(), (a, b) -> a + "; " + b);
            }
        }
        assertEquals(Map.of(), bad, "a Builder preset has a room a child cannot walk into");
    }

    @Test
    @DisplayName("the door rewrite never makes a room that could be walked into unreachable")
    void doorsNeverMakeItWorse() throws IOException {
        // phase 2: a variant per preset and starter (its own build.json), one for the real builds and one for the
        // archived agent calls; a variant whose fingerprint (its file + the code this test reaches) already passed is skipped.
        GateLedger.Check check = GateLedger.shared().check(WalkThroughCorpusTest.class, "doorsNeverMakeItWorse");
        List<String> worse = new ArrayList<>();
        for (Path root : List.of(PRESETS, STARTERS)) {
            for (Path f : buildFiles(root)) {
                GateLedger.Variant v = check.variant(f.toString().replace('\\', '/'), f.toString().replace('\\', '/'));
                if (!v.skip()) {
                    List<String> bad = worse(List.of(BuildPlan.fromJson(JsonParser.parseString(Files.readString(f)).getAsJsonObject())));
                    v.done(bad);
                    worse.addAll(bad);
                }
            }
        }
        GateLedger.Variant real = check.variant("real builds", REAL_BUILDS);
        if (!real.skip()) {
            List<BuildPlan> plans = new ArrayList<>();
            com.moddingfromamod.CorpusSource.realBuilds().forEach(r -> plans.add(r.plan()));
            List<String> bad = worse(plans);
            real.done(bad);
            worse.addAll(bad);
        }
        GateLedger.Variant agent = check.variant("agent calls", CALLS.toString());
        if (!agent.skip()) {
            List<String> bad = worse(finals(agentCalls()));
            agent.done(bad);
            worse.addAll(bad);
        }
        worse.addAll(check.finish());
        assertEquals(List.of(), worse);
    }

    /** The real builds' file, as the ledger hashes it: the one CorpusSource reads. */
    static final String REAL_BUILDS = com.moddingfromamod.CorpusSource.path("real-builds.jsonl").toString();

    static List<Path> buildFiles(Path root) throws IOException {
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.sorted().map(d -> d.resolve("build.json")).filter(Files::exists).toList();
        }
    }

    static List<String> worse(List<BuildPlan> plans) {
        List<String> worse = new ArrayList<>();
        for (BuildPlan p : plans) {
            WalkThrough.Report before = WalkThrough.ofAsDrawn(p);
            WalkThrough.Report after = WalkThrough.of(p);
            long b = before.rooms().stream().filter(WalkThrough.Room::reached).mapToInt(WalkThrough.Room::columns).sum();
            long a = after.rooms().stream().filter(WalkThrough.Room::reached).mapToInt(WalkThrough.Room::columns).sum();
            if (a < b) {
                worse.add(p.id() + ": reachable floor " + b + " -> " + a);
            }
        }
        return worse;
    }

    @Test
    @DisplayName("measure: the walk and the doors over presets, starters, real builds and every archived agent build")
    void measure() throws IOException {
        // phase 2: ONE variant, everything it reads; when that fingerprint already passed the report is written
        // from what the passing run recorded (its timings are the one thing not replayed: -Pgate.ledger=off re-measures).
        GateLedger.Check check = GateLedger.shared().check(WalkThroughCorpusTest.class, "measure");
        GateLedger.Variant v = check.variant("the report", PRESETS.toString(), STARTERS.toString(), REAL_BUILDS, CALLS.toString());
        Files.createDirectories(Path.of("build/walkthrough"));
        if (v.skip()) {
            Files.writeString(Path.of("build/walkthrough/first-call-fires.tsv"), v.facts().get(1));
            Files.writeString(Path.of("build/walkthrough/report.md"), v.facts().get(0)
                    + "\nTime: not measured — this report is the ledger's (inputs and code unchanged since it passed); "
                    + "`-Pgate.ledger=off` re-measures.\n");
            assertEquals(List.of(), check.finish());
            return;
        }
        String[] report = measured();
        v.done(List.of(), List.of(report[0], report[1]));
        assertEquals(List.of(), check.finish());
    }

    /** {deterministic report, first-call fires}: the walk and the doors over everything, and the report written. */
    static String[] measured() throws IOException {
        long t0 = System.nanoTime();
        Map<String, List<BuildPlan>> sets = new LinkedHashMap<>();
        sets.put("Builder presets", dir(PRESETS));
        sets.put("starters", dir(STARTERS));
        List<BuildPlan> real = new ArrayList<>();
        com.moddingfromamod.CorpusSource.realBuilds().forEach(r -> real.add(r.plan()));
        sets.put("real builds", real);
        List<Call> calls = agentCalls();
        List<BuildPlan> each = new ArrayList<>();
        calls.forEach(c -> each.add(c.plan()));
        sets.put("agent calls (every build_structure)", each);
        sets.put("agent buildings (last version per wish)", finals(calls));
        assertTrue(calls.size() >= (com.moddingfromamod.CorpusSource.real() ? 300 : 30), "the archived build calls did not load: " + calls.size());

        StringBuilder md = new StringBuilder("# Walk-through and doors, measured (#1947)\n\n");
        md.append("Generated by `WalkThroughCorpusTest.measure` — `./gradlew test --tests '*WalkThroughCorpusTest'`.\n\n");
        md.append("## Rooms a child can walk into\n\n");
        md.append("| set · arm | builds | with a room | with an unreachable room | main room walkable | unreachable rooms | "
                + "builds the new note speaks for (not SEALED) | causes | sizes |\n|---|---|---|---|---|---|---|---|---|\n");
        StringBuilder doors = new StringBuilder("\n## Doors (the rewrite `BuildDoors` applies as it builds)\n\n"
                + "| set | builds with a door block | door cells | runs given proper halves | facings added | iron → wood "
                + "| doorways given a door | odd top cells dropped |\n|---|---|---|---|---|---|---|---|\n");
        StringBuilder sample = new StringBuilder("\n## A sample of auto-doors, to look at\n\n");
        int shown = 0;
        for (Map.Entry<String, List<BuildPlan>> e : sets.entrySet()) {
            Tally drawn = new Tally();
            Tally fixed = new Tally();
            Doors d = new Doors();
            for (BuildPlan p : e.getValue()) {
                drawn.add(WalkThrough.ofAsDrawn(p));
                fixed.add(WalkThrough.of(p));
                BuildDoors.Fixed f = BuildDoors.of(p);
                d.add(p, f);
                if (f.autoDoors() > 0 && shown < 12 && e.getKey().startsWith("agent buildings")) {
                    shown++;
                    sample.append("- `").append(p.id()).append("`: ");
                    f.cells().entrySet().stream().filter(c -> c.getValue().contains("half=lower")).limit(4)
                            .forEach(c -> sample.append(ComposedPlan.kx(c.getKey())).append(',')
                                    .append(ComposedPlan.ky(c.getKey())).append(',').append(ComposedPlan.kz(c.getKey()))
                                    .append(' ').append(c.getValue()).append("; "));
                    sample.append('\n');
                }
            }
            md.append(drawn.row(e.getKey() + " · as drawn")).append('\n');
            md.append(fixed.row(e.getKey() + " · as built")).append('\n');
            doors.append(d.row(e.getKey())).append('\n');
        }
        StringBuilder presets = new StringBuilder("\n## Every Builder preset\n\n");
        for (BuildPlan p : sets.get("Builder presets")) {
            WalkThrough.Report r = WalkThrough.of(p);
            presets.append("- `").append(p.id()).append("`: ").append(r.rooms().size()).append(" rooms, ")
                    .append(r.unreachable().size()).append(" unreachable");
            for (WalkThrough.Room room : r.unreachable()) {
                presets.append("; ").append(room.describe()).append(' ').append(room.cause());
            }
            presets.append('\n');
        }
        // What the flagged rooms are, by the name of the smallest shape around them: how many look like
        // decoration (a turret, a chimney, a solid tower) before deciding what to say.
        long worst = 0;
        String worstId = "";
        List<Long> times = new ArrayList<>();
        for (BuildPlan p : sets.get("agent calls (every build_structure)")) {
            long a = System.nanoTime();
            WalkThrough.of(p);
            BuildDoors.of(p);
            long t = (System.nanoTime() - a) / 1_000;
            times.add(t);
            if (t > worst) {
                worst = t;
                worstId = p.id();
            }
        }
        times.sort(Long::compare);
        // the timings go LAST, after the deterministic report the ledger records (phase 2)
        StringBuilder cost = new StringBuilder("\n## Cost per call (walk + doors, after warm-up)\n\nmedian ").append(times.get(times.size() / 2) / 1000.0)
                .append(" ms, p95 ").append(times.get(times.size() * 95 / 100) / 1000.0).append(" ms, worst ")
                .append(worst / 1000.0).append(" ms (").append(worstId).append(")\n");
        Map<String, Integer> kinds = new TreeMap<>();
        Map<String, Integer> notSealedKinds = new TreeMap<>();
        for (BuildPlan p : sets.get("agent buildings (last version per wish)")) {
            for (WalkThrough.Room room : WalkThrough.of(p).unreachable()) {
                String k = partKind(p, room);
                kinds.merge(k, 1, Integer::sum);
                if (room.cause() != WalkThrough.Cause.SEALED) {
                    notSealedKinds.merge(k, 1, Integer::sum);
                }
            }
        }
        presets.append("\n## What the unreachable rooms in the agent's buildings are (by the part around them)\n\n")
                .append("all causes: ").append(kinds).append("\n\nnot SEALED (what the new note adds): ")
                .append(notSealedKinds).append('\n');
        // Which wishes' FIRST build the new note speaks about: the candidates for the agent A/B.
        StringBuilder fires = new StringBuilder();
        java.util.Set<String> firstSeen = new java.util.HashSet<>();
        for (Call c : calls) {
            if (!firstSeen.add(c.wish())) {
                continue;
            }
            WalkThrough.Report r = WalkThrough.of(c.plan());
            boolean sealedTold = BuildGeometry.of(c.plan()).sealedInterior().volume() >= BuildGeometry.SEALED_MIN_ROOM;
            String note = WalkThrough.note(r, sealedTold);
            if (!note.isEmpty()) {
                fires.append(c.wish()).append('\t').append(c.id()).append('\t')
                        .append(r.unreachable().stream().map(x -> x.cause().name()).toList()).append('\n');
            }
        }
        // The directory first: on a fresh checkout it does not exist yet, and this write came before the
        // createDirectories below (red on a clean build, found while merging).
        Files.createDirectories(Path.of("build/walkthrough"));
        Files.writeString(Path.of("build/walkthrough/first-call-fires.tsv"), fires.toString());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        md.append(doors).append(presets).append(sample);
        String deterministic = md.toString();
        md.append(cost).append("\nTime: ").append(ms).append(" ms for all of it.\n");
        Path out = Path.of("build/walkthrough/report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString());
        System.out.println(md);
        return new String[] {deterministic, fires.toString()};
    }
}
