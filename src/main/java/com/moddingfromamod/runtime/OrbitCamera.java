package com.moddingfromamod.runtime;

/**
 * <b>Where to stand to photograph a creature from four sides.</b>
 *
 * <h2>The bug this exists to fix</h2>
 *
 * <p>{@code finish_building} hands the agent <b>one</b> photograph, and it judges the whole wish
 * from it. A spawned mob inherits the spawning player's yaw and mob AI ignores whoever is watching,
 * so <i>which way it happens to be pointing is arbitrary</i>. Measured in the eval harness, every
 * "front" photograph it took was of the creature's <b>back</b> until this was fixed there — the
 * first fully-built Spikor shot is a picture of a shell. The agent then judged, and reported to the
 * child, from that.
 *
 * <h2>Move the camera, never the subject</h2>
 *
 * <p>The obvious alternative — turn the creature to face the camera — works and is wrong twice
 * over. It mutates the thing being measured, and a mob whose facing has been forced is not the mob
 * the child meets. It also only ever yields one good angle. Four stops 90° apart touch nothing, and
 * the face is in frame whatever the creature decided to do.
 *
 * <p>The camera is a <i>child</i> being borrowed, which is only acceptable because
 * {@code WishCapture} already records where they were standing and puts them back. Outside that,
 * yanking a child around the world to frame a photo would be ruder than a photo that needs a
 * second try.
 *
 * <p>⚠ <b>That caveat is now the whole story, and this class is why.</b> The single fallback shot
 * kept a stricter rule — a child was never moved for it — while THIS path had been flying him to
 * four seats and putting him back for every creature wish card since it was written. The two agree
 * again: see {@link AimPlan}, which no longer asks who is looking.
 *
 * <h2>The sign that is easy to get backwards</h2>
 *
 * <p>A player with yaw <i>Y</i> looks along {@code (-sin Y, cos Y)}. So a camera that is to
 * <i>look at</i> the creature must sit at {@code centre + R * (sin Y, -cos Y)} — the opposite.
 * Getting it the wrong way round puts the camera on the far side pointing away, which photographs
 * an empty stage very convincingly. {@code OrbitCameraTest} pins it.
 *
 * <p>Pure and Minecraft-free.
 */
public final class OrbitCamera {

    private OrbitCamera() {
    }

    /** Four stops, 90° apart. Enough that no creature can hide its face; few enough to be quick. */
    public static final int STOPS = 4;

    /** Bearings in degrees about the world, in the order they are shot. */
    public static double bearing(int stop) {
        return (stop % STOPS) * (360.0 / STOPS);
    }

    /**
     * <b>The bearing that looks a creature in the FACE</b>, given which way it is currently turned.
     *
     * <p>Fixed world bearings photograph whichever side the creature happens to present, so a
     * head-on shot is luck: four stops 90° apart can all miss a face by 45°, and a reviewer of nine
     * creatures found several with no usable front view. The mob's yaw is readable at runtime, so
     * the orbit does not have to guess — stop 0 is the face by construction, and the other three
     * are its profile, back and other profile.
     *
     * <p>The arithmetic, which is easy to get backwards: a camera at yaw <i>B</i> looks along
     * {@code (-sin B, cos B)} and therefore stands at {@code centre + R*(sin B, -cos B)}. An entity
     * at yaw <i>Y</i> faces {@code (-sin Y, cos Y)}. For the camera to stand in front of the
     * creature those must be equal, which gives <b>B = Y + 180</b>.
     *
     * <p>This also removes the reason to shoot eight angles at 45°: doubling the stops is a way of
     * covering for not knowing where the face is, and we know.
     */
    public static double faceBearing(double mobYawDegrees, int stop) {
        return ((mobYawDegrees + 180.0 + bearing(stop)) % 360.0 + 360.0) % 360.0;
    }

    /**
     * <b>All four bearings, from ONE reading of the creature's facing.</b>
     *
     * <p>The orbit used to call {@link #faceBearing} once per stop with the creature's LIVE yaw,
     * so a creature that turned between stops dragged every later stop round with it: measured in
     * a real wish, the four stops were shot at 180°, 186°, 346° and 135° — "left" six degrees from
     * the face, two sides never seen. Read the facing once, and the four sides are four sides.
     */
    public static double[] lockedBearings(double mobYawDegrees) {
        double[] out = new double[STOPS];
        for (int stop = 0; stop < STOPS; stop++) {
            out[stop] = faceBearing(mobYawDegrees, stop);
        }
        return out;
    }

    /**
     * How far back to stand.
     *
     * <p><b>Derived, not tuned.</b> Minecraft's default vertical FOV is 70°, so the visible height
     * at distance <i>d</i> is {@code 2*d*tan(35°) ≈ 1.4d}. For the creature to fill a fraction
     * <i>F</i> of the frame, {@code d = h / (1.4F)}; at F = 0.55 that is {@code h * 1.3}.
     *
     * <p>This matters more than it looks. A tuned {@code h * 2.4 + 2} held no fraction at all — it
     * gave 11% of frame for a small creature and 27% for a huge one, so photographs of different
     * creatures were not comparable, and every one of them was mostly sky.
     */
    public static double distanceFor(double heightBlocks) {
        return Math.max(2.2, Math.min(26.0, Math.max(0.6, heightBlocks) * 1.3));
    }

    /**
     * How far back to stand to photograph a BUILDING.
     *
     * <p>Same derivation, two changes — both of which are the difference between a photograph of a
     * castle and a photograph of one of its walls.
     *
     * <ul>
     *   <li><b>Width counts, not just height.</b> A creature is roughly as wide as it is tall, so
     *       framing on height alone works. A great hall is 40 long and 8 tall; framed on its height
     *       the camera stands 10 blocks away and both ends fall outside the shot. The FOV is
     *       vertical, so a width <i>w</i> needs the same standoff as a height {@code w / ASPECT}.</li>
     *   <li><b>The 26-block ceiling is lifted.</b> That cap suits creatures (nothing sculpted is
     *       taller than ~20) and silently clips anything bigger: a 30-block keep needs 39 blocks of
     *       standoff and would be photographed from 26, overflowing the frame with no error.</li>
     * </ul>
     *
     * <p>The fill fraction is deliberately looser than a creature's ({@code 0.45} against 0.55): a
     * building's shape is read from its silhouette, and a little air around it is what shows the
     * roofline meeting the walls.
     */
    public static double distanceForBuild(double widthBlocks, double heightBlocks) {
        double h = Math.max(1.0, heightBlocks);
        double w = Math.max(1.0, widthBlocks);
        // 16:9 frame; the FOV that matters is vertical, so a wide subject is converted into the
        // equivalent height before the same h/(1.4F) is applied.
        double effective = Math.max(h, w / BUILD_ASPECT);
        return Math.max(6.0, Math.min(MAX_BUILD_DISTANCE, effective / (1.4 * BUILD_FILL)));
    }

    /** 16:9, the frame every client test renders at. */
    private static final double BUILD_ASPECT = 16.0 / 9.0;

    /** How much of the frame a building should fill. Looser than a creature's 0.55 — see above. */
    private static final double BUILD_FILL = 0.34;

    /**
     * Far enough for the biggest build the builder can make, and no further. Beyond this the
     * render distance, not the camera, decides what is in the picture — and a subject fading into
     * fog photographs as an empty landscape, which reads as "the build failed" rather than "the
     * camera was too far away".
     *
     * <p><b>Raised from 110 because 110 was silently cropping every large build.</b> A refinement
     * ladder's castle measures 87 tall by 82 wide and wants ~183 blocks of standoff; clamped to 110
     * the camera sat inside it and photographed an interior wall, in frames the harness reported as
     * "ok" because a file had been written. The cap is only safe alongside a render distance that
     * can actually see this far — {@code BuildShotClientTest} raises it before shooting, and any
     * other caller must too.
     */
    public static final double MAX_BUILD_DISTANCE = 260.0;

    /**
     * A camera placement: where to stand and where to look.
     *
     * @param eyeY the height the camera's EYE should end up at — callers positioning a player must
     *             subtract that player's eye height, which is the kind of off-by-a-body-height that
     *             photographs a creature's knees
     */
    public record Shot(double x, double eyeY, double z, float yaw, float pitch) {
    }

    /**
     * Where to stand to photograph a creature of this height, from this bearing.
     *
     * @param cx,cy,cz     the creature's position ({@code cy} at its FEET, as Minecraft reports it)
     * @param heightBlocks its real apparent height, from {@link Framing#heightInBlocks}
     */
    public static Shot at(double cx, double cy, double cz, double heightBlocks, double bearingDeg) {
        return at(cx, cy, cz, heightBlocks, bearingDeg, 1.0);
    }

    /**
     * The same, standing {@code distanceScale} times further back.
     *
     * <p><b>Why a clip needs this and a photograph does not.</b> {@link #distanceFor} frames a
     * creature to fill a comparable share of a PORTRAIT — its subject is the creature, and nothing
     * else has to fit. A clip's subject is the creature <i>and what it emits</i>, and a creature
     * that emits anything emits it FORWARDS, straight down the lens of the front stop.
     *
     * <p>Measured: Spikor is about 6.5 blocks tall, so the portrait distance is 8.5 and his fire
     * breath reaches about 9. The camera was standing inside the cone, and twenty frames of clip
     * came back as a wall of flame with the creature somewhere behind it — visible in ONE of them.
     * The stills from the same run are fine, because a still is over before the breath is.
     *
     * <p>Same relationship the rig already has between a creature and a building: what changes is
     * how much has to be in frame, not where the subject is.
     */
    public static Shot at(double cx, double cy, double cz, double heightBlocks, double bearingDeg,
                          double distanceScale) {
        double h = Math.max(0.6, heightBlocks);
        // Aim at the middle of the mass, a little above centre -- a creature's interesting half is
        // its top half, and aiming at the feet is how a photograph becomes a picture of legs.
        double aimY = cy + h * 0.55;
        double dist = distanceFor(h) * Math.max(0.25, distanceScale);
        double rad = Math.toRadians(bearingDeg);

        // The opposite of the look direction: a player at yaw Y looks along (-sin Y, cos Y).
        double x = cx + dist * Math.sin(rad);
        double z = cz - dist * Math.cos(rad);
        // Slightly above the aim point, so the shot looks gently DOWN at the creature rather than
        // up its chin.
        // A SLIGHT DOWNWARD ANGLE. Level with the creature's middle photographs a wall of torso;
        // looking gently down separates it from the ground behind, which is what makes a small
        // creature read as standing ON something rather than floating in front of it.
        double eyeY = aimY + h * 0.45;
        float pitch = (float) -Math.toDegrees(Math.atan2(aimY - eyeY, dist));
        return new Shot(x, eyeY, z, (float) bearingDeg, pitch);
    }
}
