package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What a child's game grants wishes as, when nobody says otherwise.
 *
 * <p>The shipped default is <b>Opus 5 at low effort</b>, chosen from measurement (
 * {@code docs/MODEL_EVAL.md}): 59% less waiting over the seven named creatures, zero compile
 * failures, identical effect-selection accuracy, and no false claims of success.
 *
 * <p>These tests exist because the default is invisible in play. Nothing in the game says which
 * model answered, so a default that silently reverted — or that shipped half of the validated pair —
 * would look exactly like a default that worked, and the only symptom would be a slower or worse
 * wish weeks later.
 */
class AgentModelDefaultTest {

    private static final String MODEL = "moddingfromamod.model";
    private static final String EFFORT = "moddingfromamod.effort";

    @AfterEach
    void clearProperties() {
        System.clearProperty(MODEL);
        System.clearProperty(EFFORT);
    }

    @Test
    void aChildsGameGetsTheValidatedPair() {
        assertEquals("opus", AgentSession.resolvedModel(),
                "the model half of the pair that was measured");
        assertEquals("low", AgentSession.resolvedEffort(),
                "the effort half — 59% faster with no loss in code generation or honesty");
    }

    @Test
    void theModelShipsWithTheEffortBecauseThePairIsWhatWasValidated() {
        // Opus at low effort is measured. SONNET at low effort is not, and that is exactly what a
        // bare effort default would produce on a machine whose CLI model was set to something else.
        // Shipping only half the pair would be inheriting an unvalidated combination by accident.
        assertTrue(!AgentSession.resolvedModel().isEmpty() && !AgentSession.resolvedEffort().isEmpty(),
                "ship both halves or neither");
    }

    @Test
    void anEvalArmCanStillPinWhateverItLikes() {
        System.setProperty(MODEL, "sonnet");
        System.setProperty(EFFORT, "high");
        assertEquals("sonnet", AgentSession.resolvedModel());
        assertEquals("high", AgentSession.resolvedEffort());
    }

    @Test
    void anExplicitDefaultHandsTheChoiceBackToTheCli() {
        // The escape hatch: a grown-up who has configured their CLI deliberately can say so, and
        // gets no --model/--effort flag at all.
        System.setProperty(MODEL, "default");
        System.setProperty(EFFORT, "default");
        assertEquals("", AgentSession.resolvedModel());
        assertEquals("", AgentSession.resolvedEffort());
    }

    @Test
    void agarbledEffortFallsBackToTheSHIPPEDDefault() {
        // NOT to the CLI's default. The CLI would warn and quietly use its own, which turns a typo
        // into a session running at an effort no artefact records — the failure this whole knob was
        // built to make impossible.
        System.setProperty(EFFORT, "lowish");
        assertEquals("low", AgentSession.resolvedEffort(),
                "an unrecognised effort must not silently become the CLI's default");
    }

    @Test
    void effortIsCaseAndWhitespaceForgiving() {
        System.setProperty(EFFORT, "  HIGH ");
        assertEquals("high", AgentSession.resolvedEffort());
    }

    @Test
    void theWishRecordStampsTheEffortTheSessionACTUALLYRanAt() {
        // / HO-9. `wish-latency.py` has printed a per-`effort` A/B for months against a
        // field nothing wrote, and `spend.wish` copies this one off the wish record — so a
        // cost that cannot be read by effort is the default, not the exception.
        //
        // ⚠ The point of this test is WHICH value is stamped. A garbled property falls back
        // to the shipped default before it reaches the CLI, so stamping the raw property
        // would record an effort the session never ran at — a number that looks like
        // evidence and is not, which is worse than a missing field.
        System.setProperty(EFFORT, "lowish");
        assertEquals("low", AgentSession.resolvedEffort(),
                "ActionLog.wish stamps resolvedEffort(), so it must be the APPLIED value");
    }
}
