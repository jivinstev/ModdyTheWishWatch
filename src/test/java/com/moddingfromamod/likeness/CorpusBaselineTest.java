package com.moddingfromamod.likeness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.reference.ReferenceSpec;
import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Score every creature ever built, before any optimisation lands.</b>
 *
 * <p>There are 135 real sculpts across 22 recorded runs in the eval corpus, with sourced references
 * cached beside them. G1-G3 are pure functions of {@code (parts, ReferenceSpec)}, so all 135 can be
 * scored <b>with no client, no agent and no model call</b> — which is what makes a before-number
 * affordable at all. Without one, every claim about rotation or part-kits is unfalsifiable.
 *
 * <p><b>Opt-in</b>, because the corpus lives outside the repo (759 MB, and it cannot be
 * regenerated):
 *
 * <pre>
 *   ./gradlew test --tests '*CorpusBaselineTest' -Dmfam.corpus=$HOME/mfam-eval-corpus
 * </pre>
 *
 * <p>It writes a tab-separated table to {@code build/likeness-baseline.tsv}. Scores are reported
 * per creature and <b>never averaged into one number</b> — the vision judge's three buckets averaged
 * to something that read as "56%" and hid the only useful fact in the measurement.
 *
 * <p>Skips (rather than fails) when the corpus is not present, so the gate stays green on a clean
 * checkout — the same contract the mod repo's own dependent tests keep.
 */
class CorpusBaselineTest {

    private static Path corpus() {
        String dir = System.getProperty("mfam.corpus", System.getenv("MFAM_CORPUS"));
        if (dir == null || dir.isBlank()) {
            return null;
        }
        Path p = Path.of(dir);
        return Files.isDirectory(p) ? p : null;
    }

    @Test
    void scoreTheWholeCorpus() throws IOException {
        Path root = corpus();
        if (root == null) {
            System.out.println("[baseline] no corpus configured (-Dmfam.corpus=...), skipping");
            return;
        }
        Map<String, ReferenceSpec> refs = loadReferences(root);
        assertFalse(refs.isEmpty(), "the corpus has no reference cache to grade against");

        List<String> rows = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        int scored = 0;
        int kitParts = 0;

        List<Path> sculpts;
        try (Stream<Path> walk = Files.walk(root, 2)) {
            sculpts = walk.filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().equals("run.json"))
                    .filter(p -> !p.toString().contains("reference-cache"))
                    .sorted()
                    .toList();
        }

        for (Path f : sculpts) {
            JsonObject o;
            try {
                o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            } catch (RuntimeException e) {
                continue;   // not a sculpt; the corpus holds run metadata too
            }
            if (!o.has("parts") || !o.get("parts").isJsonArray()) {
                continue;
            }
            List<CreatureSculpt.Part> parts = CreatureSculpt.parseParts(o.getAsJsonArray("parts"));
            if (parts.isEmpty()) {
                continue;
            }
            String id = o.has("id") ? o.get("id").getAsString()
                    : f.getFileName().toString().replace(".json", "");
            ReferenceSpec spec = match(refs, id);
            LikenessReport report = LikenessReport.of(parts, spec);
            String label = f.getParent().getFileName() + "/" + id;
            rows.add(report.row(label));
            kitParts += report.kitParts();
            if (report.scored()) {
                scored++;
            } else {
                unmatched.add(label);
            }
        }

        assertFalse(rows.isEmpty(), "found no sculpts under " + root);

        StringBuilder out = new StringBuilder();
        out.append("# Likeness baseline — recorded BEFORE #70/#71/#72 landed\n");
        out.append("# corpus: ").append(root).append('\n');
        out.append("# sculpts: ").append(rows.size())
                .append("   with a sourced reference: ").append(scored)
                .append("   parts built by a kit: ").append(kitParts).append('\n');
        out.append("# Scores are per creature and are NEVER averaged into one number.\n");
        out.append(LikenessReport.header()).append('\n');
        rows.forEach(r -> out.append(r).append('\n'));
        if (!unmatched.isEmpty()) {
            out.append("# no reference found for: ").append(String.join(", ", unmatched))
                    .append('\n');
        }

        Path target = Path.of("build", "likeness-baseline.tsv");
        Files.createDirectories(target.getParent());
        Files.writeString(target, out.toString());
        System.out.println("[baseline] " + rows.size() + " sculpts, " + scored
                + " with a reference -> " + target.toAbsolutePath());

        // The baseline is only meaningful if the graders actually engaged with real output. If
        // nothing matched a reference the file is a list of dashes, and publishing it as "the
        // baseline" would be worse than having none.
        assertTrue(scored > 0, "not one sculpt matched a cached reference");

        // Kit parts must be ZERO here by construction: the corpus predates part-kits entirely.
        // If this ever trips, the "before" number has been contaminated by "after" data.
        assertTrue(kitParts == 0,
                "the historical corpus cannot contain kit-built parts — found " + kitParts);
    }

    /** Every cached reference in the corpus, keyed by its file name. */
    private static Map<String, ReferenceSpec> loadReferences(Path root) throws IOException {
        Map<String, ReferenceSpec> out = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root, 3)) {
            for (Path f : walk.filter(p -> p.getParent() != null
                            && p.getParent().getFileName().toString().contains("reference-cache"))
                    .filter(p -> p.toString().endsWith(".json")).toList()) {
                ReferenceSpec spec = ReferenceSpec.parse(Files.readString(f));
                if (spec != null && !spec.isEmpty()) {
                    out.put(f.getFileName().toString().replace(".json", "")
                            .toLowerCase(Locale.ROOT), spec);
                }
            }
        }
        return out;
    }

    /**
     * Find the reference for a creature id.
     *
     * <p>Exact first, then longest-prefix — the cache is keyed by character name
     * ({@code zippy_the_hedgehog}) while a sculpt is keyed by its short id ({@code zippy}), and a
     * shortest-match rule would tie {@code spikor} to
     * {@code spikor_super_plumo_modern_voyage_era} arbitrarily.
     */
    private static ReferenceSpec match(Map<String, ReferenceSpec> refs, String id) {
        String key = id.toLowerCase(Locale.ROOT).replaceAll("\\.rev\\d+$", "");
        ReferenceSpec exact = refs.get(key);
        if (exact != null) {
            return exact;
        }
        ReferenceSpec best = null;
        int bestLen = Integer.MAX_VALUE;
        for (var e : refs.entrySet()) {
            if (e.getKey().startsWith(key) && e.getKey().length() < bestLen) {
                best = e.getValue();
                bestLen = e.getKey().length();
            }
        }
        return best;
    }
}
