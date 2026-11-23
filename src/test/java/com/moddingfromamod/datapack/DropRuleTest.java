package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The {@code loot_table} part: the drop rule, parsed, clamped and matched with no game. */
class DropRuleTest {

    private static DropRule rule(String json) {
        return DropRule.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    private static final java.util.function.BiPredicate<String, String> LEAVES =
            (tag, thing) -> tag.equals("minecraft:leaves") && thing.endsWith("_leaves");

    @Test
    @DisplayName("'zombies drop diamonds' matches the zombie's table and nothing else")
    void aNamedMobMatchesOnlyItsOwnTable() {
        DropRule r = rule("{source:'mob', which:'zombie', item:'diamond'}");
        assertEquals("minecraft:zombie", r.which());
        assertEquals("minecraft:diamond", r.item());
        assertTrue(r.matches("minecraft:entities/zombie", null));
        assertFalse(r.matches("minecraft:entities/husk", null));
        assertFalse(r.matches("minecraft:blocks/zombie", null), "a block table is never a mob's");
    }

    @Test
    @DisplayName("'mobs drop cake sometimes' matches every mob table, once each")
    void anyMobMatchesEveryEntityTable() {
        DropRule r = rule("{source:'mob', which:'any', item:'cake', chance:0.1}");
        assertTrue(r.matches("minecraft:entities/zombie", null));
        assertTrue(r.matches("minecraft:entities/sheep", null));
        // A sheep's wool is a SUB-table rolled inside entities/sheep: matching it too would add the
        // cake twice to one death.
        assertFalse(r.matches("minecraft:entities/sheep/white", null));
        assertFalse(r.matches("minecraft:chests/simple_dungeon", null));
        assertEquals(0.1, r.chance(), 1e-9);
    }

    @Test
    @DisplayName("'trees drop apples' matches every leaves block through a TAG")
    void aTagMatchesThroughTheGame() {
        DropRule r = rule("{source:'block', which:'#minecraft:leaves', item:'apple'}");
        assertTrue(r.matches("minecraft:blocks/oak_leaves", LEAVES));
        assertTrue(r.matches("minecraft:blocks/birch_leaves", LEAVES));
        assertFalse(r.matches("minecraft:blocks/stone", LEAVES));
        assertFalse(r.matches("minecraft:blocks/oak_leaves", null),
                "with no way to ask the game, a tag must not match by accident");
    }

    @Test
    @DisplayName("chests and fishing each match their own tables")
    void chestsAndFishing() {
        DropRule chest = rule("{source:'chest', item:'#c:foods'}");
        assertTrue(chest.matches("minecraft:chests/simple_dungeon", null));
        assertTrue(chest.matches("minecraft:chests/village/village_weaponsmith", null),
                "a village chest's table is nested, and it is still the chest he opens");
        assertFalse(chest.matches("minecraft:entities/zombie", null));
        DropRule fish = rule("{source:'fishing', which:'cod', item:'nautilus_shell'}");
        assertEquals(DropRule.ANY, fish.which(), "there is one fishing table; `which` is ignored");
        assertTrue(fish.matches("minecraft:gameplay/fishing", null));
        assertFalse(fish.matches("minecraft:gameplay/fishing/fish", null),
                "the fish sub-table is rolled inside the catch; matching it doubles the drop");
    }

    @Test
    @DisplayName("the source is required and never guessed")
    void aMissingSourceIsRefusedNotInferred() {
        var e = assertThrows(IllegalArgumentException.class, () -> rule("{which:'stone', item:'emerald'}"));
        assertTrue(e.getMessage().contains("mob, block, chest, fishing"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> rule("{source:'mob', which:'zombie'}"),
                "an item is required");
    }

    @Test
    @DisplayName("chance and count are clamped, never refused")
    void numbersAreClamped() {
        DropRule r = rule("{source:'mob', which:'zombie', item:'diamond', chance:5, count:1000}");
        assertEquals(1.0, r.chance(), 1e-9);
        assertEquals(DropRule.MAX_COUNT, r.count());
        DropRule low = rule("{source:'mob', which:'zombie', item:'diamond', chance:0, count:0}");
        assertEquals(DropRule.MIN_CHANCE, low.chance(), 1e-9);
        assertEquals(1, low.count());
    }

    @Test
    @DisplayName("a rule with no name gets a stable one from what it does")
    void aNameIsMadeWhenNoneIsGiven() {
        assertEquals("mob_zombie_diamond", rule("{source:'mob', which:'zombie', item:'diamond'}").name());
        assertEquals("my_rule", rule("{source:'mob', which:'zombie', item:'diamond', name:'My Rule!'}")
                .name());
    }

    @Test
    @DisplayName("the modifier JSON carries exactly what AddDropModifier's codec reads")
    void theJsonMatchesTheCodec() {
        JsonObject j = rule("{source:'block', which:'stone', item:'emerald', chance:0.05, count:2}")
                .toModifierJson("moddingfromamod:add_drop");
        assertEquals("moddingfromamod:add_drop", j.get("type").getAsString());
        assertTrue(j.getAsJsonArray("conditions").isEmpty());
        assertEquals("block", j.get("source").getAsString());
        assertEquals("minecraft:stone", j.get("which").getAsString());
        assertEquals("minecraft:emerald", j.get("item").getAsString());
        assertEquals(2, j.get("count").getAsInt());
        assertEquals(0.05, j.get("chance").getAsDouble(), 1e-6);
    }

    @Test
    @DisplayName("it says what now happens in the child's terms")
    void itDescribesTheRule() {
        String s = rule("{source:'mob', which:'zombie', item:'diamond', chance:0.25}").describe();
        assertTrue(s.contains("zombie") && s.contains("diamond") && s.contains("25%"), s);
    }
}
