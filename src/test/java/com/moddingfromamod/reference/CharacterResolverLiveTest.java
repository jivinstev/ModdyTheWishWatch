package com.moddingfromamod.reference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The <b>live</b> half of character resolution — the assertions that need a real model call,
 * kept out of Gate A but not thrown away.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code CharacterResolver.classify} shells out to the {@code claude} CLI. Gate A is now hermetic
 * ({@code -Dmoddingfromamod.cli=none} in the {@code test} task), because a unit suite making live
 * model calls fails intermittently, is sensitive to machine load, and is slow — three separate runs
 * were lost to it in one day, and the first diagnosis blamed static state that was already being
 * cleared.
 *
 * <p>But "make Gate A hermetic" alone would silently stop testing the classifier at all. The
 * behaviour still matters: the classifier is what tells a wish about SPIKOR from a wish about a
 * creature the child invented, and the offline heuristic is a fallback rather than the real thing.
 * So it keeps a home here, in a test that is allowed to be slow and networked and is <b>opt-in</b>:
 *
 * <pre>
 *   MFAM_LIVE_CLASSIFIER=1 ./gradlew test --tests '*CharacterResolverLiveTest' -Dmoddingfromamod.cli=
 * </pre>
 *
 * <p>Skips loudly rather than passing vacuously when it is not enabled — a green tick for a test
 * that never ran is the disease this whole issue is about.
 */
class CharacterResolverLiveTest {

    private static boolean enabled() {
        String v = System.getenv("MFAM_LIVE_CLASSIFIER");
        return v != null && !v.isBlank() && !"0".equals(v.trim());
    }

    private String cliBefore;

    @BeforeEach
    void allowTheCli() {
        // Gate A pins this to "none"; the live test has to undo that for itself.
        cliBefore = System.getProperty("moddingfromamod.cli");
        System.clearProperty("moddingfromamod.cli");
        CharacterResolver.clear();
        CharacterLineage.clear();
    }

    @AfterEach
    void restore() {
        if (cliBefore != null) {
            System.setProperty("moddingfromamod.cli", cliBefore);
        }
        CharacterResolver.clear();
        CharacterLineage.clear();
    }

    @Test
    void theLiveClassifierTellsARealCharacterFromAnInventedOne() {
        if (!enabled()) {
            System.out.println("[live] MFAM_LIVE_CLASSIFIER not set — skipping the live classifier "
                    + "assertions. They are NOT covered by Gate A, which is hermetic by design.");
            return;
        }
        if (com.moddingfromamod.agent.ClaudeCli.resolve() == null) {
            System.out.println("[live] enabled but no CLI could be resolved — skipping rather than "
                    + "asserting the offline heuristic and calling it a live pass.");
            return;
        }
        var real = CharacterResolver.resolve("Make SPIKOR, king of the Kroaks! He can breathe FIRE!");
        assertTrue(real.isPresent() && real.get().toLowerCase().contains("spikor"),
                "a named character should resolve, got " + real);

        var invented = CharacterResolver.resolve(
                "invent me a creature called Zizzle, a spiky orange lizard with three tails");
        assertTrue(invented.isEmpty(),
                "a creature the child invented has no reference to look up, got " + invented);
    }

    @Test
    void aFollowUpNamingSomebodyElseWinsOverTheLineage() {
        if (!enabled() || com.moddingfromamod.agent.ClaudeCli.resolve() == null) {
            return;
        }
        // The assertion that was flaky in Gate A. It belongs here: its answer comes from a model.
        CharacterLineage.remember("omega_lad", "Omega Lad");
        var r = CharacterLineage.resolve("now make Tralalelo Tralalala the shark", "omega_lad");
        assertTrue(r.isPresent() && r.get().toLowerCase().contains("tralal"),
                "the wish named someone new: " + r);
    }
}
