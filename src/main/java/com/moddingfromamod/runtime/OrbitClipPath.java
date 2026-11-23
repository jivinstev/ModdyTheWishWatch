package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>A camera path for a short spin clip, threaded THROUGH the poses a framer already chose</b>
 *. Minecraft-free.
 *
 * <p>Every framer here encodes lessons paid for in wrong photographs — a building's buried floor
 * and apron and its derived roof fit ({@code BuildFraming}), a train's axis and its refusal to
 * look end-on ({@code TrainFraming}), a creature's locked bearings and portrait distance
 * ({@code OrbitCamera}). A clip that computed its own distance and height would throw all of that
 * away. So this takes the framer's KEY poses and interpolates between them in polar coordinates
 * about their centre — bearing, radius, eye height and pitch each move smoothly from one key to
 * the next — so every frame of the clip is a frame that framer would have chosen at that bearing.
 *
 * <ul>
 *   <li><b>Closed</b> ({@link #ring}): the keys go all the way round (a building's four sides, a
 *       creature's four stops) and the path is a full turn, back to where it started.</li>
 *   <li><b>Open</b> ({@link #arc}): the keys span a sweep (a train's three-quarter front → side →
 *       three-quarter rear) and the path stays inside it — never through an angle the framer
 *       deliberately does not take.</li>
 * </ul>
 *
 * <p>{@link #smoothFeet} is the elevation lesson applied to motion: line of sight
 * ({@code ClearView}) raises a seat in 4-block steps where a tree or a hill is in the way, and a
 * clip whose camera popped up for one frame and back down the next is a jolt, not a view. So the
 * clip keeps every raise (it is needed) but holds it across its neighbours and eases in and out.
 */
public final class OrbitClipPath {

    /** One camera position: feet-or-eye height is the caller's convention, kept as given. */
    public record Pose(double x, double y, double z, double yaw, double pitch) { }

    private OrbitClipPath() {
    }

    /** The bearing of a point about a centre, in the framers' convention (0 = −Z, 90 = +X). */
    static double bearingOf(double cx, double cz, double x, double z) {
        double deg = Math.toDegrees(Math.atan2(x - cx, -(z - cz)));
        return (deg % 360 + 360) % 360;
    }

    /** Where the keys circle: the mean of their positions (equal radii make it exact). */
    static double[] centre(List<Pose> keys) {
        double sx = 0;
        double sz = 0;
        for (Pose p : keys) {
            sx += p.x();
            sz += p.z();
        }
        return new double[] {sx / keys.size(), sz / keys.size()};
    }

    /**
     * A full turn through {@code keys} (ordered by bearing as the framer gives them, any start),
     * {@code steps} poses, starting AT the first key — the hero shot's own bearing — and ending one
     * step short of it, so a looping clip does not hold a frame twice.
     *
     * @param cx centre, or NaN to take the keys' mean
     */
    public static List<Pose> ring(List<Pose> keys, double cx, double cz, int steps) {
        if (keys == null || keys.size() < 2 || steps < 2) {
            return keys == null ? List.of() : List.copyOf(keys);
        }
        if (Double.isNaN(cx) || Double.isNaN(cz)) {
            double[] c = centre(keys);
            cx = c[0];
            cz = c[1];
        }
        int n = keys.size();
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            raw[i] = bearingOf(cx, cz, keys.get(i).x(), keys.get(i).z());
        }
        // The framer's own turning direction, from its first two keys; then each key is the next
        // one round in that direction, and the loop closes on the first key a full turn later.
        double first = ((raw[1] - raw[0]) % 360 + 360) % 360;
        int dir = first <= 180 ? 1 : -1;
        double[] bear = new double[n + 1];
        double[] rad = new double[n + 1];
        double[] y = new double[n + 1];
        double[] pitch = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            Pose k = keys.get(i % n);
            rad[i] = Math.hypot(k.x() - cx, k.z() - cz);
            y[i] = k.y();
            pitch[i] = k.pitch();
            if (i == 0) {
                bear[i] = raw[0];
            } else if (i == n) {
                bear[i] = raw[0] + dir * 360;
            } else {
                double fwd = ((raw[i] - raw[i - 1]) % 360 + 360) % 360;
                bear[i] = bear[i - 1] + (dir > 0 ? fwd : -((360 - fwd) % 360));
            }
        }
        return sample(bear, rad, y, pitch, cx, cz, steps, false);
    }

    /**
     * A full turn ON THE SPOT (a building's inside 360): {@code steps} poses at one place,
     * the bearing from {@code yaw0} round to one step short of a whole turn, the pitch held.
     */
    public static List<Pose> turnInPlace(double x, double y, double z, double yaw0, double pitch, int steps) {
        List<Pose> out = new ArrayList<>(Math.max(1, steps));
        for (int s = 0; s < Math.max(1, steps); s++) {
            double yaw = ((yaw0 + 360.0 * s / Math.max(1, steps)) % 360 + 360) % 360;
            out.add(new Pose(x, y, z, yaw, pitch));
        }
        return out;
    }

    /** An open sweep through {@code keys} in the order given, {@code steps} poses, both ends included. */
    public static List<Pose> arc(List<Pose> keys, double cx, double cz, int steps) {
        if (keys == null || keys.size() < 2 || steps < 2) {
            return keys == null ? List.of() : List.copyOf(keys);
        }
        int n = keys.size();
        double[] bear = new double[n];
        double[] rad = new double[n];
        double[] y = new double[n];
        double[] pitch = new double[n];
        for (int i = 0; i < n; i++) {
            Pose k = keys.get(i);
            double b = bearingOf(cx, cz, k.x(), k.z());
            if (i > 0) {
                double d = ((b - bear[i - 1]) % 360 + 540) % 360 - 180;   // the SHORT way between keys
                b = bear[i - 1] + d;
            }
            bear[i] = b;
            rad[i] = Math.hypot(k.x() - cx, k.z() - cz);
            y[i] = k.y();
            pitch[i] = k.pitch();
        }
        return sample(bear, rad, y, pitch, cx, cz, steps, true);
    }

    private static List<Pose> sample(double[] bear, double[] rad, double[] y, double[] pitch,
                                     double cx, double cz, int steps, boolean includeEnd) {
        List<Pose> out = new ArrayList<>(steps);
        double total = bear[bear.length - 1] - bear[0];
        for (int s = 0; s < steps; s++) {
            double t = includeEnd ? (double) s / (steps - 1) : (double) s / steps;
            double b = bear[0] + total * t;
            int seg = 0;
            while (seg < bear.length - 2 && (total >= 0 ? b > bear[seg + 1] : b < bear[seg + 1])) {
                seg++;
            }
            double span = bear[seg + 1] - bear[seg];
            double u = span == 0 ? 0 : (b - bear[seg]) / span;
            u = Math.max(0, Math.min(1, u));
            double w = u * u * (3 - 2 * u);                          // eased: no corner at a key
            double r = rad[seg] + (rad[seg + 1] - rad[seg]) * w;
            double py = y[seg] + (y[seg + 1] - y[seg]) * w;
            double pp = pitch[seg] + (pitch[seg + 1] - pitch[seg]) * w;
            double rr = Math.toRadians(b);
            double px = cx + r * Math.sin(rr);
            double pz = cz - r * Math.cos(rr);
            double yaw = ((b % 360) + 360) % 360;                     // facing the centre: yaw = bearing
            out.add(new Pose(px, py, pz, yaw, pp));
        }
        return out;
    }

    /**
     * Hold each line-of-sight raise across its neighbours, then ease: a seat raised for a tree in
     * one frame is raised for {@code hold} frames either side, so the camera rises once and comes
     * down once instead of bobbing. Never LOWER than asked — a lowered seat is the blocked view
     * again. {@code asked} is the framer's height, {@code seated} what line of sight gave.
     */
    public static double[] smoothFeet(double[] asked, double[] seated, int hold, boolean closed) {
        int n = seated.length;
        double[] rise = new double[n];
        for (int i = 0; i < n; i++) {
            rise[i] = Math.max(0, seated[i] - asked[i]);
        }
        double[] held = new double[n];
        for (int i = 0; i < n; i++) {
            double m = 0;
            for (int d = -hold; d <= hold; d++) {
                int j = i + d;
                if (closed) {
                    j = ((j % n) + n) % n;
                } else if (j < 0 || j >= n) {
                    continue;
                }
                m = Math.max(m, rise[j]);
            }
            held[i] = m;
        }
        // ease the held raise with one pass of a [1,2,1] filter, then never below the raw need
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double a = held[closed ? (i - 1 + n) % n : Math.max(0, i - 1)];
            double c = held[closed ? (i + 1) % n : Math.min(n - 1, i + 1)];
            double eased = (a + 2 * held[i] + c) / 4;
            // the held raise is a FLOOR; easing only lifts the shoulders either side of it
            out[i] = asked[i] + Math.max(rise[i], Math.max(held[i], eased));
        }
        return out;
    }
}
