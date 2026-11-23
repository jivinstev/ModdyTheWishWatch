package com.moddingfromamod.event.shape;

/**
 * <b>{@code impact_stamp}: a one-shot heightfield pressed into the ground</b> (E2). Pure.
 *
 * <p>Three forms, all the same idea — a height change as a function of distance from a centre:
 * <ul>
 *   <li>{@link Form#CRATER}: dug down, with a flat floor, a hot middle and a raised rim OUTSIDE the
 *       hole. Its depth comes from its DIAMETER at about five to one: an earlier mod's first craters divided the
 *       radius instead, came out half as deep as the class claimed, and photographed as concentric
 *       rings on the grass rather than a hole.</li>
 *   <li>{@link Form#CONE}: built up to a lip with a bowl in the top (a volcano).</li>
 *   <li>{@link Form#MOUND}: a smooth dome (a drift, a hill that rises, a heap).</li>
 * </ul>
 * Positive is raised, negative is dug, zero is untouched.
 */
public final class StampShape {

    public enum Form { CRATER, CONE, MOUND }

    /** What a cell of the stamp is made of. */
    public enum Role { NONE, BODY, CORE, RIM }

    public static final double WIDTH_TO_DEPTH = 5.0;
    public static final double FLOOR_FRACTION = 0.45;
    public static final double RIM_REACH = 0.30;
    /**
     * The rim's height as a share of the depth. At 0.28 a medium crater's rim was ONE block, and from
     * where he stands on flat ground a crater with no rim is a dark line on the grass (E6's film):
     * the raised ring is what he sees of it before he walks over and looks in.
     */
    public static final double RIM_HEIGHT = 0.6;
    public static final double CORE_FRACTION = 0.35;
    /** A cone is about as tall as it is wide from the middle: an earlier mod's volcano was 26 on a radius of 22. */
    public static final double CONE_HEIGHT = 1.2;
    public static final double MOUTH_FRACTION = 0.27;
    public static final double BOWL_SLOPE = 1.4;
    public static final double MOUND_HEIGHT = 0.5;

    public final Form form;
    public final int radius;

    public StampShape(Form form, int radius) {
        if (radius < 3) {
            throw new IllegalArgumentException("a stamp needs a radius of at least 3");
        }
        this.form = form;
        this.radius = radius;
    }

    public int depth() {
        return Math.max(2, (int) Math.round(radius * 2.0 / WIDTH_TO_DEPTH));
    }

    public int height() {
        return switch (form) {
            case CRATER -> Math.max(1, (int) Math.round(depth() * RIM_HEIGHT));
            case CONE -> (int) Math.round(radius * CONE_HEIGHT);
            case MOUND -> Math.max(2, (int) Math.round(radius * MOUND_HEIGHT));
        };
    }

    /** How far out anything changes. */
    public int outerRadius() {
        return form == Form.CRATER ? (int) Math.ceil(radius * (1.0 + RIM_REACH)) : radius;
    }

    /** The change at this distance: + raised, − dug, 0 untouched. */
    public int deltaAt(double d) {
        return switch (form) {
            case CRATER -> crater(d);
            case CONE -> cone(d);
            case MOUND -> d >= radius ? 0
                    : (int) Math.round(height() * (0.5 + 0.5 * Math.cos(d / radius * Math.PI)));
        };
    }

    private int crater(double d) {
        if (d >= radius) {
            double span = outerRadius() - radius;
            if (d > outerRadius() || span <= 0) {
                return 0;
            }
            return (int) Math.round(height() * (1.0 - (d - radius) / span));
        }
        double floor = radius * FLOOR_FRACTION;
        if (d <= floor) {
            return -depth();
        }
        double t = (d - floor) / (radius - floor);
        return -(int) Math.round(depth() * (0.5 + 0.5 * Math.cos(t * Math.PI)));
    }

    private int cone(double d) {
        if (d > radius) {
            return 0;
        }
        int h = height();
        int outside = (int) Math.round(h * (1.0 - d / radius));
        double mouth = radius * MOUTH_FRACTION;
        if (d > mouth) {
            return outside;
        }
        int bowl = (int) Math.round(rimHeight() - (mouth - d) * BOWL_SLOPE);
        return Math.max(1, Math.min(outside, bowl));
    }

    /** A cone's lip, where the outside slope meets the bowl. */
    public int rimHeight() {
        return (int) Math.round(height() * (1.0 - MOUTH_FRACTION));
    }

    /** Which material a column at this distance takes. */
    public Role roleAt(double d) {
        return switch (form) {
            case CRATER -> d <= radius * CORE_FRACTION ? Role.CORE
                    : d < radius ? Role.BODY : d <= outerRadius() ? Role.RIM : Role.NONE;
            case CONE -> d <= radius * MOUTH_FRACTION ? Role.CORE : d <= radius ? Role.BODY : Role.NONE;
            case MOUND -> d < radius ? Role.BODY : Role.NONE;
        };
    }

    /**
     * How deep a dig may really go where the ground is only {@code aboveFloor} blocks above the
     * bottom of the world: shallower, never through it. An earlier mod's deeper crater once dug a column out of
     * the world entirely — no floor, no bedrock — because a column that ran out of world abandoned
     * the lot instead of clamping.
     */
    public static int clampDig(int dig, int aboveFloor) {
        return Math.max(0, Math.min(dig, aboveFloor - 1));
    }

    /**
     * How much of the stamp shows at a share {@code f} (0..1) of its growth: a crater opens from the
     * middle out, a raised form builds from the ground up.
     */
    public boolean revealed(double d, int levelFromGround, double f) {
        if (form == Form.CRATER) {
            return d <= f * outerRadius();
        }
        return levelFromGround <= Math.ceil(f * height());
    }
}
