package com.moddingfromamod.safety;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The schema and the record must not drift apart</b>, and the check runs in BOTH directions.
 *
 * <p>They are two statements of one vocabulary, written in two languages, and each direction fails
 * silently on its own:
 *
 * <ul>
 *   <li>a value in the SCHEMA that the record has never heard of — the model is offered a word,
 *       says it, and {@link SafetyVerdict#parse} quietly drops it. The grade is then wrong in a
 *       way nothing logs, which is the worst available shape.</li>
 *   <li>a value in the RECORD that the schema does not offer — the parser waits for a word the
 *       model can never send, so a whole reason (or a whole band) is dead code that reads as
 *       coverage.</li>
 * </ul>
 *
 * <p>The second is the one that actually happens: somebody adds {@code Reason.HARASSMENT} to the
 * enum, the compiler is happy, every test is green, and the model is never once asked about it.
 */
class SafetyVerdictSchemaTest {

    private static final Path SCHEMA = Path.of("src/main/resources/safety/verdict-schema.json");

    private static JsonObject schema() throws Exception {
        return JsonParser.parseString(Files.readString(SCHEMA)).getAsJsonObject();
    }

    private static List<String> required(JsonObject schema) {
        List<String> out = new ArrayList<>();
        schema.getAsJsonArray("required").forEach(e -> out.add(e.getAsString()));
        return out;
    }

    private static List<String> enumOf(JsonObject props, String field) {
        JsonObject f = props.getAsJsonObject(field);
        JsonArray arr = f.has("enum") ? f.getAsJsonArray("enum")
                : f.getAsJsonObject("items").getAsJsonArray("enum");
        List<String> out = new ArrayList<>();
        arr.forEach(e -> out.add(e.getAsString()));
        return out;
    }

    @Test
    void everyAxisOffersExactlyTheBandsTheRecordKnows() throws Exception {
        List<String> bands = Arrays.stream(SafetyVerdict.Band.values()).map(Enum::name).toList();
        JsonObject props = schema().getAsJsonObject("properties");
        for (String axis : List.of("violence", "sexual", "hate", "danger")) {
            assertEquals(bands, enumOf(props, axis),
                    "the schema's " + axis + " enum and SafetyVerdict.Band have drifted. In the "
                            + "same ORDER too: the JSON is what a human reads to understand the "
                            + "severity ladder overall() computes.");
        }
    }

    @Test
    void theReasonVocabularyMatchesInBothDirections() throws Exception {
        List<String> java = Arrays.stream(SafetyVerdict.Reason.values()).map(Enum::name).sorted()
                .toList();
        List<String> json = enumOf(schema().getAsJsonObject("properties"), "reasons").stream()
                .sorted().toList();
        assertEquals(java, json,
                "SafetyVerdict.Reason and the schema's reasons enum disagree. A reason only in "
                        + "Java is never offered to the model and is dead code that reads as "
                        + "coverage; a reason only in the schema is said by the model and dropped "
                        + "by the parser.");
    }

    @Test
    void theSchemaEnforcesTheSameBoundsTheRecordDoes() throws Exception {
        JsonObject props = schema().getAsJsonObject("properties");
        assertEquals(SafetyVerdict.MAX_REASONS,
                props.getAsJsonObject("reasons").get("maxItems").getAsInt(),
                "the record caps the reason list; the schema must ask for the same cap, or the "
                        + "model wastes tokens on reasons that are silently thrown away");
        assertEquals(SafetyVerdict.MAX_REDIRECT,
                props.getAsJsonObject("redirect").get("maxLength").getAsInt(),
                "a redirect the model wrote to 400 characters and the record cut to 200 is a "
                        + "sentence that ends mid-thought in front of a child");
    }

    @Test
    void theSchemaIsClosedAndEveryRequiredFieldExists() throws Exception {
        JsonObject s = schema();
        assertFalse(s.get("additionalProperties").getAsBoolean(),
                "additionalProperties must stay false: one unclaimed string is all an injected "
                        + "instruction needs (docs/PATTERNS.md)");
        JsonObject props = s.getAsJsonObject("properties");
        for (var e : s.getAsJsonArray("required")) {
            assertTrue(props.has(e.getAsString()),
                    "required names a property that does not exist: " + e.getAsString());
        }
        // Everything the parse path reads must be offered at all.
        for (String field : List.of("violence", "sexual", "hate", "danger",
                "reasons", "redirect", "stageIndex")) {
            assertTrue(props.has(field), "the schema no longer offers " + field);
        }
    }

    // ── decision 12.12: stageIndex is required for a PLAN grade ───────────────────────────────

    @Test
    void thePlanShapeREQUIRESStageIndexAndTheInputShapeDoesNot() throws Exception {
        List<String> input = required(schema());
        assertFalse(input.contains("stageIndex"),
                "an INPUT grade has no stages, so demanding the field there asks a model to "
                        + "answer a question about something that does not exist, every time, for "
                        + "a value SafetyInputGate.combine overwrites with NO_STAGE anyway");

        List<String> plan = required(JsonParser.parseString(SafetyGrader.planSchema())
                .getAsJsonObject());
        assertTrue(plan.contains("stageIndex"),
                "the plan grade's whole point is naming the step: a divert that cannot is a "
                        + "re-plan from scratch, which is worse than the step objected to");
        // Everything ELSE is shared, which is the reason this is derived rather than a second
        // file: two statements of one vocabulary drift, and this test's other four methods exist
        // because that drift is silent in both directions.
        assertEquals(input, plan.stream().filter(r -> !r.equals("stageIndex")).toList(),
                "the plan shape must be the input shape PLUS stageIndex and nothing else");
        JsonObject planProps = JsonParser.parseString(SafetyGrader.planSchema()).getAsJsonObject()
                .getAsJsonObject("properties");
        assertEquals(schema().getAsJsonObject("properties").toString(), planProps.toString(),
                "the two shapes must offer identical properties -- only `required` differs");
    }

    @Test
    void theWholePlanWordingSurvivesForAMalformedGrade() {
        // A schema bound is a promise about a well-behaved reply, not about a subprocess. -1 is
        // still a real answer ("no single step is responsible"), a reply that dodges the schema
        // parses to NO_STAGE, and the divert message must still name something in both cases.
        SafetyVerdict noStage = new SafetyVerdict(SafetyVerdict.Band.LIKELY,
                SafetyVerdict.Band.FINE, SafetyVerdict.Band.FINE, SafetyVerdict.Band.FINE,
                List.of(SafetyVerdict.Reason.GORE), "a rubber chicken",
                SafetyVerdict.Provenance.HAIKU, SafetyVerdict.NO_STAGE);
        String msg = SafetyPlanGate.divertMessage(noStage, List.of("Make it gory"));
        assertTrue(msg.contains("The plan as a whole is the problem"), msg);
        assertTrue(msg.contains("rubber chicken"), "it must still name an alternative: " + msg);
    }

    @Test
    void theGraderPromptsAndTheSchemaAreOnTheClasspathAtRuntime() {
        // They are loaded by getResourceAsStream from the jar, so "the file exists in src" is not
        // the question — a resource that is not packaged is an UNAVAILABLE grade for ever, and
        // UNAVAILABLE in `open` mode is indistinguishable from a quiet evening.
        assertFalse(SafetyGrader.schema().isBlank(), "verdict-schema.json is not on the classpath");
        for (String p : List.of("input-grader-prompt.md", "plan-grader-prompt.md")) {
            assertFalse(SafetyGrader.promptFor(p).isBlank(), p + " is not on the classpath");
        }
    }

    @Test
    void thePromptsStateTheRuleThatCostTheReferenceLookupItsAccuracy() {
        // PATTERNS.md's hardest lesson, learned twice in this project: never let a model's own
        // knowledge gate access to knowledge it lacks. A grader asked "is that a real YouTuber"
        // fails hardest on exactly the unfamiliar names a child brings.
        for (String p : List.of("input-grader-prompt.md", "plan-grader-prompt.md")) {
            String text = SafetyGrader.promptFor(p).toLowerCase(java.util.Locale.ROOT);
            assertTrue(text.contains("never ask yourself whether a named character"),
                    p + " no longer forbids the knowledge question");
            assertTrue(text.contains("unfamiliar name"),
                    p + " no longer says an unfamiliar name is the normal case");
        }
    }

    @Test
    void theInputPromptSaysTheThingsTheCorpusActuallyContains() {
        // The right-hand column of §1 is the load-bearing half: the 84 real wishes are full of
        // killing bosses, gross-out humour and things that are scary on request, and a grader that
        // has not been told those are FINE is anti-correlated with what the child wants.
        String text = SafetyGrader.promptFor("input-grader-prompt.md")
                .toLowerCase(java.util.Locale.ROOT);
        for (String phrase : List.of("gross-out", "respawn", "scary is not a band", "gibberish")) {
            assertTrue(text.contains(phrase),
                    "the input prompt no longer tells the grader that '" + phrase + "' is fine — "
                            + "which is the direction this layer fails in");
        }
        assertTrue(text.contains("200 characters"), "the redirect bound is no longer stated");
    }
}
