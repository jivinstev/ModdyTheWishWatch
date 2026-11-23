package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>At "All done" the wish's bar comes down, and only that wish's</b> ({@link WishProgress#endForChild}). The grading after it is told in chat; nothing on screen waits
 * for it. The other half matters as much: a newer wish of his, still being made, keeps its own bar.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class EndForChildGameTest {

    @GameTest(template = "empty_test", batch = "end_for_child", timeoutTicks = 200)
    public static void theFinishedWishsBarGoesAndTheNextWishKeepsItsOwn(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        var server = helper.getLevel().getServer();
        long now = System.currentTimeMillis();
        Wish a = WishBook.get(server).add(Wish.create("make me a dragon", WishSource.TYPED, now,
                child.getUUID(), "Steve", "minecraft:overworld", 0, 64, 0));
        Wish b = WishBook.get(server).add(Wish.create("make me a castle", WishSource.TYPED, now + 1,
                child.getUUID(), "Steve", "minecraft:overworld", 0, 64, 0));
        WishProgress.started(child, a);
        WishProgress.started(child, b);
        WishProgress.startThinking(child, a.id(), "Dragon");
        WishProgress.startThinking(child, b.id(), "Castle");
        UUID done = a.id();
        UUID next = b.id();
        WishProgress.setPlan(child, done, WishPlan.of(List.of("Building a dragon")));
        WishProgress.setPlan(child, next, WishPlan.of(List.of("Building a castle")));
        helper.assertValueEqual(WishProgress.barCount(child.getUUID()), 2, Component.literal("two wishes, two bars"));

        WishProgress.endForChild(child, done);

        helper.assertValueEqual(WishProgress.barCount(child.getUUID()), 1,
                Component.literal("the finished wish's bar is still up while Moddy grades it"));
        helper.assertTrue(WishProgress.endedForChild(done), Component.literal("the wish is not marked over for him"));
        helper.assertTrue(!WishProgress.endedForChild(next), Component.literal("the next wish was ended with it"));
        // A late setPlan for the ended wish (nothing calls one, AfterFinish refuses plan_wish) is the
        // only way back; refreshBar draws from the plan, which clearWishBar dropped.
        WishProgress.clearWishBar(next);
        WishProgress.forget(child.getUUID());
        helper.succeed();
    }
}
