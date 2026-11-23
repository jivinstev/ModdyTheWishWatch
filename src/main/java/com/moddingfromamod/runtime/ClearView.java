package com.moddingfromamod.runtime;

/**
 * <b>Move a camera until it can actually SEE its subject</b> — and then move it back in so the
 * framing survives.
 *
 * <h2>Why this is its own class</h2>
 *
 * <p>All of this logic already existed, correct and hard-won, inside
 * {@code PhotographTool.moveCameraTo}. Every step of it has a measured failure behind it, quoted
 * below. But that method takes an {@code OrbitCamera.Shot} and teleports a {@code ServerPlayer},
 * which is the CREATURE orbit's shape — so the building and train path, {@code captureBuilding},
 * could not call it and never did. It sent the framing arithmetic's pose straight to the client.
 *
 * <p>The result: <b>buildings and trains were photographed through whatever was standing in the
 * way</b>, for the child's actual wish card, while creatures were not. The comment inside
 * {@code moveCameraTo} even claims the case —
 * <i>"a 100-block train is the worst case for this … but it is not train-specific, and fixing it
 * here rather than in TrainFraming fixes buildings and creatures with it"</i> — which was true
 * reasoning applied in a place the train path does not reach. {@code CLAUDE.md}'s rule for exactly
 * this: <b>a rule tested only where it is DEFINED is not tested where it RUNS.</b>
 *
 * <p>So the rule lives here, once, and both paths call it.
 *
 * <h2>Pure, so the rule is a unit test</h2>
 *
 * <p>The world is reached only through {@link Sight}, which is two questions: is this point inside
 * something, and is the segment between these two points clear. That keeps the geometry — which is
 * where every one of the bugs below lived — testable without booting a game, in line with the rest
 * of this codebase.
 */
public final class ClearView {

    /** How many one-block steps to try when the camera starts inside a block. */
    static final int MAX_LIFT = 6;

    /** How far above the camera's feet its eyes are checked when lifting out of a block. */
    static final double LIFT_PROBE = 0.6;

    /** How many rises to try before giving up on seeing the subject. */
    public static final int MAX_RISE = 24;

    /** How far each rise steps. {@code MAX_RISE * RISE_STEP} is 96 blocks — a mountain is tall. */
    public static final double RISE_STEP = 4.0;

    /** The two questions this rule asks the world. Everything else here is arithmetic. */
    public interface Sight {

        /** Is this point inside something solid? */
        boolean solid(double x, double y, double z);

        /** Is the segment from the eye to the aim point unobstructed? */
        boolean clear(double eyeX, double eyeY, double eyeZ,
                      double aimX, double aimY, double aimZ);
    }

    /**
     * Where the camera ended up.
     *
     * @param x      camera x
     * @param feetY  camera y as a PLAYER POSITION — feet, not eyes, because that is what both
     *               {@code connection.teleport} and {@code PhotoClient}'s {@code setPos} take.
     *               Getting this wrong moves every shot 1.62 blocks and nothing complains.
     * @param z      camera z
     * @param yaw    unchanged — the correction is vertical and then along the ray, so the bearing
     *               the framing chose is never overridden
     * @param pitch  RECOMPUTED, see below
     * @param rose   how far it had to CLIMB, in blocks, before the slide brought it back —
     *               not the net displacement; 0 when the first seat was already clear
     * @param slid   whether it came back down the ray afterwards
     * @param blind  it never found a clear line. The shot is still taken — a bad photograph beats
     *               none — but the caller should SAY so rather than report a clean run.
     */
    public record Seat(double x, double feetY, double z, float yaw, float pitch,
                       double rose, boolean slid, boolean blind) {
    }

    private ClearView() {
    }

    /**
     * Find a seat for a camera that was framed at {@code (camX, feetY, camZ)} looking at the aim
     * point, moving it as little as possible.
     *
     * <p>The four steps, each carrying its own scar from {@code moveCameraTo}:
     *
     * <ol>
     *   <li><b>Lift out of a block, and ONLY out of a block.</b> This used to raise the camera to
     *       the terrain height of its own column, which is wrong the moment the ground is not
     *       flat: a subject on a cliff edge or under a tree put the camera up on the ridge or in
     *       the canopy, and the frame came back as empty sky.</li>
     *   <li><b>Rise until the subject is visible.</b> Standing in air is not the same as being
     *       able to see it — a camera can be in clear air on the near side of a hill with the
     *       subject completely hidden, and every downstream check is satisfied, because a
     *       photograph of a hillside is a perfectly good photograph of something.</li>
     *   <li><b>Re-aim.</b> The pitch was computed for the height the framing wanted; lifting
     *       without recomputing leaves the camera looking along the old angle, which now passes
     *       over the subject's head.</li>
     *   <li><b>Slide back DOWN THE RAY.</b> Rising keeps the subject visible and throws the
     *       framing away — on one repro the camera went 96 blocks up, pitch 89°, and the subject
     *       was a speck that scored the best palette of its batch because no grader opens a
     *       photograph. ⚠ A horizontal pull-in does NOT fix this and was shipped once believing it
     *       did: it holds the horizontal projection of a distance whose vertical leg has already
     *       blown past it. Sliding along the ray {@link Sight#clear} has just proved is safe by
     *       construction, because a sub-segment of a clear segment is clear.</li>
     * </ol>
     */
    public static Seat find(Sight sight, double camX, double feetY, double camZ, float yaw,
                            double aimX, double aimY, double aimZ, double eyeHeight) {
        double y = feetY;

        // 1. out of a block, if we started in one
        for (int lift = 0; lift < MAX_LIFT && sight.solid(camX, y + LIFT_PROBE, camZ); lift++) {
            y += 1.0;
        }

        // 2. up until the subject is actually visible
        double startedAt = y;
        boolean blind = true;
        for (int rise = 0; rise < MAX_RISE; rise++) {
            if (sight.clear(camX, y + eyeHeight, camZ, aimX, aimY, aimZ)) {
                blind = false;
                break;
            }
            y += RISE_STEP;
        }

        // ⚠ RECORD THE CLIMB HERE, not at the return. The slide below moves `y` back down, so
        // `y - startedAt` computed at the end is the NET displacement, not how far it had to go up
        // — and a seat that climbed 52 blocks and slid 40 back would report "rose 12". That is the
        // number a caller uses to decide whether a shot is worth reporting as compromised, so it
        // has to mean what it says. Caught by a unit test, which is the argument for the whole
        // class being pure.
        double climbed = y - startedAt;
        double eyeY = y + eyeHeight;
        double flat = Math.hypot(camX - aimX, camZ - aimZ);
        boolean slid = false;

        // 4. back down the ray, to the range the framing asked for
        //
        // ⚠ THE SLANT RANGE IS THE NUMBER THAT DECIDES HOW BIG THE SUBJECT IS IN FRAME, and it is
        // measured from the pose we were GIVEN, not from wherever the rise ended.
        double askedSlant = Math.hypot(flat, aimY - (feetY + eyeHeight));
        double slant = Math.hypot(flat, eyeY - aimY);
        // ⚠ SLIDE EVEN WHEN BLIND, and that is deliberate rather than an oversight. A first cut
        // guarded this with `!blind`, which reads as caution and is the worse picture: a camera
        // that rose the full 96 blocks and still cannot see gets you a distant speck, whereas
        // sliding back to the asked range gets you a framed shot with something in front of it.
        // The `solid` guard below is what keeps it safe either way. It is also what the creature
        // orbit has always done, and this class must not change creature photographs while
        // claiming only to share them.
        if (slant > askedSlant + 0.01 && slant > 0.01) {
            double k = askedSlant / slant;
            double nx = aimX + (camX - aimX) * k;
            double nz = aimZ + (camZ - aimZ) * k;
            double nEyeY = aimY + (eyeY - aimY) * k;
            // STILL CHECK THE LANDING SPOT. `clear` deliberately accepts a ray that stops within a
            // few blocks of the aim point — hitting the subject itself IS seeing it — so the clear
            // segment is guaranteed only up to that tolerance, and a slide ending inside those
            // last blocks can end inside terrain. A small subject is a bad photograph; an interior
            // is not a photograph at all.
            if (!sight.solid(nx, nEyeY, nz)) {
                camX = nx;
                camZ = nz;
                eyeY = nEyeY;
                y = nEyeY - eyeHeight;
                flat = Math.hypot(camX - aimX, camZ - aimZ);
                slid = true;
            }
        }

        // 3. re-aim (after the slide, so it describes where the camera actually is)
        float pitch = (float) -Math.toDegrees(Math.atan2(aimY - eyeY, Math.max(0.001, flat)));
        return new Seat(camX, y, camZ, yaw, pitch, climbed, slid, blind);
    }
}
