package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: a button touches only the child who pressed it.</b>
 *
 * <p>{@code run_command} narrows a player selector to the wisher; a taught button, a template or a
 * rule replays through {@link WishExecutor#runOne} with no agent, and it did not — so
 * once {@code clear} was opened, a button holding {@code clear @a} would have emptied every child's
 * inventory. Driven through the real replay entry point with two children online.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ReplayNarrowGameTest {

    @GameTest(template = "empty_test", batch = "replaynarrow", timeoutTicks = 100)
    public static void aButtonsClearAtEveryoneTouchesOnlyThePresser(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer alice = at(helper, "Alice", 1, 1);
        ServerPlayer bob = at(helper, "Bob", 3, 1);
        alice.getInventory().setItem(5, new ItemStack(Items.DIAMOND, 5));   // a fixed slot: Inventory.add lies (LEARNINGS)
        bob.getInventory().setItem(5, new ItemStack(Items.DIAMOND, 5));
        var server = helper.getLevel().getServer();

        var cleared = WishExecutor.runOne(server, helper.getLevel(), alice.position(), alice,
                "clear @a minecraft:diamond");
        helper.assertTrue(cleared.success(), Component.literal("the presser's own clear must run: " + cleared.joined()));
        helper.assertTrue(alice.getInventory().countItem(Items.DIAMOND) == 0,
                Component.literal("Alice pressed it, so her own diamonds go: " + alice.getInventory().countItem(Items.DIAMOND)));
        helper.assertTrue(bob.getInventory().countItem(Items.DIAMOND) == 5,
                Component.literal("a button Alice pressed emptied BOB's inventory: " + bob.getInventory().countItem(Items.DIAMOND)));

        // An @e that can be a player is refused; one pinned to a wish's tag runs.
        var any = WishExecutor.runOne(server, helper.getLevel(), alice.position(), alice,
                "effect give @e[distance=..20] minecraft:levitation 5");
        helper.assertTrue(!any.success(), Component.literal("an @e that reaches Bob ran: " + any.joined()));
        var tagged = WishExecutor.runOne(server, helper.getLevel(), alice.position(), alice,
                "effect give @e[tag=gt_replaynarrow_nothing,distance=..20] minecraft:levitation 5");
        helper.assertTrue(tagged.joined().indexOf("reaches another player") < 0,
                Component.literal("a tagged @e is a wish's own thing, not another child: " + tagged.joined()));
        helper.assertTrue(!bob.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION), Component.literal("Bob was lifted"));

        MockPlayerNetwork.remove(alice);
        MockPlayerNetwork.remove(bob);
        helper.succeed();
    }

    private static ServerPlayer at(GameTestHelper helper, String name, int x, int z) {
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, name);
        BlockPos feet = helper.absolutePos(new BlockPos(x, 2, z));
        p.teleportTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
        return p;
    }
}
