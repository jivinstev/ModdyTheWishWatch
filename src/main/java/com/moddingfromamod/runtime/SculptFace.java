package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>A face with a mood.</b>
 *
 * <h2>Why this exists</h2>
 *
 * <p>Shown the winning creature of a third A/B, the human said: <i>"left is still the better shape
 * and texture, but Chucko looks angry — he generally should be happier."</i> For a <b>mascot</b>
 * that is not a small note. Mood is not decoration, it <i>is</i> the character: a scowling Chucko
 * McBurger is wrong in the same way an all-green Spikor is wrong, and nothing in this project
 * represented mood at all — not {@code Species}, not {@code ReferenceSpec}, not the sculpt
 * vocabulary.
 *
 * <p>It surfaced exactly when it did for a reason. Rotation is what made an angled brow
 * <b>possible in the first place</b>; before it every face was axis-aligned and no expression was
 * reachable, happy or angry. So the feature that enabled expression also enabled <i>unintended</i>
 * expression — the agent could suddenly make faces without being told what faces mean.
 *
 * <h2>Mood is carried by POSITION, not rotation — deliberately</h2>
 *
 * <p>The obvious build is a rotated brow. It is also the fragile one: which way a positive Z-roll
 * tips a brow depends on a sign convention that cannot be checked without looking at a screen, and
 * this project has been caught by exactly that kind of derivation repeatedly — the renderer's 180°
 * flip, the cube-versus-bone rotation, the spike row's axis. Getting it backwards would ship a
 * cheerful mascot that scowls, which is the bug being fixed.
 *
 * <p>So a brow is <b>two boxes at different heights</b>: inner-lower is unambiguously a scowl,
 * inner-higher is unambiguously worry, in any renderer, under any sign convention. A tilt is added
 * on top purely to soften the step — if that tilt were mirrored tomorrow the mood would still read
 * correctly, because the mood does not depend on it. Same for the mouth: a smile is three boxes
 * with the ends placed higher, not a rotated bar.
 *
 * <p>Pure and Minecraft-free.
 */
public final class SculptFace {

    private SculptFace() {
    }

    /** The bounded vocabulary. Anything else is not a mood this can promise to draw. */
    public static final Set<String> MOODS = Set.of(
            "happy", "angry", "sad", "surprised", "sleepy", "fierce");

    /** Near-misses a child's words turn into. Unknown degrades to "happy", never to a scowl. */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "happy";
        }
        String m = raw.trim().toLowerCase(Locale.ROOT);
        switch (m) {
            case "cheerful", "friendly", "smiling", "smile", "glad", "joyful", "nice" -> m = "happy";
            case "mad", "cross", "grumpy", "furious", "mean" -> m = "angry";
            case "unhappy", "gloomy", "crying", "upset" -> m = "sad";
            case "shocked", "amazed", "startled", "surprise" -> m = "surprised";
            case "tired", "bored", "drowsy", "calm" -> m = "sleepy";
            case "scary", "savage", "feral", "wild", "monstrous" -> m = "fierce";
            default -> { }
        }
        // DEFAULTING TO HAPPY IS THE POINT. A creature whose mood was not understood should come
        // out friendly: an unexpected scowl on a child's mascot is the failure being fixed, and an
        // unexpected smile on a monster is merely funny.
        return MOODS.contains(m) ? m : "happy";
    }

    /**
     * How a mood is drawn.
     *
     * @param browInnerDrop how much lower the INNER end of a brow sits than the outer, in
     *                      sixteenths. Positive scowls, negative worries, zero is level.
     * @param browHeight    where the brows sit, as a fraction of head height
     * @param mouthEndsRise how much higher the mouth's ENDS sit than its centre. Positive smiles,
     *                      negative frowns.
     * @param eyeSquash     1.0 is a round eye; smaller is a narrowed, sleepy one
     * @param openMouth     draw the mouth as an open O rather than a line
     * @param teeth         add a row of teeth
     */
    public record Look(float browInnerDrop, float browHeight, float mouthEndsRise,
                       float eyeSquash, boolean openMouth, boolean teeth) {
    }

    /** The six moods, as numbers rather than adjectives. */
    public static Look look(String mood) {
        return switch (sanitize(mood)) {
            case "angry" -> new Look(2.0f, 0.74f, -1.5f, 0.85f, false, false);
            case "fierce" -> new Look(2.5f, 0.74f, -1.0f, 0.8f, true, true);
            case "sad" -> new Look(-2.0f, 0.76f, -2.0f, 1.0f, false, false);
            case "surprised" -> new Look(0f, 0.82f, 0f, 1.15f, true, false);
            case "sleepy" -> new Look(0f, 0.70f, 0f, 0.4f, false, false);
            default -> new Look(-0.5f, 0.78f, 2.0f, 1.0f, false, false);   // happy
        };
    }

    /**
     * Build a face onto the front of a head box.
     *
     * @param tag    bone-name prefix (already carrying the kit marker)
     * @param parent the head bone
     * @param b      the head's box as {@code [minX,minY,minZ,maxX,maxY,maxZ]}
     * @param mood   one of {@link #MOODS}
     * @param ink    the colour for brows and mouth
     * @param eyeCol the colour for eyes
     */
    public static List<CreatureSculpt.Part> build(String tag, String parent, float[] b,
                                                  String mood, int ink, int eyeCol) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        Look look = look(mood);

        float w = b[3] - b[0];
        float h = b[4] - b[1];
        // +Z is the FRONT as the agent builds it — verified against ten real sculpts, and the same
        // convention the claw and spike_row kits use. SculptOrientation may mirror the whole
        // creature afterwards, which carries the face round with it.
        float front = b[5];
        float cx = (b[0] + b[3]) / 2f;

        // PROPORTIONS CHECKED AGAINST A RENDER, not derived. The first numbers made an eye
        // TALLER than it was wide -- two vertical bars rather than eyes -- and put the brows
        // straight on top of them, because 0.60h + 0.20h overlapped 0.78h. An eye is a wide,
        // shallow rectangle with clear air above it.
        float eyeW = Math.max(3f, w * 0.24f);
        float eyeH = Math.max(1f, h * 0.10f * look.eyeSquash());
        float eyeY = b[1] + h * 0.52f;
        float eyeX = w * 0.23f;

        for (int side = 0; side < 2; side++) {
            float dir = side == 0 ? -1f : 1f;
            float ex = cx + dir * eyeX;
            out.add(box(tag + "eye" + (side == 0 ? "L" : "R"), parent,
                    ex - eyeW / 2, eyeY, front - 1f, ex + eyeW / 2, eyeY + eyeH, front + 1.5f,
                    eyeCol));

            // THE BROW, AS TWO BOXES. The inner one sits lower or higher than the outer, and that
            // difference IS the mood — no rotation sign is involved, so it cannot come out mirrored.
            // Brows sit ABOVE the eyes with a visible gap, never on them.
            float browY = eyeY + eyeH + Math.max(1f, h * 0.06f) + h * (look.browHeight() - 0.74f);
            float browH = Math.max(1f, h * 0.045f);
            float half = eyeW * 0.5f;
            // BOTH ENDS STAY ABOVE THE EYE. Centring the step on browY sank the inner end INTO
            // the eye box on the strongest moods, so an angry brow rendered as a single flat bar --
            // the step that carries the whole expression was invisible on exactly the moods that
            // needed it most. Anchor the lower end at the brow line and raise the other.
            // ...and CLAMPED to the room left on the skull, or a strong mood lifts a brow clean
            // off the top of the head. The test caught that; a floating eyebrow is not a mood.
            float room = Math.max(0f, b[4] - browY - browH - 0.5f);
            float lift = Math.min(Math.abs(look.browInnerDrop()) * 2f, room);
            float inner = look.browInnerDrop() > 0 ? browY : browY + lift;
            float outer = look.browInnerDrop() > 0 ? browY + lift : browY;
            float innerX = ex - dir * half;
            float outerX = ex + dir * half;
            out.add(box(tag + "brow" + (side == 0 ? "L" : "R") + "i", parent,
                    Math.min(innerX, ex), inner, front - 1f,
                    Math.max(innerX, ex), inner + browH, front + 1.5f, ink));
            out.add(box(tag + "brow" + (side == 0 ? "L" : "R") + "o", parent,
                    Math.min(ex, outerX), outer, front - 1f,
                    Math.max(ex, outerX), outer + browH, front + 1.5f, ink));
        }

        // THE MOUTH, AS THREE BOXES: a centre and two ends. Ends higher is a smile, lower a frown.
        // Position again, for the same reason.
        float mouthY = b[1] + h * 0.26f;
        float mouthW = Math.max(4f, w * 0.46f);
        float seg = mouthW / 3f;
        float mouthH = Math.max(1.5f, h * (look.openMouth() ? 0.13f : 0.06f));
        for (int i = 0; i < 3; i++) {
            float x = cx - mouthW / 2 + seg * i;
            float y = mouthY + (i == 1 ? 0f : look.mouthEndsRise());
            out.add(box(tag + "mouth" + i, parent,
                    x, y, front - 1f, x + seg, y + mouthH, front + 1.5f, ink));
        }
        if (look.teeth()) {
            for (int i = 0; i < 3; i++) {
                float x = cx - mouthW / 2 + seg * i + seg * 0.25f;
                out.add(box(tag + "tooth" + i, parent,
                        x, mouthY + mouthH * 0.4f, front - 1f,
                        x + seg * 0.5f, mouthY + mouthH, front + 1.6f, 0xFFFFFF));
            }
        }
        return out;
    }

    private static CreatureSculpt.Part box(String name, String parent,
            float x0, float y0, float z0, float x1, float y1, float z1, int colour) {
        float[] from = {Math.min(x0, x1), Math.min(y0, y1), Math.min(z0, z1)};
        float[] to = {Math.max(x0, x1), Math.max(y0, y1), Math.max(z0, z1)};
        // A face part is small by nature, and parseParts drops anything under a unit thick — a
        // silently missing mouth is exactly the failure this kit exists to prevent.
        for (int i = 0; i < 3; i++) {
            if (to[i] - from[i] < 1f) {
                to[i] = from[i] + 1f;
            }
        }
        float[] pivot = {(from[0] + to[0]) / 2f, from[1], (from[2] + to[2]) / 2f};
        return new CreatureSculpt.Part(CreatureSculpt.sanitize(name), parent, pivot, from, to,
                colour, CreatureSculpt.NO_ROTATION, 0f, "", 0);
    }
}
