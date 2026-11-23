package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * <b>Stretch or move a part of the creature you started from</b> (idea G).
 *
 * <p>{@code start_from} could replace a part by name, remove, recolour and scale the whole creature,
 * but not change a PROPORTION — and proportion is exactly what separates near species: a tiger from
 * the cat is a longer body and a bigger head, a donkey from the horse is longer ears and shorter
 * legs. Measured before this existed: a near species reused a starter in 3 of 12 Opus wishes and 1
 * of 12 Spark, because re-sending a part at a new size meant writing its box from scratch (and a
 * very different size was sent back as "a new SHAPE").
 *
 * <p>An edit now names the base part and how it changes:
 * <pre>
 *   "reshape": [{"part": "body", "scale": [1, 1, 1.5]},
 *               {"part": "leg",  "scale": [1, 0.7, 1], "anchor": "bottom"},
 *               {"part": "earL", "move": [0, 2, 0]}]
 * </pre>
 * Each entry becomes a REPLACEMENT of that base part, built from the base part itself (colour,
 * pattern, tilt, parent all kept), so {@link SculptBase#merge} carries whatever hangs on it — the
 * tail moves with a longer body, the ears with a bigger head — exactly as for a hand-sent
 * replacement. A part named in {@code parts} as well is the agent's own box, and wins.
 *
 * <ul>
 *   <li>{@code part}: a base part's name; a name that matches no part exactly is a prefix, so
 *       {@code "leg"} reshapes legL, legR, legBL and legBR together.</li>
 *   <li>{@code scale}: one number, or {@code [x, y, z]}; each clamped to 0.25–4.</li>
 *   <li>{@code anchor}: the point that stays put — {@code centre} (default), {@code bottom},
 *       {@code top}, {@code front} (the −z face, where a creature faces) or {@code back}. A part
 *       standing on the ground keeps its feet on the ground unless an anchor is given.</li>
 *   <li>{@code move}: {@code [dx, dy, dz]} after the scale, each clamped to ±32.</li>
 * </ul>
 *
 * <p>Pure and Minecraft-free.
 */
public final class SculptReshape {

    private SculptReshape() {
    }

    static final float MIN_SCALE = 0.25f;
    static final float MAX_SCALE = 4f;
    static final float MAX_MOVE = 32f;

    /** What one reshape did, for the note the agent reads. */
    public record Change(String part, float[] scale, float[] move) {
    }

    /** The replacements, and what was asked that matched nothing. */
    public record Result(List<CreatureSculpt.Part> replacements, List<Change> changes, List<String> unmatched) {

        public boolean isEmpty() {
            return replacements.isEmpty() && unmatched.isEmpty();
        }

        public String note() {
            if (isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            if (!changes.isEmpty()) {
                sb.append("\nRESHAPED: ");
                List<String> said = new ArrayList<>();
                for (Change c : changes) {
                    said.add(c.part() + describe(c));
                }
                sb.append(String.join(", ", said))
                  .append(". Anything that hangs on a reshaped part moved with it.\n");
            }
            if (!unmatched.isEmpty()) {
                sb.append("⚠ NOT RESHAPED — the base has no part called ")
                  .append(String.join(", ", unmatched))
                  .append(". Use a name from the base's outline (or a prefix of one, like \"leg\").\n");
            }
            return sb.toString();
        }

        private static String describe(Change c) {
            List<String> bits = new ArrayList<>();
            if (c.scale()[0] != 1f || c.scale()[1] != 1f || c.scale()[2] != 1f) {
                bits.add(String.format(Locale.ROOT, "×%s", c.scale()[0] == c.scale()[1] && c.scale()[1] == c.scale()[2]
                        ? fmt(c.scale()[0]) : fmt(c.scale()[0]) + "/" + fmt(c.scale()[1]) + "/" + fmt(c.scale()[2])));
            }
            if (c.move()[0] != 0f || c.move()[1] != 0f || c.move()[2] != 0f) {
                bits.add("moved " + fmt(c.move()[0]) + "," + fmt(c.move()[1]) + "," + fmt(c.move()[2]));
            }
            return bits.isEmpty() ? "" : " (" + String.join(", ", bits) + ")";
        }
    }

    public static Result apply(List<CreatureSculpt.Part> base, JsonArray reshape) {
        Map<String, CreatureSculpt.Part> out = new LinkedHashMap<>();
        List<Change> changes = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        if (base == null || base.isEmpty() || reshape == null) {
            return new Result(List.of(), changes, unmatched);
        }
        for (JsonElement el : reshape) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String query = o.has("part") && o.get("part").isJsonPrimitive() ? o.get("part").getAsString().trim() : "";
            if (query.isEmpty()) {
                continue;
            }
            float[] scale = scaleOf(o);
            float[] move = vec(o, "move", 0f, -MAX_MOVE, MAX_MOVE);
            String anchor = o.has("anchor") && o.get("anchor").isJsonPrimitive()
                    ? o.get("anchor").getAsString().trim().toLowerCase(Locale.ROOT) : "";
            List<CreatureSculpt.Part> hits = matching(base, query);
            if (hits.isEmpty()) {
                unmatched.add(query);
                continue;
            }
            for (CreatureSculpt.Part p : hits) {
                // A part already reshaped by an earlier entry is reshaped again from where it got to.
                CreatureSculpt.Part from = out.getOrDefault(p.name(), p);
                out.put(p.name(), reshaped(from, scale, move, anchor));
                changes.add(new Change(p.name(), scale, move));
            }
        }
        return new Result(List.copyOf(out.values()), changes, unmatched);
    }

    static List<CreatureSculpt.Part> matching(List<CreatureSculpt.Part> base, String query) {
        String q = query.toLowerCase(Locale.ROOT);
        for (CreatureSculpt.Part p : base) {
            if (p.name().equalsIgnoreCase(q)) {
                return List.of(p);
            }
        }
        String stem = q.endsWith("s") && q.length() > 3 ? q.substring(0, q.length() - 1) : q;
        List<CreatureSculpt.Part> hits = new ArrayList<>();
        for (CreatureSculpt.Part p : base) {
            String n = p.name().toLowerCase(Locale.ROOT);
            if (n.startsWith(q) || n.startsWith(stem)) {
                hits.add(p);
            }
        }
        return hits;
    }

    static CreatureSculpt.Part reshaped(CreatureSculpt.Part p, float[] s, float[] d, String anchor) {
        float[] lo = {Math.min(p.from()[0], p.to()[0]), Math.min(p.from()[1], p.to()[1]), Math.min(p.from()[2], p.to()[2])};
        float[] hi = {Math.max(p.from()[0], p.to()[0]), Math.max(p.from()[1], p.to()[1]), Math.max(p.from()[2], p.to()[2])};
        boolean onGround = lo[1] <= 0.01f;
        float[] a = new float[3];
        a[0] = (lo[0] + hi[0]) / 2f;
        a[1] = switch (anchor) {
            case "bottom" -> lo[1];
            case "top" -> hi[1];
            case "centre", "center", "front", "back" -> (lo[1] + hi[1]) / 2f;
            default -> onGround ? lo[1] : (lo[1] + hi[1]) / 2f;
        };
        a[2] = switch (anchor) {
            case "front" -> lo[2];
            case "back" -> hi[2];
            default -> (lo[2] + hi[2]) / 2f;
        };
        float[] from = new float[3];
        float[] to = new float[3];
        for (int i = 0; i < 3; i++) {
            from[i] = a[i] + (lo[i] - a[i]) * s[i] + d[i];
            to[i] = a[i] + (hi[i] - a[i]) * s[i] + d[i];
            if (to[i] - from[i] < 1f) {               // never scale a part out of existence
                float mid = (from[i] + to[i]) / 2f;
                from[i] = mid - 0.5f;
                to[i] = mid + 0.5f;
            }
        }
        float[] pivot = null;
        if (p.pivot() != null) {
            pivot = new float[3];
            for (int i = 0; i < 3; i++) {
                pivot[i] = a[i] + (p.pivot()[i] - a[i]) * s[i] + d[i];
            }
        }
        return new CreatureSculpt.Part(p.name(), p.parent(), pivot, from, to, p.colour(),
                p.rotation(), p.inflate(), p.pattern(), p.patternColour());
    }

    private static float[] scaleOf(JsonObject o) {
        if (o.has("scale") && o.get("scale").isJsonPrimitive()) {
            try {
                float f = o.get("scale").getAsFloat();
                f = Float.isFinite(f) ? clamp(f, MIN_SCALE, MAX_SCALE) : 1f;
                return new float[]{f, f, f};
            } catch (RuntimeException e) {
                return new float[]{1f, 1f, 1f};
            }
        }
        return vec(o, "scale", 1f, MIN_SCALE, MAX_SCALE);
    }

    private static float[] vec(JsonObject o, String key, float dflt, float min, float max) {
        float[] v = {dflt, dflt, dflt};
        if (o.has(key) && o.get(key).isJsonArray()) {
            JsonArray a = o.getAsJsonArray(key);
            for (int i = 0; i < 3 && i < a.size(); i++) {
                try {
                    float f = a.get(i).getAsFloat();
                    v[i] = Float.isFinite(f) ? clamp(f, min, max) : dflt;
                } catch (RuntimeException ignored) {
                    // a non-number keeps the default for that axis
                }
            }
        }
        return v;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String fmt(float v) {
        return v == Math.round(v) ? Integer.toString(Math.round(v)) : String.format(Locale.ROOT, "%.2f", v);
    }
}
