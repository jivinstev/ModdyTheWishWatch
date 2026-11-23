package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.moddingfromamod.wish.WishVerdict;
import org.junit.jupiter.api.Test;

/**
 * <b>The verdict that is recorded is checked against what the mod knows.</b>
 *
 * <p>Coerced, never refused: every other gate in this repo asks the agent to DO something more,
 * which may be impossible, so each must give way or a wish could never end. This asks it to SAY
 * something different, which is always possible.
 *
 * <p>Until this file also pinned the Create machine and train downgrades: `done`
 * over an unproven machine or a train in pieces was recorded as `rough`. Both went with Create; what
 * remains is the creature that is standing nowhere.
 *
 * <p><b>These tests are behavioural on purpose.</b> The first version asserted on the source text of
 * {@code FinishWishTool}, and a mutation that disabled the rule left every one of them passing.
 */
class FinishWishVerdictTest {

    /** a creature this wish spawned is standing nowhere — nothing was granted. */
    @Test
    void aCreatureStandingNowhereIsNeverGranted() {
        for (WishVerdict claimed : new WishVerdict[] {
                WishVerdict.DONE, WishVerdict.COULD_BE_BETTER, WishVerdict.ROUGH}) {
            assertEquals(WishVerdict.FAILED,
                    FinishWishTool.honestVerdict(claimed, "giant_sea_leaper"),
                    claimed + " over a creature that is not there still files the wish as DONE");
        }
        assertEquals(WishVerdict.FAILED,
                FinishWishTool.honestVerdict(WishVerdict.FAILED, "giant_sea_leaper"));
    }

    @Test
    void everyVerdictStandsWhenNothingIsMissing() {
        for (WishVerdict v : WishVerdict.values()) {
            assertEquals(v, FinishWishTool.honestVerdict(v, null),
                    v + " must stand when the creature is standing there");
        }
    }
}
