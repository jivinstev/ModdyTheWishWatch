package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gate A for {@link HeldTurnMeter}, the instrument of {@code RigPowersGameTest.aChildStraightOverheadDoesNotSpinTheCreature}.
 *
 * <p>In one server tick the levels tick (the carrier's look control), then {@code GameTestTicker} runs the test's
 * {@code onEachTick}, then {@code ServerTickEvent.Post} runs {@code VanillaRigAnimator}, which decides the drawn yaw
 * from THAT tick's look. So the yaw a test reads on tick N was decided on tick N-1's state. Counting it against tick
 * N's state charged the rig with the one tick it turned after its creature glanced sideways (a non-steep look, where
 * turning is the design), whenever the next tick's look was steep again: two such ticks, 10 degrees each, failed the
 * test at 20 degrees (gate run 20261116T015452Z-1079c2).
 */
class HeldTurnMeterTest {

    @Test
    @DisplayName("a turn the rig made on a tick it was NOT meant to hold is not charged to the next tick, which was")
    void aTurnReadOneTickLateIsChargedToTheTickThatDecidedIt() {
        HeldTurnMeter m = new HeldTurnMeter();
        for (int t = 0; t < 30; t++) {
            m.tick(true, 0f, 0f);
        }
        // Tick A: the creature glances sideways (not steep): the animator, after the test, turns 10 degrees.
        m.tick(false, 0f, 0f);
        // Tick A+1: the look is steep again; the test now reads the 10 degrees decided on tick A.
        m.tick(true, 10f, 0f);
        for (int t = 0; t < 30; t++) {
            m.tick(true, 10f, 0f);
        }
        assertEquals(0f, m.total(), 1e-4, "the turn belongs to the sideways glance, where turning is right");
        assertEquals(0f, m.worst(), 1e-4);
    }

    @Test
    @DisplayName("the control: a turn the rig made on a tick it WAS meant to hold is counted")
    void aTurnWhileHeldIsCounted() {
        HeldTurnMeter m = new HeldTurnMeter();
        for (int t = 0; t < 30; t++) {
            m.tick(true, 0f, 0f);
        }
        // Tick A holds, and the animator turns anyway (the bug): read on tick A+1, whatever A+1's state is.
        m.tick(true, 0f, 0f);
        m.tick(false, 10f, 0f);
        m.tick(true, 10f, 0f);
        assertEquals(10f, m.total(), 1e-4);
        assertEquals(10f, m.worst(), 1e-4);
    }

    @Test
    @DisplayName("the carrier's own yaw is read in step with this tick's state, and wraps round")
    void rawIsThisTicksAndWraps() {
        HeldTurnMeter m = new HeldTurnMeter();
        m.tick(true, 0f, 175f);
        m.tick(true, 0f, -175f);
        m.tick(true, 0f, -165f);
        assertEquals(20f, m.raw(), 1e-4);
        assertEquals(2, m.counted());
    }
}
