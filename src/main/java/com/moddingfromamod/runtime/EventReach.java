package com.moddingfromamod.runtime;

/**
 * <b>Where an event's action goes while it is filmed</b> — Minecraft-free.
 *
 * <p>An event was framed on its centre at the moment it started, and three things a child wished for
 * are not there: a wave TRAVELS (it rolled out of a held frame and left six seconds of grass), a
 * meteor arrives from high up and far back, and a shower drops from {@code SkyDrop.HEIGHT} above
 * the ground. Each event's parts already know their own schedule, so the stager says where the
 * action is over the clip window and the framer fits THAT (docs/CAMERA.md, "One turn, filmed").
 *
 * <p>A box in world coordinates, plus the way it travels when it travels (NaN when it stays put).
 */
public record EventReach(double x0, double y0, double z0, double x1, double y1, double z1,
                         double headingX, double headingZ, Travel travel) {

    /** Ticks in the event clip: 6 s at 20 ticks a second (PhotographTool.EVENT_SECONDS). */
    public static final int CLIP_TICKS = 120;
    /** How much ground round a travelling front each frame keeps: where it came from and is going. */
    public static final double FRONT_LEAD = 10;

    /**
     * A travelling front's schedule: born at ({@code ox}, {@code oz}), moving {@code speed} blocks a
     * tick for {@code lifeTicks}, {@code halfWidth} each side and {@code height} tall.
     */
    public record Travel(double ox, double groundY, double oz, double speed, int lifeTicks,
                         double halfWidth, double height) { }

    public EventReach {
        if (x1 < x0 || y1 < y0 || z1 < z0) {
            throw new IllegalArgumentException("an empty reach");
        }
    }

    /** A box that stays where it is. */
    public static EventReach box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new EventReach(Math.min(x0, x1), Math.min(y0, y1), Math.min(z0, z1),
                Math.max(x0, x1), Math.max(y0, y1), Math.max(z0, z1), Double.NaN, Double.NaN, null);
    }

    /** A low area round a centre: a flood, a crack, a crater, a slide. */
    public static EventReach around(double cx, double groundY, double cz, double radius, double height) {
        double r = Math.max(1, radius);
        return box(cx - r, groundY, cz - r, cx + r, groundY + Math.max(1, height), cz + r);
    }

    /**
     * a transform changes a whole disc where it stands. The frame holds its middle (the ripple
     * starts there and the edge is the same change again), from the ground to a tree's crown when it
     * changes a kind of block that grows upward.
     */
    public static EventReach transform(double cx, double groundY, double cz, double radius, boolean upward) {
        double r = Math.max(1, radius);
        return around(cx, groundY - 1, cz, r * 0.6, upward ? Math.max(8, r * 0.4) : Math.max(4, r * 0.2));
    }

    /** a crowd standing round its centre, tall enough for a horse or a golem. */
    public static EventReach crowd(double cx, double groundY, double cz, double radius) {
        return around(cx, groundY, cz, radius + 1.5, 4);
    }

    /** Things dropped over a disc from {@code dropHeight} above the ground. */
    public static EventReach sky(double cx, double groundY, double cz, double radius, double dropHeight) {
        return around(cx, groundY, cz, radius, dropHeight);
    }

    /**
     * A front born at ({@code ox}, {@code oz}) rolling along the heading ({@code hx}, {@code hz}) at
     * {@code speed} blocks a tick for {@code lifeTicks}, {@code halfWidth} each side, {@code height}
     * tall. The box is everything it crosses during the clip; {@link #frontAt} is where it is now.
     */
    public static EventReach front(double ox, double groundY, double oz, double hx, double hz,
                                   double speed, int lifeTicks, double halfWidth, double height) {
        double len = Math.hypot(hx, hz);
        if (len < 1e-9) {
            return around(ox, groundY, oz, halfWidth, height);
        }
        double ux = hx / len, uz = hz / len;
        double travelled = speed * Math.min(lifeTicks, CLIP_TICKS);
        EventReach b = band(ox, groundY, oz, ux, uz, 0, travelled, halfWidth, height);
        return new EventReach(b.x0, b.y0, b.z0, b.x1, b.y1, b.z1, ux, uz,
                new Travel(ox, groundY, oz, speed, lifeTicks, halfWidth, height));
    }

    /**
     * Where a travelling front is at {@code tick}, with {@link #FRONT_LEAD} of ground either side
     * along its way — a stationary reach for the framer, so a tracking shot is one framing per frame.
     * A reach that does not travel answers itself.
     */
    public EventReach frontAt(double tick) {
        if (travel == null) {
            return this;
        }
        double along = travel.speed() * Math.max(0, Math.min(travel.lifeTicks(), tick));
        EventReach b = band(travel.ox(), travel.groundY(), travel.oz(), headingX, headingZ,
                along - FRONT_LEAD, along + FRONT_LEAD, travel.halfWidth(), travel.height());
        return new EventReach(b.x0, b.y0, b.z0, b.x1, b.y1, b.z1, headingX, headingZ, null);
    }

    /** The box of a band along a unit heading from {@code from} to {@code to} blocks out. */
    private static EventReach band(double ox, double groundY, double oz, double ux, double uz,
                                   double from, double to, double halfWidth, double height) {
        double px = -uz, pz = ux;
        double sx = ox + ux * from, sz = oz + uz * from;
        double ex = ox + ux * to, ez = oz + uz * to;
        double[] xs = {sx + px * halfWidth, sx - px * halfWidth, ex + px * halfWidth, ex - px * halfWidth};
        double[] zs = {sz + pz * halfWidth, sz - pz * halfWidth, ez + pz * halfWidth, ez - pz * halfWidth};
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            minX = Math.min(minX, xs[i]);
            maxX = Math.max(maxX, xs[i]);
            minZ = Math.min(minZ, zs[i]);
            maxZ = Math.max(maxZ, zs[i]);
        }
        return box(minX, groundY, minZ, maxX, groundY + Math.max(1, height), maxZ);
    }

    /**
     * A body falling from {@code (sx, sy, sz)} to {@code (tx, ty, tz)}, leaving a crater of
     * {@code craterRadius}. The whole line is in reach: the fall IS the event, the crater its end.
     */
    public static EventReach fall(double sx, double sy, double sz, double tx, double ty, double tz,
                                  double craterRadius) {
        double r = Math.max(1, craterRadius);
        return box(Math.min(sx, tx - r), Math.min(sy, ty), Math.min(sz, tz - r),
                Math.max(sx, tx + r), Math.max(sy, ty + 2), Math.max(sz, tz + r));
    }

    /**
     * <b>The same reach, grown to hold a box as well</b>: an event aimed AT a building — a
     * meteor hitting the castle, snow falling over the tower — is filmed with the building in the
     * frame, because "it hit my castle" is a picture of the castle being hit, not of a crater on a
     * roof with nothing round it. Its heading and its tracked schedule are kept: a travelling front
     * is still filmed by {@link #frontAt}.
     */
    public EventReach including(double bx0, double by0, double bz0, double bx1, double by1, double bz1) {
        return new EventReach(Math.min(x0, Math.min(bx0, bx1)), Math.min(y0, Math.min(by0, by1)),
                Math.min(z0, Math.min(bz0, bz1)), Math.max(x1, Math.max(bx0, bx1)), Math.max(y1, Math.max(by0, by1)),
                Math.max(z1, Math.max(bz0, bz1)), headingX, headingZ, travel);
    }

    public double centreX() {
        return (x0 + x1) / 2;
    }

    public double centreZ() {
        return (z0 + z1) / 2;
    }

    public double height() {
        return y1 - y0;
    }

    /** It has a heading: framed side-on. */
    public boolean travels() {
        return !Double.isNaN(headingX) && !Double.isNaN(headingZ);
    }

    /** It has a schedule: filmed by tracking the front ({@link #frontAt}) rather than one held frame. */
    public boolean tracked() {
        return travel != null;
    }
}
