package com.moddingfromamod.store;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * <b>When a child's thing was made, and when it last changed</b>.
 *
 * <p>Every wish history is newest first. No shelf row carried a time, so every
 * list ordered by whatever map it came out of — a {@code ConcurrentHashMap}'s hash order for his
 * creatures. Two fields now ride on every shelf row, written where the owner stamp is written:
 * {@value #CREATED} and {@value #UPDATED}, both epoch milliseconds.
 *
 * <p>Minecraft-free on purpose: a {@link Book} is the per-store memory of those two numbers, so a
 * store's records (immutable, and constructed in dozens of places) did not have to grow two fields.
 * The store reads them off each row at load ({@link Book#read}), moves them on a change
 * ({@link Book#touch}) and writes them back at save ({@link Book#stamp}).
 *
 * <h2>Absent is "we do not know", never zero</h2>
 *
 * <p>A row from carries neither field. {@link Times#NONE} sorts after every known time
 * (an unknown age is not "the oldest", it is "not on the timeline at all") and is written as
 * nothing, so an old row stays byte-identical until something actually learns its time —
 * the backfill ({@link TimesBackfill}) or an edit.
 */
public final class ArtifactTimes {

    /** First made. Never moves once known. */
    public static final String CREATED = "createdAt";
    /** Last changed — an edit, a re-teach, a re-wish that changed it. What "newest" sorts on. */
    public static final String UPDATED = "updatedAt";

    private ArtifactTimes() {
    }

    /** A row's two times; {@code 0} is unknown. */
    public record Times(long createdAt, long updatedAt) {

        public static final Times NONE = new Times(0L, 0L);

        public Times {
            createdAt = Math.max(0L, createdAt);
            updatedAt = Math.max(0L, updatedAt);
            if (createdAt > 0 && updatedAt > 0 && updatedAt < createdAt) {
                updatedAt = createdAt;   // a clock that went backwards still never reorders a row
            }
        }

        public boolean known() {
            return createdAt > 0 || updatedAt > 0;
        }

        /** The time "newest first" sorts on: the last change, else the making. */
        public long recency() {
            return Math.max(createdAt, updatedAt);
        }
    }

    /** The times a row carries, or {@link Times#NONE}. A damaged field is unknown, not an error. */
    public static Times of(JsonObject o) {
        return o == null ? Times.NONE : new Times(longOf(o, CREATED), longOf(o, UPDATED));
    }

    /** Write the times onto a row — or leave the row byte-identical when none are known. */
    public static void write(JsonObject o, Times t) {
        if (o == null || t == null || !t.known()) {
            return;
        }
        if (t.createdAt() > 0) {
            o.addProperty(CREATED, t.createdAt());
        }
        if (t.updatedAt() > 0) {
            o.addProperty(UPDATED, t.updatedAt());
        }
    }

    /** The times after a change at {@code now}: made now if new, else made when it was. */
    public static Times touched(Times had, long now) {
        if (had == null || !had.known()) {
            return new Times(now, now);
        }
        return new Times(had.createdAt() > 0 ? had.createdAt() : now, now);
    }

    private static long longOf(JsonObject o, String field) {
        JsonElement e = o.get(field);
        if (e == null || !e.isJsonPrimitive()) {
            return 0L;
        }
        try {
            return e.getAsLong();
        } catch (RuntimeException ex) {
            return 0L;
        }
    }

    /**
     * One store's times, by the store's own key.
     *
     * <p>Keys are lower-cased and trimmed — the shape every shelved store already keys on.
     */
    public static final class Book {

        private final Map<String, Times> times = new ConcurrentHashMap<>();

        private static String k(String key) {
            return key == null ? "" : key.trim().toLowerCase(java.util.Locale.ROOT);
        }

        public void clear() {
            times.clear();
        }

        /** At load: remember what this row says. */
        public void read(String key, JsonObject row) {
            Times t = of(row);
            if (!k(key).isEmpty() && t.known()) {
                times.put(k(key), t);
            } else {
                times.remove(k(key));
            }
        }

        /** It changed (or was made) at {@code now}. */
        public Times touch(String key, long now) {
            if (k(key).isEmpty()) {
                return Times.NONE;
            }
            Times next = touched(times.get(k(key)), now);
            times.put(k(key), next);
            return next;
        }

        /** Put these exact times back — an undo restores the row AND when it was last changed. */
        public void set(String key, Times t) {
            if (k(key).isEmpty()) {
                return;
            }
            if (t == null || !t.known()) {
                times.remove(k(key));
            } else {
                times.put(k(key), t);
            }
        }

        /**
         * The backfill: fill a row nobody had stamped. A row that already has a time keeps it —
         * a guess never overwrites a record.
         *
         * @return whether anything was filled
         */
        public boolean fill(String key, Times t) {
            if (k(key).isEmpty() || t == null || !t.known() || get(key).known()) {
                return false;
            }
            times.put(k(key), t);
            return true;
        }

        public void forget(String key) {
            times.remove(k(key));
        }

        public Times get(String key) {
            Times t = times.get(k(key));
            return t == null ? Times.NONE : t;
        }

        /** At save: the row as written, wearing its times. */
        public JsonObject stamp(String key, JsonObject row) {
            write(row, get(key));
            return row;
        }
    }
}
