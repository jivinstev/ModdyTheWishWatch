package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Is the palette declaration actually offered to the agent?</b>
 *
 * <p>A guard against the failure that has now happened twice in this project: guidance that is
 * written, committed, believed, and never delivered. The creature lessons were attached to
 * {@code sculpt_creature}'s RESULT for weeks, so they arrived <i>after</i> the sculpt and the agent
 * never read them before building — found only by running live, not by any amount of reading.
 *
 * <p>These are cheap assertions on the schema and the description. They cannot prove the agent
 * <i>uses</i> the field, but they do prove it was offered one, which is the part that silently
 * regresses when a description gets rewritten.
 */
class SculptPaletteWiringTest {

    private static final SculptCreatureTool TOOL = new SculptCreatureTool();

    @Test
    @DisplayName("the agent is offered a place to declare its intended palette")
    void schemaOffersPalette() {
        JsonObject props = TOOL.inputSchema().getAsJsonObject("properties");
        assertTrue(props.has("palette"),
                "sculpt_creature must expose 'palette' or the whole named tier goes ungraded");
    }

    @Test
    @DisplayName("palette is REQUIRED, so the grader always has a declaration to check")
    void paletteIsRequired() {
        // CORRECTION, on the record. This was first justified as "the agent declared a palette in
        // 0 of 30 live sculpts". That was FALSE. The census read `args` from the action log, which
        // is CLIPPED at 600 characters, and a sculpt call is mostly one enormous `parts` array --
        // so every field after it fell off the end in 57 of 72 records. The sculpts it did see
        // were from before the field existed at all.
        //
        // What actually happened: the field shipped at 22:40 and was used in EVERY sculpt from
        // 23:16 onward, voluntarily, while still optional, with real proportions.
        //
        // Required is kept anyway -- it costs nothing and guarantees the grader always has a
        // declaration -- but not for the reason originally given. Left written down because a
        // confident false statistic in a comment is exactly how "Spikor is mostly tan" survived
        // long enough to be obeyed.
        var required = TOOL.inputSchema().getAsJsonArray("required");
        boolean has = false;
        for (var e : required) {
            has |= "palette".equals(e.getAsString());
        }
        assertTrue(has, "palette must be required: " + required);
    }

    @Test
    @DisplayName("the description tells the agent to commit to PROPORTIONS, not just colours")
    void descriptionAsksForProportions() {
        String d = TOOL.description();
        assertTrue(d.contains("PROPORTIONS"),
                "naming colours is not enough to stop an all-green Spikor; proportions are");
        assertTrue(d.contains("palette"), "the description must point at the palette field");
    }

    @Test
    @DisplayName("the corrected Spikor falsehood has not crept back into the description")
    void noStaleFalsehood() {
        // This exact sentence WAS in the description, was wrong, and was obeyed into a 65%-tan
        // Spikor. The lesson file was corrected and this copy was not -- duplicated guidance
        // drifts, which is why the description now points at list_creatures instead of restating.
        String d = TOOL.description().toLowerCase(java.util.Locale.ROOT);
        assertFalse(d.contains("mostly tan"),
                "the false 'Spikor is mostly tan' claim is back in the tool description");
        assertFalse(d.contains("look the character up"),
                "the agent has no web access, so telling it to look a character up is an "
                        + "instruction it cannot follow");
    }
}
