package com.moddingfromamod.reference;

import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptCritique;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>Lever A: the TOOL keeps a named character's main colour.</b> Pure.
 *
 * <p>Measured (an earlier issue's first end-to-end, Opus at low effort): told "⚠ MAIN COLOUR WRONG: fix this
 * before you show him", the agent re-built none of three wrong builds. It kept "an original stand-in
 * (not a copy of any franchise character)". Advice loses to the model's own copying caution, and a
 * refusal can end in an empty wish. So the build tool does the one thing a child recognises
 * first, server-side, after the agent has decided everything else: every part (or painted rectangle)
 * in the build's dominant colour is turned to the reference's main colour, keeping its own lightness
 * and saturation. The shape, the face, the name and every other colour stay the agent's design.
 *
 * <p>It acts only when {@link MainColourCheck} would have fired: a sure reference, a main colour of
 * at least a quarter, missing from the build, and no colour of the child's own in his words.
 * {@code -Ptestprops standincolour=off} is the A/B arm.
 */
public final class StandInColour {

    public static final String PROPERTY = "moddingfromamod.standincolour";

    /** From which built bucket to which reference bucket. */
    public record Shift(String from, String to) {
    }

    private StandInColour() {
    }

    public static boolean on() {
        return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
    }

    /** The shift to make, or empty: exactly when the main-colour check would fire. */
    public static Optional<Shift> plan(Map<String, Double> built, ReferenceSpec reference, String childWords) {
        if (MainColourCheck.forAgent(built, reference, "x", childWords, "x").isEmpty()) {
            return Optional.empty();
        }
        String to = reference.colours().get(0).colour();
        int best = -1;
        for (var c : reference.colours()) {
            if (c.percent() > best) {
                best = c.percent();
                to = c.colour();
            }
        }
        String from = null;
        double top = -1;
        for (var e : built.entrySet()) {
            if (e.getValue() > top) {
                top = e.getValue();
                from = e.getKey();
            }
        }
        if (from == null || from.equals(to) || achromatic(to)) {
            return Optional.empty();   // never paint a character grey or white by rule
        }
        return Optional.of(new Shift(from, to));
    }

    /** The colour turned to the target bucket's hue, its own saturation and brightness kept. */
    public static int shift(int rgb, String to) {
        float[] hsv = java.awt.Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        float s = Math.max(hsv[1], 0.35f);
        float v = Math.max(hsv[2], 0.45f);
        float hue = switch (to) {
            case "red" -> 0f;
            case "tan/orange" -> 30f;
            case "yellow" -> 54f;
            case "green" -> 120f;
            case "cyan" -> 185f;
            case "blue" -> 222f;
            case "purple/pink" -> v > 0.8f && s < 0.6f ? 330f : 290f;   // a pastel is pink, a deep one purple
            default -> hsv[0] * 360f;
        };
        int out = java.awt.Color.HSBtoRGB(hue / 360f, s, v) & 0xFFFFFF;
        // Land INSIDE the target bucket, or the next check sees no change.
        return SculptCritique.bucket(out).equals(to) ? out : rgb;
    }

    /**
     * Never a skin tone, brown hair or a dark shade: they sit in the same buckets as a shirt or a body
     * (measured, round 2: turning all of "tan/orange" gave Plumo a red face and red hair, and Link a
     * green face). Skin and browns are the soft or dark colours of the orange hues; a pastel body (a mint
     * Puffo) is still turned.
     */
    public static boolean turnable(int rgb) {
        float[] hsv = java.awt.Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        float h = hsv[0] * 360f;
        if (hsv[2] < 0.35f) {
            return false;   // a shadow, a pupil, a dark outline
        }
        boolean orangeHue = h >= 8f && h < 50f;
        return !(orangeHue && (hsv[1] < 0.55f || hsv[2] < 0.55f));
    }

    static boolean achromatic(String bucket) {
        return "white".equals(bucket) || "grey".equals(bucket) || "black".equals(bucket);
    }

    /** A sculpt's parts with the shift applied to every {@link #turnable} part (and pattern colour) in {@code from}. */
    public static List<CreatureSculpt.Part> parts(List<CreatureSculpt.Part> parts, Shift s) {
        List<CreatureSculpt.Part> out = new ArrayList<>(parts.size());
        for (var p : parts) {
            boolean main = SculptCritique.bucket(p.colour()).equals(s.from()) && turnable(p.colour());
            boolean pat = p.pattern() != null && SculptCritique.bucket(p.patternColour()).equals(s.from())
                    && turnable(p.patternColour());
            out.add(main || pat ? new CreatureSculpt.Part(p.name(), p.parent(), p.pivot(), p.from(), p.to(),
                    main ? shift(p.colour(), s.to()) : p.colour(), p.rotation(), p.inflate(), p.pattern(),
                    pat ? shift(p.patternColour(), s.to()) : p.patternColour()) : p);
        }
        return out;
    }

    /** A picture's rectangles with the shift applied. */
    public static List<CreatureSculpt.PaintRect> rects(List<CreatureSculpt.PaintRect> rects, Shift s) {
        List<CreatureSculpt.PaintRect> out = new ArrayList<>(rects.size());
        for (var r : rects) {
            out.add(SculptCritique.bucket(r.colour()).equals(s.from()) && turnable(r.colour())
                    ? new CreatureSculpt.PaintRect(r.x(), r.y(), r.width(), r.height(), shift(r.colour(), s.to()))
                    : r);
        }
        return out;
    }

    /** What the agent is told, so its note and its words to the child match what was built. */
    public static String note(String name, Shift s) {
        return "🎨 COLOURS KEPT: the real " + name + " is mostly " + MainColourCheck.words(s.to())
                + ", so the parts you made " + MainColourCheck.words(s.from()) + " are now "
                + MainColourCheck.words(s.to()) + ". Your shape, face and name are unchanged. Describe it to "
                + "him in its real colours; if he asked for different colours, re-sculpt naming them.";
    }
}
