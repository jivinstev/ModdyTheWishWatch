package com.moddingfromamod.film;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>A film camera's path written as DATA: keyframes on the take clock</b> (Minecraft-free).
 *
 * <p>{@link CameraPath} moves the camera by parameters in Java (follow, orbit). A filmed take needs a
 * camera that is the same every time and that a plan can edit: positions, look targets and a field of
 * view at given times, eased between. Whatever times the take (a timeline runner) works out the
 * milliseconds and loads one of these over RCON ({@code /moddyop filmcam path load});
 * {@link FilmPathPlayer} plays it through {@link FilmCamera}.
 *
 * <h2>The file</h2>
 * <pre>{@code
 * {"keys":[
 *    {"at_ms":0,    "pos":[10.5,72,3.5], "look":[0,70,0],                       "fov":70},
 *    {"at_ms":900,  "pos":[4,71.6,2],    "look":{"entity":"@e[tag=film_subject,limit=1]"}, "ease":"inOutSine"},
 *    {"at_ms":2400, "pos":[1,71.6,-3],   "look":{"entity":"0f8c…-uuid"},         "ease":"cut"}
 *  ],
 *  "loop":false}
 * }</pre>
 * <ul>
 *   <li>{@code at_ms} — when the camera IS at this key, in milliseconds from the moment the path is
 *       played. Never decreasing; two keys at the same time are a jump.</li>
 *   <li>{@code pos} — the camera's eye, block coordinates. Required.</li>
 *   <li>{@code look} — the point it looks at, either {@code [x,y,z]} or {@code {"entity":"…"}} (a UUID
 *       or any entity selector; resolved EVERY tick, at the entity's eyes). Optional after the first
 *       key, which must have one; a key without one keeps the previous key's.</li>
 *   <li>{@code fov} — degrees, optional (default: the previous key's, else 70). Carried, eased and
 *       reported, but a stock client's field of view cannot be set by a server while it looks
 *       through a non-player entity; see {@link FilmPathPlayer}.</li>
 *   <li>{@code ease} — how the camera ARRIVES at this key from the one before: {@code inOutSine}
 *       (the default: starts and ends at rest), {@code linear}, or {@code cut} (holds the previous
 *       key exactly until this key's {@code at_ms}, then jumps). The first key's ease is unused.</li>
 *   <li>{@code loop} — when true the path restarts at its last key's {@code at_ms} (the wrap is a
 *       jump back to the first key; end on a copy of the first key for a seamless loop). Otherwise
 *       the camera holds the last key until stopped.</li>
 * </ul>
 *
 * <h2>Time is TICKS</h2>
 * The player evaluates the path at {@code ms = tick × 50}, where {@code tick} counts server ticks
 * since {@code play}; never the wall clock. So the same path played twice puts the camera at the same
 * place on every tick (a looked-at entity aside), and a key is reached on tick
 * {@link #tickReaching ceil(at_ms / 50)}: a cut lands at most 49 ms after its planned time, inside
 * one tick. A modded viewer draws the same path between ticks ({@link #sample(Path, double)}, on
 * {@link FilmClock}'s play ticks plus the frame's partial tick), which agrees with the server on every
 * whole tick; its cut lands on the first FRAME at or after {@code at_ms}.
 */
public final class CameraKeys {

    /** A server tick, in milliseconds. The only clock a path is evaluated on. */
    public static final int MS_PER_TICK = 50;

    /** The field of view a path assumes until a key gives one (the client default). */
    public static final double DEFAULT_FOV = 70;

    public enum Ease {
        LINEAR("linear"), IN_OUT_SINE("inOutSine"), CUT("cut");

        public final String id;

        Ease(String id) {
            this.id = id;
        }

        /** The eased fraction for a linear {@code u} in [0, 1]. {@code cut} is 0 until u reaches 1. */
        public double apply(double u) {
            double c = Math.max(0, Math.min(1, u));
            return switch (this) {
                case LINEAR -> c;
                case IN_OUT_SINE -> -(Math.cos(Math.PI * c) - 1) / 2;
                case CUT -> c >= 1 ? 1 : 0;
            };
        }

        public static Ease parse(String s) {
            for (Ease e : values()) {
                if (e.id.equalsIgnoreCase(s)) {
                    return e;
                }
            }
            throw new IllegalArgumentException("ease '" + s + "' is not one of inOutSine, linear, cut");
        }
    }

    /**
     * One keyframe. Exactly one of {@code look} (a point) and {@code lookEntity} (a selector or UUID)
     * is non-null once {@link #parse} has filled the gaps.
     */
    public record Key(long atMs, double[] pos, double[] look, String lookEntity, double fov, Ease ease) {
    }

    /** A loaded path: keys in time order, and whether it loops. */
    public record Path(List<Key> keys, boolean loop) {
        public Path {
            keys = List.copyOf(keys);
        }

        /** The last key's {@code at_ms}: how long the path runs before it holds (or loops). */
        public long durationMs() {
            return keys.get(keys.size() - 1).atMs();
        }
    }

    /**
     * Where the path is at one moment: the two keys it is between ({@code from} == {@code to} when
     * holding), and the EASED fraction {@code s} of the way from one to the other. The look is left
     * for the caller to resolve, since a key may look at an entity that only the world knows.
     */
    public record Sample(int from, int to, double s, double[] pos, double fov) {
    }

    /** Resolves a key's look to a point; returns null when its entity cannot be found. */
    @FunctionalInterface
    public interface LookResolver {
        double[] look(Key key);
    }

    private CameraKeys() {
    }

    /** The path's milliseconds on play-tick {@code tick} (tick 0 is the tick {@code play} ran). */
    public static long msAtTick(long tick) {
        return tick * MS_PER_TICK;
    }

    /** The first play-tick on which a key at {@code atMs} has been reached: ceil(atMs / 50). */
    public static long tickReaching(long atMs) {
        return Math.floorDiv(atMs + MS_PER_TICK - 1, MS_PER_TICK);
    }

    /** The path-relative time for elapsed {@code ms}: wrapped when the path loops. */
    public static long pathMs(Path path, long ms) {
        long d = path.durationMs();
        if (path.loop() && d > 0) {
            return Math.floorMod(ms, d);
        }
        return ms;
    }

    /**
     * The index of the last key whose {@code at_ms} is at or before {@code ms} (path time): the key
     * most recently reached, or 0 before the first key.
     */
    public static int keyAt(Path path, long ms) {
        List<Key> keys = path.keys();
        int lo = 0, hi = keys.size() - 1, found = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (keys.get(mid).atMs() <= ms) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /** The path at elapsed {@code ms} (looping applied): the segment, eased fraction, eye and FOV. */
    public static Sample sample(Path path, long ms) {
        long t = pathMs(path, ms);
        List<Key> keys = path.keys();
        int i = keyAt(path, t);
        Key a = keys.get(i);
        if (i == keys.size() - 1 || t < a.atMs()) {
            return new Sample(i, i, 0, a.pos().clone(), a.fov());
        }
        Key b = keys.get(i + 1);
        long span = b.atMs() - a.atMs();   // > 0: keyAt took the LAST key at or before t
        double s = b.ease().apply((double) (t - a.atMs()) / span);
        return new Sample(i, i + 1, s, lerp(a.pos(), b.pos(), s), a.fov() + (b.fov() - a.fov()) * s);
    }

    /**
     * The path at a FRACTIONAL elapsed {@code ms}: what a modded client draws between ticks,
     * at {@code (ticks + partialTick) × 50}. The same arithmetic as {@link #sample(Path, long)}, so on a
     * whole tick the two agree; the server keeps the {@code long} form, whose read-back is hashed.
     */
    public static Sample sample(Path path, double ms) {
        double t = ms;
        long d = path.durationMs();
        if (path.loop() && d > 0) {
            t = ms - d * Math.floor(ms / d);
            if (t >= d) {
                t = 0;   // rounding at the wrap
            }
        }
        List<Key> keys = path.keys();
        int lo = 0, hi = keys.size() - 1, i = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (keys.get(mid).atMs() <= t) {
                i = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        Key a = keys.get(i);
        if (i == keys.size() - 1 || t < a.atMs()) {
            return new Sample(i, i, 0, a.pos().clone(), a.fov());
        }
        Key b = keys.get(i + 1);
        long span = b.atMs() - a.atMs();
        double s = b.ease().apply((t - a.atMs()) / span);
        return new Sample(i, i + 1, s, lerp(a.pos(), b.pos(), s), a.fov() + (b.fov() - a.fov()) * s);
    }

    /**
     * The path back as the JSON {@link #parse} reads, every key written in full (look, fov and ease
     * included), so {@code parse(toJson(p))} is {@code p} key for key. It is how a path is handed to a
     * modded client, in the one format both sides already speak.
     */
    public static String toJson(Path p) {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Key k : p.keys()) {
            JsonObject j = new JsonObject();
            j.addProperty("at_ms", k.atMs());
            j.add("pos", vecJson(k.pos()));
            if (k.lookEntity() != null) {
                JsonObject e = new JsonObject();
                e.addProperty("entity", k.lookEntity());
                j.add("look", e);
            } else {
                j.add("look", vecJson(k.look()));
            }
            j.addProperty("fov", k.fov());
            j.addProperty("ease", k.ease().id);
            arr.add(j);
        }
        o.add("keys", arr);
        o.addProperty("loop", p.loop());
        return o.toString();
    }

    private static JsonArray vecJson(double[] v) {
        JsonArray a = new JsonArray();
        for (double d : v) {
            a.add(d);
        }
        return a;
    }

    /**
     * The look point for a sample: the two keys' looks (each resolved now, so an entity is followed
     * tick by tick) eased by the same fraction as the eye. Mid-segment, when one end cannot be
     * resolved the other is used alone; at either end of a segment only that end counts. Null when
     * nothing can be resolved: the caller holds its last look.
     */
    public static double[] look(Path path, Sample sm, LookResolver resolver) {
        double[] a = resolver.look(path.keys().get(sm.from()));
        if (sm.from() == sm.to()) {
            return a;
        }
        if (sm.s() <= 0) {
            return a;   // not the other end: before a cut that would turn the camera early
        }
        double[] b = resolver.look(path.keys().get(sm.to()));
        if (sm.s() >= 1) {
            return b;
        }
        if (a == null || b == null) {
            return a == null ? b : a;
        }
        return lerp(a, b, sm.s());
    }

    static double[] lerp(double[] a, double[] b, double s) {
        if (s <= 0) {
            return a.clone();
        }
        if (s >= 1) {
            return b.clone();
        }
        return new double[] {a[0] + (b[0] - a[0]) * s, a[1] + (b[1] - a[1]) * s, a[2] + (b[2] - a[2]) * s};
    }

    /** {@link #parse(JsonObject)} for a JSON text. */
    public static Path parse(String json) {
        JsonElement e;
        try {
            e = JsonParser.parseString(json);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("not JSON: " + ex.getMessage());
        }
        if (!e.isJsonObject()) {
            throw new IllegalArgumentException("a camera path is a JSON object with \"keys\"");
        }
        return parse(e.getAsJsonObject());
    }

    /** Read and check a path; every problem is an IllegalArgumentException naming the key. */
    public static Path parse(JsonObject o) {
        if (!o.has("keys") || !o.get("keys").isJsonArray() || o.getAsJsonArray("keys").isEmpty()) {
            throw new IllegalArgumentException("\"keys\" must be a non-empty array");
        }
        JsonArray arr = o.getAsJsonArray("keys");
        List<Key> keys = new ArrayList<>(arr.size());
        long lastAt = Long.MIN_VALUE;
        double[] look = null;
        String lookEntity = null;
        double fov = DEFAULT_FOV;
        for (int i = 0; i < arr.size(); i++) {
            String where = "key " + i + ": ";
            if (!arr.get(i).isJsonObject()) {
                throw new IllegalArgumentException(where + "not an object");
            }
            JsonObject k = arr.get(i).getAsJsonObject();
            if (!k.has("at_ms")) {
                throw new IllegalArgumentException(where + "no at_ms");
            }
            long at = number(k.get("at_ms"), where + "at_ms");
            if (at < 0) {
                throw new IllegalArgumentException(where + "at_ms is negative");
            }
            if (at < lastAt) {
                throw new IllegalArgumentException(where + "at_ms " + at + " is before the previous key's " + lastAt);
            }
            lastAt = at;
            if (!k.has("pos")) {
                throw new IllegalArgumentException(where + "no pos");
            }
            double[] pos = vec(k.get("pos"), where + "pos");
            if (k.has("look")) {
                JsonElement l = k.get("look");
                if (l.isJsonObject() && l.getAsJsonObject().has("entity")
                        && l.getAsJsonObject().get("entity").isJsonPrimitive()
                        && !l.getAsJsonObject().get("entity").getAsString().isBlank()) {
                    lookEntity = l.getAsJsonObject().get("entity").getAsString().trim();
                    look = null;
                } else if (l.isJsonArray()) {
                    look = vec(l, where + "look");
                    lookEntity = null;
                } else {
                    throw new IllegalArgumentException(where + "look must be [x,y,z] or {\"entity\":\"…\"}");
                }
            } else if (i == 0) {
                throw new IllegalArgumentException(where + "the first key needs a look");
            }
            if (k.has("fov")) {
                fov = real(k.get("fov"), where + "fov");
                if (!(fov > 0 && fov < 180)) {
                    throw new IllegalArgumentException(where + "fov " + fov + " is not in (0, 180)");
                }
            }
            Ease ease = Ease.IN_OUT_SINE;
            if (k.has("ease")) {
                try {
                    ease = Ease.parse(k.get("ease").getAsString());
                } catch (RuntimeException ex) {
                    throw new IllegalArgumentException(where + ex.getMessage());
                }
            }
            keys.add(new Key(at, pos, look == null ? null : look.clone(), lookEntity, fov, ease));
        }
        boolean loop = o.has("loop") && o.get("loop").isJsonPrimitive() && o.get("loop").getAsBoolean();
        return new Path(Collections.unmodifiableList(keys), loop);
    }

    private static long number(JsonElement e, String what) {
        double d = real(e, what);
        if (d != Math.rint(d)) {
            throw new IllegalArgumentException(what + " must be a whole number of milliseconds");
        }
        return (long) d;
    }

    private static double real(JsonElement e, String what) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(what + " must be a number");
        }
        double d = e.getAsDouble();
        if (!Double.isFinite(d)) {
            throw new IllegalArgumentException(what + " must be finite");
        }
        return d;
    }

    private static double[] vec(JsonElement e, String what) {
        if (!e.isJsonArray() || e.getAsJsonArray().size() != 3) {
            throw new IllegalArgumentException(what + " must be [x,y,z]");
        }
        JsonArray a = e.getAsJsonArray();
        return new double[] {real(a.get(0), what), real(a.get(1), what), real(a.get(2), what)};
    }

    /** One-line summary for a command reply. */
    public static String describe(Path p) {
        return String.format(Locale.ROOT, "%d keys, %d ms%s", p.keys().size(), p.durationMs(), p.loop() ? ", loops" : "");
    }
}
