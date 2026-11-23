package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * <b>Which end is the face — on an edit, and on the parts a face decides</b> (a maintainer's
 * grades).
 *
 * <p>Two things here, at two strengths, because one is an unambiguous mistake and the other is not:
 * <ul>
 *   <li>{@link #onTheFace}: an EDIT adds face parts (fangs, eyes, a nose) wholly on the far end of
 *       the creature it inherits — an Opus big cat built on the cat starter, whose face is at
 *       −z, got its fangs at z +11, and a maintainer graded it "the teeth are on the tail". The tool
 *       had even said where the face was. New face parts on the opposite end from the base's face
 *       are mirrored across z = 0 (the creature's own middle, the axis {@link SculptOrientation}
 *       mirrors about) and said so. Only parts that are new (not replacing a base part) and lie
 *       wholly on the wrong side move.</li>
 *   <li>{@link #note}: on any sculpt, a tail on the same side as the face, or every toe pointing
 *       away from it ("the chicken feet are backwards", "it had a tail on its front"). A bird has
 *       one toe behind, and a tail can curl forward on purpose, so these are said, never moved.</li>
 * </ul>
 * The face's side is read from FACE words only ({@link SculptOrientation#isFaceWord}): a tail is
 * what may be on the wrong side, so it cannot also vote on which side is the front.
 *
 * <p>Pure and Minecraft-free.
 */
public final class SculptSides {

    private SculptSides() {
    }

    /** +1 when the face parts sit at +z, −1 at −z, 0 when nothing names a face. */
    static int faceSide(List<CreatureSculpt.Part> parts) {
        double sum = 0;
        int n = 0;
        for (CreatureSculpt.Part p : parts) {
            if (SculptOrientation.isFaceWord(p.name())) {
                sum += (p.from()[2] + p.to()[2]) / 2.0;
                n++;
            }
        }
        if (n == 0 || Math.abs(sum / n) < 0.5) {
            return 0;
        }
        return sum > 0 ? 1 : -1;
    }

    /**
     * Mirror, in place, the NEW face parts an edit sent wholly on the far end of its base's face.
     *
     * @return the names moved, in order; empty when nothing was wrong or the base shows no face
     */
    public static List<String> onTheFace(JsonArray sent, List<CreatureSculpt.Part> base) {
        List<String> moved = new ArrayList<>();
        if (sent == null || base == null || base.isEmpty()) {
            return moved;
        }
        int face = faceSide(base);
        if (face == 0) {
            return moved;
        }
        Set<String> baseNames = new HashSet<>();
        base.forEach(p -> baseNames.add(p.name().toLowerCase(Locale.ROOT)));
        for (JsonElement el : sent) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String name = o.has("name") && o.get("name").isJsonPrimitive() ? o.get("name").getAsString() : "";
            float[] from = CreatureSculpt.vec3(o, "from");
            float[] to = CreatureSculpt.vec3(o, "to");
            if (from == null || to == null || !SculptOrientation.isFaceWord(name)
                    || baseNames.contains(CreatureSculpt.sanitize(name).toLowerCase(Locale.ROOT))) {
                continue;
            }
            float lo = Math.min(from[2], to[2]);
            float hi = Math.max(from[2], to[2]);
            boolean wrongEnd = face < 0 ? lo > 0 : hi < 0;
            if (!wrongEnd) {
                continue;
            }
            from[2] = -hi;
            to[2] = -lo;
            o.add("from", vec(from));
            o.add("to", vec(to));
            float[] pivot = CreatureSculpt.vec3(o, "pivot");
            if (pivot != null) {
                pivot[2] = -pivot[2];
                o.add("pivot", vec(pivot));
            }
            moved.add(name);
        }
        return moved;
    }

    /** What the agent is told about {@link #onTheFace}. Empty when nothing moved. */
    public static String movedNote(List<String> moved) {
        if (moved == null || moved.isEmpty()) {
            return "";
        }
        return "\nMOVED TO THE FACE END: " + String.join(", ", moved) + " sat on the far end of the "
                + "creature you started from, away from its face, so they were mirrored across its "
                + "middle (z → −z) onto the face end. Nothing to fix — next time, read which side the "
                + "base's face is on before adding to it.\n";
    }

    /**
     * A tail on the face's side, or every toe pointing away from the face. Said, never moved.
     * Empty when the face's side is unknown or nothing looks wrong.
     */
    public static String note(List<CreatureSculpt.Part> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        int face = faceSide(parts);
        if (face == 0) {
            return "";
        }
        List<String> tails = new ArrayList<>();
        List<String> toes = new ArrayList<>();
        int toesForward = 0;
        for (CreatureSculpt.Part p : parts) {
            String n = p.name().toLowerCase(Locale.ROOT);
            double z = (p.from()[2] + p.to()[2]) / 2.0;
            if (n.contains("tail") && Math.signum(z) == face && Math.abs(z) >= 1) {
                tails.add(p.name());
            } else if (n.contains("toe")) {
                if (Math.signum(z) == -face) {
                    toes.add(p.name());
                } else {
                    toesForward++;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        String side = face < 0 ? "−z" : "+z";
        if (!tails.isEmpty()) {
            sb.append("\n⚠ TAIL ON THE FACE SIDE: ").append(String.join(", ", tails))
              .append(" sit on the same side as the face (").append(side)
              .append("), so the tail grows out of its chest. Move it to the other end unless the "
                      + "wish asked for that.\n");
        }
        if (!toes.isEmpty() && toesForward == 0) {
            sb.append("\n⚠ TOES POINT BACKWARDS: every toe (").append(String.join(", ", toes))
              .append(") is on the far side from the face (").append(side)
              .append("), so the feet face the wrong way. Put them on the face side.\n");
        }
        return sb.toString();
    }

    private static JsonArray vec(float[] v) {
        JsonArray a = new JsonArray();
        for (float f : v) {
            a.add(f);
        }
        return a;
    }
}
