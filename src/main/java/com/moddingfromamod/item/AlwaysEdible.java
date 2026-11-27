package com.moddingfromamod.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * <b>A wished food can always be eaten (#173).</b>
 *
 * <p>An {@code on_consume} wish rides a real food ({@code ItemTriggers.defaultBase}: a cookie from
 * scratch) and keeps that food's own {@code minecraft:food}. Vanilla food has
 * {@code can_always_eat=false}, and since 1.21.2 {@code Consumable} refuses to START the use on a full
 * hunger bar, so a heal egg did nothing at all for a child who was hurt but not hungry, which is
 * exactly when a heal is wished for. The power is the point of the item, not the hunger it fills.
 *
 * <p><b>One mechanism, for new stacks and old ones alike.</b> A stack carrying
 * {@link WishItem#CONSUME_FN} whose food cannot always be eaten gets the same food with
 * {@code can_always_eat: true}, its nutrition and saturation unchanged. It runs
 * <ul>
 *   <li>on the right-click itself (server, and a modded client's own prediction), so the very
 *       click works;</li>
 *   <li>over his inventory at join and about once a second, so a STOCK client already holds the
 *       corrected stack when it predicts the eat (it decides for itself whether to start).</li>
 * </ul>
 * That covers an item made today, one in a save from before this (a MIGRATION of stacks in a save
 * this server loads, the same decision as {@link OverlayBackfill}), and one
 * re-given by an old wish datapack's {@code give.mcfunction}, which still writes the old shape and
 * is never rebuilt. Idempotent: a corrected stack is never touched again. A drink ({@code potion})
 * has no food and is always drinkable; real food with no wish on it is never touched.
 */
@EventBusSubscriber
public final class AlwaysEdible {

    /** About once a second: cheap (one pass over ~41 slots) and well inside a child's patience. */
    static final int SWEEP_EVERY_TICKS = 20;

    private AlwaysEdible() {
    }

    /** Is this a wished food that a full hunger bar would refuse? */
    public static boolean needsFix(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null || food.canAlwaysEat()) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return false;
        }
        CompoundTag tag = data.copyTag();
        return tag.contains(WishItem.CONSUME_FN);
    }

    /** Make this wished food always edible. @return true if it changed. */
    public static boolean apply(ItemStack stack) {
        if (!needsFix(stack)) {
            return false;
        }
        FoodProperties food = stack.get(DataComponents.FOOD);
        stack.set(DataComponents.FOOD, new FoodProperties(food.nutrition(), food.saturation(), true));
        return true;
    }

    /** Every slot he carries: main, armour and offhand. @return how many stacks changed. */
    public static int sweep(ServerPlayer player) {
        var inv = player.getInventory();
        int changed = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (apply(inv.getItem(i))) {
                changed++;
            }
        }
        if (changed > 0) {
            inv.setChanged();
            player.inventoryMenu.broadcastChanges();
        }
        return changed;
    }

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickItem event) {
        // Before Item.use asks Consumable whether he may start: both sides, so a modded client
        // predicts the same answer the server gives.
        apply(event.getItemStack());
    }

    @SubscribeEvent
    public static void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            safeSweep(player);
        }
    }

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && player.tickCount % SWEEP_EVERY_TICKS == 0) {
            safeSweep(player);
        }
    }

    private static void safeSweep(ServerPlayer player) {
        try {
            sweep(player);
        } catch (RuntimeException e) {
            // A food left as it was is a blemish; a tick that throws is a child who cannot play.
            com.mojang.logging.LogUtils.getLogger().warn("always-edible sweep skipped for {}: {}",
                    player.getGameProfile().name(), e.toString());
        }
    }
}
