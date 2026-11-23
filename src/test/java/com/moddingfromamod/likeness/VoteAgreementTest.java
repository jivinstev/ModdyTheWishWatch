package com.moddingfromamod.likeness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.agent.tools.SculptCreatureTool;
import com.moddingfromamod.reference.ReferenceSpec;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.gate.StructuralQuality;
import org.junit.jupiter.api.Test;

/**
 * <b>Which grader agrees with the human?</b>
 *
 * <p>Scores every candidate — the three likeness graders <i>and</i> the pre-existing
 * {@link StructuralQuality} metrics — against recorded votes, and prints agreement per grader.
 *
 * <p>Deliberately <b>per grader and per creature, never averaged</b>. Averaging is what once turned
 * a judge that was excellent at two questions and at chance on the third into a single "56%" that
 * hid the only useful fact.
 *
 * <pre>
 *   MFAM_VOTE_A=/tmp/mfam-ab2-A MFAM_VOTE_B=/tmp/mfam-ab2-B \
 *     ./gradlew test --tests '*VoteAgreementTest' -i
 * </pre>
 */
class VoteAgreementTest {

    /**
     * Where the votes live.
     *
     * <p>Read from the calibration store rather than hard-coded here, so a new voting round is
     * scored by adding data and never by editing a test — a test that carries its own ground truth
     * is one edit away from being tuned to agree with itself.
     */
    private static final Path VOTES = Path.of(
            "src/main/resources/calibration/human-votes-002-fidelity.json");

    @Test
    void whichGraderAgrees() throws IOException {
        if (!Files.isRegularFile(VOTES)) {
            // The votes are keyed with a child's own words, so the file is private: the public
            // cut has no copy and skips this by name. Here it is required, so a lost file cannot pass.
            org.junit.jupiter.api.Assumptions.assumeFalse(com.moddingfromamod.PrivateCorpora.publicCut(),
                    "calibration/human-votes-002-fidelity.json is private (#2310): the public cut has no copy, so this is skipped");
            org.junit.jupiter.api.Assertions.fail(VOTES + " is missing; it is required here (only the public cut may lack it)");
        }
        JsonObject store = JsonParser.parseString(Files.readString(VOTES)).getAsJsonObject();
        Map<String, Map<String, Boolean>> agree = new LinkedHashMap<>();
        for (var el : store.getAsJsonArray("votes")) {
            JsonObject v = el.getAsJsonObject();
            String cid = v.get("cid").getAsString();
            int round = v.has("round") ? v.get("round").getAsInt() : 1;
            String da = v.has("armADir") ? v.get("armADir").getAsString()
                    : System.getenv().getOrDefault("MFAM_VOTE_A", "/tmp/mfam-ab2-A");
            String db = v.has("armBDir") ? v.get("armBDir").getAsString()
                    : System.getenv().getOrDefault("MFAM_VOTE_B", "/tmp/mfam-ab2-B");
            List<CreatureSculpt.Part> pa = load(Path.of(da), "A", cid);
            List<CreatureSculpt.Part> pb = load(Path.of(db), "B", cid);
            if (pa == null || pb == null) {
                continue;
            }
            String human = v.get("human").getAsString();
            String key = cid + "#r" + round;
            ReferenceSpec ref = reference(cid);
            LikenessReport ra = LikenessReport.of(pa, ref);
            LikenessReport rb = LikenessReport.of(pb, ref);

            record(agree, "G2_palette", key, human,
                    ra.palette().scored()
                            ? pick(ra.palette().distance(), rb.palette().distance(), false)
                            : null);
            record(agree, "G3_precision", key, human,
                    ra.features().scored()
                            ? pick(ra.features().precision(), rb.features().precision(), true)
                            : null);

            // The pre-existing structural metrics, which need NO reference — so unlike the three
            // above they can speak about an invented creature, which is half the corpus.
            Map<String, Double> sa = structural(pa);
            Map<String, Double> sb = structural(pb);
            for (String metric : sa.keySet()) {
                if (sb.containsKey(metric)) {
                    record(agree, "SQ_" + metric, key, human,
                            pick(sa.get(metric), sb.get(metric), true));
                }
            }
            System.out.printf("[vote] %-16s r%d human=%s  parts %d/%d  kit %d/%d%n",
                    cid, round, human, pa.size(), pb.size(),
                    com.moddingfromamod.runtime.SculptKits.usedBy(pa),
                    com.moddingfromamod.runtime.SculptKits.usedBy(pb));
        }

        System.out.println("\n[vote] ===== AGREEMENT (per grader, never averaged) =====");
        agree.forEach((grader, per) -> {
            long right = per.values().stream().filter(v -> v).count();
            long scored = per.size();
            System.out.printf("[vote] %-22s %d/%d   %s%n", grader, right, scored, per);
        });
    }

    private static void record(Map<String, Map<String, Boolean>> agree, String grader,
                               String cid, String human, String said) {
        // UNKNOWN and TIE are both NO ANSWER, not wrong answers. Scoring a tie as a miss made a
        // metric that was right on both cases it had an opinion about read as "2/5" -- the same
        // shape of error as averaging buckets, and it buried the best candidate on the list.
        if (said == null || "TIE".equals(said)) {
            return;
        }
        agree.computeIfAbsent(grader, k -> new LinkedHashMap<>()).put(cid, said.equals(human));
    }

    /** Which arm a metric favours; higherIsBetter=false for a distance. */
    private static String pick(double a, double b, boolean higherIsBetter) {
        if (Math.abs(a - b) < 1e-9) {
            return "TIE";
        }
        boolean aWins = higherIsBetter ? a > b : a < b;
        return aWins ? "A" : "B";
    }

    private static Map<String, Double> structural(List<CreatureSculpt.Part> parts) {
        Map<String, Double> out = new LinkedHashMap<>();
        StructuralQuality.Report r = StructuralQuality.measure(parts, "");
        for (StructuralQuality.Metric m : r.metrics()) {
            out.put(m.name(), Math.round(m.score() * 1000) / 1000.0);
        }
        return out;
    }

    private static List<CreatureSculpt.Part> load(Path dir, String arm, String cid)
            throws IOException {
        Path f = dir.resolve(arm + "__" + cid + ".json");
        if (!Files.isRegularFile(f)) {
            // the first Spikor pair lives in its own directories
            Path alt = Path.of("/tmp/mfam-ab-" + arm, arm + "__" + cid + ".json");
            if (!Files.isRegularFile(alt)) {
                return null;
            }
            f = alt;
        }
        JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
        return SculptCreatureTool.withKits(
                CreatureSculpt.parseParts(o.getAsJsonArray("parts")), o);
    }

    private static ReferenceSpec reference(String cid) throws IOException {
        String key = cid.toLowerCase(Locale.ROOT);
        for (Path root : List.of(
                Path.of(System.getProperty("user.home"), ".moddingfromamod", "reference-cache"),
                Path.of(System.getProperty("user.home"), "mfam-eval-corpus", "reference-cache"))) {
            Path f = root.resolve(key + ".json");
            if (Files.isRegularFile(f)) {
                return ReferenceSpec.parse(Files.readString(f));
            }
        }
        return null;
    }
}
