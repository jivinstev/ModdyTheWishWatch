package com.moddingfromamod.agent.tools;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.item.ItemSpecStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The PROPERTIES axis survives an edit that never mentions it.</b>
 *
 * <p>This is an earlier issue's shape applied to a new axis before it can happen again, and it is rule 2 of
 * <i>"what every step inherits"</i>: a family survived its first wish and died on its second,
 * because absent meant <i>throw it away</i> instead of <i>keep</i>. The give line still rendered and
 * every gate stayed green.
 *
 * <p>The failure this prevents is the same one, one axis over: he asks for small, medium and LARGE
 * cups, then says <i>"make them blue"</i> — a wish about the COLOUR, carrying no sizes — and the
 * LARGE cup silently goes back to stacking to sixty-four. What is left is three names over one
 * item, which is exactly the state the axis was built to leave behind.
 */
class PropertiesSurviveRefinementTest {

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** The three cups, as the corpus pre-registered them: one family, one size each. */
    private static JsonArray threeCups() {
        return JsonParser.parseString("""
                [{"name":"Small Cup","colour":"white","properties":{"max_stack_size":"64"}},
                 {"name":"Medium Cup","colour":"silver","properties":{"max_stack_size":"16"}},
                 {"name":"Large Cup","colour":"gray","properties":{"max_stack_size":"1"}}]""")
                .getAsJsonArray();
    }

    private static ItemSpecStore.Spec cups() {
        return new ItemSpecStore.Spec("small_cup_plain", "Small Cup", "cup", "white", "plain",
                6, List.of(), new JsonObject(), threeCups(), new JsonObject());
    }

    private static List<MakeItemTool.Variant> familyAfter(String edit) {
        return MakeItemTool.parseVariants(
                MakeItemTool.inherit(args(edit), cups()), "Small Cup", 0xFFFFFF);
    }

    @Test
    @DisplayName("an edit about the COLOUR leaves every size standing")
    void aColourEditKeepsTheSizes() {
        List<MakeItemTool.Variant> after = familyAfter(
                "{\"start_from\":\"small_cup_plain\",\"colour\":\"blue\"}");
        assertEquals(3, after.size(), "the family itself must survive too");
        assertEquals(List.of("64", "16", "1"),
                after.stream().map(v -> v.props().get("max_stack_size").getAsString()).toList(),
                "a wish about the colour deleted the sizes — three names over one item");
    }

    @Test
    @DisplayName("a wish-level property survives, and a member may still override it")
    void theSharedPropertyIsInheritedAndOverridable() {
        ItemSpecStore.Spec shared = new ItemSpecStore.Spec("bag_plain", "Bag", "bag", "white",
                "plain", 3, List.of(), new JsonObject(),
                JsonParser.parseString("""
                        [{"name":"Little Bag","colour":"white"},
                         {"name":"Big Tub","colour":"red","properties":{"max_stack_size":"1"}}]""")
                        .getAsJsonArray(),
                args("{\"max_stack_size\":\"64\"}"));

        List<MakeItemTool.Variant> after = MakeItemTool.parseVariants(
                MakeItemTool.inherit(args("{\"start_from\":\"bag_plain\",\"colour\":\"green\"}"),
                        shared), "Bag", 0xFFFFFF);

        assertEquals("64", after.get(0).props().get("max_stack_size").getAsString(),
                "the member with no property of its own takes the wish's");
        assertEquals("1", after.get(1).props().get("max_stack_size").getAsString(),
                "a member's own value wins over the wish's");
    }

    @Test
    @DisplayName("a member's new property is merged into the ones it already had, not swapped for them")
    void aSecondPropertyDoesNotDropTheFirst() {
        // "make the big one hit hard too" — an edit naming ONE property on an item that already
        // carries another. A whole-object replace would leave a Large Cup that stacks to 64 again,
        // which is the mergeParams failure in a different costume.
        ItemSpecStore.Spec base = new ItemSpecStore.Spec("club_plain", "Club", "hammer", "brown",
                "plain", 1, List.of(), new JsonObject(), new JsonArray(),
                args("{\"max_stack_size\":\"1\"}"));

        JsonObject merged = MakeItemTool.inherit(
                args("{\"start_from\":\"club_plain\",\"properties\":{\"attack_damage\":\"14\"}}"),
                base).getAsJsonObject("properties");

        assertEquals("1", merged.get("max_stack_size").getAsString(),
                "the property the edit did not mention was dropped");
        assertEquals("14", merged.get("attack_damage").getAsString());
    }

    @Test
    @DisplayName("the store round-trips properties, and a record written before the axis still loads")
    void theStoreRoundTripsAndToleratesOlderRecords() {
        ItemSpecStore.clear();
        ItemSpecStore.put(cups());
        ItemSpecStore.Spec back = ItemSpecStore.get("small_cup_plain");
        assertTrue(back != null, "the spec did not come back");
        assertEquals(3, back.variants().size());
        assertEquals("1", back.variants().get(2).getAsJsonObject()
                .getAsJsonObject("properties").get("max_stack_size").getAsString());

        // Every item made before this axis existed has no `properties` key at all. Absent must mean
        // "none", never "this record is damaged" — a child must not lose a design to a new field.
        ItemSpecStore.Spec old = new ItemSpecStore.Spec("older_hurt", "Older", "wand", "red",
                "held", 1, List.of("hurt"), new JsonObject());
        assertEquals(0, old.properties().size());
        ItemSpecStore.put(old);
        assertTrue(ItemSpecStore.get("older_hurt") != null,
                "a record with no properties must still load");
        ItemSpecStore.clear();
    }
}
