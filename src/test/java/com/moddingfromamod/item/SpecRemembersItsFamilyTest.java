package com.moddingfromamod.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A design remembers its own family —.</b>
 *
 * <p>Sibling of {@code VariantsSurviveRefinementTest}, which pins the same rule one layer up. This
 * is the layer that made the bug unfixable rather than merely present: the store had nowhere to
 * keep a family, so a refinement arriving days later could not have recovered one however carefully
 * the agent asked. Everything else about an item survives a restart; the flavours did not.
 */
class SpecRemembersItsFamilyTest {

    private static JsonArray flavours() {
        return JsonParser.parseString("""
                [{"name":"Cherry Fizzlepop Slushie","colour":"red"},
                 {"name":"Lime Fizzlepop Slushie","colour":"lime"},
                 {"name":"Grape Fizzlepop Slushie","colour":"purple"},
                 {"name":"Blueberry Fizzlepop Slushie","colour":"blue"}]""").getAsJsonArray();
    }

    private static ItemSpecStore.Spec family() {
        return new ItemSpecStore.Spec("cherry_fizzlepop_slushie_heal", "Cherry Fizzlepop Slushie",
                "cup", "red", "held", 4, List.of("heal"),
                JsonParser.parseString("{\"heal\":{\"SECONDS\":\"6\"}}").getAsJsonObject(),
                flavours());
    }

    @Test
    @DisplayName("the family survives being written down and read back")
    void theFamilyRoundTrips() {
        ItemSpecStore.Spec back = ItemSpecStore.Spec.fromJson(family().toJson());
        assertNotNull(back);
        assertEquals(4, back.variants().size(), "a saved design forgot its own flavours");
        assertEquals("Grape Fizzlepop Slushie",
                back.variants().get(2).getAsJsonObject().get("name").getAsString());
    }

    @Test
    @DisplayName("a design written before families existed still loads, as a family of one")
    void aPreFamilyRecordStillLoads() {
        // Every item already in a child's world was written without this field. Refusing one, or
        // crashing on it, would lose him the wand he made last week — the rule this store has held
        // since it was written: one damaged record is skipped, never fatal.
        JsonObject old = family().toJson();
        old.remove("variants");
        ItemSpecStore.Spec back = ItemSpecStore.Spec.fromJson(old);
        assertNotNull(back, "a pre-#269 record must still load");
        assertTrue(back.variants().isEmpty(), "and an absent family must mean no family");
    }

    @Test
    @DisplayName("a damaged family is dropped, not carried as half a set")
    void aDamagedFamilyIsDropped() {
        // Half a family is worse than none: it renders a give block missing members the child can
        // see are missing, with nothing anywhere saying why.
        JsonObject bent = family().toJson();
        bent.addProperty("variants", "cherry, lime");
        ItemSpecStore.Spec back = ItemSpecStore.Spec.fromJson(bent);
        assertNotNull(back, "the rest of the design is still good and he should keep it");
        assertTrue(back.variants().isEmpty());
    }

    @Test
    @DisplayName("`with` keeps the family, like every other field it does not mention")
    void withKeepsTheFamily() {
        ItemSpecStore.Spec renamed =
                family().with("Fizzlepop MEGA", null, null, null, null, null, null);
        assertEquals(4, renamed.variants().size(),
                "a rename is not a reason to lose three flavours");
    }
}
