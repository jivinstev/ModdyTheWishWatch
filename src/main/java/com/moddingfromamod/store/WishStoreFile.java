package com.moddingfromamod.store;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>One envelope, one refusal, for every {@code <world>/wishes/*.json} store</b> — the fix
 * for the defect class behind.
 *
 * <h2>Why this exists, measured rather than assumed</h2>
 *
 * Ten stores live under {@code <world>/wishes/}. Every one of them <b>whole-file overwrites</b>, and
 * most of them {@code clear()} their in-memory map at the top of {@code load()} and then
 * {@code return} silently when the file does not parse or does not carry the key they expect. Those
 * two facts compose into permanent data loss with nothing reported:
 *
 * <pre>
 *   load()  → cannot read it → map is CLEARED and the method returns quietly
 *   put()   → save()         → the whole file is rewritten from the empty map
 * </pre>
 *
 * <p> is the case where that already happens on a shipped path: {@code ItemSpecStore} writes
 * {@code {"specs":[…]}} and {@code WishItemCatalogue} writes {@code {"items":[…]}} — <b>into the same
 * file</b>. Whichever saves last wins, the loser's {@code load()} finds no key it recognises, returns
 * empty, and the child's items are gone at the next unrelated write.
 *
 * <h2>The three rules</h2>
 *
 * <ol>
 *   <li><b>An unreadable store is a REFUSAL, never an empty one.</b> The whole defect is that "I
 *       could not read this" and "this is empty" are the same value today. {@link Read.Refused}
 *       makes them different types, so a caller cannot accidentally treat one as the other.</li>
 *   <li><b>A refused store must not be WRITTEN.</b> Refusing to read is useless if the next save
 *       overwrites the bytes anyway — that is the mechanism above. {@link Guard} carries the latch.</li>
 *   <li><b>Absent version means LEGACY, and legacy is accepted.</b> Every file on disk today is
 *       unversioned, so a rule that treated a missing stamp as an error would fire on all of them at
 *       once. This is the same load-bearing choice per-child ownership made for {@code ArtifactOwner}, where absent
 *       ownership means SHARED.</li>
 * </ol>
 *
 * <h2>⚠ The envelope is INLINE, and that is a rollback guarantee</h2>
 *
 * The stamp is added <i>beside</i> the payload's own keys rather than nesting the payload under a
 * {@code "data"} object:
 *
 * <pre>
 *   {"store":"item-specs","v":1,"specs":[ … ]}
 * </pre>
 *
 * <p>So a jar built <i>before</i> this framework still reads a file written <i>after</i> it — its
 * {@code root.has("specs")} is still true, and the two extra keys are ignored. Nesting would have
 * been tidier and would have made every downgrade a silent empty store, which is the exact failure
 * this class exists to remove. A world save must survive the jar going backwards as well as forwards.
 */
public final class WishStoreFile {

    /** What an unversioned (pre-framework) file reports as. */
    public static final int LEGACY_VERSION = 0;

    private static final String KEY_STORE = "store";
    private static final String KEY_VERSION = "v";

    private WishStoreFile() {}

    /** The outcome of reading a store file. Four cases, and none of them is "empty". */
    public sealed interface Read {

        /** No file yet. A new world, and genuinely empty — the only case where empty is correct. */
        record Absent() implements Read {}

        /** A file this build understands. {@code root} carries the payload keys inline. */
        record Ok(int version, JsonObject root) implements Read {}

        /**
         * An unversioned file, written before this framework. Accepted, and the caller is expected
         * to stamp it on its next write.
         */
        record Legacy(JsonObject root) implements Read {}

        /**
         * ⚠ <b>Do not write over this file.</b> Unparseable, the wrong store's data, or written by a
         * newer build. {@code reason} is written for a grown-up reading a log, not for a machine.
         */
        record Refused(String reason) implements Read {}
    }

    /**
     * Read a store file's text.
     *
     * @param text             the file's contents, or null when the file does not exist
     * @param store            the name this store expects to own, e.g. {@code "item-specs"}
     * @param newestUnderstood the highest schema version this build can read
     */
    public static Read read(String text, String store, int newestUnderstood) {
        if (text == null || text.isBlank()) {
            return new Read.Absent();
        }
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed == null || !parsed.isJsonObject()) {
                return new Read.Refused("not a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            return new Read.Refused("could not be parsed as JSON: " + e.getMessage());
        }

        // ⚠ The store name is checked BEFORE the version, because a file carrying another store's
        // name is itself — and reporting it as a version problem would send the next reader
        // hunting through migrations for a collision.
        if (root.has(KEY_STORE)) {
            String found = asString(root, KEY_STORE);
            if (found == null) {
                return new Read.Refused("'" + KEY_STORE + "' is not a string");
            }
            if (!found.equals(store)) {
                return new Read.Refused(
                        "belongs to the '" + found + "' store, but was read as '" + store + "'");
            }
        }

        if (!root.has(KEY_VERSION)) {
            // Rule 3. Every file written before this framework lands here.
            return new Read.Legacy(root);
        }
        Integer v = asInt(root, KEY_VERSION);
        if (v == null || v < 0) {
            return new Read.Refused("'" + KEY_VERSION + "' is not a whole number");
        }
        if (v > newestUnderstood) {
            return new Read.Refused("was written by a newer Moddy (schema v" + v
                    + "; this build understands up to v" + newestUnderstood + ")");
        }
        return new Read.Ok(v, root);
    }

    /**
     * Stamp a payload for writing. The payload's own keys are kept; {@code store} and {@code v} are
     * added beside them (see the rollback note on the class).
     *
     * @throws IllegalArgumentException if the payload already uses a reserved key, which would mean
     *     a store had invented its own {@code v} and the two would silently disagree
     */
    public static JsonObject stamp(String store, int version, JsonObject payload) {
        if (payload.has(KEY_STORE) || payload.has(KEY_VERSION)) {
            throw new IllegalArgumentException(
                    "store '" + store + "' already writes a reserved key ('" + KEY_STORE + "' or '"
                            + KEY_VERSION + "'); rename it rather than letting two stamps disagree");
        }
        JsonObject out = new JsonObject();
        out.addProperty(KEY_STORE, store);
        out.addProperty(KEY_VERSION, version);
        for (var e : payload.entrySet()) {
            out.add(e.getKey(), e.getValue());
        }
        return out;
    }

    // ── the file half, so a store does not hand-roll its own I/O around the envelope ─────────────

    /**
     * {@link #read} on a file. Absent when the file is not there; a file that EXISTS and cannot be
     * read (permissions, an I/O error) is a {@link Read.Refused}, never an absent one — the whole
     * defect this class exists for is "could not read" and "empty" being the same answer.
     */
    public static Read readFile(java.nio.file.Path f, String store, int newestUnderstood) {
        if (f == null || !java.nio.file.Files.isRegularFile(f)) {
            return new Read.Absent();
        }
        try {
            return read(java.nio.file.Files.readString(f, java.nio.charset.StandardCharsets.UTF_8),
                    store, newestUnderstood);
        } catch (java.io.IOException | RuntimeException e) {
            return new Read.Refused("could not be read: " + e);
        }
    }

    /** The payload root of an {@link Read.Ok} or a {@link Read.Legacy}; null for the other two. */
    public static JsonObject rootOf(Read read) {
        if (read instanceof Read.Ok ok) {
            return ok.root();
        }
        if (read instanceof Read.Legacy legacy) {
            return legacy.root();
        }
        return null;
    }

    /**
     * {@link #stamp} the payload and write it atomically (a temp file, then a move), so a crash
     * mid-write leaves the old file whole rather than half a new one.
     */
    public static void writeFile(java.nio.file.Path f, String store, int version, JsonObject payload)
            throws java.io.IOException {
        java.nio.file.Files.createDirectories(f.getParent());
        java.nio.file.Path tmp = f.resolveSibling(f.getFileName() + ".tmp-" + System.nanoTime());
        try {
            java.nio.file.Files.writeString(tmp, stamp(store, version, payload).toString(),
                    java.nio.charset.StandardCharsets.UTF_8);
            try {
                java.nio.file.Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }

    /**
     * <b>The write check for a store that carries its OWN version field</b> — {@code mobs},
     * {@code minted}, {@code handover/*}, {@code memory/*}.
     *
     * <p>Those stores deliberately READ any version, a newer one included, best-effort: an older
     * build showing a child's mob names is better than showing none. That choice is kept. What it
     * never covered is the WRITE: the older build then saves its narrower view over the newer file,
     * and every field it did not understand is gone — an earlier issue's mechanism by another road. So the file
     * on disk is checked before every write, and a file that exists and cannot be parsed, or that
     * says it was written by a newer schema, is left alone.
     *
     * @return why the write must not happen, or null when it may (including "no file yet")
     */
    public static String writeBlocker(java.nio.file.Path f, String versionKey, int newestUnderstood) {
        if (f == null || !java.nio.file.Files.exists(f)) {
            return null;
        }
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(
                    java.nio.file.Files.readString(f, java.nio.charset.StandardCharsets.UTF_8));
            if (parsed == null || !parsed.isJsonObject()) {
                return f.getFileName() + " is not a JSON object";
            }
            root = parsed.getAsJsonObject();
        } catch (java.io.IOException | RuntimeException e) {
            return f.getFileName() + " could not be read: " + e.getMessage();
        }
        if (!root.has(versionKey)) {
            return null;   // written before the field existed: ours to rewrite
        }
        Integer v = asInt(root, versionKey);
        if (v == null) {
            return f.getFileName() + "'s '" + versionKey + "' is not a whole number";
        }
        if (v > newestUnderstood) {
            return f.getFileName() + " was written by a newer Moddy (v" + v
                    + "; this build understands up to v" + newestUnderstood + ")";
        }
        return null;
    }

    /**
     * Rule 2, as a latch a store holds: once a read is refused, {@link #mayWrite()} stays false for
     * the life of the store, so no later {@code save()} can overwrite bytes nobody could read.
     *
     * <p>⚠ <b>The latch is cleared by {@link #reset()} and by nothing else</b> — in particular a
     * later successful {@code observe} does not clear it, because within one load cycle there is
     * exactly one read and quietly re-opening on a retry is how the evidence disappears.
     *
     * <p>A store calls {@code reset()} at the top of its own {@code load()}, which makes the latch
     * <b>per world load</b> rather than per process. That distinction was found by
     * {@code GateShardsTest}: these stores are process-wide statics, so a latch that never reset
     * would mean a server that opened one damaged world could never save any <i>later</i> world
     * either — and in the gates, one test's damaged fixture would silently block every neighbour's
     * writes, which is precisely the shared-static hazard that test exists to catch.
     */
    public static final class Guard {
        private volatile boolean refused;
        private volatile String reason;

        /** Begin a fresh judgement — called at the top of a store's {@code load()}. */
        public void reset() {
            refused = false;
            reason = null;
        }

        /** Record the outcome of a read. Returns the same value, so it can wrap a call. */
        public Read observe(Read read) {
            if (read instanceof Read.Refused r) {
                refused = true;
                reason = r.reason();
            }
            return read;
        }

        /** False once anything has been refused. A {@code save()} must check this first. */
        public boolean mayWrite() {
            return !refused;
        }

        /** Why writing is blocked, or null. */
        public String refusalReason() {
            return reason;
        }
    }

    private static String asString(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()
                    ? e.getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer asInt(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
                return null;
            }
            double d = e.getAsDouble();
            return d == Math.rint(d) ? (int) d : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
