package com.moddingfromamod.reference;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>Does the build stand the way the character does, and is its tail right?</b>
 *
 * <p>A maintainer's vote on the broad likeness run: Tabbycat, Snuffy and Scruff-Pup were built on all fours ("a cat
 * standing on his hind legs … the simple description of cat is misleading it"), and Pippo Rabbit got a long tail.
 * The reference now carries {@link ReferenceSpec.Stance} and {@link ReferenceSpec.Tail}; this reads the same two
 * facts off the boxes that were built and says so before the reveal, the way {@link ReferencePartCheck} does for
 * named parts. It reports; it never refuses. Pure and Minecraft-free.
 *
 * <p>Stance is read from the ground, not from names: the boxes that reach the lowest level are the feet, and
 * feet that stand apart from each other are separate legs (a leg and the shoe under it are one). Four or more
 * feet, or three with a body longer than it is tall, is four-legged; one or two feet under a body that is not
 * long is upright. Anything else is not judged.
 */
public final class ReferenceBodyCheck {

    /** One built box, in model units (16 = one block): the name the agent gave it and its corners. */
    public record Box(String name, float[] from, float[] to) {
    }

    /** What the boxes say about how it stands. */
    enum Built { UPRIGHT, FOUR_LEGS, UNKNOWN }

    private ReferenceBodyCheck() {
    }

    static float lo(Box b, int ax) {
        return Math.min(b.from()[ax], b.to()[ax]);
    }

    static float hi(Box b, int ax) {
        return Math.max(b.from()[ax], b.to()[ax]);
    }

    /** How the built boxes stand. */
    static Built stance(List<Box> boxes) {
        if (boxes == null || boxes.isEmpty()) {
            return Built.UNKNOWN;
        }
        float ground = Float.MAX_VALUE;
        for (Box b : boxes) {
            ground = Math.min(ground, lo(b, 1));
        }
        // The feet: boxes that reach within one unit of the ground, merged where they overlap in plan.
        List<float[]> feet = new ArrayList<>();   // {x0, x1, z0, z1}
        for (Box b : boxes) {
            if (lo(b, 1) > ground + 1f || isTail(b.name())) {
                continue;
            }
            float[] f = {lo(b, 0), hi(b, 0), lo(b, 2), hi(b, 2)};
            boolean merged = false;
            for (float[] g : feet) {
                if (f[0] < g[1] + 0.5f && g[0] < f[1] + 0.5f && f[2] < g[3] + 0.5f && g[2] < f[3] + 0.5f) {
                    g[0] = Math.min(g[0], f[0]);
                    g[1] = Math.max(g[1], f[1]);
                    g[2] = Math.min(g[2], f[2]);
                    g[3] = Math.max(g[3], f[3]);
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                feet.add(f);
            }
        }
        Box body = body(boxes);
        boolean longBody = body != null && (hi(body, 2) - lo(body, 2)) > 1.2f * (hi(body, 1) - lo(body, 1));
        if (feet.size() >= 4 || (feet.size() == 3 && longBody)) {
            return Built.FOUR_LEGS;
        }
        if (feet.size() >= 1 && feet.size() <= 2 && !longBody) {
            return Built.UPRIGHT;
        }
        return Built.UNKNOWN;
    }

    /** The part named body or torso, else the biggest box. */
    static Box body(List<Box> boxes) {
        Box best = null;
        double vol = -1;
        for (Box b : boxes) {
            String n = b.name() == null ? "" : b.name().toLowerCase(Locale.ROOT);
            if (n.equals("body") || n.equals("torso")) {
                return b;
            }
            double v = (double) (hi(b, 0) - lo(b, 0)) * (hi(b, 1) - lo(b, 1)) * (hi(b, 2) - lo(b, 2));
            if (v > vol) {
                vol = v;
                best = b;
            }
        }
        return best;
    }

    static boolean isTail(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.contains("tail") && !n.contains("pony") && !n.contains("pig");
    }

    /** What to tell the agent, or "" when the build agrees with the reference or there is nothing to judge. */
    public static String report(ReferenceSpec.Stance stance, ReferenceSpec.Tail tail, List<Box> boxes,
                                String character) {
        if (boxes == null || boxes.isEmpty()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        Built built = stance(boxes);
        if (stance == ReferenceSpec.Stance.UPRIGHT_BIPED && built == Built.FOUR_LEGS) {
            out.add("⚠ STANCE — " + character + " stands and walks upright on two legs, like a person, and your "
                    + "sculpt stands on four. Rebuild it standing up: two legs under a body that is taller than it "
                    + "is long, arms at its sides, the head on top. A four-legged ready-made body (start_from a "
                    + "cat, a dog, a wolf) is the wrong starting point for this character.");
        } else if (stance == ReferenceSpec.Stance.QUADRUPED && built == Built.UPRIGHT) {
            out.add("⚠ STANCE — " + character + " stands and walks on four legs, and your sculpt stands upright "
                    + "on two. Rebuild it on four legs, its body longer than it is tall.");
        }
        List<Box> tails = new ArrayList<>();
        float y0 = Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (Box b : boxes) {
            y0 = Math.min(y0, lo(b, 1));
            y1 = Math.max(y1, hi(b, 1));
            if (isTail(b.name())) {
                tails.add(b);
            }
        }
        float height = Math.max(1f, y1 - y0);
        float tailLength = 0;
        if (!tails.isEmpty()) {
            float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (Box b : tails) {
                for (int ax = 0; ax < 3; ax++) {
                    lo[ax] = Math.min(lo[ax], lo(b, ax));
                    hi[ax] = Math.max(hi[ax], hi(b, ax));
                }
            }
            tailLength = Math.max(hi[0] - lo[0], Math.max(hi[1] - lo[1], hi[2] - lo[2]));
        }
        List<String> names = tails.stream().map(Box::name).toList();
        if (tail == ReferenceSpec.Tail.NONE && !tails.isEmpty()) {
            out.add("⚠ TAIL — " + character + " has no tail, and your sculpt has " + String.join(", ", names)
                    + ". Take it off (with start_from, list it in remove).");
        } else if (tail == ReferenceSpec.Tail.SHORT && tailLength > 0.4f * height) {
            out.add("⚠ TAIL — " + character + " has a short tail, and yours is " + Math.round(tailLength)
                    + " units long on a body " + Math.round(height) + " tall. Make it a short stub or puff.");
        } else if (tail == ReferenceSpec.Tail.LONG && tails.isEmpty()) {
            out.add("⚠ TAIL — " + character + " has a long tail, and your sculpt has none.");
        }
        return String.join("\n", out);
    }
}
