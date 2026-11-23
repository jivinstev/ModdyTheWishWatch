package com.moddingfromamod.event;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Runs every staged event, and gives the world back</b> (E1).
 *
 * <p>Each server tick, after the level has ticked: a RUNNING event's motion writes through an
 * {@link EventWriter} within its budget; a RESTORING one puts back a budget's worth of cells, newest
 * first. The events and their journals are saved with the world (one store per world, D3), and the
 * motion is not — so an event that was running when the server stopped is rolled back on the next
 * start. A world is never left half-flooded (D1).
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class EventDirector {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);
    private static final String DATA = ModdingFromAModMod.MODID + "_events";

    private EventDirector() {
    }

    /** The per-world store. Holds the events themselves; saving flattens each journal. */
    public static final class Store extends SavedData {
        public static final int VERSION = 1;
        final Map<String, StagedEvent> events = new LinkedHashMap<>();
        /** A copy written by a newer build: run nothing, write nothing. */
        String refused;

        static Store load(CompoundTag tag, HolderLookup.Provider lookup) {
            Store s = new Store();
            int v = tag.getIntOr("v", 0);
            if (v > VERSION) {
                s.refused = "the staged events were saved by a newer Moddy (v" + v + ")";
                LOGGER.warn("[events] {}; they are left untouched", s.refused);
                return s;
            }
            HolderGetter<Block> blocks = lookup.lookupOrThrow(Registries.BLOCK);
            ListTag list = tag.getListOrEmpty("events");
            for (int i = 0; i < list.size(); i++) {
                try {
                    StagedEvent e = read(list.getCompoundOrEmpty(i), blocks);
                    s.events.put(e.id, e);
                } catch (RuntimeException ex) {
                    // One damaged record must not cost every other event its restore.
                    LOGGER.warn("[events] an unreadable event was skipped: {}", ex.toString());
                }
            }
            return s;
        }
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
            tag.putInt("v", VERSION);
            ListTag list = new ListTag();
            for (StagedEvent e : events.values()) {
                list.add(write(e));
            }
            tag.put("events", list);
            return tag;
        }

        static net.minecraft.world.level.saveddata.SavedDataType<Store> factory() {
            return com.moddingfromamod.compat.SavedDataTypes.of(DATA, Store::new, Store::load,
                    (s, lookup) -> s.save(new CompoundTag(), lookup));
        }

        /**
         * Test seam, ONE event: behave as if that event had been saved and loaded again, leaving every
         * other event as it is.: GameTests in one batch run at once and share this store, so the
         * whole-store reload this replaced dropped the MOTION of every other test's running event (a motion is not
         * saved), and a neighbour's "the motion should have run" failed in a full run.
         */
        public void reloadForTest(HolderLookup.Provider lookup, String id) {
            StagedEvent again = load(save(new CompoundTag(), lookup), lookup).events.get(id);
            if (again != null) {
                events.put(id, again);
            }
        }
    }

    static CompoundTag write(StagedEvent e) {
        CompoundTag t = new CompoundTag();
        t.putString("id", e.id);
        t.putString("dim", e.dimension.location().toString());
        t.putString("owner", e.owner);
        t.putString("scale", e.scale.name());
        t.putString("policy", e.policy.name());
        t.putBoolean("restoreAtEnd", e.restoreAtEnd);
        t.putString("phase", e.phase.name());
        t.putInt("age", e.age);
        t.putInt("spawned", e.spawned);
        EventJournal.Flat<CompoundTag> flat = e.journal.flatten(NbtUtils::writeBlockState);
        ListTag palette = new ListTag();
        palette.addAll(flat.palette());
        t.put("palette", palette);
        t.putLongArray("pos", flat.pos());
        t.putIntArray("prior", flat.prior());
        t.putIntArray("written", flat.written());
        return t;
    }

    static StagedEvent read(CompoundTag t, HolderGetter<Block> blocks) {
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse(t.getStringOr("dim", "")));
        List<CompoundTag> palette = new ArrayList<>();
        ListTag p = t.getListOrEmpty("palette");
        for (int i = 0; i < p.size(); i++) {
            palette.add(p.getCompoundOrEmpty(i));
        }
        EventJournal<BlockState> journal = EventJournal.unflatten(
                new EventJournal.Flat<>(palette, t.getLongArray("pos").orElseGet(() -> new long[0]), t.getIntArray("prior").orElseGet(() -> new int[0]),
                        t.getIntArray("written").orElseGet(() -> new int[0])),
                c -> NbtUtils.readBlockState(blocks, c));
        StagedEvent e = new StagedEvent(t.getStringOr("id", ""), dim, t.getStringOr("owner", ""),
                EventScale.valueOf(t.getStringOr("scale", "")),
                EventGuard.Policy.valueOf(t.getStringOr("policy", "")),
                t.getBooleanOr("restoreAtEnd", false), journal,
                EventPhase.valueOf(t.getStringOr("phase", "")), t.getIntOr("age", 0));
        e.spawned = t.getIntOr("spawned", 0);
        return e;
    }

    /** The tag every entity an event spawns carries, so the undo can find it again. */
    public static String entityTag(String eventId) {
        return "moddy_event_" + eventId;
    }

    /** Remove whatever the event spawned that is still about (the chickens it rained). */
    static void removeSpawned(ServerLevel level, String eventId) {
        String tag = entityTag(eventId);
        List<net.minecraft.world.entity.Entity> gone = new ArrayList<>();
        for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
            if (e.getTags().contains(tag)) {
                gone.add(e);
            }
        }
        gone.forEach(net.minecraft.world.entity.Entity::discard);
    }

    public static Store store(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(Store.factory());
    }

    /**
     * Stage a new event. Throws when the store refused to load, because starting one would mean
     * writing a store this build cannot read back.
     */
    public static StagedEvent start(ServerLevel level, String id, String owner, EventScale scale,
                                    EventGuard.Policy policy, boolean restoreAtEnd,
                                    EventMotion motion) {
        Store s = store(level.getServer());
        if (s.refused != null) {
            throw new IllegalStateException(s.refused);
        }
        if (s.events.containsKey(id)) {
            throw new IllegalArgumentException("an event called " + id + " is already staged");
        }
        StagedEvent e = new StagedEvent(id, level.dimension(), owner, scale, policy, restoreAtEnd,
                new EventJournal<>(), EventPhase.RUNNING, 0);
        e.motion = motion;
        s.events.put(id, e);
        s.setDirty();
        return e;
    }

    /** The event by id, or null (finished events are forgotten). */
    public static StagedEvent get(MinecraftServer server, String id) {
        return store(server).events.get(id);
    }

    public static List<StagedEvent> all(MinecraftServer server) {
        return List.copyOf(store(server).events.values());
    }

    /**
     * the event this owner most recently started that knows where it is happening, within
     * {@code maxAgeMs}, or null — what the wish camera films when a wish's photograph names no subject.
     */
    public static StagedEvent latestFocused(MinecraftServer server, String owner, long maxAgeMs) {
        StagedEvent best = null;
        long now = System.currentTimeMillis();
        for (StagedEvent e : store(server).events.values()) {
            if (e.focus() == null || now - e.focusedAtMs() > maxAgeMs) {
                continue;
            }
            if (!filmableBy(e.owner(), owner)) {
                continue;
            }
            if (best == null || e.focusedAtMs() > best.focusedAtMs()) {
                best = e;
            }
        }
        return best;
    }

    /**
     * May a wish photograph taken for {@code owner} film an event staged by {@code eventOwner}? Only his own
     * (the vortex leak). A photograph with no child behind it may film any event; an event with NO owner — staged by a
     * command with nobody near, or by a GameTest — is nobody's wish, and never his. It used to be everybody's:
     * for two minutes after any ownerless event started, every wish's photograph that named no subject filmed
     * THAT event instead (six seconds of someone else's tornado, holding the one camera), and on a shared
     * server any child's.
     */
    public static boolean filmableBy(String eventOwner, String owner) {
        if (owner == null || owner.isEmpty()) {
            return true;
        }
        return owner.equals(eventOwner);
    }

    /**
     * Give the world back: stop the motion if it is still playing and restore everything it changed.
     * Returns false when there is no such event (it already finished and gave everything back).
     */
    public static boolean undo(MinecraftServer server, String id) {
        Store s = store(server);
        StagedEvent e = s.events.get(id);
        if (e == null || s.refused != null) {
            return false;
        }
        if (e.phase == EventPhase.RUNNING || e.phase == EventPhase.KEPT) {
            stopMotion(server.getLevel(e.dimension), e);
            e.phase = EventPhase.RESTORING;
            s.setDirty();
        }
        return true;
    }

    /** Stop a motion that has not finished, so it puts back what it set outside the journal (EventMotion.stopped). */
    private static void stopMotion(ServerLevel level, StagedEvent e) {
        EventMotion m = e.motion;
        e.motion = null;
        if (m != null && level != null) {
            try {
                m.stopped(level);
            } catch (RuntimeException ex) {
                LOGGER.warn("[events] {} could not tidy up as it stopped: {}", e.id, ex.toString());
            }
        }
    }

    /**
     * <b>A GameTest's event ends with its test</b> (the vortex leak). Gives the world back NOW — every journaled block,
     * whatever it spawned, its displays — stops its motion, and forgets it, so nothing of it outlives the test that
     * staged it: not a tornado still spinning in a forced chunk, not a KEPT wreck, and not a "just started" event
     * the next test's wish camera goes to film. Server thread; a no-op when it has already gone.
     */
    public static void endForTest(MinecraftServer server, String id) {
        Store s = store(server);
        StagedEvent e = s.events.get(id);
        if (e == null) {
            return;
        }
        ServerLevel level = server.getLevel(e.dimension);
        stopMotion(level, e);
        if (level != null) {
            for (EventJournal.Restore<BlockState> r : e.journal.drain(Integer.MAX_VALUE,
                    pos -> level.getBlockState(BlockPos.of(pos)))) {
                level.setBlock(BlockPos.of(r.pos()), r.state(), EventWriter.FLAGS);
            }
            removeSpawned(level, id);
        }
        e.phase = EventPhase.DONE;
        s.events.remove(id);
        s.setDirty();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        tickAll(event.getServer());
    }

    /** Work to do after this pass over the events: a follow-on is staged here, never mid-loop. */
    private static final java.util.Queue<Runnable> LATER = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Run {@code r} at the end of the director's next pass (a follow-on event, a countdown's end). */
    public static void later(MinecraftServer server, Runnable r) {
        LATER.add(r);
    }

    /** One pass over every event. Public so a GameTest can drive it deterministically. */
    public static void tickAll(MinecraftServer server) {
        try {
            tickEvents(server);
        } finally {
            for (Runnable r; (r = LATER.poll()) != null; ) {
                try {
                    r.run();
                } catch (RuntimeException ex) {
                    LOGGER.warn("[events] a follow-on could not be staged: {}", ex.toString());
                }
            }
        }
    }

    private static void tickEvents(MinecraftServer server) {
        Store s = store(server);
        if (s.refused != null || s.events.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Iterator<StagedEvent> it = s.events.values().iterator(); it.hasNext(); ) {
            StagedEvent e = it.next();
            ServerLevel level = server.getLevel(e.dimension);
            if (level == null) {
                continue; // a dimension that is not loaded right now; try again later
            }
            changed |= tickOne(level, e);
            if (e.phase == EventPhase.DONE) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            s.setDirty();
        }
    }

    private static boolean tickOne(ServerLevel level, StagedEvent e) {
        switch (e.phase) {
            case RUNNING -> {
                if (e.motion != null && e.held(System.currentTimeMillis())) {
                    return false;            // the camera is on its way: wait for it
                }
                if (e.motion == null) {
                    // Its motion did not survive a restart: roll it back.
                    e.phase = EventPhase.RESTORING;
                    return true;
                }
                EventBudget budget = new EventBudget(e.scale);
                EventWriter w = new EventWriter(level, e, budget);
                boolean finished;
                try {
                    finished = e.motion.step(w, e.age);
                } catch (RuntimeException ex) {
                    LOGGER.warn("[events] {} stopped with an error and is being undone: {}",
                            e.id, ex.toString());
                    finished = true;
                    stopMotion(level, e);
                    e.phase = EventPhase.RESTORING;
                }
                w.pin();
                e.age++;
                if (finished && e.phase == EventPhase.RUNNING) {
                    e.motion = null;
                    e.phase = e.restoreAtEnd ? EventPhase.RESTORING
                            : (e.journal.isEmpty() && e.spawned == 0 ? EventPhase.DONE : EventPhase.KEPT);
                }
                return true;
            }
            case RESTORING -> {
                List<EventJournal.Restore<BlockState>> back = e.journal.drain(e.scale.perTick,
                        pos -> level.getBlockState(BlockPos.of(pos)));
                EventWriter w = new EventWriter(level, e, new EventBudget(e.scale));
                for (EventJournal.Restore<BlockState> r : back) {
                    BlockPos p = BlockPos.of(r.pos());
                    level.setBlock(p, r.state(), EventWriter.FLAGS);
                    w.touched(p);
                }
                w.pin();
                if (e.journal.isEmpty()) {
                    // Given back: the blocks, and anything it spawned that is still here.
                    removeSpawned(level, e.id);
                    e.phase = EventPhase.DONE;
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
