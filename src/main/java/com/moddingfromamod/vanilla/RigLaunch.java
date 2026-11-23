package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>Where a rig creature's throw leaves its body, and how it flies so it is SEEN</b>.
 *
 * <p>The fire buddy's fireball was invisible whenever the monster was close (
 * "you don't actually see the fireball being thrown"). Two causes, and neither was about fire:
 * <ul>
 *   <li><b>it started inside the creature.</b> A ball was built with vanilla's
 *       {@code Snowball(level, owner)}, which puts it at the OWNER's eye. The owner is the rig's
 *       invisible carrier, so the ball appeared at a point the carrier's box chose, deep inside the
 *       drawn body, nowhere near the hand that holds the fireball;</li>
 *   <li><b>it arrived before it could be seen.</b> At 1.4 blocks a tick, a monster three blocks off
 *       was hit on the second tick, so what flight there was happened inside the body.</li>
 * </ul>
 * Both are true of every sculpted creature with {@code ranged}, whatever it throws, so the answer
 * is read off the DRAWN body, never a named starter: the ball leaves from a held thing, else a
 * hand, else the mouth, else the front of the body ({@link #from}), and it is flown so it takes at
 * least {@link #MIN_FLIGHT_TICKS} to arrive ({@link #flightTicks}, {@link #velocity}), landing where
 * it was aimed in exactly that many ticks.
 *
 * <p>Pure: no Minecraft. Boxes are in the rig's frame, in blocks over the carrier's feet
 * ({@link RigFit#atoms}).
 */
public final class RigLaunch {

    private RigLaunch() {
    }

    /** One drawn box, in the rig's frame, in blocks; {@code parent} may be null. */
    public record Part(String name, String parent, double minX, double minY, double minZ,
                       double maxX, double maxY, double maxZ) {

        double cx() {
            return (minX + maxX) / 2;
        }

        double cy() {
            return (minY + maxY) / 2;
        }

        double cz() {
            return (minZ + maxZ) / 2;
        }

        double size() {
            return Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        }
    }

    /** Where the ball left from, and which rule chose it (for a test and a log line to name). */
    public record Launch(double x, double y, double z, String rule, String part) {
    }

    /**
     * The fewest ticks a throw spends in the air: 8 ticks is 0.4 s, long enough for a child's eye
     * to follow a ball from the hand to the monster. A far shot is no slower than it was.
     */
    public static final int MIN_FLIGHT_TICKS = 8;
    /** A far shot's pace, blocks a tick: about the 1.4 a ball used to be thrown at, less its drag. */
    public static final double CRUISE = 1.3;
    /** A snowball's gravity and drag a tick (vanilla {@code ThrowableProjectile}, 1.21.8). */
    public static final double GRAVITY = 0.03;
    public static final double DRAG = 0.99;

    /** Names of a thing held to be thrown. Tokens of the thrown item's own id count too. */
    private static final String[] HELD = {"hand", "fist", "palm", "held", "ball", "orb", "bomb",
            "missile", "shot", "projectile", "rock", "stone", "spear"};
    /** Names of an arm, whose free end is the hand. */
    private static final String[] ARM = {"arm", "hand", "fist", "paw", "claw", "forearm"};
    /** Names of a mouth. */
    private static final String[] MOUTH = {"mouth", "jaw", "beak", "snout", "muzzle", "maw", "tongue"};

    /**
     * Where a throw leaves this body, aiming at {@code target} (rig frame). Never empty: a body with
     * no hand, mouth or head throws from the front of its middle.
     *
     * @param thrownItem the item id it throws ({@code minecraft:fire_charge}), or empty
     */
    public static Launch from(List<Part> parts, String thrownItem, double[] target) {
        if (parts == null || parts.isEmpty()) {
            return new Launch(0, 1, 0, "none", null);
        }
        double lowest = Double.MAX_VALUE;
        double highest = -Double.MAX_VALUE;
        for (Part p : parts) {
            lowest = Math.min(lowest, p.minY());
            highest = Math.max(highest, p.maxY());
        }
        final double minY = lowest;
        final double height = Math.max(1e-6, highest - lowest);

        // 1. A thing held to be thrown: small, and named for it (or for what it throws).
        List<String> heldWords = new ArrayList<>(List.of(HELD));
        heldWords.addAll(itemWords(thrownItem));
        Part held = nearest(parts, target, p -> named(p, heldWords) && p.size() <= 0.45 * height
                && p.minY() > minY + 0.05 * height);
        if (held != null) {
            return new Launch(held.cx(), held.cy(), held.cz(), "held", held.name());
        }

        // 2. A hand: the free end of an arm that does not stand on the ground.
        Part arm = nearest(parts, target, p -> named(p, List.of(ARM)) && p.minY() > minY + 0.15 * height);
        if (arm != null) {
            double[] b = subtree(parts, arm);
            double y = b[1] + 0.15 * (b[4] - b[1]);
            return new Launch((b[0] + b[3]) / 2, y, (b[2] + b[5]) / 2, "hand", arm.name());
        }

        // 3. A mouth, else the face of the head turned to the target.
        Part mouth = nearest(parts, target, p -> named(p, List.of(MOUTH)));
        if (mouth != null) {
            return new Launch(mouth.cx(), mouth.cy(), mouth.cz(), "mouth", mouth.name());
        }
        Part head = null;
        for (Part p : parts) {
            if (lower(p).equals("head")) {
                head = p;
            }
        }
        if (head == null) {
            head = nearest(parts, target, p -> lower(p).contains("head"));
        }
        if (head != null) {
            double[] f = face(head.minX(), head.minZ(), head.maxX(), head.maxZ(), head.cy(), target);
            return new Launch(f[0], head.cy(), f[1], "head", head.name());
        }

        // 4. The front of its middle, at chest height.
        double x0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
        for (Part p : parts) {
            x0 = Math.min(x0, p.minX());
            z0 = Math.min(z0, p.minZ());
            x1 = Math.max(x1, p.maxX());
            z1 = Math.max(z1, p.maxZ());
        }
        double y = minY + 0.7 * height;
        double[] f = face(x0, z0, x1, z1, y, target);
        return new Launch(f[0], y, f[1], "body", null);
    }

    /** Ticks a throw of {@code distance} blocks spends in the air: never fewer than the minimum. */
    public static int flightTicks(double distance) {
        return Math.max(MIN_FLIGHT_TICKS, (int) Math.ceil(distance / CRUISE));
    }

    /**
     * The starting velocity that lands a snowball on {@code to} in exactly {@code ticks} ticks,
     * under vanilla's own order each tick: gravity, then drag, then the move. Exact, not a lob
     * guessed by eye: after {@code n} ticks the ball has moved {@code v0·S(n) − g·ΣS(k)}, with
     * {@code S(n) = Σ drag^k}.
     */
    public static double[] velocity(double[] from, double[] to, int ticks) {
        int n = Math.max(1, ticks);
        double s = 0;          // S(n)
        double sumS = 0;       // Σ_{k=1..n} S(k)
        double p = 1;
        for (int k = 1; k <= n; k++) {
            p *= DRAG;
            s += p;
            sumS += s;
        }
        return new double[]{(to[0] - from[0]) / s,
                (to[1] - from[1] + GRAVITY * sumS) / s,
                (to[2] - from[2]) / s};
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private static List<String> itemWords(String item) {
        List<String> out = new ArrayList<>();
        if (item == null || item.isEmpty()) {
            return out;
        }
        String path = item.contains(":") ? item.substring(item.indexOf(':') + 1) : item;
        for (String w : path.toLowerCase(Locale.ROOT).split("[_\\-/]")) {
            if (w.length() >= 4) {
                out.add(w);
            }
        }
        return out;
    }

    private static boolean named(Part p, List<String> words) {
        String n = lower(p);
        for (String w : words) {
            if (n.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static String lower(Part p) {
        return p.name() == null ? "" : p.name().toLowerCase(Locale.ROOT);
    }

    private static Part nearest(List<Part> parts, double[] target, java.util.function.Predicate<Part> ok) {
        Part best = null;
        double bestD = Double.MAX_VALUE;
        for (Part p : parts) {
            if (!ok.test(p)) {
                continue;
            }
            double dx = p.cx() - target[0];
            double dy = p.cy() - target[1];
            double dz = p.cz() - target[2];
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** Bounds of a part and everything hung from it: {minX, minY, minZ, maxX, maxY, maxZ}. */
    static double[] subtree(List<Part> parts, Part root) {
        double[] b = {root.minX(), root.minY(), root.minZ(), root.maxX(), root.maxY(), root.maxZ()};
        java.util.Set<String> in = new java.util.HashSet<>();
        in.add(root.name());
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Part p : parts) {
                if (p.parent() != null && in.contains(p.parent()) && in.add(p.name())) {
                    grew = true;
                    b[0] = Math.min(b[0], p.minX());
                    b[1] = Math.min(b[1], p.minY());
                    b[2] = Math.min(b[2], p.minZ());
                    b[3] = Math.max(b[3], p.maxX());
                    b[4] = Math.max(b[4], p.maxY());
                    b[5] = Math.max(b[5], p.maxZ());
                }
            }
        }
        return b;
    }

    /**
     * The point on a box's outline, in plan, where the line from its middle to the target leaves it:
     * the face it turns to what it throws at.
     */
    static double[] face(double x0, double z0, double x1, double z1, double y, double[] target) {
        double cx = (x0 + x1) / 2;
        double cz = (z0 + z1) / 2;
        double dx = target[0] - cx;
        double dz = target[2] - cz;
        double hx = (x1 - x0) / 2;
        double hz = (z1 - z0) / 2;
        double t = Double.MAX_VALUE;
        if (Math.abs(dx) > 1e-9) {
            t = Math.min(t, hx / Math.abs(dx));
        }
        if (Math.abs(dz) > 1e-9) {
            t = Math.min(t, hz / Math.abs(dz));
        }
        if (t == Double.MAX_VALUE) {
            return new double[]{cx, cz};
        }
        t = Math.min(t, 1.0);   // never past the target
        return new double[]{cx + dx * t, cz + dz * t};
    }
}
