package com.moddingfromamod.provenance;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.moddingfromamod.store.PlayerShelf;
import com.moddingfromamod.store.WishStoreFile;

/**
 * <b>Which model made it, on the child's own shelf</b> —.
 *
 * <p>{@link StampLedger} keeps every grant in the SERVER's mod home, which a creature leaves behind
 * when it follows its child to Survival, to another server or home: the shelf is
 * what travels. So a grant of an OWNED
 * artifact is also written here, {@code <players>/<owner>/provenance.json}. The server's ledger keeps
 * everything as before; a reader takes both ({@link #grantsFor}).
 *
 * <p>The shape is a shelf store's, so a shelf syncer merges it like the others: one row PER GRANT,
 * {@code id = <kind>:<artifact>@<at>}. Rows are only ever added, and two servers' grants have
 * different ids, so a per-key three-way merge of two machines' files is a union — nothing either
 * wrote can be lost. The lock is the one a syncer takes for this file
 * ({@code .provenance.lock}, as {@link PlayerShelf} names its own). No words, no player id in a
 * row: the owner is the folder.
 */
public final class ShelfProvenance {

    public static final String STORE = "provenance";
    public static final int SCHEMA = 1;
    public static final String FILE = "provenance.json";
    static final String ARRAY = "grants";

    private ShelfProvenance() {
    }

    public static Path file(Path playersRoot, String owner) {
        return playersRoot.resolve(owner).resolve(FILE);
    }

    static String rowId(String kind, String id, ModelStamp stamp) {
        return StampLedger.key(kind, id) + "@" + stamp.at();
    }

    /**
     * Add one grant to a root, in place. The same grant twice (same artifact, same millisecond) is
     * one row. Past {@link StampLedger#MAX_GRANTS} for one artifact the oldest after the FIRST go,
     * the server ledger's rule: the first says which model made it.
     */
    static JsonObject add(JsonObject root, String kind, String id, ModelStamp stamp) {
        JsonArray rows = root.has(ARRAY) && root.get(ARRAY).isJsonArray() ? root.getAsJsonArray(ARRAY)
                : new JsonArray();
        root.add(ARRAY, rows);
        String rid = rowId(kind, id, stamp);
        for (JsonElement e : rows) {
            if (e.isJsonObject() && rid.equals(str(e.getAsJsonObject(), "id"))) {
                return root;
            }
        }
        JsonObject row = stamp.toJson();
        row.addProperty("id", rid);
        row.addProperty("kind", kind);
        row.addProperty("artifact", id);
        rows.add(row);
        List<Integer> mine = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (isFor(rows.get(i), kind, id)) {
                mine.add(i);
            }
        }
        // mine is in file order; order by `at` so "first" means the first grant, not the first row
        mine.sort((a, b) -> Long.compare(at(rows.get(a)), at(rows.get(b))));
        List<Integer> drop = new ArrayList<>();
        for (int i = 1; mine.size() - drop.size() > StampLedger.MAX_GRANTS; i++) {
            drop.add(mine.get(i));
        }
        drop.sort((a, b) -> Integer.compare(b, a));
        for (int i : drop) {
            rows.remove(i);
        }
        return root;
    }

    /** Every grant on this root for one artifact, oldest first. */
    static List<ModelStamp> grants(JsonObject root, String kind, String id) {
        List<JsonObject> hits = new ArrayList<>();
        if (root != null && root.has(ARRAY) && root.get(ARRAY).isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray(ARRAY)) {
                if (isFor(e, kind, id)) {
                    hits.add(e.getAsJsonObject());
                }
            }
        }
        hits.sort((a, b) -> Long.compare(at(a), at(b)));
        List<ModelStamp> out = new ArrayList<>();
        for (JsonObject o : hits) {
            out.add(ModelStamp.fromJson(o));
        }
        return out;
    }

    /**
     * Record a grant on {@code owner}'s shelf: locked, re-read, added, written. A file a newer Moddy
     * wrote, or one that does not parse, is left alone and false is returned. An owner that
     * cannot name a shelf directory ({@link PlayerShelf#isShelvable}) is not recorded here.
     */
    public static boolean record(Path playersRoot, String owner, String kind, String id,
                                 ModelStamp stamp) throws IOException {
        if (playersRoot == null || !PlayerShelf.isShelvable(owner)) {
            return false;
        }
        Path f = file(playersRoot, owner);
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
                    return false;
                }
                root = root.deepCopy();
                root.remove("store");
                root.remove("v");
            }
            WishStoreFile.writeFile(f, STORE, SCHEMA, add(root, kind, id, stamp));
            return true;
        }
    }

    /** Every grant for one artifact on {@code owner}'s shelf; empty on any trouble. */
    public static List<ModelStamp> read(Path playersRoot, String owner, String kind, String id) {
        if (playersRoot == null || !PlayerShelf.isShelvable(owner)) {
            return List.of();
        }
        return grants(WishStoreFile.rootOf(WishStoreFile.readFile(file(playersRoot, owner), STORE, SCHEMA)),
                kind, id);
    }

    /**
     * What a reader should use: the server ledger's grants and the owner's shelf's, one list, oldest
     * first, the same grant (same {@code at}) once. Grants made on another server are only on the
     * shelf; grants made here only in the ledger.
     */
    public static List<ModelStamp> grantsFor(Path ledger, Path playersRoot, String owner, String kind,
                                             String id) {
        return union(StampLedger.read(ledger, kind, id), read(playersRoot, owner, kind, id));
    }

    static List<ModelStamp> union(List<ModelStamp> a, List<ModelStamp> b) {
        List<ModelStamp> out = new ArrayList<>(a);
        for (ModelStamp s : b) {
            if (out.stream().noneMatch(x -> x.at() == s.at())) {
                out.add(s);
            }
        }
        out.sort((x, y) -> Long.compare(x.at(), y.at()));
        return out;
    }

    /**
     * The owner an artifact's own JSON names ({@code ownerId}), or "" when it names none, is not
     * JSON, or is shared. How {@link ProvenancePublisher#publish} knows whose shelf a grant goes on
     * without a call site changing.
     */
    public static String ownerOf(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        try {
            JsonElement e = com.google.gson.JsonParser.parseString(content);
            if (e.isJsonObject()) {
                return str(e.getAsJsonObject(), com.moddingfromamod.marshal.ArtifactOwner.FIELD);
            }
        } catch (RuntimeException ignored) {
            // not JSON: not an owned record
        }
        return "";
    }

    private static boolean isFor(JsonElement e, String kind, String id) {
        return e.isJsonObject() && kind.equals(str(e.getAsJsonObject(), "kind"))
                && id.equals(str(e.getAsJsonObject(), "artifact"));
    }

    private static long at(JsonElement e) {
        JsonObject o = e.getAsJsonObject();
        return o.has("at") && o.get("at").isJsonPrimitive() ? o.get("at").getAsLong() : 0L;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
