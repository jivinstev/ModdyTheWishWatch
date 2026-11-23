package com.moddingfromamod.agent.tools;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.item.ItemSpecStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Is the items drift gate actually WIRED?</b>
 *
 * <p>Separate from {@code ItemDriftTest} on purpose, because that tests the <i>rule</i> and nothing
 * in it tests that the rule reaches the agent. This distinction is not theoretical here: unwiring
 * the creature shape gate from its critique left {@code ShapeDriftTest} <b>6/6 green</b>, and the
 * obvious live check — grepping a real client's {@code run.log} for the warning — returns zero
 * whether the gate is silent, unwired or absent, because {@code run.log} carries no tool results.
 *
 * <p>So this asserts on the string {@code make_item} really builds.
 */
class ItemCritiqueWiringTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static ItemSpecStore.Spec spec(List<String> effects) {
        return new ItemSpecStore.Spec("zonkbolt", "Zonkbolt", "wand", "purple", "held", 1,
                effects, json("{\"lightning\":{\"HEARTS\":\"4\"}}"));
    }

    @Test
    @DisplayName("a dropped effect reaches the string the agent reads")
    void aDroppedEffectReachesTheAgent() {
        String note = MakeItemTool.critiqueFor(spec(List.of("lightning")), spec(List.of("ignite")));
        assertTrue(note.contains("no longer does lightning"),
                "the gate is not wired into make_item's result: " + note);
        assertTrue(note.startsWith("\n"), "it must be separated from the success text: " + note);
    }

    @Test
    @DisplayName("a good edit adds nothing, so the note stays worth reading")
    void aGoodEditAddsNothing() {
        assertEquals("", MakeItemTool.critiqueFor(spec(List.of("lightning")),
                spec(List.of("lightning", "ignite"))));
    }

    @Test
    @DisplayName("a brand-new item is not critiqued against nothing")
    void aBrandNewItemIsNotCritiqued() {
        // The guarantee here is DRIFT, not silence, and it survives: a creation has nothing
        // to lose, so it must never be told it lost something. It used to be asserted as `equals
        // ""` because silence was the only way of not reporting drift; a creation is now DESCRIBED
        // instead (WISH_LINTERS P3), and the original guarantee is asserted directly.
        String note = MakeItemTool.critiqueFor(null, spec(List.of("lightning")));
        assertTrue(!note.contains("no longer does"), "a creation cannot have LOST anything: " + note);
        assertTrue(!note.contains("REPLACED"), "nor replaced anything: " + note);
        assertTrue(note.contains("WHAT YOU ACTUALLY MADE"), "but it must not be silent: " + note);
    }

    @Test
    @DisplayName("outside a run it still works, rather than throwing on a missing wish")
    void outsideARunItStillWorks() {
        // Every gate in this repo is called from tests and from tools alike, and AgentContext is
        // null in the first case. A gate that throws there is a gate nobody can unit test.
        assertEquals("", MakeItemTool.childWordsFor());
        assertTrue(!MakeItemTool.critiqueFor(spec(List.of("lightning")), spec(List.of("ignite")))
                .isEmpty());
    }

    // ================= the NUMBERS half, which is a second gate =================

    private static ItemSpecStore.Spec tuned(String params) {
        return new ItemSpecStore.Spec("zonkbolt", "Zonkbolt", "wand", "purple", "held", 1,
                List.of("lightning", "ignite"), json(params));
    }

    @Test
    @DisplayName("a rewritten number reaches the string the agent reads")
    void aRewrittenNumberReachesTheAgent() {
        // The other half of an item's behaviour. ItemDrift cannot see this — a changed value is
        // what a refinement IS — so if EffectDrift is not wired in here, the whole class of
        // "the burn he asked for quietly went back to 8" is invisible with every test green.
        String note = MakeItemTool.critiqueFor(
                tuned("{\"lightning\":{\"STRIKES\":\"1\"},\"ignite\":{\"SECONDS\":\"6\"}}"),
                tuned("{\"lightning\":{\"STRIKES\":\"8\"},\"ignite\":{\"SECONDS\":\"8\"}}"));
        assertTrue(note.contains("ignite"),
                "the numbers gate is not wired into make_item's result: " + note);
        assertTrue(note.startsWith("\n"), "it must be separated from the success text: " + note);
    }

    @Test
    @DisplayName("both gates can speak at once, and are not run together into one sentence")
    void bothGatesCanSpeakAtOnce() {
        // An edit that drops an effect AND rewrites a number on another one. Both are real and
        // they are different problems; concatenating them without a break reads as one.
        ItemSpecStore.Spec before = new ItemSpecStore.Spec("zonkbolt", "Zonkbolt", "wand",
                "purple", "held", 1, List.of("lightning", "ignite", "freeze"),
                json("{\"lightning\":{\"STRIKES\":\"1\"},\"ignite\":{\"SECONDS\":\"6\"},"
                        + "\"freeze\":{\"SECONDS\":\"30\"}}"));
        ItemSpecStore.Spec after = new ItemSpecStore.Spec("zonkbolt", "Zonkbolt", "wand",
                "purple", "held", 1, List.of("lightning", "ignite"),
                json("{\"lightning\":{\"STRIKES\":\"1\"},\"ignite\":{\"SECONDS\":\"8\"}}"));
        String note = MakeItemTool.critiqueFor(before, after);
        assertTrue(note.contains("no longer does freeze"), "the lost effect must be named: " + note);
        assertTrue(note.contains("ignite"), "the rewritten number must be named too: " + note);
        assertTrue(note.contains("\n\n⚠ THIS CHANGED A NUMBER"),
                "the two findings must be separate paragraphs: " + note);
    }

    @Test
    @DisplayName("a clean edit still adds nothing at all")
    void aCleanEditIsStillSilent() {
        assertEquals("", MakeItemTool.critiqueFor(
                tuned("{\"lightning\":{\"STRIKES\":\"1\"},\"ignite\":{\"SECONDS\":\"6\"}}"),
                tuned("{\"lightning\":{\"STRIKES\":\"1\"},\"ignite\":{\"SECONDS\":\"6\"}}")));
    }

    // ----: a CREATION must be described, not met with silence (WISH_LINTERS P3) ----------

    @Test
    @DisplayName("a newly made item is described, not silently accepted")
    void aCreationIsDescribed() {
        // before == null is a creation. Both drift gates correctly return "" here, which used to
        // mean the agent was told nothing at all about the item it had just made.
        String note = MakeItemTool.critiqueFor(null, spec(List.of("lightning")));
        assertTrue(note.contains("WHAT YOU ACTUALLY MADE"),
                "a creation must be described: " + note);
        assertTrue(note.contains("lightning"), "the resolved effects must be named: " + note);
        assertTrue(note.startsWith("\n"), "it must be separated from the success text: " + note);
    }

    @Test
    @DisplayName("the derived NUMBERS reach the agent, because it never chose them")
    void theNumbersReachTheAgent() {
        // The child said "really far away"; the item has a specific value derived from a scale.
        // Closing that loop is the whole point -- the agent cannot check the wish against the
        // item without seeing the number.
        String note = MakeItemTool.critiqueFor(null, spec(List.of("lightning")));
        assertTrue(note.contains("HEARTS"), "the parameters must be named: " + note);
        assertTrue(note.contains("4"), "and their values: " + note);
    }

    @Test
    @DisplayName("an item that does nothing says so")
    void anInertItemIsFlagged() {
        // An effect-less item looks right in the hand and does nothing, which to a child is
        // indistinguishable from a wish that was never granted.
        String note = MakeItemTool.critiqueFor(null, spec(List.of()));
        assertTrue(note.contains("DOES NOTHING"), note);
    }

    @Test
    @DisplayName("an EDIT still gets drift, not a description")
    void anEditIsStillGradedForDrift() {
        // The creation path must not swallow the edit path: a refinement that drops an effect is
        // the failure ItemDrift exists for, and describing the result instead would hide it.
        String note = MakeItemTool.critiqueFor(spec(List.of("lightning")), spec(List.of("ignite")));
        assertTrue(note.contains("no longer does lightning"), note);
        assertTrue(!note.contains("WHAT YOU ACTUALLY MADE"),
                "an edit must be graded, not merely described: " + note);
    }

    @Test
    @DisplayName("the four always-required fields SAY they are required, where the agent reads them")
    void theRequiredFieldsAnnounceThemselvesInTheSchema() {
        // MEASURED, over ten real item wishes seeded into corpus/real-tool-calls.jsonl: 10 of 14
        // make_item calls FAILED, every wish needed two or more calls to make one item, and the
        // commonest single cause was missing fields -- `icon` and `colour` together, 4 times out of
        // 4. The agent was learning a requirement by being refused, once per wish, a turn each time.
        //
        // It cannot be fixed with the schema's `required` array: with `start_from` these fields are
        // INHERITED, so declaring them required would force every refinement to resend fields it is
        // not changing. The requirement is genuinely conditional, so it lives in the descriptions --
        // which is the only place a model reads while filling one field in.
        JsonObject props = new MakeItemTool().inputSchema()
                .getAsJsonObject("properties");
        for (String field : List.of("name", "icon", "colour", "shape")) {
            String desc = props.getAsJsonObject(field).get("description").getAsString();
            assertTrue(desc.startsWith("REQUIRED"),
                    field + " is enforced by missingFields() but its description never says so, so "
                            + "the agent can only discover it by being refused: " + desc);
            assertTrue(desc.contains("start_from"),
                    field + " must also say the requirement lifts under start_from, or a refinement "
                            + "will resend every field it is not changing");
        }
    }

    @Test
    @DisplayName("...and the schema still declares nothing required, which is deliberate")
    void theSchemaDoesNotForceFieldsThatStartFromInherits() {
        // The other half of the same rule, pinned so a later reader "fixing" the omission above by
        // adding Schemas.required(...) breaks a test rather than breaking refinement. VariantsSurvive
        // RefinementTest covers what that would cost; this states the intent.
        JsonObject schema = new MakeItemTool().inputSchema();
        assertTrue(!schema.has("required") || schema.getAsJsonArray("required").isEmpty(),
                "make_item must not declare `required`: start_from inherits these fields, and a "
                        + "required array would force a refinement to resend what it is not changing");
    }
}
