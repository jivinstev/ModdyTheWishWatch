package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.CorpusSource;
import org.junit.jupiter.api.Test;
import com.moddingfromamod.UsesCorpus;

/**
 * a follow-up that names nothing ("give it wings too") must reach the agent as one, not the
 * offline cache — and a NEW-thing wish must not. Positives are the step-6 pilot's nine follow-ups;
 * negatives are every other wish in that pre-registered sample (synthetic, never typed by a child).
 */
@UsesCorpus
class WishReferenceFollowupTest {

    /** The pilot's nine follow-ups (corpus/synthetic-player/sample.json, class followup). */
    static final List<String> PILOT_FOLLOWUPS = List.of(
            "make him BIGGER!!!",
            "make it have LASERS too!!!",
            "give it wings too",
            "give it a little baby version",
            "more ice should make it bigger",
            "now make it glow",
            "make it sparkle when it moves");

    /** The two of the nine that name his thing rather than say "it" — contextual with ownership. */
    static final List<String> PILOT_NAMED = List.of(
            "make the black hole pull way harder",
            "make the cloud bounce me higher");

    /** Sample wishes the rules on main ALREADY flagged ("that" + a colour, "back", …): not ours. */
    static final Set<String> FLAGGED_BEFORE_1464 = Set.of(
            "a blue phoenix that comes back after fire",
            "a shield that bounces arrows back",
            "a tornado that spins around my house",
            "a black hole block that sucks everything in",
            "turn this lake into ice",
            "a gold block that gives me cake when mined");

    @Test
    void everyPilotFollowUpIsContextual() {
        for (String w : PILOT_FOLLOWUPS) {
            assertTrue(WishReference.isContextual(w), "a follow-up read as a new wish: " + w);
        }
        List<String> owned = List.of("black_hole", "black hole", "bouncy_cloud", "bouncy cloud");
        for (String w : PILOT_NAMED) {
            assertFalse(WishReference.isContextual(w), "names nothing he owns on its own: " + w);
            assertTrue(WishReference.isContextual(w, owned), "names his thing + a change: " + w);
            assertFalse(WishReference.isContextual(w, List.of("pegasus", "stone castle")),
                    "his things are not named here: " + w);
            assertTrue(WishReference.pointsBack(w), "points back, owned or not: " + w);
        }
    }

    /**
     * Every NEW-thing wish of the pre-registered sample, through {@link CorpusSource}: the real synthetic
     * corpus and sample here, a fixture of wishes written for it in the public repo, with its own floor.
     */
    @Test
    void newThingWishesInThePilotSampleStayNew() throws Exception {
        Map<String, String> prompts = new HashMap<>();
        Map<String, String> classes = new HashMap<>();
        for (String line : Files.readAllLines(CorpusSource.path("synthetic-wishes/wishes.jsonl"))) {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            if (o.has("id") && o.has("prompt")) {
                prompts.put(o.get("id").getAsString(), o.get("prompt").getAsString());
                classes.put(o.get("id").getAsString(), o.get("class").getAsString());
            }
        }
        JsonObject sample = JsonParser.parseString(Files.readString(
                CorpusSource.path("synthetic-player/sample.json"))).getAsJsonObject();
        int checked = 0;
        for (String half : List.of("pilot", "panel")) {
            for (var el : sample.getAsJsonArray(half)) {
                String id = el.getAsJsonObject().get("id").getAsString();
                String p = prompts.get(id);
                if (p == null || "followup".equals(classes.get(id)) || FLAGGED_BEFORE_1464.contains(p)) {
                    continue;
                }
                checked++;
                assertFalse(WishReference.isContextual(p), "a NEW-thing wish read as a follow-up: " + p);
            }
        }
        assertTrue(checked >= (CorpusSource.real() ? 40 : 30), "the sample shrank: " + checked);
    }

    @Test
    void newThingsWithPronounsInsideThemStayNew() {
        for (String w : List.of(
                "make me a dragon",
                "a pickaxe that breaks a whole tunnel",
                "a dog that sparkles when it runs",
                "a sword that glitters",
                "make it rain chickens",
                "make it night and spooky",
                "make it sunny",
                "make it snow forever",
                "let it snow",
                "give me a pet that follows me and lets me ride it",
                "clouds that sparkle when it rains",
                "a dragon that gives me diamonds",
                "make a giant slime that jumps higher than my house")) {
            assertFalse(WishReference.isContextual(w), "must stay a NEW wish: " + w);
        }
    }

    @Test
    void openingByActingOnItIsAFollowUp() {
        for (String w : List.of(
                "teach him to fly",
                "now give her a crown",
                "can you turn it blue",
                "make them dance",
                "make it follow me",
                "make it twice as tall")) {
            assertTrue(WishReference.isContextual(w), "acts on something already there: " + w);
        }
    }
}
