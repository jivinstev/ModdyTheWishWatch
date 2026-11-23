package com.moddingfromamod.wish;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.marshal.ArtifactOwner;
import com.moddingfromamod.store.PlayerShelf;
import com.moddingfromamod.store.WishStoreFile;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>A player's own wish settings, on his shelf</b>: today one row, {@code mode}, the Magician's
 * "Magician ✪ / Apprentice ✨" toggle. {@code <players>/<uuid>/wish-settings.json}, store {@code wish-settings} v1,
 * rows {@code {"id": "mode", "value": "apprentice", "at": <ms>}}, so it follows him between servers
 * that share his shelf, is erased with him, and is read like every other shelf store
 * (docs/WISH_STORES.md §9).
 *
 * <p>A file this build cannot read (a newer Moddy's) is never written over: the setting then simply does not
 * stick, which costs a Magician one tap, where a wrong guess could cost him a wish he meant for the agent.
 */
public final class WishSettingsStore {

    public static final String STORE = "wish-settings";
    public static final int SCHEMA = 1;
    public static final String FILE = "wish-settings.json";
    static final String ARRAY = "settings";

    private WishSettingsStore() {
    }

    public static Path file(Path playersRoot, String owner) {
        return playersRoot.resolve(owner).resolve(FILE);
    }

    /** Every setting on a root, id → value; empty for a null or malformed one. Pure. */
    static Map<String, String> rows(JsonObject root) {
        Map<String, String> out = new LinkedHashMap<>();
        if (root != null && root.has(ARRAY) && root.get(ARRAY).isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray(ARRAY)) {
                if (e.isJsonObject() && e.getAsJsonObject().has("id") && e.getAsJsonObject().has("value")) {
                    out.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject().get("value").getAsString());
                }
            }
        }
        return out;
    }

    /** Put one setting on a root, in place, replacing the row with the same id. Pure. */
    static void put(JsonObject root, String id, String value, long at) {
        JsonArray rows = root.has(ARRAY) && root.get(ARRAY).isJsonArray() ? root.getAsJsonArray(ARRAY) : new JsonArray();
        root.add(ARRAY, rows);
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (rows.get(i).isJsonObject() && rows.get(i).getAsJsonObject().has("id")
                    && id.equals(rows.get(i).getAsJsonObject().get("id").getAsString())) {
                rows.remove(i);
            }
        }
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("value", value);
        row.addProperty("at", at);
        rows.add(row);
    }

    /** {@code owner}'s setting {@code id}, or {@code dflt} (none, a file not ours to read, not shelvable). */
    public static String get(Path playersRoot, String owner, String id, String dflt) {
        String o = ArtifactOwner.normalise(owner);
        if (playersRoot == null || !PlayerShelf.isShelvable(o)) {
            return dflt;
        }
        return rows(WishStoreFile.rootOf(WishStoreFile.readFile(file(playersRoot, o), STORE, SCHEMA))).getOrDefault(id, dflt);
    }

    /** Keep a setting. False when the file is not ours to write (refused, not shelvable). */
    public static boolean set(Path playersRoot, String owner, String id, String value) throws IOException {
        String o = ArtifactOwner.normalise(owner);
        if (playersRoot == null || !PlayerShelf.isShelvable(o) || id == null || !id.matches("[a-z_]{1,32}")
                || value == null || !value.matches("[a-z0-9_]{1,32}")) {
            return false;
        }
        Path f = file(playersRoot, o);
        Files.createDirectories(f.getParent());
        Path lock = f.resolveSibling("." + STORE + ".lock");
        try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = ch.lock()) {
            WishStoreFile.Read read = WishStoreFile.readFile(f, STORE, SCHEMA);
            JsonObject root;
            if (read instanceof WishStoreFile.Read.Absent) {
                root = new JsonObject();
            } else {
                root = WishStoreFile.rootOf(read);
                if (root == null) {
                    return false;   // refused: a newer Moddy's file, or one that does not parse
                }
                root = root.deepCopy();
                root.remove("store");
                root.remove("v");
            }
            put(root, id, value, System.currentTimeMillis());
            WishStoreFile.writeFile(f, STORE, SCHEMA, root);
            return true;
        }
    }
}
