package com.moddingfromamod.mob;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The world's backstop against a TNT chain that stops the server</b>. The wish side
 * refuses a building with more than {@code BuildPlan.MAX_TNT}; this covers everything else — TNT a
 * child places and lights by hand, a {@code /fill} or a datapack, and lit TNT SAVED with the world
 * by a crash (it joins again at load, and is counted and capped like any other).
 *
 * <p>Counts live {@link PrimedTnt} per level as they join and leave; past {@link TntBudget#MAX_LIVE}
 * the join is cancelled. Server side only. Registered on {@code NeoForge.EVENT_BUS}.
 */
public final class PrimedTntLimit {

    private static final Logger LOG = LoggerFactory.getLogger("moddingfromamod/tnt");
    private static final Map<ResourceKey<Level>, Integer> LIVE = new HashMap<>();
    private static final Map<ResourceKey<Level>, Integer> DROPPED = new HashMap<>();
    private static long lastSaid;

    private PrimedTntLimit() {
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof PrimedTnt) || event.getLevel().isClientSide()) {
            return;
        }
        ResourceKey<Level> key = event.getLevel().dimension();
        int live = LIVE.getOrDefault(key, 0);
        if (TntBudget.admits(live)) {
            LIVE.put(key, live + 1);
            return;
        }
        event.setCanceled(true);
        int dropped = DROPPED.merge(key, 1, Integer::sum);
        long now = System.currentTimeMillis();
        if (TntBudget.worthSaying(now, lastSaid)) {
            lastSaid = now;
            LOG.warn("TNT-LIMIT {} lit TNT already in {} — {} more dropped so far so the server keeps up (#1037)",
                    live, key.identifier(), dropped);
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof PrimedTnt) || event.getLevel().isClientSide()) {
            return;
        }
        LIVE.computeIfPresent(event.getLevel().dimension(), (k, v) -> v > 1 ? v - 1 : null);
    }

    /** Lit TNT this level holds right now, as counted here. For the GameTest and the health line. */
    public static int live(ResourceKey<Level> level) {
        return LIVE.getOrDefault(level, 0);
    }
}
