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
import com.moddingfromamod.store.WishStoreFile;

/**
 * <b>Every server's own record of which model made each artifact</b> (model-stamp half).
 *
 * <p>{@code <mod home>/provenance/stamps.json}, a {@link WishStoreFile} envelope ({@code stamps},
 * v1): {@code {"stamps": {"<kind>:<id>": [grant, grant, …]}}}, a grant being a {@link ModelStamp}.
 * Written by {@link ProvenancePublisher#record} — the one call every persisted wish already makes
 * (CLAUDE.md, "Every persisted wish publishes its provenance") — and written EVEN WHEN NO MOD REPO
 * IS CONFIGURED, which is the case on the home server: a Mod Repo manifest alone would have left
 * every real grant unstamped.
 *
 * <p>Why the mod home: it is backed up (backup-archive.py carries it, minus the logs), it is on
 * every server with no configuration, and two servers on one machine share it — so the write is a
 * locked read-merge-write, the same discipline as a shelf's. A stamp holds no words and no player
 * id; the one thing in it that is a child's is a FORK key ({@code spikor~1f2e3d4c}), which
 * {@code erase-player.py} removes with the rest of his forks.
 *
 * <p>The merge is pure ({@link #add}) so a unit test reads it without a file.
 */
public final class StampLedger {

    public static final String STORE = "stamps";
    public static final int SCHEMA = 1;
    static final String KEY = "stamps";
    /** A thing re-made a hundred times keeps its first grant and its newest; the middle is noise. */
    static final int MAX_GRANTS = 32;

    private StampLedger() {
    }

    public static Path file() {
        return com.moddingfromamod.runtime.RuntimeConfig.home().resolve("provenance")
                .resolve("stamps.json");
    }

    /**
     * False on a gate run pointed at the REAL mod home: a gate's test artifacts must not land in the
     * ledger a real server's promotions read — the same rule {@code RuntimeConfig.persistDir()}
     * keeps for the Mod Repo. A gate that sets {@code -Dmoddingfromamod.home} gets its own ledger.
     */
    public static boolean writable() {
        return !com.moddingfromamod.RunContext.isTestRun()
                || System.getProperty(com.moddingfromamod.ModdingFromAModMod.MODID + ".home") != null;
    }

    /** The ledger key: {@code species:spikor~1f2e3d4c}. */
    public static String key(String kind, String id) {
        return kind + ":" + id;
    }

    /**
     * Add one grant to a ledger root, in place. The FIRST grant is never dropped — it says which
     * model made the thing — and past {@link #MAX_GRANTS} the oldest refinements after it go.
     */
    public static JsonObject add(JsonObject root, String kind, String id, ModelStamp stamp) {
        JsonObject all = root.has(KEY) && root.get(KEY).isJsonObject()
                ? root.getAsJsonObject(KEY) : new JsonObject();
        root.add(KEY, all);
        String k = key(kind, id);
        JsonArray grants = all.has(k) && all.get(k).isJsonArray() ? all.getAsJsonArray(k)
                : new JsonArray();
        grants.add(stamp.toJson());
        while (grants.size() > MAX_GRANTS) {
            grants.remove(1);
        }
        all.add(k, grants);
        return root;
    }

    /** Every grant recorded for one artifact, oldest first; empty when none (unattributable). */
    public static List<ModelStamp> grants(JsonObject root, String kind, String id) {
        List<ModelStamp> out = new ArrayList<>();
        if (root == null || !root.has(KEY) || !root.get(KEY).isJsonObject()) {
            return out;
        }
        JsonElement g = root.getAsJsonObject(KEY).get(key(kind, id));
        if (g != null && g.isJsonArray()) {
            for (JsonElement e : g.getAsJsonArray()) {
                if (e.isJsonObject()) {
                    out.add(ModelStamp.fromJson(e.getAsJsonObject()));
                }
            }
        }
        return out;
    }

    /**
     * Record a grant in the ledger file: locked, re-read, merged, written atomically. A file a
     * NEWER Moddy wrote, or one that does not parse, is left alone and the grant is not recorded —
     * The rule, because rewriting it from a narrower reading loses what the newer one wrote.
     *
     * @return true when the grant is on disk
     */
    public static boolean record(Path f, String kind, String id, ModelStamp stamp) throws IOException {
        Files.createDirectories(f.getParent());
        Path lock = f.resolveSibling(f.getFileName() + ".lock");
        try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
             FileLock ignored = ch.lock()) {
            WishStoreFile.Read read = WishStoreFile.readFile(f, STORE, SCHEMA);
            JsonObject root;
            if (read instanceof WishStoreFile.Read.Absent) {
                root = new JsonObject();
            } else {
                root = WishStoreFile.rootOf(read);
                if (root == null) {
                    return false;          // refused: newer, another store, or unreadable
                }
                root = root.deepCopy();
                root.remove("store");
                root.remove("v");
            }
            WishStoreFile.writeFile(f, STORE, SCHEMA, add(root, kind, id, stamp));
            return true;
        }
    }

    /** Every grant for one artifact, read from the ledger file; empty on any trouble. */
    public static List<ModelStamp> read(Path f, String kind, String id) {
        return grants(WishStoreFile.rootOf(WishStoreFile.readFile(f, STORE, SCHEMA)), kind, id);
    }
}
