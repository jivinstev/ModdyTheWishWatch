package com.moddingfromamod.event.shape;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * <b>{@code spreading_cracks}: lines that open in the ground and spread</b> (E2). Pure and
 * seeded, so a crack is the same crack on a replay and in a test.
 *
 * <p>From a mod repo's earthquake: each crack WANDERS as it runs, because a straight line reads as a trench
 * somebody dug; it varies in depth; and it spreads over time rather than appearing whole.
 */
public final class CrackShape {

    /** A step of a crack: a column offset from the centre, and how deep it opens there. */
    public record Step(int dx, int dz, int depth, int order) {
    }

    public static final double WANDER = 0.6;
    public static final int MIN_DEPTH = 2;

    public final int cracks;
    public final int length;
    public final int maxDepth;

    public CrackShape(int cracks, int length, int maxDepth) {
        if (cracks < 1 || length < 2 || maxDepth < MIN_DEPTH) {
            throw new IllegalArgumentException("a crack needs a count, a length and a depth");
        }
        this.cracks = cracks;
        this.length = length;
        this.maxDepth = maxDepth;
    }

    /** Every step of every crack, in the order they open ({@link Step#order}: steps from the centre). */
    public List<Step> steps(long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        List<Step> out = new ArrayList<>();
        for (int c = 0; c < cracks; c++) {
            // Spread the starting directions round the compass, then let each one wander.
            double angle = (c + r.nextDouble() * 0.6) * (2 * Math.PI / cracks);
            double x = 0.5, z = 0.5;
            int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
            for (int s = 0; s < length; s++) {
                angle += (r.nextDouble() - 0.5) * WANDER;
                x += Math.cos(angle);
                z += Math.sin(angle);
                int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
                if (bx == lastX && bz == lastZ) {
                    continue;
                }
                lastX = bx;
                lastZ = bz;
                int depth = MIN_DEPTH + r.nextInt(maxDepth - MIN_DEPTH + 1);
                out.add(new Step(bx, bz, depth, s));
            }
        }
        return out;
    }
}
