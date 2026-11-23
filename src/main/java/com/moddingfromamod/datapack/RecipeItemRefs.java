package com.moddingfromamod.datapack;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * <b>Naming an item the child MADE inside a recipe — on both sides.</b>
 *
 * <p>{@code author_recipe}'s own {@code he_gets_out} description tells the agent, in as many words,
 * <i>"find_content that noun first … and make_item it if nothing has it"</i> — and then the recipe
 * could not express the result. Every item made from scratch is {@code moddingfromamod:wish_item};
 * naming that matches every wish item at once, and naming the feature id names nothing at all. So
 * the tool <b>directed</b> the agent to make an item and then silently could not use it: a recipe
 * with a nonexistent id is a datapack load error, and one naming the shared id is a machine that
 * eats whatever wish item it is handed.
 *
 * <h2>Both ends work, and that was read out of the bytecode rather than assumed</h2>
 * The plan predicted a refusal here. It was wrong, and the correction is worth keeping:
 *
 * <ul>
 *   <li><b>Result</b> — Create's {@code ProcessingOutput} codec carries {@code id}, {@code count},
 *       {@code chance} and <b>{@code components}</b>. Confirmed by reading the class: it holds a
 *       {@code DataComponentPatch patch} field, and those four strings are the keys in its codec.</li>
 *   <li><b>Ingredient</b> — NeoForge 21.1 ships {@code DataComponentIngredient}, registered as
 *       {@code neoforge:components}, with keys {@code items}, {@code components} and
 *       {@code strict}. So an ingredient CAN match on components.</li>
 * </ul>
 *
 * <p><b>{@code strict} is false on purpose.</b> Strict demands the stack's components equal the
 * listed ones exactly, which would fail the moment an item carried anything the recipe did not
 * mention. Non-strict asks that the listed ones are present — and the one listed here is the whole
 * of {@code custom_data}, identity and behaviour together, which is what tells a pizza from its
 * slices (they share a feature id; only the behaviour tag differs).
 */
public final class RecipeItemRefs {

    private RecipeItemRefs() {
    }

    /** Turns a rendered give argument into the {@code components} object a recipe wants. */
    public interface Components {
        /** @return the components of this made item as recipe JSON, or null if it cannot be built */
        JsonObject of(ItemRef ref);
    }

    /**
     * Rewrites every made-item reference in a recipe's ingredients and results, in place.
     *
     * @return everything that could not be resolved, in the agent's terms; empty when all is well
     */
    public static List<String> rewrite(JsonArray ingredients, JsonArray results,
                                       Components components, RegistryExists registry) {
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < ingredients.size(); i++) {
            JsonElement rewritten = ingredient(ingredients.get(i), components, registry, i, bad);
            if (rewritten != null) {
                ingredients.set(i, rewritten);
            }
        }
        for (int i = 0; i < results.size(); i++) {
            JsonElement rewritten = result(results.get(i), components, registry, i, bad);
            if (rewritten != null) {
                results.set(i, rewritten);
            }
        }
        return bad;
    }

    /** An ingredient is {@code {"item":…}} or {@code {"tag":…}} or a fluid; only the first can be ours. */
    private static JsonElement ingredient(JsonElement el, Components components,
                                          RegistryExists registry, int i, List<String> bad) {
        if (!el.isJsonObject()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        // A TAG OR A FLUID IS NOT OURS TO TOUCH. Only a plain `item` can name a made thing, and a
        // fluid ingredient carries its own `type` which must survive untouched -- writing it
        // without one is the mistake this tool's own description already warns about.
        if (!o.has("item") || o.has("type") || o.has("neoforge:ingredient_type") || o.has("tag") || o.has("fluid")) {
            return null;
        }
        ItemRef ref = ItemRef.parse(o.get("item").getAsString());
        String no = ref.whyUnusable("ingredient " + (i + 1), registry);
        if (no != null) {
            bad.add(no);
            return null;
        }
        if (!ref.isWished()) {
            return null;
        }
        JsonObject c = components.of(ref);
        if (c == null) {
            bad.add("ingredient " + (i + 1) + " names '" + o.get("item").getAsString()
                    + "', which could not be built into an ingredient the game understands.");
            return null;
        }
        JsonObject out = new JsonObject();
        out.addProperty(com.moddingfromamod.compat.Ingredients.TYPE_KEY, "neoforge:components");
        JsonArray items = new JsonArray();
        // WHATEVER OUR OWN ITEM CURRENTLY IS, not the literal — a recipe that names the mod item
        // while the give hands out a vanilla carrier can never match the very thing it was built
        // from, and neither /clear nor the recipe book says so. Measured by Gate B's
        // arecipecannamesomethinghemade the day the carrier became the default.
        items.add(ItemBase.id(null));
        out.add("items", items);
        out.add("components", c);
        out.addProperty("strict", false);
        return out;
    }

    /** A result is keyed by {@code id}, not {@code item} — Create's own shape, and easy to confuse. */
    private static JsonElement result(JsonElement el, Components components,
                                      RegistryExists registry, int i, List<String> bad) {
        if (!el.isJsonObject()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        if (!o.has("id") || o.has("fluid")) {
            return null;
        }
        ItemRef ref = ItemRef.parse(o.get("id").getAsString());
        String no = ref.whyUnusable("result " + (i + 1), registry);
        if (no != null) {
            bad.add(no);
            return null;
        }
        if (!ref.isWished()) {
            return null;
        }
        JsonObject c = components.of(ref);
        if (c == null) {
            bad.add("result " + (i + 1) + " names '" + o.get("id").getAsString()
                    + "', which could not be built into a result the game understands.");
            return null;
        }
        JsonObject out = o.deepCopy();
        out.addProperty("id", ItemBase.id(null));   // ditto: what he actually gets, not the literal
        out.add("components", c);
        return out;
    }
}
