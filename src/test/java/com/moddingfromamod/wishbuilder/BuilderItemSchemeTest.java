package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * <b>A gold pickaxe is the golden pickaxe</b>. An item built on a real tool is drawn as that tool by every
 * client and a colour on it is ignored, so the Gold scheme lowers to the golden piece itself; a scheme that names no
 * material leaves the base alone (it would otherwise be an upgrade), and a base vanilla makes in no tiers is untouched.
 * {@code BuilderItemSchemeGameTest} proves the same through the real tools, in his hand.
 */
class BuilderItemSchemeTest {

    static JsonObject makeItem(String stock, String power, String scheme, String tier) {
        Map<String, String> opts = new java.util.LinkedHashMap<>();
        opts.put("stock", stock);
        opts.put("power", power);
        opts.put("strength", "2");
        if (!tier.isEmpty()) {
            opts.put("tier", tier);
        }
        BuilderSpec spec = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.ITEM, "", scheme, 2, List.of(), "",
                1, "", "", opts);
        List<BuilderLowering.Call> calls = BuilderLowering.lower(spec, "mine_1", LibraryPresets.SHIPPED);
        return calls.stream().filter(c -> c.tool().equals("make_item")).reduce((a, b) -> b).orElseThrow().args();
    }

    static String base(JsonObject a) {
        return a.has("base_item") ? a.get("base_item").getAsString() : null;
    }

    @Test
    void aGoldPickaxeIsBuiltOnTheGoldenPickaxe() {
        assertEquals("minecraft:golden_pickaxe", base(makeItem("pickaxe", "haste", "gold", "")));
        assertEquals("minecraft:diamond_pickaxe", base(makeItem("pickaxe", "haste", "", "")), "no scheme: as listed");
    }

    @Test
    void withTheBasicTierToo() {
        assertEquals("minecraft:stone_pickaxe", base(makeItem("pickaxe", "haste", "", BuilderItemPowers.TIER_BASIC)));
        assertEquals("minecraft:golden_pickaxe", base(makeItem("pickaxe", "haste", "gold", BuilderItemPowers.TIER_BASIC)));
    }

    @Test
    void everyTieredStockItemTakesGold() {
        for (BuilderItemPowers.Stock s : BuilderItemPowers.STOCK) {
            String to = BuilderItemPowers.inScheme(s.base(), "gold");
            if (s.base().matches("minecraft:[a-z]+_(sword|axe|pickaxe|shovel)")) {
                assertEquals("minecraft:golden_" + s.base().replaceAll(".*_", ""), to, s.id());
            } else {
                assertNull(to, s.id() + " (" + s.base() + ") has no golden piece");
            }
        }
    }

    @Test
    void aColourThatIsNoMaterialChangesNoBase() {
        for (Schemes.Scheme sc : Schemes.ALL) {
            if (!sc.id().equals("gold")) {
                assertEquals("minecraft:diamond_pickaxe", base(makeItem("pickaxe", "haste", sc.id(), "")), sc.id());
                assertNull(BuilderItemPowers.inScheme("minecraft:diamond_sword", sc.id()), sc.id());
            }
        }
        assertEquals("minecraft:bow", base(makeItem("bow", "lightning", "gold", "")), "a bow has no golden one");
        assertEquals("minecraft:golden_boots", BuilderItemPowers.inScheme("minecraft:diamond_boots", "scheme:gold"));
    }

    // ──: every other scheme is DRAWN on the real item (its tinted model, the scheme's main colour) ──

    static boolean tinted(JsonObject a) {
        return a.has("tint") && a.get("tint").getAsBoolean();
    }

    @Test
    void everySchemeButGoldTintsTheRealItem() {
        for (Schemes.Scheme sc : Schemes.ALL) {
            JsonObject a = makeItem("pickaxe", "haste", sc.id(), "");
            if (sc.id().equals("gold")) {
                assertFalse(tinted(a), "gold is the golden pickaxe, not a tint");
                continue;
            }
            assertTrue(tinted(a), sc.id());
            assertEquals("minecraft:diamond_pickaxe", base(a), sc.id() + ": still the real pickaxe");
            assertEquals(sc.main(), a.get("colour").getAsString(), sc.id() + ": in the scheme's main colour");
        }
        assertFalse(tinted(makeItem("pickaxe", "haste", "", "")), "no scheme, no tint");
        assertTrue(tinted(makeItem("pickaxe", "haste", "ocean", BuilderItemPowers.TIER_BASIC)),
                "the basic tier's stone pickaxe takes the scheme too");
    }

    @Test
    void onlyRealItemsWithATintedModelAreTinted() {
        assertFalse(tinted(makeItem("trident", "lightning", "ocean", "")),
                "the trident is a special renderer in the hand (TintedItems.notTinted)");
        assertFalse(tinted(makeItem("wand", "teleport", "ocean", "")), "the wand rides the carrier, dyed as before");
        assertFalse(tinted(makeItem("boots", "jump", "ocean", "")), "worn pieces ride the carrier, dyed as before");
        for (String stock : List.of("sword", "axe", "mace", "bow", "crossbow", "snowball", "pickaxe", "shovel",
                "staff", "snack", "drink")) {
            String power = BuilderItemPowers.fitting(BuilderItemPowers.stock(stock)).get(0).id();
            assertTrue(tinted(makeItem(stock, power, "candy", "")), stock);
            assertTrue(BuilderItemPowers.NOT_BASIC.contains(stock)
                    || tinted(makeItem(stock, power, "candy", BuilderItemPowers.TIER_BASIC)), stock + " at the basic tier");
        }
    }
}
