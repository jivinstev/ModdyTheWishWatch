package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * <b>A left/right pair off by a unit is made each other's mirror, not sent back</b>.
 *
 * <p>The critique's LOPSIDED note was behind redos on both models in the baseline panel, and in the
 * starter pilot it still shipped three of a maintainer's 19 picks — a bear's eyes at x −3 and +4, an
 * eagle's brows and talons, a rabbit's inner ears — because an agent told "these do not mirror"
 * sometimes finishes anyway. Almost every case is the same mistake: whole-unit boxes mirrored by
 * hand, one side a unit out ({@code [-4,-2]} and {@code [3,5]}).
 *
 * <p>So a pair that is NEARLY a mirror — sizes within a unit per axis, the centres within
 * {@link #NEAR} of mirrored, tilts mirrored — has its right side rebuilt as the exact mirror of its
 * left, about the body's centre line, keeping its own colour and pattern. A pair further apart, or
 * tilted differently, may be meant (a wink, a raised arm) and stays the agent's. Runs on the tool's
 * arguments before parsing, like {@link SculptThickness}, so saved creatures are read back as they
 * were.
 *
 * <p>Pure and Minecraft-free.
 */
public final class SculptSymmetry {

    private SculptSymmetry() {
    }

    /** How far from mirrored a pair may be and still count as a slip, in sculpt units. */
    static final float NEAR = 1.5f;

    public static List<String> apply(JsonArray parts) {
        List<String> fixed = new ArrayList<>();
        if (parts == null || parts.isEmpty()) {
            return fixed;
        }
        List<CreatureSculpt.Part> parsed = CreatureSculpt.parseParts(parts);
        float axis = 0f;
        for (CreatureSculpt.Part p : parsed) {
            if (p.name().equalsIgnoreCase("body")) {
                axis = (p.from()[0] + p.to()[0]) / 2f;
            }
        }
        Map<String, CreatureSculpt.Part[]> pairs = new LinkedHashMap<>();
        for (CreatureSculpt.Part p : parsed) {
            String[] side = SculptCritique.side(p.name());
            if (side != null) {
                pairs.computeIfAbsent(side[0], k -> new CreatureSculpt.Part[2])[side[1].equals("L") ? 0 : 1] = p;
            }
        }
        Map<String, float[][]> rebuilt = new LinkedHashMap<>();
        for (CreatureSculpt.Part[] pair : pairs.values()) {
            CreatureSculpt.Part l = pair[0];
            CreatureSculpt.Part r = pair[1];
            if (l == null || r == null || !tiltsMirror(l, r)) {
                continue;
            }
            float[] lo = new float[3];
            float[] hi = new float[3];
            boolean near = true;
            boolean exact = true;
            for (int i = 0; i < 3; i++) {
                float dl = l.to()[i] - l.from()[i];
                float dr = r.to()[i] - r.from()[i];
                if (Math.abs(dl - dr) > 1.001f) {
                    near = false;
                }
                float cl = (l.from()[i] + l.to()[i]) / 2f;
                float cr = (r.from()[i] + r.to()[i]) / 2f;
                float miss = i == 0 ? Math.abs((cl - axis) + (cr - axis)) : Math.abs(cl - cr);
                if (miss > NEAR) {
                    near = false;
                }
                if (Math.abs(dl - dr) > 1e-3f || miss > 1e-3f) {
                    exact = false;
                }
                if (i == 0) {
                    lo[0] = 2 * axis - l.to()[0];
                    hi[0] = 2 * axis - l.from()[0];
                } else {
                    lo[i] = l.from()[i];
                    hi[i] = l.to()[i];
                }
            }
            if (near && !exact) {
                float[] pivot = l.pivot() == null ? null
                        : new float[] {2 * axis - l.pivot()[0], l.pivot()[1], l.pivot()[2]};
                rebuilt.put(r.name(), new float[][] {lo, hi, pivot});
            }
        }
        if (rebuilt.isEmpty()) {
            return fixed;
        }
        for (JsonElement el : parts) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String name = o.has("name") && o.get("name").isJsonPrimitive()
                    ? CreatureSculpt.sanitize(o.get("name").getAsString()) : "";
            float[][] box = rebuilt.get(name);
            if (box == null) {
                continue;
            }
            o.add("from", vec(box[0]));
            o.add("to", vec(box[1]));
            if (box[2] != null) {
                o.add("pivot", vec(box[2]));
            }
            fixed.add(name);
        }
        return fixed;
    }

    public static String note(List<String> fixed) {
        if (fixed == null || fixed.isEmpty()) {
            return "";
        }
        return "\nMIRRORED: " + String.join(", ", fixed) + " sat a unit or so off from their left-hand "
                + "partner, so each was rebuilt as its exact mirror (left side kept). Nothing to fix.\n";
    }

    /** A right part's tilt is the left's mirrored: x the same, y and z negated. */
    private static boolean tiltsMirror(CreatureSculpt.Part l, CreatureSculpt.Part r) {
        float[] a = l.rotation() == null ? new float[3] : l.rotation();
        float[] b = r.rotation() == null ? new float[3] : r.rotation();
        return Math.abs(a[0] - b[0]) < 1f && Math.abs(a[1] + b[1]) < 1f && Math.abs(a[2] + b[2]) < 1f;
    }

    private static JsonArray vec(float[] v) {
        JsonArray a = new JsonArray();
        for (float f : v) {
            a.add(f);
        }
        return a;
    }
}
