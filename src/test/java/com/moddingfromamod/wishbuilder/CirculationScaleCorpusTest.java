package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.builder.BuildDoors;
import com.moddingfromamod.builder.BuildGrounding;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildScale;
import com.moddingfromamod.builder.BuildShape;
import com.moddingfromamod.builder.WalkProbe;
import com.moddingfromamod.builder.WalkThrough;
import com.moddingfromamod.testing.ledger.GateLedger;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import com.moddingfromamod.UsesCorpus;

/**
 * <b>PROTOTYPE measurement</b> for docs/BUILD_CIRCULATION_PLAN.md: every Builder type at every size, three
 * ways of scaling — PLAIN (every number times the factor, what {@code main} does), WIP ({@link BuilderLowering#scale}
 * on this branch: fixtures, ladders and named openings keep their size) and PARTS ({@link BuildScale}: inferred
 * roles and a per-axis layout) — each cell judged against the same building at size 2. And the proof the design
 * stands on: at size 2 PARTS places exactly the blocks the preset draws.
 *
 * <p>Writes {@code build/circulation-report.md}.
 */
@UsesCorpus
class CirculationScaleCorpusTest {

    static final String[] COUNTED = {"chest", "bed", "gold", "door", "trapdoor", "gate"};
    static final String[] KEPT = {"lava", "water", "ladder"};

    record Cell(int blocks, int floats, int reached, int unreached, boolean tooBig, Map<String, Integer> fn,
                List<WalkThrough.Room> rooms, String floating, int loose, int leaks) {
    }

    static List<BuildShape> shapes(JsonArray a) {
        List<BuildShape> out = new ArrayList<>();
        for (JsonElement e : a) {
            BuildShape s = BuildShape.fromJson(e.getAsJsonObject());
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    static List<BuildShape> plain(JsonArray a, double f, boolean wip) {
        JsonArray c = a.deepCopy();
        if (wip && f != 1.0) {
            BuilderLowering.scaleAll(c, f, false);   // what main ships today
            return shapes(c);
        }
        for (JsonElement e : c) {
            JsonObject s = e.getAsJsonObject();
            if (f == 1.0) {
                continue;
            }
            {
                for (String k : new String[] {"dx", "dy", "dz"}) {
                    if (s.has(k)) {
                        s.addProperty(k, (int) Math.round(s.get(k).getAsDouble() * f));
                    }
                }
                for (String k : new String[] {"width", "height", "depth"}) {
                    if (s.has(k)) {
                        s.addProperty(k, Math.max(1, (int) Math.round(s.get(k).getAsDouble() * f)));
                    }
                }
            }
        }
        return shapes(c);
    }

    static Cell cell(String name, List<BuildShape> list) {
        Map<Long, String> cells = WalkProbe.compose(list);
        // BuildDoors reads a plan (64 shapes at most): the treads of a re-walked stair go last, and hang no door.
        List<BuildShape> forDoors = new ArrayList<>(list);
        forDoors.sort((a, b) -> Boolean.compare(a.name().matches("(step|under)\\d+"), b.name().matches("(step|under)\\d+")));
        WalkProbe.doors(cells, BuildDoors.of(new BuildPlan("corpus", name, forDoors)));
        WalkThrough.Report r = WalkProbe.walk(cells);
        Map<Long, String> solid = new HashMap<>(cells);
        solid.values().removeIf(Objects::isNull);
        Map<String, Integer> fn = new LinkedHashMap<>();
        for (String b : solid.values()) {
            String n = BuildScale.base(b);
            String hit = n.endsWith("_fence_gate") ? "gate" : n.endsWith("_trapdoor") ? "trapdoor" : n.endsWith("_door") ? "door"
                    : n.endsWith("_bed") ? "bed" : n.equals("chest") ? "chest" : n.equals("gold_block") ? "gold"
                    : n.equals("lava") ? "lava" : n.equals("water") ? "water" : n.equals("ladder") ? "ladder" : null;
            if (hit != null) {
                fn.merge(hit, 1, Integer::sum);
            }
        }
        int reached = (int) r.rooms().stream().filter(WalkThrough.Room::reached).count();
        List<BuildGrounding.Floating> fl = BuildGrounding.of(solid).floating();
        StringBuilder fs = new StringBuilder();
        fl.forEach(x -> fs.append(" [").append(x.describe()).append(']'));
        return new Cell(solid.size(), fl.size(), reached, r.rooms().size() - reached,
                r.tooBig(), fn, r.rooms(), fs.toString(), looseRungs(solid), leaks(cells));
    }

    /**
     * Rooms hold air: cells the plan CUT (air, kept as null) with water directly over or beside them. In the world that
     * water flows in. The underwater dome's invariant at every size.
     */
    static int leaks(Map<Long, String> cells) {
        int n = 0;
        for (Map.Entry<Long, String> e : cells.entrySet()) {
            if (e.getValue() != null) {
                continue;
            }
            long k = e.getKey();
            int x = WalkProbe.kx(k);
            int y = WalkProbe.ky(k);
            int z = WalkProbe.kz(k);
            for (int[] d : new int[][] {{0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}}) {
                String b = cells.get(WalkProbe.key(x + d[0], y + d[1], z + d[2]));
                if (b != null && b.contains("water")) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    /** Ladder blocks with no solid block behind them: in the world they fall off as items. */
    static int looseRungs(Map<Long, String> solid) {
        int n = 0;
        for (Map.Entry<Long, String> e : solid.entrySet()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("ladder\\[.*facing=(north|south|east|west)").matcher(e.getValue());
            if (!m.find()) {
                continue;
            }
            long k = e.getKey();
            int x = WalkProbe.kx(k);
            int y = WalkProbe.ky(k);
            int z = WalkProbe.kz(k);
            int dx = m.group(1).equals("west") ? 1 : m.group(1).equals("east") ? -1 : 0;
            int dz = m.group(1).equals("north") ? 1 : m.group(1).equals("south") ? -1 : 0;
            String behind = solid.get(WalkProbe.key(x + dx, y, z + dz));
            if (behind == null || behind.contains("ladder")) {
                n++;
            }
        }
        return n;
    }

    /** Why this cell is not as good as the same building at size 2; empty when it is. */
    static List<String> worse(Cell base, Cell c, java.util.function.IntUnaryOperator[] map) {
        List<String> why = new ArrayList<>();
        if (c.tooBig()) {
            why.add("too big to walk");
            return why;
        }
        if (c.leaks() > base.leaks()) {
            why.add(c.leaks() + " place(s) where water stands over or beside air the plan cut (size 2: " + base.leaks() + "): it floods");
        }
        if (c.loose() > base.loose()) {
            why.add(c.loose() + " ladder rung(s) with no block behind (size 2: " + base.loose() + ")");
        }
        if (c.floats() > base.floats()) {
            why.add("floats " + c.floats() + " (size 2: " + base.floats() + ")" + c.floating());
        }
        // "The same rooms as at size 2": each room he walks into at size 2 must be one he walks into here. Voids he could
        // never enter (a turret's inside, the space under eaves) multiply with size and are not counted against it.
        int lost = 0;
        java.util.Set<WalkThrough.Room> taken = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (WalkThrough.Room r : base.rooms()) {
            if (!r.reached()) {
                continue;
            }
            int x = map[0].applyAsInt((r.minX() + r.maxX() + 1) / 2);
            int y = map[1].applyAsInt(r.floorY());
            int z = map[2].applyAsInt((r.minZ() + r.maxZ() + 1) / 2);
            boolean found = false;
            for (WalkThrough.Room q : c.rooms()) {   // one new room answers for one old room
                if (!found && !taken.contains(q) && q.reached() && q.minX() - 1 <= x && x <= q.maxX() + 1
                        && q.minZ() - 1 <= z && z <= q.maxZ() + 1 && Math.abs(q.floorY() - y) <= 2) {
                    found = true;
                    taken.add(q);
                }
            }
            lost += found ? 0 : 1;
        }
        if (lost > 0) {
            why.add(lost + " of the " + base.reached() + " rooms he walks into at size 2 cannot be walked into (rooms "
                    + c.reached() + "/" + (c.reached() + c.unreached()) + ")");
        }
        for (String k : COUNTED) {
            int was = base.fn().getOrDefault(k, 0);
            int now = c.fn().getOrDefault(k, 0);
            // A door is two blocks and a doorway may be hung or not; one of each thing he uses is the rule.
            if (k.equals("door") && now > was) {
                continue;   // a doorway that now takes a door is not a loss
            }
            if (was != now && was <= 8) {   // over eight it is a material (gold trim), not a thing
                why.add(k + " " + now + " (size 2: " + was + ")");
            }
        }
        for (String k : KEPT) {
            if (base.fn().getOrDefault(k, 0) > 0 && c.fn().getOrDefault(k, 0) == 0) {
                why.add("no " + k);
            }
        }
        return why;
    }

    static final String[] ARMS = {"PLAIN", "WIP", "PARTS"};

    static BuildScale.Scaled arm(int arm, JsonArray shapes, double f) {
        java.util.function.IntUnaryOperator times = c -> (int) Math.round(c * f);
        return arm == 2 ? BuildScale.scaled(shapes(shapes), f)
                : new BuildScale.Scaled(plain(shapes, f, arm == 1), new java.util.function.IntUnaryOperator[] {times, times, times});
    }

    @Test
    void everyTypeAtEverySizeThreeWays() throws IOException {
        StringBuilder rep = new StringBuilder("# Circulation prototype: every Builder type, every size, three scalers\n\n");
        int[][] pass = new int[3][5];
        List<String> identity = new ArrayList<>();
        StringBuilder detail = new StringBuilder();
        StringBuilder roles = new StringBuilder("\n## Inferred parts per type\n\n| type | shell | fixture | ladder | doorway | stair runs (treads) |\n|---|---|---|---|---|---|\n");
        rep.append("| type | PLAIN 1 | PLAIN 3 | PLAIN 4 | WIP 1 | WIP 3 | WIP 4 | PARTS 1 | PARTS 3 | PARTS 4 |\n|---|---|---|---|---|---|---|---|---|---|\n");
        // phase 2: each type is a fingerprinted variant (its preset + the code this test reaches). One that already
        // passed is not scaled and walked again: its rows of the report are the ones it recorded (its FACTS), so the
        // report stays whole either way.
        GateLedger.Check check = BuilderBuildingComposableTest.ledger(CirculationScaleCorpusTest.class, "everyTypeAtEverySizeThreeWays");
        for (BuilderBuildings.Type t : BuilderBuildings.TYPES) {
            GateLedger.Variant v = check.variant(t.id(), BuilderBuildingComposableTest.presetInputs(t.preset()), "");
            List<String> facts = v.skip() ? v.facts() : typeRows(t);
            if (facts.get(4).equals("moved")) {
                identity.add(t.id());
            }
            rep.append(facts.get(0));
            detail.append(facts.get(1));
            roles.append(facts.get(2));
            for (String cellPass : facts.get(3).split(",")) {
                if (!cellPass.isEmpty()) {
                    pass[cellPass.charAt(0) - '0'][cellPass.charAt(1) - '0']++;
                }
            }
            if (!v.skipped()) {
                v.done(facts.get(4).equals("moved") ? List.of(t.id() + ": PARTS moves blocks at size 2") : List.of(), facts);
            }
        }
        identity.addAll(check.finish());
        rep.append("\n## Cells that pass, of ").append(BuilderBuildings.TYPES.size()).append(" types\n\n| scaler | size 1 | size 3 | size 4 |\n|---|---|---|---|\n");
        for (int arm = 0; arm < 3; arm++) {
            rep.append("| ").append(ARMS[arm]).append(" | ").append(pass[arm][1]).append(" | ").append(pass[arm][3])
                    .append(" | ").append(pass[arm][4]).append(" |\n");
        }
        rep.append("\nSize 2 through PARTS differs from the preset as drawn in: ").append(identity.isEmpty() ? "nothing (0 of "
                + BuilderBuildings.TYPES.size() + " types; block maps equal)" : identity.toString()).append('\n');
        rep.append(roles).append("\n## Why each cell failed\n").append(detail);
        GateLedger.Check census = GateLedger.shared().check(CirculationScaleCorpusTest.class, "agentBuilds");
        GateLedger.Variant agent = census.variant("the agent's own buildings", List.of(com.moddingfromamod.CorpusSource.path("real-builds.jsonl").toString(),
                BuildGroundingCorpusTest.CALLS.toString()), "");
        if (agent.skip()) {
            rep.append(agent.facts().get(0));
        } else {
            String text = agentBuilds();
            agent.done(List.of(), List.of(text));
            rep.append(text);
        }
        identity.addAll(census.finish());
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/circulation-report.md"), rep.toString());
        System.out.println(rep);
        assertTrue(identity.isEmpty(), "PARTS must place the preset's own blocks at size 2: " + identity);
    }

    /**
     * One type's part of the report, as the facts its ledger variant records: {its row of the table, its detail block, its
     * row of inferred parts, the cells that pass as "armsize" joined by commas, "same" or "moved" (PARTS at size 2)}.
     */
    static List<String> typeRows(BuilderBuildings.Type t) {
        JsonObject stored = LibraryPresets.SHIPPED.read(t.preset());
        JsonArray shapes = stored.getAsJsonArray("shapes");
        String name = stored.has("name") ? stored.get("name").getAsString() : t.id();
        Cell base = cell(name, shapes(shapes));
        // The design's first claim: at size 2 nothing moves.
        boolean moved = !WalkProbe.compose(BuildScale.scale(shapes(shapes), 1.0)).equals(WalkProbe.compose(shapes(shapes)));
        StringBuilder rep = new StringBuilder("| ").append(t.id());
        StringBuilder detail = new StringBuilder();
        List<String> passes = new ArrayList<>();
        detail.append("\n### ").append(t.id()).append(" — size 2: ").append(base.blocks()).append(" blocks, rooms ")
                .append(base.reached()).append('/').append(base.reached() + base.unreached()).append(", floats ")
                .append(base.floats()).append(", ").append(base.fn()).append('\n');
        for (int arm = 0; arm < 3; arm++) {
            for (int size : new int[] {1, 3, 4}) {
                BuildScale.Scaled sc = arm(arm, shapes, BuilderLowering.BUILDING_SCALE[size - 1]);
                Cell c = cell(name, sc.shapes());
                List<String> why = worse(base, c, sc.map());
                if (why.isEmpty()) {
                    passes.add("" + arm + size);
                }
                rep.append(" | ").append(why.isEmpty() ? "ok" : "**no**");
                detail.append("- ").append(ARMS[arm]).append(" size ").append(size).append(": ").append(c.blocks())
                        .append(" blocks; ").append(why.isEmpty() ? "ok" : String.join("; ", why)).append('\n');
            }
        }
        rep.append(" |\n");
        return List.of(rep.toString(), detail.toString(), roleRow(t.id(), shapes(shapes)), String.join(",", passes), moved ? "moved" : "same");
    }

    static String roleRow(String id, List<BuildShape> shapes) {
        Map<BuildScale.Role, Integer> n = new EnumMap<>(BuildScale.Role.class);
        int runs = 0;
        for (BuildScale.Part p : BuildScale.infer(shapes)) {
            n.merge(p.role(), 1, Integer::sum);
            runs = Math.max(runs, p.run() + 1);
        }
        return "| " + id + " | " + n.getOrDefault(BuildScale.Role.SHELL, 0) + " | " + n.getOrDefault(BuildScale.Role.FIXTURE, 0)
                + " | " + n.getOrDefault(BuildScale.Role.LADDER, 0) + " | " + n.getOrDefault(BuildScale.Role.DOORWAY, 0)
                + " | " + runs + " (" + n.getOrDefault(BuildScale.Role.TREAD, 0) + ") |\n";
    }

    /** The agent's own buildings: what is inferred, and whether each still works at the other sizes, PLAIN against PARTS. */
    static String agentBuilds() throws IOException {
        List<JsonArray> real = new ArrayList<>();
        for (String line : com.moddingfromamod.CorpusSource.lines("real-builds.jsonl")) {
            JsonObject spec = line.isBlank() ? null : JsonParser.parseString(line).getAsJsonObject().getAsJsonObject("spec");
            if (spec != null) {
                real.add(spec.getAsJsonArray("shapes"));
            }
        }
        List<JsonArray> calls = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(
                Files.newInputStream(BuildGroundingCorpusTest.CALLS)), StandardCharsets.UTF_8))) {
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                JsonObject args = JsonParser.parseString(line).getAsJsonObject().getAsJsonObject("args");
                if (args != null && args.has("shapes") && args.get("shapes").isJsonArray() && !args.has("start_from")) {
                    calls.add(args.getAsJsonArray("shapes"));
                }
            }
        }
        StringBuilder out = new StringBuilder("\n## The agent's own buildings\n\n");
        out.append(census("real builds (corpus/real-builds.jsonl)", real));
        out.append(census("archived build_structure calls, whole plans (no start_from)", calls));
        return out.toString();
    }

    static String census(String label, List<JsonArray> plans) {
        int n = 0;
        int identical = 0;
        int withDoorway = 0;
        int withLadder = 0;
        int withRun = 0;
        int unreachable = 0;
        int[][] pass = new int[3][5];
        int[] judged = new int[5];
        for (JsonArray a : plans) {
            List<BuildShape> list = shapes(a);
            if (list.isEmpty() || list.size() > BuildPlan.MAX_SHAPES) {
                continue;
            }
            n++;
            if (WalkProbe.compose(BuildScale.scale(list, 1.0)).equals(WalkProbe.compose(list))) {
                identical++;
            }
            boolean dw = false;
            boolean ld = false;
            boolean rn = false;
            for (BuildScale.Part p : BuildScale.infer(list)) {
                dw |= p.role() == BuildScale.Role.DOORWAY;
                ld |= p.role() == BuildScale.Role.LADDER;
                rn |= p.role() == BuildScale.Role.TREAD;
            }
            withDoorway += dw ? 1 : 0;
            withLadder += ld ? 1 : 0;
            withRun += rn ? 1 : 0;
            Cell base = cell("corpus", list);
            if (base.tooBig()) {
                continue;
            }
            unreachable += base.unreached() > 0 ? 1 : 0;
            for (int size : new int[] {1, 3, 4}) {
                double f = BuilderLowering.BUILDING_SCALE[size - 1];
                long volume = (long) (base.blocks() * f * f * f);
                if (volume > BuildPlan.MAX_BLOCKS) {
                    continue;
                }
                judged[size]++;
                for (int arm = 0; arm < 3; arm += 2) {
                    BuildScale.Scaled sc = arm(arm, a, f);
                    if (worse(base, cell("corpus", sc.shapes()), sc.map()).isEmpty()) {
                        pass[arm][size]++;
                    }
                }
            }
        }
        return "**" + label + "**: " + n + " plans; size 2 through PARTS is block-identical in " + identical + "; a doorway inferred in "
                + withDoorway + ", a ladder in " + withLadder + ", a stair run in " + withRun + "; " + unreachable
                + " have a room nobody can walk into as made.\n\n| scaler | size 1 | size 3 | size 4 |\n|---|---|---|---|\n"
                + "| judged (small enough) | " + judged[1] + " | " + judged[3] + " | " + judged[4] + " |\n"
                + "| PLAIN | " + pass[0][1] + " | " + pass[0][3] + " | " + pass[0][4] + " |\n"
                + "| PARTS | " + pass[2][1] + " | " + pass[2][3] + " | " + pass[2][4] + " |\n\n";
    }

    /** DEBUG: CIRCDUMP=type:size prints the scaled shapes, the rooms and the walk's layers. */
    @Test
    void dump() throws IOException {
        String want = System.getenv("CIRCDUMP");
        if (want == null) {
            return;
        }
        String[] w = want.split(":");
        JsonObject stored = LibraryPresets.SHIPPED.read("buildings/" + w[0]);
        List<BuildShape> list = BuildScale.scale(shapes(stored.getAsJsonArray("shapes")), BuilderLowering.BUILDING_SCALE[Integer.parseInt(w[1]) - 1]);
        StringBuilder out = new StringBuilder();
        for (BuildShape s : list) {
            out.append(String.format("%-20s %-8s %4d %4d %4d  %3dx%3dx%3d %s%s%n", s.name(), s.kind(), s.dx(), s.dy(), s.dz(), s.width(), s.height(), s.depth(), s.block(), s.hollow() ? " H" : ""));
        }
        Map<Long, String> cells = WalkProbe.compose(list);
        List<BuildShape> forDoors = new ArrayList<>(list);
        forDoors.sort((a, b) -> Boolean.compare(a.name().matches("(step|under)\\d+"), b.name().matches("(step|under)\\d+")));
        WalkProbe.doors(cells, BuildDoors.of(new BuildPlan("corpus", stored.get("name").getAsString(), forDoors)));
        out.append(WalkProbe.dump(cells, w.length > 2 ? Integer.parseInt(w[2]) : -1, w.length > 3 ? Integer.parseInt(w[3]) : 6));
        Files.writeString(Path.of("build/circulation-dump.txt"), out.toString());
    }

    /**
     * The water-entry rewrite over what agents and the Builder really made: it must leave alone every build whose
     * rooms can already be walked into, and the report lists each build it touches.
     */
    @Test
    void theWaterEntryRuleTouchesOnlyWhatItShould() throws IOException {
        List<String> touched = new ArrayList<>();
        java.util.Set<String> touchedIds = new java.util.TreeSet<>();
        int n = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(
                Files.newInputStream(BuildGroundingCorpusTest.CALLS)), StandardCharsets.UTF_8))) {
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                JsonObject args = JsonParser.parseString(line).getAsJsonObject().getAsJsonObject("args");
                BuildPlan p = args == null || !args.has("shapes") ? null : BuildPlan.fromJson(args);
                if (p == null) {
                    continue;
                }
                n++;
                com.moddingfromamod.builder.BuildWaterEntry.Fixed f = com.moddingfromamod.builder.BuildWaterEntry.compute(p);
                if (!f.isEmpty()) {
                    touched.add(p.id() + " (" + f.cells().size() + " cells, " + (f.lowered() > 0 ? "lowered" : "raised") + ")");
                    touchedIds.add(p.id());
                }
            }
        }
        List<String> presets = new ArrayList<>();
        for (BuilderBuildings.Type t : BuilderBuildings.TYPES) {
            BuildPlan p = BuildPlan.fromJson(LibraryPresets.SHIPPED.read(t.preset()));
            if (p != null && !com.moddingfromamod.builder.BuildWaterEntry.compute(p).isEmpty()) {
                presets.add(t.id());
            }
        }
        String rep = "# BuildWaterEntry over the corpus\n\narchived agent build_structure calls: " + n + "; touched: " + touched.size()
                + " " + touched + "\nBuilder types touched at size 2: " + presets + "\n";
        Files.writeString(Path.of("build/water-entry-report.md"), rep);
        System.out.println(rep);
        if (com.moddingfromamod.CorpusSource.real()) {
            org.junit.jupiter.api.Assertions.assertEquals(List.of(), touched,
                    "no archived agent build has a ladder shaft in water with its rim off the waterline; a ladder beside a "
                            + "pool is not one");
        } else {
            // The public fixture sends every shipped preset as a call, so it is touched exactly where they are.
            org.junit.jupiter.api.Assertions.assertEquals(new java.util.TreeSet<>(presets), touchedIds, touched.toString());
        }
        org.junit.jupiter.api.Assertions.assertEquals(List.of("underwater_dome"), presets,
                "of the Builder's own types, only the dome has a shaft standing in water with its rim off the waterline");
    }
}
