package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** the parts of turn_into that are true without a game — the mode table above all. */
class TurnIntoTest {

    @Test
    @DisplayName("the mode table: machine is his bag, held and on_use_done aim, every other shape is blocks")
    void theModeTable() {
        assertEquals(TurnInto.Mode.INVENTORY, TurnInto.forShape(ItemCatalogue.MACHINE));
        assertEquals(TurnInto.Mode.AIM, TurnInto.forShape("held"));
        assertEquals(TurnInto.Mode.AIM, TurnInto.forShape("on_use_done"));
        for (String s : ItemCatalogue.EFFECT_SHAPES) {
            if (!List.of(ItemCatalogue.MACHINE, "held", "on_use_done").contains(s)) {
                assertEquals(TurnInto.Mode.BLOCKS, TurnInto.forShape(s), s);
            }
        }
        for (String w : WornShape.SHAPES) {
            assertEquals(TurnInto.Mode.BLOCKS, TurnInto.forShape(w), "a worn thing runs on a clock: " + w);
        }
    }

    @Test
    @DisplayName("the arguments narrow the mode: AIM loses the side it cannot do; a shape with no side is null")
    void theArgumentsNarrowIt() {
        assertEquals(TurnInto.Mode.AIM, TurnInto.effective("held", true, true));
        assertEquals(TurnInto.Mode.INVENTORY, TurnInto.effective("held", false, true), "a made TO: bag only");
        assertEquals(TurnInto.Mode.BLOCKS, TurnInto.effective("held", true, false), "air, any_block: blocks only");
        assertNull(TurnInto.effective("thrown", false, true), "thrown with a TO that is no block: refused");
        assertNull(TurnInto.effective(ItemCatalogue.MACHINE, true, false), "a machine with only any_block: refused");
        assertTrue(TurnInto.sideRefusal(ItemCatalogue.MACHINE, true, false, true, true, false).contains("CARRIES"));
        assertTrue(TurnInto.sideRefusal("thrown", true, true, false, true, false).contains("no block form"));
    }

    @Test
    @DisplayName("a material is a block one way and an item the other; a plain id keeps itself where it can")
    void materials() {
        assertEquals("minecraft:gold_block", TurnInto.material("gold").block());
        assertEquals("minecraft:gold_ingot", TurnInto.material("gold").item());
        assertEquals(TurnInto.material("gold"), TurnInto.material("minecraft:gold_ingot"));
        assertEquals(TurnInto.material("gold"), TurnInto.material("minecraft:gold_block"));
        assertTrue(TurnInto.isMaterialWord("gold"));
        assertTrue(TurnInto.isMaterialWord("minecraft:iron"));
        assertFalse(TurnInto.isMaterialWord("minecraft:gold_ingot"));
        assertNull(TurnInto.material("minecraft:glass"), "glass is one id both ways");
        assertNull(TurnInto.material("othermod:gold"), "another namespace is not ours to read");
        assertFalse(TurnInto.ASSUME.isBlock("minecraft:gold_ingot"));
        assertTrue(TurnInto.ASSUME.isBlock("minecraft:gold_block"));
        assertFalse(TurnInto.ASSUME.isItem("minecraft:air"));
    }

    @Test
    @DisplayName("FROM is a list: comma separated or a JSON array, repeats dropped, order kept")
    void fromIsAList() {
        assertEquals(List.of("minecraft:stone", "#minecraft:logs"), TurnInto.list("minecraft:stone, #minecraft:logs, minecraft:stone"));
        assertEquals(List.of("a:b", "c:d"), TurnInto.list("[\"a:b\",\"c:d\"]"));
        assertTrue(TurnInto.isAnyBlock("any_block") && TurnInto.isAnyBlock("Anything") && TurnInto.isAnyBlock("*"));
        assertTrue(TurnInto.isFurnace("what a furnace makes") && TurnInto.isFurnace("smelted"));
        assertFalse(TurnInto.isFurnace("minecraft:furnace"), "a furnace BLOCK is a thing, not the keyword");
    }

    /** Every helper function closes before the next opens — CBScript has no nested functions (Gate B found it). */
    static void flat(TurnInto.Plan p) {
        int depth = 0;
        for (String line : p.helpers().split("\n")) {
            if (line.startsWith("function ")) {
                assertEquals(0, depth, "a function opened inside another:\n" + p.helpers());
                depth++;
            } else if (line.equals("end")) {
                depth--;
            }
        }
        assertEquals(0, depth, p.helpers());
    }

    private static TurnInto.From block(String id) {
        return new TurnInto.From(TurnInto.readable(id), id, id, id);
    }

    @Test
    @DisplayName("a machine renders the bag only: one take per use, the needs line says 'in your inventory'")
    void aMachineIsItsBag() {
        TurnInto.To gold = new TurnInto.To("Gold", List.of("minecraft:gold_block"), List.of("minecraft:gold_ingot"), "random", false);
        TurnInto.Plan p = TurnInto.plan(ItemCatalogue.MACHINE,
                List.of(block("minecraft:cobblestone"), block("minecraft:stone")), gold, 1, TurnInto.Smelting.load());
        assertEquals(TurnInto.Mode.INVENTORY, p.mode());
        flat(p);
        assertEquals("    /function $namespace:turn_into_bag", p.acts());
        assertFalse(p.helpers().contains("fill "), "a machine never touches a block");
        assertTrue(p.helpers().contains("clear @s minecraft:cobblestone 1") && p.helpers().contains("clear @s minecraft:stone 1"));
        assertTrue(p.helpers().indexOf("clear @s minecraft:cobblestone") < p.helpers().indexOf("clear @s minecraft:stone"),
                "the first one he has is taken: in the order named");
        assertTrue(p.helpers().contains("tag=!mfam_ti_done] store result"), "a take after one landed is skipped: one per use");
        assertTrue(p.helpers().contains("give @s minecraft:gold_ingot 1"), "gold in his hand is an ingot");
        assertTrue(p.helpers().contains("Cobblestone or Stone") && p.helpers().contains(" in your inventory to use this!"));
    }

    @Test
    @DisplayName("held aims: the first solid block decides — a match is zapped, anything else works on the bag, never both")
    void heldAims() {
        TurnInto.To gold = new TurnInto.To("Gold", List.of("minecraft:gold_block"), List.of("minecraft:gold_ingot"), "random", false);
        TurnInto.Plan p = TurnInto.plan("held", List.of(block("minecraft:stone")), gold, 0, TurnInto.Smelting.load());
        assertEquals(TurnInto.Mode.AIM, p.mode());
        flat(p);
        assertFalse(TurnInto.shapeAims(p, "held"), "it raycasts for itself, so the shape runs it as him");
        assertTrue(p.acts().contains("run function $namespace:turn_into_aim"));
        assertTrue(p.acts().contains("/execute if entity @s[tag=!mfam_ti_block] run function $namespace:turn_into_bag"),
                "the bag only when no block matched");
        assertTrue(p.helpers().contains("/execute if block ~ ~ ~ minecraft:stone run tag @s add mfam_ti_block"));
        assertTrue(p.helpers().contains("fill ~-0 ~-0 ~-0 ~0 ~0 ~0 minecraft:gold_block replace minecraft:stone"),
                "a block is gold BLOCK");
        assertTrue(p.helpers().contains("give @s minecraft:gold_ingot 1"));
    }

    @Test
    @DisplayName("thrown is blocks only, at the place the shape chooses; a list of FROMs is one helper per pick")
    void thrownIsBlocks() {
        TurnInto.To glass = new TurnInto.To("Glass", List.of("minecraft:glass"), List.of("minecraft:glass"), "random", false);
        TurnInto.Plan p = TurnInto.plan("thrown", List.of(block("minecraft:sand"), block("minecraft:dirt")), glass, 1,
                TurnInto.Smelting.load());
        assertEquals(TurnInto.Mode.BLOCKS, p.mode());
        flat(p);
        assertTrue(TurnInto.shapeAims(p, "thrown"));
        assertEquals("    /function $namespace:turn_into_zap", p.acts());
        assertFalse(p.helpers().contains("clear @s"), "a thrown thing never takes from his bag");
        assertTrue(p.helpers().contains("replace minecraft:sand") && p.helpers().contains("replace minecraft:dirt"));
    }

    @Test
    @DisplayName("furnace: each FROM becomes its own result; a block chain never changes twice; no result is said")
    void furnace() {
        TurnInto.Smelting t = TurnInto.Smelting.load();
        assertEquals("minecraft:iron_ingot", t.items().get("minecraft:raw_iron"));
        assertEquals("minecraft:charcoal", t.items().get("minecraft:oak_log"));
        assertEquals("minecraft:glass", t.blocks().get("minecraft:sand"));
        assertEquals("minecraft:stone", t.blocks().get("minecraft:cobblestone"));
        assertNull(t.blocks().get("minecraft:oak_log"), "a log has no block result");
        TurnInto.Plan p = TurnInto.plan("held", List.of(block("minecraft:cobblestone"), block("minecraft:stone"),
                block("minecraft:dirt")), TurnInto.To.smelter(), 0, t);
        assertEquals(TurnInto.Mode.AIM, p.mode());
        flat(p);
        String h = p.helpers();
        assertTrue(h.indexOf("minecraft:smooth_stone replace minecraft:stone") < h.indexOf("minecraft:stone replace minecraft:cobblestone"),
                "stone -> smooth stone runs BEFORE cobblestone -> stone, or cobblestone would become smooth stone: " + h);
        assertTrue(h.contains("give @s minecraft:stone 1") && h.contains("give @s minecraft:smooth_stone 1"));
        assertEquals(List.of("Dirt"), p.skipped(), "dirt smelts into nothing");
        assertTrue(h.contains("A furnace makes nothing from "));
        assertTrue(TurnInto.plan(ItemCatalogue.MACHINE, List.of(new TurnInto.From("any block", TurnInto.ANY, null, null)),
                TurnInto.To.smelter(), 1, t).mode() == null, "a furnace machine of any_block takes nothing");
        assertFalse(TurnInto.plan("thrown", List.of(new TurnInto.From("any block", TurnInto.ANY, null, null)),
                TurnInto.To.smelter(), 1, t).helpers().isEmpty(), "but thrown, any_block smelts the changeable blocks");
    }

    @Test
    @DisplayName("the old names: INPUT/OUTPUT and BLOCK are FROM/TO, flat or grouped; a held convert gains RADIUS 0")
    void theOldNames() {
        JsonObject flat = JsonParser.parseString("{\"INPUT\":\"a:b\",\"OUTPUT\":\"c:d\"}").getAsJsonObject();
        assertTrue(TurnInto.normaliseParams(flat, List.of("turn_into"), "held"));
        assertEquals(JsonParser.parseString("{\"FROM\":\"a:b\",\"TO\":\"c:d\",\"RADIUS\":\"0\"}"), flat);
        JsonObject machine = JsonParser.parseString("{\"INPUT\":\"a:b\",\"OUTPUT\":\"c:d\"}").getAsJsonObject();
        TurnInto.normaliseParams(machine, List.of("turn_into"), ItemCatalogue.MACHINE);
        assertFalse(machine.has("RADIUS"), "a machine never reads one");
        JsonObject grouped = JsonParser.parseString(
                "{\"change_block\":{\"FROM\":\"a:b\",\"BLOCK\":\"c:d\",\"RADIUS\":\"1\"},\"hurt\":{\"HEARTS\":\"2\"}}")
                .getAsJsonObject();
        TurnInto.normaliseParams(grouped, List.of("turn_into", "hurt"), "thrown");
        assertEquals(JsonParser.parseString(
                "{\"hurt\":{\"HEARTS\":\"2\"},\"turn_into\":{\"FROM\":\"a:b\",\"RADIUS\":\"1\",\"TO\":\"c:d\"}}"), grouped);
        JsonObject place = JsonParser.parseString("{\"BLOCK\":\"minecraft:water\"}").getAsJsonObject();
        assertFalse(TurnInto.normaliseParams(place, List.of("place"), "held"), "another effect's BLOCK is its own");
        assertEquals(List.of("turn_into", "glow"), TurnInto.canonicalEffects(List.of("convert", "glow", "change_block")));
        assertTrue(TurnInto.normaliseParams(JsonParser.parseString("{\"FROM\":\"a:b\",\"TO\":\"c:d\"}").getAsJsonObject(),
                List.of("turn_into"), "held") == false, "already new: nothing to do");
    }

    @Test
    @DisplayName("the catalogue: turn_into is one entry, and convert and change_block are its aliases only")
    void theCatalogue() {
        ItemCatalogue c = ItemCatalogue.load();
        assertEquals("turn_into", c.canonical("convert"));
        assertEquals("turn_into", c.canonical("change_block"));
        assertTrue(c.all().stream().noneMatch(e -> TurnInto.OLD_IDS.contains(e.id())));
        assertEquals(List.of("FROM", "TO", "RADIUS"), c.get("turn_into").params());
    }

    @Test
    @DisplayName("readable names and 'either'")
    void words() {
        assertEquals("Raw Iron", TurnInto.readable("minecraft:raw_iron"));
        assertEquals("any Logs", TurnInto.readable("#minecraft:logs"));
        assertEquals("A, B or C", TurnInto.either(List.of("A", "B", "C", "A")));
        assertEquals(Map.of(), Map.of());
    }
}
