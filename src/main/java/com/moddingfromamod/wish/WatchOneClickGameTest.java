package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.compat.Consumables;
import com.moddingfromamod.compat.Cooldowns;
import com.moddingfromamod.compat.Interactions;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>One click, one hand — for Moddy.</b> A right-click on
 * Moddy opened the panel AND emptied the backpack in the player's offhand.
 *
 * <p>A stock client decides by itself whether a right-click used the main hand, by running the
 * item's own vanilla {@code use}; only a success stops {@code Minecraft.startUseItem} going on to
 * the offhand. So these ask exactly what the CLIENT asks — vanilla {@code Item.use} on the stack,
 * with none of our server events in the way — and the film ({@code tools/watch-pin-film.sh}, rows
 * {@code right_click_offhand*}, on newer clients through a version proxy) is the end-to-end proof.
 * On the 1.21.1 compat build nothing is stamped ({@link Consumables#STAMPS}) and the stamp tests
 * say so and pass. Its own batch: {@code VanillaCarrier.force} is a global static.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WatchOneClickGameTest {

    private static final String BATCH = "watch_one_click";

    private static ServerPlayer holding(GameTestHelper helper, String name, ItemStack stack) {
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, name);
        p.getInventory().clearContent();
        p.getInventory().setItem(0, stack);
        p.getInventory().setSelectedSlot(0);
        return p;
    }

    private static InteractionResult vanillaUse(ServerPlayer p) {
        ItemStack held = p.getMainHandItem();
        try {
            return Interactions.use(held.getItem(), p.level(), p, InteractionHand.MAIN_HAND);
        } finally {
            p.stopUsingItem();
        }
    }

    /** What a stock client computes for Moddy: a success, so it never goes on to the offhand. */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void moddysOwnUseStopsTheClickAtTheMainHand(GameTestHelper helper) {
        if (!Consumables.STAMPS) {
            helper.succeed();
            return;
        }
        ServerPlayer p = holding(helper, "oneclick", VanillaWatchItem.stack());
        try {
            InteractionResult r = vanillaUse(p);
            helper.assertTrue(r.consumesAction(),
                    Component.literal("vanilla use of Moddy must succeed, or a stock client uses the offhand too: " + r));
            helper.assertTrue(p.getMainHandItem().getCount() == 1 && VanillaWatchItem.isWatch(p.getMainHandItem()),
                    Component.literal("starting the use must not take anything from him: " + p.getMainHandItem()));
        } finally {
            MockPlayerNetwork.remove(p);
        }
        helper.succeed();
    }

    /** The control: the same carrier WITHOUT the stamp is PASS — the fall-through that was hit in play. */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void anUnstampedWatchStillFallsThroughTheControl(GameTestHelper helper) {
        ItemStack old = VanillaWatchItem.stack();
        Consumables.unstamp(old);
        ServerPlayer p = holding(helper, "oneclick_ctl", old);
        try {
            InteractionResult r = vanillaUse(p);
            helper.assertFalse(r.consumesAction(),
                    Component.literal("without the stamp, leather horse armour's use must be PASS for a player (the bug), got " + r));
        } finally {
            MockPlayerNetwork.remove(p);
        }
        helper.succeed();
    }

    /**
     * No cooldown on the stamped watch: on cooldown a stock client skips the watch's use and falls
     * through to the offhand (filmed: open, close, click inside 10 ticks — the backpack emptied).
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void openingThePanelLeavesNoCooldownOnModdy(GameTestHelper helper) {
        if (!Consumables.STAMPS) {
            helper.succeed();
            return;
        }
        ServerPlayer p = holding(helper, "oneclick_cd", VanillaWatchItem.stack());
        try {
            // The watch's click is bridged only on the carrier route (a stock child's server).
            com.moddingfromamod.datapack.VanillaCarrier.force(true);
            int boxes = com.moddingfromamod.wish.WishCompose.openedCount(p.getUUID());
            p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
            // (on by default, 2026-11-16): Moddy opens the text-first wish box.
            helper.assertTrue(p.containerMenu instanceof WatchMenu
                            || com.moddingfromamod.wish.WishCompose.openedCount(p.getUUID()) > boxes,
                    Component.literal("the click must open Moddy (his wish box), got " + p.containerMenu));
            helper.assertFalse(Cooldowns.active(p, p.getMainHandItem()),
                    Component.literal("Moddy is on cooldown after opening the panel — the next quick click uses the offhand"));
        } finally {
            com.moddingfromamod.datapack.VanillaCarrier.force(null);
            if (p.containerMenu != p.inventoryMenu) {
                p.closeContainer();
            }
            MockPlayerNetwork.remove(p);
        }
        helper.succeed();
    }
}
