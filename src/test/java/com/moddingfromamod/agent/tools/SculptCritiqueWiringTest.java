package com.moddingfromamod.agent.tools;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Is the shape gate actually connected to what the agent reads?</b>
 *
 * <p>{@code ShapeDriftTest} proves the <i>rule</i>. This proves the <i>wiring</i>, and they are not
 * the same claim — a gate whose class compiles, whose unit tests pass, and whose call site looks
 * right can still contribute nothing to the string the agent is handed.
 *
 * <p><b>Why this test exists at all.</b> After adding the gate, the obvious check was to run six
 * real ladder cells and grep each client's {@code run.log} for the warning. All six returned zero.
 * That reads like "the gate never fired", and it was nearly recorded as such. It means nothing:
 * the gate is silent whenever nothing was lost — which is every healthy run — and, more to the
 * point, {@code run.log} does not carry tool results at all. Grepping it for {@code MISSED}, which
 * certainly does fire in real runs, also returns zero. The instrument was dead, and a negative from
 * a dead instrument is not evidence in either direction.
 */
class SculptCritiqueWiringTest {

    private static List<CreatureSculpt.Part> parts(String json) {
        return CreatureSculpt.parseParts(JsonParser.parseString(json).getAsJsonArray());
    }

    private static List<CreatureSculpt.Part> before() {
        return parts("""
            [{"name":"body","from":[-4,0,-4],"to":[4,16,4],"colour":"orange"},
             {"name":"head","from":[-5,16,-5],"to":[5,26,5],"colour":"orange"},
             {"name":"eye","from":[-2,20,-6],"to":[2,24,-5],"colour":"purple"},
             {"name":"wingL","from":[-8,10,-1],"to":[-4,16,1],"colour":"black"},
             {"name":"wingR","from":[4,10,-1],"to":[8,16,1],"colour":"black"}]
            """);
    }

    /** A from-scratch rebuild: no start_from, and only the parts the child re-mentioned. */
    private static JsonObject rebuildArgs() {
        return JsonParser.parseString("""
            {"id":"snorble","palette":[{"colour":"orange","percent":100}],
             "parts":[{"name":"body","from":[-4,0,-4],"to":[4,18,4],"colour":"orange"},
                      {"name":"head","from":[-5,18,-5],"to":[5,28,5],"colour":"orange"}]}
            """).getAsJsonObject();
    }

    @Test
    @DisplayName("a rebuild's warning REACHES the text the agent is handed")
    void theWarningIsInTheCritique() {
        String critique = SculptCreatureTool.critiqueFor(
                rebuildArgs(), before(), parts(rebuildArgs().getAsJsonArray("parts").toString()),
                "make him a bit taller", "");
        assertTrue(critique.contains("REPLACED THE CREATURE"),
                "the shape gate must contribute to the critique string, not merely exist: "
                        + critique);
        assertTrue(critique.contains("wingL") && critique.contains("eye"),
                "…and it must still NAME what went missing once assembled: " + critique);
    }

    @Test
    @DisplayName("a clean edit adds nothing — the gate is silent, not chatty")
    void aGoodEditIsNotWarnedAbout() {
        List<CreatureSculpt.Part> after = new ArrayList<>(before());
        after.addAll(parts("""
            [{"name":"tail","from":[-1,2,4],"to":[1,6,10],"colour":"orange"}]
            """));
        JsonObject args = JsonParser.parseString("""
            {"id":"snorble","start_from":"snorble",
             "palette":[{"colour":"orange","percent":100}],
             "parts":[{"name":"tail","from":[-1,2,4],"to":[1,6,10],"colour":"orange"}]}
            """).getAsJsonObject();
        String critique = SculptCreatureTool.critiqueFor(args, before(), after, "give him a tail",
                "");
        assertFalse(critique.contains("REPLACED THE CREATURE"),
                "nothing was lost, so the agent must not be told anything was: " + critique);
        assertFalse(critique.contains("did not ask for"),
                "…and not the gentler line either: " + critique);
    }

    @Test
    @DisplayName("a creation is not compared against a previous version it never had")
    void aCreationIsSilent() {
        JsonObject args = rebuildArgs();
        String critique = SculptCreatureTool.critiqueFor(
                args, List.of(), parts(args.getAsJsonArray("parts").toString()),
                "make me a Snorble", "");
        assertFalse(critique.contains("REPLACED THE CREATURE"), critique);
    }

    // ----: kits are idempotent on an EDIT ------------------------------------------------

    private static CreatureSculpt.Part kitPart(String name) {
        return new CreatureSculpt.Part(name, "body", new float[]{0, 0, 0},
                new float[]{0, 0, 0}, new float[]{2, 2, 2}, 0xEEEEDD);
    }

    private static JsonObject kitArgs(String startFrom, String kit) {
        JsonObject o = new JsonObject();
        if (startFrom != null) {
            o.addProperty("start_from", startFrom);
        }
        com.google.gson.JsonArray kits = new com.google.gson.JsonArray();
        JsonObject k = new JsonObject();
        k.addProperty("kit", kit);
        k.addProperty("count", 7);
        kits.add(k);
        o.add("kits", kits);
        return o;
    }

    @Test
    @DisplayName("re-asking for a kit the creature already has is refused on an edit")
    void aRepeatedKitOnAnEditIsDropped() {
        // The measured case: Spikor shipped with 15 kit_spike_row parts because the agent asked
        // for `spike_row count:7` on the first sculpt and again on an inheriting one.
        var base = java.util.List.of(kitPart("kit_spike_row12"), kitPart("kit_spike_row13"));
        assertTrue(SculptCreatureTool.duplicateKits(kitArgs("spikor", "spike_row"), base)
                .contains("spike_row"));
        assertEquals(0, SculptCreatureTool
                .withoutDuplicateKits(kitArgs("spikor", "spike_row"), base).size());
    }

    @Test
    @DisplayName("asking for a kit the creature does NOT have still works")
    void aNewKitOnAnEditIsKept() {
        // P6 -- the guard must be silent on the normal case, or it blocks real refinements.
        var base = java.util.List.of(kitPart("kit_horn3"));
        assertTrue(SculptCreatureTool.duplicateKits(kitArgs("spikor", "spike_row"), base).isEmpty());
        assertEquals(1, SculptCreatureTool
                .withoutDuplicateKits(kitArgs("spikor", "spike_row"), base).size());
    }

    @Test
    @DisplayName("a FRESH sculpt may repeat a kit — two rows of spikes is a real thing to want")
    void aRepeatedKitOnACreationIsAllowed() {
        // Only an EDIT is unambiguous. Without start_from the agent is describing the whole
        // creature in one call, and a repeat there is an explicit choice.
        var base = java.util.List.of(kitPart("kit_spike_row12"));
        assertTrue(SculptCreatureTool.duplicateKits(kitArgs(null, "spike_row"), base).isEmpty());
    }

    // ----: truncation must never be silent ------------------------------------------------

    private static List<CreatureSculpt.Part> nParts(int n) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(kitPart("box" + i));
        }
        return out;
    }

    @Test
    @DisplayName("hitting the ceiling is reported, not swallowed")
    void theCeilingIsReported() {
        String note = SculptCreatureTool.truncationNote(new JsonObject(),
                nParts(CreatureSculpt.MAX_PARTS));
        assertTrue(note.contains("CEILING"), note);
        assertTrue(note.contains("DROPPED"), "it must say something was lost: " + note);
    }

    @Test
    @DisplayName("it says how many were sent when the agent overshot")
    void itNamesHowManyWereSent() {
        JsonObject args = new JsonObject();
        com.google.gson.JsonArray sent = new com.google.gson.JsonArray();
        for (int i = 0; i < CreatureSculpt.MAX_PARTS + 12; i++) {
            sent.add(new JsonObject());
        }
        args.add("parts", sent);
        String note = SculptCreatureTool.truncationNote(args, nParts(CreatureSculpt.MAX_PARTS));
        assertTrue(note.contains(String.valueOf(CreatureSculpt.MAX_PARTS + 12)),
                "the agent has to know how far over it went: " + note);
    }

    @Test
    @DisplayName("a creature under the ceiling is not nagged")
    void underTheCeilingIsSilent() {
        // P6 -- almost every creature is under the cap, so firing here would make the note noise.
        assertEquals("", SculptCreatureTool.truncationNote(new JsonObject(),
                nParts(CreatureSculpt.MAX_PARTS - 1)));
    }

    @Test
    @DisplayName("the cap is in the schema, so the agent knows it exists at all")
    void theCapIsAdvertised() {
        // It could not know it had hit a limit nobody told it about.
        assertTrue(new SculptCreatureTool().inputSchema().toString()
                .contains(String.valueOf(CreatureSculpt.MAX_PARTS)));
    }

    @Test
    @DisplayName("a part too thin to draw is named back, with what to change")
    void thinPartsAreReported() {
        // The REAL case, from a real Spikor (v3_fixed6): pupils authored 0.7 deep. parseParts
        // drops them -- correctly, a flat box draws nothing -- and said nothing, so the agent
        // believed it had drawn pupils onto a creature that has none.
        JsonObject args = JsonParser.parseString("""
                {"parts": [
                  {"name":"head",  "from":[-8,40,-8], "to":[8,56,8],       "colour":"green"},
                  {"name":"pupilL","from":[-5.2,45.6,9.5],"to":[-3.6,48,10.2],"colour":"red"}
                ]}""").getAsJsonObject();
        String note = SculptCreatureTool.thinPartsNote(args);
        assertTrue(note.contains("pupilL"), "must name the part that vanished: " + note);
        assertTrue(note.contains("deep"), "must say WHICH axis was too small: " + note);
        assertTrue(note.contains("at least 1 unit"), "must say what to change: " + note);
        assertTrue(!note.contains("head"), "a healthy part must not be listed: " + note);
    }

    @Test
    @DisplayName("nothing is said when every box is drawable")
    void thickPartsAreSilent() {
        JsonObject args = JsonParser.parseString("""
                {"parts": [{"name":"head","from":[-8,40,-8],"to":[8,56,8],"colour":"green"}]}""")
                .getAsJsonObject();
        assertEquals("", SculptCreatureTool.thinPartsNote(args));
    }

    @Test
    @DisplayName("a box sent high corner first is not called thin (#1659)")
    void anInvertedBoxIsNotThin() {
        JsonObject args = JsonParser.parseString("""
                {"parts": [{"name":"hornL","from":[-6,26,-26],"to":[-3,29,-38],"colour":"white"}]}""")
                .getAsJsonObject();
        assertEquals("", SculptCreatureTool.thinPartsNote(args));
    }
}
