package com.moddingfromamod.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.store.WishStoreFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Who has been shown this world's first-join intro</b> —
 * {@code <world>/wishes/intro-seen.json}, {@code docs/WISH_STORES.md} §22.
 *
 * <pre>
 * {"store":"intro-seen","v":1,"players":[{"ownerId":"…","at":1759…}]}
 * </pre>
 *
 * <p>A WORLD store and deliberately not a shelf one: the shelf follows a child from world to world,
 * and the intro is per world — a flag on the shelf would skip one world's intro for a child who had
 * already met another. It lives with the world's other stores, so every backup carries it, the
 * census reads it keyed by {@code ownerId} ({@code artifact-census.py} {@code WORLD_ONLY}), and
 * {@code erase-player.py} drops his row (he would simply be introduced again).
 *
 * <p>The rules of {@code WISH_STORES.md} §2 through {@link WishStoreFile}: an unreadable file is a
 * REFUSAL — nobody is introduced, rather than everybody being re-introduced and the file written over
 * — and a refused file is never saved over (the {@link WishStoreFile.Guard} latch). Minecraft-free;
 * Gate A is {@code IntroSeenStoreTest}.
 */
public final class IntroSeenStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("moddingfromamod/intro");

    public static final String STORE = "intro-seen";
    public static final int SCHEMA = 1;
    public static final String FILE = "intro-seen.json";

    private final Path file;
    private final WishStoreFile.Guard guard = new WishStoreFile.Guard();
    private final Map<UUID, Long> seen = new LinkedHashMap<>();
    private boolean loaded;

    public IntroSeenStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** Read the file. Returns the refusal reason, or null. */
    public synchronized String load() {
        loaded = true;
        seen.clear();
        guard.reset();
        var read = guard.observe(WishStoreFile.readFile(file, STORE, SCHEMA));
        if (read instanceof WishStoreFile.Read.Refused r) {
            LOGGER.error("could not read {}: {}. Nobody is shown the join intro until it is looked at, and "
                    + "nothing is saved over it.", file, r.reason());
            return r.reason();
        }
        JsonObject root = WishStoreFile.rootOf(read);
        JsonElement arr = root == null ? null : root.get("players");
        if (arr != null && arr.isJsonArray()) {
            for (JsonElement el : arr.getAsJsonArray()) {
                try {
                    JsonObject o = el.getAsJsonObject();
                    seen.put(UUID.fromString(o.get("ownerId").getAsString()),
                            o.has("at") ? o.get("at").getAsLong() : 0L);
                } catch (RuntimeException e) {
                    LOGGER.warn("skipped a damaged intro-seen record: {}", e.toString());
                }
            }
        }
        return null;
    }

    /** False when the last load was refused. */
    public synchronized boolean usable() {
        if (!loaded) {
            load();
        }
        return guard.mayWrite();
    }

    public synchronized boolean seen(UUID player) {
        if (!loaded) {
            load();
        }
        return player != null && seen.containsKey(player);
    }

    /**
     * Record that {@code player} has been introduced, and save. False when the latch refused the
     * write (then nothing is kept in memory either, so the next look reads the same file).
     */
    public synchronized boolean markSeen(UUID player, long at) {
        if (player == null || !usable()) {
            return false;
        }
        if (seen.containsKey(player)) {
            return true;
        }
        seen.put(player, at);
        if (!save()) {
            seen.remove(player);
            return false;
        }
        return true;
    }

    private boolean save() {
        JsonArray arr = new JsonArray();
        for (Map.Entry<UUID, Long> e : seen.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("ownerId", e.getKey().toString());
            o.addProperty("at", e.getValue());
            arr.add(o);
        }
        JsonObject payload = new JsonObject();
        payload.add("players", arr);
        try {
            WishStoreFile.writeFile(file, STORE, SCHEMA, payload);
            return true;
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("could not save {}: {}", file, ex.toString());
            return false;
        }
    }
}
