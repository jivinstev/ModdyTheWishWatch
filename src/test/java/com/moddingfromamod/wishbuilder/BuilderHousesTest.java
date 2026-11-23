package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.WalkThrough;
import com.moddingfromamod.ui.PickPage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.moddingfromamod.testing.AllPairs;
import com.moddingfromamod.testing.GateTier;
import com.moddingfromamod.testing.ledger.NightlySampler;
import org.junit.jupiter.api.Test;

/**
 * <b>A simple building from parts</b>: an all-pairs sample of simple type × floors × roof × door side × windows,
 * with each thing outside alone and everything outside (what goes outside is independent by zone), is lowered as
 * the Builder makes it and WALKED (every room a child can get into, from outside, up the ladder) and checked to STAND
 * (nothing that falls, no ladder on nothing). A failure is named: type, floors, roof, door, windows, outside, and why.
 */
class BuilderHousesTest {

    static BuilderSpec spec(String type, int floors, String roof, String door, String windows, String outside) {
        return new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.BUILDING, "", "", 2, List.of(), "", 1, "", "",
                BuilderHouses.opts(type, floors, roof, door, windows, outside));
    }

    static BuildPlan plan(String type, int floors, String roof, String door, String windows, String outside) {
        JsonObject a = BuilderLowering.lower(spec(type, floors, roof, door, windows, outside), "mine_1", LibraryPresets.SHIPPED).get(1).args();
        return BuildPlan.fromJson(a);
    }

    /**
     * the houses' parts are not independent of each other (the roof sits on the floors, the windows skip the
     * door), so the bodies are an ALL-PAIRS sample: every value of every part appears with every value of every other
     * part in at least one house ({@link AllPairs}, ~25 houses where the product was 720, and it grows with the log of
     * the parts, not their product), plus a few from a fixed seed. What goes outside IS independent, by zone, as on the
     * ready-made types ({@link OutsideZones}): each option alone on every sampled house, the zones checked independent,
     * and everything outside at once checked to be the sum of its parts.
     */
    static List<List<String>> factors() {
        List<String> floors = new ArrayList<>();
        for (int f = 1; f <= BuilderHouses.TALLEST; f++) {
            floors.add(String.valueOf(f));
        }
        return List.of(BuilderHouses.SIMPLE.stream().map(BuilderHouses.Simple::id).toList(), floors,
                List.copyOf(BuilderHouses.ROOFS.keySet()), List.copyOf(BuilderHouses.DOORS.keySet()),
                List.copyOf(BuilderHouses.WINDOWS.keySet()));
    }

    static List<List<String>> houses() {
        List<List<String>> factors = factors();
        List<List<String>> out = new ArrayList<>(AllPairs.of(factors, 2222));
        java.util.Random rnd = new java.util.Random(1995);
        for (int i = 0; i < 4; i++) {
            List<String> row = new ArrayList<>();
            factors.forEach(f -> row.add(f.get(rnd.nextInt(f.size()))));
            out.add(row);
        }
        return out;
    }

    /** Pairs of outside options that meet round a simple house, each with why: see {@code BuilderBuildingComposableTest.INTERACTING}. */
    static final Map<String, String> INTERACTING = Map.of(
            "lamps+flag", "the flag's pole stands one block out from the back corner's lamp post, round every house");

    /** Options that move a house's dark-room lights, each with why ({@link OutsideZones#lights}). */
    static final Map<String, String> RELIGHTS = Map.of(
            "lamps", "a small house's rooms are lit through the door and windows by the corner lanterns, so it needs fewer floor lights");

    @Test
    void everyPartIsWalkableAndStands() {
        List<String> bad = new ArrayList<>();
        Map<String, List<String>> met = new java.util.TreeMap<>();
        java.util.Set<String> relit = new java.util.TreeSet<>();
        String all = BuilderBuildings.join(OutsideZones.OPTIONS);
        List<List<String>> houses = houses();
        for (List<String> h : houses) {
            int floors = Integer.parseInt(h.get(1));
            String what = h.get(0) + " " + floors + " floors, " + h.get(2) + " roof, door " + h.get(3) + ", " + h.get(4) + " windows";
            OutsideZones.Measured m = OutsideZones.measure(what, o -> plan(h.get(0), floors, h.get(2), h.get(3), h.get(4), o));
            bad.addAll(OutsideZones.walkableAndStands(what, m.bare()));
            for (String o : OutsideZones.OPTIONS) {
                bad.addAll(OutsideZones.walkableAndStands(what + ", " + o, m.alone().get(o)));
            }
            for (OutsideZones.Finding f : OutsideZones.interactions(m.zones(), OutsideZones.entry(m.zones()), m.bareCells().keySet())) {
                if (f.b().equals(OutsideZones.WAY_IN) || f.b().equals(OutsideZones.BUILDING)) {
                    bad.add(what + ": " + f);
                } else {
                    met.computeIfAbsent(f.pair(), k -> new ArrayList<>()).add(what);
                    if (INTERACTING.containsKey(f.pair())) {
                        bad.addAll(OutsideZones.walkableAndStands(what + ", " + f.pair(), plan(h.get(0), floors, h.get(2), h.get(3), h.get(4),
                                BuilderBuildings.join(List.of(f.a(), f.b())))));
                    }
                }
            }
            BuildPlan everything = plan(h.get(0), floors, h.get(2), h.get(3), h.get(4), all);
            relit.addAll(m.relit());
            // what goes outside is generated, outside the 64-shape cap: the house writes what its body writes, and a
            // three-floor barn with many windows (16 shapes not built with everything outside) keeps all of it.
            var args = BuilderLowering.lower(spec(h.get(0), floors, h.get(2), h.get(3), h.get(4), all), "mine_1", LibraryPresets.SHIPPED)
                    .get(1).args();
            if (OutsideZones.written(args) != BuildPlan.written(m.bare().shapes()) || everything.shapes().size() != args.getAsJsonArray("shapes").size()) {
                bad.add(what + ", everything outside: " + OutsideZones.written(args) + " written shapes (the bare house "
                        + BuildPlan.written(m.bare().shapes()) + "), " + (args.getAsJsonArray("shapes").size() - everything.shapes().size()) + " not built");
            } else if (!OutsideZones.notTheSum(m, everything).isEmpty()) {
                bad.add(what + ", everything outside: not the bare house plus each option's own change");
            }
            bad.addAll(OutsideZones.walkableAndStands(what + ", everything outside", everything));
        }
        met.keySet().stream().filter(p -> !INTERACTING.containsKey(p))
                .forEach(p -> bad.add(p + " meet round " + met.get(p).size() + " houses and are not declared: " + met.get(p).get(0)));
        INTERACTING.keySet().stream().filter(p -> !met.containsKey(p)).forEach(p -> bad.add(p + " declared to meet, and never do"));
        relit.stream().filter(o -> !RELIGHTS.containsKey(o)).forEach(o -> bad.add(o + " moves a house's dark-room lights and is not declared"));
        assertTrue(houses.size() < 40, "all-pairs stays small: " + houses.size());
        assertTrue(bad.isEmpty(), bad.size() + " problems over " + houses.size() + " houses:\n"
                + String.join("\n", bad.subList(0, Math.min(40, bad.size()))));
    }

    /**
     * phase 2, THE NIGHTLY ({@code -Pgate.nightly}; skipped in Gate A): whole houses with a SUBSET of things outside
     * built at once — the combinations Gate A proves by independence instead of building — all-triples of the six parts,
     * then random ones no night has tried, for a fixed time ({@link NightlySampler}). A failure prints its seed.
     */
    @Test
    void nightlyHousesWithThingsOutsideSampled() {
        org.junit.jupiter.api.Assumptions.assumeTrue(GateTier.nightly(), "the nightly's sample (-Pgate.nightly)");
        List<List<String>> factors = new ArrayList<>(factors());
        factors.add(outsideSubsets(true));
        NightlySampler.Outcome o = NightlySampler.of(BuilderHousesTest.class, "nightlyHousesWithThingsOutsideSampled", factors, 240)
                .run(h -> {
                    String what = h.get(0) + " " + h.get(1) + " floors, " + h.get(2) + " roof, door " + h.get(3) + ", " + h.get(4)
                            + " windows, outside: " + h.get(5);
                    return OutsideZones.walkableAndStands(what, plan(h.get(0), Integer.parseInt(h.get(1)), h.get(2), h.get(3), h.get(4), h.get(5)));
                });
        assertTrue(o.failures().isEmpty(), String.join("\n", o.failures()));
    }

    /** Every subset of the things outside, as the Builder's value (nothing, one, several): a FACTOR the nightly samples. */
    static List<String> outsideSubsets(boolean withNothing) {
        List<String> out = new ArrayList<>();
        List<String> ids = OutsideZones.OPTIONS;
        // gate-a: bounded - a factor of the nightly sampler, which is time-budgeted (NightlySampler); never enumerated
        for (int mask = withNothing ? 0 : 1; mask < 1 << ids.size(); mask++) {
            List<String> on = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                if ((mask & 1 << i) != 0) {
                    on.add(ids.get(i));
                }
            }
            out.add(on.isEmpty() ? BuilderBuildings.NOTHING : BuilderBuildings.join(on));
        }
        return out;
    }

    /** the sample really holds every pair of parts (the sampler's own test proves it in general; this, here). */
    @Test
    void theSampleHoldsEveryPairOfParts() {
        List<List<String>> h = houses();
        List<List<String>> factors = factors();
        for (int i = 0; i < factors.size(); i++) {
            for (int j = i + 1; j < factors.size(); j++) {
                for (String a : factors.get(i)) {
                    for (String b : factors.get(j)) {
                        int fi = i;
                        int fj = j;
                        assertTrue(h.stream().anyMatch(r -> r.get(fi).equals(a) && r.get(fj).equals(b)), a + " with " + b);
                    }
                }
            }
        }
    }

    /** Every storey is a room he reaches: the walk finds as many reachable rooms as there are floors. */
    @Test
    void everyStoreyIsAReachableRoom() {
        for (BuilderHouses.Simple s : BuilderHouses.SIMPLE) {
            for (int floors = 1; floors <= BuilderHouses.TALLEST; floors++) {
                WalkThrough.Report r = WalkThrough.of(plan(s.id(), floors, "pointed", "front", "some", "nothing"));
                long reached = r.rooms().stream().filter(WalkThrough.Room::reached).count();
                assertTrue(reached >= floors, s.id() + " with " + floors + " floors: " + reached + " rooms reached");
            }
        }
    }

    /** The menus: A building → A house → a roof → windows → outside → tune (floors, door, colours, name). */
    @Test
    void theMenusBuildItFromParts() {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        f.press("building");
        assertEquals("simple:house", f.page().tiles().get(0).id(), "the simple types come first");
        f.press("simple:house");
        assertEquals(BuilderFlow.Step.BUILD_ROOF, f.step());
        assertEquals(4, f.page().tiles().size());
        f.press("roof:dome");
        assertEquals(BuilderFlow.Step.BUILD_WINDOWS, f.step());
        f.press("windows:lots");
        assertEquals(BuilderFlow.Step.BUILD_OUTSIDE, f.step());
        f.press("outside:garden");
        assertEquals(BuilderFlow.Step.BUILD_OUTSIDE, f.step(), "a multi-pick: he may add more");
        f.press("outside:flag");
        assertEquals(List.of("outside:garden", "outside:flag"), f.page().chosen().stream().map(com.moddingfromamod.ui.PickPage.Tile::id).toList(),
                "both picks are in the picks row (no count in the title since #2043)");
        f.press("outside:flag");   // and take one off again
        f.press(com.moddingfromamod.ui.PickPage.NEXT_ID);
        assertEquals(BuilderFlow.Step.TUNE, f.step());
        assertEquals(List.of("floors", "door", "scheme"), f.tune().keys());
        Map<String, String> v = new LinkedHashMap<>();
        v.put("floors", "3");
        v.put("door", "left");
        BuilderSpec spec = f.spec(v);
        assertTrue(BuilderHouses.composed(spec));
        assertEquals("3", spec.opt("floors"));
        assertEquals("left", spec.opt("door"));
        assertTrue(BuilderAllowlist.REVIEWED.problems(spec).isEmpty(), BuilderAllowlist.REVIEWED.problems(spec).toString());
        var a = BuilderLowering.lower(spec, "mine_1", LibraryPresets.SHIPPED).get(1).args();
        assertEquals(spec.name(), a.get("name").getAsString(), "Moddy's name for it");
        assertTrue(spec.name().startsWith("Tall ") || spec.name().endsWith("Mansion"), "three floors say so: " + spec.name());
        assertTrue(a.get("note").getAsString().contains("built from parts"));
        // Back walks back through the parts.
        f.press(PickPage.BACK_ID);
        assertEquals(BuilderFlow.Step.TUNE, f.step(), "the tune dialog is not a page; back from it is the menu's");
    }

    @Test
    void anEditReopensWithItsParts() {
        BuilderSpec spec = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.BUILDING, "", "", 2, List.of(), "", 1, "", "Barny",
                BuilderHouses.opts("barn", 2, "sloped", "back", "none", "pool"));
        BuilderFlow edit = BuilderFlow.editing(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, "id1", spec);
        assertEquals(BuilderFlow.Step.BUILD_TYPE, edit.step(), "an edit reopens at the building's type (#2281)");
        EditWalk.toTune(edit);
        BuilderSpec again = edit.spec(Map.of());
        assertEquals("Barny", again.name(), "an edit keeps a name that is not Moddy's");
        assertEquals(BuilderLowering.lower(spec, "mine_1", LibraryPresets.SHIPPED),
                BuilderLowering.lower(again, "mine_1", LibraryPresets.SHIPPED), "an unchanged edit makes the same building");
    }
}
