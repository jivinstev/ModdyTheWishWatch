package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * The manifest is only worth having if it cannot fall out of step with the directory.
 *
 * <p>Two real failures motivate every assertion here: {@code creatures.jsonl} held 23 real creature
 * wishes and sat outside the loader for its whole life because a hand-kept list never mentioned it,
 * and {@code datapacks.jsonl} — 154 CBScript compiler records, not wishes — was counted as demand
 * by a tool that globbed the directory. One was invisible, the other was invisible in the other
 * direction, and both looked completely normal.
 */
class CorpusManifestTest {

    private static JsonObject manifest() throws Exception {
        try (var in = WishCorpus.class.getResourceAsStream("/corpus/manifest.json")) {
            return JsonParser.parseString(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static List<String> declared() throws Exception {
        List<String> out = new ArrayList<>();
        manifest().getAsJsonArray("files").forEach(e ->
                out.add(e.getAsJsonObject().get("file").getAsString()));
        return out;
    }

    @Test
    void everyDeclaredFileExists() throws Exception {
        List<String> missing = new ArrayList<>();
        for (String f : declared()) {
            if (WishCorpus.class.getResourceAsStream("/corpus/" + f) == null
                    && !(com.moddingfromamod.PrivateCorpora.publicCut() && com.moddingfromamod.PrivateCorpora.NAMES.contains(f))) {
                missing.add(f);   // a private corpus is absent only in the public cut
            }
        }
        assertEquals(List.of(), missing, "the manifest declares files that are not there: " + missing);
    }

    @Test
    void everyFileDeclaresAtLeastOneKnownRole() throws Exception {
        List<String> known = List.of("RUNNABLE", "GAPS", "DEMAND_DIRECT", "DEMAND_HISTORICAL",
                "CALIBRATION");
        List<String> bad = new ArrayList<>();
        manifest().getAsJsonArray("files").forEach(e -> {
            JsonObject o = e.getAsJsonObject();
            var roles = o.getAsJsonArray("roles");
            if (roles == null || roles.isEmpty()) {
                bad.add(o.get("file").getAsString() + " (no role)");
                return;
            }
            roles.forEach(r -> {
                if (!known.contains(r.getAsString())) {
                    bad.add(o.get("file").getAsString() + " -> unknown role " + r.getAsString());
                }
            });
        });
        assertEquals(List.of(), bad, "a role nobody recognises means a file whose purpose is a "
                + "guess, which is how one got globbed into a demand ranking: " + bad);
    }

    @Test
    void theLoaderLoadsExactlyWhatTheManifestSaysToLoad() throws Exception {
        List<String> expected = new ArrayList<>();
        manifest().getAsJsonArray("files").forEach(e -> {
            JsonObject o = e.getAsJsonObject();
            if (o.has("loadIntoStress") && o.get("loadIntoStress").getAsBoolean()) {
                expected.add("/corpus/" + o.get("file").getAsString());
            }
        });
        assertEquals(expected, WishCorpus.RESOURCES,
                "the loader and the manifest disagree, so one of them is lying about what the "
                        + "corpus contains");
    }

    @Test
    void compilerFixturesAreNotLoadedAsWishes() throws Exception {
        // datapacks.jsonl is 154 CBScript COMPILE RECORDS. It was counted as demand once already.
        assertFalse(WishCorpus.RESOURCES.contains("/corpus/datapacks.jsonl"),
                "datapacks.jsonl is compiler regression data, not wishes — loading it would put "
                        + "154 compile records into the demand signal");
        assertTrue(declared().contains("datapacks.jsonl"),
                "it must still be DECLARED, or the next person to glob the directory repeats the "
                        + "mistake");
    }

    @Test
    void theCorpusIsNotAccidentallyEmpty() {
        // Each cut is held to what it is MEANT to carry, not to the other's number. The public files load
        // 340 cases (wishes 307, pictures 13, creatures 20); the private corpora add 244 more here (a child's real
        // wishes 84, the mod-repo history 154, the verbatim rows 6), 584 in all. A floor under each with headroom:
        // a damaged manifest drops a whole file, which falls through either one.
        boolean publicCut = com.moddingfromamod.PrivateCorpora.publicCut();
        int floor = publicCut ? 300 : 500;
        int loaded = WishCorpus.load().size();
        assertTrue(loaded > floor,
                "only " + loaded + " cases loaded (floor " + floor + (publicCut ? ", the public cut" : "")
                        + ") — a damaged manifest would make every gate below this a vacuous pass");
    }
}
