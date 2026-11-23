package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import com.moddingfromamod.UsesCorpus;

/**
 * {@link BuildRepair} over every building the agent really made (the archived {@code build_structure} calls
 * and {@code corpus/real-builds.jsonl}), repairs off against on. Writes {@code build/build-repair-report.md}.
 * The counter-metrics are asserted: a building whose rooms all walk is never touched, no reachable floor is lost,
 * and a fix stays small.
 */
@UsesCorpus
class BuildRepairCorpusTest {

    /** The archived build_structure calls, through CorpusSource: the real archive here, a fixture of presets and hand-made faults in the public repo. */
    static final Path CALLS = com.moddingfromamod.CorpusSource.path("build-calls/agent-build-calls.jsonl.gz");

    record Plan(String name, BuildPlan plan) {
    }

    static List<Plan> plans() throws IOException {
        List<Plan> out = new ArrayList<>();
        int n = 0;
        for (String line : com.moddingfromamod.CorpusSource.lines("real-builds.jsonl")) {
            JsonObject spec = line.isBlank() ? null : JsonParser.parseString(line).getAsJsonObject().getAsJsonObject("spec");
            if (spec != null) {
                add(out, "real_" + n++, spec.getAsJsonArray("shapes"));
            }
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(
                Files.newInputStream(CALLS)), StandardCharsets.UTF_8))) {
            int i = 0;
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                JsonObject args = JsonParser.parseString(line).getAsJsonObject().getAsJsonObject("args");
                if (args != null && args.has("shapes") && args.get("shapes").isJsonArray() && !args.has("start_from")) {
                    add(out, "call_" + i, args.getAsJsonArray("shapes"));
                }
                i++;
            }
        }
        return out;
    }

    private static void add(List<Plan> out, String name, JsonArray shapes) {
        JsonObject o = new JsonObject();
        o.addProperty("id", name);
        o.addProperty("name", name);
        o.add("shapes", shapes);
        BuildPlan p = BuildPlan.fromJson(o);
        if (p != null && !p.shapes().isEmpty()) {
            out.add(new Plan(name, p));
        }
    }

    @Test
    void repairsOverEveryRealBuilding() throws IOException {
        int n = 0;
        int judged = 0;
        int allWalkBefore = 0;
        int allWalkAfter = 0;
        int touched = 0;
        int touchedClean = 0;
        int lost = 0;
        int most = 0;
        int dumped = 0;
        StringBuilder dumps = new StringBuilder();
        long roomsBefore = 0;
        long roomsAfter = 0;
        Map<String, Integer> causes = new TreeMap<>();
        Map<String, Integer> kinds = new TreeMap<>();
        Map<String, Integer> detail = new TreeMap<>();
        StringBuilder rows = new StringBuilder();
        for (Plan pl : plans()) {
            n++;
            System.setProperty("moddingfromamod.buildrepair", "off");
            WalkThrough w = WalkThrough.plan(pl.plan());
            System.clearProperty("moddingfromamod.buildrepair");
            if (w == null || w.report().tooBig()) {
                continue;
            }
            judged++;
            WalkThrough.Report before = w.report();
            Map<Long, String> c0 = BuildDoors.applied(pl.plan(), BuildDoors.of(pl.plan()));
            for (WalkThrough.Room r : before.unreachable()) {
                causes.merge(r.cause() + (r.cut() == null ? "" : " (one cut would open it)"), 1, Integer::sum);
                boolean ladder = false;
                boolean stairs = false;
                boolean lowDoor = false;
                for (Map.Entry<Long, String> e : c0.entrySet()) {
                    int x = ComposedPlan.kx(e.getKey());
                    int y = ComposedPlan.ky(e.getKey());
                    int z = ComposedPlan.kz(e.getKey());
                    if (x < r.minX() - 2 || x > r.maxX() + 2 || z < r.minZ() - 2 || z > r.maxZ() + 2) {
                        continue;
                    }
                    String b = e.getValue() == null ? "" : BuildSupport.base(e.getValue());
                    if (y >= r.floorY() - 6 && y <= r.floorY() + 1) {
                        ladder |= b.equals("ladder");
                        stairs |= b.endsWith("_stairs");
                    }
                    if (e.getValue() == null && y == r.floorY()
                            && WalkThrough.kindOf(c0.get(ComposedPlan.key(x, y + 1, z))) == WalkThrough.FULL
                            && WalkThrough.kindOf(c0.get(ComposedPlan.key(x, y - 1, z))) == WalkThrough.FULL) {
                        lowDoor = true;
                    }
                }
                boolean hatch = false;
                for (int x = r.minX(); x <= r.maxX() && !hatch; x++) {
                    for (int z = r.minZ(); z <= r.maxZ() && !hatch; z++) {
                        long k = ComposedPlan.key(x, r.floorY() - 1, z);
                        hatch = c0.containsKey(k) && c0.get(k) == null
                                && WalkThrough.kindOf(c0.get(ComposedPlan.key(x, r.floorY() - 2, z))) == WalkThrough.FREE
                                && WalkThrough.kindOf(c0.get(ComposedPlan.key(x, r.floorY() - 3, z))) == WalkThrough.FREE;
                    }
                }
                String size = r.columns() < 9 ? "tiny(<9)" : r.columns() < 25 ? "small(<25)" : "room(25+)";
                detail.merge(r.cause() + " " + size + (ladder ? " ladder-near" : "") + (stairs ? " stairs-near" : "")
                        + (lowDoor ? " low-door" : "") + (hatch ? " HATCH-no-ladder" : "") + (r.cut() != null ? " cut" : ""), 1, Integer::sum);
            }
            for (WalkThrough.Room r : before.unreachable()) {
                boolean lad = false;
                for (Map.Entry<Long, String> e : c0.entrySet()) {
                    lad |= e.getValue() != null && BuildSupport.base(e.getValue()).equals("ladder")
                            && ComposedPlan.kx(e.getKey()) >= r.minX() - 2 && ComposedPlan.kx(e.getKey()) <= r.maxX() + 2
                            && ComposedPlan.kz(e.getKey()) >= r.minZ() - 2 && ComposedPlan.kz(e.getKey()) <= r.maxZ() + 2
                            && ComposedPlan.ky(e.getKey()) >= r.floorY() - 6 && ComposedPlan.ky(e.getKey()) <= r.floorY() + 1;
                }
                if (lad && r.cause() != WalkThrough.Cause.LOW_OPENING && dumped < 9 && BuildRepair.of(pl.plan()).isEmpty()) {
                    dumped++;
                    StringBuilder d = new StringBuilder("DUMP " + pl.name() + " " + r.cause() + " " + r.describe() + " cut "
                            + java.util.Arrays.toString(r.cut()) + "\n");
                    for (int y = r.floorY() - 3; y <= r.floorY() + 1; y++) {
                        String[] lines = w.layer(y).split("\n");
                        int minX = Integer.parseInt(lines[0].replaceAll(".*x (-?\\d+)\\.\\..*", "$1"));
                        d.append(lines[0]).append('\n');
                        for (String line : lines) {
                            int at = line.lastIndexOf("  z=");
                            if (at < 0) {
                                continue;
                            }
                            int rz = Integer.parseInt(line.substring(at + 4).trim());
                            if (rz >= r.minZ() - 3 && rz <= r.maxZ() + 3) {
                                int a = Math.max(0, r.minX() - 3 - minX);
                                int b = Math.min(at, r.maxX() + 4 - minX);
                                d.append(a < b ? line.substring(a, b) : "").append("  z=").append(rz).append('\n');
                            }
                        }
                    }
                    dumps.append(d).append('\n');
                }
            }
            roomsBefore += before.unreachable().size();
            boolean clean = before.unreachable().isEmpty();
            allWalkBefore += clean ? 1 : 0;
            BuildRepair.Fixed f = BuildRepair.of(pl.plan());
            if (f.isEmpty()) {
                allWalkAfter += clean ? 1 : 0;
                roomsAfter += before.unreachable().size();
                continue;
            }
            touched++;
            touchedClean += clean ? 1 : 0;
            Map<Long, String> cells = BuildDoors.applied(pl.plan(), BuildDoors.of(pl.plan()));
            BuildWaterEntry.apply(cells, BuildWaterEntry.fix(pl.plan()));
            BuildRepair.apply(cells, f);
            WalkThrough.Report after = WalkThrough.over(cells, WalkThrough.BODY).report();
            long rb = before.rooms().stream().filter(WalkThrough.Room::reached).mapToInt(WalkThrough.Room::columns).sum();
            long ra = after.rooms().stream().filter(WalkThrough.Room::reached).mapToInt(WalkThrough.Room::columns).sum();
            lost += ra < rb ? 1 : 0;
            most = Math.max(most, f.cells().size());
            roomsAfter += after.unreachable().size();
            allWalkAfter += after.unreachable().isEmpty() ? 1 : 0;
            f.fixes().forEach(x -> kinds.merge(x.kind(), 1, Integer::sum));
            rows.append("| ").append(pl.name()).append(" | ").append(before.unreachable().size()).append(" | ")
                    .append(after.unreachable().size()).append(" | ").append(f.cells().size()).append(" | ");
            f.fixes().forEach(x -> rows.append(x.kind()).append(" @").append(x.x()).append(',').append(x.y()).append(',')
                    .append(x.z()).append("; "));
            rows.append("|\n");
        }
        String rep = "# BuildRepair over the agent's real buildings (#2114)\n\n"
                + n + " plans, " + judged + " small enough to walk.\n\n"
                + "| | repairs off | repairs on |\n|---|---|---|\n"
                + "| plans where every room can be walked into | " + allWalkBefore + " | " + allWalkAfter + " |\n"
                + "| rooms nobody can walk into | " + roomsBefore + " | " + roomsAfter + " |\n\n"
                + "Plans a repair touched: " + touched + ". Of those, plans whose rooms all walked already: " + touchedClean
                + ". Plans that lost reachable floor: " + lost + ". Most cells changed in one plan: " + most + ".\n\n"
                + "Why rooms were unreachable (repairs off): " + causes + "\n\nDetail: " + detail.toString().replace(", ", "\n  ") + "\n\nFixes made: " + kinds + "\n\n"
                + "| plan | unreachable before | after | cells changed | fixes |\n|---|---|---|---|---|\n" + rows;
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/build-repair-report.md"), rep);
        Files.writeString(Path.of("build/build-repair-dumps.txt"), dumps.toString());
        System.out.println(rep);
        int floor = com.moddingfromamod.CorpusSource.real() ? FLOOR_ALL_WALK : FIXTURE_FLOOR_ALL_WALK;
        org.junit.jupiter.api.Assertions.assertTrue(allWalkAfter >= floor, "plans where every room walks, repairs on: "
                + allWalkAfter + " < " + floor);
        org.junit.jupiter.api.Assertions.assertTrue(most <= BuildRepair.MOST_FIXES * BuildRepair.MOST_CELLS);
        assertEquals(0, touchedClean, "a repair touched a building whose rooms all walked");
        assertEquals(0, lost, "a repair lost reachable floor");
    }

    /** The measured floor (2026-11-12): fewer fixed plans than this is a repair lost. */
    static final int FLOOR_ALL_WALK = 173;

    /** The same floor on the public fixture: the shipped presets and the fixture's hand-made faults. */
    static final int FIXTURE_FLOOR_ALL_WALK = 42;
}
