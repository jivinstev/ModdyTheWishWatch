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
 * <b>The {@code aggro} effect survives an edit that never mentions it.</b>
 *
 * <p>Rule 2 of <i>what every step inherits</i>, and it is here because shipped correct on the
 * first wish and broke on the second: absent meant <i>throw it away</i> rather than <i>keep</i>,
 * the give line still rendered, and every gate stayed green.
 *
 * <p>The failure this prevents, in this axis's own terms: he asks for a pearl that makes what it
 * hits come after him, then says <i>"make it purple"</i> — a wish about the COLOUR, carrying no
 * effect and no {@code FIGHTS} — and the pearl quietly goes back to doing nothing at all. He would
 * throw it, watch nothing happen, and have no way to tell us what broke.
 */
class AggroSurvivesRefinementTest {

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** A pearl whose whole content is the aggro effect and the one answer it carries. */
    private static ItemSpecStore.Spec pearl() {
        return new ItemSpecStore.Spec("noob_pearl_aggro", "Noob Pearl", "orb", "lime", "thrown",
                4, List.of("aggro"),
                args("{\"aggro\":{\"FIGHTS\":\"me\"}}"),
                new JsonArray(), new JsonObject(), new JsonArray());
    }

    private static JsonObject after(String edit) {
        return MakeItemTool.inherit(args(edit), pearl());
    }

    @Test
    @DisplayName("an edit about the COLOUR keeps the effect AND who it fights")
    void aColourEditKeepsTheWholeEffect() {
        JsonObject kept = after("{\"start_from\":\"noob_pearl_aggro\",\"colour\":\"purple\"}");

        assertEquals("aggro", kept.get("effect").getAsString(),
                "a wish about the colour deleted the effect — the pearl now does nothing");
        assertEquals("me",
                kept.getAsJsonObject("params").getAsJsonObject("aggro").get("FIGHTS").getAsString(),
                "the effect survived but forgot WHO it fights, which is the whole choice");
        assertEquals("purple", kept.get("colour").getAsString(), "the edit itself must land");
        assertEquals("thrown", kept.get("shape").getAsString(), "the shape must be kept too");
    }

    /**
     * The other half, and the one that is easy to get backwards: a refinement that DOES name the
     * axis must win. Keeping too hard is as wrong as keeping too little — it would make
     * <i>"actually make it fight the zombie instead"</i> impossible to grant.
     */
    @Test
    @DisplayName("an edit that names FIGHTS overrides it")
    void anExplicitChangeStillWins() {
        JsonObject kept = after("""
                {"start_from":"noob_pearl_aggro","effect":"aggro",
                 "params":{"aggro":{"FIGHTS":"mob"}}}""");

        assertEquals("mob",
                kept.getAsJsonObject("params").getAsJsonObject("aggro").get("FIGHTS").getAsString(),
                "he asked for it to fight something else and was given the old answer");
    }

    /**
     * A refinement can only be AIMED at something the agent can see, so the axis has to come back
     * out of {@code inspect_item}. This is the cheap end of the same rule: an effect that is stored
     * and never reported is one the next wish cannot mention.
     */
    @Test
    @DisplayName("inspect_item reports the effect and its answer, so a refinement can aim at it")
    void theAgentCanSeeItToRefineIt() {
        String report = InspectItemTool.describe(pearl());
        assertTrue(report.contains("aggro"), "inspect_item hides the effect: " + report);
        assertTrue(report.contains("FIGHTS"), "inspect_item hides WHO it fights: " + report);
    }
}
