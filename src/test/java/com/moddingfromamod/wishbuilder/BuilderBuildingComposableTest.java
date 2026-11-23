package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildSupport;
import com.moddingfromamod.builder.WalkThrough;
import com.moddingfromamod.testing.ledger.GateLedger;
import com.moddingfromamod.ui.PickPage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * <b>Every building ready-made is a composition</b>: its type with nothing outside lowers to the SAME
 * build_structure call as the ready-made did (with and without a colour scheme, at every size), and a saved one reads
 * as that composition. And what goes outside never costs a room a child could walk into, and never adds a block that
 * cannot stand: each option alone on every (type, size) is walked and checked, and their zones are proved independent
 * ({@link OutsideZones}), so no combination needs building.
 */
class BuilderBuildingComposableTest {

    /** Every building ready-made the Builder ever offered: the builder library's and the starter library's. */
    static List<String> readyBuildings() {
        List<String> out = new ArrayList<>();
        for (String p : Catalogue.presets()) {
            if (p.startsWith("buildings/") && LibraryPresets.SHIPPED.read(p) != null) {
                out.add(p);
            }
        }
        return out;
    }

    static List<BuilderLowering.Call> fromPreset(String preset, String scheme, int size, String name) {
        return BuilderLowering.lower(new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.BUILDING, "preset:" + preset, scheme,
                size, List.of(), "", 1, "", name, Map.of()), "mine_1", LibraryPresets.SHIPPED);
    }

    /** The menus: A building → the type → what goes outside (if it asks) → tune: size, colours, name. */
    static BuilderSpec composed(String preset, String outside, String scheme, int size, String name) {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        f.press("building");
        assertEquals(BuilderFlow.Step.BUILD_TYPE, f.step(), "the building kind opens on its types");
        f.press(preset);
        if (f.step() == BuilderFlow.Step.BUILD_OUTSIDE) {
            // Several at once: each one, then Done (a full page moves on by itself); none is "just the building".
            List<String> on = BuilderBuildings.parse(outside);
            if (on.isEmpty()) {
                f.press("outside:" + BuilderBuildings.NOTHING);
            }
            on.forEach(o -> f.press("outside:" + o));
            if (f.step() == BuilderFlow.Step.BUILD_OUTSIDE) {
                f.press(com.moddingfromamod.ui.PickPage.NEXT_ID);
            }
        }
        assertEquals(BuilderFlow.Step.TUNE, f.step(), preset + " + " + outside);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("size", String.valueOf(size));
        values.put("scheme", scheme.isEmpty() ? "as_is" : scheme);
        // Moddy names a composed make; the comparison is of everything else, under the ready-made's name.
        return f.spec(values).withName(name);
    }

    @Test
    void everyReadyMadeIsAType() {
        List<String> all = readyBuildings();
        assertTrue(all.size() >= 19, all.toString());
        Set<String> types = new TreeSet<>(BuilderBuildings.presets());
        List<String> missing = all.stream().filter(p -> !types.contains(p)).toList();
        assertTrue(missing.isEmpty(), "building ready-mades no type is: " + missing);
    }

    /**
     * phase 2, the fingerprint ledger ({@link GateLedger}): the libraries every building check can read. A
     * building type's own preset is left out of it ({@link #presetInputs}, {@link #allPresetInputs}) and hashed into its
     * own variants only, so editing one preset re-tests that type alone. What else a check reads by name is the libraries'
     * other files (indexes, size tables, creature bodies): an edit there re-tests every variant, on the safe side.
     */
    static final List<String> LIBRARY = List.of("src/main/resources/starter-library", "src/main/resources/builder-library",
            "src/main/resources/builder-bodies");

    /** A preset's own files, wherever {@link LibraryPresets} may find them (the ones that exist are hashed). */
    static List<String> presetInputs(String preset) {
        if (preset.startsWith("bodies/")) {
            return List.of("src/main/resources/builder-bodies/species/" + preset.substring("bodies/".length()));
        }
        return List.of("src/main/resources/starter-library/" + preset, "src/main/resources/builder-library/" + preset);
    }

    static List<String> allPresetInputs() {
        List<String> out = new ArrayList<>();
        BuilderBuildings.TYPES.forEach(t -> out.addAll(presetInputs(t.preset())));
        return out;
    }

    /** The ledger check for a building test method: the libraries shared, each type's preset its own. */
    static GateLedger.Check ledger(Class<?> test, String method) {
        return GateLedger.shared().check(test, method, LIBRARY, allPresetInputs());
    }

    /** A (type, size) variant: its preset's files are its own input. */
    static GateLedger.Variant variant(GateLedger.Check check, BuilderBuildings.Type t, int size) {
        return check.variant(t.id() + " size " + size, presetInputs(t.preset()), "");
    }

    @Test
    void everyReadyMadeLowersToTheSameCallsAsItsComposition() {
        List<String> wrong = new ArrayList<>();
        GateLedger.Check check = ledger(BuilderBuildingComposableTest.class, "everyReadyMadeLowersToTheSameCallsAsItsComposition");
        for (String p : readyBuildings()) {
            GateLedger.Variant v = check.variant(p, presetInputs(p), "");
            if (v.skip()) {
                continue;
            }
            int before = wrong.size();
            String name = LibraryPresets.SHIPPED.label(p);
            for (String scheme : new String[] {"", "ocean"}) {
                for (int size : BuilderSizes.offered(BuilderBuildings.type(p).id())) {   //: the sizes it is offered
                    var a = fromPreset(p, scheme, size, name);
                    var b = BuilderLowering.lower(composed(p, BuilderBuildings.NOTHING, scheme, size, name), "mine_1",
                            LibraryPresets.SHIPPED);
                    if (!a.equals(b)) {
                        wrong.add(p + " " + scheme + " size " + size + "\n  ready-made: " + a + "\n  composed:   " + b);
                    }
                }
            }
            v.done(wrong.subList(before, wrong.size()));
        }
        wrong.addAll(check.finish());
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    /** the tune dialog offers only the sizes measured to work, says why the others aren't, and never makes one. */
    @Test
    void aBuildingIsOfferedOnlyTheSizesThatWork() {
        for (BuilderBuildings.Type t : BuilderBuildings.TYPES) {
            List<Integer> ok = BuilderSizes.offered(t.id());
            BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
            f.press("building");
            f.press(t.preset());
            if (f.step() == BuilderFlow.Step.BUILD_OUTSIDE) {
                f.press("outside:" + BuilderBuildings.NOTHING);
            }
            if (f.step() == BuilderFlow.Step.BUILD_OUTSIDE) {
                f.press(PickPage.NEXT_ID);
            }
            assertEquals(BuilderFlow.Step.TUNE, f.step(), t.id());
            var tune = f.tune();
            var slider = tune.inputs().stream().filter(i -> i.key().equals("size")).findFirst();
            if (ok.size() == 1) {
                assertTrue(slider.isEmpty(), t.id() + ": one size, no slider");
            } else {
                var sl = (com.moddingfromamod.ui.TuneDialog.Slider) slider.orElseThrow();
                assertEquals(ok.get(0), sl.start(), t.id());
                assertEquals(ok.get(ok.size() - 1), sl.end(), t.id());
            }
            assertEquals(ok.size() < 4, !tune.body().isEmpty(), t.id() + ": a size not offered is said, and why");
            for (int asked = 1; asked <= 4; asked++) {
                Map<String, String> v = new LinkedHashMap<>();
                v.put("size", String.valueOf(asked));
                assertTrue(ok.contains(f.spec(v).size()), t.id() + " size " + asked);
            }
        }
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        f.press("building");
        f.press("buildings/wizard_tower");
        f.press("outside:" + BuilderBuildings.NOTHING);
        if (f.step() == BuilderFlow.Step.BUILD_OUTSIDE) {
            f.press(PickPage.NEXT_ID);
        }
        // with the role-based scaler the tower's stair gains treads, so sizes 3 and 4 work; at size 1 the
        // spiral no longer fits the narrower well.
        assertEquals(List.of("Size 1 isn't ready: you couldn't reach its rooms"),
                f.tune().body().stream().map(com.moddingfromamod.ui.TuneDialog.Line::words).toList());
    }

    @Test
    void aSpecSavedFromAReadyMadeReadsAsItsComposition() {
        for (String p : readyBuildings()) {
            for (String named : new String[] {"", "My Den"}) {
                JsonObject saved = JsonParser.parseString("{\"builder\":1,\"kind\":\"building\",\"from\":\"preset:" + p + "\",\"size\":3"
                        + (named.isEmpty() ? "" : ",\"name\":\"" + named + "\"") + "}").getAsJsonObject();
                BuilderSpec read = BuilderSpec.read(saved);
                assertEquals("", read.from(), p);
                assertTrue(BuilderBuildings.composed(read), p);
                assertEquals(fromPreset(p, "", 3, named), BuilderLowering.lower(read, "mine_1", LibraryPresets.SHIPPED),
                        p + ": the migrated spec builds something else");
                BuilderFlow edit = BuilderFlow.editing(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, "id1", read);
                assertEquals(BuilderFlow.Step.BUILD_TYPE, edit.step(), p + ": an edit reopens at the building's first page (#2281)");
                EditWalk.toTune(edit);
            }
        }
    }

    @Test
    void theBuildingKindHasNoReadyMadePage() {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        assertTrue(f.fromIds(BuilderSpec.Kind.BUILDING).isEmpty());
        f.press("building");
        assertEquals(BuilderFlow.Step.BUILD_TYPE, f.step());
        assertEquals(BuilderHouses.SIMPLE.size() + 23, f.page().tiles().size(), "the simple types from parts, then 19 bodies and 4 working builds (#2184)");
        f.press(PickPage.BACK_ID);
        assertEquals(BuilderFlow.Step.KIND, f.step());
    }

    /** A type that does not stand on the ground goes straight to the tune dialog: nothing can go outside it. */
    @Test
    void onlyATypeOnTheGroundAsksWhatGoesOutside() {
        for (BuilderBuildings.Type t : BuilderBuildings.TYPES) {
            BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
            f.press("building");
            f.press(t.preset());
            assertEquals(t.takesOutside() ? BuilderFlow.Step.BUILD_OUTSIDE : BuilderFlow.Step.TUNE, f.step(), t.id());
            if (t.takesOutside()) {
                assertTrue(f.page().multi(), t.id() + ": what goes outside is a multi-pick (#1998's picks row)");
                assertEquals(BuilderBuildings.OUTSIDE.size() - 1, f.page().tiles().size(), t.id() + ": Done with none is nothing");
            }
        }
    }

    static BuildPlan plan(BuilderSpec spec) {
        JsonObject a = BuilderLowering.lower(spec, "mine_1", LibraryPresets.SHIPPED).get(1).args();
        return BuildPlan.fromJson(a);
    }

    /**
     * what goes outside, measured option by option on every grounded type at every size (cached: four tests read
     * it). The old sweep built every SUBSET of the options (2^5 per type and size, 157 s); independence by zone makes
     * each option alone a proof for every combination ({@link OutsideZones}).
     */
    private static final Map<String, OutsideZones.Measured> MEASURED = new java.util.concurrent.ConcurrentHashMap<>();

    static OutsideZones.Measured measured(BuilderBuildings.Type t, int size) {
        return MEASURED.computeIfAbsent(t.id() + " size " + size, what -> OutsideZones.measure(what,
                o -> plan(composed(t.preset(), o, "", size, "X"))));
    }

    static List<BuilderBuildings.Type> grounded() {
        return BuilderBuildings.TYPES.stream().filter(BuilderBuildings.Type::takesOutside).toList();
    }

    /**
     * the pairs of outside options whose zones DO meet on some building, so are built and checked together
     * ({@code BuilderOutsideAloneTest.theDeclaredPairsTogether}). Each says why. The independence check fails a pair that meets and is not
     * here, and a pair here that never meets (a stale line hides nothing).
     */
    static final Map<String, String> INTERACTING = Map.of(
            "lamps+flag", "the flag's pole stands behind, one block out from the back corner's lamp post: two posts side by "
                    + "side on every building, so the pair is walked and stood together");

    /**
     * the options that move the building's dark-room lights ({@code BuilderLowering.light}, set after everything
     * else), each with why. A light is not in any zone ({@link OutsideZones#lights}); this is where that is said.
     */
    static final Map<String, String> RELIGHTS = Map.of();

    /**
     * (found 's zones): what goes outside is GENERATED, outside {@link BuildPlan#MAX_SHAPES}, so no building
     * loses any of it to the cap. A mansion, a wizard tower at sizes 1-2, the lava castle and the hedge maze used to lose
     * 7-15 shapes with everything outside, silently. Every grounded type, every size, each option alone (the cached zones)
     * and all of them: the written count is the bare body's, and every shape the lowering wrote is in the plan (a lowering
     * that would lose one is refused, {@link #aBuildingOverTheShapeCapIsRefusedNotCut}).
     */
    @Test
    void nothingOutsideIsLostToTheShapeCap() {
        List<String> bad = new ArrayList<>();
        String all = BuilderBuildings.join(OutsideZones.OPTIONS);
        for (BuilderBuildings.Type t : grounded()) {
            for (int size = 1; size <= 4; size++) {
                OutsideZones.Measured m = measured(t, size);
                int bare = BuildPlan.written(m.bare().shapes());
                Map<String, BuildPlan> each = new LinkedHashMap<>(m.alone());
                JsonObject args = BuilderLowering.lower(composed(t.preset(), all, "", size, "X"), "mine_1", LibraryPresets.SHIPPED).get(1).args();
                BuildPlan everything = BuildPlan.fromJson(args);
                if (everything.shapes().size() != args.getAsJsonArray("shapes").size()) {
                    bad.add(m.what() + " + everything: " + (args.getAsJsonArray("shapes").size() - everything.shapes().size()) + " shapes not built");
                }
                each.put(all, everything);
                each.forEach((o, p) -> {
                    if (BuildPlan.written(p.shapes()) != bare) {
                        bad.add(m.what() + " + " + o + ": " + BuildPlan.written(p.shapes()) + " written shapes, the body alone " + bare);
                    }
                });
            }
        }
        assertTrue(bad.isEmpty(), "things outside a building that count against the shape cap or are not built:\n" + String.join("\n", bad));
        var body = BuilderLowering.lower(composed(BuilderBuildings.type("cozy_house").preset(), BuilderBuildings.NOTHING, "", 2, "X"), "mine_1", LibraryPresets.SHIPPED)
                .get(1).args().getAsJsonArray("shapes");
        var outside = BuilderBuildings.shapes(all, body);
        assertEquals(BuilderBuildings.MOST_SHAPES, outside.size(), "everything outside lays MOST_SHAPES shapes");
        outside.forEach(e -> assertTrue(e.getAsJsonObject().get("gen").getAsBoolean(), "generated: " + e));
    }

    /** a Builder building that WOULD lose a shape to a cap is refused in words, never built short. */
    @Test
    void aBuildingOverTheShapeCapIsRefusedNotCut() {
        com.google.gson.JsonArray many = new com.google.gson.JsonArray();
        for (int i = 0; i < BuildPlan.MAX_SHAPES; i++) {
            JsonObject s = new JsonObject();
            s.addProperty("kind", "box");
            s.addProperty("block", "minecraft:stone");
            many.add(s);
        }
        assertEquals(null, BuilderLowering.overTheShapeCaps(many), "exactly the cap is kept whole");
        many.add(many.get(0).deepCopy());
        String said = BuilderLowering.overTheShapeCaps(many);
        assertTrue(said != null && said.contains("65 parts") && said.contains("last 1 would not be built"), String.valueOf(said));
        com.google.gson.JsonArray made = new com.google.gson.JsonArray();
        for (int i = 0; i <= BuildPlan.MAX_GENERATED; i++) {
            JsonObject s = new JsonObject();
            s.addProperty("block", "minecraft:stone");
            s.addProperty("gen", true);
            made.add(s);
        }
        assertTrue(String.valueOf(BuilderLowering.overTheShapeCaps(made)).contains("generated pieces"));
        made.remove(0);
        assertEquals(null, BuilderLowering.overTheShapeCaps(made), "generated pieces up to their own cap are kept");
    }

    /**
     * the zones are independent on every grounded type at every size: no two options share a cell or stand next to
     * each other, none but the path is on the way in, none is drawn over the building. Pure: no build, no walk.
     */
    @Test
    void theZonesOutsideAreIndependent() {
        Map<String, List<String>> where = new java.util.TreeMap<>();
        Map<String, List<String>> relit = new java.util.TreeMap<>();
        List<String> over = new ArrayList<>();
        // phase 2: a (type, size) whose fingerprint passed is not measured again; it replays its FACTS (the pairs
        // that meet on it, the options that move its lights), which the declarations below are checked against.
        GateLedger.Check check = ledger(BuilderBuildingComposableTest.class, "theZonesOutsideAreIndependent");
        for (BuilderBuildings.Type t : grounded()) {
            for (int size = 1; size <= 4; size++) {
                GateLedger.Variant v = variant(check, t, size);
                String what = t.id() + " size " + size;
                List<String> facts = new ArrayList<>();
                List<String> mine = new ArrayList<>();
                if (v.skip()) {
                    facts.addAll(v.facts());
                } else {
                    OutsideZones.Measured m = measured(t, size);
                    m.relit().forEach(o -> facts.add("relit\t" + o));
                    for (OutsideZones.Finding f : OutsideZones.interactions(m.zones(), OutsideZones.entry(m.zones()), m.bareCells().keySet())) {
                        if (f.b().equals(OutsideZones.WAY_IN) || f.b().equals(OutsideZones.BUILDING)) {
                            mine.add(m.what() + ": " + f);
                        } else {
                            facts.add("meet\t" + f.pair() + "\t" + f.how());
                        }
                    }
                    v.done(mine, facts);
                    over.addAll(mine);
                }
                for (String fact : facts) {
                    String[] p = fact.split("\t");
                    if (p[0].equals("relit")) {
                        relit.computeIfAbsent(p[1], k -> new ArrayList<>()).add(what);
                    } else {
                        where.computeIfAbsent(p[1], k -> new ArrayList<>()).add(what + " (" + p[2] + ")");
                    }
                }
            }
        }
        over.addAll(check.finish());
        assertTrue(over.isEmpty(), "on the way in or over the building:\n" + String.join("\n", over));
        List<String> undeclared = new ArrayList<>();
        where.forEach((pair, at) -> {
            if (!INTERACTING.containsKey(pair)) {
                undeclared.add(pair + " meet on " + at.size() + ": " + at.subList(0, Math.min(6, at.size())));
            }
        });
        assertTrue(undeclared.isEmpty(), "options that meet and are not declared to (declare them in INTERACTING, with why):\n"
                + String.join("\n", undeclared));
        List<String> stale = INTERACTING.keySet().stream().filter(p -> !where.containsKey(p)).toList();
        assertTrue(stale.isEmpty(), "declared to meet, and they never do: " + stale);
        List<String> newLights = relit.keySet().stream().filter(o -> !RELIGHTS.containsKey(o)).map(o -> o + " on " + relit.get(o)).toList();
        assertTrue(newLights.isEmpty(), "options that move the building's dark-room lights, not declared in RELIGHTS:\n" + String.join("\n", newLights));
        assertTrue(RELIGHTS.keySet().stream().allMatch(relit::containsKey), "declared to move the lights and never do: " + RELIGHTS.keySet());
    }

    /** the check fires. A fixture option drawn on the way in, and one over the lamps, are both named. */
    @Test
    void theIndependenceCheckFires() {
        OutsideZones.Measured m = measured(BuilderBuildings.type("cozy_house"), 2);
        Map<String, java.util.Set<Long>> zones = new LinkedHashMap<>(m.zones());
        java.util.Set<Long> entry = OutsideZones.entry(zones);
        zones.put("fixture_on_the_path", java.util.Set.of(entry.iterator().next()));
        zones.put("fixture_over_the_lamps", java.util.Set.of(m.zones().get("lamps").iterator().next()));
        var found = OutsideZones.interactions(zones, entry, m.bareCells().keySet());
        assertTrue(found.stream().anyMatch(f -> f.a().equals("fixture_on_the_path") && f.b().equals(OutsideZones.WAY_IN)), found.toString());
        assertTrue(found.stream().anyMatch(f -> f.pair().equals("lamps+fixture_over_the_lamps") && f.how().equals("overlap")), found.toString());
        // and a zone beside another, sharing no cell, is a touch
        long k = m.zones().get("pool").iterator().next();
        var beside = OutsideZones.interactions(Map.of("a", java.util.Set.of(k),
                "b", java.util.Set.of(com.moddingfromamod.builder.WalkProbe.key(com.moddingfromamod.builder.WalkProbe.kx(k) + 1,
                        com.moddingfromamod.builder.WalkProbe.ky(k), com.moddingfromamod.builder.WalkProbe.kz(k)))), java.util.Set.of(), java.util.Set.of());
        assertEquals(1, beside.size(), beside.toString());
        assertEquals("touch", beside.get(0).how());
    }

    /**
     * EVERYTHING outside at once is exactly the bare building with each option's own change laid over it (so the
     * steps after the options, the dark-room lights, change nothing), and it walks and stands.
     */
    @Test
    void everythingOutsideIsTheSumOfItsParts() {
        List<String> bad = new ArrayList<>();
        String all = BuilderBuildings.join(OutsideZones.OPTIONS);
        GateLedger.Check check = ledger(BuilderBuildingComposableTest.class, "everythingOutsideIsTheSumOfItsParts");
        for (BuilderBuildings.Type t : grounded()) {
            for (int size = 1; size <= 4; size++) {
                GateLedger.Variant v = variant(check, t, size);
                if (v.skip()) {
                    continue;
                }
                List<String> mine = new ArrayList<>();
                OutsideZones.Measured m = measured(t, size);
                BuildPlan with = plan(composed(t.preset(), all, "", size, "X"));
                java.util.Set<Long> off = OutsideZones.notTheSum(m, with);
                if (!off.isEmpty()) {
                    mine.add(m.what() + " + everything: " + off.size() + " cells differ from the bare building plus each option's own change");
                }
                mine.addAll(OutsideZones.noWorseThanBare(m.what() + " + everything", m.bare(), with));
                v.done(mine);
                bad.addAll(mine);
            }
        }
        bad.addAll(check.finish());
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    /** a combination's shapes are its options' own, one after another (the zones argument stands on it). */
    @Test
    void theOptionsAreLaidOutIndependently() {
        for (BuilderBuildings.Type t : grounded()) {
            var body = BuilderLowering.lower(composed(t.preset(), BuilderBuildings.NOTHING, "", 2, "X"), "mine_1", LibraryPresets.SHIPPED)
                    .get(1).args().getAsJsonArray("shapes");
            com.google.gson.JsonArray each = new com.google.gson.JsonArray();
            OutsideZones.OPTIONS.forEach(o -> each.addAll(BuilderBuildings.shapes(o, body)));
            assertEquals(each, BuilderBuildings.shapes(BuilderBuildings.join(OutsideZones.OPTIONS), body), t.id());
        }
    }

    @Test
    void whatGoesOutsideIsOutsideTheFootprint() {
        for (BuilderBuildings.Type t : BuilderBuildings.TYPES) {
            if (!t.takesOutside()) {
                continue;
            }
            BuilderSpec bareSpec = composed(t.preset(), BuilderBuildings.NOTHING, "", 2, "X");
            var body = BuilderLowering.lower(bareSpec, "mine_1", LibraryPresets.SHIPPED).get(1).args().getAsJsonArray("shapes");
            int[] fp = BuilderBuildings.footprint(body);
            java.util.Set<Long> solid = new java.util.HashSet<>();
            for (var e : body) {
                var s = com.moddingfromamod.builder.BuildShape.fromJson(e.getAsJsonObject());
                if (s != null && !s.block().endsWith("air")) {
                    s.forEachBlock((x, y, z) -> solid.add(((long) x << 40) ^ ((long) y << 20) ^ z));
                }
            }
            for (String o : OutsideZones.OPTIONS) {   // each alone: a combination's shapes are theirs (theOptionsAreLaidOutIndependently)
                for (var e : BuilderBuildings.shapes(o, body)) {
                    var s = com.moddingfromamod.builder.BuildShape.fromJson(e.getAsJsonObject());
                    boolean[] inside = {false};
                    boolean[] onIt = {false};
                    s.forEachBlock((x, y, z) -> {
                        inside[0] |= x >= fp[0] && x <= fp[2] && z >= fp[1] && z <= fp[3];
                        onIt[0] |= solid.contains(((long) x << 40) ^ ((long) y << 20) ^ z);
                    });
                    // only the path may come in, over open ground, to a door set back inside the footprint.
                    assertFalse(inside[0] && !s.name().startsWith("path_"), t.id() + " + " + o + ": " + s.name() + " is inside the building");
                    assertFalse(onIt[0], t.id() + " + " + o + ": " + s.name() + " is drawn over the building itself");
                }
            }
        }
    }
}
