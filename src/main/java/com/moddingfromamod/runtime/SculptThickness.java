package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * <b>A box too thin to draw is made 1 thick, not thrown away</b>.
 *
 * <p>{@link CreatureSculpt#parseParts} drops any box under one unit on an axis, because it draws
 * nothing, and the sculpt tool then told the agent "DROPPED FOR BEING TOO THIN TO DRAW ... make each
 * at least 1 unit on every axis". That instruction is mechanical — a pupil at 0.7 deep becomes a
 * pupil at 1 deep — and the agent paid a whole re-sculpt to carry it out. Measured over the
 * 2026-10-27 baseline panel (26 Opus grants): 8 of the 11 Opus re-sculpts followed that note, and a
 * re-sculpt costs ~10 s of the wish. The parts are almost always face details sent at 0.5 deep so
 * they sit proud: eyes, pupils, mouths, brows, fangs.
 *
 * <p>So the tool does the edit itself, BEFORE parsing, and says what it did. Each thin axis grows to
 * 1 unit, away from the creature's middle (the side with the larger magnitude), so a pupil on the
 * front of a face keeps its inner face where the agent put it and grows outward, sitting proud the
 * way it was meant to. It runs on the tool's arguments rather than inside the parser, so a creature
 * already saved is read back exactly as before.
 *
 * <p>Pure and Minecraft-free.
 */
public final class SculptThickness {

    private SculptThickness() {
    }

    /** The thinnest a box may be and still draw. */
    public static final float MIN = 1f;

    /**
     * Thicken every part in {@code parts} that is under {@link #MIN} on an axis, in place.
     *
     * @return the names of the parts that were thickened, in order
     */
    public static List<String> thicken(JsonArray parts) {
        List<String> out = new ArrayList<>();
        if (parts == null) {
            return out;
        }
        for (JsonElement el : parts) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            float[] from = CreatureSculpt.vec3(o, "from");
            float[] to = CreatureSculpt.vec3(o, "to");
            if (from == null || to == null) {
                continue;
            }
            boolean changed = false;
            for (int i = 0; i < 3; i++) {
                float lo = Math.min(from[i], to[i]);
                float hi = Math.max(from[i], to[i]);
                if (hi - lo >= MIN || !Float.isFinite(lo) || !Float.isFinite(hi)) {
                    continue;
                }
                float grow = MIN - (hi - lo);
                if (i == 1) {               // up/down: no "outward", so grow both ways
                    lo -= grow / 2;
                    hi += grow / 2;
                } else if (Math.abs(hi) >= Math.abs(lo)) {
                    hi += grow;             // the outer face is at hi: grow outward
                } else {
                    lo -= grow;
                }
                from[i] = lo;
                to[i] = hi;
                changed = true;
            }
            if (changed) {
                o.add("from", vec(from));
                o.add("to", vec(to));
                out.add(o.has("name") && o.get("name").isJsonPrimitive()
                        ? o.get("name").getAsString() : "(unnamed)");
            }
        }
        return out;
    }

    /** What the agent is told: done, not to-do. Empty when nothing was thickened. */
    public static String note(List<String> thickened) {
        if (thickened == null || thickened.isEmpty()) {
            return "";
        }
        List<String> shown = thickened.size() > 8 ? thickened.subList(0, 8) : thickened;
        return "\nMADE 1 UNIT THICK (a box under 1 on any axis draws nothing): "
                + String.join(", ", shown) + (thickened.size() > shown.size()
                        ? " and " + (thickened.size() - shown.size()) + " more" : "")
                + ". Each grew outward from the creature's middle, so it sits proud where you put "
                + "it. Nothing to fix — send 1 or more next time.\n";
    }

    private static JsonArray vec(float[] v) {
        JsonArray a = new JsonArray();
        for (float f : v) {
            a.add(f);
        }
        return a;
    }
}
