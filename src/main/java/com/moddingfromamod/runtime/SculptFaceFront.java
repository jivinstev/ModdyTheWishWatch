package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * <b>A face sunk inside the head is brought forward, not sent back</b>.
 *
 * <p>On the 2026-10-27 baseline panel, 14 of Spark's 16 re-sculpts followed a BURIED note, and the
 * buried parts were almost always the face — eyes, a mouth, a nose placed inside the head's box,
 * so from the front the child sees a blank head. The agent's redo moved them forward; a redo costs
 * ~9 s of the wish. This makes the same move before the sculpt is built, and says so.
 *
 * <p>The rule is narrow on purpose:
 * <ul>
 *   <li>only FACE parts ({@link SculptCritique#isFaceFeature}), judged from the front only, exactly
 *       as the critique judges them;</li>
 *   <li>only when everything covering them is NOT itself a face part — a mouth under a beak is a
 *       design question (move the beak? the mouth?) and stays the agent's;</li>
 *   <li>ONE shift for all of them, the least that puts every one 0.5 proud of what covered it, so
 *       their layering (a pupil in front of an eye) is kept; face parts sitting on a moved one, by
 *       footprint, ride along;</li>
 *   <li>never more than {@link #MAX_SHIFT}: a face buried deeper than that was not placed on the
 *       wrong side of a surface, it was placed somewhere else, and that stays the agent's to fix.</li>
 * </ul>
 *
 * <p>Runs on the tool's arguments before parsing, like {@link SculptThickness}, so a creature
 * already saved is read back exactly as before. Pure and Minecraft-free.
 */
public final class SculptFaceFront {

    private SculptFaceFront() {
    }

    /** How far proud of its cover a face part is left. */
    static final float PROUD = 0.5f;

    /** The furthest a face is moved. Past this it was not a near miss. */
    static final float MAX_SHIFT = 4f;

    /** What was done: the parts moved and by how much along z (0 and empty when nothing). */
    public record Result(List<String> moved, float shift) {

        public String note() {
            if (moved.isEmpty()) {
                return "";
            }
            return String.format(Locale.ROOT, "\\nBROUGHT THE FACE FORWARD: %s sat inside the head, "
                    + "where nobody could see them, so they moved %.1f toward the front (together, "
                    + "so their layering is kept) and now sit just proud of it. Nothing to fix — "
                    + "next time put face parts ON the face, not inside the head.%n",
                    String.join(", ", moved), Math.abs(shift));
        }
    }

    public static Result apply(JsonArray parts) {
        Result none = new Result(List.of(), 0f);
        if (parts == null || parts.isEmpty()) {
            return none;
        }
        List<CreatureSculpt.Part> parsed = CreatureSculpt.parseParts(parts);
        int front = SculptOrientation.frontSign(parsed);
        if (front == 0) {
            return none;
        }
        List<SculptCritique.BuriedFace> buried = SculptCritique.buriedFaces(parsed);
        Set<String> move = new LinkedHashSet<>();
        float shift = 0f;
        for (SculptCritique.BuriedFace b : buried) {
            if (b.occluders().isEmpty()
                    || b.occluders().stream().anyMatch(SculptFaceFront::isFacePart)) {
                continue;
            }
            CreatureSculpt.Part p = byName(parsed, b.name());
            if (p == null) {
                continue;
            }
            float coverFront = front > 0 ? -Float.MAX_VALUE : Float.MAX_VALUE;
            for (String o : b.occluders()) {
                CreatureSculpt.Part op = byName(parsed, o);
                if (op != null) {
                    float[] ob = SculptCritique.bounds(op);
                    coverFront = front > 0 ? Math.max(coverFront, ob[5]) : Math.min(coverFront, ob[2]);
                }
            }
            float[] pb = SculptCritique.bounds(p);
            float need = front > 0 ? coverFront + PROUD - pb[5] : coverFront - PROUD - pb[2];
            if (Math.abs(need) > MAX_SHIFT || Math.signum(need) != front) {
                continue;
            }
            move.add(p.name());
            shift = front > 0 ? Math.max(shift, need) : Math.min(shift, need);
        }
        if (move.isEmpty()) {
            return none;
        }
        // Anything smaller sitting ON a moved part (a pupil, a highlight on an eye) rides along, or
        // the move buries it. "On" = its footprint overlaps and it touches the part along z.
        Set<String> riders = new LinkedHashSet<>();
        for (CreatureSculpt.Part q : parsed) {
            if (move.contains(q.name())) {
                continue;
            }
            for (String m : move) {
                CreatureSculpt.Part mp = byName(parsed, m);
                if (mp != null && volume(q) < volume(mp) && footprintOverlaps(q, mp)
                        && touchesAlongZ(q, mp)) {
                    riders.add(q.name());
                    break;
                }
            }
        }
        move.addAll(riders);
        List<String> moved = new ArrayList<>();
        for (JsonElement el : parts) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String name = o.has("name") && o.get("name").isJsonPrimitive()
                    ? CreatureSculpt.sanitize(o.get("name").getAsString()) : "";
            if (!move.contains(name)) {
                continue;
            }
            for (String k : new String[] {"from", "to", "pivot"}) {
                float[] v = CreatureSculpt.vec3(o, k);
                if (v != null) {
                    v[2] += shift;
                    JsonArray a = new JsonArray();
                    for (float f : v) {
                        a.add(f);
                    }
                    o.add(k, a);
                }
            }
            moved.add(name);
        }
        return new Result(moved, shift);
    }

    /**
     * A part ON the face (an eye, a mouth, a beak) — the critique's feature words minus the head
     * itself, which the critique counts because on some sculpts the head IS the face, but which here
     * is the surface a face is brought forward out of.
     */
    static boolean isFacePart(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return SculptCritique.isFaceFeature(n) && !n.equals("head") && !n.startsWith("head_");
    }

    private static CreatureSculpt.Part byName(List<CreatureSculpt.Part> parts, String name) {
        for (CreatureSculpt.Part p : parts) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    private static double volume(CreatureSculpt.Part p) {
        return (double) Math.max(1, p.width()) * Math.max(1, p.height()) * Math.max(1, p.depth());
    }

    private static boolean touchesAlongZ(CreatureSculpt.Part a, CreatureSculpt.Part b) {
        float[] ab = SculptCritique.bounds(a);
        float[] bb = SculptCritique.bounds(b);
        return ab[2] <= bb[5] + 0.01f && bb[2] <= ab[5] + 0.01f;
    }

    private static boolean footprintOverlaps(CreatureSculpt.Part a, CreatureSculpt.Part b) {
        float[] ab = SculptCritique.bounds(a);
        float[] bb = SculptCritique.bounds(b);
        return ab[0] < bb[3] && bb[0] < ab[3] && ab[1] < bb[4] && bb[1] < ab[4];
    }
}
