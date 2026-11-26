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
        for (double h : new double[] {10, 25, 70}) {
            out.add(Map.of(MakeFacts.HEALTH_HALF_HEARTS, h));
        }
        out.add(Map.of(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS, 3.0));
        out.add(Map.of(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS, 6.0));
        out.add(Map.of(MakeFacts.HEALTH_HALF_HEARTS, 40.0, MakeFacts.ATTACK_DAMAGE_HALF_HEARTS, 6.0));
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
        List<BuilderSpec> leaves = new ArrayList<>(BuilderPageFitTest.leaves());
        assertTrue(leaves.size() > 100, "the walk reached only " + leaves.size() + " makes");
        // the two #14 makes: the strongest sword, and a giant tough creature
        leaves.add(sword(3));
        leaves.add(toughest());
        MakeFacts.itemAttack(MADE_UP);   // a sword's attack damage, as the game would read it
        List<String> bad = new ArrayList<>();
        int fitted = 0;
        int live = 0;
        try {
        for (BuilderSpec s : leaves) {
            for (Map<String, Double> l : spread()) {
                MakeFit.Fitted f = MakeFit.fit(s, l);
                if (!f.fits()) {
                    continue;
                }
                MakeFacts after = MakeFacts.of(f.spec());
                MakeFacts before = MakeFacts.of(s);
                if (!MakeFit.satisfiesHere(after, l)) {
                    bad.add(s.toJson() + " under " + l + " → " + after);
                }
                for (String k : MakeFacts.NUMBERS) {
                    if (before.number(k).isPresent() && after.number(k).orElse(0) > before.number(k).getAsDouble() + 1e-9) {
                        bad.add("a fit raised " + k + ": " + s.toJson() + " → " + f.spec().toJson());
                    }
                }
                // what is left over a limit is only ever a creature's health or damage, put on the live creature
                if (!MakeFit.satisfies(after, l)) {
                    live++;
                    if (!MakeFit.livesFitted(after) || !MakeFit.LIVE.containsAll(MakeFit.over(after, l))
                            || !f.lowered().containsAll(MakeFit.over(after, l))) {
                        bad.add("left over a limit, not fitted live: " + s.toJson() + " under " + l + " → " + after);
                    }
                }
                if (f.changed()) {
                    fitted++;
                    if (f.spec().size() > s.size() || f.spec().count() > s.count()
                            || strength(f.spec()) > strength(s)
                            || f.spec().kind() != s.kind() || !f.spec().from().equals(s.from())) {
                        bad.add("a fit made more or something else: " + s.toJson() + " → " + f.spec().toJson());
                    }
                } else if (!MakeFit.satisfiesHere(MakeFacts.of(s), l)) {
                    bad.add("said it fits unchanged but does not: " + s.toJson() + " under " + l);
                }
            }
        }
        } finally {
            MakeFacts.itemAttack(null);
        }
        assertTrue(bad.isEmpty(), bad.size() + " failures, e.g.\n" + String.join("\n", bad.subList(0, Math.min(10, bad.size()))));
        assertTrue(fitted > 100, "only " + fitted + " fits changed anything: the spread does not reach the knobs");
        assertTrue(live > 0, "no make was fitted live: the spread does not reach a tough creature");
    }

    /** Its strength step: a creature's or ride's unstated one is its own (#103, BuilderCreatureStrength.OWN). */
    private static int strength(BuilderSpec s) {
        boolean creature = s.kind() == BuilderSpec.Kind.CREATURE || s.kind() == BuilderSpec.Kind.RIDE;
        return creature && s.opt("strength").isEmpty() ? BuilderCreatureStrength.OWN : num(s.opt("strength"));
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
        BuilderSpec cat = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "preset:species/cat", "", 4,
                List.of(), "friendly", 6, "", "", Map.of());
        // a limit on a number a make does not carry does nothing (a cat's spec carries no reach)
        assertTrue(MakeFit.fit(cat, Map.of(MakeFacts.RADIUS_BLOCKS, 0.0)).fits());
        assertFalse(MakeFit.fit(cat, Map.of(MakeFacts.RADIUS_BLOCKS, 0.0)).changed());
        // a limit lower than the knob's lowest value cannot be fitted
        assertFalse(MakeFit.fit(cat, Map.of(MakeFacts.COUNT_MADE, 0.5)).fits());
        MakeFit.Fitted three = MakeFit.fit(cat, Map.of(MakeFacts.COUNT_MADE, 3.0));
        assertTrue(three.fits() && three.changed() && three.spec().count() == 3, three.toString());
        assertEquals(List.of(MakeFacts.COUNT_MADE), three.lowered());
        for (var e : MakeFit.FACTS.values()) {
            // #102: what a creature's moves do a second is read, not lowered
            assertEquals(e.clampable(), !List.of(MakeFacts.MOVEMENT_SPEED_ATTRIBUTE, MakeFacts.EXTENT_BLOCKS,
                    MakeFacts.MOVE_DAMAGE_HALF_HEARTS_PER_SECOND, MakeFacts.EFFECTIVE_DAMAGE_HALF_HEARTS_PER_SECOND,
                    MakeFacts.HEAL_HALF_HEARTS_PER_SECOND).contains(e.key()), e.key());
        }
    }

    @Test
    void healthAndDamageAreSearchedThenFittedLive() {
        BuilderSpec cat = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "preset:species/cat", "", 4,
                List.of(), "friendly", 1, "", "", Map.of());
        double health = MakeFacts.of(cat).number(MakeFacts.HEALTH_HALF_HEARTS).orElse(-1);
        assertTrue(health > 0, "a Builder creature carries its ready-made's health: " + MakeFacts.of(cat));
        // within the limit: made as it is
        MakeFit.Fitted within = MakeFit.fit(cat, Map.of(MakeFacts.HEALTH_HALF_HEARTS, health));
        assertTrue(within.fits() && !within.changed());
        // over it: a creature's health and damage are fitted on the LIVE creature, never by making it smaller or
        // gentler (#103: its size and strength move them now, but a world that raises its limits later must get it
        // back at full strength); the make itself is not changed
        MakeFit.Fitted over = MakeFit.fit(cat, Map.of(MakeFacts.HEALTH_HALF_HEARTS, health - 1));
        assertTrue(over.fits() && over.spec().equals(cat) && over.lowered().equals(List.of(MakeFacts.HEALTH_HALF_HEARTS)),
                over.toString());
        // a limit on its strength is its own knob: that one makes it gentler
        MakeFit.Fitted gentle = MakeFit.fit(cat, Map.of(MakeFacts.BUILDER_STRENGTH_STEP, 1.0));
        assertTrue(gentle.fits() && gentle.changed() && gentle.spec().opt("strength").equals("1"), gentle.toString());
        BuilderSpec sword = sword(3);
        // a creature offers no steps for health and damage; an item's search tries every lower combination
        assertTrue(MakeFit.steps(cat).isEmpty(), "a creature's health and damage are fitted live");
        List<BuilderSpec> tried = MakeFit.steps(sword);
        assertTrue(tried.stream().noneMatch(s -> s.equals(sword)), "the make as it is is no candidate");
        BuilderSpec imp = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "preset:species/fire_imp", "", 2,
                List.of(), "friendly", 1, "", "", Map.of());
        double damage = MakeFacts.of(imp).number(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS).orElse(-1);
        assertTrue(damage > 0, MakeFacts.of(imp).toString());
        assertEquals(List.of(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS),
                MakeFit.fit(imp, Map.of(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS, damage - 1)).lowered());
    }

    /**
     * A stand-in for the game's own item numbers (ItemStats, read at run time on a server): made-up values, so nothing
     * of the game's is copied here. A diamond sword hits for 9, a stone one for 6.
     */
    private static final java.util.function.Function<String, java.util.OptionalDouble> MADE_UP = id -> switch (id) {
        case "minecraft:diamond_sword" -> java.util.OptionalDouble.of(9);
        case "minecraft:stone_sword" -> java.util.OptionalDouble.of(6);
        default -> java.util.OptionalDouble.empty();
    };

    private static BuilderSpec sword(int strength) {
        Map<String, String> o = new LinkedHashMap<>();
        o.put("stock", "sword");
        o.put("shape", "shape:on_hit");
        o.put("effect", "effect:lightning");
        o.put("power", "lightning");
        o.put("strength", String.valueOf(strength));
        return new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.ITEM, "", "", 2, List.of(), "", 1, "", "", o);
    }

    @Test
    void theStrongestSwordEverGivesTheStrongestSwordTheWorldAllows() {
        MakeFacts.itemAttack(MADE_UP);
        try {
            BuilderSpec strongest = sword(3);
            assertEquals(9.0, MakeFacts.of(strongest).number(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS).orElse(-1));
            // a world that allows 7: the strongest sword it allows is the stone one, at full strength
            MakeFit.Fitted f = MakeFit.fit(strongest, Map.of(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS, 7.0));
            assertTrue(f.fits() && f.changed(), f.toString());
            assertEquals(BuilderItemPowers.TIER_BASIC, f.spec().opt("tier"));
            assertEquals("3", f.spec().opt("strength"), "the most strength kept");
            assertEquals(6.0, MakeFacts.of(f.spec()).number(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS).orElse(-1));
            // a world that allows less than any sword: no sword is fitted wrong, it is locked
            assertFalse(MakeFit.fit(strongest, Map.of(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS, 5.0)).fits());
            // the property, under damage limits too: derive(fit(spec, L)) keeps within L
            for (BuilderSpec s : List.of(sword(1), sword(2), sword(3))) {
                for (double l : new double[] {5, 6, 7, 9, 12}) {
                    MakeFit.Fitted g = MakeFit.fit(s, Map.of(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS, l));
                    if (g.fits()) {
                        assertTrue(MakeFit.satisfies(MakeFacts.of(g.spec()), Map.of(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS, l)),
                                s.toJson() + " under " + l + " → " + g.spec().toJson());
                    }
                }
            }
        } finally {
            MakeFacts.itemAttack(null);
        }
    }

    /** A giant long-neck dinosaur: a ready-made body with 120 health, at the biggest size. */
    static BuilderSpec toughest() {
        return new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "preset:bodies/long_neck_dinosaur", "",
                4, List.of(), "friendly", 1, "", "", Map.of());
    }

    @Test
    void aGiantToughCreatureIsMadeGiantAndFittedOnTheLiveEntity() {
        BuilderSpec dragon = toughest();
        double health = MakeFacts.of(dragon).number(MakeFacts.HEALTH_HALF_HEARTS).orElse(-1);
        assertTrue(health > 40, MakeFacts.of(dragon).toString());
        MakeFit.Fitted f = MakeFit.fit(dragon, Map.of(MakeFacts.HEALTH_HALF_HEARTS, 40.0));
        // no size or strength lowers its health, so it is made as it is (giant) and fitted on the live creature
        assertTrue(f.fits(), f.toString());
        assertEquals(dragon, f.spec(), "the make itself is not changed: it is made at its own size");
        assertEquals(List.of(MakeFacts.HEALTH_HALF_HEARTS), f.lowered());
        assertTrue(MakeFit.satisfiesHere(MakeFacts.of(f.spec()), Map.of(MakeFacts.HEALTH_HALF_HEARTS, 40.0)));
        // an item is never fitted live: only a creature is
        assertFalse(MakeFit.satisfiesHere(new MakeFacts("item", Map.of(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS, 9.0),
                java.util.Set.of(), true), Map.of(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS, 7.0)));
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
        // its health is its own and is never changed in the store: the creature is fitted on the live entity at spawn,
        // so the command is kept as it is
        assertEquals(List.of("wishmob spawn d"),
                MakeFit.fitCommands(List.of("wishmob spawn d"), Map.of(MakeFacts.HEALTH_HALF_HEARTS, 40.0), store));
        Map<String, Double> l = new LinkedHashMap<>();
        l.put(MakeFacts.COUNT_MADE, 0.0);
        assertNull(MakeFit.fitCommands(List.of("wishmob spawn d 2"), l, store));
        // a creature over a SIZE limit is made too, its command as it is: its size is fitted where it stands
        // (LiveFit.drawnScale), never refused for being big, never written into its record
        JsonObject giant = JsonParser.parseString("{\"id\":\"g\",\"name\":\"G\",\"powers\":[\"friendly\"],\"scale\":6}")
                .getAsJsonObject();
        MakeFacts.Artifacts giants = (k, id) -> id.equals("g") ? giant : null;
        assertEquals(List.of("wishmob spawn g"),
                MakeFit.fitCommands(List.of("wishmob spawn g"), Map.of(MakeFacts.SCALE_TIMES, 2.0), giants));
        assertEquals(6.0, giant.get("scale").getAsDouble(), 1e-9, "its record is untouched");
    }

    @Test
    void howManyARecipeMakesIsFittedOverTheWholeRecipe() {
        // #174 B7: a recipe repeated or with several spawns made the most any ONE line made, so twenty summons met a
        // limit of three
        Map<String, Double> three = Map.of(MakeFacts.COUNT_MADE, 3.0);
        MakeFacts.Artifacts none = (k, id) -> null;
        List<String> wolves = java.util.Collections.nCopies(5, "summon minecraft:wolf ~ ~ ~");
        assertEquals(3, MakeFacts.ofCommands(List.of("summon minecraft:wolf ~ ~ ~", "summon minecraft:wolf ~ ~ ~",
                "summon minecraft:wolf ~ ~ ~"), none).number(MakeFacts.COUNT_MADE).orElse(0), 1e-9);
        assertEquals(java.util.Collections.nCopies(3, "summon minecraft:wolf ~ ~ ~"),
                MakeFit.fitCommands(wolves, three, none), "the summons past the most at once are not run");
        List<String> mixed = List.of("say here they come", "wishsummon minecraft:wolf {\"count\":2,\"damage\":100}",
                "wishsummon minecraft:wolf {\"count\":4}", "summon minecraft:cat ~ ~ ~", "say done");
        List<String> fitted = MakeFit.fitCommands(mixed, three, none);
        assertEquals(4, fitted.size(), "" + fitted);
        assertEquals("say here they come", fitted.get(0));
        assertEquals("wishsummon minecraft:wolf {\"count\":2,\"damage\":100}", fitted.get(1),
                "its damage is held on the live wolf, never rewritten");
        assertTrue(fitted.get(2).startsWith("wishsummon minecraft:wolf {") && fitted.get(2).contains("\"count\":1"),
                "a spawn past what is left is lowered to it: " + fitted.get(2));
        assertEquals("say done", fitted.get(3), "the cat past the most is not made; what makes nothing is kept");
        Map<String, Double> zero = new LinkedHashMap<>();
        zero.put(MakeFacts.COUNT_MADE, 0.0);
        assertNull(MakeFit.fitCommands(wolves, zero, none), "a world that allows none refuses it");
    }

    @Test
    void aCreatureOverOnlyItsSizeIsMadeHereAndAnItemIsNot() {
        MakeFacts dragon = new MakeFacts(BuilderSpec.Kind.CREATURE.id(),
                Map.of(MakeFacts.SCALE_TIMES, 4.0, MakeFacts.BUILDER_SIZE_STEP, 4.0, MakeFacts.HEALTH_HALF_HEARTS, 300.0),
                java.util.Set.of(), true);
        Map<String, Double> low = Map.of(MakeFacts.SCALE_TIMES, 2.0, MakeFacts.BUILDER_SIZE_STEP, 2.0,
                MakeFacts.HEALTH_HALF_HEARTS, 40.0);
        assertTrue(MakeFit.satisfiesHere(dragon, low), "size and health are both fitted where it stands");
        assertFalse(MakeFit.satisfies(dragon, low), "though it is over them as asked");
        MakeFacts tooMany = new MakeFacts(BuilderSpec.Kind.CREATURE.id(), Map.of(MakeFacts.COUNT_MADE, 9.0),
                java.util.Set.of(), true);
        assertFalse(MakeFit.satisfiesHere(tooMany, Map.of(MakeFacts.COUNT_MADE, 3.0)), "how many is not fitted where it stands");
        MakeFacts bigBuilding = new MakeFacts(BuilderSpec.Kind.BUILDING.id(), Map.of(MakeFacts.BUILDER_SIZE_STEP, 4.0),
                java.util.Set.of(), true);
        assertFalse(MakeFit.satisfiesHere(bigBuilding, Map.of(MakeFacts.BUILDER_SIZE_STEP, 2.0)),
                "a building is not a creature: its size is its make's");
    }
}
