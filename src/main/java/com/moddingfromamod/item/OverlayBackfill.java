package com.moddingfromamod.item;

import com.moddingfromamod.compat.ModelData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * <b>Items made before overlays existed get theirs.</b>
 *
 * <p>An overlay draws what an item DOES over its icon, from a string stamped on the stack when it is
 * made. Every item made before that carries no string and draws plain — but its DESIGN still says
 * what it does ({@link ItemSpecStore}, keyed by the {@code WishItem} feature id every made item
 * carries), so the overlay is derivable rather than lost. Two places ask:
 *
 * <ul>
 *   <li><b>his inventory, on join</b> — a MIGRATION, not a shim (docs/COMPAT_SHIMS.md's decision:
 *       the stacks are in a save this server loads, so we can rewrite them and then need no reader
 *       for the old shape). Idempotent: a stack that already has an overlay is never touched, so a
 *       second join is a no-op, and nothing is ever removed.</li>
 *   <li><b>the panel</b>, which rebuilds an item cell from the catalogue's stored stack
 *       ({@code WatchMenu.iconStack}) — that copy is minted at wish time and never re-read from a
 *       player, so it gets the same derivation when it is drawn.</li>
 * </ul>
 *
 * <p>Only a stack drawn by the icon library changes ({@link ModelData#withOverlay} says which); a
 * real golden axe he built on, or boots drawn as leather, have no icon of ours to draw over.
 */
@EventBusSubscriber
public final class OverlayBackfill {

    private OverlayBackfill() {
    }

    /** The overlay this made item's design calls for, or null — nothing made it, or it does nothing picturable. */
    public static String overlayFor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String feature = WishItem.wishFeature(stack);
        if (feature.isBlank()) {
            return null;
        }
        ItemSpecStore.Spec spec = ItemSpecStore.get(feature);
        return spec == null ? null : IconOverlays.forEffects(spec.effects());
    }

    /** Give this stack its overlay if its design calls for one and it has none. @return true if it changed. */
    public static boolean apply(ItemStack stack) {
        String overlay = overlayFor(stack);
        return overlay != null && ModelData.withOverlay(stack, overlay);
    }

    /** Every slot he carries — main, armour and offhand. @return how many stacks changed. */
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
    public static void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!ModelData.COMPOSED_ICONS || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        try {
            sweep(player);
        } catch (RuntimeException e) {
            // A stack left plain is a blemish; a join that throws is a child who cannot play.
            com.mojang.logging.LogUtils.getLogger().warn("overlay backfill skipped for {}: {}",
                    player.getGameProfile().name(), e.toString());
        }
    }
}
