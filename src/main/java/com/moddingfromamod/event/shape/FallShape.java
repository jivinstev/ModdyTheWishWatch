package com.moddingfromamod.event.shape;

/**
 * <b>{@code falling_body}: something coming down where he can see it</b> (E2; reworked E6). Pure.
 *
 * <p>An earlier mod's meteor was reported twice as "we can never see where it's crashing down". The maths was
 * never the problem; the body was: it started so far out that it was drawn into chunks the server had
 * not loaded, i.e. it did not exist for most of its fall. So the rules are:
 * <ul>
 *   <li>it comes in from BEYOND the target, falling toward him, so the whole fall is in front of him
 *       and never over his head;</li>
 *   <li>it comes in on a steep slant, derived rather than a fixed height;</li>
 *   <li>every point of the fall stays within {@link #VISIBLE_RANGE} of him;</li>
 *   <li>it takes long enough to be watched: at least {@link #MIN_FALL_TICKS}.</li>
 * </ul>
 *
 * <p>E6's films are why it comes from beyond. It used to start on HIS side of the target and fall away,
 * which is only comfortable to look at if the target is far off (closer, the start swings overhead);
 * so it landed 48 blocks out, where a crater on flat ground is a thin dark line he cannot see into, and
 * the fall was over in two seconds. From beyond, it can land close enough to look into and still start
 * low in his sky.
 */
public final class FallShape {

    public static final int VISIBLE_RANGE = 128;
    public static final int MAX_AIM = 96;
    /** The nearest a body may be aimed: close enough to look into what it leaves, clear of him. */
    public static final int MIN_AIM = 26;
    /** The steepest he should have to look up to see it start. */
    public static final double MAX_COMFORTABLE_PITCH = 55.0;
    /** Low enough that it starts inside his view looking ahead; at 58° it began off the top of the screen. */
    public static final double APPROACH_DEGREES = 45.0;
    public static final double LEAN_FRACTION = 1.6;
    public static final int MIN_LEAN = 16;
    public static final int MAX_LEAN = 44;
    /**
     * Blocks it falls a tick. It stays quick on purpose: at 0.8 a block ball filmed as a stack of old
     * balls, because the client redraws every chunk section it sits in and a slow one sits in each for
     * longer. Starting higher is what makes the fall long enough to watch, not slowing it down.
     */
    public static final double SPEED = 2.0;
    /** The shortest fall worth watching, in ticks. */
    public static final int MIN_FALL_TICKS = 25;

    public record Point(double x, double y, double z) {
        double dist(Point o) {
            return Math.sqrt((o.x - x) * (o.x - x) + (o.y - y) * (o.y - y) + (o.z - z) * (o.z - z));
        }

        double flat(Point o) {
            return Math.hypot(o.x - x, o.z - z);
        }
    }

    private FallShape() {
    }

    /** Where it starts: beyond the target, up on the slant, pulled in until it is all in sight. */
    public static Point start(Point viewer, Point target) {
        double away = viewer.flat(target);
        double lean = Math.max(MIN_LEAN, Math.min(MAX_LEAN, away * LEAN_FRACTION));
        double ux = 0, uz = 1;
        if (away >= 1e-3) {
            ux = (target.x - viewer.x) / away;
            uz = (target.z - viewer.z) / away;
        }
        double tan = Math.tan(Math.toRadians(APPROACH_DEGREES));
        Point s = null;
        for (double l = lean; l >= 4; l -= 2) {
            s = new Point(target.x + ux * l, target.y + l * tan, target.z + uz * l);
            if (viewer.dist(s) <= VISIBLE_RANGE - 4) {
                return s;
            }
        }
        return s;
    }

    /** How far away to aim, given how far away he pointed. */
    public static double aimRange(double pointed) {
        return Math.max(MIN_AIM, Math.min(MAX_AIM, pointed));
    }

    /** Where it is a share {@code t} (0..1) of the way down. */
    public static Point at(Point start, Point target, double t) {
        double c = Math.max(0, Math.min(1, t));
        return new Point(start.x + (target.x - start.x) * c, start.y + (target.y - start.y) * c,
                start.z + (target.z - start.z) * c);
    }

    public static int ticks(Point start, Point target) {
        return Math.max(MIN_FALL_TICKS, (int) Math.ceil(start.dist(target) / SPEED));
    }

    /** Is every point of the fall within sight of the viewer? */
    public static boolean inSight(Point viewer, Point start, Point target) {
        for (int i = 0; i <= 20; i++) {
            if (viewer.dist(at(start, target, i / 20.0)) > VISIBLE_RANGE) {
                return false;
            }
        }
        return true;
    }

    /** Is this offset part of a hollow shell of radius {@code r}? Hollow: a solid ball is twice the writes for the same outside. */
    public static boolean shell(int dx, int dy, int dz, int r) {
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return d <= r + 0.5 && d > r - 1.0;
    }
}
