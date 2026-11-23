package com.moddingfromamod.wish;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.moddingfromamod.marshal.ArtifactOwner;
import com.moddingfromamod.store.PlayerShelf;
import com.moddingfromamod.store.WishStoreFile;

/**
 * <b>The tiles a child took off his watch</b> —, on his own shelf.
 *
 * <p>A HIDE, not a deep delete. The design behind a tile may stay on disk,
 * but the watch does not show it, the account page does not show it, and the game does not quietly
 * reuse it — until he wishes for it again and that design is saved, which un-hides it. A saved
 * BUTTON he owns is not hidden at all: it is forgotten, as the account page's delete always did
 * ({@code LearnedTemplates.forgetOwned}). This list holds what cannot be forgotten that way — his
 * creations, the parts of a bigger wish, a button nobody owns, a library starter.
 *
 * <p>Keyed by the TILE's id: a creation's {@code id} (the cell's {@code arg} — a species, building,
 * machine or train id, an item's {@code id:<design>}) and a button's {@code recipeId}.
 *
 * <p>The file is {@code <players>/<uuid>/hidden.json}, a versioned store ({@code hidden} v1, refused
 * when a newer Moddy wrote it), shaped like the other shelf stores so a shelf sync
 * merges it per key: one row per hidden tile, {@code {"id","at"}}. Two servers on one machine write
 * it locked and re-read, like {@code ShelfProvenance}; an un-hide removes the row. Across machines
 * the syncer's merge lets a HIDE win a conflict, so a tile he took away never comes back by race.
 */
public final class WatchHides {

    public static final String STORE = "hidden";
    public static final int SCHEMA = 1;
    public static final String FILE = "hidden.json";
    static final String ARRAY = "hidden";

    /** A file's ids, and the stamp they were read at. */
    private record Cached(long mtime, long size, Set<String> ids) {
    }

    private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();

    private WatchHides() {
    }

    public static Path file(Path playersRoot, String owner) {
        return playersRoot.resolve(owner).resolve(FILE);
    }

    // ── pure: a root, in and out ────────────────────────────────────────────────────────────

    /** The ids on a root (in file order); empty for a null or malformed one. */
    static Set<String> ids(JsonObject root) {
        Set<String> out = new LinkedHashSet<>();
        if (root != null && root.has(ARRAY) && root.get(ARRAY).isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray(ARRAY)) {
                String id = idOf(e);
                if (!id.isEmpty()) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    /** Hide one id on a root, in place; a tile already hidden keeps its row (and its time). */
    static boolean add(JsonObject root, String id, long at) {
        JsonArray rows = rows(root);
        for (JsonElement e : rows) {
            if (id.equals(idOf(e))) {
                return false;
            }
        }
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("at", at);
        rows.add(row);
        return true;
    }

    /** Un-hide one id on a root, in place. */
    static boolean remove(JsonObject root, String id) {
        JsonArray rows = rows(root);
        boolean removed = false;
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (id.equals(idOf(rows.get(i)))) {
                rows.remove(i);
                removed = true;
            }
        }
        return removed;
    }

    /**
     * Is this a tile id we will write and an operator may name? Not empty, not absurd, no control
     * characters or spaces — the ids the watch mints are a creation's key (letters, digits, {@code
     * _ - . : ~ # @ +}) and a recipe id. The account service validates the same alphabet before
     * anything reaches an RCON line.
     */
    public static boolean isTileId(String id) {
        return id != null && id.matches("[0-9A-Za-z_\\-.:~#@+]{1,128}");
    }

    // ── the file ────────────────────────────────────────────────────────────────────────────

    /** The tiles {@code owner} hid; empty on any trouble (absent, refused, not shelvable). */
    public static Set<String> hiddenFor(Path playersRoot, String owner) {
        String o = ArtifactOwner.normalise(owner);
        if (playersRoot == null || !PlayerShelf.isShelvable(o)) {
            return Set.of();
        }
        Path f = file(playersRoot, o);
        long mtime;
        long size;
        try {
            if (!Files.isRegularFile(f)) {
                CACHE.remove(f);
                return Set.of();
            }
            mtime = Files.getLastModifiedTime(f).toMillis();
            size = Files.size(f);
        } catch (IOException e) {
            return Set.of();
        }
        Cached c = CACHE.get(f);
        if (c != null && c.mtime() == mtime && c.size() == size) {
            return c.ids();
        }
        Set<String> ids = Collections.unmodifiableSet(ids(
                WishStoreFile.rootOf(WishStoreFile.readFile(f, STORE, SCHEMA))));
        CACHE.put(f, new Cached(mtime, size, ids));
        return ids;
    }

    /** {@link #hiddenFor(Path, String)} on this machine's shelves. */
    public static Set<String> hiddenFor(String owner) {
        return hiddenFor(com.moddingfromamod.runtime.RuntimeConfig.playersDir(), owner);
    }

    public static boolean isHidden(String owner, String tileId) {
        return tileId != null && !tileId.isEmpty() && hiddenFor(owner).contains(tileId);
    }

    /**
     * Would serving this template REUSE something he hid? Either it is a button he hid itself, or a
     * one-thing recipe that remakes a creation he hid (its tile carried that recipe).
     */
    public static boolean hidesTemplate(String owner, String templateId) {
        if (templateId == null || templateId.isEmpty()) {
            return false;
        }
        Set<String> hidden = hiddenFor(owner);
        if (hidden.isEmpty()) {
            return false;
        }
        if (hidden.contains(templateId)) {
            return true;
        }
        for (var t : LearnedTemplates.taught()) {
            if (templateId.equals(t.id()) && !com.moddingfromamod.marshal.TaughtJoin.isWish(t)) {
                for (String h : hidden) {
                    if (com.moddingfromamod.marshal.TaughtJoin.names(t, h)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Hide a tile on his shelf. False when nothing changed, or the file is not ours to write. */
    public static boolean hide(Path playersRoot, String owner, String tileId, long at) throws IOException {
        return edit(playersRoot, owner, tileId, true, at);
    }

    /** Un-hide a tile on his shelf. False when it was not hidden. */
    public static boolean unhide(Path playersRoot, String owner, String tileId) throws IOException {
        return edit(playersRoot, owner, tileId, false, 0L);
    }

    /**
     * <b>A design was just SAVED — it comes back to the watch</b> ("a hidden creation comes
     * back only when he wishes it again"). Asked of the record's own owner and of the child the
     * wish in flight is bound to, which is who just wished it. Best effort: a failure here leaves a
     * tile hidden, never a save undone. Cheap when nothing is hidden — one stat of a missing file.
     */
    public static void unhideSaved(String owner, String tileId) {
        if (tileId == null || tileId.isEmpty()) {
            return;
        }
        Path root = com.moddingfromamod.runtime.RuntimeConfig.playersDir();
        Set<String> owners = new LinkedHashSet<>();
        owners.add(ArtifactOwner.normalise(owner));
        owners.add(ArtifactOwner.normalise(com.moddingfromamod.agent.AgentContext.currentPlayerId()));
        for (String o : owners) {
            if (!o.isEmpty() && hiddenFor(root, o).contains(tileId)) {
                try {
                    unhide(root, o, tileId);
                } catch (IOException | RuntimeException e) {
                    // left hidden; the design itself was saved
                }
            }
        }
    }

    private static boolean edit(Path playersRoot, String owner, String tileId, boolean hide, long at)
            throws IOException {
        String o = ArtifactOwner.normalise(owner);
        if (playersRoot == null || !PlayerShelf.isShelvable(o) || !isTileId(tileId)) {
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
                if (!hide) {
                    return false;
                }
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
            boolean changed = hide ? add(root, tileId, at) : remove(root, tileId);
            if (changed) {
                WishStoreFile.writeFile(f, STORE, SCHEMA, root);
                CACHE.remove(f);
            }
            return changed;
        }
    }

    /** Test only: forget what was read, as a server that just started would. */
    public static void dropCacheForTest() {
        CACHE.clear();
    }

    private static JsonArray rows(JsonObject root) {
        if (root.has(ARRAY) && root.get(ARRAY).isJsonArray()) {
            return root.getAsJsonArray(ARRAY);
        }
        JsonArray rows = new JsonArray();
        root.add(ARRAY, rows);
        return rows;
    }

    private static String idOf(JsonElement e) {
        if (e != null && e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("id") && o.get("id").isJsonPrimitive()) {
                return o.get("id").getAsString();
            }
        }
        return "";
    }
}
