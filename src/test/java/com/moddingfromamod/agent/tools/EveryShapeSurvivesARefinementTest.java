package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.datapack.ItemCatalogue;
import com.moddingfromamod.item.ItemSpecStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A refinement is a capability of the CLASS, not of the shapes that happened to ship first.</b>
 *
 * <p>Every axis added to the items class so far arrived with its own refinement bug, and each one
 * was found by a person in play rather than by a gate:
 *
 * <ul>
 *   <li>{@code variants} — <i>"make them all give you way more speed"</i> deleted three of four
 *       flavours;</li>
 *   <li>{@code properties} — an edit about the colour put the LARGE cup back to stacking to 64;</li>
 *   <li>{@code parts} — <i>"make it blue"</i> took the sprinkles off the cone.</li>
 * </ul>
 *
 * <p>The pattern is not that three fields were forgotten. It is that {@code inherit} is a
 * whitelist, so <b>a new axis defaults to being dropped</b>, and the drop is silent: the item is
 * rebuilt, it renders, it goes in his hand, and the only tell is that something he already had is
 * gone. {@link VariantsSurviveRefinementTest} pins the three known cases; this pins the RULE, over
 * the live catalogue, so the next shape and the next effect are covered the moment they exist
 * rather than the next time somebody remembers.
 *
 * <p>Two things it deliberately does <i>not</i> test, because other gates own them: whether the
 * merged arguments RENDER (the effects x shapes matrix), and whether the agent can find the item to
 * refine at all (that is {@code ItemSpecStore}, a file keyed by the stable feature id — a routing
 * preference expires, so a stranger worker reads exactly the same record the maker wrote).
 */
class EveryShapeSurvivesARefinementTest {

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** Every parameter the effect declares, given a value nothing else in the fixture uses. */
    private static JsonObject paramsFor(ItemCatalogue.Entry e) {
        JsonObject per = new JsonObject();
        for (String p : e.params()) {
            per.addProperty(p, "kept_" + p.toLowerCase(java.util.Locale.ROOT));
        }
        JsonObject all = new JsonObject();
        all.add(e.id(), per);
        return all;
    }

    private static ItemSpecStore.Spec spec(String shape, ItemCatalogue.Entry e) {
        JsonArray variants = JsonParser.parseString(
                "[{\"name\":\"One\",\"colour\":\"red\"},{\"name\":\"Two\",\"colour\":\"blue\"}]")
                .getAsJsonArray();
        JsonArray parts = JsonParser.parseString("[{\"kind\":\"sprinkles\"}]").getAsJsonArray();
        JsonObject props = args("{\"max_stack_size\":1}");
        // BASE_ITEM is in the fixture because it was the axis this sweep could NOT see: the Spec
        // did not carry it, so "not mentioned" had nowhere to be kept from, and an axis that is not
        // persisted does not look like an axis at all. The sweep reported every field surviving
        // while "make my Coffee Beans green" turned them from cocoa beans into a blank wish item.
        return new ItemSpecStore.Spec("fixture_" + e.id(), "The Original Name", "wand", "purple",
                shape, 7, List.of(e.id()), paramsFor(e), variants, props, parts,
                "minecraft:golden_axe");
    }

    /**
     * The whole matrix, edited by a wish that mentions only the name.
     *
     * <p>"Call it the MEGA one" is the smallest real refinement there is, and it is the one that
     * has to be safe: an agent that must restate every field in order to change one is an agent
     * that will get one of them wrong.
     */
    @Test
    @DisplayName("every shape x every effect survives an edit that only renames it")
    void nothingIsDroppedByAnEditThatDidNotMentionIt() {
        ItemCatalogue catalogue = ItemCatalogue.load();
        List<String> lost = new ArrayList<>();

        for (String shape : ItemCatalogue.SHAPES) {
            for (ItemCatalogue.Entry e : catalogue.all()) {
                ItemSpecStore.Spec base = spec(shape, e);
                JsonObject sent = args("{\"start_from\":\"" + base.id()
                        + "\",\"name\":\"The MEGA One\"}");
                JsonObject merged = MakeItemTool.inherit(sent, base);
                String cell = shape + "/" + e.id();

                if (!shape.equals(merged.get("shape").getAsString())) {
                    lost.add(cell + ": shape became " + merged.get("shape").getAsString());
                }
                if (!"wand".equals(merged.get("icon").getAsString())) {
                    lost.add(cell + ": icon");
                }
                if (!"purple".equals(merged.get("colour").getAsString())) {
                    lost.add(cell + ": colour");
                }
                if (merged.get("count").getAsInt() != 7) {
                    lost.add(cell + ": count");
                }
                if (!e.id().equals(merged.get("effect").getAsString())) {
                    lost.add(cell + ": effect became " + merged.get("effect").getAsString());
                }
                if (MakeItemTool.parseVariants(merged, "The MEGA One", 0x8B3BE3).size() != 2) {
                    lost.add(cell + ": the family");
                }
                if (!merged.has("properties")
                        || !merged.getAsJsonObject("properties").has("max_stack_size")) {
                    lost.add(cell + ": properties");
                }
                if (!merged.has("parts") || merged.getAsJsonArray("parts").isEmpty()) {
                    lost.add(cell + ": parts");
                }
                if (!"minecraft:golden_axe".equals(
                        merged.has("base_item") ? merged.get("base_item").getAsString() : null)) {
                    lost.add(cell + ": base_item — the thing it is BUILT ON");
                }
                JsonObject kept = merged.has("params")
                        ? merged.getAsJsonObject("params") : new JsonObject();
                for (String p : e.params()) {
                    if (!kept.has(e.id()) || !kept.getAsJsonObject(e.id()).has(p)) {
                        lost.add(cell + ": param " + p);
                    }
                }
            }
        }

        assertTrue(lost.isEmpty(), "a rename dropped something nobody asked to lose — the #269 "
                + "shape, and `inherit` is a whitelist so a NEW axis defaults to this:\n  "
                + String.join("\n  ", lost));
    }

    /**
     * The wish this test was written for, in the words it would be typed in.
     *
     * <p>A coffee machine that takes cocoa beans; then <i>"actually make it use coffee beans"</i>.
     * The child named the INPUT and nothing else, so the OUTPUT, the body, the shape and the name
     * are all things he still expects to be there when he looks up.
     */
    @Test
    @DisplayName("'make it use coffee beans instead' keeps the machine, the body and the output")
    void changingOneSideOfARecipeKeepsTheOther() {
        // the design as the store holds it now (turn_into, FROM/TO), edited by a call in the OLD
        // shape — {"convert":{"INPUT":…}} — which is what an agent that remembers convert still sends.
        ItemSpecStore.Spec machine = new ItemSpecStore.Spec("coffee_machine_convert",
                "Coffee Machine", "block", "brown", ItemCatalogue.MACHINE, 1, List.of("turn_into"),
                args("""
                        {"turn_into":{"FROM":"minecraft:cocoa_beans",
                                      "TO":"minecraft:mushroom_stew"}}"""));

        JsonObject merged = MakeItemTool.canonicalParams(MakeItemTool.inherit(
                args("{\"start_from\":\"coffee_machine_convert\","
                        + "\"params\":{\"convert\":{\"INPUT\":\"minecraft:wheat_seeds\"}}}"),
                machine));

        JsonObject recipe = merged.getAsJsonObject("params").getAsJsonObject("turn_into");
        assertFalse(merged.getAsJsonObject("params").has("convert"), "the old name is folded in: " + merged);
        assertEquals("minecraft:wheat_seeds", recipe.get("FROM").getAsString(),
                "the one thing he asked to change");
        assertEquals("minecraft:mushroom_stew", recipe.get("TO").getAsString(),
                "he said nothing about what comes OUT — a half-stated recipe is not an edit, it is "
                        + "a new machine wearing the old one's name");
        assertEquals(ItemCatalogue.MACHINE, merged.get("shape").getAsString(),
                "it is still a thing on the floor with a handle");
        assertEquals("brown", merged.get("colour").getAsString(), "the body he watched get built");
        assertEquals("Coffee Machine", merged.get("name").getAsString());
    }


    /**
     * smoke: changing the EFFECT on an edit must not carry the old effect's parameters into
     * the new one. A convert machine turned into load_and_pour kept convert's INPUT, and the new
     * effect refused it — three times, on a correct call.
     */
    @Test
    void aChangedEffectDropsTheOldEffectsParameters() {
        ItemSpecStore.Spec base = new ItemSpecStore.Spec("m_convert", "Machine", "machine", "red",
                "machine", 1, List.of("turn_into"),
                args("{\"turn_into\":{\"FROM\":\"minecraft:wheat\",\"TO\":\"minecraft:bread\"}}"),
                new JsonArray(), new JsonObject(), new JsonArray(), "minecraft:leather_horse_armor");
        JsonObject changed = MakeItemTool.inherit(args(
                "{\"start_from\":\"m_convert\",\"effect\":\"load_and_pour\",\"params\":{\"WHERE\":\"chest\"}}"), base);
        assertFalse(changed.getAsJsonObject("params").has("turn_into"),
                "turn_into's parameters rode along into load_and_pour: " + changed.get("params"));
        JsonObject same = MakeItemTool.inherit(args(
                "{\"start_from\":\"m_convert\",\"name\":\"Bigger Machine\"}"), base);
        assertTrue(same.getAsJsonObject("params").has("turn_into"), "an edit that keeps the effect keeps its parameters");
        // an edit naming the effect by its OLD name keeps it too — "convert" IS the turn_into it has.
        JsonObject oldName = MakeItemTool.inherit(args(
                "{\"start_from\":\"m_convert\",\"effect\":\"convert\"}"), base);
        assertTrue(oldName.getAsJsonObject("params").has("turn_into"), "the old name is not a new effect: " + oldName);

        // THE SHAPE A ONE-EFFECT DESIGN IS REALLY STORED IN: flat. The keyed form above passed
        // while the live wish still failed, because the store never writes it for one effect.
        ItemSpecStore.Spec flat = new ItemSpecStore.Spec("m_flat", "Machine", "machine", "red",
                "machine", 1, List.of("turn_into"),
                args("{\"FROM\":\"minecraft:wheat\",\"TO\":\"minecraft:bread\"}"),
                new JsonArray(), new JsonObject(), new JsonArray(), "minecraft:leather_horse_armor");
        JsonObject p = MakeItemTool.inherit(args(
                "{\"start_from\":\"m_flat\",\"effect\":\"load_and_pour\",\"params\":{\"WHERE\":\"chest\",\"OUTPUT\":null}}"), flat)
                .getAsJsonObject("params");
        assertFalse(p.has("FROM"), "turn_into's flat FROM rode along: " + p);
        assertFalse(p.has("OUTPUT"), "a parameter sent as null is taken off, not a crash: " + p);
        assertEquals("chest", p.get("WHERE").getAsString());
        JsonObject kept = MakeItemTool.inherit(args("{\"start_from\":\"m_flat\",\"name\":\"Big\"}"), flat)
                .getAsJsonObject("params");
        assertTrue(kept.has("FROM"), "an edit that keeps the effect keeps its flat parameters: " + kept);
    }
}
