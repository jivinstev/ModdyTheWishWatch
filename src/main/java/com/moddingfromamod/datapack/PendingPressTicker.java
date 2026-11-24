package com.moddingfromamod.datapack;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Runs a {@link PendingPresses} press once its pack has loaded. Server thread only.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class PendingPressTicker {

    static final PendingPresses PRESSES = new PendingPresses();

    private PendingPressTicker() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PRESSES.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        PendingPresses.Due due = PRESSES.take(id -> WishDatapackCommand.loaded(server, id),
                System.currentTimeMillis());
        for (PendingPresses.Press p : due.ready()) {
            ServerPlayer player = server.getPlayerList().getPlayer(p.player());
            if (player != null) {
                // AS THE SCOPE THAT PRESSED: a wish's or a button's give lands in the
                // hotbar or the Wish Bag and is recorded for that wish's undo; a rule's lands as its
                // behaviour — exactly as it would have, had the pack been loaded.
                com.moddingfromamod.mob.SpawnScope.during(p.wishKey(), p.kind(),
                        () -> WishDatapackCommand.runEntry(server, player, p.id(), p.entry()));
            }
        }
        for (PendingPresses.Press p : due.expired()) {
            ServerPlayer player = server.getPlayerList().getPlayer(p.player());
            if (player != null) {
                player.sendSystemMessage(Component.literal("✨ '" + p.id()
                        + "' is still getting ready — try it again in a moment!"));
            }
        }
    }

    /** For the command: queue a press for when {@code id} answers. */
    static void queue(ServerPlayer player, String id, String entry) {
        var scope = com.moddingfromamod.mob.SpawnScope.current();
        PRESSES.add(player.getUUID(), id, entry, System.currentTimeMillis(),
                com.moddingfromamod.item.delivery.WishDelivery.currentWishKey(),
                scope == null ? com.moddingfromamod.item.delivery.DeliveryPlan.Kind.HANDOVER : scope.kind());
    }

    /** True when {@code id}'s load function is present — the loaded probe the command uses. */
    static boolean probe(MinecraftServer server, String id) {
        return server.getFunctions().get(Identifier.fromNamespaceAndPath(id, "reset")).isPresent();
    }
}
