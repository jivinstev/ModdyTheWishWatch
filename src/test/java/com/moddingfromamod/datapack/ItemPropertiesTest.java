package com.moddingfromamod.datapack;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PROPERTIES axis's own rules, none of which need Minecraft to be true.
 *
 * <p>What is NOT tested here is whether the rendered component spelling is the one 1.21.1 accepts —
 * that is a version-specific fact, and by this repo's own rule such a fact is measured against the
 * real game or it is not known. {@code ItemPropertiesGameTest} gives the item and reads the stack
 * back.
 */
class ItemPropertiesTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    @DisplayName("nothing the agent typed reaches the give line — the value is a re-rendered number")
    void valuesAreParsedNeverInterpolated() {
        // The injection this table exists to prevent. A component map an agent can write into is a
        // way into NBT; the value here survives only as an int, so a payload cannot ride on it.
        JsonObject evil = json("{\"max_stack_size\":\"1] /kill @a\"}");
        assertFalse(ItemProperties.refusals(evil).isEmpty(),
                "a value that is not a whole number must be refused, not rendered");
        assertEquals("", ItemProperties.render(json("{}")),
                "an empty properties object adds nothing to the give line");
    }

    @Test
    @DisplayName("an unknown property is refused, and the refusal says what an effect is instead")
    void anUnknownPropertyIsRefused() {
        List<String> why = ItemProperties.refusals(json("{\"sparkly\":\"3\"}"));
        assertEquals(1, why.size(), why.toString());
        assertTrue(why.get(0).contains("sparkly"), why.get(0));
        // The likeliest cause of a made-up property is an agent reaching for a BEHAVIOUR, so the
        // refusal has to point at the other axis rather than only list the keys.
        assertTrue(why.get(0).contains("effect"),
                "the refusal should say where a behaviour belongs: " + why.get(0));
    }

    @Test
    @DisplayName("out of range is refused rather than clamped")
    void outOfRangeIsRefusedNotClamped() {
        // Clamping is the tempting version and it is the wrong one: a wish for a stack of 200
        // quietly answered with 64 is a wrong answer he discovers by counting.
        assertFalse(ItemProperties.refusals(json("{\"max_stack_size\":\"200\"}")).isEmpty());
        assertFalse(ItemProperties.refusals(json("{\"max_stack_size\":\"0\"}")).isEmpty());
        assertTrue(ItemProperties.refusals(json("{\"max_stack_size\":\"64\"}")).isEmpty());
        assertTrue(ItemProperties.refusals(json("{\"max_stack_size\":\"1\"}")).isEmpty());
    }

    @Test
    @DisplayName("a number arrives as a number or as a string, and both work")
    void bothJsonSpellingsOfANumberWork() {
        // Agents send 64 and "64" interchangeably, and the corpus pre-registration uses strings.
        assertEquals(ItemProperties.render(json("{\"max_stack_size\":1}")),
                ItemProperties.render(json("{\"max_stack_size\":\"1\"}")));
    }

    @Test
    @DisplayName("the rendered order is OURS, so two identical wishes render identically")
    void renderedOrderDoesNotFollowJsonKeyOrder() {
        String a = ItemProperties.render(
                json("{\"max_stack_size\":\"1\",\"attack_damage\":\"12\"}"));
        String b = ItemProperties.render(
                json("{\"attack_damage\":\"12\",\"max_stack_size\":\"1\"}"));
        assertEquals(a, b, "key order in the agent's JSON must not change the give line, or every "
                + "rendered-bytes assertion in the suite depends on it");
        assertTrue(a.startsWith(","), "the fragment splices into a give line: " + a);
    }

    @Test
    @DisplayName("a size is the difference between three cups and one cup in three colours")
    void theSizesAreActuallyDifferent() {
        // The wish this axis was built for. If small and large render the same fragment, the family
        // is three names over one item and the child cannot tell them apart by anything but a label.
        String small = ItemProperties.render(json("{\"max_stack_size\":\"64\"}"));
        String large = ItemProperties.render(json("{\"max_stack_size\":\"1\"}"));
        assertFalse(small.equals(large), "small and LARGE must render differently");
        assertTrue(large.contains("max_stack_size=1"), large);
    }

    @Test
    @DisplayName("attack damage carries OUR id, so nothing else can collide with or replace it")
    void theAttributeModifierIsNamespaced() {
        String r = ItemProperties.render(json("{\"attack_damage\":\"12\"}"));
        assertTrue(r.contains("moddingfromamod:"),
                "an unnamespaced modifier id can be silently replaced by another mod's: " + r);
        assertTrue(r.contains("amount:12"), r);
    }

    @Test
    @DisplayName("every property is described for the agent and for inspect_item")
    void everyPropertyIsDescribed() {
        // A property with no line in the catalogue is a property the agent is never told about,
        // which is the whole finding in a smaller shape.
        assertEquals(ItemProperties.keys().size(), ItemProperties.catalogue().size());
        for (String key : ItemProperties.keys()) {
            assertTrue(ItemProperties.catalogue().stream().anyMatch(l -> l.startsWith(key + " ")),
                    key + " has no line in the catalogue");
        }
        assertEquals(List.of("max_stack_size = 1"),
                ItemProperties.describe(json("{\"max_stack_size\":\"1\"}")));
    }

    @Test
    @DisplayName("null and absent are the same thing, and neither is an error")
    void absentIsNotAnError() {
        assertTrue(ItemProperties.refusals(null).isEmpty());
        assertEquals("", ItemProperties.render(null));
        assertEquals(List.of(), ItemProperties.describe(null));
    }

    @Test
    @DisplayName("enchantments are a closed table of names and bounded levels")
    void enchantmentsAreClosedAndBounded() {
        assertTrue(ItemProperties.refusals(json("{\"enchantments\":{\"fortune\":3}}")).isEmpty());
        assertTrue(ItemProperties.refusals(json("{\"enchantments\":{\"minecraft:loyalty\":\"3\"}}"))
                .isEmpty(), "a namespaced name and a string level both work");
        assertFalse(ItemProperties.refusals(json("{\"enchantments\":{\"fortune\":6}}")).isEmpty(),
                "past the cap is refused, not clamped");
        assertFalse(ItemProperties.refusals(json("{\"enchantments\":{\"fortune\":0}}")).isEmpty());
        assertFalse(ItemProperties.refusals(json("{\"enchantments\":{\"binding_curse\":1}}")).isEmpty(),
                "curses are not in the table");
        assertFalse(ItemProperties.refusals(json("{\"enchantments\":3}")).isEmpty(),
                "a number where an object belongs is refused");
        assertFalse(ItemProperties.refusals(json("{\"enchantments\":{}}")).isEmpty(),
                "an empty object asks for nothing and is refused rather than silently ignored");
    }

    @Test
    @DisplayName("nothing the agent typed reaches the enchantments component")
    void enchantmentsAreRenderedFromTheTable() {
        String evil = ItemProperties.render(json(
                "{\"enchantments\":{\"fortune\":3,\"x\\\"}] /kill @a\":1}}"));
        assertEquals(",minecraft:enchantments={\"minecraft:fortune\":3}", evil,   // 1.21.5: no {levels:} wrapper
                "only a known name at a valid level renders");
        String a = ItemProperties.render(json("{\"enchantments\":{\"unbreaking\":3,\"fortune\":2}}"));
        String b = ItemProperties.render(json("{\"enchantments\":{\"fortune\":2,\"unbreaking\":3}}"));
        assertEquals(a, b, "the order is the table's, not the JSON's");
        assertEquals(List.of("enchantments = fortune 2, unbreaking 3"),
                ItemProperties.describe(json("{\"enchantments\":{\"unbreaking\":3,\"fortune\":2}}")));
    }

    /** crossbow-only enchantments on a bow are a silent no-op, so they are refused by name. */
    @org.junit.jupiter.api.Test
    void crossbowOnlyEnchantmentsAreRefusedOffACrossbow() {
        com.google.gson.JsonObject p = com.google.gson.JsonParser.parseString(
                "{\"enchantments\":{\"multishot\":1,\"power\":3}}").getAsJsonObject();
        var bad = ItemProperties.baseRefusals(p, "minecraft:bow");
        org.junit.jupiter.api.Assertions.assertEquals(1, bad.size(), String.valueOf(bad));
        org.junit.jupiter.api.Assertions.assertTrue(bad.get(0).contains("CROSSBOW"), bad.get(0));
        org.junit.jupiter.api.Assertions.assertTrue(ItemProperties.baseRefusals(p, "crossbow").isEmpty(),
                "on a crossbow it is exactly right");
    }
}
