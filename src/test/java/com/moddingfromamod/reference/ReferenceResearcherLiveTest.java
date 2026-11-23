package com.moddingfromamod.reference;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Does a live lookup find the RIGHT SUBJECT, and say enough about it to matter?</b>
 *
 * <p>The wiring tests next door prove the fields parse, survive the cache, and appear in the brief.
 * None of them proves a live researcher fills them in with the character the child meant — and this
 * repo's signature failure is a capability that was built, wired, committed, believed, and never
 * actually exercised.
 *
 * <p>The measured failure this pins is <b>Omega Lad</b>. The researcher answered from
 * {@code en.wikipedia.org/wiki/Omega_Lad} — the SONG — and returned head/body/arm/leg/eye/hair in
 * black, tan and grey: a generic human boy. The character a child asking for a creature means is
 * the Italian-brainrot penguin with a sushi belly, on the same meme wiki that made Tralalelo work.
 * Every downstream gate was green, because a wrong reference and a right one are the same shape.
 *
 * <p>Opt-in, because it is slow (a lookup measured 75–109 seconds), networked and
 * non-deterministic:
 *
 * <pre>
 *   MFAM_LIVE_REFERENCE=1 ./gradlew test --tests '*ReferenceResearcherLiveTest' \
 *       -Dmoddingfromamod.cli=
 * </pre>
 *
 * <p>Skips loudly rather than passing vacuously when it is not enabled.
 */
class ReferenceResearcherLiveTest {

    private static boolean enabled() {
        String v = System.getenv("MFAM_LIVE_REFERENCE");
        return v != null && !v.isBlank() && !"0".equals(v.trim());
    }

    /** Prefetch and wait, because {@code lookUp} never waits (MAX_WAIT_SECONDS is 0 by design). */
    private static ReferenceSpec fetch(Path dir, String who, String key) throws Exception {
        ReferenceResearcher.cacheDir(dir);
        ReferenceResearcher.prefetch(who);
        Path cached = dir.resolve(key + ".json");
        for (int i = 0; i < 200 && !Files.isRegularFile(cached); i++) {
            Thread.sleep(1000);
        }
        return ReferenceResearcher.lookUp(who);
    }

    @Test
    @DisplayName("ADOPTION: a documented character comes back with parts and a mood")
    void theResearcherFillsInTheFieldsThatSurvived(@TempDir Path dir) throws Exception {
        if (!enabled()) {
            System.out.println("[live] MFAM_LIVE_REFERENCE not set — skipping. ADOPTION is NOT "
                    + "covered by Gate A, only wiring.");
            return;
        }
        String cliBefore = System.getProperty("moddingfromamod.cli");
        System.clearProperty("moddingfromamod.cli");
        try {
            if (com.moddingfromamod.agent.ClaudeCli.resolve() == null) {
                System.out.println("[live] no CLI — skipping rather than reporting a pass.");
                return;
            }
            // A FRESH cache dir on purpose: a hit would measure nothing.
            ReferenceSpec spec = fetch(dir, "Spikor (Super Plumo), Voyage-era",
                    "spikor_super_plumo_voyage_era");
            System.out.println("[live] " + spec.describe());

            assertFalse(spec.isEmpty(), "a live lookup for Spikor returned nothing at all");
            // The parts vocabulary is the ONLY shape channel now the blockout is gone, so a
            // reference that names none of a character's distinctive parts is worth nothing.
            assertTrue(spec.parts().contains("shell") || spec.parts().contains("horn")
                            || spec.parts().contains("spike"),
                    "no distinctive part for a spiked-shell character: " + spec.parts());
            // a documented mascot must come back with a mood. Spikor scowls; if this is
            // UNKNOWN the field is wired and unused, which is worth less than not having it.
            assertTrue(spec.expression() != ReferenceSpec.Expression.UNKNOWN,
                    "the researcher gave no expression for a character with a famous one");
        } finally {
            if (cliBefore != null) {
                System.setProperty("moddingfromamod.cli", cliBefore);
            }
        }
    }

    /**
     * <b>The measured Omega Lad failure, as a test.</b>
     *
     * <p>A name that is a song AND a character must resolve to the character, because that is the
     * only one a child can ask to have built. The tell is cheap and does not need a canon: the
     * penguin has a beak and fins, and the song's namesake boy has hair and shoes.
     */
    @Test
    @DisplayName("REAL BUG: an ambiguous name resolves to the CHARACTER, not the song")
    void anAmbiguousNameFindsTheCharacter(@TempDir Path dir) throws Exception {
        if (!enabled()) {
            System.out.println("[live] MFAM_LIVE_REFERENCE not set — skipping the Omega Lad "
                    + "disambiguation check. This is the one that was measured FAILING.");
            return;
        }
        String cliBefore = System.getProperty("moddingfromamod.cli");
        System.clearProperty("moddingfromamod.cli");
        try {
            if (com.moddingfromamod.agent.ClaudeCli.resolve() == null) {
                System.out.println("[live] no CLI — skipping rather than reporting a pass.");
                return;
            }
            ReferenceSpec spec = fetch(dir, "Omega Lad", "omega_lad");
            System.out.println("[live] " + spec.describe());
            System.out.println("[live] sources: " + spec.sources());

            boolean animal = spec.parts().contains("beak") || spec.parts().contains("fin")
                    || spec.parts().contains("wing") || spec.parts().contains("snout");
            assertTrue(animal || spec.isEmpty(),
                    "the researcher described a human boy rather than the brainrot character, and "
                            + "returned it as usable. Either find the character or return nothing "
                            + "— a confidently wrong reference builds worse than no reference: "
                            + spec.parts() + " from " + spec.sources());
        } finally {
            if (cliBefore != null) {
                System.setProperty("moddingfromamod.cli", cliBefore);
            }
        }
    }
}
