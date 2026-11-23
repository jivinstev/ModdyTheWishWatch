package com.moddingfromamod.agent;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.testing.GateShards;

/**
 * Gate B for the mod-owned agent session.
 *
 * <p>There is really only one thing to assert here, and it is the one that costs money to get
 * wrong: <b>a test run must never spawn a real agent.</b> If {@code shouldRun()} ever returned true
 * under the gates, every CI run would launch an autonomous Claude session that spends tokens and
 * starts changing a test world underneath the tests that are trying to assert on it. The failure
 * would be expensive, non-deterministic, and easy not to notice.
 *
 * <p>This test is running inside exactly the situation it is guarding against, so it can simply
 * ask.
 */
@GameTestHolder(GateShards.CI)
public class AgentSessionGameTest {

    /** The gates must be agent-free. This test IS a gate, so its own answer is the proof. */
    @GameTest(template = "empty_test")
    public static void noAgentIsEverSpawnedDuringTests(GameTestHelper helper) {
        helper.assertFalse(AgentSession.shouldRun(),
                Component.literal("a GameTest run must NEVER start a real agent — it would spend tokens and act "
                        + "autonomously on the very world these tests are asserting against"));
        helper.assertFalse(AgentSession.isRunning(), Component.literal("and none should be running"));
        helper.succeed();
    }

    /**
     * Asking for one anyway must be a safe no-op rather than an exception — start() is called from
     * the server-start event, and a throw there would take the whole world load with it.
     */
    @GameTest(template = "empty_test")
    public static void startingIsASafeNoOpUnderTests(GameTestHelper helper) {
        AgentSession.start("/definitely/not/a/real/claude");
        helper.assertFalse(AgentSession.isRunning(), Component.literal("still nothing running"));
        AgentSession.stop();   // must also be safe with nothing to stop
        helper.succeed();
    }

    /**
     * <b>The pool is a flag, not a redesign</b> — and it still may not spawn anything in a test.
     *
     * <p>The one that would cost real money to get wrong: raising {@code wishslots} multiplies how
     * many autonomous Claude sessions the mod launches, so if the test guard were ever bypassed it
     * would now be bypassed N times over. The guard is checked at the same place it always was, and
     * this asserts it holds at three slots as well as at one.
     */
    @GameTest(template = "empty_test")
    public static void raisingTheSlotsStillSpawnsNothingUnderTests(GameTestHelper helper) {
        try {
            com.moddingfromamod.marshal.WishSlots.override(3);
            helper.assertFalse(AgentSession.shouldRun(),
                    Component.literal("three slots must not mean three agents in a gate — it means three times the "
                            + "tokens and three autonomous actors in the test world"));
            AgentSession.start("/definitely/not/a/real/claude");
            helper.assertTrue(AgentSession.workerCount() == 0, Component.literal("nothing parked"));
            helper.assertFalse(AgentSession.isRunning(), Component.literal("and nothing running"));
            AgentSession.stop();
        } finally {
            com.moddingfromamod.marshal.WishSlots.override(null);
        }
        helper.succeed();
    }

    /** Stopping must walk EVERY worker, or a raised pool strands a daemon per slot. */
    @GameTest(template = "empty_test")
    public static void stoppingIsSafeWithAPoolOfAnySize(GameTestHelper helper) {
        for (int slots : new int[]{1, 2, 4}) {
            try {
                com.moddingfromamod.marshal.WishSlots.override(slots);
                AgentSession.stop();
                helper.assertTrue(AgentSession.sessionIds().isEmpty(),
                        Component.literal("at " + slots + " slots, stop() must leave nothing behind — a stray "
                                + "daemon per worker is what this whole mechanism prevents"));
            } finally {
                com.moddingfromamod.marshal.WishSlots.override(null);
            }
        }
        helper.succeed();
    }

    /** A missing CLI is normal (most machines won't have it) and must never break the game. */
    @GameTest(template = "empty_test")
    public static void aMissingCliIsSurvivable(GameTestHelper helper) {
        // Resolution may legitimately find a CLI on this machine or not; either way it must not
        // throw, and must not have started anything just by being asked.
        String resolved = ClaudeCli.resolve();
        helper.assertTrue(resolved == null || !resolved.isBlank(),
                Component.literal("resolve() returns a usable path or null, never an empty string"));
        helper.assertFalse(AgentSession.isRunning(), Component.literal("resolving must not start anything"));
        helper.succeed();
    }
}
