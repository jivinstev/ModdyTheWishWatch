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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.ArtifactMemory;
import com.moddingfromamod.marshal.HandoverRecord;
import com.moddingfromamod.runtime.WorldPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where an artifact's whole history lives: on disk, per world, one file per thing.
 *
 * <p>Successor to {@link HandoverStore}. Same shape — per world, atomic write, defensive load,
 * because losing one artifact's notes is a small problem and failing to open a world over them is
 * not. The difference is what is stored: an append-only journal of turns rather than five fields
 * that get truncated into each other.
 *
 * <h2>Migration is not optional</h2>
 * Steve has real records in a real save, one of them eight revisions deep. Shipping a new store that
 * simply ignores them would silently reset the memory of everything he has built — the child-visible
 * symptom being that Moddy suddenly re-asks things it already knew. So an old {@code handover/}
 * record is read once and folded in as a <b>single synthetic turn 1</b>, which is honest about what
 * it is: the old format cannot be un-merged back into the turns it was made from, and pretending
 * otherwise would invent a history.
 *
 * <p><b>Concurrency.</b> Appends are keyed by {@code wishId} and merged onto whatever is on disk at
 * the time, so two workers refining one artifact both survive and neither can lose the other's turn.
 * That answers an earlier issue's fourth question without a lease: an append-only log needs no exclusive access,
 * only an idempotent key.
 */
public final class ArtifactMemoryStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** Bumped only if the shape changes incompatibly; an unknown version loads as best it can. */
    private static final int VERSION = 2;

    private static final Map<String, ArtifactMemory> CACHE = new ConcurrentHashMap<>();

    private ArtifactMemoryStore() {
    }

    private static Path dir() {
        Path wishes = WorldPaths.wishesDir();
        return wishes == null ? null : wishes.resolve("memory");
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
     * Record one wish's worth of work, merged onto whatever is already known.
     *
     * @return the memory as it now stands, or null if there was nothing to record
     */
    public static synchronized ArtifactMemory append(String artifactId, String kind,
                                                     ArtifactMemory.Turn turn, String whatItIs,
                                                     String where, List<String> state,
                                                     List<String> aliases) {
        String k = key(artifactId);
        if (k.isEmpty()) {
            return null;
        }
        ArtifactMemory existing = read(k);
        ArtifactMemory base = existing != null ? existing
                : ArtifactMemory.of(k, kind == null ? "" : kind);
        ArtifactMemory merged = base.plus(turn, whatItIs, where, state, aliases);
        CACHE.put(k, merged);
        save(k, merged);
        return merged;
    }

    /** What is known about this thing, or null. Migrates an old handover record on first read. */
    public static ArtifactMemory read(String artifactId) {
        String k = key(artifactId);
        if (k.isEmpty()) {
            return null;
        }
        ArtifactMemory cached = CACHE.get(k);
        if (cached != null) {
            return cached;
        }
        ArtifactMemory loaded = readFile(k, true);
        if (loaded != null) {
            CACHE.put(k, loaded);
            return loaded;
        }
        ArtifactMemory migrated = migrate(k);
        if (migrated != null) {
            CACHE.put(k, migrated);
            save(k, migrated);
        }
        return migrated;
    }

    /**
     * <b>What is on DISK for this id, ignoring the in-memory mirror entirely.</b>
     *
     * <p>The seam a persistence test needs. "Would this journal survive a reload" used to be asked
     * with {@code clear()} then {@link #read}, which empties the mirror for <em>every</em> test
     * running concurrently in the same batch — invisible at a handful, fatal at fifty (see
     * docs/GATE_PERFORMANCE.md). This reads the file and touches no shared state, so the same
     * question is answered without a test being able to break its neighbours.
     *
     * <p>Deliberately does NOT migrate: a migration writes, and a read-only probe must not.
     */
    public static ArtifactMemory savedOnDisk(String artifactId) {
        String k = key(artifactId);
        return k.isEmpty() ? null : readFile(k, false);
    }

    private static ArtifactMemory readFile(String k, boolean migrate) {
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
            LOGGER.warn("skipping damaged memory '{}': {}", k, e.toString());
            return null;
        }
    }

    /**
     * Fold a pre-turn-journal record in as one synthetic turn.
     *
     * <p>Deliberately ONE turn and not {@code revision} of them. The old format merged every turn's
     * reasoning into single fields and then truncated the tail, so the turns it was made from are
     * genuinely unrecoverable — splitting the survivors on their " · " separator would produce a
     * history that looks complete and is not, which is the exact failure being corrected.
     */
    static ArtifactMemory migrate(String artifactId) {
        HandoverRecord old = HandoverStore.read(artifactId);
        if (old == null) {
            return null;
        }
        List<String> rejected = new ArrayList<>();
        for (String part : old.triedAndRejected().split(" · ")) {
            if (!part.isBlank()) {
                rejected.add(part.strip());
            }
        }
        var turn = new ArtifactMemory.Turn(
                "migrated:" + artifactId, old.writtenMs(), "",
                "", "",
                "(carried over from " + old.revision() + " earlier edit(s), before turn-by-turn "
                        + "notes existed — the detail of the older ones was not kept)",
                old.decisionsAndWhy(), rejected, "", "", old.beCarefulOf());
        return ArtifactMemory.of(artifactId, old.kind())
                .describedAs(old.whatItIs())
                .plus(turn, old.whatItIs(), old.where(),
                        old.beCarefulOf().isEmpty() ? List.of() : List.of(old.beCarefulOf()),
                        old.aliases())
                // MINUS ONE: the synthetic turn already stands for one of them, so counting all
                // `revision` on top would tell the agent it is one edit further along than it is.
                .carryingOver(Math.max(0, old.revision() - 1));
    }

    /** Everything remembered, for diagnostics and for the gate. */
    public static List<ArtifactMemory> all() {
        return new ArrayList<>(CACHE.values());
    }

    /** Between worlds: one world's notes must never be handed to another world's agent. */
    public static void clear() {
        CACHE.clear();
    }

    private static void save(String key, ArtifactMemory m) {
        Path dir = dir();
        if (dir == null) {
            return;   // no world open (a unit test); the cache still works
        }
        String blocked = com.moddingfromamod.store.WishStoreFile.writeBlocker(
                dir.resolve(fileName(key)), "version", VERSION);
        if (blocked != null) {
            // A newer or damaged note is left alone — see WishStoreFile.writeBlocker.
            LOGGER.error("NOT saving memory '{}' — {}. The file has been left alone on purpose.",
                    key, blocked);
            return;
        }
        try {
            Files.createDirectories(dir);
            Path dest = dir.resolve(fileName(key));
            Path tmp = dir.resolve("." + fileName(key) + ".tmp");
            Files.writeString(tmp, toJson(m).toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Never fail a wish over a note. The child got his thing; the next agent has less to
            // go on.
            LOGGER.warn("could not save memory '{}': {}", key, e.toString());
        }
    }

    static JsonObject toJson(ArtifactMemory m) {
        JsonObject o = new JsonObject();
        o.addProperty("version", VERSION);
        o.addProperty("artifactId", m.artifactId());
        o.addProperty("kind", m.kind());
        o.addProperty("whatItIs", m.whatItIs());
        o.addProperty("where", m.where());
        o.addProperty("carriedOverRevisions", m.carriedOverRevisions());
        o.add("aliases", arr(m.aliases()));
        o.add("state", arr(m.state()));
        JsonArray turns = new JsonArray();
        for (ArtifactMemory.Turn t : m.turns()) {
            JsonObject j = new JsonObject();
            j.addProperty("wishId", t.wishId());
            j.addProperty("atMs", t.atMs());
            j.addProperty("workerId", t.workerId());
            j.addProperty("wish", t.wish());
            j.addProperty("intent", t.intent());
            j.addProperty("did", t.did());
            j.addProperty("why", t.why());
            j.add("rejected", arr(t.rejected()));
            j.addProperty("saidToChild", t.saidToChild());
            j.addProperty("verdict", t.verdict());
            j.addProperty("careful", t.careful());
            turns.add(j);
        }
        o.add("turns", turns);
        return o;
    }

    static ArtifactMemory fromJson(JsonObject o) {
        if (o == null) {
            return null;
        }
        try {
            String id = str(o, "artifactId");
            if (id.isBlank()) {
                // A record with no id can never be looked up again, and would sit in the cache
                // under "" shadowing real lookups. Same rule as the store it replaces.
                return null;
            }
            List<ArtifactMemory.Turn> turns = new ArrayList<>();
            if (o.has("turns") && o.get("turns").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("turns")) {
                    if (!e.isJsonObject()) {
                        continue;
                    }
                    JsonObject j = e.getAsJsonObject();
                    // ONE damaged turn must not cost the whole artifact its history.
                    try {
                        turns.add(new ArtifactMemory.Turn(
                                str(j, "wishId"), lng(j, "atMs"), str(j, "workerId"),
                                str(j, "wish"), str(j, "intent"), str(j, "did"), str(j, "why"),
                                list(j, "rejected"), str(j, "saidToChild"), str(j, "verdict"),
                                str(j, "careful")));
                    } catch (RuntimeException damaged) {
                        LOGGER.warn("skipping a damaged turn in '{}': {}", id, damaged.toString());
                    }
                }
            }
            return new ArtifactMemory(id, str(o, "kind"), str(o, "whatItIs"), list(o, "aliases"),
                    str(o, "where"), list(o, "state"), turns,
                    (int) lng(o, "carriedOverRevisions"));
        } catch (RuntimeException e) {
            return null;   // one damaged record must never stop a world opening
        }
    }

    private static JsonArray arr(List<String> xs) {
        JsonArray a = new JsonArray();
        xs.forEach(a::add);
        return a;
    }

    private static List<String> list(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        if (o.has(k) && o.get(k).isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray(k)) {
                if (e.isJsonPrimitive()) {
                    out.add(e.getAsString());
                }
            }
        }
        return out;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static long lng(JsonObject o, String k) {
        try {
            return o.has(k) ? o.get(k).getAsLong() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
