package com.moddingfromamod.redstone;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.runtime.WorldPaths;
import com.moddingfromamod.store.WishStoreFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every mechanism built in this world, with its journal: each position it placed and what that position held
 * before. {@code remove_mechanism} puts exactly those back. Stored in {@code <world>/wishes/mechanisms.json} under
 * the one store envelope, so a file this build cannot read is refused and never written over.
 */
public final class MechanismStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("moddingfromamod/mechanisms");
    public static final String STORE = "mechanisms";
    public static final int SCHEMA = 1;

    /** One built mechanism. {@code journal} is in placement order. */
    public record Entry(String id, String kind, String owner, String dimension, String summary,
                        List<MechanismWorld.Placed> journal, List<BlockPos> controls, JsonArray logic) {
        public Entry(String id, String kind, String owner, String dimension, String summary,
                     List<MechanismWorld.Placed> journal, List<BlockPos> controls) {
            this(id, kind, owner, dimension, summary, journal, controls, new JsonArray());
        }
    }

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static final WishStoreFile.Guard GUARD = new WishStoreFile.Guard();
    private static Path loadedFrom;

    private MechanismStore() {
    }

    static Path file() {
        return WorldPaths.wishesDir().resolve("mechanisms.json");
    }

    /** Loads the world's file once (and again when the world changes). Server thread. */
    static synchronized void ensureLoaded(ServerLevel level) {
        Path f = file();
        if (f.equals(loadedFrom)) {
            return;
        }
        loadedFrom = f;
        ENTRIES.clear();
        GUARD.reset();
        var read = GUARD.observe(WishStoreFile.readFile(f, STORE, SCHEMA));
        if (read instanceof WishStoreFile.Read.Refused r) {
            LOGGER.error("MODDY-ALERT could not read {}: {}. Nothing will be saved over it.", f, r.reason());
            return;
        }
        JsonObject root = WishStoreFile.rootOf(read);
        JsonElement arr = root == null ? null : root.get("mechanisms");
        if (arr == null || !arr.isJsonArray()) {
            return;
        }
        for (JsonElement el : arr.getAsJsonArray()) {
            JsonObject o = el.getAsJsonObject();
            List<MechanismWorld.Placed> journal = new ArrayList<>();
            for (JsonElement j : o.getAsJsonArray("journal")) {
                JsonObject p = j.getAsJsonObject();
                BlockState before = MechanismWorld.parse(level, p.get("before").getAsString());
                if (before == null) {
                    before = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                }
                journal.add(new MechanismWorld.Placed(pos(p.getAsJsonArray("at")), before));
            }
            List<BlockPos> controls = new ArrayList<>();
            for (JsonElement c : o.getAsJsonArray("controls")) {
                controls.add(pos(c.getAsJsonArray()));
            }
            Entry e = new Entry(o.get("id").getAsString(), o.get("kind").getAsString(), o.get("owner").getAsString(),
                    o.get("dimension").getAsString(), o.get("summary").getAsString(), journal, controls,
                    o.has("logic") && o.get("logic").isJsonArray() ? o.getAsJsonArray("logic") : new JsonArray());
            ENTRIES.put(e.id(), e);
        }
    }

    static BlockPos pos(JsonArray a) {
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    static JsonArray arr(BlockPos p) {
        JsonArray a = new JsonArray();
        a.add(p.getX());
        a.add(p.getY());
        a.add(p.getZ());
        return a;
    }

    public static synchronized List<Entry> all(ServerLevel level) {
        ensureLoaded(level);
        return List.copyOf(ENTRIES.values());
    }

    public static synchronized Entry get(ServerLevel level, String id) {
        ensureLoaded(level);
        return ENTRIES.get(id);
    }

    /** Adds (or replaces) an entry and saves. False when the store refused to be written. */
    public static synchronized boolean put(ServerLevel level, Entry e) {
        ensureLoaded(level);
        ENTRIES.put(e.id(), e);
        return save();
    }

    public static synchronized boolean remove(ServerLevel level, String id) {
        ensureLoaded(level);
        ENTRIES.remove(id);
        return save();
    }

    /** A fresh id for a new mechanism of {@code kind}: piston_door, piston_door_2, … */
    public static synchronized String freshId(ServerLevel level, String kind) {
        ensureLoaded(level);
        if (!ENTRIES.containsKey(kind)) {
            return kind;
        }
        for (int n = 2; ; n++) {
            if (!ENTRIES.containsKey(kind + "_" + n)) {
                return kind + "_" + n;
            }
        }
    }

    private static boolean save() {
        if (!GUARD.mayWrite()) {
            LOGGER.error("NOT saving {}: {}", loadedFrom, GUARD.refusalReason());
            return false;
        }
        JsonArray list = new JsonArray();
        for (Entry e : ENTRIES.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id());
            o.addProperty("kind", e.kind());
            o.addProperty("owner", e.owner());
            o.addProperty("dimension", e.dimension());
            o.addProperty("summary", e.summary());
            JsonArray journal = new JsonArray();
            for (MechanismWorld.Placed p : e.journal()) {
                JsonObject j = new JsonObject();
                j.add("at", arr(p.pos()));
                j.addProperty("before", net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(p.before()));
                journal.add(j);
            }
            o.add("journal", journal);
            JsonArray controls = new JsonArray();
            e.controls().forEach(c -> controls.add(arr(c)));
            o.add("controls", controls);
            if (e.logic() != null && !e.logic().isEmpty()) {
                o.add("logic", e.logic());     // additive: a reader without logic ignores it
            }
            list.add(o);
        }
        JsonObject payload = new JsonObject();
        payload.add("mechanisms", list);
        try {
            WishStoreFile.writeFile(loadedFrom, STORE, SCHEMA, payload);
            return true;
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("could not save {}: {}", loadedFrom, ex.toString());
            return false;
        }
    }
}
