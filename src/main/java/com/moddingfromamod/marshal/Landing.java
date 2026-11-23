package com.moddingfromamod.marshal;

/**
 * Where to put a child who asked to be taken to his wish — the arithmetic, with no game in it.
 *
 * <p>Getting there is the easy half. These are the parts that bite:
 *
 * <ul>
 *   <li><b>Never land inside the build.</b> The volume is being written at 8,000 blocks a tick, so
 *       arriving inside it means suffocating in a wall that did not exist a tick ago. He is stood
 *       {@link #STAND_OFF} blocks back, at the anchor's eye level, <i>facing</i> it.</li>
 *   <li><b>Always be able to get home.</b> One remembered departure point per player. A child
 *       teleported 3,000 blocks from his base and unable to find it again is a session-ending
 *       disaster, and it is entirely preventable.</li>
 * </ul>
 *
 * <p>Pure so the geometry is a unit test: "does he end up outside the build volume, looking at it"
 * is a question about numbers, and answering it in a real world means building a real castle first.
 */
public final class Landing {

    /**
     * How far back to stand him.
     *
     * <p>Six is not arbitrary: a build anchored at a spot grows <i>from</i> it, so standing on the
     * anchor is standing in the foundations. Six blocks is outside anything small, far enough that a
     * big thing is framed rather than filling the screen, and close enough that he does not have to
     * go looking.
     */
    public static final int STAND_OFF = 6;

    /** Roughly eye height, so he is looking at the thing rather than at its feet. */
    public static final int EYE_LIFT = 2;

    /**
     * How long {@code [take me back]} stays on offer after he lands.
     *
     * <p>A minute, because the promise is "you can always get home", not "you have five seconds to
     * decide". Timed against an injected clock, never ticks.
     */
    public static final long WAY_HOME_MS = 60_000L;

    /** Where he ends up, and which way he is looking. */
    public record Spot3(double x, double y, double z, float yaw, float pitch) {
    }

    private Landing() {
    }

    /**
     * Stand him off the anchor, on the side he is coming from, facing it.
     *
     * <p>Approaching from his current position rather than from a fixed compass direction matters:
     * a fixed offset drops him on the far side of a hill as often as not, and worse, it is the same
     * spot every time so two wishes in the same place put him inside each other.
     *
     * @param fromX where he is now — only the direction is used
     */
    public static Spot3 standOff(int anchorX, int anchorY, int anchorZ,
                                 double fromX, double fromZ) {
        double dx = fromX - anchorX;
        double dz = fromZ - anchorZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.001) {
            // He is already standing on it. Any direction will do, but it must be a REAL direction:
            // a zero vector would leave him exactly on the anchor, which is the one spot to avoid.
            dx = 1;
            dz = 0;
            length = 1;
        }
        double ux = dx / length;
        double uz = dz / length;
        double x = anchorX + 0.5 + ux * STAND_OFF;
        double z = anchorZ + 0.5 + uz * STAND_OFF;
        return new Spot3(x, anchorY + EYE_LIFT, z, yawTowards(x, z, anchorX + 0.5, anchorZ + 0.5), 0f);
    }

    /**
     * The yaw that looks from one point at another.
     *
     * <p>Minecraft's yaw is measured from south and grows clockwise, which is why this is
     * {@code atan2(-dx, dz)} and not the atan2 anyone writes from memory. Getting it wrong lands him
     * facing exactly away from the thing he asked to see — which reads as the teleport going to the
     * wrong place.
     */
    public static float yawTowards(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        return (float) (Math.toDegrees(Math.atan2(-dx, dz)));
    }

    /** True when a spot is inside the box a build of this size occupies around its anchor. */
    public static boolean insideBuild(double x, double y, double z,
                                      int anchorX, int anchorY, int anchorZ, int radius) {
        return Math.abs(x - anchorX) <= radius
                && Math.abs(z - anchorZ) <= radius
                && y >= anchorY - 1 && y <= anchorY + radius;
    }

    /** Is the way home still on offer? */
    public static boolean wayHomeOpen(long departedMs, long nowMs) {
        return departedMs > 0 && nowMs - departedMs <= WAY_HOME_MS;
    }
}
