package com.moddingfromamod.runtime;

import java.util.List;

/**
 * How far back to stand to actually SEE a creature.
 *
 * <p>This exists because the agent checks its own work by photographing it, and a photo it can't
 * read is worse than none — it looks like a successful check. The first attempt placed every
 * creature two blocks away and the resulting picture was a wall of colour.
 *
 * <p>The second attempt scaled the distance by the species' {@code scale}, which is still wrong: a
 * creature <b>built</b> 80 units tall at scale 1.0 stands five blocks high, and guessing from the
 * multiplier alone treats it as two. Apparent size is the model's own height <i>times</i> the scale,
 * so both have to be in the sum.
 *
 * <p>The distance itself comes from the camera rather than taste: to fit something of height
 * {@code h} into a vertical field of view {@code f}, you need {@code (h/2) / tan(f/2)}. Minecraft's
 * default FOV is 70°, so that's about {@code 0.71·h} — then some margin, because a creature exactly
 * filling the frame edge-to-edge is as hard to judge as one too close.
 *
 * <p>Pure, so the geometry is unit-tested instead of eyeballed one screenshot at a time.
 */
public final class Framing {

    private Framing() {
    }

    /** Minecraft's default vertical field of view, in degrees. */
    public static final double DEFAULT_FOV_DEGREES = 70.0;

    /** Leave the subject filling ~70% of the frame, not 100%. */
    private static final double MARGIN = 1.4;

    /** Never stand closer than this, however tiny the creature. */
    public static final double MIN_DISTANCE = 3.0;

    /** Never stand further than this, however enormous — beyond it, detail is gone anyway. */
    public static final double MAX_DISTANCE = 40.0;

    /** Bedrock units per block. */
    private static final double UNITS_PER_BLOCK = 16.0;

    /**
     * The height of a sculpted creature, in bedrock units — the top of its tallest box. Feet sit at
     * y=0 by convention, so the maximum is the height.
     */
    public static float modelHeightUnits(List<CreatureSculpt.Part> parts) {
        float top = 0f;
        if (parts != null) {
            for (CreatureSculpt.Part p : parts) {
                top = Math.max(top, p.to()[1]);
            }
        }
        return top;
    }

    /** The same measurement taken from already-written geometry rather than from parts. */
    public static float modelHeightUnits(BedrockGeometry geometry) {
        float top = 0f;
        if (geometry != null) {
            for (BedrockGeometry.Bone b : geometry.bones()) {
                for (BedrockGeometry.Cube c : b.cubes()) {
                    top = Math.max(top, c.origin()[1] + c.size()[1]);
                }
            }
        }
        return top;
    }

    /**
     * How far the model reaches SIDEWAYS (the larger of its X and Z extents, in model units). A spider is low and
     * wide: framed on its height alone, the wish camera stood inside its legs (F1, the tarantula, 2026-11-09).
     */
    public static float modelSpanUnits(BedrockGeometry geometry) {
        float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, z0 = Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
        if (geometry != null) {
            for (BedrockGeometry.Bone b : geometry.bones()) {
                for (BedrockGeometry.Cube c : b.cubes()) {
                    x0 = Math.min(x0, c.origin()[0]);
                    x1 = Math.max(x1, c.origin()[0] + c.size()[0]);
                    z0 = Math.min(z0, c.origin()[2]);
                    z1 = Math.max(z1, c.origin()[2] + c.size()[2]);
                }
            }
        }
        return x1 < x0 ? 0f : Math.max(x1 - x0, z1 - z0);
    }

    /**
     * How much further back than its height alone would put it a camera must stand to fit a creature that is
     * {@code spanBlocks} across: 1 for a creature about as wide as it is tall, more for a wide one. A wide subject is
     * turned into the height that fills a 16:9 frame the same, as {@code OrbitCamera.distanceForBuild} does.
     */
    public static double widthScale(double heightBlocks, double spanBlocks) {
        double h = Math.max(0.6, heightBlocks);
        double effective = Math.max(h, spanBlocks / (16.0 / 9.0) * 1.15);
        return Math.min(4.0, Math.max(1.0, effective / h));
    }

    /** Apparent height in blocks: what it was built as, times how much it's scaled up. */
    public static double heightInBlocks(float modelHeightUnits, double scale) {
        double h = (modelHeightUnits / UNITS_PER_BLOCK) * Math.max(0.01, scale);
        // A creature with no geometry still needs a sane number rather than zero.
        return h <= 0 ? 1.0 : h;
    }

    /**
     * How far in front of the camera to stand something of this height so it fits comfortably.
     *
     * @param heightBlocks apparent height, from {@link #heightInBlocks}
     */
    public static double distanceFor(double heightBlocks) {
        double halfFov = Math.toRadians(DEFAULT_FOV_DEGREES / 2.0);
        double fitExactly = (heightBlocks / 2.0) / Math.tan(halfFov);
        double comfortable = fitExactly * MARGIN;
        return Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, comfortable));
    }

    /** Convenience: straight from the parts and the species scale to a distance. */
    public static double distanceFor(List<CreatureSculpt.Part> parts, double scale) {
        return distanceFor(heightInBlocks(modelHeightUnits(parts), scale));
    }

    /**
     * Would something this tall fit in frame from this distance? Used to tell the agent honestly
     * that its photo is too close to judge, rather than letting it draw conclusions from a wall of
     * colour.
     */
    public static boolean fitsInFrame(double heightBlocks, double distance) {
        double halfFov = Math.toRadians(DEFAULT_FOV_DEGREES / 2.0);
        double visibleHeight = 2.0 * distance * Math.tan(halfFov);
        return visibleHeight >= heightBlocks;
    }
}
