package com.moddingfromamod.marshal;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.WishQueue;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>D10's cap is wired where it RUNS</b>.
 *
 * <p>{@code WishQueueFairnessTest} proves the rule — who goes next, the cap, the hand-off window —
 * on a queue the test builds, with a clock it can move. What no unit test can reach is one line in
 * {@code WishAgent}'s server start: the GLOBAL queue, the one {@code await_wish} blocks on, counting
 * a child's in-flight wishes off the GLOBAL Marshal. Unwired, every child reads as idle and the cap
 * never binds, while every unit test stays green.
 *
 * <p>Asked synchronously, never by parking a worker: a GameTest server ticks as fast as it can, so a
 * wait measured in real milliseconds against a budget in ticks times out whether or not the feature
 * works (CLAUDE.md). Its first cut did exactly that and read "waiting for the probe worker" with the
 * wiring cut — red, for the wrong reason.
 *
 * <p>Its own batch: it puts runs into the global Marshal, and a co-batched test reading it would see
 * them.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class FairShareGameTest {

    private FairShareGameTest() {
    }

    @GameTest(template = "empty_test", batch = "fairShare")
    public static void theGlobalQueueCountsAChildsWorkOffTheGlobalMarshal(GameTestHelper helper) {
        Marshal m = Marshal.global();
        UUID child = UUID.randomUUID();
        List<UUID> working = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                UUID id = UUID.randomUUID();
                m.submit(id, child, "gt fair " + i);
                m.assign("gt-fair-worker-" + i, id);
                working.add(id);
            }
            int seen = WishQueue.global().inFlight(child);
            helper.assertTrue(seen == 3,
                    Component.literal("the global queue sees " + seen + " of this child's 3 WORKING wishes — "
                            + "WishAgent's countingWorkWith is not wired, so D10's cap reads every "
                            + "child as idle and never binds"));
            m.finish(working.get(0));
            helper.assertValueEqual(WishQueue.global().inFlight(child), 2,
                    Component.literal("a finished wish must stop counting against him"));
        } finally {
            working.forEach(m::finish);
        }
        helper.succeed();
    }
}
