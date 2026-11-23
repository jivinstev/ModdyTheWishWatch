package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moddingfromamod.agent.tools.ApplyEffectTool;
import com.moddingfromamod.datapack.ItemCatalogue;

/**
 * the Builder's limits are the TOOLS' limits. Each conflict row is held to the tool behaviour it stands for,
 * BOTH WAYS: every pair it names really collides in the tool, and every pair it lets through really does not.
 */
class BuilderLimitsTest {

    @Test
    @DisplayName("two moves clash exactly when apply_effect would keep them in one pack (the second replacing the first)")
    void movesClashExactlyWhenTheyShareAPack() {
        List<String> wrong = new ArrayList<>();
        int clashes = 0;
        for (BuilderAbilities.Ability a : BuilderAbilities.ALL) {
            for (BuilderAbilities.Ability b : BuilderAbilities.ALL) {
                if (a == b) {
                    continue;
                }
                boolean onePack = ApplyEffectTool.featureId(a.effect(), "cat_1").equals(ApplyEffectTool.featureId(b.effect(), "cat_1"));
                boolean clash = BuilderLimits.whyNot("abilities", List.of(a.id()), b.id()) != null;
                if (onePack != clash) {
                    wrong.add(a.id() + " + " + b.id() + ": one pack " + onePack + ", the Builder says clash " + clash);
                }
                clashes += clash ? 1 : 0;
            }
        }
        assertEquals(List.of(), wrong);
        assertTrue(clashes > 0, "the table has rows to hold");
        // And the moves that do not clash all go on ONE creature: one of each effect.
        List<String> oneOfEach = new ArrayList<>();
        for (BuilderAbilities.Ability a : BuilderAbilities.ALL) {
            if (BuilderLimits.whyNot("abilities", oneOfEach, a.id()) == null) {
                oneOfEach.add(a.id());
            }
        }
        assertNull(BuilderLimits.problem("abilities", oneOfEach));
        assertEquals(BuilderAbilities.ALL.stream().map(BuilderAbilities.Ability::effect).distinct().count(), oneOfEach.size());
        assertEquals("Can't with Breathes fire", BuilderLimits.whyNot("abilities", List.of("fire_breath"), "frost_breath"));
    }

    @Test
    @DisplayName("two snack powers clash exactly when make_item refuses them together; three go, four do not")
    void snackPowersClashExactlyWhenMakeItemRefusesThem() {
        ItemCatalogue cat = ItemCatalogue.load();
        List<BuilderItemPowers.Power> powers = BuilderSnacks.powers().stream().filter(w -> w != BuilderSnacks.YUMMY).toList();
        List<String> wrong = new ArrayList<>();
        for (BuilderItemPowers.Power a : powers) {
            for (BuilderItemPowers.Power b : powers) {
                if (a == b) {
                    continue;
                }
                boolean refused = refuses(cat, List.of(a, b));
                boolean clash = BuilderLimits.whyNot("snack_powers", List.of(a.id()), b.id()) != null;
                if (refused != clash) {
                    wrong.add(a.id() + " + " + b.id() + ": make_item refuses " + refused + ", the Builder says clash " + clash);
                }
            }
        }
        assertEquals(List.of(), wrong);
        // make_item's count, not a typed one: as many as it takes go, one more does not.
        List<BuilderItemPowers.Power> fit = new ArrayList<>();
        for (BuilderItemPowers.Power w : powers) {
            if (BuilderLimits.whyNot("snack_powers", fit.stream().map(BuilderItemPowers.Power::id).toList(), w.id()) == null) {
                fit.add(w);
            }
        }
        assertEquals(ItemCatalogue.MAX_EFFECTS, fit.size());
        assertTrue(!refuses(cat, fit), "the most the Builder allows, make_item makes");
        BuilderItemPowers.Power more = powers.stream().filter(w -> fit.stream().noneMatch(x -> x.effect().equals(w.effect())))
                .findFirst().orElseThrow();
        List<BuilderItemPowers.Power> over = new ArrayList<>(fit);
        over.add(more);
        assertTrue(refuses(cat, over), "one more, make_item refuses");
        assertEquals("One snack does 3 things at most",
                BuilderLimits.whyNot("snack_powers", fit.stream().map(BuilderItemPowers.Power::id).toList(), more.id()));
    }

    @Test
    @DisplayName("\"just yummy\" rides alone: with anything, the lowering refuses; alone on a plain food, it makes")
    void justYummyRidesAlone() {
        for (BuilderItemPowers.Power w : BuilderSnacks.powers()) {
            if (w == BuilderSnacks.YUMMY) {
                continue;
            }
            assertTrue(BuilderLimits.whyNot("snack_powers", List.of("yummy"), w.id()) != null, w.id());
            assertThrows(IllegalArgumentException.class, () -> BuilderSnacks.calls(BuilderSpec.read(com.google.gson.JsonParser.parseString(
                    "{\"builder\":1,\"kind\":\"machine\",\"opts\":" + new com.google.gson.Gson().toJson(
                            BuilderSnacks.opts("cookie", List.of("yummy", w.id()), 2, "minecraft:wheat", 3, BuilderSnacks.POUR)) + "}")
                    .getAsJsonObject())), "yummy + " + w.id());
        }
        assertDoesNotThrow(() -> BuilderSnacks.calls(BuilderSpec.read(com.google.gson.JsonParser.parseString(
                "{\"builder\":1,\"kind\":\"machine\",\"opts\":" + new com.google.gson.Gson().toJson(
                        BuilderSnacks.opts("cookie", List.of("yummy"), 2, "minecraft:wheat", 3, BuilderSnacks.POUR)) + "}")
                .getAsJsonObject())));
    }

    @Test
    @DisplayName("parts: the body's room is the limit — the room line and the refusal are the sculpt tool's own budget")
    void partsAreLimitedByTheBodysRoomOnly() {
        com.google.gson.JsonArray cat = LibraryPresets.SHIPPED.read("species/cat").getAsJsonArray("parts");
        int room = BuilderParts.budget(cat.size());
        List<String> fit = new ArrayList<>();
        for (BuilderParts.Part p : BuilderParts.ALL) {
            List<String> would = new ArrayList<>(fit);
            would.add(p.id());
            if (BuilderLimits.parts(BuilderParts.boxes(would, cat, ""), room, "cat") == null) {
                fit.add(p.id());
            }
        }
        assertTrue(fit.size() > 3, "more than the old typed three fit a cat: " + fit);
        assertEquals("Too big for this cat", BuilderLimits.parts(room + 1, room, "Cat"));
        assertEquals("Body room 7/12 boxes", BuilderLimits.room(7, 12));
    }

    /** Does make_item refuse these snack powers as one on_consume item? (render is the tool's own check). */
    private static boolean refuses(ItemCatalogue cat, List<BuilderItemPowers.Power> ws) {
        List<String> effects = new ArrayList<>();
        Map<String, Map<String, String>> args = new LinkedHashMap<>();
        for (BuilderItemPowers.Power w : ws) {
            effects.add(w.effect());
            args.putIfAbsent(w.effect(), w.params(2));
        }
        try {
            // what make_item fills into each effect itself, then its render: the tool's own rules, both
            Map<String, Map<String, String>> filled = new LinkedHashMap<>();
            args.forEach((e, a) -> filled.put(e, com.moddingfromamod.agent.tools.MakeItemTool.filledOffServer(cat.get(e), "on_consume", a)));
            args = filled;
            // the item-wide arguments make_item writes for any item (its give line, colour, message): not composition
            cat.render(effects, "on_consume", args, Map.of("GIVES", "give @s minecraft:apple", "GIVE_MESSAGE", "You got it",
                    "HEX", "#FFFFFF", "AIM", ""));
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
