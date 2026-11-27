package com.moddingfromamod.wish;

import java.util.List;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.wishbuilder.MakeFacts;
import com.moddingfromamod.world.HandedOver;
import com.moddingfromamod.world.Landing;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Handing a kept make over</b> (#70, {@code WorldMakes.landing}): the one place a kept Builder, Apprentice or
 * Magician make reaches him. A hotbar wish for the recipe that makes it again (the same proxy "Add to hotbar" makes,
 * {@link WishHotbar#addKept}), or for an item a world asked {@code KEPT_AS_ITEM} of, the real item already in his hand;
 * then the world is told ({@link MakeGate#handedOver}) and he is told where it went. Server thread.
 */
public final class KeptHandOver {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private KeptHandOver() {
    }

    /**
     * Hand one kept make over. {@code label} is what its hotbar wish is called (already child-safe: a make's name or a
     * taught recipe's label, as the watch shows it); {@code real}: the real item is already his.
     */
    public static void give(MinecraftServer server, UUID who, String makeId, String kind, String recipe, String label,
                            MakeFacts facts, boolean real) {
        int slot = -1;
        if (!real) {
            ServerPlayer p = server.getPlayerList().getPlayer(who);
            if (p == null) {
                LOGGER.info("kept make {}: he left before it could be handed over (it is on his shelf)", makeId);
                return;
            }
            slot = WishHotbar.addKept(p, recipe, label, com.moddingfromamod.marshal.WatchIcons.forKind(kind));
            if (slot < -1) {
                return;   // no room anywhere: he was told; it is on his shelf
            }
            p.sendSystemMessage(Component.literal(slot >= 0
                    ? "🎯 " + label + " is on your hotbar. Aim and use it to make it appear."
                    : "🎯 " + label + " is in your Wish Bag. Put it on your hotbar to make it appear."));
        }
        LOGGER.info("kept make {}: handed over ({}, recipe {}, slot {})", makeId, real ? "the real item" : "a hotbar wish",
                recipe, slot);
        MakeGate.handedOver(new HandedOver(who, makeId, kind, recipe, facts, real ? -1 : slot, real));
    }

    /**
     * A kept Magician wish finished (#70): its newest taught recipe (the one "Make another one" and a hotbar fire use)
     * is handed over. Any thread; the work runs on the server thread. A kept wish that taught nothing has no recipe to
     * hand over: logged, and what its tools saved stays where they saved it.
     */
    public static void magician(WishRun run) {
        List<String> taught = run.taughtIds();
        Landing landing = run.landing();
        String kind = run.landingKind() != null ? run.landingKind()
                : run.plannedKind() == null ? "other" : run.plannedKind();   // the kind it was kept as
        String makeId = "";
        for (WishRun.Made m : run.made()) {
            makeId = m.id();
        }
        final String made = makeId;
        com.moddingfromamod.agent.AgentBridge.onServerRun(() -> {
            MinecraftServer server = com.moddingfromamod.agent.AgentBridge.serverOrNull();
            if (server == null) {
                return;
            }
            if (taught.isEmpty()) {
                LOGGER.warn("[kept] wish {}: kept, and it taught no recipe, so nothing could be handed over",
                        run.shortId());
                MakeGate.notKept(new com.moddingfromamod.world.NotKept(run.playerId(), kind, landing,
                        com.moddingfromamod.world.NotKept.Why.NOTHING_TAUGHT));
                return;
            }
            String recipe = taught.get(taught.size() - 1);
            boolean real = landing == Landing.KEPT_AS_ITEM && "item".equals(kind);
            give(server, run.playerId(), made.isEmpty() ? recipe : made, kind, recipe, WishPresets.labelFor(recipe),
                    MakeFactsLookup.forRecipe(server, run.playerId(), recipe), real);
        });
    }
}
