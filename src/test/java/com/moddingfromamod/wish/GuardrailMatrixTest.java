package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A new wish class cannot ship with an empty guardrail column.</b>
 *
 * <p> Phase 4. The ✅/⚠️/⬜ grid in {@code docs/WISH_BUILD_GUARDRAILS.md} renders perfectly with a
 * whole wish class missing from it, and an absent column reads as "no known gaps". This joins the
 * matrix to {@link ReplayContract#ALL} — the list a new wish class must already be added to — so
 * forgetting the guardrail column fails the build by name. The published grid's own columns are checked by
 * {@code GuardrailGridDocTest}, beside the document.
 */
class GuardrailMatrixTest {

    /** The wish classes that actually exist, from the contract every one of them must satisfy. */
    private static List<String> wishClasses() {
        return ReplayContract.ALL.stream().map(ReplayContract.Replayable::feature).toList();
    }

    @Test
    @DisplayName("every real wish class declares where it stands on every principle")
    void theMatrixCoversEveryWishClass() {
        List<String> problems = GuardrailMatrix.violations(wishClasses());
        assertTrue(problems.isEmpty(),
                "The guardrail matrix is incomplete or dishonest:\n  - "
                        + String.join("\n  - ", problems)
                        + "\n\nEvery wish class must say, for every principle, that it HOLDS, is "
                        + "NOT_APPLICABLE (with a reason), or is a GAP (with an issue). Silence is "
                        + "the state that stops being re-examined.");
    }

    /**
     * The join must actually be a join. If the two tables stopped sharing key names this test would
     * pass while checking nothing, which is the failure mode it is here to prevent.
     */
    @Test
    @DisplayName("the matrix is keyed the same way as the replay contract")
    void theMatrixJoinsToTheReplayContract() {
        List<String> classes = wishClasses();
        assertFalse(classes.isEmpty(), "no wish classes found — the join proves nothing");
        for (String kind : classes) {
            assertTrue(GuardrailMatrix.MATRIX.containsKey(kind),
                    "wish class '" + kind + "' from ReplayContract.ALL has no column in "
                            + "GuardrailMatrix.MATRIX — the two tables have drifted apart, so the "
                            + "coverage check above is not actually checking coverage");
        }
    }

    /**
     * The known gaps are the ones the doc already admits to, each tracked. This pins them: closing
     * one means updating the matrix, and a NEW gap appearing means someone must justify it here
     * rather than quietly widening a ⚠️ in prose.
     */
    @Test
    @DisplayName("every open gap cites a tracking issue")
    void openGapsAreTracked() {
        List<String> gaps = GuardrailMatrix.openGaps();
        assertFalse(gaps.isEmpty(),
                "no gaps at all is suspicious rather than reassuring — issues #1 and #2 are open, "
                        + "so if this list is empty the matrix has stopped reflecting reality");
        for (String gap : gaps) {
            assertTrue(gap.matches(".*\\(issue #\\d+\\)$"),
                    "a gap without a tracking issue is a shrug: " + gap);
        }
    }
}
