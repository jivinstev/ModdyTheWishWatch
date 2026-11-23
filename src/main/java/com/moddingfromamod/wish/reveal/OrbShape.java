package com.moddingfromamod.wish.reveal;

/**
 * <b>Where each piece of a waiting wish's pillar of light is, at a given moment</b>.
 * Pure: no Minecraft, so the rules a child would notice are unit-tested rather than eyeballed.
 *
 * <p>The reference design is a thick, bright pillar of light with blocks spiralling up
 * around it in a double helix and lightning crackling between them, "swirling a little tighter
 * around the pillar, so the pillar is a lot more visible at a distance". Drawn in display entities,
 * not particles: a display is a real object the client draws as far as its view range says, and it
 * survives "decreased particles". The pieces:
 * <ul>
 *   <li>a <b>pillar</b>: a white core inside a cyan halo and a faint haze, flat light that always faces you, rising
 *   {@link #PILLAR_HEIGHT} from the spot the wish was aimed at;</li>
 *   <li>a <b>double helix</b> of glowing blocks at {@link #HELIX_RADIUS}, close in so it reads as part
 *   of the pillar from far away. It CLIMBS as the plan is done: a fresh wish shows the bottom turn,
 *   a finished one the whole spiral ({@link #visible}), and it turns faster as it nears done;</li>
 *   <li><b>arcs</b> of light flickering between the pillar and the helix blocks;</li>
 *   <li>a glowing <b>star</b> on the ground at its foot.</li>
 * </ul>
 * The reveal still starts at {@link #ORB_HEIGHT} up the pillar: head height, where he is looking.
 */
public final class OrbShape {

    /** Where on the pillar a reveal starts: head height above the locked spot. */
    public static final double ORB_HEIGHT = 3.2;

    /** The pillar: height above the ground, and the widths of its white core, cyan halo and haze. */
    public static final float PILLAR_HEIGHT = 48f;
    public static final float CORE_WIDTH = 0.8f;
    public static final float HALO_WIDTH = 1.8f;
    public static final float HAZE_WIDTH = 3.0f;

    /** The helix: two strands, this many blocks each, this close to the pillar's centre. */
    public static final int STRANDS = 2;
    public static final int PER_STRAND = 14;
    public static final double HELIX_RADIUS = 1.7;
    public static final double HELIX_BOTTOM = 1.0;
    public static final double HELIX_TOP = 17.0;
    /** Full turns each strand makes from bottom to top. */
    public static final double HELIX_TURNS = 2.5;
    /** Edge of one helix block. */
    public static final float HELIX_BLOCK = 0.55f;

    /** How many arcs crackle at once, at most, and how thick each is. */
    public static final int ARCS = 5;
    public static final float ARC_WIDTH = 0.11f;

    /** The star on the ground: edge of each of its two squares. */
    public static final float STAR = 3.6f;

    /** Radians per tick the helix turns: slow at the start, faster as the wish nears done. */
    static final double SPIN_SLOW = 0.05;
    static final double SPIN_FAST = 0.13;

    private OrbShape() {
    }

    /** How fast the helix turns at this fraction done, in radians per tick. */
    public static double spinRate(float fraction) {
        float f = Math.max(0f, Math.min(1f, fraction));
        return SPIN_SLOW + (SPIN_FAST - SPIN_SLOW) * f;
    }

    /**
     * How many blocks of each strand are up, at this fraction done: the bottom fifth from the
     * start (so a fresh wish already spirals), climbing to every block when the wish is done.
     */
    public static int visible(int perStrand, float fraction) {
        float f = Math.max(0f, Math.min(1f, fraction));
        int n = (int) Math.ceil(perStrand * (0.2 + 0.8 * f) - 1e-6);
        return Math.max(1, Math.min(perStrand, n));
    }

    /**
     * Block {@code i} of strand {@code strand}, with the helix turned to {@code angle}: its offset
     * from the pillar's foot, {x, y, z}. The two strands sit opposite each other.
     */
    public static double[] helix(int strand, int i, double angle) {
        double t = PER_STRAND <= 1 ? 0 : (double) i / (PER_STRAND - 1);
        double a = angle + Math.PI * strand + t * HELIX_TURNS * Math.PI * 2;
        double y = HELIX_BOTTOM + t * (HELIX_TOP - HELIX_BOTTOM);
        return new double[]{Math.cos(a) * HELIX_RADIUS, y, Math.sin(a) * HELIX_RADIUS};
    }

    /** The gentle breath the old orb had; kept for anything still floating at head height. */
    public static double bob(long tick) {
        return Math.sin(tick * 0.08) * 0.25;
    }

    /**
     * Ticks a pillar takes to glide to a corrected spot: long enough to watch, short enough
     * that it is there before he looks away. Grows a little with distance, capped.
     */
    public static int glideTicks(double distance) {
        return (int) Math.max(24, Math.min(50, 20 + distance * 0.6));
    }

    /**
     * Where a gliding pillar's foot is a fraction {@code t} (0..1) of the way from {@code from} to
     * {@code to}: eased in and out, and lifted in an arc so it visibly TRAVELS rather than sliding
     * through a hill: a move must be animated, never torn down and rebuilt.
     */
    public static double[] glide(double[] from, double[] to, double t) {
        double u = Math.max(0, Math.min(1, t));
        double e = u * u * (3 - 2 * u);
        double dx = to[0] - from[0], dy = to[1] - from[1], dz = to[2] - from[2];
        double lift = Math.min(10, 2 + Math.sqrt(dx * dx + dy * dy + dz * dz) / 3) * Math.sin(Math.PI * u);
        return new double[]{from[0] + dx * e, from[1] + dy * e + lift, from[2] + dz * e};
    }
}
