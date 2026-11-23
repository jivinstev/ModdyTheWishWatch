package com.moddingfromamod.wish;

/**
 * <b>Where the camera stands when there is no subject to frame</b> (F). Minecraft-free.
 *
 * <p>The old answer was "wherever the viewpoint already was, facing whichever way it already
 * faced". Through the child's own eyes that was at least the child's view; through a camera VM it
 * was a picture of wherever the camera was last parked — on 2026-10-03 a garage wish's face shot
 * was empty sky. The agent then judged its work from that frame.
 *
 * <p>So the shot is aimed at the WISH'S OWN SPOT: the anchor it manifests at (locked at wish time,
 * moved only by {@code conjure_at} or a safe-spot correction, both of which update the run's
 * anchor, so this reads the FINAL spot). Seen from where he stood when he wished, when that is a
 * sensible distance away; otherwise from {@link #STANDOFF} blocks back along the way he was
 * looking, a little above. A teleport makes nothing: the shot is taken from where he now stands,
 * looking where he looks — which is the place the wish took him.
 */
public final class WishSpotShot {

    /** How far back a camera stands from the spot when his own wish-time position will not do. */
    public static final double STANDOFF = 10.0;
    /** How far above the spot that camera's eye is. */
    public static final double RISE = 4.0;
    /** Where on the spot it looks: a block and a half up, the middle of a thing standing there. */
    public static final double AIM_UP = 1.5;
    /** His wish-time eye is used only if it is at least this far from the spot... */
    public static final double MIN_FROM = 3.0;
    /** ...and no further than this. */
    public static final double MAX_FROM = 40.0;

    /** A camera pose: feet position (what a teleport takes), facing, and the point it looks at. */
    public record Pose(double x, double feetY, double z, float yaw, float pitch,
                       double aimX, double aimY, double aimZ, String how) {
    }

    private WishSpotShot() {
    }

    /**
     * @param ax          the anchor's block x/y/z (its floor)
     * @param from        his wish-time eye {x, eyeY, z, yaw} or null
     * @param eyeHeight   the viewpoint's eye height, to turn an eye into feet
     */
    public static Pose atSpot(int ax, int ay, int az, double[] from, double eyeHeight) {
        double tx = ax + 0.5;
        double ty = ay + AIM_UP;
        double tz = az + 0.5;
        if (from != null && from.length >= 3) {
            double dx = tx - from[0];
            double dz = tz - from[2];
            double flat = Math.sqrt(dx * dx + dz * dz);
            if (flat >= MIN_FROM && flat <= MAX_FROM) {
                return look(from[0], from[1], from[2], tx, ty, tz, eyeHeight, "from where he wished");
            }
        }
        // Back along the way he was looking (so the spot is in front of the camera as it was in
        // front of him), or due south of it when nobody recorded a facing.
        double ux = 0.0;
        double uz = 1.0;
        if (from != null && from.length >= 4) {
            double yaw = Math.toRadians(from[3]);
            ux = -Math.sin(yaw);
            uz = Math.cos(yaw);
        }
        double cx = tx - ux * STANDOFF;
        double cz = tz - uz * STANDOFF;
        double eye = ay + RISE + AIM_UP;
        return look(cx, eye, cz, tx, ty, tz, eyeHeight, "stood back from the spot");
    }

    /**
     * A teleport: his own feet and facing, and a point eight blocks along his look for the
     * line-of-sight check.
     */
    public static Pose fromHim(double feetX, double feetY, double feetZ, float yaw, float pitch,
                               double eyeHeight) {
        double r = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        double reach = 8.0;
        double ax = feetX - Math.sin(r) * Math.cos(p) * reach;
        double ay = feetY + eyeHeight - Math.sin(p) * reach;
        double az = feetZ + Math.cos(r) * Math.cos(p) * reach;
        return new Pose(feetX, feetY, feetZ, yaw, pitch, ax, ay, az, "where he went");
    }

    /** Yaw and pitch from an eye to a point, in Minecraft's convention (yaw 0 = +z, south). */
    static Pose look(double ex, double eyeY, double ez, double tx, double ty, double tz,
                     double eyeHeight, String how) {
        double dx = tx - ex;
        double dz = tz - ez;
        double dy = ty - eyeY;
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));
        return new Pose(ex, eyeY - eyeHeight, ez, yaw, pitch, tx, ty, tz, how);
    }
}
