package com.moddingfromamod.datapack;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** the one-kind bag's word renders a tag from OUR table, and nothing else renders. */
class HoldsOnlyTest {

    private static JsonObject word(String w) {
        JsonObject o = new JsonObject();
        o.addProperty("holds_only", w);
        return o;
    }

    @Test
    void aKnownWordRendersItsTagAndATypoIsRefusedByName() {
        JsonObject food = word(" Food");
        assertTrue(ItemProperties.refusals(food).isEmpty(), ItemProperties.refusals(food).toString());
        assertEquals(",WishHoldsOnly:\"c:foods\"", ItemProperties.dataEntries(food));
        assertEquals("minecraft:arrows", ItemProperties.holdsOnlyTag(word("arrow")), "a singular finds its plural");
        JsonObject bad = word("c:foods\"]");
        assertFalse(ItemProperties.refusals(bad).isEmpty());
        assertEquals("", ItemProperties.dataEntries(bad), "an injected word renders nothing");
    }

    @Test
    void onlyABundleTakesIt() {
        JsonObject food = word("food");
        if (com.moddingfromamod.compat.Bundles.SUPPORTED) {
            assertTrue(ItemProperties.baseRefusals(food, "minecraft:bundle").isEmpty());
            assertTrue(ItemProperties.baseRefusals(food, "minecraft:red_bundle").isEmpty());
            assertFalse(ItemProperties.baseRefusals(food, "minecraft:chest").isEmpty());
        } else {
            assertFalse(ItemProperties.baseRefusals(food, "minecraft:bundle").isEmpty(), "1.21.1 has no bundle");
        }
    }
}
