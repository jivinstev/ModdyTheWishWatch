package com.moddingfromamod.mob;


import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** the world's lit-TNT ceiling, as a pure rule. */
class TntBudgetTest {

    @Test
    void admitsUpToTheCeilingAndNotOneMore() {
        assertTrue(TntBudget.admits(0));
        assertTrue(TntBudget.admits(TntBudget.MAX_LIVE - 1));
        assertFalse(TntBudget.admits(TntBudget.MAX_LIVE));
        assertFalse(TntBudget.admits(47_000), "the volcano's worth is refused");
    }

    @Test
    void aChainIsReportedOnceASecondNotOncePerBlock() {
        assertTrue(TntBudget.worthSaying(10_000, 0));
        assertFalse(TntBudget.worthSaying(10_500, 10_000));
        assertTrue(TntBudget.worthSaying(11_000, 10_000));
    }
}
