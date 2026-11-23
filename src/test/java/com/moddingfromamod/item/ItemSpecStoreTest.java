package com.moddingfromamod.item;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that lets a wand be edited instead of duplicated.
 *
 * <p>Measured before this existed: over eight ladder cells, {@code refinements that LANDED} equalled
 * {@code EXTRA items} exactly in both arms — every refinement that worked, worked by making a new
 * item — and a child who asked for one wand ended holding three.
 */
class ItemSpecStoreTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static ItemSpecStore.Spec wand() {
        return new ItemSpecStore.Spec("zonkbolt_lightning", "Zonkbolt", "wand", "purple", "held", 1,
                List.of("lightning"), json("{\"lightning\":{\"HEARTS\":\"4\"}}"));
    }

    @BeforeEach
    void fresh() {
        ItemSpecStore.clear();
    }

    @Test
    @DisplayName("an unmentioned field is KEPT, which is what makes it an edit")
    void anUnmentionedFieldIsKept() {
        // The whole difference between a refinement and a re-description. An agent forced to
        // restate name, icon, colour, shape, count and every effect parameter in order to change
        // one of them gets six more chances per wish to get one wrong, and one wrong field is a
        // wand that quietly stops doing what it did last week.
        ItemSpecStore.Spec green = wand().with(null, null, "green", null, null, null, null);
        assertEquals("green", green.colour());
        assertEquals("Zonkbolt", green.name());
        assertEquals("wand", green.icon());
        assertEquals("held", green.shape());
        assertEquals(List.of("lightning"), green.effects());
        assertEquals("zonkbolt_lightning", green.id(), "the id must NOT move when a field does");
    }

    @Test
    @DisplayName("the id survives a rename, or the rename makes a second item")
    void theIdSurvivesARename() {
        // The measured bug, in one line. "make it hurt way more" was answered by renaming it
        // Zonkbolt MEGA, the id was a slug of the name, and the child got a second wand.
        assertEquals("zonkbolt_lightning",
                wand().with("Zonkbolt MEGA", null, null, null, null, null, null).id());
    }

    @Test
    @DisplayName("the id survives a NEW EFFECT, which is the other half of the same bug")
    void theIdSurvivesANewEffect() {
        // "can it set things on fire too" changed the effect list, the id was a slug of that too,
        // and the child got a third wand — which had ignite and had LOST the extra damage.
        ItemSpecStore.Spec both = wand().with(null, null, null, null, null,
                List.of("lightning", "ignite"), null);
        assertEquals("zonkbolt_lightning", both.id());
        assertEquals(List.of("lightning", "ignite"), both.effects());
    }

    @Test
    @DisplayName("parameters merge per EFFECT, so adding one does not silently disarm the other")
    void parametersMergePerEffect() {
        // "make it hurt way more" arrives as {"hurt":{"HEARTS":"12"}} on an item that also ignites.
        // A whole-object replace drops the burn's SECONDS while the item still CLAIMS both effects
        // — one of them quietly stops working, which is the failure no appearance check can see.
        ItemSpecStore.Spec two = new ItemSpecStore.Spec("id", "W", "wand", "purple", "held", 1,
                List.of("lightning", "ignite"),
                json("{\"lightning\":{\"HEARTS\":\"4\",\"RANGE\":\"10\"},"
                        + "\"ignite\":{\"SECONDS\":\"6\"}}"));
        JsonObject merged = two.with(null, null, null, null, null, null,
                json("{\"lightning\":{\"HEARTS\":\"12\"}}")).params();
        assertEquals("12", merged.getAsJsonObject("lightning").get("HEARTS").getAsString());
        // WITHIN the effect too, not only between effects. "Hurt more" names HEARTS and says
        // nothing about RANGE, and a wholesale replace of the lightning object leaves a wand that
        // hits harder and no longer reaches — a change nobody asked for, in the same call.
        assertEquals("10", merged.getAsJsonObject("lightning").get("RANGE").getAsString(),
                "the bolt lost its range while being asked only to hurt more");
        assertEquals("6", merged.getAsJsonObject("ignite").get("SECONDS").getAsString(),
                "the burn lost its duration and the item still says it burns");
    }

    @Test
    @DisplayName("he finds it by the name he actually says, not only by an id")
    void heFindsItByName() {
        ItemSpecStore.put(wand());
        assertNotNull(ItemSpecStore.find("zonkbolt_lightning"));
        assertNotNull(ItemSpecStore.find("Zonkbolt"), "the name he uses");
        assertNotNull(ItemSpecStore.find("zonkbolt"), "…in any case");
        assertNull(ItemSpecStore.find("mjolnir"), "and not something he has never owned");
    }

    @Test
    @DisplayName("a rename does not lose the thing — he still calls it by the NEW name")
    void aRenameKeepsItFindable() {
        // The id and the name drift apart the moment a refinement renames it, and the child goes on
        // saying whichever one he last heard. Both have to work or the wish after the rename makes
        // a second item all over again.
        ItemSpecStore.put(wand());
        ItemSpecStore.put(wand().with("Zonkbolt MEGA", null, null, null, null, null, null));
        assertEquals(1, ItemSpecStore.count(), "a rename is not a new item");
        assertNotNull(ItemSpecStore.find("Zonkbolt MEGA"));
        assertNotNull(ItemSpecStore.find("zonkbolt_lightning"), "and the id still finds it");
    }

    @Test
    @DisplayName("a damaged record is skipped, never fatal")
    void aDamagedRecordIsSkipped() {
        // Every store in this repo learned this the same way: a child must not lose every item
        // because one of them was written during a crash.
        assertNull(ItemSpecStore.Spec.fromJson(json("{\"id\":\"x\"}")));
        assertNull(ItemSpecStore.Spec.fromJson(json(
                "{\"id\":\"x\",\"name\":\"W\",\"icon\":\"wand\",\"colour\":\"red\","
                        + "\"shape\":\"held\",\"count\":1,\"effects\":[]}")),
                "an item that does nothing is not an item");
    }

    @Test
    @DisplayName("#1558: a PLAIN design has no effects and still reads back")
    void aPlainDesignReadsBack() {
        // make_item's `plain` shape mints exactly this; refusing it lost every plain design at the
        // next world load and left its watch card saying "—" four times.
        ItemSpecStore.Spec back = ItemSpecStore.Spec.fromJson(json(
                "{\"id\":\"best_pick_plain\",\"name\":\"Best Pick\",\"shape\":\"plain\","
                        + "\"count\":1,\"effects\":[],\"base_item\":\"minecraft:netherite_pickaxe\"}"));
        assertNotNull(back);
        assertEquals("minecraft:netherite_pickaxe", back.baseItem());
    }

    @Test
    @DisplayName("a design round-trips through JSON, because a refinement can come days later")
    void aDesignRoundTrips() {
        ItemSpecStore.Spec back = ItemSpecStore.Spec.fromJson(
                JsonParser.parseString(wand().toJson().toString()).getAsJsonObject());
        assertNotNull(back);
        assertEquals(wand().id(), back.id());
        assertEquals(wand().effects(), back.effects());
        assertEquals("4", back.params().getAsJsonObject("lightning").get("HEARTS").getAsString());
    }

    @Test
    @DisplayName("an edit may send ONE field — the schema must not force a full restatement")
    void anEditMaySendOneField() {
        // The measured regression this exists to stop. Marking every field required made the model
        // restate every field on every edit: of ~40 start_from calls across eight ladder cells,
        // nearly all sent name, icon, colour, shape, effect, count AND params. On the rung asking
        // for "a whole bunch of them" it restated the colour from memory as the ORIGINAL purple and
        // undid the green it had applied one rung earlier. Four of six cells reverted that way.
        var tool = new com.moddingfromamod.agent.tools.MakeItemTool();
        com.google.gson.JsonObject schema = tool.inputSchema();
        assertTrue(!schema.has("required")
                        || schema.getAsJsonArray("required").isEmpty(),
                "every field marked required means every field gets restated, and a restated field "
                        + "is a field remembered wrong: " + schema);
        // …and the rule itself still holds where it CAN be evaluated: with no start_from, a call
        // missing a field is refused, by the tool rather than by the schema.
        com.moddingfromamod.agent.ToolResult r = tool.call(new com.google.gson.JsonObject());
        assertTrue(r.isError(), "a bare make_item with nothing in it must still be refused");
    }

    @Test
    @DisplayName("it is bounded, so a runaway agent cannot fill a disk")
    void itIsBounded() {
        for (int i = 0; i < 400; i++) {
            ItemSpecStore.put(new ItemSpecStore.Spec("id_" + i, "W" + i, "wand", "red", "held", 1,
                    List.of("lightning"), new JsonObject()));
        }
        assertTrue(ItemSpecStore.count() <= 256, "unbounded: " + ItemSpecStore.count());
        assertNotNull(ItemSpecStore.get("id_399"), "and it keeps the NEWEST, not the oldest");
    }

    @Test
    @DisplayName("a build that fails puts the store back exactly as it was (#1044)")
    void aFailedBuildPutsTheStoreBack() {
        // A new design that fails to build leaves NOTHING behind...
        ItemSpecStore.Spec before = ItemSpecStore.own("zonkbolt_lightning");
        assertNull(before);
        ItemSpecStore.put(wand());
        ItemSpecStore.restore("zonkbolt_lightning", before);
        assertNull(ItemSpecStore.own("zonkbolt_lightning"), "a failed first build left its design stored");
        // ...and a failed EDIT leaves the design he already had, not the half-made one.
        ItemSpecStore.put(wand());
        ItemSpecStore.Spec had = ItemSpecStore.own("zonkbolt_lightning");
        ItemSpecStore.put(wand().with(null, null, "green", null, null, null, null));
        ItemSpecStore.restore("zonkbolt_lightning", had);
        assertEquals("purple", ItemSpecStore.own("zonkbolt_lightning").colour(),
                "a failed edit replaced the working design");
    }
}
