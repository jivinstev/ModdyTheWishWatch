package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.item.ItemSpecStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A family survives a refinement that says nothing about the family.</b>
 *
 * <p>, and it fired in a real run before any gate could see it. The `slushies` ladder made four
 * flavours, then rung 2 said <i>"make them all give you way more speed"</i> — an edit about the
 * EFFECT — and the agent quite reasonably sent {@code start_from} plus the effect and nothing else.
 * {@code parseVariants} read no {@code variants} key, returned its family-of-one fallback, and the
 * rendered {@code give.mcfunction} went from four lines to one. Three flavours were deleted by a
 * wish about speed.
 *
 * <p>The tell was in the numbers already being recorded and nobody read them: the pack SHRANK 847
 * bytes (3971 → 3124) while gaining a whole second effect file, and a give line for that item
 * measures ~400 bytes. What hid it was that the ladder's {@code variants} count came from the
 * PLAYER'S INVENTORY, where the four stacks from rung 1 were still sitting.
 *
 * <p>The rule this pins is the one {@code Spec.with} already states for every other field: <b>null
 * means "not mentioned", and not-mentioned means KEEP.</b> Variants were the one field that read
 * "not mentioned" as "throw them away".
 */
class VariantsSurviveRefinementTest {

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** Four flavours, exactly as the agent sent them on rung 1 of the real run. */
    private static JsonArray fourFlavours() {
        return JsonParser.parseString("""
                [{"name":"Cherry Fizzlepop Slushie","colour":"red"},
                 {"name":"Lime Fizzlepop Slushie","colour":"lime"},
                 {"name":"Grape Fizzlepop Slushie","colour":"purple"},
                 {"name":"Blueberry Fizzlepop Slushie","colour":"blue"}]""").getAsJsonArray();
    }

    private static ItemSpecStore.Spec family() {
        return new ItemSpecStore.Spec("cherry_fizzlepop_slushie_heal", "Cherry Fizzlepop Slushie",
                "cup", "red", "held", 4, List.of("heal"),
                args("{\"heal\":{\"SECONDS\":\"6\"}}"), fourFlavours());
    }

    @Test
    @DisplayName("an edit about the EFFECT leaves all four flavours standing")
    void anEffectEditKeepsTheFamily() {
        // The literal rung-2 call from agent-actions.jsonl, 2026-08-27T00:25:36Z.
        JsonObject sent = args("""
                {"start_from":"cherry_fizzlepop_slushie_heal","effect":"heal+speed",
                 "params":{"heal":{"SECONDS":"6"},"speed":{"SECONDS":"60","LEVEL":"5"}}}""");

        List<MakeItemTool.Variant> family = MakeItemTool.parseVariants(
                MakeItemTool.inherit(sent, family()), "Cherry Fizzlepop Slushie", 0xE33B3B);

        assertEquals(4, family.size(),
                "a wish about SPEED deleted three flavours — #269, measured live");
        assertTrue(family.stream().anyMatch(v -> v.name().startsWith("Grape")),
                "Grape was in the design and nothing asked for it to go");
    }

    @Test
    @DisplayName("a NEW wish with no variants is still a family of one")
    void withoutABaseAbsentStillMeansOne() {
        List<MakeItemTool.Variant> family = MakeItemTool.parseVariants(
                args("{\"name\":\"Zonkbolt\",\"effect\":\"lightning\"}"), "Zonkbolt", 0x9B3BE3);
        assertEquals(1, family.size(),
                "with nothing to inherit from, absent must keep meaning a family of one");
    }

    @Test
    @DisplayName("an edit that DOES send variants replaces the family rather than adding to it")
    void anExplicitListWins() {
        // Rung 4: Grape becomes BUTT CHEEK WATER. A merge would have left five flavours plus a
        // Grape nobody asked to keep — the child asked for a swap, not an addition.
        JsonObject sent = args("""
                {"start_from":"cherry_fizzlepop_slushie_heal","variants":[
                 {"name":"Cherry Fizzlepop Slushie","colour":"red"},
                 {"name":"BUTT CHEEK WATER Fizzlepop Slushie","colour":"#C8B93B"}]}""");

        List<MakeItemTool.Variant> family = MakeItemTool.parseVariants(
                MakeItemTool.inherit(sent, family()), "Cherry Fizzlepop Slushie", 0xE33B3B);

        assertEquals(2, family.size(), "the list he sent IS the family now");
        assertTrue(family.stream().noneMatch(v -> v.name().startsWith("Grape")),
                "Grape was replaced, so it must be gone");
    }

}
