package com.moddingfromamod.world;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.wish.LiveFit;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>A wish creature that crosses a region's edge is fitted again</b> (#182). A living creature is fitted by the
 * region it stands in ({@code LiveFit.apply} asks with its place, {@code WishAsk.where}), so one that walks into an
 * arena is held to the arena's limits for its owner, and one that walks out is the world's again: a held-down creature
 * comes back when it leaves.
 *
 * <p>Cheap: every {@link #EVERY_TICKS} ticks, and only while some plug-in has a region, it looks at the creatures the
 * live fit saw (never every entity), one {@code Regions.at} each. When the first region appears or the last one goes,
 * every wish creature is fitted once again. Each owner is told once per look ({@code LiveFit.told}), not once per
 * creature. With no region anywhere and none a moment ago, it returns at once. Server thread.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class RegionWatch {

    private static final Logger LOG = LoggerFactory.getLogger(ModdingFromAModMod.MODID);
    /** How often creatures' regions are looked at, in ticks. */
    public static final int EVERY_TICKS = 10;
    private static final RegionCrossings SEEN = new RegionCrossings();
    private static final Map<UUID, ResourceKey<Level>> WHERE = new ConcurrentHashMap<>();
    /** Was any region there the last time we looked: so the last one going away still lets creatures go. */
    private static volatile boolean hadRegions;

    private RegionWatch() {
    }

    /** The id of the region at {@code e}'s feet, or {@link RegionCrossings#NONE}. */
    static String regionAt(Entity e) {
        WorldRegion r = Regions.at(e.level().dimension().location().toString(), e.getX(), e.getY(), e.getZ());
        return r == null ? RegionCrossings.NONE : r.getClass().getName() + "/" + r.id();   // ids are per plug-in
    }

    /** The live fit just fitted {@code mob} where it stands: watch it from here. */
    public static void track(LivingEntity mob) {
        SEEN.fitted(mob.getUUID(), regionAt(mob));
        WHERE.put(mob.getUUID(), mob.level().dimension());
    }

    /** How many creatures are watched (Gate B). */
    public static int watched() {
        return SEEN.size();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % EVERY_TICKS == 0) {
            look(server);
        }
    }

    /** One look. Public for a GameTest that drives it. @return how many creatures were fitted again. */
    public static int look(MinecraftServer server) {
        boolean any = Regions.any();
        if (!any && !hadRegions) {
            return 0;   // no region anywhere, and none a moment ago: nothing can have changed
        }
        Map<UUID, int[]> byOwner = new HashMap<>();   // owner → raised, lowered, same
        int n = 0;
        if (any != hadRegions) {
            hadRegions = any;
            SEEN.clear();
            WHERE.clear();
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity e : level.getAllEntities()) {
                    n += again(e, byOwner);   // the first region appeared, or the last went: every creature once
                }
            }
            if (!any) {
                SEEN.clear();
                WHERE.clear();
            }
        } else {
            for (UUID id : SEEN.watched()) {
                ResourceKey<Level> dim = WHERE.get(id);
                ServerLevel level = dim == null ? null : server.getLevel(dim);
                Entity e = level == null ? null : level.getEntity(id);
                if (!(e instanceof LivingEntity) || !e.isAlive()) {
                    SEEN.forget(id);   // died or unloaded: fitted, and watched, again when it loads
                    WHERE.remove(id);
                    continue;
                }
                if (e.level().dimension() != dim) {
                    WHERE.put(id, e.level().dimension());
                }
                if (SEEN.moved(id, regionAt(e))) {
                    n += again(e, byOwner);
                }
            }
        }
        for (var o : byOwner.entrySet()) {
            int[] c = o.getValue();
            LiveFit.told(server, o.getKey(), new LiveFit.Refit(c[0], c[1], c[2]));
        }
        if (n > 0) {
            LOG.info("region watch: {} wish creature(s) crossed a region's edge and were fitted again", n);
        }
        return n;
    }

    private static int again(Entity e, Map<UUID, int[]> byOwner) {
        if (!(e instanceof LivingEntity mob) || !e.isAlive()) {
            return 0;
        }
        String speciesId = LiveFit.speciesOf(mob);
        UUID owner = speciesId == null ? null : LiveFit.ownerOf(speciesId);
        if (owner == null) {
            return 0;
        }
        LiveFit.Moved m = LiveFit.refitOne(mob, speciesId);
        int[] c = byOwner.computeIfAbsent(owner, k -> new int[3]);
        c[m.moved() > 0 ? 0 : m.moved() < 0 ? 1 : 2]++;
        if (m.resized()) {
            com.moddingfromamod.species.SpeciesRegistry.touch();
        }
        return 1;
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        SEEN.clear();
        WHERE.clear();
        hadRegions = false;
    }
}
