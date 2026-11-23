package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.item.ItemSpecStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A recipe can name something he made — and must leave everything else alone.</b>
 *
 * <p>The rewrite runs over every recipe the agent writes, so the interesting assertions are the
 * ones about what it does <b>not</b> touch. A fluid ingredient carries its own {@code type} and
 * Create rejects the whole recipe if that is disturbed; a tag is not an item; a vanilla id is
 * already right. Each of those is a way for this to break machines that had nothing to do with it.
 */
class RecipeItemRefsTest {

    /** Stands in for the encode-from-the-real-stack path, which needs a server. */
    private static final RecipeItemRefs.Components COMPONENTS = ref -> {
        JsonObject c = new JsonObject();
        c.addProperty("minecraft:custom_name", ref.feature());
        return c;
    };

    /**
     * Only the ids this test names as real. Spelt out rather than "anything in minecraft:",
     * because the typo row exists precisely to prove a well-formed id is still checked.
     */
    private static final RegistryExists KNOWN = id -> java.util.Set.of(
            "minecraft:wheat", "minecraft:cookie", "minecraft:water", "minecraft:logs",
            ItemBase.WISH_ITEM, VanillaCarrier.ITEM_ID).contains(id);

    private static JsonArray arr(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    @BeforeEach
    void made() {
        ItemSpecStore.put(new ItemSpecStore.Spec("roasted_beans_plain", "Roasted Coffee Beans",
                "seed", "brown", "plain", 1, List.of(), new JsonObject()));
    }

    @AfterEach
    void forget() {
        ItemSpecStore.clear();
    }

    @Test
    @DisplayName("a made item as an INGREDIENT becomes a component match, not a bare id")
    void anIngredientMatchesTheOneItem() {
        JsonArray in = arr("[{\"item\":\"roasted_beans_plain\"}]");
        JsonArray out = arr("[{\"id\":\"minecraft:cookie\"}]");
        assertTrue(RecipeItemRefs.rewrite(in, out, COMPONENTS, KNOWN).isEmpty());

        JsonObject i = in.get(0).getAsJsonObject();
        assertEquals("neoforge:components", i.get(com.moddingfromamod.compat.Ingredients.TYPE_KEY).getAsString(),
                "a bare wish_item id would match EVERY wish item he is carrying");
        // OUR OWN ITEM, whichever id that currently is. A recipe naming the mod item while the
        // give hands out a vanilla carrier matches nothing, silently — so this must follow the
        // route rather than pin it.
        assertEquals(ItemBase.id(null), i.getAsJsonArray("items").get(0).getAsString());
        assertTrue(i.has("components"));
        assertFalse(i.get("strict").getAsBoolean(),
                "strict demands the components match EXACTLY, so the recipe would stop matching "
                        + "the moment the item carried anything it did not list");
    }

    @Test
    @DisplayName("a made item as a RESULT keeps its count and chance, and gains its components")
    void aResultKeepsWhatItAlreadySaid() {
        JsonArray in = arr("[{\"item\":\"minecraft:wheat\"}]");
        JsonArray out = arr("[{\"id\":\"roasted_beans_plain\",\"count\":3,\"chance\":0.5}]");
        assertTrue(RecipeItemRefs.rewrite(in, out, COMPONENTS, KNOWN).isEmpty());

        JsonObject r = out.get(0).getAsJsonObject();
        assertEquals(ItemBase.id(null), r.get("id").getAsString());
        assertEquals(3, r.get("count").getAsInt(), "the amount he asked for is not ours to change");
        assertEquals(0.5, r.get("chance").getAsDouble(), 0.0001);
        assertTrue(r.has("components"));
    }

    /**
     * The rows that must survive untouched. A fluid ingredient written without its {@code type} is
     * a mistake this tool's own description already warns about — reintroducing it here would break
     * every fluid machine, and none of them has anything to do with item references.
     */
    @Test
    @DisplayName("tags, fluids and plain vanilla ids are left exactly as they were")
    void everythingElseIsUntouched() {
        String original = "[{\"item\":\"minecraft:wheat\"},{\"tag\":\"minecraft:logs\"},"
                + "{\"type\":\"neoforge:single\",\"amount\":250,\"fluid\":\"minecraft:water\"}]";
        JsonArray in = arr(original);
        JsonArray out = arr("[{\"id\":\"minecraft:cookie\",\"count\":2},"
                + "{\"id\":\"minecraft:water\",\"amount\":250}]");
        String outBefore = out.toString();

        assertTrue(RecipeItemRefs.rewrite(in, out, COMPONENTS, KNOWN).isEmpty());
        assertEquals(original.replace(" ", ""), in.toString().replace(" ", ""));
        assertEquals(outBefore, out.toString());
    }

    @Test
    @DisplayName("an id nothing registers is refused BY POSITION, so the agent knows which one")
    void aBadIdSaysWhichOne() {
        JsonArray in = arr("[{\"item\":\"minecraft:wheat\"},{\"item\":\"minecraft:whaet\"}]");
        JsonArray out = arr("[{\"id\":\"never_made_this\"}]");
        List<String> bad = RecipeItemRefs.rewrite(in, out, COMPONENTS, KNOWN);
        assertEquals(2, bad.size(), bad.toString());
        assertTrue(bad.get(0).contains("ingredient 2"), bad.get(0));
        assertTrue(bad.get(1).contains("result 1"), bad.get(1));
    }

    @Test
    @DisplayName("the blank wish item is refused here too — it would match every wish item")
    void theBlankIsRefused() {
        JsonArray in = arr("[{\"item\":\"" + ItemBase.WISH_ITEM + "\"}]");
        JsonArray out = arr("[{\"id\":\"minecraft:cookie\"}]");
        assertFalse(RecipeItemRefs.rewrite(in, out, COMPONENTS, KNOWN).isEmpty(),
                "a recipe eating any wish item at all is a machine that eats his wand");
    }
}
