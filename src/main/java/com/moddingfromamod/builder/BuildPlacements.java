package com.moddingfromamod.builder;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * <b>Where a building stands, written on its own record</b>.
 *
 * <p>{@link BuildRegistry} saved each building's PLAN on the child's shelf and kept where it stood
 * in memory only, so every restart — every release — forgot it: {@code remove_build} refused,
 * {@code take_me_to} and {@code inspect_build} could not find it, an edit put up a second castle
 * at his aim, and a seated part's spot was "not standing". Now each record carries
 *
 * <pre>
 * "placed": {"&lt;world&gt;": {"dim":"minecraft:overworld","x":10,"y":64,"z":-3,
 *                         "toward":[4,9],"seed":123456789,"removed":true}}
 * </pre>
 *
 * <p>INLINE beside the plan's own keys (the store envelope's rule), so a jar from before reads the record and
 * ignores the field. Keyed by WORLD because the shelf follows him between worlds: a
 * Creative world's castle and a Survival copy of it are the same record in two places, and one world's
 * position means nothing in the other. {@code seed} is the world's seed when it went up: a world
 * deleted and made again under the same name has the same key and none of the old blocks, and
 * {@code remove_build} clearing a footprint there would delete terrain — so a position whose seed
 * does not match is treated as somewhere else, never as here. {@code removed} marks one taken down:
 * the position is kept (an edit can still rebuild it where it was) but it is not STANDING.
 *
 * <p>Written only when the building has been put up somewhere, so a record that never was stays
 * byte-identical. Pure — no Minecraft — so every rule is a row in {@code BuildPlacementsTest}.
 */
public final class BuildPlacements {

    /** The record's key. */
    public static final String FIELD = "placed";

    private BuildPlacements() {
    }

    /**
     * One building's position in one world.
     *
     * @param dim     the dimension id ({@code minecraft:overworld}), or null when nobody recorded it
     * @param seed    the world's seed when it went up, or null when unknown (a hand edit)
     */
    public record Placement(String dim, int x, int y, int z, int towardX, int towardZ, Long seed,
                            boolean removed) {

        public Placement withRemoved(boolean gone) {
            return new Placement(dim, x, y, z, towardX, towardZ, seed, gone);
        }

        /** Is this the world it says it is? A seed that disagrees means a world made again. */
        public boolean sameWorld(long worldSeed) {
            return seed == null || seed == worldSeed;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            if (dim != null && !dim.isEmpty()) {
                o.addProperty("dim", dim);
            }
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            if (towardX != 0 || towardZ != 0) {
                JsonArray t = new JsonArray();
                t.add(towardX);
                t.add(towardZ);
                o.add("toward", t);
            }
            if (seed != null) {
                o.addProperty("seed", seed);
            }
            if (removed) {
                o.addProperty("removed", true);
            }
            return o;
        }

        /** Null for a row that is not a position (no x/y/z): never a guessed one. */
        public static Placement fromJson(JsonElement el) {
            if (el == null || !el.isJsonObject()) {
                return null;
            }
            JsonObject o = el.getAsJsonObject();
            try {
                if (!o.has("x") || !o.has("y") || !o.has("z")) {
                    return null;
                }
                int tx = 0, tz = 0;
                if (o.has("toward") && o.get("toward").isJsonArray()
                        && o.getAsJsonArray("toward").size() == 2) {
                    tx = o.getAsJsonArray("toward").get(0).getAsInt();
                    tz = o.getAsJsonArray("toward").get(1).getAsInt();
                }
                return new Placement(
                        o.has("dim") && o.get("dim").isJsonPrimitive() ? o.get("dim").getAsString() : null,
                        o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(), tx, tz,
                        o.has("seed") && o.get("seed").isJsonPrimitive() ? o.get("seed").getAsLong() : null,
                        o.has("removed") && o.get("removed").isJsonPrimitive() && o.get("removed").getAsBoolean());
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /**
     * Every world's position on a record, as written. A field that is not an object, or rows that
     * are not positions, read as nothing — and are written back as they were (see {@link #stamp}).
     */
    public static Map<String, JsonObject> rows(JsonObject record) {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        JsonElement p = record == null ? null : record.get(FIELD);
        if (p != null && p.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : p.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonObject()) {
                    out.put(e.getKey(), e.getValue().getAsJsonObject());
                }
            }
        }
        return out;
    }

    /** What a record says about THIS world. */
    public enum Status {
        /** It stands here: the position is good. */
        HERE,
        /** It was here and was taken down. */
        REMOVED,
        /** It stands in another world. */
        ELSEWHERE,
        /** It stood in an earlier world of this name, since made again (another seed): not here. */
        REMADE,
        /** No position anywhere: built before positions were saved, or never built. */
        UNKNOWN
    }

    /** {@code here} = this world's own row, accepted; null otherwise. */
    public record Found(Status status, Placement here, String otherWorld) {
    }

    /** Read a record's rows for world {@code world} with seed {@code seed}. */
    public static Found find(Map<String, JsonObject> rows, String world, long seed) {
        Placement mine = world == null ? null : Placement.fromJson(rows.get(world));
        if (mine != null && mine.sameWorld(seed)) {
            return new Found(mine.removed() ? Status.REMOVED : Status.HERE, mine, null);
        }
        for (Map.Entry<String, JsonObject> e : rows.entrySet()) {
            Placement p = Placement.fromJson(e.getValue());
            if (p != null && !p.removed() && !e.getKey().equals(world)) {
                return new Found(Status.ELSEWHERE, null, e.getKey());
            }
        }
        return new Found(mine != null ? Status.REMADE : Status.UNKNOWN, null, null);
    }

    /**
     * The record as it should be written: every OTHER world's row kept exactly as read, this
     * world's row replaced by {@code here} (or left as read when {@code here} is null — a stale row
     * from a remade world is not ours to delete). No rows at all → no field, so a record that was
     * never put up stays byte-identical.
     */
    public static JsonObject stamp(JsonObject record, Map<String, JsonObject> read, String world,
                                   Placement here) {
        Map<String, JsonObject> rows = new LinkedHashMap<>(read);
        if (world != null && here != null) {
            rows.put(world, here.toJson());
        }
        if (rows.isEmpty()) {
            return record;
        }
        JsonObject placed = new JsonObject();
        rows.forEach(placed::add);
        record.add(FIELD, placed);
        return record;
    }

    /**
     * What to tell the agent (and through it, him) when a building has no position here. Honest
     * and never a guess — the plan is still saved, the position is not.
     */
    public static String whyNotHere(String id, Status status, String otherWorld) {
        return switch (status) {
            case REMOVED -> "'" + id + "' was taken down — it is not standing any more (its design "
                    + "is still saved, so it can be built again).";
            case ELSEWHERE -> "'" + id + "' stands in another world (" + otherWorld + "), not this "
                    + "one. Its design is saved here, so it can be built again here.";
            case REMADE -> "'" + id + "' stood in an earlier copy of this world, which has since been "
                    + "made again — it is not here. Its design is still saved, so it can be built again.";
            default -> "I don't know where '" + id + "' is any more — I have no record of it standing in this "
                    + "world (one put up before I started remembering where buildings stand, I lost "
                    + "track of). Its design is still saved, so it can be built again; tell him so "
                    + "rather than guess where an old one is.";
        };
    }
}
