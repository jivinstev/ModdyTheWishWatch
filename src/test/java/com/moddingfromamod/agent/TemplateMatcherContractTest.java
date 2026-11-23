package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.agent.tools.AddTemplateTool;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * <b>A real wish was lost to a constraint that lived only in prose — and the prose said the
 * opposite.</b>
 *
 * <p>{@code add_template} refuses a call with neither {@code all} nor {@code any}. The schema marked
 * both optional and {@code any}'s own description opened with the word <em>"Optional:"</em>, so the
 * agent sent every field the schema declared required, was refused, and Spikor was built and then
 * unteachable — the child had already been told <em>"Tap the watch to bring him back anytime!"</em>
 *
 * <p>Three channels have to agree, and this pins all three, because fixing only the one that failed
 * last time is how the next one gets found in a Gate D run instead of here:
 *
 * <ol>
 *   <li>the SCHEMA, machine-readable, so a validating client can refuse before the call is made;
 *   <li>the DESCRIPTIONS, which must not contradict it;
 *   <li>the ERROR, which must name both fields and the way out, since the model that got it wrong
 *       is the one that has to repair it.
 * </ol>
 */
class TemplateMatcherContractTest {

    private static JsonObject schema() {
        return new AddTemplateTool().inputSchema();
    }

    private static JsonObject prop(String name) {
        return schema().getAsJsonObject("properties").getAsJsonObject(name);
    }

    @Test
    void theSchemaSaysAtLeastOneOfAllOrAnyWithoutARootCombinator() {
        // a root-level anyOf made add_template invisible to a Muse worker (the CLI drops
        // the whole tool), so the rule lives in each field's terse prefix plus the tool's refusal.
        assertFalse(schema().has("anyOf"), "a root anyOf drops the tool on a redirected worker (#2227)");
        for (String field : List.of("all", "any")) {
            assertTrue(prop(field).get("description").getAsString().startsWith("[all|any REQUIRED"),
                    field + " must say, first, that one of all|any is required");
        }
    }

    @Test
    void requiredStillCoversTheFieldsThatAreAlwaysNeeded() {
        List<String> required = new ArrayList<>();
        for (var n : schema().getAsJsonArray("required")) {
            required.add(n.getAsString());
        }
        assertTrue(required.containsAll(List.of("id", "commands", "say")),
                "requireOneOf must ADD a constraint, never replace the flat one");
        assertFalse(required.contains("all"),
                "'all' alone is not required -- 'any' alone is a valid template");
        assertFalse(required.contains("any"));
    }

    @Test
    void neitherDescriptionCallsItOptional() {
        // This is the exact word that cost the wish. `any` opened with "Optional:", which is a
        // stronger and more local claim than anything the schema says.
        for (String field : List.of("all", "any")) {
            String d = prop(field).get("description").getAsString();
            assertFalse(d.toLowerCase().contains("optional"),
                    field + " must not describe itself as optional when the tool refuses without it");
            assertTrue(d.contains("all|any"),
                    field + " should carry the terse constraint, which reads as a rule rather than "
                            + "a suggestion and survives being skimmed");
        }
    }

    @Test
    void theTrulyOptionalFieldsStillSaySoSoTheWordKeepsItsMeaning() {
        // If everything is marked REQUIRED the marking carries no information. `repeat` and friends
        // really are optional and must keep saying it.
        assertTrue(prop("repeat").get("description").getAsString().toLowerCase()
                .startsWith("optional"));
    }

    @Test
    void theRefusalNamesBothFieldsAndTheWayBack() {
        JsonObject args = new JsonObject();
        args.addProperty("id", "spikor");
        args.addProperty("say", "Here he is!");
        JsonArray commands = new JsonArray();
        commands.add("say hi");
        args.add("commands", commands);

        ToolResult r = new AddTemplateTool().call(args);
        String message = r.text() == null ? "" : r.text();
        assertTrue(r.isError(), "a template with no keywords would match EVERY wish");
        assertTrue(message.contains("`all`") && message.contains("`any`"),
                "the error is the last chance to say which fields; it read as a scolding before");
        assertTrue(message.toLowerCase().contains("call again"),
                "name the repair, or the model re-derives it and spends another turn");
    }

    @Test
    void oneKeywordListIsEnoughEitherWay() {
        // Both halves, because a fix that demanded `any` specifically would be the same bug wearing
        // the other field's name.
        for (String field : List.of("all", "any")) {
            JsonObject args = new JsonObject();
            args.addProperty("id", "spikor");
            args.addProperty("say", "Here he is!");
            JsonArray commands = new JsonArray();
            commands.add("say hi");
            args.add("commands", commands);
            JsonArray words = new JsonArray();
            words.add("spikor");
            args.add(field, words);

            ToolResult r = new AddTemplateTool().call(args);
            String message = r.text() == null ? "" : r.text();
            assertFalse(message.contains("needs keywords"),
                    field + " alone must satisfy the matcher rule");
        }
    }
}
