package com.moddingfromamod.testing;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The abandoned-mock sweep never takes a mock its own test is still using.</b>
 *
 * <p>{@code CarrierFollowProbeGameTest} holds one mock child for 12 carriers × 3 trials × 200
 * ticks. In a full default run that is about 34 s of wall clock at ~213 ticks/s (measured on
 * {@code main}, 2026-09-27), past the sweep's 30 s. The sweep took the child mid-probe, and every
 * pet after that had no owner to follow: pig 8.5 → 8.5, three trials running. That read as "a pig
 * cannot follow", failed {@code CANNOT_FOLLOW}'s invariant, and was filed as a flake, which
 * best-of-3 only hid. The sweep's own message claims "no test can still be using it", and that
 * has to be true.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class MockSweepGameTest {

    @GameTest(template = "empty_test", batch = "mock_sweep", timeoutTicks = 100)
    public static void aMockItsOwnTestStillHoldsIsNeverSwept(GameTestHelper helper) {
        ServerPlayer kid = MockPlayerNetwork.inLevel(helper, "mfam-long-test");
        // As old as the probe's child was when it was taken, without spending 30 real seconds.
        MockPlayerNetwork.ageForTest(kid.getUUID(), MockPlayerNetwork.ABANDONED_MS + 5_000L);
        helper.runAfterDelay(20, () -> {
            boolean still = helper.getLevel().getServer().getPlayerList()
                    .getPlayer(kid.getUUID()) != null;
            helper.assertTrue(still, Component.literal("the sweep took a mock whose test is still running: after "
                    + MockPlayerNetwork.ABANDONED_MS + " ms of wall clock, a long test loses its "
                    + "child mid-test"));
            helper.succeed();
        });
    }
}
