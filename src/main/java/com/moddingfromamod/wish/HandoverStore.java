package com.moddingfromamod.wish;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.HandoverRecord;
import com.moddingfromamod.runtime.WorldPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where the handover records live: on disk, per world, one small file per artifact.
 *
 * <p><b>On disk is the whole point.</b> A record kept only in memory is a session's memory wearing a
 * different hat — it dies with the agent, and the reason for having a record at all is that a worker
 * dying, or the game restarting, or the child coming back tomorrow, must not lose what the last
 * agent decided.
 *
 * <p>Kept beside the world's other wish data rather than in the Mod Repo. The Mod Repo is the
 * published, human-readable history; this is working notes for the next agent, and mixing the two
 * would put half-finished reasoning into something meant to be read.
 *
 * <p><b>Defensive load, like every other store here.</b> A damaged record is skipped, not fatal —
 * losing one artifact's notes is a small problem, and failing to open a world because of them is
 * not.
 */
public final class HandoverStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** Bumped only if the shape changes incompatibly; an unknown version loads as best it can. */
    private static final int VERSION = 1;

    /** In-memory mirror, so a wake costs no disk read. */
    private static final Map<String, HandoverRecord> CACHE = new ConcurrentHashMap<>();

    private HandoverStore() {
    }

    private static Path dir() {
        Path wishes = WorldPaths.wishesDir();
        return wishes == null ? null : wishes.resolve("handover");
    }

    private static String key(String artifactId) {
        return artifactId == null ? "" : artifactId.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The file a key is saved in. An item's id is {@code id:<name>}, which Windows refuses in a file name, so the key
     * goes through {@link com.moddingfromamod.store.SafeFileName} (a file saved under the raw name moves on first read).
     */
    static String fileName(String key) {
        return com.moddingfromamod.store.SafeFileName.of(key) + ".json";
    }

    /**
     * Record what this wish learned, merged onto whatever the last one left.
     *
     * <p>Merged rather than replaced: a revision chain that overwrites is an earlier issue's rework
     * undercount in a new place — four "make it bigger"s would read as four creations.
     */
    public static HandoverRecord write(HandoverRecord record) {
        if (record == null || record.artifactId().isEmpty()) {
            return null;
        }
        String k = key(record.artifactId());
        HandoverRecord existing = read(k);
        HandoverRecord merged = existing == null ? record : existing.thenAlso(record);
        CACHE.put(k, merged);
        save(k, merged);
        return merged;
    }

    /** What is known about this thing, or null. */
    public static HandoverRecord read(String artifactId) {
        String k = key(artifactId);
        if (k.isEmpty()) {
            return null;
        }
        HandoverRecord cached = CACHE.get(k);
        if (cached != null) {
            return cached;
        }
        HandoverRecord loaded = readFile(k, true);
        if (loaded != null) {
            CACHE.put(k, loaded);
        }
        return loaded;
    }

    /**
     * <b>What is on DISK for this id, ignoring the in-memory mirror entirely.</b>
     *
     * <p>The seam a persistence test needs. The question such a test asks is "would this record
     * still be here after a restart", and the honest way to ask it used to be {@code clear()} then
     * {@link #read} — which empties the mirror for <em>every</em> test running concurrently in the
     * same batch. That is invisible while a handful of tests share a batch and fatal at fifty (see
     * docs/GATE_PERFORMANCE.md — it is what stopped the suite being shardable), so the round trip
     * is expressed here instead: this reads the file and touches no shared state at all.
     */
    public static HandoverRecord savedOnDisk(String artifactId) {
        String k = key(artifactId);
        return k.isEmpty() ? null : readFile(k, false);
    }

    private static HandoverRecord readFile(String k, boolean migrate) {
        Path dir = dir();
        if (dir == null) {
            return null;
        }
        Path file = com.moddingfromamod.store.SafeFileName.resolve(dir, k, ".json", migrate);
        if (!Files.isReadable(file)) {
            return null;
        }
        try {
            return fromJson(JsonParser.parseString(Files.readString(file)).getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("skipping damaged handover record '{}': {}", k, e.toString());
            return null;
        }
    }

    /** Everything remembered, for diagnostics and for the gate. */
    public static List<HandoverRecord> all() {
        return new ArrayList<>(CACHE.values());
    }

    /** Between worlds: one world's notes must never be handed to another world's agent. */
    public static void clear() {
        CACHE.clear();
    }

    private static void save(String key, HandoverRecord record) {
        Path dir = dir();
        if (dir == null) {
            return;   // no world open (a unit test); the cache still works
        }
        String blocked = com.moddingfromamod.store.WishStoreFile.writeBlocker(
                dir.resolve(fileName(key)), "version", VERSION);
        if (blocked != null) {
            // A newer or damaged note is left alone — see WishStoreFile.writeBlocker.
            LOGGER.error("NOT saving handover record '{}' — {}. The file has been left alone on purpose.",
                    key, blocked);
            return;
        }
        try {
            Files.createDirectories(dir);
            Path dest = dir.resolve(fileName(key));
            Path tmp = dir.resolve("." + fileName(key) + ".tmp");
            Files.writeString(tmp, toJson(record).toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Never fail a wish over a note. The child got his thing; the next agent just has less
            // to go on.
            LOGGER.warn("could not save handover record '{}': {}", key, e.toString());
        }
    }

    static JsonObject toJson(HandoverRecord r) {
        JsonObject o = new JsonObject();
        o.addProperty("version", VERSION);
        o.addProperty("artifactId", r.artifactId());
        o.addProperty("kind", r.kind());
        o.addProperty("whatItIs", r.whatItIs());
        JsonArray aliases = new JsonArray();
        r.aliases().forEach(aliases::add);
        o.add("aliases", aliases);
        o.addProperty("decisionsAndWhy", r.decisionsAndWhy());
        o.addProperty("triedAndRejected", r.triedAndRejected());
        o.addProperty("where", r.where());
        o.addProperty("revision", r.revision());
        o.addProperty("beCarefulOf", r.beCarefulOf());
        o.addProperty("writtenMs", r.writtenMs());
        return o;
    }

    static HandoverRecord fromJson(JsonObject o) {
        if (o == null) {
            return null;
        }
        try {
            List<String> aliases = new ArrayList<>();
            if (o.has("aliases") && o.get("aliases").isJsonArray()) {
                o.getAsJsonArray("aliases").forEach(e -> aliases.add(e.getAsString()));
            }
            // A record with no id can never be looked up again, so returning one is worse than
            // returning nothing: it would sit in the cache under "" and shadow real lookups. This
            // is also what a damaged field degrades to — str() answers "" for anything that is not
            // a primitive rather than throwing, so without this check a corrupt file loads as a
            // blank record that looks fine.
            String id = str(o, "artifactId");
            if (id.isBlank()) {
                return null;
            }
            return new HandoverRecord(id, str(o, "kind"), str(o, "whatItIs"),
                    aliases, str(o, "decisionsAndWhy"), str(o, "triedAndRejected"),
                    str(o, "where"), num(o, "revision"), str(o, "beCarefulOf"),
                    o.has("writtenMs") ? o.get("writtenMs").getAsLong() : 0L);
        } catch (RuntimeException e) {
            return null;   // one damaged record must never stop a world opening
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static int num(JsonObject o, String k) {
        try {
            return o.has(k) ? o.get(k).getAsInt() : 1;
        } catch (RuntimeException e) {
            return 1;
        }
    }
}
