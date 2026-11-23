package com.moddingfromamod.runtime.gate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Did arm B build better creatures than arm A?</b>
 *
 * <pre>
 * MFAM_RUN_A=/tmp/run-before MFAM_RUN_B=/tmp/run-after \
 *     ./gradlew test --tests '*CreatureRunCompareTest' -i
 * </pre>
 *
 * <p>Exists because answering that question previously took hand-written throwaway scripts and a
 * person reading two tables — for a project whose entire point is measuring quality automatically.
 * Worse, the throwaway script reimplemented {@link com.moddingfromamod.runtime.SculptCritique}'s
 * colour bucketing, so the comparison was being made against a second, divergent definition of what
 * "green" means.
 *
 * <p><b>What this can and cannot say.</b> It reports movement in the things that are checkable
 * without a reference: how many of the child's stated properties were satisfied, and how faithfully
 * the agent built the palette it declared. It deliberately does <b>not</b> claim a creature looks
 * more like Spikor — reference-based distance is excluded from this project because both previous
 * attempts rested on reference values written from memory, and one of them inverted completely once
 * the real source was read. Until references are <i>sourced</i> (the walled researcher), "closer to
 * the character" stays a human call, and this tool says so rather than inventing a number.
 */
class CreatureRunCompareTest {

    /** One run's score for one creature. */
    private record Score(int satisfied, int total, int declaredMet, int declaredTotal, int parts) {

        static Score of(Path sculpt, String prompt) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(sculpt)).getAsJsonObject();
                var parts = CreatureSculpt.parseParts(o.getAsJsonArray("parts"));
                double scale = o.has("scale") ? o.get("scale").getAsDouble() : 1.0;
                var np = NamedProperties.grade(prompt == null ? "" : prompt, parts, scale);
                var dp = DeclaredPalette.check(o, parts);
                return new Score(np.satisfied(), np.total(),
                        dp.outcomes().size() - dp.missed().size(), dp.outcomes().size(),
                        parts.size());
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static Map<String, Path> sculpts(Path dir) {
        Map<String, Path> out = new LinkedHashMap<>();
        try (var s = Files.list(dir)) {
            s.filter(f -> f.toString().endsWith(".json"))
             .filter(f -> f.getFileName().toString().contains("__"))
             .filter(f -> !f.getFileName().toString().startsWith("declared-"))
             .forEach(f -> {
                 String n = f.getFileName().toString().replace(".json", "");
                 out.put(n.substring(n.indexOf("__") + 2), f);
             });
        } catch (Exception ignored) {
            // an unreadable directory is reported by the assumption below
        }
        return out;
    }

    @Test
    @DisplayName("compare: arm A vs arm B, on everything checkable without a reference")
    void compare() {
        String a = System.getenv("MFAM_RUN_A");
        String b = System.getenv("MFAM_RUN_B");
        Assumptions.assumeTrue(a != null && b != null
                        && Files.isDirectory(Path.of(a)) && Files.isDirectory(Path.of(b)),
                "SKIPPED — set MFAM_RUN_A and MFAM_RUN_B to two run directories");

        Map<String, Path> runA = sculpts(Path.of(a));
        Map<String, Path> runB = sculpts(Path.of(b));
        Map<String, String> prompts = new LinkedHashMap<>();
        com.moddingfromamod.wish.CreatureCorpus.load()
                .forEach(c -> prompts.put(c.id(), c.prompt()));

        System.out.println("\n============== A/B: " + a + "  vs  " + b + " ==============");
        System.out.printf("%-22s %-18s %-18s%n", "creature", "stated props", "declared palette");

        int aSat = 0;
        int aTot = 0;
        int bSat = 0;
        int bTot = 0;
        int aMet = 0;
        int aDec = 0;
        int bMet = 0;
        int bDec = 0;
        int both = 0;

        for (String id : new TreeSet<>(runA.keySet())) {
            if (!runB.containsKey(id)) {
                continue;
            }
            String prompt = prompts.get(id);
            if (prompt == null) {
                // ids are the agent's choice, so fall back to the closest corpus prompt
                prompt = prompts.entrySet().stream()
                        .filter(e -> id.contains(e.getKey()) || e.getKey().contains(id))
                        .map(Map.Entry::getValue).findFirst().orElse("");
            }
            Score sa = Score.of(runA.get(id), prompt);
            Score sb = Score.of(runB.get(id), prompt);
            if (sa == null || sb == null) {
                continue;
            }
            both++;
            aSat += sa.satisfied();
            aTot += sa.total();
            bSat += sb.satisfied();
            bTot += sb.total();
            aMet += sa.declaredMet();
            aDec += sa.declaredTotal();
            bMet += sb.declaredMet();
            bDec += sb.declaredTotal();

            String props = sa.total() + sb.total() == 0 ? "—"
                    : sa.satisfied() + "/" + sa.total() + "  ->  " + sb.satisfied() + "/" + sb.total();
            String pal = sa.declaredTotal() + sb.declaredTotal() == 0 ? "—"
                    : sa.declaredMet() + "/" + sa.declaredTotal() + "  ->  "
                            + sb.declaredMet() + "/" + sb.declaredTotal();
            System.out.printf("  %-20s %-18s %-18s%n", id, props, pal);
        }

        System.out.println("  " + "-".repeat(58));
        System.out.printf("  %d creature(s) in both runs%n", both);
        System.out.printf("  stated properties satisfied:   A %d/%d   ->   B %d/%d%n",
                aSat, aTot, bSat, bTot);
        System.out.printf("  declared colours honoured:     A %d/%d   ->   B %d/%d%n",
                aMet, aDec, bMet, bDec);
        System.out.println();
        System.out.println("  NOT measured here: whether a creature LOOKS more like the character.");
        System.out.println("  Reference distance is excluded until references are sourced rather");
        System.out.println("  than recalled — two earlier attempts used values written from memory");
        System.out.println("  and one inverted entirely once the real source was read.");
        System.out.println("=".repeat(62) + "\n");
    }
}
