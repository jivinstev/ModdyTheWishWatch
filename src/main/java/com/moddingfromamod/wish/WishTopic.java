package com.moddingfromamod.wish;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.google.gson.JsonObject;
import com.moddingfromamod.store.WishStoreFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>What he talked about last</b> — a FACT about the conversation, never a decision about a wish
 *.
 *
 * <p>Written from what the agent's own tools touched ({@code McpProtocol}, the one place every
 * artifact a wish makes or edits passes through), and read in three places: the agent's
 * brief says it out loud as a fact ({@link WishFacts}), worker affinity prefers the worker that
 * touched it ({@code WishAgent.bindingOf}), and nothing else. It used to be rung 3 of the local
 * resolver ({@code WishResolver}, deleted), which TOLD the agent what the wish meant; the
 * agent now decides that itself, with this as one of its facts.
 *
 * <h2>It survives a restart now </h2>
 *
 * <p>It was an in-memory map, so after every server restart — and in every fresh world — "give it
 * wings too" reached the agent with NOTHING saying what "it" had been, and the agent either made a
 * new thing and called it an upgrade or changed the child instead. So each child's topic is kept on
 * his SHELF ({@code <players>/<uuid>/wish-topic.json}, docs/WISH_STORES.md §9), in the versioned
 * envelope ({@link WishStoreFile}), and it carries two facts:
 *
 * <ul>
 *   <li>{@code last} — the last thing a wish of his made or changed: its id, its kind, when, and the
 *       words of the wish that did it;</li>
 *   <li>{@code wished} — the words of his previous wish, whether or not it made anything (a wish
 *       that changed HIM made no artifact, and is still what "make it bigger" may follow).</li>
 * </ul>
 *
 * <p>Two servers share a shelf, so a write is a per-field merge BY TIME: a field the disk holds
 * newer than ours is kept. A file this build cannot read (a newer schema, garbage) is never written
 * over. A gate never touches the real shelves — {@code RuntimeConfig.playersDir} keeps a test run's
 * inside its own world, and Gate A's under {@code build/}; unit tests point {@link #useRoot} at a
 * temp folder.
 *
 * <p>Minecraft-free.
 */
public final class WishTopic {

    private static final Logger LOGGER = LoggerFactory.getLogger("mfam-topic");

    /** The envelope's store name and schema version. */
    public static final String STORE = "wish-topic";
    public static final int VERSION = 1;
    public static final String FILE = "wish-topic.json";

    /** His words are kept to this many characters — a follow-up needs the gist, not an essay. */
    public static final int MAX_WORDS = 160;

    /** What a wish of his last made or changed. {@code kind} and {@code words} may be "". */
    public record Last(String artifactId, String kind, long setMs, String words) {
        public Last {
            kind = kind == null ? "" : kind;
            words = clip(words);
        }
    }

    /** The words of his previous wish, and when he wished it. */
    public record Wished(String words, long atMs) {
        public Wished {
            words = clip(words);
        }
    }

    private record Topic(Last last, Wished wished) {
    }

    private static final Map<UUID, Topic> TOPICS = new ConcurrentHashMap<>();

    /** Players whose file has been read (or found absent / refused) since the last {@link #clear}. */
    private static final Set<UUID> LOADED = ConcurrentHashMap.newKeySet();

    /** Players whose file could not be read: never written over. */
    private static final Set<UUID> REFUSED = ConcurrentHashMap.newKeySet();

    private static volatile Supplier<Path> root = WishTopic::defaultRoot;

    private WishTopic() {
    }

    private static Path defaultRoot() {
        try {
            return com.moddingfromamod.runtime.RuntimeConfig.playersDir();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Where shelves live; tests point this at a temp folder. {@code null} restores the default. */
    public static void useRoot(Supplier<Path> where) {
        root = where == null ? WishTopic::defaultRoot : where;
        clear();
    }

    /** Remember what his wish just made or changed. Safe off the server thread. */
    public static void remember(UUID playerId, String artifactId) {
        remember(playerId, artifactId, "", "");
    }

    /**
     * Remember what his wish just made or changed, with its kind and the words of the wish that did
     * it. Safe off the server thread.
     */
    public static void remember(UUID playerId, String artifactId, String kind, String words) {
        if (playerId == null || artifactId == null || artifactId.isBlank()) {
            return;
        }
        Topic now = topic(playerId);
        Last last = new Last(artifactId, kind, System.currentTimeMillis(), words);
        TOPICS.put(playerId, new Topic(last, now == null ? null : now.wished()));
        save(playerId);
    }

    /**
     * Remember the words of a wish he just made — called at submit, AFTER the facts for that wish
     * were gathered, so a wish's own facts name the wish BEFORE it.
     */
    public static void wished(UUID playerId, String words) {
        if (playerId == null || words == null || words.isBlank()) {
            return;
        }
        Topic now = topic(playerId);
        TOPICS.put(playerId, new Topic(now == null ? null : now.last(),
                new Wished(words, System.currentTimeMillis())));
        save(playerId);
    }

    /** The artifact he last talked about, or null. */
    public static String lastOf(UUID playerId) {
        Last l = last(playerId);
        return l == null ? null : l.artifactId();
    }

    /** What a wish of his last made or changed, or null. */
    public static Last last(UUID playerId) {
        Topic t = topic(playerId);
        return t == null ? null : t.last();
    }

    /** His previous wish's words, or null. */
    public static Wished previousWish(UUID playerId) {
        Topic t = topic(playerId);
        return t == null ? null : t.wished();
    }

    /**
     * How long ago it was set, or {@code -1} when there is none. An AGE rather than a
     * timestamp, so a caller never has to know which clock it came from.
     */
    public static long ageMs(UUID playerId) {
        Last l = last(playerId);
        return l == null ? -1L : Math.max(0L, System.currentTimeMillis() - l.setMs());
    }

    /** Forget him — in memory and on his shelf. */
    public static void forget(UUID playerId) {
        if (playerId == null) {
            return;
        }
        TOPICS.remove(playerId);
        LOADED.add(playerId);
        Path f = fileOf(playerId);
        if (f != null && !REFUSED.contains(playerId)) {
            try {
                java.nio.file.Files.deleteIfExists(f);
            } catch (java.io.IOException e) {
                LOGGER.warn("could not forget the wish topic for {}: {}", playerId, e.toString());
            }
        }
    }

    /** Drop what is in memory (a world unload). The shelf keeps it; the next read loads it again. */
    public static void clear() {
        TOPICS.clear();
        LOADED.clear();
        REFUSED.clear();
    }

    // ── the shelf file ────────────────────────────────────────────────────────────────────────

    private static Topic topic(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        if (LOADED.add(playerId)) {
            Topic disk = read(playerId);
            if (disk != null) {
                TOPICS.merge(playerId, disk, WishTopic::newer);
            }
        }
        return TOPICS.get(playerId);
    }

    private static Path fileOf(UUID playerId) {
        Path r = root.get();
        return r == null ? null : r.resolve(playerId.toString()).resolve(FILE);
    }

    private static Topic read(UUID playerId) {
        Path f = fileOf(playerId);
        if (f == null) {
            return null;
        }
        WishStoreFile.Read got = WishStoreFile.readFile(f, STORE, VERSION);
        if (got instanceof WishStoreFile.Read.Refused refused) {
            REFUSED.add(playerId);
            LOGGER.warn("{} for {} {} — not read, and never written over", FILE, playerId,
                    refused.reason());
            return null;
        }
        return fromJson(WishStoreFile.rootOf(got));
    }

    private static synchronized void save(UUID playerId) {
        Path f = fileOf(playerId);
        if (f == null || REFUSED.contains(playerId)) {
            return;
        }
        // Two servers write one shelf: merge per field by time, never take an older one over newer.
        WishStoreFile.Read got = WishStoreFile.readFile(f, STORE, VERSION);
        if (got instanceof WishStoreFile.Read.Refused) {
            REFUSED.add(playerId);
            return;
        }
        Topic ours = TOPICS.get(playerId);
        Topic merged = newer(ours, fromJson(WishStoreFile.rootOf(got)));
        if (merged == null) {
            return;
        }
        TOPICS.put(playerId, merged);
        try {
            WishStoreFile.writeFile(f, STORE, VERSION, toJson(merged));
        } catch (java.io.IOException | RuntimeException e) {
            // Context, not a gate: a topic that did not save is a fact the next wish lacks.
            LOGGER.warn("could not save the wish topic for {}: {}", playerId, e.toString());
        }
    }

    /** Field by field, the later of the two. */
    static Topic newer(Topic a, Topic b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        Last last = a.last() == null ? b.last()
                : b.last() == null ? a.last()
                : b.last().setMs() > a.last().setMs() ? b.last() : a.last();
        Wished wished = a.wished() == null ? b.wished()
                : b.wished() == null ? a.wished()
                : b.wished().atMs() > a.wished().atMs() ? b.wished() : a.wished();
        return new Topic(last, wished);
    }

    static JsonObject toJson(Topic t) {
        JsonObject o = new JsonObject();
        if (t.last() != null) {
            JsonObject l = new JsonObject();
            l.addProperty("id", t.last().artifactId());
            l.addProperty("kind", t.last().kind());
            l.addProperty("at", t.last().setMs());
            l.addProperty("words", t.last().words());
            o.add("last", l);
        }
        if (t.wished() != null) {
            JsonObject w = new JsonObject();
            w.addProperty("words", t.wished().words());
            w.addProperty("at", t.wished().atMs());
            o.add("wished", w);
        }
        return o;
    }

    static Topic fromJson(JsonObject o) {
        if (o == null) {
            return null;
        }
        Last last = null;
        Wished wished = null;
        try {
            if (o.has("last") && o.get("last").isJsonObject()) {
                JsonObject l = o.getAsJsonObject("last");
                String id = str(l, "id");
                if (!id.isBlank()) {
                    last = new Last(id, str(l, "kind"), num(l, "at"), str(l, "words"));
                }
            }
            if (o.has("wished") && o.get("wished").isJsonObject()) {
                JsonObject w = o.getAsJsonObject("wished");
                String words = str(w, "words");
                if (!words.isBlank()) {
                    wished = new Wished(words, num(w, "at"));
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return last == null && wished == null ? null : new Topic(last, wished);
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static long num(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : 0L;
    }

    static String clip(String words) {
        if (words == null) {
            return "";
        }
        String w = words.strip().replaceAll("\\s+", " ");
        return w.length() <= MAX_WORDS ? w : w.substring(0, MAX_WORDS - 1) + "…";
    }
}
