package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.reveal.FanfareTiming;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B for a served wish's fanfare</b>: the promises a headless server can
 * check, driven through the REAL {@link WishExecutor#submit} a button, a cached wish or a starter
 * calls. <b>Nothing lands before its moment</b>: the plan runs at the reveal's climax, not when he
 * taps. <b>A stop before the climax runs nothing.</b> <b>An item is flown in</b>: in his inventory
 * only once the spiral reaches him. How it looks is the films' (Gate C).
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ServedFanfareGameTest {

    private static final String BATCH = "servedfanfare";

    /**
     * A child standing in THIS test's own plot. A mock joins at world spawn, and every test
     * in this batch runs at once: four children within a few blocks of one spawn point, where two
     * can share a block. Then {@code give @p} — nearest to where HE stands — ties with a sibling and
     * can hand the diamonds to the wrong child, and a sibling's {@code setblock ~1 ~1 ~} can land on
     * the spot another test asserts stays empty. In his own plot nobody else is near him.
     */
    private static ServerPlayer childInItsOwnPlot(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        BlockPos here = helper.absolutePos(new BlockPos(1, 2, 1));
        child.connection.teleport(here.getX() + 0.5, here.getY(), here.getZ() + 0.5, 0f, 0f);
        return child;
    }

    private static Wish wish(GameTestHelper helper, ServerPlayer child, String text) {
        return Wish.create(text, WishSource.PRESET, System.currentTimeMillis(), child.getUUID(),
                child.getGameProfile().name(), helper.getLevel().dimension().location().toString(),
                child.getX(), child.getY(), child.getZ());
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 300)
    public static void aServedWishLandsAtItsClimaxNotWhenHeTaps(GameTestHelper helper) {
        ServerPlayer child = childInItsOwnPlot(helper);
        ServedFanfare.forgetForTest();
        BlockPos gold = child.blockPosition().offset(1, 1, 0);
        WishExecutor.submit(child, wish(helper, child, "a gold block please"),
                new CommandPlan(List.of("setblock ~1 ~1 ~ minecraft:gold_block"), "Ta-da!", 1));
        helper.assertTrue(ServedFanfare.pending() >= 1, Component.literal("the served wish never reached its fanfare"));
        // Half way up the pillar: nothing may have landed yet.
        helper.runAfterDelay(FanfareTiming.FULL_RISE / 2, () -> helper.assertTrue(
                !helper.getLevel().getBlockState(gold).is(Blocks.GOLD_BLOCK),
                Component.literal("the served wish landed while its pillar was still rising, which is the instant "
                        + "no-fanfare behaviour the served fanfare exists to remove")));
        // Then it lands: waited for, not looked for at a guessed tick. After the climax its
        // commands go to the executor's drip, which every served wish on the server shares.
        helper.startSequence()
                .thenExecuteAfter(FanfareTiming.FULL_RISE / 2 + 1, () -> { })
                .thenWaitUntil(() -> helper.assertTrue(
                        helper.getLevel().getBlockState(gold).is(Blocks.GOLD_BLOCK),
                        Component.literal("the served wish never landed after its reveal")))
                .thenExecute(() -> MockPlayerNetwork.remove(child))
                .thenSucceed();
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 300)
    public static void aStopBeforeTheClimaxRunsNothing(GameTestHelper helper) {
        ServerPlayer child = childInItsOwnPlot(helper);
        ServedFanfare.forgetForTest();
        BlockPos gold = child.blockPosition().offset(1, 1, 0);
        WishExecutor.submit(child, wish(helper, child, "a gold block please"),
                new CommandPlan(List.of("setblock ~1 ~1 ~ minecraft:gold_block"), "Ta-da!", 1));
        helper.runAfterDelay(10, () -> {
            var out = WishCancel.stop(child, null, com.moddingfromamod.marshal.CancelMode.STOP, null);
            helper.assertTrue(out.stopped(), Component.literal("/moddy stop said nothing to stop while a served "
                    + "wish's pillar was rising"));
        });
        helper.runAfterDelay(FanfareTiming.FULL_RISE + 40, () -> {
            helper.assertTrue(!helper.getLevel().getBlockState(gold).is(Blocks.GOLD_BLOCK),
                    Component.literal("a stopped served wish still ran its commands"));
            MockPlayerNetwork.remove(child);
            helper.succeed();
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 300)
    public static void anItemIsInHisHandOnlyWhenTheSpiralReachesHim(GameTestHelper helper) {
        ServerPlayer child = childInItsOwnPlot(helper);
        ServedFanfare.forgetForTest();
        child.getInventory().clearContent();
        String name = child.getGameProfile().name();
        Wish w = wish(helper, child, "three diamonds");
        WishExecutor.submit(child, w, new CommandPlan(List.of("give @p minecraft:diamond 3"),
                "Shiny!", 1));
        // Every step waits on THIS fanfare's own phase, never on a tick counted from the test's
        // start: a fixed FULL_RISE + ITEM_HANDOFF + 15 left 13 ticks of slack and went red
        // twice in full runs ("holds 0") while passing alone.
        helper.startSequence()
                // The commands have run (the diamonds exist) and the Spiral is flying them in.
                .thenWaitUntil(() -> helper.assertTrue(ServedFanfare.handingOff(w.id()),
                        Component.literal("the three-diamonds fanfare never reached its hand-off")))
                .thenExecute(() -> helper.assertTrue(
                        child.getInventory().countItem(Items.DIAMOND) == 0,
                        Component.literal("the diamonds were in " + name + "'s inventory before the spiral reached him")))
                // Half way along the Spiral they are still in flight, not in his hand.
                .thenExecuteAfter(FanfareTiming.ITEM_HANDOFF / 2, () -> helper.assertTrue(
                        !ServedFanfare.handingOff(w.id())
                                || child.getInventory().countItem(Items.DIAMOND) == 0,
                        Component.literal("the diamonds reached " + name + "'s inventory while the spiral was "
                                + "still flying them")))
                .thenWaitUntil(() -> helper.assertTrue(!ServedFanfare.active(w.id()),
                        Component.literal("the three-diamonds fanfare never finished its hand-off")))
                .thenExecute(() -> {
                    int got = child.getInventory().countItem(Items.DIAMOND);
                    helper.assertTrue(got == 3, Component.literal("after the spiral he should hold exactly the 3 "
                            + "diamonds the plan gave; holds " + got + whereTheyWent(helper, child)));
                    MockPlayerNetwork.remove(child);
                })
                .thenSucceed();
    }

    /** When he does not hold them: who does — another child, or his Wish Bag. */
    private static String whereTheyWent(GameTestHelper helper, ServerPlayer child) {
        StringBuilder out = new StringBuilder();
        var server = helper.getLevel().getServer();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            int n = p.getInventory().countItem(Items.DIAMOND);
            if (p != child && n > 0) {
                out.append("; ").append(p.getGameProfile().name()).append(" at ")
                        .append(p.blockPosition()).append(" holds ").append(n);
            }
        }
        int bag = com.moddingfromamod.bag.WishBagService.size(server, child.getUUID());
        if (bag > 0) {
            out.append("; his Wish Bag holds ").append(bag).append(" entr").append(bag == 1 ? "y" : "ies");
        }
        return out.toString();
    }

    /**
     * A starter item is a datapack's give, and the first time that pack is used it is installed and
     * reloaded asynchronously, so the item lands a tick or two AFTER the commands ran. The
     * fanfare must catch it when it lands and still fly it in, not give up and play a burst. The
     * late give is simulated by the test itself while the plan's own command gives nothing.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 300)
    public static void anItemThatLandsLateIsStillFlownIn(GameTestHelper helper) {
        ServerPlayer child = childInItsOwnPlot(helper);
        ServedFanfare.forgetForTest();
        child.getInventory().clearContent();
        String name = child.getGameProfile().name();
        // Read as an item reveal (a datapack's give) but gives nothing itself.
        Wish w = wish(helper, child, "a late emerald");
        WishExecutor.submit(child, w, new CommandPlan(List.of("wishdatapack no_such_pack give"),
                "Shiny!", 1));
        // The "datapack" gives once the plan has RUN — i.e. once the fanfare is catching — never
        // at a tick counted from this test's start: the fanfare takes the inventory
        // snapshot it measures "gained" against when its own rise ends, and a give that lands
        // before that is part of the snapshot, is never gained, and is never caught.
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ServedFanfare.catching(w.id()),
                        Component.literal("the late-emerald fanfare never reached its catch")))
                .thenExecute(() -> com.moddingfromamod.item.HandOver.toPlayer(child,
                        new net.minecraft.world.item.ItemStack(Items.EMERALD, 2)))
                .thenExecuteAfter(6, () -> helper.assertTrue(
                        child.getInventory().countItem(Items.EMERALD) == 0,
                        Component.literal("the late emeralds stayed in " + name + "'s inventory instead of being "
                                + "caught and flown in")))
                .thenWaitUntil(() -> helper.assertTrue(!ServedFanfare.active(w.id()),
                        Component.literal("the late-emerald fanfare never finished its hand-off")))
                .thenExecute(() -> {
                    int got = child.getInventory().countItem(Items.EMERALD);
                    helper.assertTrue(got == 2,
                            Component.literal("after the spiral he should hold the 2 emeralds; holds " + got));
                    MockPlayerNetwork.remove(child);
                })
                .thenSucceed();
    }
}
