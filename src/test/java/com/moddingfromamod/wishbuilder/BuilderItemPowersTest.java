package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.moddingfromamod.datapack.ItemCatalogue;
import com.moddingfromamod.datapack.ItemTriggers;
import org.junit.jupiter.api.Test;

/** every made-up item the Builder offers is a real catalogue pair with every parameter it needs. */
class BuilderItemPowersTest {

    @Test
    void everyPairIsARealCatalogueEffectAndShapeWithExactlyItsParameters() {
        List<String> wrong = new ArrayList<>();
        for (BuilderItemPowers.Power w : BuilderItemPowers.POWERS) {
            if (w.effect().isEmpty()) {
                continue;   //: "Just for fun" is make_item's plain item: no effect, on purpose
            }
            ItemCatalogue.Entry e = ItemCatalogue.load().get(w.effect());
            if (e == null) {
                wrong.add(w.id() + ": no catalogue effect '" + w.effect() + "'");
                continue;
            }
            for (String shape : w.shapes()) {
                if (!ItemCatalogue.EFFECT_SHAPES.contains(shape)) {
                    wrong.add(w.id() + ": no shape '" + shape + "'");
                }
            }
            for (int strength = 1; strength <= 3; strength++) {
                // The catalogue has no defaults: every parameter it asks for, and nothing it doesn't.
                // "Turn things into…" takes FROM and TO from its own pages (BuilderTurnInto.params)
                Set<String> given = new TreeSet<>(w.id().equals(BuilderTurnInto.POWER)
                        ? BuilderTurnInto.params("held", List.of(BuilderTurnInto.from("sand")), BuilderTurnInto.to("glass"), strength).keySet()
                        : w.params(strength).keySet());
                Set<String> wanted = new TreeSet<>(e.params());
                if (!given.equals(wanted)) {
                    wrong.add(w.id() + " at " + strength + ": gives " + given + ", the effect asks " + wanted);
                }
            }
            if (BuilderItemPowers.STOCK.stream().noneMatch(w::fits)) {
                wrong.add(w.id() + ": fits no stock item");
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void fifteenToTwentyStockItemsEachOnAnItemThatReallyHasItsTrigger() {
        int n = BuilderItemPowers.STOCK.size();
        assertTrue(n >= 15 && n <= 20, "the owner asked for 15–20 stock items, not " + n);
        List<String> wrong = new ArrayList<>();
        Set<String> ids = new TreeSet<>();
        for (BuilderItemPowers.Stock s : BuilderItemPowers.STOCK) {
            if (!ids.add(s.id())) {
                wrong.add(s.id() + ": twice");
            }
            if (!ItemCatalogue.EFFECT_SHAPES.contains(s.shape())) {
                wrong.add(s.id() + ": no trigger '" + s.shape() + "'");
            }
            // A wish rides WITH the item's own action, never instead of it (ItemTriggers, docs/ITEM_TRIGGERS_PLAN.md §3).
            var verdict = ItemTriggers.check(s.base().isEmpty() ? null : s.base(), s.shape());
            if (!verdict.ok()) {
                wrong.add(s.id() + ": " + verdict.reason());
            }
            if (!ItemCatalogue.ICONS.containsKey(s.icon())) {
                wrong.add(s.id() + ": no icon '" + s.icon() + "'");
            }
            if (BuilderItemPowers.fitting(s).isEmpty()) {
                wrong.add(s.id() + ": no power fits it");
            }
            if (s.words().size() < BuilderTree.MIN_WORDS) {
                wrong.add(s.id() + ": too few words");
            }
            for (BuilderItemPowers.Power w : BuilderItemPowers.fitting(s)) {
                for (int strength = 1; strength <= 3; strength++) {
                    assertNotNull(args(s.id(), w.id(), strength), s.id() + " + " + w.id());
                }
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void theOwnersFamiliesAreAllThere() {
        // weapons, tools including a pickaxe, wearables, consumables.
        for (String id : new String[] {"sword", "axe", "mace", "bow", "crossbow", "trident", "pickaxe", "shovel", "wand",
                "staff", "helmet", "chestplate", "boots", "snack", "drink", "snowball"}) {
            assertNotNull(BuilderItemPowers.stock(id), id);
        }
    }

    @Test
    void placeConvertAndChangeABlockAreOnAWandAndLavaOnlyWhereTheWorldAllowsIt() {
        // Place on a wand or a staff only.: the six fixed pairs are one power, "Turn things into…", on
        // anything that aims, hits or is thrown; its From and To are its own pages.
        for (String id : List.of("place_water", "place_lava", "place_dirt", "place_grass", "place_sand")) {
            BuilderItemPowers.Power w = BuilderItemPowers.power(id);
            assertNotNull(w, id);
            assertEquals(List.of("held"), w.shapes(), id + " is a wand's");
        }
        for (String gone : List.of("smelt_iron", "charcoal", "glass", "dirt_to_sand", "cobble_to_gravel", "stone_to_cobble")) {
            assertEquals(null, BuilderItemPowers.power(gone), gone + " is a pick of 'Turn things into…' now");
        }
        BuilderItemPowers.Power turn = BuilderItemPowers.power(BuilderTurnInto.POWER);
        assertEquals(List.of("on_hit", "on_throw_land", "held"), turn.shapes());
        assertEquals("Turn things into…", turn.label());
        assertEquals(BuilderTurnInto.POWER, BuilderItemPowers.POWERS.get(BuilderItemPowers.POWERS.size() - 1).id(),
                "last, so it is never the power an item with no power named gets");
        assertEquals("minecraft:water", BuilderItemPowers.power("place_water").params(2).get("BLOCK"));
        assertEquals("minecraft:lava", BuilderItemPowers.power("place_lava").params(2).get("BLOCK"));
        var smelter = BuilderItemPowers.args("wand", BuilderTurnInto.POWER, 2, BuilderItemPowers.TIER_ANY, "ores", "furnace")
                .getAsJsonObject("params");
        assertEquals("furnace", smelter.get("TO").getAsString());
        assertTrue(smelter.get("FROM").getAsString().contains("minecraft:raw_iron"));
        assertEquals(List.of(0, 1, 2), List.of(1, 2, 3).stream().map(s -> BuilderTurnInto.radius("held", s)).toList(),
                "held: strength is the patch, from the one block he points at");
        assertEquals(List.of(1, 2, 3), List.of(1, 2, 3).stream().map(s -> BuilderTurnInto.radius("on_hit", s)).toList(),
                "swung or thrown: never 0, which would change nothing (ChangeBlock.radiusRefusal)");
        assertEquals("Midas Wand", BuilderItemPowers.name("wand", BuilderTurnInto.POWER, "gold"));
        assertEquals("Water Wand", BuilderItemPowers.name("wand", "place_water"));
        // Lava is offered in every world (the knob is gone), the lava wand included. A house world's own manifest
        // is checked the same way beside it (BuilderHouseWorldItemPowersTest).
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        f.press("item");
        f.press(BuilderFlow.STOCK_PREFIX + "wand");
        assertTrue(f.itemPowerTiles().stream().anyMatch(t -> t.id().equals("itempower:place_lava")), "the lava wand everywhere");
        BuilderSpec lavaWand = lavaWand();
        assertTrue(BuilderAllowlist.REVIEWED.problems(lavaWand).isEmpty(), BuilderAllowlist.REVIEWED.problems(lavaWand).toString());
        // The knob is gone, in both its spellings: an old key is refused by name, never read as nothing.
        for (String old : List.of("\"supplies\":{\"lava\":true}", "\"lava\":false")) {
            assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> BuilderSettings.parse(
                    "{\"crafters\":{\"builder\":{\"enabled\":true," + old + "}}}")).getMessage().contains("lava is offered in every world"));
        }
    }

    static BuilderSpec lavaWand() {
        return BuilderSpec.read(com.google.gson.JsonParser.parseString("{\"builder\":1,\"kind\":\"item\",\"from\":\"\","
                + "\"opts\":{\"stock\":\"wand\",\"shape\":\"shape:held\",\"effect\":\"effect:place\",\"power\":\"place_lava\","
                + "\"strength\":\"2\"}}").getAsJsonObject());
    }

    @Test
    void onlyAMeleeWeaponOrOneThatSparesPlayersCarriesAPowerThatLandsOnSomethingElse() {
        // WISH_BUILDER_PLAN §6.2: no power lands on another player. A melee weapon fires on a hit that lands (PvP is
        // off on a house world); a bow, trident or snowball lands near where it falls, so it carries spare_players.
        // A wand, a tool, armour and food carry only powers that land on him or on the block he points at.
        for (BuilderItemPowers.Stock s : BuilderItemPowers.STOCK) {
            for (BuilderItemPowers.Power w : BuilderItemPowers.fitting(s)) {
                if (w.effect().isEmpty()) {
                    continue;   //: no power lands anywhere
                }
                String applies = ItemCatalogue.load().get(w.effect()).appliesTo();
                boolean lands = "target".equals(applies) || w.effect().equals("lightning");
                if (!lands) {
                    continue;
                }
                var args = args(s.id(), w.id(), 2);
                boolean spared = args.has("spare_players") && args.get("spare_players").getAsBoolean();
                assertTrue(s.shape().equals("on_hit") || spared,
                        w.id() + " on a " + s.id() + " lands on what is near and must spare players");
            }
        }
    }

    /** A made-up item's args; "Turn things into…" with any block into gold, which every item that carries it can make. */
    static com.google.gson.JsonObject args(String stock, String power, int strength) {
        return power.equals(BuilderTurnInto.POWER)
                ? BuilderItemPowers.args(stock, power, strength, BuilderItemPowers.TIER_ANY, "any_block", "gold")
                : BuilderItemPowers.args(stock, power, strength);
    }

    @Test
    void namesReadAsAThing() {
        assertEquals("Lightning Sword", BuilderItemPowers.name("sword", "lightning"));
        assertEquals("Speedy Boots", BuilderItemPowers.name("boots", "speed"));
        assertEquals("Tiny Snack", BuilderItemPowers.name("snack", "tiny"));
        assertEquals("Frost Bow", BuilderItemPowers.name("bow", "freeze"));
    }

    @Test
    void aRealItemIsBuiltOnItselfSoEveryClientDrawsItsOwnPicture() {
        // the horse armour on Bedrock was the CARRIER. Only the wand and armour pieces ride it.
        for (BuilderItemPowers.Stock s : BuilderItemPowers.STOCK) {
            boolean carrier = s.base().isEmpty();
            assertEquals(s.id().equals("wand") || s.shape().startsWith("worn_"), carrier, s.id());
        }
    }
}
