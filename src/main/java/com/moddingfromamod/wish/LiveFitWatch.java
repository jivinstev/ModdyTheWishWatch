package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.world.RegionCrossings;
import com.moddingfromamod.world.Regions;
import com.moddingfromamod.world.WorldRegion;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The one watcher that holds every wished body to the limits where it stands</b> (#174, #182). Before it, a world's
 * limits reached only the creatures and tools the core recognised, and every other route failed open: a learned
 * recipe's {@code wishsummon} wolf at attack 100, a {@code summon} with NBT attributes, a wild mob a wish's
 * {@code attribute} or {@code effect} command or {@code modify_mob} made stronger, a datapack's summons, a spawn egg a
 * wish handed over. Remembering each route had failed once already; this holds them by construction instead:
 *
 * <ol>
 *   <li><b>Marked where they come in, and indexed.</b> A body a wish brings in is marked by the spawn scope every wish
 *       command, event and wish datapack runs in ({@code mob/WishMobLimit}: {@code moddy_wished}); a sculpted creature
 *       carries {@code wishmob}; a spawn egg a wish hands over carries {@link LiveFit#HELD_TAG} into the mob it hatches.
 *       A body whose attributes change WHILE a wish's scope is open is reported by its own attribute map
 *       ({@code mixin/AttributeTouchMixin}), and marked {@link LiveFit#TOUCHED_TAG}. Every marked body is put in this
 *       watch's index when it is fitted, joins or loads, and taken out when it leaves: the watch never scans every
 *       entity.</li>
 *   <li><b>Fitted the moment the wish command ends</b> ({@link #afterWishCommand}, from {@code WishExecutor.runOne}),
 *       as the player who ran it, before the body can act.</li>
 *   <li><b>Kept fitted.</b> Every {@link #EVERY_TICKS} ticks, only while a world answers (or a region stood a moment
 *       ago), the index is walked once: a body that crossed a region's edge is fitted again for the place it stands
 *       now (raised as well as lowered, its owner told once per look), one standing above the limits it is held to
 *       (an effect, a held weapon, a later command) is fitted down, and a player whose limits stamp changed with
 *       nobody saying so has his creatures refit. When the first region appears or the last goes, every indexed body
 *       is fitted once again.</li>
 * </ol>
 *
 * <p><b>What it costs.</b> In a world where nothing answers and no region stands, the tick handler returns after one
 * check. Otherwise each look reads, per indexed body, one entity lookup by UUID, one region lookup (only while a region
 * stands) and five attribute values; per online player one limits stamp. Measured in
 * {@code WishedVanillaMobLimitsGameTest}: 200 marked bodies beside 200 unmarked ones are 200 reads, and the look's time
 * is logged.
 *
 * <p>As everywhere in the live fit, nothing is persisted into a make: the fit is a modifier on the live body, and it
 * comes back to its own numbers when the limit rises, outside the region, or in a world with none. A world's own
 * creature ({@code Wished.isWorldWished}) is held by {@code WorldWishes}, never here. Server thread; registered on
 * {@code NeoForge.EVENT_BUS}.
 */
public final class LiveFitWatch {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** How often the index is walked, in ticks. */
    public static final int EVERY_TICKS = 10;

    /** Every indexed body, with the region it was last fitted in ({@link RegionCrossings#NONE} for none). */
    private static final RegionCrossings SEEN = new RegionCrossings();
    /** Where each indexed body is, to find it again by UUID. */
    private static final Map<UUID, ResourceKey<Level>> WHERE = new ConcurrentHashMap<>();
    /** Bodies brought in or changed by a wish, not yet fitted with their wisher's limits. Identity, in order. */
    private static final Map<LivingEntity, Boolean> PENDING = new LinkedHashMap<>();
    /** The limits stamp each player's creatures were last fitted at. */
    private static final Map<UUID, String> STAMPS = new HashMap<>();
    /** True while the live fit itself changes a body (its change is not a wish's). */
    private static boolean fitting;
    /** Was any region there the last time we looked: so the last one going away still lets creatures go. */
    private static volatile boolean hadRegions;
    /** How many indexed bodies the last look read (Gate B: the index, not every entity). */
    private static volatile int lastRead;
    private static long lastSaid;

    private LiveFitWatch() {
    }

    /** The live fit is changing a body: its own change is not one to answer. Server thread. */
    static void fitting(boolean on) {
        fitting = on;
    }

    // ── what comes in ─────────────────────────────────────────────────────────────────────────────────────────────

    /** The id of the region at {@code e}'s feet, or {@link RegionCrossings#NONE} (always, with no region anywhere). */
    static String regionAt(Entity e) {
        if (!Regions.any()) {
            return RegionCrossings.NONE;
        }
        WorldRegion r = Regions.at(e.level().dimension().location().toString(), e.getX(), e.getY(), e.getZ());
        return r == null ? RegionCrossings.NONE : r.getClass().getName() + "/" + r.id();   // ids are per plug-in
    }

    /** The live fit just fitted {@code mob} where it stands: index it, with the region it was fitted for. */
    public static void track(LivingEntity mob) {
        if (mob == null || mob instanceof Player || mob.level().isClientSide()) {
            return;
        }
        SEEN.fitted(mob.getUUID(), regionAt(mob));
        WHERE.put(mob.getUUID(), mob.level().dimension());
    }

    /**
     * An attribute of {@code body} changed while a wish's spawn scope was open ({@code mixin/AttributeTouchMixin}):
     * remembered, and fitted when the wish command ends (or on the next look). Cheap; a player is never held here.
     */
    public static void touched(LivingEntity body) {
        if (fitting || body == null || body instanceof Player || body.level().isClientSide() || !MakeGate.anyWorld()) {
            return;
        }
        synchronized (PENDING) {
            PENDING.put(body, Boolean.TRUE);
        }
    }

    /** A body joined inside a wish's spawn scope and was admitted ({@code mob/WishMobLimit}). */
    public static void admitted(Entity e) {
        if (!(e instanceof LivingEntity mob) || e instanceof Player) {
            return;
        }
        track(mob);   // indexed whether or not a world answers now: a region that appears later still finds it
        if (MakeGate.anyWorld()) {
            synchronized (PENDING) {
                PENDING.put(mob, Boolean.TRUE);
            }
        }
    }

    /** A marked body that loads (or joins marked) is indexed, and fitted on the next look: made before a rule existed. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.isCanceled() || event.getLevel().isClientSide() || !(event.getEntity() instanceof LivingEntity mob)
                || mob instanceof Player) {
            return;
        }
        if (LiveFit.markedByTags(mob.getTags()) || com.moddingfromamod.world.Wished.isWished(mob)) {
            WHERE.put(mob.getUUID(), mob.level().dimension());
            SEEN.fitted(mob.getUUID(), regionAt(mob));
            if (event.loadedFromDisk() && LiveFit.speciesOf(mob) == null && MakeGate.anyWorld()) {
                synchronized (PENDING) {
                    PENDING.put(mob, Boolean.TRUE);
                }
            }
        }
    }

    /** A body that leaves (dies, unloads, changes dimension) leaves the index; it rejoins when it is back. */
    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            UUID id = event.getEntity().getUUID();
            if (WHERE.remove(id) != null) {
                SEEN.forget(id);
            }
        }
    }

    // ── fitting ───────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * A wish command just ended ({@code WishExecutor.runOne}): every body it brought in or changed is held to
     * {@code wisher}'s limits where it stands now (the tightest any player here is held to when nobody ran it), before
     * it can act.
     */
    public static void afterWishCommand(MinecraftServer server, ServerPlayer wisher) {
        if (server == null || !MakeGate.anyWorld()) {
            return;
        }
        settle(wisher == null ? null : wisher.getUUID());
    }

    /** Fit what came in or was changed since the last look, as {@code owner}'s (null: whoever it remembers). */
    static int settle(UUID owner) {
        List<LivingEntity> bodies;
        synchronized (PENDING) {
            if (PENDING.isEmpty()) {
                return 0;
            }
            bodies = new ArrayList<>(PENDING.keySet());
            PENDING.clear();
        }
        int held = 0;
        for (LivingEntity mob : bodies) {
            if (mob.isAlive() && !mob.isRemoved()) {
                held += fitNow(mob, owner) ? 1 : 0;
            }
        }
        return held;
    }

    /** One body a wish brought in or changed: a species creature fitted by its species, any other held. */
    private static boolean fitNow(LivingEntity mob, UUID owner) {
        if (com.moddingfromamod.world.Wished.isWorldWished(mob)) {
            return false;   // a world's own creature: WorldWishes holds it at the ceiling it named
        }
        String speciesId = LiveFit.speciesOf(mob);
        if (speciesId != null) {
            return !LiveFit.apply(mob, speciesId).isEmpty();
        }
        UUID whose = LiveFit.fitOwnerOf(mob) != null ? null : owner;   // the first wisher it was held for stays
        return !LiveFit.hold(mob, whose).isEmpty();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % EVERY_TICKS != 0) {
            return;
        }
        try {
            look(server);
        } catch (RuntimeException e) {
            LOGGER.warn("live fit watch: {}", e.toString());
        }
    }

    /**
     * One look. Public for a GameTest that drives it. @return how many bodies were fitted again (crossed an edge, stood
     * above their limits, or were refit when the first region appeared or the last went).
     */
    public static int look(MinecraftServer server) {
        boolean world = MakeGate.anyWorld();
        boolean any = Regions.any();
        if (!world && !any && !hadRegions) {
            lastRead = 0;
            return 0;   // nothing answers, and no region stands or stood a moment ago: nothing can be held
        }
        boolean transition = any != hadRegions;
        hadRegions = any;
        int held = world ? settle(null) : 0;
        if (world) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                String stamp = MakeGate.limits(p.getUUID()).stamp();
                String was = STAMPS.put(p.getUUID(), stamp);
                if (was != null && !was.equals(stamp)) {
                    // his limits changed and nobody said so: fit his creatures again, raised as well as lowered, and
                    // tell him once, exactly as a world's own change does
                    LiveFit.refit(server, p.getUUID());
                }
            }
        }
        Map<UUID, int[]> byOwner = new HashMap<>();   // owner → raised, lowered, same
        Map<UUID, Map<String, Double>> perOwner = new HashMap<>();
        int read = 0;
        for (UUID id : SEEN.watched()) {
            ResourceKey<Level> dim = WHERE.get(id);
            ServerLevel level = dim == null ? null : server.getLevel(dim);
            Entity e = level == null ? null : level.getEntity(id);
            if (!(e instanceof LivingEntity mob) || !e.isAlive()) {
                SEEN.forget(id);   // died or unloaded: indexed and fitted again when it loads
                WHERE.remove(id);
                continue;
            }
            read++;
            if (com.moddingfromamod.world.Wished.isWorldWished(mob)) {
                continue;
            }
            boolean crossed = SEEN.moved(id, regionAt(mob));
            if (transition || crossed) {
                again(mob, byOwner);
                held++;
            } else if (world) {
                Map<String, Double> limits = LiveFit.heldLimits(mob, perOwner);
                if (!limits.isEmpty() && LiveFit.above(mob, limits)) {
                    fitNow(mob, null);
                    held++;
                }
            }
        }
        lastRead = read;
        for (var o : byOwner.entrySet()) {
            int[] c = o.getValue();
            LiveFit.told(server, o.getKey(), new LiveFit.Refit(c[0], c[1], c[2]));
        }
        if (!any && !world) {
            SEEN.clear();   // the last region went and nothing answers: every fit came off above; nothing to watch
            WHERE.clear();
        }
        if (held > 0) {
            long now = System.currentTimeMillis();
            if (now - lastSaid > 10_000L) {
                lastSaid = now;
                LOGGER.info("live fit watch: {} wished bod(ies) fitted again where they stand ({} read)", held, read);
            }
        }
        return held;
    }

    /** Fit one indexed body again where it stands (it crossed an edge, or regions came or went), counting how it moved. */
    private static void again(LivingEntity mob, Map<UUID, int[]> byOwner) {
        String speciesId = LiveFit.speciesOf(mob);
        UUID owner;
        int moved;
        if (speciesId != null) {
            owner = LiveFit.ownerOf(speciesId);
            LiveFit.Moved m = LiveFit.refitOne(mob, speciesId);
            moved = m.moved();
            if (m.resized()) {
                com.moddingfromamod.species.SpeciesRegistry.touch();
            }
        } else {
            owner = LiveFit.fitOwnerOf(mob);
            moved = LiveFit.refitWished(mob);
        }
        track(mob);
        if (owner != null) {
            int[] c = byOwner.computeIfAbsent(owner, k -> new int[3]);
            c[moved > 0 ? 0 : moved < 0 ? 1 : 2]++;
        }
    }

    /** {@link LiveFit#refit} fitted this player's creatures at this stamp: no second refit for the same change. */
    static void fittedAt(UUID player, String stamp) {
        if (player != null && stamp != null) {
            STAMPS.put(player, stamp);
        }
    }

    /** How many bodies the index holds (Gate B). */
    public static int watched() {
        return SEEN.size();
    }

    /** Is this body in the index (Gate B)? */
    public static boolean indexes(UUID id) {
        return SEEN.watches(id);
    }

    /** How many indexed bodies the last look read (Gate B: never every entity). */
    public static int lastRead() {
        return lastRead;
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        synchronized (PENDING) {
            PENDING.clear();
        }
        SEEN.clear();
        WHERE.clear();
        STAMPS.clear();
        hadRegions = false;
    }
}
