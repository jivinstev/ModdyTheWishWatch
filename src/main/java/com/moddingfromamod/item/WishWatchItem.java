package com.moddingfromamod.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * <b>♻️ SHIM (an old world's chest holds one of these and nothing can reach in and change
 * it). The pre-vanilla-route Moddy: kept for old worlds, not how anyone gets one now.</b>
 * See {@code docs/COMPAT_SHIMS.md} and.
 *
 * <p>Since the watch spike classified as <b>path 1</b> and {@link
 * com.moddingfromamod.wish.VanillaWatch} flipped on, Moddy is a vanilla carrier handed out on join,
 * on respawn and on a five-second top-up ({@code VanillaWatchItem}), and pressing him opens a
 * server-driven vanilla container. This item is what a child had <i>before</i> that, and the only
 * way to still have one is to have had one already: it is off the creative menu while the route is
 * on ({@code ModItems.addToCreativeTabs}).
 *
 * <p><b>And it opens the same panel the carrier does.</b> Two Moddys with two different UIs in one
 * world is not back-compatibility, it is a second product — so when deleted {@code WishScreen}
 * there was no fork left here to remove, only a branch. {@code VanillaWatchGameTest
 * .theLegacyWatchOpensTheVanillaPanel} drives the item's real {@code use} and asserts the
 * container, because "there is no door" and "the door is not wired to the item" look identical from
 * outside.
 *
 * <p><b>Why this is a shim and not something to delete.</b> The decision rule in
 * {@code docs/COMPAT_SHIMS.md} asks who holds the old shape and whether they can be made to change.
 * Here it is a {@code moddingfromamod:wish_watch} stack sitting in a chest in a world saved months
 * ago: we cannot rewrite it, we cannot reach the person holding it, and an unregistered item id is
 * not a tidier world — it is a thing the child <b>loses</b>, silently, on the next load. That is
 * the one answer of four that earns a shim, and it is <b>forever</b>.
 *
 * <p>The registry id stays {@code wish_watch} for the same reason. Renaming it would make every
 * Moddy already in an inventory vanish on the next world load.
 *
 * <p>A short cooldown gives the vanilla "swipe" animation as instant feedback and takes the edge off
 * button-mashing before the server-side rate limiter has to say no.
 */
public class WishWatchItem extends com.moddingfromamod.compat.UseItem {

    private static final int COOLDOWN_TICKS = 10;

    public WishWatchItem(Properties properties) {
        super(properties);
    }

    @Override
    protected Outcome onUse(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        com.moddingfromamod.compat.Cooldowns.add(player, stack, COOLDOWN_TICKS);
        // ONE door, and it is the server's. An old watch out of an old chest opens exactly the
        // container the carrier opens; there is no second UI left to fork to.
        if (!level.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer sp) {
            com.moddingfromamod.wish.WatchHome.openWatch(sp);
        }
        // sidedSuccess on the CLIENT only, so the arm swings; the server must not also report
        // success or the container it just opened is closed again by the interaction result.
        // (UseItem keeps 1.21.1's per-side values on every target: see compat.Interactions.)
        return Outcome.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                net.minecraft.world.item.component.TooltipDisplay display,
                                java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.literal("Use it to make a wish!")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true; // it's magic — make it shimmer
    }
}
