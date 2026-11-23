package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import com.google.gson.JsonObject;

/**
 * <b>Whose thing is this</b> — the owner stamp, the fork key, and the order a child's own things
 * come in.
 *
 * <p>Minecraft-free on purpose: every rule here is a string rule, so the naming, the ranking and
 * the "absent means shared" reading are unit tests rather than something only a two-child server
 * can show.
 *
 * <h2>Three scopes, and ABSENT is one of them</h2>
 *
 * <p>The plan's §9 table names library, world-shared and per-child. In a record that is one field,
 * {@value #FIELD}, and the interesting value is the one that is not there: <b>a record written
 * before owner stamps carries no owner, and that reads as SHARED</b> — not as "owned by nobody", not as an
 * error. Every world on disk today is in that state, so any rule that treated a missing stamp as a
 * problem would be a rule that fires on every existing artifact.
 *
 * <p>It is a <b>UUID string, never a player name</b>. Names are re-used, re-typed and changed; the
 * journal is the one file that is allowed to hold a name (deliberately the only one), and these files are mined into committed corpora.
 *
 * <h2>The fork key, and why {@code ~} had to be let into two alphabets</h2>
 *
 * <p>A child's fork of a shared thing is {@code <base>~<8 hex of his uuid>} — the shape the plan
 * writes and the shape an operator types at {@code /moddyop promote spikor~1f2e3d4c}. The suffix is
 * the short uuid rather than his NAME for the reason above, and it is a suffix rather than a
 * directory so that one string is still one artifact key everywhere it travels: a species id, a
 * model directory, a library path, an argument to {@code promote.py}.
 *
 * <p>⚠ That last part cost a real decision. {@code ModelRepository.isValidKey} and
 * {@code Species}'s own {@code sanitizeKey} both allowed {@code [a-z0-9_-]} only — and
 * {@code sanitizeKey} does not REFUSE an illegal character, it <b>drops</b> it. So a fork key
 * built with {@code ~} would have arrived as {@code spikor1f2e3d4c}: still a valid key, still
 * unique, and silently not the key anything else in the system believes in. Both alphabets now
 * carry {@code ~} (and so does {@code promote.py}'s {@code servable}), which keeps ONE fork
 * separator rather than one for ids and another for paths. {@code ~} cannot form {@code ..} or a
 * separator, so it can no more escape a directory than {@code -} can.
 */
public final class ArtifactOwner {

    /** The field name in every artifact record. One spelling, everywhere. */
    public static final String FIELD = "ownerId";

    /** What separates an artifact's base name from the child who forked it. */
    public static final char FORK = '~';

    /** How much of a uuid a fork key carries. Eight hex digits — a uuid's first group. */
    public static final int SHORT = 8;

    /** Where a thing sits relative to the child asking about it. */
    public enum Scope {
        /** He made it, or he forked it. */
        OWN,
        /** Nobody's in particular: written before owner stamps, or made by an unidentifiable caller. */
        SHARED,
        /** Another child's. */
        OTHER
    }

    /** {@code new UUID(0, xuid)} as a string starts with this (identity/PlayerKey). */
    static final String XUID_PREFIX = "00000000-0000-0000-";

    private ArtifactOwner() {
    }

    /** An owner id as it is stored: trimmed, lower-cased, {@code ""} when there isn't one. */
    public static String normalise(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The part of an owner id that goes into a key.
     *
     * <p>A uuid's first group, so {@code 1f2e3d4c-…} becomes {@code 1f2e3d4c}. Anything that is not
     * a uuid is reduced to the key alphabet and cut to the same length rather than refused — this
     * is used to NAME things, and a caller that has an odd id should still get a usable key.
     */
    public static String shortIdOf(String ownerId) {
        String id = normalise(ownerId);
        // Step 8 (D23): an XUID key is new UUID(0, xuid) — its first group is 00000000 for EVERY Xbox
        // account, so the first group would give all of them one fork suffix and their forks would
        // overwrite each other. Its last eight hex digits are the account's (the XUID's low 32 bits).
        if (id.length() == 36 && id.startsWith(XUID_PREFIX)) {
            return id.substring(28);
        }
        StringBuilder sb = new StringBuilder();
        for (char c : id.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
            if (sb.length() >= SHORT) {
                break;
            }
        }
        return sb.toString();
    }

    /**
     * This child's fork of {@code key}.
     *
     * <p><b>Forking a fork re-bases.</b> Refining another child's {@code spikor~aaaaaaaa} gives
     * {@code spikor~bbbbbbbb}, not a chain — a key is a name, and a name that grows a suffix per
     * person who touched it stops being one.
     *
     * @return {@code ""} when there is no key or no owner to attribute it to, which the caller must
     *         read as "there is no fork to make here"
     */
    public static String forkKey(String key, String ownerId) {
        String base = baseOf(key);
        String who = shortIdOf(ownerId);
        if (base.isEmpty() || who.isEmpty()) {
            return "";
        }
        return base + FORK + who;
    }

    /** Whether this key names somebody's fork. */
    public static boolean isFork(String key) {
        return key != null && key.indexOf(FORK) > 0 && !suffixOf(key).isEmpty();
    }

    /** The thing this key is a fork OF — itself, when it is not a fork. */
    public static String baseOf(String key) {
        if (key == null) {
            return "";
        }
        String k = key.trim().toLowerCase(Locale.ROOT);
        int at = k.indexOf(FORK);
        return at < 0 ? k : k.substring(0, at);
    }

    /** The short owner id a fork key carries, or {@code ""}. */
    public static String suffixOf(String key) {
        if (key == null) {
            return "";
        }
        String k = key.trim().toLowerCase(Locale.ROOT);
        int at = k.indexOf(FORK);
        return at < 0 || at + 1 >= k.length() ? "" : k.substring(at + 1);
    }

    /** Whether this key is the given child's own fork — under his key or any id he moved from (step 8). */
    public static boolean isForkOf(String key, String ownerId) {
        String suffix = suffixOf(key);
        for (String id : com.moddingfromamod.net.PlayerIdentity.idsOf(ownerId)) {
            String who = shortIdOf(id);
            if (!who.isEmpty() && who.equals(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where {@code owner} sits relative to {@code asker}.
     *
     * <p>An unknown asker sees everything as SHARED. Nothing bound means two wishes in flight, a
     * gate, or a direct call; {@code AgentContext.currentRun()} already refuses to guess in that
     * case and this inherits the refusal rather than re-deciding it — which is exactly the
     * pre-HD-7 world, and the right one for a single-child server.
     */
    public static Scope scopeOf(String asker, String owner) {
        String me = normalise(asker);
        String theirs = normalise(owner);
        if (theirs.isEmpty() || me.isEmpty()) {
            return Scope.SHARED;
        }
        return me.equals(theirs) ? Scope.OWN : Scope.OTHER;
    }

    /** OWN before SHARED before OTHER — the order {@link #preferOwn} narrows on. */
    public static int rank(String asker, String owner) {
        return switch (scopeOf(asker, owner)) {
            case OWN -> 0;
            case SHARED -> 1;
            case OTHER -> 2;
        };
    }

    /**
     * <b>His own things first, then the shared ones, then anyone's</b> — as a NARROWING, not a sort.
     *
     * <p>It returns the best rank that is actually present and nothing else. A sort would leave
     * another child's Spikor in the running one position down, which for a rung that picks "the
     * single remaining candidate" is the same as not having preferred anything. Narrowing means
     * "my Spikor" resolves to mine when I have one and falls through to his when I do not, which is
     * the sentence §9 item 3 actually writes.
     *
     * <p>An empty input, or a list where nothing has an owner, comes back unchanged — the caller
     * must never have to check whether preferring did anything.
     */
    public static <T> List<T> preferOwn(String asker, List<T> items, Function<T, String> ownerOf) {
        if (items == null || items.size() < 2) {
            return items == null ? List.of() : items;
        }
        int best = Integer.MAX_VALUE;
        for (T item : items) {
            best = Math.min(best, rank(asker, ownerOf.apply(item)));
        }
        List<T> out = new ArrayList<>();
        for (T item : items) {
            if (rank(asker, ownerOf.apply(item)) == best) {
                out.add(item);
            }
        }
        return out;
    }

    /** Whether this child may see a per-child thing: his own, or one nobody claimed. */
    public static boolean visibleTo(String viewer, String owner) {
        return scopeOf(viewer, owner) != Scope.OTHER;
    }

    // ── the JSON half: one spelling of the field, read and written in one place ──────────────

    /** Write the stamp, or leave the record byte-identical when there is no owner to write. */
    public static void stamp(JsonObject o, String ownerId) {
        String id = normalise(ownerId);
        if (o == null || id.isEmpty()) {
            return;
        }
        o.addProperty(FIELD, id);
    }

    /** The owner a record carries, or {@code ""} — which is SHARED, not an error. */
    public static String of(JsonObject o) {
        if (o == null || !o.has(FIELD) || !o.get(FIELD).isJsonPrimitive()) {
            return "";
        }
        try {
            return normalise(o.get(FIELD).getAsString());
        } catch (RuntimeException e) {
            return "";
        }
    }
}
