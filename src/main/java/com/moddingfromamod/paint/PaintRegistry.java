package com.moddingfromamod.paint;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every picture a wish has painted, so one can be put back.
 *
 * <p>Same shape and same file layout as {@code BuildRegistry} — per-world, JSON under
 * {@code <world>/wishes/}, bounded, and defensive on load — because it answers the same question for
 * {@code WishUndo}: <i>what did this wish make, and how do I reverse it?</i>
 *
 * <p><b>Per-world rather than global</b>, like everything else a wish produces. {@code WorldPaths}
 * records why: a shared store let re-sculpting a creature in one world silently rewrite another
 * world's creature, and a picture keyed by a name a child chose has exactly that collision.
 */
public final class PaintRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** Bounded like the others. A world with more paintings than this has lost the oldest ones. */
    private static final int MAX = 256;

    private static final Map<String, PaintRecord> RECORDS = new LinkedHashMap<>();

    private PaintRegistry() {
    }

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("wishes").resolve("paintings.json");
    }

    /** The envelope name, and the newest schema this build reads (stamped this store). */
    static final String STORE = "paintings";
    /**
     * v2: a record may carry {@code ownerId}. Additive — a v1 file reads as every picture
     * shared — but BUMPED so an older jar refuses this file rather than re-saving it without the
     * owner stamps, which would quietly make every child's picture everyone's.
     */
    static final int SCHEMA = 2;

    /** The rule 2: a refused read latches every write off until the next world load. */
    private static final com.moddingfromamod.store.WishStoreFile.Guard GUARD =
            new com.moddingfromamod.store.WishStoreFile.Guard();

    public static synchronized void load(MinecraftServer server) {
        RECORDS.clear();
        GUARD.reset();   // a new world load is a fresh judgement
        readInto(server, RECORDS, GUARD);
        if (!RECORDS.isEmpty()) {
            LOGGER.info("loaded {} saved painting(s)", RECORDS.size());
        }
    }

    /**
     * <b>What is ON DISK for {@code id}, without touching the shared registry.</b>
     *
     * <p>The per-test seam, same as {@code TrainRegistry.savedOnDisk} and for the same reason: the
     * round-trip test used to prove persistence with {@code put} → {@link #clear()} → {@link #load},
     * which empties a map every concurrently-running test is reading. See
     * docs/GATE_PERFORMANCE.md → "a GameTest never wipes a shared registry".
     */
    public static PaintRecord savedOnDisk(MinecraftServer server, String id) {
        Map<String, PaintRecord> records = new LinkedHashMap<>();
        readInto(server, records, null);
        return id == null ? null : records.get(id);
    }

    /**
     * The one parser, shared by {@link #load} and {@link #savedOnDisk}. {@code guard} is the
     * store's latch on a load and null for the side-effect-free {@code savedOnDisk} read.
     */
    private static void readInto(MinecraftServer server, Map<String, PaintRecord> records,
                                 com.moddingfromamod.store.WishStoreFile.Guard guard) {
        Path p = file(server);
        var read = com.moddingfromamod.store.WishStoreFile.readFile(p, STORE, SCHEMA);
        if (guard != null) {
            guard.observe(read);
        }
        if (read instanceof com.moddingfromamod.store.WishStoreFile.Read.Refused r) {
            // Before the store latches a file that did not parse was logged, the map came back EMPTY, and the
            // next painting rewrote the file from nothing — every picture a child had, gone.
            LOGGER.error("could not read saved paintings at {}: {}. Nothing will be saved over it.",
                    p, r.reason());
            return;
        }
        JsonObject root = com.moddingfromamod.store.WishStoreFile.rootOf(read);
        JsonElement arr = root == null ? null : root.get("paintings");
        if (arr == null || !arr.isJsonArray()) {
            return;
        }
        for (JsonElement el : arr.getAsJsonArray()) {
            if (!el.isJsonObject()) {
                continue;
            }
            // One damaged record must cost that record, never the file: a child with a corrupt
            // entry would otherwise lose every painting he had.
            try {
                PaintRecord r = PaintRecord.fromJson(el.getAsJsonObject());
                if (r != null && !r.id().isEmpty()) {
                    records.put(r.id(), r);
                }
            } catch (RuntimeException e) {
                LOGGER.warn("skipped a damaged painting record: {}", e.toString());
            }
        }
    }

    public static synchronized void put(MinecraftServer server, PaintRecord record) {
        if (record == null || record.id().isEmpty()) {
            return;
        }
        RECORDS.remove(record.id());          // re-inserted so the newest is last
        RECORDS.put(record.id(), record);
        while (RECORDS.size() > MAX) {
            RECORDS.remove(RECORDS.keySet().iterator().next());
        }
        save(server);
    }

    public static synchronized PaintRecord get(String id) {
        return id == null ? null : RECORDS.get(id);
    }

    /** The picture whose map art hangs with its bottom-left at this spot, or null. */
    public static synchronized PaintRecord at(String dimension, int x, int y, int z) {
        for (PaintRecord r : RECORDS.values()) {
            if (r.hasVanillaCopy() && r.x() == x && r.y() == y && r.z() == z
                    && r.dimension().equals(dimension)) {
                return r;
            }
        }
        return null;
    }

    /**
     * The newest picture in this dimension hung within {@code reach} blocks (horizontally) of
     * {@code (x, z)}, or null. A served {@code /wishpaint} names no id to its reveal, so its
     * frame finds the picture it is revealing this way — records are kept newest-last.
     */
    public static synchronized PaintRecord newestNear(String dimension, double x, double z, double reach) {
        PaintRecord found = null;
        for (PaintRecord r : RECORDS.values()) {
            double dx = r.x() + 0.5 - x, dz = r.z() + 0.5 - z;
            if (r.dimension().equals(dimension) && dx * dx + dz * dz <= reach * reach) {
                found = r;
            }
        }
        return found;
    }

    public static synchronized void remove(MinecraftServer server, String id) {
        if (id != null && RECORDS.remove(id) != null) {
            save(server);
        }
    }

    public static synchronized List<String> ids() {
        return new ArrayList<>(RECORDS.keySet());
    }

    public static synchronized int count() {
        return RECORDS.size();
    }

    /** For tests, and for a world that has just been closed. */
    public static synchronized void clear() {
        RECORDS.clear();
    }

    private static void save(MinecraftServer server) {
        if (server == null) {
            return;
        }
        Path p = file(server);
        if (!GUARD.mayWrite()) {
            LOGGER.error("NOT saving paintings — {} could not be read ({}). The file has been left "
                    + "alone on purpose; a grown-up should look at it.", p, GUARD.refusalReason());
            return;
        }
        try {
            JsonArray arr = new JsonArray();
            for (PaintRecord r : RECORDS.values()) {
                arr.add(r.toJson());
            }
            JsonObject payload = new JsonObject();
            payload.add("paintings", arr);
            com.moddingfromamod.store.WishStoreFile.writeFile(p, STORE, SCHEMA, payload);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("could not save paintings to {}: {}", p, e.toString());
        }
    }
}
