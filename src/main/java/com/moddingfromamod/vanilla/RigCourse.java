package com.moddingfromamod.vanilla;

/**
 * <b>Which way a FLYING creature is going</b>, from where it has been. Pure.
 *
 * <p>A flier rides a bat, and a bat's yaw is its velocity's direction, rewritten by its own AI
 * every tick. Hovering, that velocity is flapping: measured in {@code RigPowersGameTest}, the rig
 * drawn from it turned 6,200 degrees in 200 ticks and half a turn in one. So a flier's heading is
 * the direction it has actually been TRAVELLING, and stays that while it flutters about one spot.
 *
 * <p>Travelling is told from fluttering by how STRAIGHT the last {@link #WINDOW} positions are: the
 * net way covered must be at least {@link #MIN_TRAVEL} blocks and {@link #STRAIGHT} of the path
 * walked. (it used to take the chord of every {@link #STEP} blocks, and on the fly stage's
 * tight loop that chord spans 77 degrees of arc, so the drawn body faced 97 degrees off its travel
 * on average and 117 at worst: it flew sideways, which is what the owner saw on the film.)
 */
public final class RigCourse {

    /** The size of a flutter the heading ignores (a hover jitters within about this many blocks). */
    public static final double STEP = 2.0;
    /**
     * How many positions the heading looks back over: one a TICK (VanillaRigAnimator drives every tick), so 24 is 1.2 s.
     * (At 8 the window was 8 ticks, the fly-stage loop covered 1 block of it, and on a phone the flier never turned.)
     */
    static final int WINDOW = 24;
    /** The least net travel over the window, blocks, that is a heading. */
    static final double MIN_TRAVEL = 2.0;
    /**
     * The net travel over the window, blocks, above which it turns at a MOVING creature's pace ({@link #travelling}):
     * about a block a second, for a whole window running ({@link #WINDOW} samples). A hover drifting toward a child
     * heads its way at the standing pace, and so does a bat's flit, which is brisk and straight but short
     * (RigPowersGameTest's hover turned 24 degrees in one tick at the moving pace, at 0.5 blocks and at 1.2).
     */
    static final double BRISK = 2.2;
    /** The least net travel over the path walked in the window: a loop's arc is ~0.99, a flutter far less. */
    static final double STRAIGHT = 0.8;

    private final double[] xs = new double[WINDOW];
    private final double[] zs = new double[WINDOW];
    private int count;
    private int next;
    private float heading = Float.NaN;

    /** Start again from here, heading {@code yaw} (the first tick, a teleport). */
    public float reset(double x, double z, float yaw) {
        count = 0;
        next = 0;
        travelling = false;
        brisk = 0;
        push(x, z);
        heading = yaw;
        return heading;
    }

    private void push(double x, double z) {
        xs[next] = x;
        zs[next] = z;
        next = (next + 1) % WINDOW;
        count = Math.min(WINDOW, count + 1);
    }

    /** The heading with the creature now at {@code (x, z)}; {@code yaw} is used only at first. */
    public float at(double x, double z, float yaw) {
        if (count == 0 || Float.isNaN(heading)) {
            return reset(x, z, yaw);
        }
        push(x, z);
        if (count < 3) {
            return heading;
        }
        int oldest = (next - count + WINDOW) % WINDOW;
        double walked = 0;
        for (int i = 1; i < count; i++) {
            int a = (oldest + i - 1) % WINDOW;
            int b = (oldest + i) % WINDOW;
            walked += Math.hypot(xs[b] - xs[a], zs[b] - zs[a]);
        }
        int newest = (next - 1 + WINDOW) % WINDOW;
        double dx = xs[newest] - xs[oldest];
        double dz = zs[newest] - zs[oldest];
        double net = Math.hypot(dx, dz);
        boolean heads = net >= MIN_TRAVEL && net >= STRAIGHT * walked;
        brisk = heads && net >= BRISK ? brisk + 1 : 0;
        travelling = brisk >= WINDOW;
        if (heads) {
            // The chord points along the middle of the window, half a window behind on a curve; the turn between the
            // window's two halves is how far it turned in that half window, so it is added on (none on a straight).
            int mid = (oldest + count / 2) % WINDOW;
            double a1 = Math.atan2(zs[mid] - zs[oldest], xs[mid] - xs[oldest]);
            double a2 = Math.atan2(zs[newest] - zs[mid], xs[newest] - xs[mid]);
            double turned = Math.IEEEremainder(a2 - a1, 2 * Math.PI);
            heading = (float) ((Math.atan2(dz, dx) + turned) * 180.0 / Math.PI) - 90f;
        }
        return heading;
    }

    private boolean travelling;
    /** Samples in a row it has been travelling briskly. */
    private int brisk;

    /**
     * The yaw a flier is DRAWN at, from its heading {@code course} ({@link #at}): travelling briskly, the heading itself
     * (it is already smooth, a window of {@link #WINDOW} runs; eased by RigTurn as well it trailed a loop by a constant
     * 16.5 degrees, FlyStageHeadingTest); drifting or fluttering, turned toward it at the standing pace.
     */
    public float drawn(RigTurn turn, float course) {
        if (!travelling) {
            return turn.step(course, false);
        }
        float was = turn.drawn();
        if (Float.isNaN(was)) {
            return turn.snap(course);
        }
        // never more than RigTurn.MAX in a tick: on the tick it starts travelling it has the standing pace's lag to
        // catch up, and snapping it there was one 15-degree jump
        float d = RigHeadYaw.wrap(course - was);
        return turn.snap(was + Math.max(-RigTurn.MAX, Math.min(RigTurn.MAX, d)));
    }

    /** Whether the last {@link #at} saw it travelling briskly ({@link #BRISK}), not drifting or fluttering about one spot. */
    public boolean travelling() {
        return travelling;
    }
}
