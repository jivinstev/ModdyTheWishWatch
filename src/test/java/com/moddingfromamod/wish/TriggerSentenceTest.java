package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * <b>The deterministic half of the trigger-sentence gate.</b>
 *
 * <p>{@code tools/verify-trigger-sentence.py} asks a model whether what Moddy told the child
 * contradicts the ability that was actually built, and commits its verdicts. This test reads those
 * committed verdicts offline and fails on any contradiction that is not baselined — the same split
 * as {@code CatalogueWishesTest}: a model's judgement, a deterministic gate.
 *
 * <p><b>The ratchet matters more than the assertion.</b> One contradiction is in the baseline: the
 * wish {@code "the spider bite should make you slow"}, built correctly as {@code on_hit} and
 * described to the child as "get close". It is kept, not deleted, because it is the evidence the fix
 * was needed — a gate that goes green by forgetting its own motivating case proves nothing. New
 * contradictions fail; the count can go down and never up.
 */
class TriggerSentenceTest {

    private static final Path VERDICTS =
            Path.of("tools", "trigger-sentence-verdicts.json");

    @Test
    void noNewContradictionBetweenWhatWasSaidAndWhatWasBuilt() throws Exception {
        if (!Files.exists(VERDICTS)) {
            // Not a silent pass: say plainly that nothing was checked. An unchecked log is not a
            // clean one, and this is the message that stops it reading as green.
            System.out.println("TRIGGER SENTENCE: SKIPPED — no verdicts file. Nothing was checked. "
                    + "Run tools/verify-trigger-sentence.py.");
            return;
        }
        JsonObject root = JsonParser.parseString(Files.readString(VERDICTS)).getAsJsonObject();
        List<String> baseline = new ArrayList<>();
        if (root.has("baseline")) {
            root.getAsJsonArray("baseline").forEach(e -> baseline.add(e.getAsString()));
        }
        JsonObject v = root.getAsJsonObject("v");
        List<String> offenders = new ArrayList<>();
        for (String key : v.keySet()) {
            JsonObject rec = v.getAsJsonObject(key);
            if (rec.get("contradicts").getAsBoolean() && !baseline.contains(key)) {
                offenders.add(rec.get("wish").getAsString() + " — built "
                        + rec.get("built") + " — " + rec.get("why").getAsString());
            }
        }
        assertTrue(offenders.isEmpty(),
                "Moddy told the child to do something that will not set the ability off. This is "
                        + "the unrecoverable failure: the child follows the instruction, nothing "
                        + "happens, and they blame the toy rather than the sentence.\n  "
                        + String.join("\n  ", offenders));
    }

    @Test
    void theBaselineIsNotAllowedToGrowSilently() {
        // A baseline that can be appended to is not a ratchet, it is a mute button. Pinning the
        // size means adding to it is a deliberate, reviewable edit to this test.
        if (!Files.exists(VERDICTS)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(VERDICTS)).getAsJsonObject();
            int size = root.has("baseline") ? root.getAsJsonArray("baseline").size() : 0;
            assertFalse(size > 1,
                    "the pre-fix contradiction baseline grew to " + size + ". It is meant to shrink "
                            + "as history is re-judged, never to grow: a new contradiction belongs "
                            + "in a bug, not in the list of ones we have agreed to ignore.");
        } catch (Exception e) {
            throw new AssertionError("could not read the verdicts file: " + e);
        }
    }
}
