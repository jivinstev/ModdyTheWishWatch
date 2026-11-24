package com.moddingfromamod.test.example;

import java.util.List;
import java.util.UUID;

import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishCharge;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * <b>The example plug-in of {@code docs/EXTENDING.md}</b>: a toy {@link WishCharge} where every wish costs ONE
 * emerald. It overrides only what a charge needs; every other method keeps the public mod's free, silent default.
 *
 * <p>A test fixture, never shipped (the {@code test} package is left out of the jar). {@link EmeraldPlugin} shows how
 * a real plug-in would register it; {@link EmeraldWishChargeGameTest} drives the core's real entry points with it.
 */
public final class EmeraldWishCharge implements WishCharge {

    /** What a player with no emerald hears. */
    public static final String NO_EMERALD = "A wish costs one emerald, and you have none.";
    /** {@code /moddy wishes}. */
    public static final String PRICE_LINE = "💎 Every wish costs one emerald.";

    @Override
    public boolean priced(UUID player) {
        return true;
    }

    @Override
    public List<String> wishesCommand(UUID player) {
        return List.of(PRICE_LINE);
    }

    /** Server thread. Never refuse a child in distress: the core asks this before anything is charged. */
    @Override
    public boolean refusesAtSubmit(ServerPlayer player, boolean distress, String text, boolean recipe) {
        if (distress || player.getInventory().countItem(Items.EMERALD) > 0) {
            return false;
        }
        player.sendSystemMessage(Component.literal(NO_EMERALD));
        return true;
    }

    /** A wish served with no model (a button, a recipe, a Builder make): server thread. */
    @Override
    public Paid charge(UUID player, UUID wishId, boolean agent) {
        return take(player);
    }

    /** The agent picked a wish up: may be on an agent thread, so the inventory is touched on the server thread. */
    @Override
    public Paid atPickup(Wish wish) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return Paid.FREE;
        }
        return server.isSameThread() ? take(wish.playerId()) : server.submit(() -> take(wish.playerId())).join();
    }

    private static Paid take(UUID id) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerPlayer p = server == null || id == null ? null : server.getPlayerList().getPlayer(id);
        if (p == null) {
            return Paid.FREE;                                     // nobody to charge (he left): let it through
        }
        int took = p.getInventory().clearOrCountMatchingItems(s -> s.is(Items.EMERALD), false, 1,
                p.inventoryMenu.getCraftSlots());
        return took == 1 ? Paid.FREE : new Paid(true, () -> NO_EMERALD);
    }
}
