package com.moddingfromamod.readymade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** the race between the hand-over nudge and the worker's first build call. */
class NudgeRaceTest {

    @Test
    @DisplayName("landed first, then the build: won, by the lead")
    void won() {
        NudgeRace r = NudgeRace.forTest();
        r.started("w");
        assertNull(r.landed("w", 1_000, true));
        assertNull(r.called("w", "plan_wish", 1_200), "a plan is not a build");
        NudgeRace.Outcome o = r.called("w", "sculpt_creature", 4_000);
        assertTrue(o.won());
        assertEquals(3_000, o.ms());
        assertTrue(o.picked());
        assertNull(r.called("w", "make_item", 5_000), "only the FIRST build decides");
    }

    @Test
    @DisplayName("built first, then landed: lost, by the lag, decided at landing")
    void lost() {
        NudgeRace r = NudgeRace.forTest();
        r.started("w");
        assertNull(r.called("w", "make_item", 2_000));
        NudgeRace.Outcome o = r.landed("w", 6_500, false);
        assertFalse(o.won());
        assertEquals(4_500, o.ms());
        assertFalse(o.picked());
    }

    @Test
    @DisplayName("a build that BEGAN before the landing but was reported after it is still a loss")
    void beganBefore() {
        NudgeRace r = NudgeRace.forTest();
        r.started("w");
        assertNull(r.landed("w", 5_000, true));
        NudgeRace.Outcome o = r.called("w", "sculpt_creature", 3_000);
        assertFalse(o.won());
        assertEquals(2_000, o.ms());
    }

    @Test
    @DisplayName("no nudge, no race; a forgotten wish decides nothing")
    void nothing() {
        NudgeRace r = NudgeRace.forTest();
        assertNull(r.called("x", "make_item", 1));
        assertNull(r.landed("x", 2, true));
        r.started("y");
        r.forget("y");
        assertNull(r.called("y", "make_item", 1));
    }
}
