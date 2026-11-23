package com.moddingfromamod.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.store.WishStoreFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>A standalone pack on a world with bases has a lifetime</b>. A pack from {@code author_datapack}, and a
 * scoped {@code set_rule}, belong to nothing he can see or hold — so on a world whose rules give them a lifetime
 * ({@code allow_base_scoped.params.lifetimeDays}), each is written down here with its end, which playing on his
 * own base pushes back ({@code renewByPlay}). When it ends it is taken away and he is told, in kid words; when his
 * base is reclaimed, all of his go with it. A pack ATTACHED to a thing — a {@code make_item} effect, an
 * {@code apply_effect} ability, a species pack like the pet dragon's breath — is never in here and never expires on
 * its own: it lives as long as its thing.
 *
 * <p>The decisions ({@link #due}, {@link #renewed}) are pure; the store is {@code <world>/wishes/standalone-packs.json}
 * (store {@code standalone-packs} v1), rows by {@code playerId}, so erasing a player drops his.
 */
public final class StandalonePacks {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-standalone");

    public static final String STORE = "standalone-packs";
    public static final int SCHEMA = 1;

    /** One pack or rule with a lifetime. {@code kind} is {@code datapack} or {@code rule}. */
    public record Entry(String kind, String id, String playerId, long expiresAtMs, boolean told, String scope) {
        public Entry(String kind, String id, String playerId, long expiresAtMs, boolean told) {
            this(kind, id, playerId, expiresAtMs, told, "");
        }
    }

    /** The world's lifetime, or null when its rules give standalone packs none. */
    public record Lifetime(long millis, boolean renewByPlay) {
    }

    /** This world's lifetime for standalone packs, from {@code allow_base_scoped}, or null. */
    public static Lifetime of(WorldRules rules) {
        WorldRule r = rules == null ? null : rules.first(WorldRule.Type.ALLOW_BASE_SCOPED);
        if (r == null) {
            return null;
        }
        int days = r.integer("lifetimeDays", 0);
        if (days <= 0) {
            return null;
        }
        return new Lifetime(days * 86_400_000L, !Boolean.FALSE.equals(r.params().get("renewByPlay")));
    }

    // ── pure ─────────────────────────────────────────────────────────────────────────────────────────────

    /** The entries whose lifetime is over at {@code nowMs}. */
    public static List<Entry> due(List<Entry> all, long nowMs) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            if (e.expiresAtMs() <= nowMs) {
                out.add(e);
            }
        }
        return out;
    }

    /** His entries pushed back to {@code nowMs + lifetime} (others untouched). */
    public static List<Entry> renewed(List<Entry> all, String playerId, long nowMs, Lifetime l) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            out.add(e.playerId().equals(playerId) && l.renewByPlay()
                    ? new Entry(e.kind(), e.id(), e.playerId(), Math.max(e.expiresAtMs(), nowMs + l.millis()), false,
                    e.scope()) : e);
        }
        return out;
    }

    /** What he is told when one ends. */
    public static String endWords(Entry e) {
        String name = e.id().replaceAll("[^A-Za-z0-9]+", " ").strip();
        return "Your " + name + " " + (e.kind().equals("rule") ? "rule" : "magic") + " has worn off: nobody played "
                + "on your island for a while. Wish for it again any time!";
    }

    // ── the store ────────────────────────────────────────────────────────────────────────────────────────

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static Path loadedFrom;
    private static String refused;
    /** Test seam: a clock. */
    static volatile java.util.function.LongSupplier clock = System::currentTimeMillis;

    static Path file() {
        Path w = com.moddingfromamod.runtime.WorldPaths.wishesDir();
        return w == null ? null : w.resolve(STORE + ".json");
    }

    private static String key(String kind, String id) {
        return kind + ":" + id;
    }

    static synchronized void ensureLoaded() {
        Path f = file();
        if (f == null || f.equals(loadedFrom)) {
            return;
        }
        loadedFrom = f;
        ENTRIES.clear();
        refused = null;
        WishStoreFile.Read read = WishStoreFile.readFile(f, STORE, SCHEMA);
        if (read instanceof WishStoreFile.Read.Refused r) {
            refused = r.reason();
            LOG.error("[standalone] {} {} — left untouched", f, r.reason());
            return;
        }
        JsonObject root = WishStoreFile.rootOf(read);
        if (root == null || !root.has("packs") || !root.get("packs").isJsonArray()) {
            return;
        }
        for (JsonElement el : root.getAsJsonArray("packs")) {
            if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                Entry e = new Entry(str(o, "kind"), str(o, "id"), str(o, "playerId"),
                        o.has("expiresAt") ? o.get("expiresAt").getAsLong() : 0, o.has("told") && o.get("told").getAsBoolean(),
                        str(o, "scope"));
                ENTRIES.put(key(e.kind(), e.id()), e);
            }
        }
    }

    private static synchronized void save() {
        Path f = file();
        if (f == null || refused != null) {
            return;
        }
        JsonArray arr = new JsonArray();
        for (Entry e : ENTRIES.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("kind", e.kind());
            o.addProperty("id", e.id());
            o.addProperty("playerId", e.playerId());
            o.addProperty("expiresAt", e.expiresAtMs());
            o.addProperty("told", e.told());
            if (!e.scope().isEmpty()) {
                o.addProperty("scope", e.scope());
            }
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("packs", arr);
        try {
            WishStoreFile.writeFile(f, STORE, SCHEMA, root);
        } catch (IOException e) {
            LOG.warn("[standalone] could not save {}: {}", f, e.toString());
        }
    }

    /** Every entry in the open world. */
    public static synchronized List<Entry> all() {
        ensureLoaded();
        return new ArrayList<>(ENTRIES.values());
    }

    /** A standalone pack (or scoped rule) of his was made: it lives {@code l} from now. */
    public static synchronized void register(String kind, String id, UUID owner, Lifetime l) {
        register(kind, id, owner, l, "");
    }

    /**
     * …with a scope: {@code his_island} keeps a rule firing only on his base ({@code rules.WorldRules} asks
     * {@link #scopeOf}). A world with no lifetime keeps the scope and never ends it.
     */
    public static synchronized void register(String kind, String id, UUID owner, Lifetime l, String scope) {
        if (owner == null || id == null || (l == null && (scope == null || scope.isEmpty()))) {
            return;
        }
        ensureLoaded();
        ENTRIES.put(key(kind, id), new Entry(kind, id, owner.toString(),
                l == null ? Long.MAX_VALUE : clock.getAsLong() + l.millis(), false, scope == null ? "" : scope));
        save();
    }

    /** The scope and owner a rule was set with, or null when it has none. */
    public static synchronized Entry scopeOf(String kind, String id) {
        ensureLoaded();
        Entry e = ENTRIES.get(key(kind, id));
        return e == null || e.scope().isEmpty() ? null : e;
    }

    /** It was taken away by other means (remove_datapack, set_rule remove): forget it. */
    public static synchronized void forget(String kind, String id) {
        ensureLoaded();
        if (ENTRIES.remove(key(kind, id)) != null) {
            save();
        }
    }

    /** He is playing on his own base: push his back. */
    public static synchronized void renew(UUID player, Lifetime l) {
        if (l == null || player == null) {
            return;
        }
        ensureLoaded();
        List<Entry> before = new ArrayList<>(ENTRIES.values());
        List<Entry> after = renewed(before, player.toString(), clock.getAsLong(), l);
        if (!after.equals(before)) {
            ENTRIES.clear();
            after.forEach(e -> ENTRIES.put(key(e.kind(), e.id()), e));
            save();
        }
    }

    /** What to take away now; each is removed from the store by {@link #ended}. */
    public static synchronized List<Entry> dueNow() {
        ensureLoaded();
        return due(new ArrayList<>(ENTRIES.values()), clock.getAsLong());
    }

    /** It was taken away: drop it from the store. */
    public static synchronized void ended(Entry e) {
        ensureLoaded();
        ENTRIES.remove(key(e.kind(), e.id()));
        save();
    }

    /** His base was reclaimed: every one of his, now, whatever its end. */
    public static synchronized List<Entry> of(String playerId) {
        ensureLoaded();
        List<Entry> out = new ArrayList<>();
        for (Entry e : ENTRIES.values()) {
            if (e.playerId().equals(playerId)) {
                out.add(e);
            }
        }
        return out;
    }

    /** Tests: forget the open world's store so the next read is a fresh boot's. */
    static synchronized void forgetForTest() {
        loadedFrom = null;
        ENTRIES.clear();
        refused = null;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private StandalonePacks() {
    }
}
