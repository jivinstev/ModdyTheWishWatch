package com.moddingfromamod.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.marshal.ArtifactOwner;

/**
 * <b>One store, two places: a child's things on his own shelf, everyone's in the world</b> — the
 * mechanical half of D6. Minecraft-free and unit-tested
 * ({@code PlayerShelfTest}).
 *
 * <h2>Why</h2>
 *
 * <p>D6 is Creative first, Survival as a fast follow, <b>sharing wish history per player</b>. Two
 * servers are two world directories, and everything a wish made lived in {@code <world>/wishes/}, so
 * a Spikor made in Creative did not exist in Survival. The design settles
 * what "history" means: <i>the things a child made</i> follow him; the log of what he typed stays
 * per server.
 *
 * <h2>The rule</h2>
 *
 * <p>A record with an owner lives in {@code <players>/<owner>/<file>}; a record with none — every
 * record written before owner stamps, and anything a gate or a direct call made — stays in
 * {@code <world>/wishes/<file>}, because unowned already means SHARED
 * ({@link ArtifactOwner}) and a shared thing belongs to the world everybody is standing in. The
 * store keeps ONE in-memory map exactly as before; only the files split. So no call site changes,
 * and the existing {@code preferOwn}/{@code visibleTo} rules decide who sees what — which is why a
 * server loads EVERY child's shelf and not only the children online: ownership, forks and "is this
 * id taken" were already answered across all owners, and splitting the loaded set by who is online
 * would make a fork key's uniqueness depend on who happened to be connected.
 *
 * <h2>⚠ Two servers write one shelf, so a save is a MERGE, not an overwrite</h2>
 *
 * <p>The per-world stores could whole-file overwrite because each had one writer. A shelf has as
 * many as there are servers on the machine, and each holds the shelf in memory from its own boot.
 * A plain overwrite would lose a child's Creative wand the first time Survival — still holding the
 * shelf it read at breakfast — saved anything of his. So each owner's file is written by a
 * three-way merge under a file lock:
 *
 * <pre>
 *   base    = what THIS server last read or wrote for that owner (its own serialisation)
 *   current = what this server holds now
 *   fresh   = what is on disk now
 *
 *   per key:  current == base  → take fresh    (we did not touch it; the other server may have)
 *             current != base  → take current  (we changed it — added, edited or deleted)
 * </pre>
 *
 * <p>⚠ <b>{@code base} is our OWN serialisation, never the disk's bytes.</b> A record read and
 * re-serialised need not produce the same string (field order, defaults a newer build adds), and a
 * base taken from the disk would make every record look changed-by-us at the first save — so ours
 * would always win and the other server's edits would always be lost. That is why a store calls
 * {@link #remember} with its own payload after it loads, and why a write re-bases on
 * {@code current} rather than on what it wrote: a record the OTHER server added is in {@code fresh}
 * and in neither {@code base} nor {@code current}, so it is kept — had the base been the merged
 * write, its absence from memory would read as a deletion at the next save.
 *
 * <h2>What the envelope rules become</h2>
 *
 * <ul>
 *   <li><b>A refused WORLD file latches the whole store</b> ({@link #mayWrite}), as the store latch always did —
 *       the shared records are in it.</li>
 *   <li><b>A refused SHELF latches only that child.</b> His records are not loaded and nothing is
 *       written over his file; every other child's saves go on. One damaged file must not stop the
 *       other players saving, and must never be overwritten either.</li>
 *   <li><b>Migration is convergence.</b> A world file holding owned records (every world before
 *       shelves) reads them like any other; {@link #needsConverge} says so, and the store's next save
 *       partitions them out onto their owners' shelves. Nobody holds the old shape but us, so this is
 *       COMPAT_SHIMS.md's "we can rewrite it → migrate", with no register row.</li>
 * </ul>
 */
public final class PlayerShelf {

    private final String store;
    private final String fileName;
    private final String arrayKey;
    private final Function<JsonObject, String> keyOf;

    /** Rule 2 for the world file: a refused world read latches every write off. */
    private final WishStoreFile.Guard worldGuard = new WishStoreFile.Guard();

    /** Children whose shelf could not be read this load: never written over, never loaded. */
    private final Map<String, String> refusedOwners = new LinkedHashMap<>();

    /** Per owner: key to the record's JSON as THIS server last serialised it. See the class note. */
    private final Map<String, Map<String, String>> base = new HashMap<>();

    /**
     * Per owner: the keys the last {@link #read} actually took off that owner's SHELF. {@link
     * #remember} bases only these, because a record that came out of the WORLD file (a migration)
     * is not on the shelf yet — basing it would make it look untouched-here, the merge would take
     * the disk's answer for it, and the disk's answer is "absent": the migration would delete the
     * very record it was moving. {@code PlayerShelfTest.migratesAnOldWorld} is that, seen red.
     */
    private final Map<String, Set<String>> readFromShelf = new HashMap<>();

    /** Per owner: the shelf file's modification time when we last read or wrote it. */
    private final Map<String, Long> seenAt = new HashMap<>();

    private boolean needsConverge;

    /** True when the last read found no WORLD file (whatever the shelves held). */
    private boolean worldAbsent = true;

    /** {@code owner:key} of each shelf record not loaded because its key was already taken. */
    private final List<String> collisions = new ArrayList<>();

    /**
     * @param store    the envelope name, e.g. {@code "species"}
     * @param fileName the file under both roots, e.g. {@code "species.json"}
     * @param arrayKey the payload key holding the records, e.g. {@code "species"}
     * @param keyOf    a record's identity within the store; a record with a blank key is kept in
     *                 the WORLD file rather than guessed at
     */
    public PlayerShelf(String store, String fileName, String arrayKey,
                       Function<JsonObject, String> keyOf) {
        this.store = store;
        this.fileName = fileName;
        this.arrayKey = arrayKey;
        this.keyOf = keyOf;
    }

    /** The key a record goes by, from a JSON field. The common case. */
    public static Function<JsonObject, String> byField(String field) {
        return o -> {
            JsonElement e = o == null ? null : o.get(field);
            try {
                return e != null && e.isJsonPrimitive()
                        ? e.getAsString().trim().toLowerCase(java.util.Locale.ROOT) : "";
            } catch (RuntimeException ex) {
                return "";
            }
        };
    }

    /**
     * An owner id that can name a directory: lower-case letters, digits, {@code -} and {@code _}.
     * A uuid always qualifies. Anything else stays in the world file rather than becoming a path.
     */
    public static boolean isShelvable(String owner) {
        if (owner == null || owner.isEmpty() || owner.length() > 64) {
            return false;
        }
        for (char c : owner.toCharArray()) {
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    // ── read ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Read the world file and every child's shelf as ONE store.
     *
     * <p>Returns {@link WishStoreFile.Read.Absent} only when neither exists anywhere — a brand-new
     * world on a machine where no child has made one of these yet. {@link WishStoreFile.Read.Refused}
     * only when the WORLD file is; a refused shelf is left out and latched (see the class note).
     * Otherwise the world root with the payload array replaced by the union: world records first,
     * then each shelf's, shelves in owner order so two servers load the same set in the same order.
     * The version reported is the OLDEST file's, so a store's own "is this older than my schema"
     * check still converges a stale file wherever it sits.
     *
     * <p>A shelf record is stamped with the shelf's owner whatever it says itself: the folder is
     * the authority, and a record hand-copied between shelves must not keep claiming its old owner.
     *
     * @param worldDir    {@code <world>/wishes}; null when no world is open
     * @param playersRoot the shelves' root; null or absent means there are none
     * @param schema      the newest version this build reads
     */
    public synchronized WishStoreFile.Read read(Path worldDir, Path playersRoot, int schema) {
        worldGuard.reset();
        refusedOwners.clear();
        collisions.clear();
        readFromShelf.clear();
        base.clear();
        seenAt.clear();
        needsConverge = false;
        worldAbsent = true;

        WishStoreFile.Read world = worldGuard.observe(
                WishStoreFile.read(textOf(worldDir == null ? null : worldDir.resolve(fileName)),
                        store, schema));
        if (world instanceof WishStoreFile.Read.Refused) {
            return world;
        }
        JsonObject root = rootOf(world);
        boolean any = !(world instanceof WishStoreFile.Read.Absent);
        worldAbsent = !any;
        int oldest = world instanceof WishStoreFile.Read.Ok ok ? ok.version()
                : world instanceof WishStoreFile.Read.Legacy ? WishStoreFile.LEGACY_VERSION
                : schema;

        JsonArray merged = new JsonArray();
        Set<String> seenKeys = new java.util.HashSet<>();
        JsonArray worldRecords = arrayOf(root);
        for (JsonElement el : worldRecords) {
            merged.add(el);
            if (el.isJsonObject()) {
                String k = keyOf.apply(el.getAsJsonObject());
                if (!k.isEmpty()) {
                    seenKeys.add(k);
                }
                if (isShelvable(ArtifactOwner.of(el.getAsJsonObject())) && !k.isEmpty()) {
                    needsConverge = true;   // an owned record still in the world: pre-T9.1b
                }
            }
        }

        for (String owner : owners(playersRoot)) {
            Path f = playersRoot.resolve(owner).resolve(fileName);
            String text = textOf(f);
            WishStoreFile.Read shelf = WishStoreFile.read(text, store, schema);
            if (shelf instanceof WishStoreFile.Read.Absent) {
                continue;
            }
            if (shelf instanceof WishStoreFile.Read.Refused r) {
                refusedOwners.put(owner, r.reason());
                continue;
            }
            any = true;
            seenAt.put(owner, mtime(f));
            int v = shelf instanceof WishStoreFile.Read.Ok ok ? ok.version()
                    : WishStoreFile.LEGACY_VERSION;
            oldest = Math.min(oldest, v);
            for (JsonElement el : arrayOf(rootOf(shelf))) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject().deepCopy();
                String k = keyOf.apply(o);
                if (!k.isEmpty() && !seenKeys.add(k)) {
                    // ⚠ A key already loaded — from the world, or an earlier shelf. Two servers
                    // can mint one id for two children before either sees the other's. The FIRST
                    // wins in memory and the loser stays untouched ON DISK: it is in neither this
                    // server's base nor its current set, so the merge keeps it. Letting the later
                    // one win instead would drop a SHARED world record at the next world write.
                    collisions.add(owner + ":" + k);
                    continue;
                }
                o.addProperty(ArtifactOwner.FIELD, owner);
                merged.add(o);
                readFromShelf.computeIfAbsent(owner, x -> new java.util.HashSet<>()).add(k);
            }
        }

        if (!any) {
            return new WishStoreFile.Read.Absent();
        }
        JsonObject out = new JsonObject();
        for (var e : root.entrySet()) {
            if (!e.getKey().equals(arrayKey) && !e.getKey().equals("store")
                    && !e.getKey().equals("v")) {
                out.add(e.getKey(), e.getValue());
            }
        }
        out.add(arrayKey, merged);
        return oldest == WishStoreFile.LEGACY_VERSION
                ? new WishStoreFile.Read.Legacy(out)
                : new WishStoreFile.Read.Ok(oldest, out);
    }

    /** True when the last read found owned records still in the world file (pre-T9.1b). */
    public synchronized boolean needsConverge() {
        return needsConverge;
    }

    /**
     * True when the last read found no world file. A store with a pre-envelope migration keys it on
     * this rather than on {@link WishStoreFile.Read.Absent}: a world opened for the first time since
     * A store can have an old file to migrate AND a child whose shelf is already full from another
     * world, and the merged read of that is not Absent.
     */
    public synchronized boolean worldWasAbsent() {
        return worldAbsent;
    }

    /** False when the WORLD file was refused — the whole store is latched, as it always was. */
    public synchronized boolean mayWrite() {
        return worldGuard.mayWrite();
    }

    /** Why the world file was refused, or null. */
    public synchronized String refusalReason() {
        return worldGuard.refusalReason();
    }

    /** Shelf records the last read skipped because their key was taken, as {@code owner:key}. */
    public synchronized List<String> collisions() {
        return new ArrayList<>(collisions);
    }

    /** The children whose shelves were refused this load, with why — for a log line and a test. */
    public synchronized Map<String, String> refusedShelves() {
        return new LinkedHashMap<>(refusedOwners);
    }

    /**
     * Take this server's own serialisation as the merge base — called by a store straight after it
     * has adopted a {@link #read}. See the class note for why the base cannot be the disk's bytes.
     *
     * @param payload the store's payload as it would write it now
     * @param ownerOf whose each record is; {@code ArtifactOwner::of} unless the payload carries no
     *                owner field of its own (the item icons, whose owner is their design's)
     */
    public synchronized void remember(JsonObject payload, Function<JsonObject, String> ownerOf) {
        base.clear();
        partition(payload, ownerOf).shelves.forEach((owner, recs) -> {
            Set<String> onShelf = readFromShelf.getOrDefault(owner, Set.of());
            base.put(owner, serialise(recs.stream()
                    .filter(o -> onShelf.contains(keyOf.apply(o))).toList()));
        });
    }

    /**
     * Has another process written this child's shelf since we last read or wrote it? A store asks
     * when the child logs in, and reloads if so — the merge keeps the DISK right without this, and
     * this is what keeps the child's VIEW right.
     */
    public synchronized boolean changedOnDisk(Path playersRoot, String owner) {
        String who = ArtifactOwner.normalise(owner);
        if (playersRoot == null || !isShelvable(who)) {
            return false;
        }
        Path f = playersRoot.resolve(who).resolve(fileName);
        long now = mtime(f);
        Long was = seenAt.get(who);
        return was == null ? now != 0L : now != was;
    }

    // ── write ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Write the store: shared records to the world file, each child's to his shelf by a locked
     * three-way merge.
     *
     * <p>Shelves are written FIRST. A migrating record leaves the world file only in the world
     * write, so a crash in between leaves it in both places — and a record in both is read twice and
     * collapses to one in memory (same key, the shelf's wins, since shelves load after the world).
     * The other order would leave it in neither.
     *
     * @return false when nothing was written because the WORLD file is latched; a latched or failed
     *         SHELF is skipped and logged through {@code warn}, and the rest still write
     */
    public synchronized boolean write(Path worldDir, Path playersRoot, int schema,
                                      JsonObject payload, Function<JsonObject, String> ownerOf,
                                      java.util.function.Consumer<String> warn) {
        if (!worldGuard.mayWrite()) {
            return false;
        }
        Split split = partition(payload, ownerOf);
        Set<String> owners = new TreeSet<>(split.shelves.keySet());
        owners.addAll(base.keySet());   // an owner whose last record we deleted still needs a write
        if (playersRoot != null) {
            for (String owner : owners) {
                if (refusedOwners.containsKey(owner)) {
                    warn.accept("NOT saving " + store + " for " + owner + " -- their shelf could not"
                            + " be read (" + refusedOwners.get(owner) + "); it has been left alone");
                    continue;
                }
                List<JsonObject> current = split.shelves.getOrDefault(owner, List.of());
                try {
                    writeShelf(playersRoot.resolve(owner), schema, current,
                            base.getOrDefault(owner, Map.of()));
                    base.put(owner, serialise(current));
                } catch (IOException | RuntimeException e) {
                    warn.accept("could not save " + store + " for " + owner + ": " + e);
                }
            }
        } else {
            // No shelf root: everything stays in the world, which is what this store did before.
            split.world.addAll(split.shelves.values().stream().flatMap(List::stream).toList());
        }
        if (worldDir != null) {
            JsonObject out = new JsonObject();
            for (var e : payload.entrySet()) {
                if (!e.getKey().equals(arrayKey)) {
                    out.add(e.getKey(), e.getValue());
                }
            }
            JsonArray arr = new JsonArray();
            split.world.forEach(arr::add);
            out.add(arrayKey, arr);
            try {
                writeAtomically(worldDir.resolve(fileName),
                        WishStoreFile.stamp(store, schema, out).toString());
            } catch (IOException | RuntimeException e) {
                warn.accept("could not save " + store + " to the world: " + e);
            }
        }
        needsConverge = false;
        return true;
    }

    private void writeShelf(Path dir, int schema, List<JsonObject> current,
                            Map<String, String> ownBase) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve(fileName);
        // ⚠ One lock file PER STORE. FileChannel.lock is per JVM as well as per process, and two
        // stores in this JVM saving one child's shelf at once would throw
        // OverlappingFileLockException on a shared lock file. A store's own save is synchronized,
        // so its lock is never contended from inside this process.
        Path lockFile = dir.resolve("." + store + ".lock");
        try (FileChannel ch = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = ch.lock()) {
            String text = textOf(f);
            WishStoreFile.Read disk = WishStoreFile.read(text, store, schema);
            if (disk instanceof WishStoreFile.Read.Refused r) {
                // Written by a newer Moddy, or damaged, SINCE we read it. Same rule as at load.
                refusedOwners.put(dir.getFileName().toString(), r.reason());
                throw new IOException("the shelf became unreadable (" + r.reason() + ")");
            }
            Map<String, JsonObject> fresh = new LinkedHashMap<>();
            for (JsonElement el : arrayOf(rootOf(disk))) {
                if (el.isJsonObject()) {
                    String k = keyOf.apply(el.getAsJsonObject());
                    if (!k.isEmpty()) {
                        fresh.put(k, el.getAsJsonObject());
                    }
                }
            }
            JsonArray merged = merge(current, ownBase, fresh, keyOf);
            JsonObject payload = new JsonObject();
            payload.add(arrayKey, merged);
            writeAtomically(f, WishStoreFile.stamp(store, schema, payload).toString());
            seenAt.put(dir.getFileName().toString(), mtime(f));
        }
    }

    /**
     * The three-way merge, by key. Package-visible and pure, because it is the part a mistake
     * in loses a child's things silently — {@code PlayerShelfTest} drives every cell.
     *
     * <p>Order: ours first, in memory order (a store's "newest last" is memory order), then
     * records only the disk has, in disk order.
     */
    static JsonArray merge(List<JsonObject> current, Map<String, String> base,
                           Map<String, JsonObject> fresh, Function<JsonObject, String> keyOf) {
        JsonArray out = new JsonArray();
        Set<String> ours = new java.util.HashSet<>();
        for (JsonObject c : current) {
            String k = keyOf.apply(c);
            ours.add(k);
            String was = base.get(k);
            if (was != null && was.equals(c.toString())) {
                // Untouched here: the disk is the newer truth, including "the other server removed it".
                JsonObject theirs = fresh.get(k);
                if (theirs != null) {
                    out.add(theirs);
                }
            } else {
                out.add(c);
            }
        }
        for (var e : fresh.entrySet()) {
            if (ours.contains(e.getKey())) {
                continue;
            }
            if (base.containsKey(e.getKey())) {
                continue;   // we had it and no longer do: we deleted it
            }
            out.add(e.getValue());   // added by the other server
        }
        return out;
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private record Split(List<JsonObject> world, Map<String, List<JsonObject>> shelves) {}

    private Split partition(JsonObject payload, Function<JsonObject, String> ownerOf) {
        List<JsonObject> world = new ArrayList<>();
        Map<String, List<JsonObject>> shelves = new LinkedHashMap<>();
        for (JsonElement el : arrayOf(payload)) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String owner = ArtifactOwner.normalise(ownerOf.apply(o));
            if (isShelvable(owner) && !keyOf.apply(o).isEmpty()) {
                shelves.computeIfAbsent(owner, k -> new ArrayList<>()).add(o);
            } else {
                world.add(o);
            }
        }
        return new Split(world, shelves);
    }

    private Map<String, String> serialise(List<JsonObject> recs) {
        Map<String, String> out = new HashMap<>();
        for (JsonObject o : recs) {
            out.put(keyOf.apply(o), o.toString());
        }
        return out;
    }

    private JsonArray arrayOf(JsonObject root) {
        JsonElement arr = root == null ? null : root.get(arrayKey);
        return arr != null && arr.isJsonArray() ? arr.getAsJsonArray() : new JsonArray();
    }

    private static JsonObject rootOf(WishStoreFile.Read read) {
        if (read instanceof WishStoreFile.Read.Ok ok) {
            return ok.root();
        }
        if (read instanceof WishStoreFile.Read.Legacy l) {
            return l.root();
        }
        return new JsonObject();
    }

    /** The children with a shelf directory, sorted, so every server loads them in one order. */
    private static List<String> owners(Path playersRoot) {
        if (playersRoot == null || !Files.isDirectory(playersRoot)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(playersRoot)) {
            return s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(PlayerShelf::isShelvable)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String textOf(Path f) {
        try {
            return f != null && Files.isRegularFile(f)
                    ? Files.readString(f, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            // An unreadable file is not an absent one. Report it as text that will not parse, so
            // it lands in Refused and is latched, rather than in Absent and overwritten.
            return "\u0000unreadable: " + e;
        }
    }

    private static long mtime(Path f) {
        try {
            return Files.isRegularFile(f) ? Files.getLastModifiedTime(f).toMillis() : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void writeAtomically(Path f, String text) throws IOException {
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp-" + System.nanoTime());
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
