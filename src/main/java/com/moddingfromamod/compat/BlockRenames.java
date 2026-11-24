package com.moddingfromamod.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;

/**
 * <b>Block ids a newer Minecraft renamed</b>, applied when a STORED plan is read on a version where the old id no
 * longer exists. Minecraft's own DataFixers upgrade the world's blocks; nothing upgrades the block ids a wish wrote into
 * its own files, so a 1.21.8 well still says {@code minecraft:chain} on 1.21.10, where that block is gone.
 *
 * <p><b>The table is the mechanism.</b> One {@link Row} per rename, with the Minecraft version that introduced it; a
 * later rename is one more row, never new code. Rows apply in table order, so a block renamed twice chains through both.
 *
 * <p><b>The marker.</b> A stored record carries {@code "mc": "<the Minecraft version it was written for>"}
 * ({@link #MARKER}), stamped by the store when it writes ({@link #stamp}). A record without one was written before the
 * marker, which is {@value #PRE_MARKER}. A row applies when the record was written BEFORE the row's version and this
 * build runs at or after it, so a record stamped by this build is never remapped again. A record stamped by a NEWER
 * Minecraft than this build is read as it is: no row maps an id backwards. A marker that is not a version is refused
 * (an {@link IllegalArgumentException}), never guessed at.
 *
 * <p>Reading never rewrites the file: the remapped ids reach the disk the next time the store saves that record, with
 * this build's marker on it.
 *
 * <p>Pure (no Minecraft imports), tested in Gate A ({@code BlockRenamesTest}).
 */
public final class BlockRenames {

    private BlockRenames() {
    }

    /** One rename: {@code from} no longer exists from Minecraft {@code since} on, and is {@code to} there. */
    public record Row(String from, String to, String since) {
    }

    /** Every rename, oldest first. */
    public static final List<Row> ROWS = List.of(
            // 1.21.9: DataFixers schema 4541, "Rename chain to iron_chain" (block and item)
            new Row("minecraft:chain", "minecraft:iron_chain", "1.21.9"));

    /** The key a stored record's Minecraft version is kept under. */
    public static final String MARKER = "mc";

    /** What a record with no marker was written for: every Moddy before the marker targeted 1.21.8. */
    public static final String PRE_MARKER = "1.21.8";

    /** The Minecraft version {@code record} was written for: its marker, else {@link #PRE_MARKER}. Throws on a bad one. */
    public static String writtenFor(JsonObject record) {
        if (record == null || !record.has(MARKER) || record.get(MARKER).isJsonNull()) {
            return PRE_MARKER;
        }
        JsonElement m = record.get(MARKER);
        String v = m.isJsonPrimitive() && m.getAsJsonPrimitive().isString() ? m.getAsString() : "";
        if (!v.matches("\\d+(\\.\\d+)*")) {
            throw new IllegalArgumentException("\"" + MARKER + "\" is not a Minecraft version: " + m);
        }
        return v;
    }

    /** {@code record} with this build's marker on it, in place; returned for chaining. */
    public static JsonObject stamp(JsonObject record, String runningOn) {
        record.addProperty(MARKER, runningOn);
        return record;
    }

    /**
     * One block as written for {@code writtenFor}, as it is called on {@code runningOn}. Keeps what is around the id: a
     * missing namespace, block states ({@code [axis=y]}) and NBT. A tag ({@code #…}) and an id no row names pass
     * through untouched.
     */
    public static String remap(String block, String writtenFor, String runningOn) {
        return remap(ROWS, block, writtenFor, runningOn);
    }

    /** {@link #remap(String, String, String)} through {@code rows} (a test hands its own table). */
    static String remap(List<Row> rows, String block, String writtenFor, String runningOn) {
        if (block == null || block.isEmpty() || block.startsWith("#")) {
            return block;
        }
        int cut = block.length();
        for (char c : new char[] {'[', '{'}) {
            int i = block.indexOf(c);
            if (i >= 0 && i < cut) {
                cut = i;
            }
        }
        String id = block.substring(0, cut);
        String rest = block.substring(cut);
        boolean bare = !id.contains(":");
        String full = bare ? "minecraft:" + id : id;
        String was = full;
        for (Row r : rows) {
            if (r.from().equals(full) && compare(writtenFor, r.since()) < 0 && compare(runningOn, r.since()) >= 0) {
                full = r.to();
            }
        }
        if (full.equals(was)) {
            return block;
        }
        return (bare && full.startsWith("minecraft:") ? full.substring("minecraft:".length()) : full) + rest;
    }

    /**
     * A stored building plan ({@code {"shapes":[{"block":…},…]}}) as it reads on {@code runningOn}: a copy with every
     * shape's block remapped for the version its marker names. The marker itself is left as it was (the store stamps
     * on write). The same object when nothing changes. Throws when the marker is not a version.
     */
    public static JsonObject plan(JsonObject stored, String runningOn) {
        return plan(ROWS, stored, runningOn);
    }

    /** {@link #plan(JsonObject, String)} through {@code rows}. */
    static JsonObject plan(List<Row> rows, JsonObject stored, String runningOn) {
        if (stored == null) {
            return null;
        }
        String from = writtenFor(stored);
        if (compare(from, runningOn) >= 0 || !(stored.get("shapes") instanceof JsonArray shapes)) {
            return stored;
        }
        JsonObject out = null;
        for (int i = 0; i < shapes.size(); i++) {
            if (!(shapes.get(i) instanceof JsonObject s) || !s.has("block") || !s.get("block").isJsonPrimitive()) {
                continue;
            }
            String b = s.get("block").getAsString();
            String now = remap(rows, b, from, runningOn);
            if (!now.equals(b)) {
                if (out == null) {
                    out = stored.deepCopy();
                }
                out.getAsJsonArray("shapes").get(i).getAsJsonObject().addProperty("block", now);
            }
        }
        return out == null ? stored : out;
    }

    /** Dotted Minecraft versions, numerically: {@code 1.21.10} is after {@code 1.21.9}; {@code 1.21} is {@code 1.21.0}. */
    public static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }
}
