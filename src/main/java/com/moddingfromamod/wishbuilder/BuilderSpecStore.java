package com.moddingfromamod.wishbuilder;

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
 * <b>What each Builder make was made FROM</b>: the spec, kept on the child's shelf beside the thing,
 * so "change one thing" reopens the Builder with every pick he made and edits that same thing.
 *
 * <p>The file is {@code <players>/<uuid>/builder-specs.json}, a versioned store ({@code builder-specs} v1,
 * refused when a newer Moddy wrote it), shaped like the other shelf stores so a server's shelf sync
 * merges it per key (docs/WISH_STORES.md §9): one row per THING made, keyed by its id —
 * {@code {"id","ref","kind","spec","wish","at"}}, plus two optional fields:
 * <ul>
 *   <li>{@code alias}: an item's own id, the one {@code make_item} minted;</li>
 *   <li>{@code touched}: the wish id of a MAGICIAN wish that changed the thing since.</li>
 * </ul>
 * ({@code ref} is what the tool knows the thing by: its id, or for an item the name {@code make_item} was given). An edit replaces the row (the latest picks are the thing's).
 *
 * <p><b>Magician-touched.</b> Once the wish worker has changed a Builder make, its spec no longer describes it,
 * and re-lowering the spec under the same id would silently throw the Magician's change away. So the row is marked
 * ({@link #touch}), kept for history, and no longer offered as an edit ({@link #idFor}, {@code BuilderMenus.edit},
 * {@code BuilderMake}). A Builder edit of an untouched make clears nothing and changes nothing (as before).
 * Written locked and re-read, like {@code WatchHides}. A stored spec of any version is read through
 * {@link BuilderSpec#read}, so {@link SpecMigrations} upgrades it.
 */
public final class BuilderSpecStore {

    public static final String STORE = "builder-specs";
    public static final int SCHEMA = 1;
    public static final String FILE = "builder-specs.json";
    static final String ARRAY = "specs";

    private BuilderSpecStore() {
    }

    /**
     * One stored make: the thing's id, what the tool calls it, its kind, the spec it was made from, the wish, when;
     * an item's own minted id ({@code alias}, "" when none), and the Magician wish that changed it since ({@code touched},
     * "" when none:).
     */
    public record Made(String id, String ref, String kind, JsonObject spec, String wish, long at, String alias,
                       String touched) {
        public Made {
            alias = alias == null ? "" : alias;
            touched = touched == null ? "" : touched;
        }

        public Made(String id, String ref, String kind, JsonObject spec, String wish, long at) {
            this(id, ref, kind, spec, wish, at, "", "");
        }

        public BuilderSpec read() {
            return BuilderSpec.read(spec);
        }

        /** The wish worker changed this thing after the Builder made it: its spec no longer describes it. */
        public boolean magicianTouched() {
            return !touched.isEmpty();
        }

        /** Is {@code name} one of the names this row's thing goes by (its id, its ref, its item id)? */
        boolean answersTo(String name) {
            if (name == null || name.isBlank()) {
                return false;
            }
            String n = name.strip();
            return n.equals(id) || n.equalsIgnoreCase(ref) || (!alias.isEmpty() && n.equals(alias));
        }
    }

    public static Path file(Path playersRoot, String owner) {
        return playersRoot.resolve(owner).resolve(FILE);
    }

    // ── pure: a root, in and out ────────────────────────────────────────────────────────────

    /** The rows on a root, by id (file order); empty for a null or malformed one. */
    static Map<String, Made> rows(JsonObject root) {
        Map<String, Made> out = new LinkedHashMap<>();
        if (root != null && root.has(ARRAY) && root.get(ARRAY).isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray(ARRAY)) {
                if (e.isJsonObject()) {
                    JsonObject o = e.getAsJsonObject();
                    if (o.has("id") && o.get("spec") instanceof JsonObject spec) {
                        out.put(o.get("id").getAsString(), new Made(o.get("id").getAsString(),
                                o.has("ref") ? str(o, "ref") : o.get("id").getAsString(), str(o, "kind"), spec, str(o, "wish"),
                                o.has("at") ? o.get("at").getAsLong() : 0L, str(o, "alias"), str(o, "touched")));
                    }
                }
            }
        }
        return out;
    }

    /** Put one row on a root, in place, replacing any row with the same id. */
    static void put(JsonObject root, Made m) {
        JsonArray rows = root.has(ARRAY) && root.get(ARRAY).isJsonArray() ? root.getAsJsonArray(ARRAY) : new JsonArray();
        root.add(ARRAY, rows);
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (rows.get(i).isJsonObject() && m.id().equals(str(rows.get(i).getAsJsonObject(), "id"))) {
                rows.remove(i);
            }
        }
        JsonObject row = new JsonObject();
        row.addProperty("id", m.id());
        row.addProperty("ref", m.ref());
        row.addProperty("kind", m.kind());
        row.add("spec", m.spec().deepCopy());
        row.addProperty("wish", m.wish());
        row.addProperty("at", m.at());
        if (!m.alias().isEmpty()) {
            row.addProperty("alias", m.alias());
        }
        if (!m.touched().isEmpty()) {
            row.addProperty("touched", m.touched());
        }
        rows.add(row);
    }

    /**
     * Mark, in place, every untouched row whose thing answers to one of {@code names} as changed by wish {@code wish}
     *. Returns the ids marked; empty when none matched (most wishes touch no Builder make). A row already
     * touched keeps its first mark.
     */
    static java.util.List<String> touch(JsonObject root, java.util.Collection<String> names, String wish) {
        java.util.List<String> marked = new java.util.ArrayList<>();
        if (root == null || names == null || names.isEmpty() || !root.has(ARRAY) || !root.get(ARRAY).isJsonArray()) {
            return marked;
        }
        Map<String, Made> rows = rows(root);
        for (JsonElement e : root.getAsJsonArray(ARRAY)) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject row = e.getAsJsonObject();
            Made m = rows.get(str(row, "id"));
            if (m == null || m.magicianTouched() || names.stream().noneMatch(m::answersTo)) {
                continue;
            }
            row.addProperty("touched", wish == null || wish.isBlank() ? "magician" : wish);
            marked.add(m.id());
        }
        return marked;
    }

    // ── the file ────────────────────────────────────────────────────────────────────────────

    /** The make {@code owner} keeps for thing {@code id}, or null (none, refused, not shelvable). */
    public static Made get(Path playersRoot, String owner, String id) {
        return all(playersRoot, owner).get(id);
    }

    /** Every make {@code owner} keeps; empty on any trouble. */
    public static Map<String, Made> all(Path playersRoot, String owner) {
        String o = ArtifactOwner.normalise(owner);
        if (playersRoot == null || !PlayerShelf.isShelvable(o)) {
            return Map.of();
        }
        return rows(WishStoreFile.rootOf(WishStoreFile.readFile(file(playersRoot, o), STORE, SCHEMA)));
    }

    /**
     * The kept make a watch tile shows, by the tile's id ({@code arg}) or — an item, whose tile id is one
     * {@code make_item} minted — by the name it was made under ({@code label}). Null when none is his.
     */
    public static String idFor(String owner, String arg, String label) {
        Map<String, Made> mine = all(com.moddingfromamod.runtime.RuntimeConfig.playersDir(), owner);
        return idFor(mine, arg, label);
    }

    static String idFor(Map<String, Made> mine, String arg, String label) {
        if (arg != null && mine.containsKey(arg)) {
            return mine.get(arg).magicianTouched() ? null : arg;   //: the Magician changed it; no Builder edit
        }
        for (Made m : mine.values()) {
            if (m.ref().equals(arg) || (!m.alias().isEmpty() && m.alias().equals(arg))
                    || (!m.ref().equals(m.id()) && m.ref().equalsIgnoreCase(label == null ? "" : label.strip()))) {
                return m.magicianTouched() ? null : m.id();
            }
        }
        return null;
    }

    public static Made get(String owner, String id) {
        return get(com.moddingfromamod.runtime.RuntimeConfig.playersDir(), owner, id);
    }

    /** Keep a make on {@code owner}'s shelf. False when the file is not ours to write (refused, not shelvable). */
    public static boolean put(Path playersRoot, String owner, Made m) throws IOException {
        if (m == null || m.id() == null || !m.id().matches("[0-9A-Za-z_\\-.:~#@+]{1,128}")) {
            return false;
        }
        return edit(playersRoot, owner, root -> {
            put(root, m);
            return true;
        });
    }

    /**
     * Mark {@code owner}'s Builder makes that answer to any of {@code names} as changed by the Magician. The
     * ids marked; empty when he has no such make, no file, or the file is not ours to write.
     */
    public static java.util.List<String> touch(Path playersRoot, String owner, java.util.Collection<String> names,
                                               String wish) throws IOException {
        java.util.List<String> marked = new java.util.ArrayList<>();
        if (names == null || names.isEmpty() || !Files.exists(file(playersRoot == null ? Path.of(".") : playersRoot,
                ArtifactOwner.normalise(owner)))) {
            return marked;   // no file: nothing he made in the Builder, and no empty file written for it
        }
        edit(playersRoot, owner, root -> {
            marked.addAll(touch(root, names, wish));
            return !marked.isEmpty();
        });
        return marked;
    }

    public static java.util.List<String> touch(String owner, java.util.Collection<String> names, String wish)
            throws IOException {
        return touch(com.moddingfromamod.runtime.RuntimeConfig.playersDir(), owner, names, wish);
    }

    /**
     * Read {@code owner}'s file under its lock, let {@code change} edit the root, and write it back when it says it
     * changed something. False when the file is not ours to write (refused, not shelvable) or nothing changed.
     */
    private static boolean edit(Path playersRoot, String owner, java.util.function.Predicate<JsonObject> change)
            throws IOException {
        String o = ArtifactOwner.normalise(owner);
        if (playersRoot == null || !PlayerShelf.isShelvable(o)) {
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
            if (!change.test(root)) {
                return false;
            }
            WishStoreFile.writeFile(f, STORE, SCHEMA, root);
            return true;
        }
    }

    public static boolean put(String owner, Made m) throws IOException {
        return put(com.moddingfromamod.runtime.RuntimeConfig.playersDir(), owner, m);
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
