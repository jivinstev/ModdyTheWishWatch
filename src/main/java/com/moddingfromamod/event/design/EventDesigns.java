package com.moddingfromamod.event.design;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.event.EventDirector;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The events a world keeps</b> (E4): its designs by name, and its schedules ("every N
 * days"). One store per world (D3), versioned; a copy written by a newer build is refused, never
 * overwritten. A design that no longer parses is skipped and logged, never the whole store.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class EventDesigns {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);
    private static final String DATA = ModdingFromAModMod.MODID + "_event_designs";

    private EventDesigns() {
    }

    /** "Every {@code days} days, {@code design}", with what it has banked so far. */
    public static final class Schedule {
        final String design;
        final int days;
        long banked;
        String owner;
        /** The event it staged last, so it never stages a second while the first is running. */
        String running;

        Schedule(String design, int days, long banked, String owner, String running) {
            this.design = design;
            this.days = days;
            this.banked = banked;
            this.owner = owner == null ? "" : owner;
            this.running = running;
        }

        public String design() {
            return design;
        }

        public int days() {
            return days;
        }

        public long banked() {
            return banked;
        }
    }

    public static final class Store extends SavedData {
        public static final int VERSION = 1;
        final Map<String, EventDesign> designs = new LinkedHashMap<>();
        final Map<String, Schedule> schedules = new LinkedHashMap<>();
        String refused;
        /** The day time last looked at; -1 after a load, so the first look only re-bases. */
        transient long lastDayTime = -1;

        static Store load(CompoundTag tag, HolderLookup.Provider lookup) {
            Store s = new Store();
            int v = tag.getIntOr("v", 0);
            if (v > VERSION) {
                s.refused = "the event designs were saved by a newer Moddy (v" + v + ")";
                LOGGER.warn("[events] {}; they are left untouched", s.refused);
                return s;
            }
            ListTag list = tag.getListOrEmpty("designs");
            for (int i = 0; i < list.size(); i++) {
                EventDesign.Parsed p = EventDesign.parse(list.getStringOr(i, ""));
                if (p.ok()) {
                    s.designs.put(p.design().name(), p.design());
                } else {
                    LOGGER.warn("[events] an unreadable design was skipped: {}", p.errors());
                }
            }
            ListTag sch = tag.getListOrEmpty("schedules");
            for (int i = 0; i < sch.size(); i++) {
                CompoundTag c = sch.getCompoundOrEmpty(i);
                String d = c.getStringOr("design", "");
                if (!d.isEmpty()) {
                    s.schedules.put(d, new Schedule(d, c.getIntOr("days", 0), c.getLongOr("banked", 0L),
                            c.getStringOr("owner", ""), c.contains("running") ? c.getStringOr("running", "") : null));
                }
            }
            return s;
        }
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
            tag.putInt("v", VERSION);
            ListTag list = new ListTag();
            for (EventDesign d : designs.values()) {
                list.add(StringTag.valueOf(d.toJson().toString()));
            }
            tag.put("designs", list);
            ListTag sch = new ListTag();
            for (Schedule s : schedules.values()) {
                CompoundTag c = new CompoundTag();
                c.putString("design", s.design);
                c.putInt("days", s.days);
                c.putLong("banked", s.banked);
                c.putString("owner", s.owner);
                if (s.running != null) {
                    c.putString("running", s.running);
                }
                sch.add(c);
            }
            tag.put("schedules", sch);
            return tag;
        }

        /** The names of this world's own designs (never the library's starters). */
        public java.util.Set<String> names() {
            return java.util.Collections.unmodifiableSet(designs.keySet());
        }

        static net.minecraft.world.level.saveddata.SavedDataType<Store> factory() {
            return com.moddingfromamod.compat.SavedDataTypes.of(DATA, Store::new, Store::load,
                    (s, lookup) -> s.save(new CompoundTag(), lookup));
        }

        /** Test seam: behave as if the world had just been saved and loaded again. */
        public void reloadForTest(HolderLookup.Provider lookup) {
            Store again = load(save(new CompoundTag(), lookup), lookup);
            designs.clear();
            designs.putAll(again.designs);
            schedules.clear();
            schedules.putAll(again.schedules);
            refused = again.refused;
            lastDayTime = -1;
        }
    }

    public static Store store(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(Store.factory());
    }

    /** Keep a design; returns the reason it was refused, or null. */
    public static String put(MinecraftServer server, EventDesign d) {
        Store s = store(server);
        if (s.refused != null) {
            return s.refused;
        }
        s.designs.put(d.name(), d);
        s.setDirty();
        return null;
    }

    /** This world's design, else the wish library's (a starter); null when neither has one. */
    public static EventDesign get(MinecraftServer server, String name) {
        String key = EventDesign.clean(name);
        EventDesign own = store(server).designs.get(key);
        return own != null ? own : fromLibrary(key);
    }

    /**
     * This world's designs, then the library's that the world has not replaced. The library's come
     * last, because "the wave" in a wish means the one he was just making.
     */
    public static List<EventDesign> all(MinecraftServer server) {
        List<EventDesign> out = new ArrayList<>(store(server).designs.values());
        for (String key : com.moddingfromamod.library.WishLibrary.keys(LIBRARY_KIND)) {
            if (!store(server).designs.containsKey(key)) {
                EventDesign d = fromLibrary(key);
                if (d != null) {
                    out.add(d);
                }
            }
        }
        return out;
    }

    /** The wish library's kind for event designs: {@code events/<name>/event.json}. */
    public static final String LIBRARY_KIND = "events";

    /** Whether the wish library serves this design — a base, never a write target. */
    public static boolean inLibrary(String name) {
        return com.moddingfromamod.library.WishLibrary.artifactDir(LIBRARY_KIND, EventDesign.clean(name)) != null;
    }

    private static EventDesign fromLibrary(String key) {
        java.nio.file.Path f = com.moddingfromamod.library.WishLibrary.artifactFile(LIBRARY_KIND, key, "event.json");
        if (f == null) {
            return null;
        }
        try {
            EventDesign.Parsed p = EventDesign.parse(java.nio.file.Files.readString(f));
            if (!p.ok()) {
                LOGGER.warn("library event '{}' does not parse: {}", key, p.errors());
                return null;
            }
            return p.design();
        } catch (java.io.IOException e) {
            LOGGER.warn("could not read library event '{}': {}", key, e.toString());
            return null;
        }
    }

    /** Stage {@code design} every {@code days} days from now; refuses a design the world does not know. */
    public static String every(MinecraftServer server, String design, int days, String owner) {
        Store s = store(server);
        String name = EventDesign.clean(design);
        if (s.refused != null) {
            return s.refused;
        }
        if (!s.designs.containsKey(name) && fromLibrary(name) == null) {
            return "there is no event design called " + name;
        }
        s.schedules.put(name, new Schedule(name, Math.max(DayBank.MIN_DAYS, Math.min(DayBank.MAX_DAYS, days)),
                0, owner, null));
        s.setDirty();
        return null;
    }

    public static boolean stop(MinecraftServer server, String design) {
        Store s = store(server);
        boolean had = s.schedules.remove(EventDesign.clean(design)) != null;
        if (had) {
            s.setDirty();
        }
        return had;
    }

    /** Forget a design and its schedule. Returns whether there was one. */
    public static boolean forget(MinecraftServer server, String design) {
        Store s = store(server);
        String name = EventDesign.clean(design);
        boolean had = s.designs.remove(name) != null;
        had |= s.schedules.remove(name) != null;
        if (had) {
            s.setDirty();
        }
        return had;
    }

    public static List<Schedule> schedules(MinecraftServer server) {
        return new ArrayList<>(store(server).schedules.values());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 == 0) {
            tickSchedules(server);
        }
    }

    /** Bank the day clock and stage whatever is due. Public so a GameTest can drive it. */
    public static void tickSchedules(MinecraftServer server) {
        Store s = store(server);
        long now = com.moddingfromamod.mv.DayTime.get(server.overworld());
        long before = s.lastDayTime;
        s.lastDayTime = now;
        if (s.refused != null || s.schedules.isEmpty()) {
            return;
        }
        for (Schedule sch : s.schedules.values()) {
            sch.banked = DayBank.bank(sch.banked, before, now);
            if (!DayBank.due(sch.banked, sch.days)) {
                continue;
            }
            if (sch.running != null && EventDirector.get(server, sch.running) != null) {
                continue; // never two at once: the last one is still going
            }
            EventDesign d = s.designs.get(sch.design);
            ServerPlayer p = pick(server, sch.owner);
            if (d == null || p == null) {
                continue; // nobody to have it happen to; it keeps its bank until somebody is on
            }
            EventBuild.Result r = EventBuild.nearPlayer(p, d, sch.owner);
            if (r.ok()) {
                sch.running = r.id();
                sch.banked = DayBank.spend(sch.banked, sch.days);
            } else {
                LOGGER.warn("[events] the scheduled {} could not be staged: {}", sch.design, r.error());
                sch.banked = DayBank.spend(sch.banked, sch.days);
            }
        }
        s.setDirty();
    }

    /** The owner if he is on, else whoever is. */
    private static ServerPlayer pick(MinecraftServer server, String owner) {
        List<ServerPlayer> on = server.getPlayerList().getPlayers();
        for (ServerPlayer p : on) {
            if (p.getGameProfile().name().equalsIgnoreCase(owner)) {
                return p;
            }
        }
        return on.isEmpty() ? null : on.get(0);
    }
}
