package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * <b>A fitted make keeps within the limits it was fitted to</b> (#14): derive(clamp(spec, L)) satisfies L, for every
 * make the menus can reach ({@link BuilderPageFitTest#leaves}) and a spread of limits; a fit only ever lowers a knob;
 * a number no knob lowers is never "fitted" by a wrong clamp.
 */
class MakeFitTest {

    /** A spread of limits over every clampable number. */
    static List<Map<String, Double>> spread() {
        List<Map<String, Double>> out = new ArrayList<>();
        for (double size : new double[] {1, 2, 3}) {
            out.add(Map.of(MakeFacts.BUILDER_SIZE_STEP, size));
        }
        for (double scale : new double[] {0.5, 1.0, 1.5}) {
            out.add(Map.of(MakeFacts.SCALE_TIMES, scale));
        }
        for (double n : new double[] {1, 3}) {
            out.add(Map.of(MakeFacts.COUNT_MADE, n));
        }
        out.add(Map.of(MakeFacts.BUILDER_STRENGTH_STEP, 1.0));
        out.add(Map.of(MakeFacts.DURATION_TICKS, 100.0));
        out.add(Map.of(MakeFacts.RADIUS_BLOCKS, 0.0));
        out.add(Map.of(MakeFacts.AMOUNT_ITEMS, 8.0));
        out.add(Map.of(MakeFacts.BUILDER_SIZE_STEP, 2.0, MakeFacts.COUNT_MADE, 2.0, MakeFacts.BUILDER_STRENGTH_STEP, 2.0,
                MakeFacts.AMOUNT_ITEMS, 16.0, "notAFact", 0.0));
        return out;
    }

    @Test
    void everyNumberIsDeclaredWithItsClamp() {
        assertEquals(MakeFacts.NUMBERS, new ArrayList<>(MakeFit.FACTS.keySet()),
                "every MakeFacts number has its row in MakeFit.FACTS, in the same order: clampable or not");
    }

    @Test
    void aFittedMakeSatisfiesItsLimitsAndOnlyEverGetsLess() {
        List<BuilderSpec> leaves = BuilderPageFitTest.leaves();
        assertTrue(leaves.size() > 100, "the walk reached only " + leaves.size() + " makes");
        List<String> bad = new ArrayList<>();
        int fitted = 0;
        for (BuilderSpec s : leaves) {
            for (Map<String, Double> l : spread()) {
                MakeFit.Fitted f = MakeFit.fit(s, l);
                if (!f.fits()) {
                    continue;
                }
                MakeFacts after = MakeFacts.of(f.spec());
                if (!MakeFit.satisfies(after, l)) {
                    bad.add(s.toJson() + " under " + l + " → " + after);
                }
                if (f.changed()) {
                    fitted++;
                    if (f.spec().size() > s.size() || f.spec().count() > s.count()
                            || num(f.spec().opt("strength")) > num(s.opt("strength"))
                            || f.spec().kind() != s.kind() || !f.spec().from().equals(s.from())) {
                        bad.add("a fit made more or something else: " + s.toJson() + " → " + f.spec().toJson());
                    }
                } else if (!MakeFit.satisfies(MakeFacts.of(s), l)) {
                    bad.add("said it fits unchanged but does not: " + s.toJson() + " under " + l);
                }
            }
        }
        assertTrue(bad.isEmpty(), bad.size() + " failures, e.g.\n" + String.join("\n", bad.subList(0, Math.min(10, bad.size()))));
        assertTrue(fitted > 100, "only " + fitted + " fits changed anything: the spread does not reach the knobs");
    }

    private static int num(String v) {
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Test
    void aNumberNoKnobLowersIsNeverFitted() {
        // a spec carries no health: a limit on it does nothing
        BuilderSpec cat = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "preset:species/cat", "", 4,
                List.of(), "friendly", 6, "", "", Map.of());
        assertTrue(MakeFit.fit(cat, Map.of(MakeFacts.HEALTH_HALF_HEARTS, 1.0)).fits());
        assertFalse(MakeFit.fit(cat, Map.of(MakeFacts.HEALTH_HALF_HEARTS, 1.0)).changed());
        // a limit lower than the knob's lowest value cannot be fitted
        assertFalse(MakeFit.fit(cat, Map.of(MakeFacts.COUNT_MADE, 0.5)).fits());
        MakeFit.Fitted three = MakeFit.fit(cat, Map.of(MakeFacts.COUNT_MADE, 3.0));
        assertTrue(three.fits() && three.changed() && three.spec().count() == 3, three.toString());
        assertEquals(List.of(MakeFacts.COUNT_MADE), three.lowered());
        for (var e : MakeFit.FACTS.values()) {
            assertEquals(e.clampable(), !List.of(MakeFacts.HEALTH_HALF_HEARTS, MakeFacts.ATTACK_DAMAGE_HALF_HEARTS,
                    MakeFacts.MOVEMENT_SPEED_ATTRIBUTE, MakeFacts.EXTENT_BLOCKS).contains(e.key()), e.key());
        }
    }

    @Test
    void theOneFittingPathIsTheAllowlistsCap() {
        BuilderSpec cat = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "preset:species/cat", "", 2,
                List.of(), "friendly", 6, "", "", Map.of());
        assertEquals(MakeFit.fit(BuilderAllowlist.REVIEWED.capped(cat), Map.of(MakeFacts.COUNT_MADE, 2.0)),
                BuilderAllowlist.REVIEWED.capped(cat, Map.of(MakeFacts.COUNT_MADE, 2.0)));
        assertEquals(BuilderAllowlist.REVIEWED.capped(cat), BuilderAllowlist.REVIEWED.capped(cat, Map.of()).spec());
    }

    @Test
    void aRecipeIsFittedByHowManyItSpawnsAndNothingElse() {
        JsonObject d = JsonParser.parseString("{\"id\":\"d\",\"name\":\"D\",\"powers\":[\"friendly\"],\"health\":200}")
                .getAsJsonObject();
        MakeFacts.Artifacts store = (k, id) -> id.equals("d") ? d : null;
        assertEquals(List.of("wishmob spawn d 3 at castle:door"),
                MakeFit.fitCommands(List.of("wishmob spawn d 9 at castle:door"), Map.of(MakeFacts.COUNT_MADE, 3.0), store));
        assertEquals(List.of("wishmob spawn d 2"),
                MakeFit.fitCommands(List.of("wishmob spawn d 2"), Map.of(MakeFacts.COUNT_MADE, 3.0), store));
        // its health is its own: a limit on it cannot be fitted here
        assertNull(MakeFit.fitCommands(List.of("wishmob spawn d"), Map.of(MakeFacts.HEALTH_HALF_HEARTS, 40.0), store));
        Map<String, Double> l = new LinkedHashMap<>();
        l.put(MakeFacts.COUNT_MADE, 0.0);
        assertNull(MakeFit.fitCommands(List.of("wishmob spawn d 2"), l, store));
    }
}
