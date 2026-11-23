package com.moddingfromamod.place;

/**
 * <b>Where the camera stands to show Y in / on / next to / over X</b> — the pose the
 * placement check's photograph is taken from. Pure: {@code PlaceShotTest}.
 *
 * <p>The {@code photograph} tool frames the BUILDING, which proves nothing about what was put at it:
 * the first real grant's frame was a fine picture of a barn with the cow it was asked about out of
 * sight inside. So this frames the CLAIM: the building's box and the thing put at it together, from
 * his side, a little above — or, for a thing put INSIDE, from inside the room looking at it
 * ({@link #looking}), because no outside camera sees through a wall (docs/CAMERA.md §5).
 *
 * <p>Poses are FEET positions with Minecraft yaw (south = 0) and pitch (positive looks down), the
 * form {@code PhotoClient.request} takes.
 */
public final class PlaceShot {

    /** The eye's height above the feet. */
    public static final double EYE = 1.62;
    /** Half the vertical field of view the framing assumes (vanilla's 70°), in degrees. */
    static final double HALF_FOV = 35.0;
    /** How far above the horizon the camera looks down from. */
    static final double ELEVATION = 25.0;
    /** How much of the building either side of the thing is framed with it. */
    static final double CLIP = 10.0;

    private PlaceShot() {
    }

    /** A camera pose: feet x, y, z, then yaw and pitch. */
    public record Pose(double x, double y, double z, float yaw, float pitch) {
    }

    /**
     * Frame a box (the building, {@code {x0,y0,z0,x1,y1,z1}} inclusive blocks, or null) and a point
     * (the thing put at it) together, from the side {@code fromX/fromZ} is on (his position; NaN for
     * the south), {@link #ELEVATION} degrees above, far enough for all of it to fit.
     */
    public static Pose framing(int[] box, double tx, double ty, double tz, double fromX, double fromZ) {
        return framing(box, tx, ty, tz, fromX, fromZ, CLIP);
    }

    /**
     * A whole building, unclipped, from his side: for a wish that EDITED the building he named (the
     * lighthouse added to the hall's own design), where the new part is somewhere in it.
     */
    public static Pose whole(int[] box, double fromX, double fromZ) {
        return framing(box, (box[0] + box[3] + 1) / 2.0, box[1], (box[2] + box[5] + 1) / 2.0, fromX, fromZ,
                Double.MAX_VALUE);
    }

    private static Pose framing(int[] box, double tx, double ty, double tz, double fromX, double fromZ, double clip) {
        double x0 = tx - 1, y0 = ty, z0 = tz - 1, x1 = tx + 1, y1 = ty + 2, z1 = tz + 1;
        if (box != null) {
            // The building AROUND the thing, not all of it: a 40-tall tower framed whole made the
            // dragon on its top a speck (measured, the second real grant). CLIP blocks either side.
            x0 = Math.min(x0, Math.max(box[0], tx - clip));
            y0 = Math.min(y0, Math.max(box[1], ty - clip));
            z0 = Math.min(z0, Math.max(box[2], tz - clip));
            x1 = Math.max(x1, Math.min(box[3] + 1, tx + clip));
            y1 = Math.max(y1, Math.min(box[4] + 1, ty + clip));
            z1 = Math.max(z1, Math.min(box[5] + 1, tz + clip));
        }
        double cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, cz = (z0 + z1) / 2;
        double r = Math.sqrt(sq(x1 - x0) + sq(y1 - y0) + sq(z1 - z0)) / 2;
        double dist = r / Math.tan(Math.toRadians(HALF_FOV)) * 1.15 + 2;
        double dx = Double.isNaN(fromX) ? 0 : fromX - cx, dz = Double.isNaN(fromZ) ? 1 : fromZ - cz;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) {
            dx = 0;
            dz = 1;
            len = 1;
        }
        double e = Math.toRadians(ELEVATION);
        double ex = cx + dx / len * dist * Math.cos(e);
        double ez = cz + dz / len * dist * Math.cos(e);
        double ey = cy + dist * Math.sin(e);
        return looking(ex, ey, ez, cx, cy, cz);
    }

    /** A camera whose EYE is at (ex, ey, ez), looking at (lx, ly, lz). */
    public static Pose looking(double ex, double ey, double ez, double lx, double ly, double lz) {
        double dx = lx - ex, dy = ly - ey, dz = lz - ez;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        return new Pose(ex, ey - EYE, ez, yaw, pitch);
    }

    private static double sq(double v) {
        return v * v;
    }
}
