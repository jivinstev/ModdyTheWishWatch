package com.moddingfromamod.event.shape;

/**
 * <b>Where each one of a crowd stands</b>. Pure: no Minecraft import.
 *
 * <p>Three ways a child says it: "a herd of horses HERE" (bunched at the spot), "a ring of snow
 * golems" (round the spot, facing out), "ten cats AROUND ME" (a ring with him in the middle). A crowd
 * is spaced so its members do not spawn inside each other: a mob spawned on top of another shoves
 * it, and twenty in one block read as one blob that explodes outwards.
 */
public final class CrowdShape {

    private CrowdShape() {
    }

    /** Blocks between neighbours: room for a horse, not so far that a herd reads as strays. */
    public static final double SPACING = 1.6;
    /** The smallest ring: a ring of three round him must still be a ring, not a scrum at his feet. */
    public static final double MIN_RING = 3.0;
    /** The largest any crowd spreads, whatever the count: it stays a place he can see at once. */
    public static final double MAX_RADIUS = 16.0;
    private static final double GOLDEN = Math.PI * (3 - Math.sqrt(5));

    /** A spot relative to the centre, and the way that one faces (degrees, Minecraft yaw). */
    public record Spot(double dx, double dz, float yaw) {
    }

    /** How far the crowd reaches from its centre, for the camera. */
    public static double radius(int n, boolean ring) {
        if (ring) {
            return Math.min(MAX_RADIUS, Math.max(MIN_RING, n * SPACING / (2 * Math.PI)));
        }
        return Math.min(MAX_RADIUS, SPACING * Math.sqrt(Math.max(1, n)));
    }

    /**
     * Where member {@code i} of {@code n} stands. A cluster is a sunflower spiral (even spacing, no
     * rows), a ring is evenly round its circle; both face away from the middle, so a ring round him
     * looks outward like a guard and a herd does not all stare at one block.
     */
    public static Spot spot(int i, int n, boolean ring) {
        double a, r;
        if (ring) {
            a = 2 * Math.PI * i / Math.max(1, n);
            r = radius(n, true);
        } else {
            a = i * GOLDEN;
            r = Math.min(MAX_RADIUS, SPACING * Math.sqrt(i + 0.5));
        }
        double dx = Math.cos(a) * r, dz = Math.sin(a) * r;
        // Minecraft yaw: 0 faces +z (south), 90 faces -x; outward from the middle is atan2(-dx, dz).
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        return new Spot(dx, dz, yaw);
    }

    /** How many arrive by this tick: all of them over {@code ticks}, the first at once. */
    public static int dueBy(int age, int n, int ticks) {
        return (int) Math.min(n, Math.ceil(n * (age + 1) / (double) Math.max(1, ticks)));
    }
}
