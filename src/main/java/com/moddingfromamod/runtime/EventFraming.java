package com.moddingfromamod.runtime;

/**
 * <b>Where the camera stands to film an event</b> — Minecraft-free.
 *
 * <p>An event has no subject a framer knows — a wave, a meteor, a shower of chickens — only a
 * centre and a size ({@code EventScale.radius}). So it is framed the way a building is
 * (docs/CAMERA.md §4) on its footprint: the WHOLE area in the 16:9 frame with a margin, from the
 * side the child stands on (it is his view of it,: "we can never see where it's crashing
 * down"), looking down at a photographer's angle rather than a map's, aimed a little above the ground
 * because what an event does arrives from above. The frame is then HELD for the whole clip: the
 * motion is the subject, and a camera that moved would subtract it (CAMERA.md, "Filming a CLIP").
 */
public final class EventFraming {

    /** Looking down this much: past ~25° a building is a map; an event's ground still reads at 28°. */
    public static final double ELEVATION_DEG = 28;
    /** The lens: 70° vertical, 16:9, so half the horizontal field is atan(tan 35° · 16/9) ≈ 51°. */
    public static final double HALF_H_FOV_DEG = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(35)) * 16 / 9.0));
    /** Room round the area — a wave that fills the frame edge to edge has nowhere to arrive from. */
    public static final double MARGIN = 1.35;
    public static final double MIN_DISTANCE = 16;
    public static final double MAX_DISTANCE = 120;
    /** Where it aims, above the centre, as a fraction of the radius: things fall in from above. */
    public static final double AIM_LIFT = 0.25;
    /** The camera body's eye height: a pose is FEET, as PhotoPayload pins it. */
    public static final double EYE_HEIGHT = 1.62;

    /** One pose, feet convention, and what it is aimed at. */
    public record Shot(double x, double feetY, double z, double yaw, double pitch, double aimY) { }

    private EventFraming() {
    }

    /**
     * Frame an event centred at ({@code fx}, {@code fy}, {@code fz}) of radius {@code radius},
     * from the side ({@code viewerX}, {@code viewerZ}) is on. A viewer standing on the centre (or
     * none — NaN) gets a three-quarter bearing.
     */
    public static Shot frame(double fx, double fy, double fz, double radius, double viewerX, double viewerZ) {
        double r = Math.max(2, radius);
        double fit = (r * MARGIN) / Math.tan(Math.toRadians(HALF_H_FOV_DEG));
        double dist = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, r + fit));
        double bearing;
        if (Double.isNaN(viewerX) || Double.isNaN(viewerZ) || Math.hypot(viewerX - fx, viewerZ - fz) < 1) {
            bearing = 225;   // a three-quarter view, never square-on
        } else {
            bearing = Math.toDegrees(Math.atan2(viewerX - fx, -(viewerZ - fz)));
        }
        double rad = Math.toRadians(bearing);
        double aimY = fy + r * AIM_LIFT;
        double flat = dist * Math.cos(Math.toRadians(ELEVATION_DEG));
        double eye = aimY + dist * Math.sin(Math.toRadians(ELEVATION_DEG));
        double x = fx + flat * Math.sin(rad);
        double z = fz - flat * Math.cos(rad);
        double yaw = ((bearing % 360) + 360) % 360;
        double pitch = Math.toDegrees(-Math.atan2(aimY - eye, flat));
        return new Shot(x, eye - EYE_HEIGHT, z, yaw, pitch, aimY);
    }

    /** Looking down this little at an event that rises (a shower, a fall): the height is the subject. */
    public static final double TALL_ELEVATION_DEG = 12;
    /** How far a travelling front's camera turns from side-on toward where it heads (its face). */
    public static final double FRONT_QUARTER_DEG = 35;
    /** The lens's vertical half-field: 70° vertical. */
    public static final double HALF_V_FOV_DEG = 35;

    /**
     * <b>Frame where the action GOES</b>, not where it started ({@link EventReach}).
     *
     * <p>A travelling reach (a wave) is filmed from the SIDE the viewer stands on, turned
     * {@link #FRONT_QUARTER_DEG} toward where it heads: its path runs across the frame, so the front
     * crosses it rather than leaving it, and its face shows rather than its thin profile. A reach that stays put is
     * filmed from the viewer's side. Either way the camera backs off until the whole box fits the
     * 16:9 frame with {@link #MARGIN} — across by the horizontal field, up by the vertical one — and
     * looks down less as the box gets taller, because a shower's 18 blocks of fall and a meteor's
     * approach are seen from beside them, not from above.
     */
    public static Shot frame(EventReach reach, double viewerX, double viewerZ) {
        double cx = reach.centreX(), cz = reach.centreZ();
        double bearing;
        boolean noViewer = Double.isNaN(viewerX) || Double.isNaN(viewerZ)
                || Math.hypot(viewerX - cx, viewerZ - cz) < 1;
        if (reach.travels()) {
            // side-on: perpendicular to the heading, on the viewer's side (or the left, with none)
            double px = -reach.headingZ(), pz = reach.headingX();
            if (!noViewer && (viewerX - cx) * px + (viewerZ - cz) * pz < 0) {
                px = -px;
                pz = -pz;
            }
            // ...turned toward where it is going: exactly side-on films a front's thin profile (its
            // width points at the lens — measured, a 32-wide wave read as a narrow blue post); a
            // quarter turn shows its face coming on and still its travel across the frame
            double q = Math.toRadians(FRONT_QUARTER_DEG);
            double qx = px * Math.cos(q) + reach.headingX() * Math.sin(q);
            double qz = pz * Math.cos(q) + reach.headingZ() * Math.sin(q);
            bearing = Math.toDegrees(Math.atan2(qx, -qz));
        } else if (noViewer) {
            bearing = 225;
        } else {
            bearing = Math.toDegrees(Math.atan2(viewerX - cx, -(viewerZ - cz)));
        }
        double rad = Math.toRadians(bearing);
        // camera's own axes in the ground plane: toward the camera (bx,bz) and its right (rx,rz)
        double bx = Math.sin(rad), bz = -Math.cos(rad);
        double rx = -bz, rz = bx;
        double halfAcross = 0, halfDeep = 0;
        double[] xs = {reach.x0(), reach.x1()};
        double[] zs = {reach.z0(), reach.z1()};
        for (double x : xs) {
            for (double z : zs) {
                halfAcross = Math.max(halfAcross, Math.abs((x - cx) * rx + (z - cz) * rz));
                halfDeep = Math.max(halfDeep, Math.abs((x - cx) * bx + (z - cz) * bz));
            }
        }
        double h = reach.height();
        double tall = Math.min(1, h / Math.max(4, 2 * halfAcross));
        double elev = ELEVATION_DEG + (TALL_ELEVATION_DEG - ELEVATION_DEG) * tall;
        double fitAcross = Math.max(2, halfAcross) * MARGIN / Math.tan(Math.toRadians(HALF_H_FOV_DEG));
        double fitUp = (h / 2) * MARGIN / Math.tan(Math.toRadians(HALF_V_FOV_DEG));
        double dist = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, halfDeep + Math.max(fitAcross, fitUp)));
        double aimY = reach.y0() + h * (reach.travels() ? 0.4 : 0.5);
        double flat = dist * Math.cos(Math.toRadians(elev));
        double eye = aimY + dist * Math.sin(Math.toRadians(elev));
        double x = cx + flat * bx;
        double z = cz + flat * bz;
        double yaw = ((bearing % 360) + 360) % 360;
        double pitch = Math.toDegrees(-Math.atan2(aimY - eye, flat));
        return new Shot(x, eye - EYE_HEIGHT, z, yaw, pitch, aimY);
    }

    /**
     * Is world point ({@code px}, {@code py}, {@code pz}) inside this shot's 16:9, 70° frame? The
     * honest "did the camera see it" question for an event: asked of where the event really
     * WROTE, not of the picture — a frame difference cannot tell six seconds of grass from a few small
     * chickens (measured: 0.8% and 0.9% of pixels change a frame on llvmpipe).
     */
    public static boolean inFrame(Shot s, double px, double py, double pz) {
        double eye = s.feetY() + EYE_HEIGHT;
        double yaw = Math.toRadians(s.yaw());
        double pitch = Math.toRadians(s.pitch());
        double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        double ux = fy * rz, uy = fz * rx - fx * rz, uz = -fy * rx;
        double dx = px - s.x(), dy = py - eye, dz = pz - s.z();
        double f = dx * fx + dy * fy + dz * fz;
        if (f <= 0) {
            return false;
        }
        double r = dx * rx + dz * rz;
        double u = dx * ux + dy * uy + dz * uz;
        return Math.abs(r / f) <= Math.tan(Math.toRadians(HALF_H_FOV_DEG)) * 1.02
                && Math.abs(u / f) <= Math.tan(Math.toRadians(HALF_V_FOV_DEG)) * 1.02;
    }
}
