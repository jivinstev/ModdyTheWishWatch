package com.moddingfromamod.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>How often do the creature linters fire on REAL creatures?</b>
 *
 * <p>Why this exists, and it is a lesson rather than a nicety: {@code buried} and {@code floating}
 * were shipped having been validated on hand-built fixtures and one live wish, and never swept
 * across real output. The buildings counterpart was, and the answer there was <b>14 of 19 real
 * builds flagged</b> — a 74% fire rate that stopped that check from shipping, because
 * docs/WISH_LINTERS.md P6 says a linter which fires on healthy work trains the agent to skip the
 * whole note and takes the true findings with it.
 *
 * <p><b>A high rate is not automatically a false-positive rate.</b> It can equally mean the defect
 * is real and common — which is the more interesting outcome, and the one the agent could not
 * diagnose before these checks existed. So this REPORTS rather than judges; deciding which it is
 * needs someone to look at a flagged creature, exactly as it does for buildings.
 *
 * <p>Buildings have a committed corpus ({@code corpus/real-builds.jsonl}, mined by
 * {@code tools/mine-real-builds.py}). Creatures have no equivalent — the archive is machine-local
 * at {@code $MFAM_CORPUS} (default {@code ~/mfam-eval-corpus}), written by
 * {@code tools/archive-eval-runs.sh} — so a fresh clone has nothing to sweep. <b>That asymmetry is
 * why the creature sweep was skipped and the buildings one was not.</b> This skip-succeeds when the
 * archive is absent, in the same shape as the optional-mod gates.
 */
class CreatureLintSweepTest {

    /** Where the sweep looks, in order. Machine-local first, then this checkout's own runs. */
    private static List<Path> roots() {
        List<Path> out = new ArrayList<>();
        String env = System.getenv("MFAM_CORPUS");
        out.add(Path.of(env == null || env.isBlank()
                ? System.getProperty("user.home") + "/mfam-eval-corpus" : env));
        out.add(Path.of("run"));
        return out;
    }

    private static List<Path> sculpts() {
        List<Path> found = new ArrayList<>();
        for (Path root : roots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root, 8)) {
                walk.filter(p -> p.getFileName().toString().equals("sculpt.json"))
                    .forEach(found::add);
            } catch (IOException e) {
                // An unreadable archive is not a test failure -- it is a machine without one.
            }
        }
        return found;
    }

    @Test
    @DisplayName("report how often buried/floating fire on real creatures")
    void sweep() {
        List<Path> all = sculpts();
        if (all.isEmpty()) {
            System.out.println("creature-lint-sweep: no archived creatures on this machine "
                    + "(set MFAM_CORPUS, or run tools/archive-eval-runs.sh) — skipping");
            return;
        }
        int buried = 0;
        int floating = 0;
        int scanned = 0;
        for (Path p : all) {
            List<CreatureSculpt.Part> parts;
            try {
                JsonObject o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                parts = CreatureSculpt.parseParts(o.getAsJsonArray("parts"));
            } catch (IOException | RuntimeException e) {
                continue;   // a damaged archive entry must not cost the whole sweep
            }
            if (parts.isEmpty()) {
                continue;
            }
            scanned++;
            SculptCritique.Report r = SculptCritique.critique(parts);
            if (!r.buried().isEmpty()) {
                buried++;
            }
            if (!r.floating().isEmpty()) {
                floating++;
            }
            System.out.printf("creature-lint-sweep: %-46s parts=%-4d buried=%-3d floating=%d%n",
                    p.getParent().getFileName(), parts.size(), r.buried().size(),
                    r.floating().size());
        }
        if (scanned == 0) {
            return;
        }
        System.out.printf("creature-lint-sweep: %d creatures — buried fires on %d (%.0f%%), "
                + "floating on %d (%.0f%%)%n", scanned, buried, 100.0 * buried / scanned,
                floating, 100.0 * floating / scanned);
        // Report, do not judge. A rate is only interpretable against a sample worth interpreting,
        // and a threshold asserted over three creatures would be noise wearing a gate's clothes.
        assertTrue(scanned > 0);
    }
}
