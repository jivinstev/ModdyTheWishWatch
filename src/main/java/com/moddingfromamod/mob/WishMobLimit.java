package com.moddingfromamod.mob;

import java.util.EnumMap;
import java.util.Map;
import java.util.WeakHashMap;

import com.moddingfromamod.vanilla.DisplayRig;
import com.moddingfromamod.vanilla.HitProxies;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The world's guard on WISHED creatures</b> — {@link PrimedTntLimit}'s twin. A per-tool
 * number alone cannot hold: one {@code execute as @e run summon} fans a single command out to
 * hundreds, a datapack summons every tick for as long as the world runs, and four
 * {@code run_command} calls carry four times what one does. So the count is kept where every one of
 * those paths ends — the creature joining the level.
 *
 * <p>A creature counts only when it joins inside a {@link SpawnScope} (a wish command, an event, a
 * datapack function): the creatures a world spawns by itself and the ones a child's own spawn egg
 * makes are none of its business. It is then classed ({@link SpawnBudget#classify}), checked against
 * its wish's wave and the level's ceiling for that class, and either admitted — tagged
 * {@link #WISHED_TAG}, which is what the level's census counts — or cancelled, with the scope
 * told why so the command's result can say so in true words. The level's count is re-derived from
 * the entities it tracks each tick ({@link WishedTally}), never carried as a running total.
 *
 * <p>Sculpted creatures are priced by their displays and are never cancelled here (half a creature
 * is worse than none); their spawn sites ask {@link #displays} first and make fewer. Lit TNT has
 * {@link PrimedTntLimit}. Server side only; registered on {@code NeoForge.EVENT_BUS}.
 */
public final class WishMobLimit {

    /** On every admitted wished creature: what the census counts, so a reload counts it again (and never cancels it). */
    public static final String WISHED_TAG = "moddy_wished";

    private static final Logger LOG = LoggerFactory.getLogger("moddingfromamod/spawn-budget");
    /**
     * Per level, the count re-derived from what the level tracks each tick. Never a running
     * total: join and leave are not a pair (see {@link WishedTally}), and a total kept from them
     * drifted to the ceiling in long runs while one wished mob stood. Weak, so a stopped server's
     * levels go with it.
     */
    private static final Map<ServerLevel, WishedTally<SpawnBudget.Kind>> TALLY = new WeakHashMap<>();
    /** Which wish made each admitted creature, so one that is gone gives its wave slot back. Bounded. */
    private static final Map<java.util.UUID, String> WISH_OF = new java.util.LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<java.util.UUID, String> eldest) {
            return size() > 8192;
        }
    };
    private static long lastSaid;

    private WishMobLimit() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)   // last: a join another handler cancels is never counted
    public static void onJoin(EntityJoinLevelEvent event) {
        Entity e = event.getEntity();
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (isRigDisplay(e)) {
            joined(level, SpawnBudget.Kind.SCULPTED);
            return;
        }
        if (event.loadedFromDisk()) {
            SpawnBudget.Kind k = e.getTags().contains(WISHED_TAG) ? kindOf(e) : null;
            if (k != null) {
                joined(level, k);   // counted, never cancelled: it is already his
            }
            return;
        }
        SpawnScope scope = SpawnScope.current();
        if (scope == null || isRigPart(e)) {
            return;
        }
        SpawnBudget.Kind k = kindOf(e);
        if (k == null) {
            return;
        }
        String typeId = typeId(e);
        int live = live(level, k);
        SpawnBudget.Stop stop = SpawnBudget.admit(k, scope.madeByThisWish(k), live);
        if (stop == SpawnBudget.Stop.NONE) {
            e.addTag(WISHED_TAG);
            joined(level, k);
            com.moddingfromamod.wish.LiveFitWatch.admitted(e);   // held to the world's limits when its command ends
            scope.admitted(k, typeId);
            if (!scope.wishKey().isEmpty()) {
                synchronized (WISH_OF) {
                    WISH_OF.put(e.getUUID(), scope.wishKey());
                }
            }
            // A `summon` that carried NBT skipped the mob's finalizeSpawn — a warden then burrows
            // away in seconds. Give back the one value that matters, never re-roll the rest.
            com.moddingfromamod.mob.SummonRepairs.apply(e);
            return;
        }
        event.setCanceled(true);
        scope.dropped(k, typeId, stop);
        long now = System.currentTimeMillis();
        if (TntBudget.worthSaying(now, lastSaid)) {
            lastSaid = now;
            LOG.info("SPAWN-BUDGET {} {} turned away ({}): wish {} has made {}, {} holds {} wished {} (#1265)",
                    typeId, k, stop, scope.wishKey().isEmpty() ? "-" : scope.wishKey(),
                    scope.madeByThisWish(k), level.dimension().identifier(), live, k);
        }
    }

    /**
     * Only the wave slot: a wished creature gone for good (killed, or discarded — a warden that dug
     * away) gives its wish's wave room again. Unloaded with its chunk is NOT gone, and keeps its
     * slot. The world count does not listen here: it is re-derived from the level.
     */
    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        Entity e = event.getEntity();
        if (event.getLevel().isClientSide() || isRigDisplay(e) || !e.getTags().contains(WISHED_TAG)) {
            return;
        }
        SpawnBudget.Kind k = kindOf(e);
        Entity.RemovalReason why = e.getRemovalReason();
        if (k != null && why != null && why.shouldDestroy()) {
            String wish;
            synchronized (WISH_OF) {
                wish = WISH_OF.remove(e.getUUID());
            }
            SpawnScope.released(wish, k);
        }
    }

    /** Wished creatures of class {@code k} this level holds right now. */
    public static int live(ResourceKey<Level> level, SpawnBudget.Kind k) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerLevel l = server == null ? null : server.getLevel(level);
        return l == null ? 0 : live(l, k);
    }

    private static int live(ServerLevel level, SpawnBudget.Kind k) {
        return tally(level).live(k, level.getServer().getTickCount(), () -> census(level));
    }

    private static void joined(ServerLevel level, SpawnBudget.Kind k) {
        tally(level).joined(k, level.getServer().getTickCount());
    }

    private static WishedTally<SpawnBudget.Kind> tally(ServerLevel level) {
        return TALLY.computeIfAbsent(level, x -> new WishedTally<>(SpawnBudget.Kind.class));
    }

    /**
     * What the level TRACKS right now: rig displays, and every creature carrying {@link #WISHED_TAG}.
     * A creature in a hidden chunk section is not ticked, sent or seen, and is counted again the
     * moment its chunk shows it; one that left by any road, with or without a leave event, is not
     * here to count.
     */
    static Map<SpawnBudget.Kind, Integer> census(ServerLevel level) {
        EnumMap<SpawnBudget.Kind, Integer> n = new EnumMap<>(SpawnBudget.Kind.class);
        for (Entity e : level.getAllEntities()) {
            if (e == null || e.isRemoved()) {
                continue;
            }
            if (isRigDisplay(e)) {
                n.merge(SpawnBudget.Kind.SCULPTED, 1, Integer::sum);
            } else if (e.getTags().contains(WISHED_TAG)) {
                SpawnBudget.Kind k = kindOf(e);
                if (k != null) {
                    n.merge(k, 1, Integer::sum);
                }
            }
        }
        return n;
    }

    /** Sculpted creatures' displays this level holds right now — what a creature spawn asks first. */
    public static int displays(ResourceKey<Level> level) {
        return live(level, SpawnBudget.Kind.SCULPTED);
    }

    /**
     * How many sculpted creatures a spawn of {@code asked} may make, judged once the FIRST is
     * standing: its displays are what the level gained since {@code displaysBefore}. Never below
     * one — the first one has already come.
     */
    public static int sculptedAllowed(ResourceKey<Level> level, int displaysBefore, int asked) {
        int each = displays(level) - displaysBefore;
        return Math.max(1, SpawnBudget.sculptedCount(each, asked, displaysBefore));
    }

    private static SpawnBudget.Kind kindOf(Entity e) {
        return SpawnBudget.classify(typeId(e), e instanceof Mob);
    }

    private static String typeId(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    /** A rig's carrier or hit proxy: part of a sculpted creature, priced by its displays instead. */
    private static boolean isRigPart(Entity e) {
        return e.getTags().contains(DisplayRig.RIG_TAG) || HitProxies.isProxy(e);
    }

    private static boolean isRigDisplay(Entity e) {
        if (!(e instanceof Display)) {
            return false;
        }
        for (String t : e.getTags()) {
            if (t.startsWith(DisplayRig.RIG_OWNER_PREFIX)) {
                return true;
            }
        }
        return false;
    }
}
