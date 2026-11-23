package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** what a plan shows the child is shown after the grade passes it, and never when it refuses. */
class PlanDeferralTest {

    private final PlanDeferral d = PlanDeferral.forTest();
    private final UUID wish = UUID.randomUUID();
    private final List<String> shown = new ArrayList<>();

    @Test
    @DisplayName("no plan being graded: shown at once")
    void notHeld() {
        Runnable r = () -> shown.add("plan");
        assertSame(r, d.show(wish, r));
    }

    @Test
    @DisplayName("held while graded, then flushed in order when it passes")
    void heldThenFlushed() {
        d.hold(wish);
        assertNull(d.show(wish, () -> shown.add("bar")));
        assertNull(d.show(wish, () -> shown.add("chat")));
        assertTrue(shown.isEmpty(), "nothing reaches the child while the plan is graded");
        d.flush(wish).forEach(Runnable::run);
        assertEquals(List.of("bar", "chat"), shown);
    }

    @Test
    @DisplayName("refused: nothing held is shown — nor what the tool body tries to show after the refusal")
    void droppedStaysDropped() {
        d.hold(wish);
        assertNull(d.show(wish, () -> shown.add("bar")));
        d.drop(wish);
        assertTrue(d.flush(wish).isEmpty());
        // The grade can finish before plan_wish's body reaches its show line: that late show must not run.
        assertNull(d.show(wish, () -> shown.add("late")));
        assertTrue(shown.isEmpty(), "a refused plan reached the child: " + shown);
    }

    @Test
    @DisplayName("a re-plan after a refusal is held again, and shown when it passes")
    void replan() {
        d.hold(wish);
        d.drop(wish);
        d.hold(wish);
        assertNull(d.show(wish, () -> shown.add("second plan")));
        d.flush(wish).forEach(Runnable::run);
        assertEquals(List.of("second plan"), shown);
    }
}
