package com.moddingfromamod.event.shape;

import java.util.SplittableRandom;

/**
 * <b>{@code from_the_sky}: things dropped over an area</b> (E2). Pure and seeded.
 *
 * <p>Hail, raining chickens, falling snow, a shower of presents. The drops are spread over the whole
 * area (a column-by-column sweep reads as a machine, not weather) and fall from a height the viewer can
 * see: an earlier mod dropped hail from high enough to hit a roof and low enough to watch it come.
 */
public final class SkyDrop {

    public static final int HEIGHT = 18;

    /** One drop: an offset from the centre, inside the radius. */
    public record Drop(double dx, double dz) {
    }

    public final int radius;
    public final int perTick;
    public final int ticks;

    public SkyDrop(int radius, int perTick, int ticks) {
        if (radius < 1 || perTick < 1 || ticks < 1) {
            throw new IllegalArgumentException("a sky drop needs an area, a rate and a length");
        }
        this.radius = radius;
        this.perTick = perTick;
        this.ticks = ticks;
    }

    /**
     * How many of {@code most} things, spread evenly over {@code ticks}, are due on tick {@code age}.
     * Over the whole shower it sums to exactly {@code most} (for {@code most} up to what can be made).
     */
    public static int dueAt(int most, int ticks, int age) {
        if (age < 0 || age >= ticks) {
            return 0;
        }
        return (int) ((long) (age + 1) * most / ticks - (long) age * most / ticks);
    }

    public int total() {
        return perTick * ticks;
    }

    /** The drops of one tick, uniformly spread over the disc. */
    public Drop[] at(long seed, int tick) {
        SplittableRandom r = new SplittableRandom(seed * 31 + tick);
        Drop[] out = new Drop[perTick];
        for (int i = 0; i < perTick; i++) {
            // sqrt for a UNIFORM spread: plain r * random() piles everything in the middle.
            double rr = radius * Math.sqrt(r.nextDouble());
            double a = r.nextDouble() * 2 * Math.PI;
            out[i] = new Drop(Math.cos(a) * rr, Math.sin(a) * rr);
        }
        return out;
    }
}
