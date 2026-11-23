package com.moddingfromamod.runtime.gate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptCritique;
import com.moddingfromamod.wish.CreatureCorpus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Scores a live run's captured sculpts and prints a report per creature.
 *
 * <p>Runs against whatever a client run left in {@code MFAM_SCULPT_DIR}. Skips LOUDLY when that is
 * absent — a quiet skip here would report green over an evaluation that never happened, which is
 * the failure this project keeps rediscovering.
 *
 * <p>Grades with {@link NamedProperties} first, because it needs no reference and has never had to
 * be retracted. Reference-based numbers are deliberately not computed here: two of them turned out
 * to rest on values I invented rather than sourced.
 */
class CreatureRunReportTest {

    /**
     * The authoritative species → corpus mapping, stamped by the harness when it captured the
     * creature. {@link #matchCorpus} below is guesswork kept only for runs recorded before
     * stamping existed.
     */
    private static Map<String, String> loadIndex(Path dir) {
        Map<String, String> idx = new LinkedHashMap<>();
        try (var s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.toString().endsWith("__index.jsonl")).toList()) {
                for (String line : Files.readAllLines(f)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                        if (o.has("species") && o.has("corpusId")) {
                            idx.put(o.get("species").getAsString(),
                                    o.get("corpusId").getAsString());
                        }
                    } catch (RuntimeException ignored) {
                        // a damaged line is one lost attribution, not a lost run
                    }
                }
            }
        } catch (Exception ignored) {
            // no index: pre-stamping run, fall back below
        }
        return idx;
    }

    private static JsonObject wrap(com.google.gson.JsonElement palette) {
        JsonObject o = new JsonObject();
        o.add("palette", palette);
        return o;
    }

    private static String norm(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Words of 4+ characters. Shorter ones ("the", "big", "boy") collide across unrelated ids. */
    private static java.util.Set<String> tokens(String s) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String t : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() >= 4) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Map a captured SPECIES id back to its corpus case.
     *
     * <p>Captures are named by the id the AGENT chose, and it rarely matches the corpus id. Real
     * examples from one run:
     *
     * <pre>
     *   chucko            -&gt; chucko_mcburger     (prefix)
     *   bloop             -&gt; the_bloop           (suffix)
     *   gorilla_boss      -&gt; jungle_gorilla_boss (infix)
     *   omegalad          -&gt; omega_lad           (punctuation only)
     *   frost_mage        -&gt; icy_mage            (SYNONYM — no shared substring at all)
     *   mutant_illusioner -&gt; buff_illusioner     (synonym + shared noun)
     *   baby_dragon       -&gt; baby_ender_dragon   (interpolated word)
     * </pre>
     *
     * <p>A prefix rule caught one of those seven and containment caught four. The last three share
     * no substring, only a noun — which is why scoring on shared WORDS is the rule that holds.
     *
     * <p>Getting this wrong is silent rather than loud: an unmatched creature keeps its photographs
     * but loses its tier and its wish text, so {@link NamedProperties} has no prompt to grade
     * against and reports nothing. The run then looks complete while grading almost nothing.
     */
    private static CreatureCorpus.Case matchCorpus(String speciesId,
                                                   Map<String, CreatureCorpus.Case> byId) {
        CreatureCorpus.Case exact = byId.get(speciesId);
        if (exact != null) {
            return exact;
        }
        String n = norm(speciesId);
        var nt = tokens(speciesId);
        String best = null;
        double bestScore = 0;
        for (String key : byId.keySet()) {
            String c = norm(key);
            var ct = tokens(key);
            double score = 0;
            if (!c.isEmpty() && (c.contains(n) || n.contains(c))) {
                score += 3;
            }
            ct.retainAll(nt);
            score += 2 * ct.size();
            score += ct.stream().mapToInt(String::length).max().orElse(0) / 100.0;
            if (score > bestScore) {
                best = key;
                bestScore = score;
            }
        }
        return bestScore > 0 ? byId.get(best) : null;
    }

    @Test
    @DisplayName("report: what a live creature run actually produced")
    void report() throws Exception {
        String dir = System.getenv("MFAM_SCULPT_DIR");
        Assumptions.assumeTrue(dir != null && Files.isDirectory(Path.of(dir)),
                "SKIPPED — set MFAM_SCULPT_DIR to a run's signal directory");

        Map<String, CreatureCorpus.Case> byId = new LinkedHashMap<>();
        CreatureCorpus.load().forEach(c -> byId.put(c.id(), c));

        Map<String, String> index = loadIndex(Path.of(dir));
        // What the agent SAID it was going to build, recovered from the action log. Without this
        // the named tier is ungradeable: those wishes state no colour, count or size, so the only
        // checkable standard is the agent's own declaration.
        JsonObject declared = new JsonObject();
        Path decl = Path.of(dir).resolve("declared-palettes.json");
        if (Files.isRegularFile(decl)) {
            try {
                declared = JsonParser.parseString(Files.readString(decl)).getAsJsonObject();
            } catch (RuntimeException ignored) {
                // no declarations: the report still runs, it just says less
            }
        }
        List<Path> files;
        try (var s = Files.list(Path.of(dir))) {
            // Sidecars are not creatures. declared-palettes.json and index.jsonl live in the same
            // directory and were being counted as sculpts, inflating the total by one.
            files = s.filter(f -> f.toString().endsWith(".json"))
                    .filter(f -> !f.getFileName().toString().startsWith("declared-"))
                    // A vanilla-baseline record is not a sculpt; it has no parts and was being
                    // listed as a "0 parts" creature AND counted in the total.
                    .filter(f -> !f.getFileName().toString().endsWith(".vanilla.json"))
                    .filter(f -> f.getFileName().toString().contains("__"))
                    .sorted().toList();
        }
        if (files.isEmpty()) {
            System.out.println("\n(no sculpts captured in " + dir + ")\n");
            return;
        }

        System.out.println("\n================= LIVE CREATURE RUN =================");
        System.out.println(index.isEmpty()
                ? "(no capture-time index — ids resolved by word-scoring fallback)"
                : index.size() + " creature(s) mapped from the capture-time index");
        int graded = 0;
        int satisfied = 0;
        int possible = 0;
        List<Double> structScores = new ArrayList<>();
        int declaredChecked = 0;
        int declaredMet = 0;
        int declaredTotal = 0;
        List<String> blocked = new ArrayList<>();

        for (Path f : files) {
            String name = f.getFileName().toString().replace(".json", "");
            String id = name.contains("__") ? name.substring(name.indexOf("__") + 2) : name;
            String lookupId = id.endsWith(".rev1") ? id.substring(0, id.length() - 5) : id;
            JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            List<CreatureSculpt.Part> parts = CreatureSculpt.parseParts(o.getAsJsonArray("parts"));
            CreatureCorpus.Case c = index.containsKey(lookupId)
                    ? byId.get(index.get(lookupId))    // stamped at capture: authoritative
                    : matchCorpus(lookupId, byId);     // pre-stamping run: word-scoring guess

            var pal = SculptCritique.palette(parts);
            System.out.printf("%n%-16s %s | %d parts | %s%n", id,
                    c == null ? "?" : c.tier(), parts.size(),
                    SculptCritique.describeBuckets(pal));

            if (c == null) {
                System.out.println("    UNMATCHED to any corpus case — cannot grade this one");
            }
            if (c != null) {
                double scale = o.has("scale") ? o.get("scale").getAsDouble() : 1.0;
                var np = NamedProperties.grade(c.prompt(), parts, scale);
                if (!np.isEmpty()) {
                    graded++;
                    satisfied += np.satisfied();
                    possible += np.total();
                    System.out.println("    said: " + np.describe().trim());
                } else {
                    System.out.println("    said: nothing countable — judge by eye");
                }
            }
            // The declaration lives IN the sculpt (sculpt.json stores `palette`), so the artifact
            // is the authority. The recovered sidecar is only a fallback for runs captured before
            // that was noticed -- and it disagreed, because the log holds every re-sculpt while
            // the artifact holds the final one.
            JsonObject decls = o.has("palette") ? o
                    : declared.has(id) ? wrap(declared.get(id)) : null;
            if (decls != null) {
                var dp = DeclaredPalette.check(decls, parts);
                if (!dp.isEmpty()) {
                    declaredChecked++;
                    declaredMet += dp.outcomes().size() - dp.missed().size();
                    declaredTotal += dp.outcomes().size();
                    System.out.println("    promised: " + dp.outcomes().size() + " colour(s), "
                            + dp.missed().size() + " missed"
                            + (dp.missed().isEmpty() ? ""
                                    : " -> " + dp.missed().stream()
                                            .map(DeclaredPalette.Outcome::describe).toList()));
                }
            }
            // REVISION SCORING. `<id>.rev1.json` beside `<id>.json` means the creature was wished
            // again with a change. That pairing is the only thing the BLOCK gate can act on -- it
            // detects a colour that was significant before and is gone after -- and until a run
            // produced one, the gate had never been observed doing anything at all.
            //
            // The wish text handed to the checks is the REVISION's text, not the original, because
            // criterion 6 is intent-awareness: a colour the child explicitly asked to change must
            // not be reported as a regression.
            List<CreatureSculpt.Part> previous = null;
            String wishText = c == null ? "" : c.prompt();
            if (name.endsWith(".rev1")) {
                Path base = f.resolveSibling(name.replace(".rev1", "") + ".json");
                if (Files.isRegularFile(base)) {
                    previous = CreatureSculpt.parseParts(JsonParser.parseString(
                            Files.readString(base)).getAsJsonObject().getAsJsonArray("parts"));
                    wishText = System.getenv("MFAM_REVISION_TEXT") == null ? wishText
                            : System.getenv("MFAM_REVISION_TEXT");
                    System.out.println("    (revision of " + base.getFileName() + ")");
                }
            }
            var sq = StructuralQuality.measure(parts, wishText);
            System.out.print("    " + sq.describe().replace("\n    ", "\n        "));
            structScores.add(sq.overall());
            var v = VisualChecks.inspect(new VisualCheck.Subject(parts, previous, wishText));
            v.blocking().forEach(b -> blocked.add(id + ": " + b));
            v.warnings().forEach(w -> System.out.println("    warn: " + w));
        }

        // VANILLA-BASELINE PIVOT RATE.
        //
        // The hypothesis worth testing: reusing a vanilla body is fine for an archetype or a
        // variant ("an enderman that teleports" IS an enderman) and wrong for a NAMED character,
        // whose specific silhouette a vanilla body cannot supply -- that is the pumpkin-headed
        // Chucko. Reported per tier so the claim is checkable rather than asserted.
        Map<String, Integer> pivotByTier = new LinkedHashMap<>();
        Map<String, Integer> totalByTier = new LinkedHashMap<>();
        try (var vs = Files.list(Path.of(dir))) {
            for (Path v : vs.filter(f -> f.toString().endsWith(".vanilla.json")).toList()) {
                JsonObject o = JsonParser.parseString(Files.readString(v)).getAsJsonObject();
                String tier = o.has("tier") ? o.get("tier").getAsString() : "?";
                pivotByTier.merge(tier, 1, Integer::sum);
                System.out.printf("%n%-16s %s | VANILLA BASELINE: %s%n",
                        o.has("corpusId") ? o.get("corpusId").getAsString() : "?", tier,
                        o.has("baseMob") ? o.get("baseMob").getAsString() : "?");
            }
        } catch (Exception ignored) {
            // no vanilla artefacts in this run
        }
        for (var c : CreatureCorpus.load()) {
            totalByTier.merge(c.tier(), 1, Integer::sum);
        }

        System.out.println("\n-----------------------------------------------------");
        if (!structScores.isEmpty()) {
            var sorted = structScores.stream().sorted().toList();
            System.out.printf("structure: min %.2f  median %.2f  max %.2f  (spread %.2f)%n",
                    sorted.get(0), sorted.get(sorted.size() / 2), sorted.get(sorted.size() - 1),
                    sorted.get(sorted.size() - 1) - sorted.get(0));
        }
        if (!pivotByTier.isEmpty()) {
            System.out.println("vanilla-baseline pivots (reused a mob instead of sculpting):");
            pivotByTier.forEach((tier, n) -> System.out.printf("    %-10s %d of %d in the corpus%n",
                    tier, n, totalByTier.getOrDefault(tier, 0)));
        }
        System.out.printf("%d creature(s); %d had checkable claims, %d/%d satisfied%n",
                files.size(), graded, satisfied, possible);
        if (declaredChecked > 0) {
            System.out.printf("%d creature(s) declared a palette first; %d/%d declared colours "
                            + "landed within tolerance%n",
                    declaredChecked, declaredMet, declaredTotal);
        }
        if (blocked.isEmpty()) {
            System.out.println("no gate would have blocked any of them");
        } else {
            System.out.println("gates would have BLOCKED:");
            blocked.forEach(b -> System.out.println("  - " + b));
        }
        System.out.println("=====================================================\n");
    }
}
