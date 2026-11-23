package com.moddingfromamod.runtime.gate;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.item.ItemSpecStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The item-effect class's R4/R5 rule: a number he chose, rewritten on a wish about something else.
 *
 * <p>Every case here is shaped after the real {@code frazzlepop} ladder, because the failure it is
 * aimed at is invisible in the one place a child would look — the item is the same wand, the same
 * colour, with the same three effects listed on it.
 */
class EffectDriftTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    /** The Frazzlepop as the ladder leaves it at rung 3: frozen 30s, one bolt, burning 6s. */
    private static ItemSpecStore.Spec frazzlepop(String params) {
        return new ItemSpecStore.Spec("frazzlepop_freeze_lightning", "Frazzlepop", "wand", "cyan",
                "held", 1, List.of("freeze", "lightning", "ignite"), json(params));
    }

    private static final String RUNG3 =
            "{\"freeze\":{\"SECONDS\":\"30\"},\"lightning\":{\"STRIKES\":\"1\"},"
                    + "\"ignite\":{\"SECONDS\":\"6\"}}";

    // ================= the failure it exists for =================

    @Test
    @DisplayName("a burn he set three rungs ago, rewritten on a wish about the lightning")
    void aNumberRewrittenOnAWishAboutSomethingElseIsReported() {
        // He asked for six seconds of fire at rung 3. This rung is about the lightning, and the
        // burn comes back as 8 — the number the tool's own description suggests to a model filling
        // the field from memory. Three effects before, three after; nothing looks any different.
        String note = EffectDrift.between(
                frazzlepop(RUNG3),
                frazzlepop("{\"freeze\":{\"SECONDS\":\"30\"},\"lightning\":{\"STRIKES\":\"8\"},"
                        + "\"ignite\":{\"SECONDS\":\"8\"}}"),
                "make its lightning way stronger");
        assertTrue(note.contains("ignite"), "it must name the effect that moved: " + note);
        assertTrue(note.contains("6") && note.contains("8"),
                "it must say what the number was and what it became: " + note);
        assertFalse(note.contains("lightning's STRIKES"),
                "the lightning IS the edit and must not be reported: " + note);
    }

    @Test
    @DisplayName("two collateral changes are both named, not just the first")
    void everyUnaskedChangeIsNamed() {
        String note = EffectDrift.between(
                frazzlepop(RUNG3),
                frazzlepop("{\"freeze\":{\"SECONDS\":\"5\"},\"lightning\":{\"STRIKES\":\"8\"},"
                        + "\"ignite\":{\"SECONDS\":\"8\"}}"),
                "make its lightning way stronger");
        assertTrue(note.contains("freeze") && note.contains("ignite"),
                "a restatement can undo more than one wish at once: " + note);
    }

    // ================= what must stay silent, or the gate stops being read =================

    @Test
    @DisplayName("the effect he named is the edit, and is never reported")
    void theEditItselfIsNotDrift() {
        assertEquals("", EffectDrift.between(
                frazzlepop(RUNG3),
                frazzlepop("{\"freeze\":{\"SECONDS\":\"30\"},\"lightning\":{\"STRIKES\":\"1\"},"
                        + "\"ignite\":{\"SECONDS\":\"20\"}}"),
                "can it burn them for way longer? make the ignite last ages"));
    }

    @Test
    @DisplayName("\"make it stronger\" names nothing, so the single change that happened is the edit")
    void theOnlyChangeIsTheEditEvenWhenHeNamesNothing() {
        // The commonest phrasing a seven-year-old uses. Refusing to excuse it would report every
        // such wish as drift, which is how a gate becomes noise and stops being read.
        assertEquals("", EffectDrift.between(
                frazzlepop(RUNG3),
                frazzlepop("{\"freeze\":{\"SECONDS\":\"30\"},\"lightning\":{\"STRIKES\":\"9\"},"
                        + "\"ignite\":{\"SECONDS\":\"6\"}}"),
                "make it way stronger"));
    }

    @Test
    @DisplayName("…but two changes under a vague wish cannot both be the edit")
    void twoChangesUnderAVagueWishAreNotBothTheEdit() {
        String note = EffectDrift.between(
                frazzlepop(RUNG3),
                frazzlepop("{\"freeze\":{\"SECONDS\":\"30\"},\"lightning\":{\"STRIKES\":\"9\"},"
                        + "\"ignite\":{\"SECONDS\":\"8\"}}"),
                "make it way stronger");
        assertFalse(note.isEmpty(),
                "only one of them can be what he meant, so the other is worth a sentence");
    }

    @Test
    @DisplayName("an edit that changes nothing says nothing")
    void anUnchangedItemIsSilent() {
        assertEquals("", EffectDrift.between(frazzlepop(RUNG3), frazzlepop(RUNG3),
                "give me loads of them"));
    }

    @Test
    @DisplayName("a brand-new item has no earlier number to lose")
    void aNewItemIsNotCritiqued() {
        assertEquals("", EffectDrift.between(null, frazzlepop(RUNG3), "i want a frazzlepop"));
    }

    @Test
    @DisplayName("…and it cannot be the edit when the same call also changed the effect list")
    void theOnlyChangeIsNotTheEditWhenTheListMovedToo() {
        // Found by the WIRING test, not by this file: an edit that drops `freeze` and rewrites
        // `ignite`'s burn on the way past. The burn was the only NUMBER that moved, so the first
        // version of the rule excused it as "plainly the edit" — reporting a lost effect while
        // staying silent about a lost wish. A call that changes the list was evidently not about
        // tuning a number, so the excuse does not apply to it.
        ItemSpecStore.Spec after = new ItemSpecStore.Spec("frazzlepop_freeze_lightning",
                "Frazzlepop", "wand", "cyan", "held", 1, List.of("lightning", "ignite"),
                json("{\"freeze\":{\"SECONDS\":\"30\"},\"lightning\":{\"STRIKES\":\"1\"},"
                        + "\"ignite\":{\"SECONDS\":\"8\"}}"));
        String note = EffectDrift.between(frazzlepop(RUNG3), after, "take the freeze off");
        assertTrue(note.contains("ignite"),
                "a number that moved beside a list change is collateral, not the edit: " + note);
    }

    // ================= the boundary with ItemDrift =================

    @Test
    @DisplayName("an effect that was removed entirely is ItemDrift's finding, not this one")
    void aRemovedEffectIsNotReportedTwice() {
        // Said twice, in two voices, it reads as two different problems — and the agent then has
        // to work out that one item lost one thing.
        ItemSpecStore.Spec after = new ItemSpecStore.Spec("frazzlepop_freeze_lightning",
                "Frazzlepop", "wand", "cyan", "held", 1, List.of("freeze", "lightning"),
                json("{\"freeze\":{\"SECONDS\":\"30\"},\"lightning\":{\"STRIKES\":\"1\"}}"));
        assertEquals("", EffectDrift.between(frazzlepop(RUNG3), after, "take the fire off"));
    }

    @Test
    @DisplayName("a parameter blanked rather than changed is also ItemDrift's")
    void aBlankedParameterIsNotReportedTwice() {
        ItemSpecStore.Spec after = frazzlepop(
                "{\"freeze\":{\"SECONDS\":\"30\"},\"lightning\":{\"STRIKES\":\"1\"},"
                        + "\"ignite\":{}}");
        assertEquals("", EffectDrift.between(frazzlepop(RUNG3), after, "make it freeze longer"));
    }

    // ================= the whole-word rule =================

    @Test
    @DisplayName("an effect id inside a longer word does not count as naming it")
    void mentionsIsWholeWordOnly() {
        assertTrue(EffectDrift.mentions("make its lightning way stronger", "lightning"));
        assertTrue(EffectDrift.mentions("MAKE THE FREEZE LONGER", "freeze"));
        assertFalse(EffectDrift.mentions("put it by the fireplace", "place"),
                "\"fireplace\" does not name the place effect");
        assertFalse(EffectDrift.mentions("send it hurtling into the sky", "hurt"),
                "\"hurtling\" does not name the hurt effect");
        assertFalse(EffectDrift.mentions("", "hurt"));
        assertFalse(EffectDrift.mentions("make it stronger", null));
    }

    // ================= it cannot be talked out of the finding =================

    @Test
    @DisplayName("nothing in the wish text can silence a change to an effect it never mentions")
    void aWordyWishCannotExcuseACollateralChange() {
        // an earlier issue's lesson in its item form: an agent that could silence this by SAYING something
        // would silence it instead of keeping the number. The only input is the artifact's own
        // previous version and the child's words — and his words never mention the burn.
        String note = EffectDrift.between(
                frazzlepop(RUNG3),
                frazzlepop("{\"freeze\":{\"SECONDS\":\"30\"},\"lightning\":{\"STRIKES\":\"8\"},"
                        + "\"ignite\":{\"SECONDS\":\"8\"}}"),
                "make its lightning way stronger and i checked everything else is still fine "
                        + "and tidy and correct and preserved exactly as it was");
        assertTrue(note.contains("ignite"), "reassurance is not preservation: " + note);
    }
}
