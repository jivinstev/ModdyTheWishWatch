package com.moddingfromamod.wish;

/**
 * <b>Where a wish lands: aim, then clamp — decided once, when he wishes</b>. Pure, no
 * Minecraft; the world is asked through {@link World}, so every case is a row in a unit test.
 *
 * <p>The old rule cast only 24 blocks, so a child flying high and looking at the horizon hit
 * nothing and got a point in mid-air; {@code conjure_at} then re-aimed 12 blocks out from wherever
 * he had flown to and dropped it straight down — nearly under his feet, ~77° below his eye line
 * from 50 blocks up (2026-10-01, on a live server). The decisions (2026-10-02,):
 * <ul>
 *   <li><b>R = {@value #REACH}</b>: how far "where I look" reaches; beyond it, the miss rule;</li>
 *   <li><b>a wish aimed at water floats on it</b> (the ray stops at a water surface, and a fluid's
 *   top is somewhere to stand);</li>
 *   <li><b>one slide along the gaze line by kind</b> at {@code plan_wish} ({@link #slide});</li>
 *   <li><b>only the agent's explicit {@code up} floats a wish</b>: every spot here is settled
 *   DOWN onto something, never left in the air and never raised out of a cave.</li>
 * </ul>
 */
public final class WishAim {

    /** How far "where he looks" reaches, in blocks. */
    public static final int REACH = 64;

    /** A miss lands at least this far out (his height above the ground, clamped to this..REACH). */
    public static final int MIN_MISS = 12;

    /** How far a settle may climb out of solid ground to find its surface. */
    static final int CLIMB = 32;

    private WishAim() {
    }

    /** What one block is, as far as standing on it goes. */
    public enum Cell {
        /** Nothing to stand in the way: air, grass, flowers, snow layers. */
        AIR,
        /** Something a wish can stand on. */
        SOLID,
        /** Water: a wish floats on its surface. */
        FLUID,
        /**
         * Lava. Not a floor: the spot above it stays where he aimed, but it is reported
         * {@code over lava} so the log says so, and a creature put there gets a footing
         * ({@code SafeSpot.fix}) instead of standing on the lava and burning.
         */
        LAVA,
        /** Not loaded: never read, never generated. */
        UNLOADED
    }

    /** The world, as the rule needs it. */
    public interface World {
        Cell at(int x, int y, int z);

        int bottom();

        int top();
    }

    /** Where he was and which way he looked when he wished: his FEET, his eye height, yaw, pitch. */
    public record Snapshot(double x, double y, double z, double eyeY, float yaw, float pitch) {
    }

    /** The block his gaze hit and the outward normal of the face it hit. */
    public record Hit(int x, int y, int z, int nx, int ny, int nz) {
    }

    /** A spot and how it was decided — the "how" goes in the log, so a report can be checked. */
    public record Spot(int x, int y, int z, String how) {
    }

    /** The kinds a spot is clamped for. Known only at {@code plan_wish} for an agent's wish. */
    public enum Kind {
        BUILDING(MIN_MISS, REACH), CREATURE(4, 16), PICTURE(5, 16), ITEM(0, REACH), EVENT(0, 8),
        OTHER(0, REACH);

        /** The nearest and farthest a thing of this kind may stand from him, horizontally. */
        public final int near, far;

        Kind(int near, int far) {
            this.near = near;
            this.far = far;
        }

        /** A kind from a word; OTHER for anything unknown or blank. */
        public static Kind parse(String s) {
            if (s == null) {
                return OTHER;
            }
            return switch (s.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "building", "build", "structure", "house", "machine", "contraption", "train" -> BUILDING;
                case "creature", "mob", "animal", "pet" -> CREATURE;
                case "picture", "painting" -> PICTURE;
                case "item", "tool", "weapon" -> ITEM;
                case "event", "weather", "disaster" -> EVENT;
                default -> OTHER;
            };
        }
    }

    /** The horizontal unit vector of a Minecraft yaw: {x, z}. */
    static double[] facing(float yaw) {
        double rad = Math.toRadians(yaw);
        return new double[]{-Math.sin(rad), Math.cos(rad)};
    }

    /**
     * <b>The wish-time spot.</b> {@code hit} is what a ray along his gaze struck within
     * {@link #REACH} blocks of loaded world, stopping at a water surface — or null for a miss (open
     * sky, or out of reach).
     */
    public static Spot aim(Snapshot me, Hit hit, World w) {
        if (hit != null) {
            // The air block in FRONT of the face it hit — never the block itself, which put a
            // wish inside the hillside it was aimed at.
            int x = hit.x() + hit.nx(), y = hit.y() + hit.ny(), z = hit.z() + hit.nz();
            int stand = standAt(w, x, y, z);
            return stand == Integer.MIN_VALUE ? new Spot(x, y, z, "hit, nothing below")
                    : new Spot(x, stand, z, (stand == y ? "hit" : "hit, settled " + (y - stand) + " down")
                            + overLava(w, x, stand, z));
        }
        int fx = floor(me.x()), fy = floor(me.y()), fz = floor(me.z());
        int ground = standAt(w, fx, fy, fz);
        int height = ground == Integer.MIN_VALUE ? 0 : Math.max(0, fy - ground);
        // Looking upward: there is no ground along his gaze at all, so the default distance.
        // Otherwise as far out as he is high, so the spot stays within ~45° of his eye line instead
        // of under his feet.
        boolean up = me.pitch() < -5f;
        int d = up ? MIN_MISS : Math.max(MIN_MISS, Math.min(REACH, height));
        double[] f = facing(me.yaw());
        int px = fx, pz = fz;
        int dd = d;
        for (; dd > 0; dd--) {
            px = floor(me.x() + f[0] * dd);
            pz = floor(me.z() + f[1] * dd);
            if (w.at(px, fy, pz) != Cell.UNLOADED) {
                break;     // the last loaded point along his facing
            }
        }
        if (dd <= 0) {
            px = fx;
            pz = fz;
        }
        int stand = standAt(w, px, fy, pz);
        String why = (up ? "miss looking up" : "miss " + height + " above ground") + ", " + dd + " out"
                + (dd < d ? " (last loaded point)" : "");
        return stand == Integer.MIN_VALUE ? new Spot(px, fy, pz, why + ", nothing below")
                : new Spot(px, stand, pz, why + overLava(w, px, stand, pz));
    }

    /**
     * <b>The one slide by kind</b>, along the same gaze line and never sideways. Null when the spot
     * is already in range for the kind (or there is nowhere to stand at the new distance — then it
     * stays where it was, which he has already seen sparkle).
     */
    public static Spot slide(Snapshot me, int ax, int ay, int az, Kind kind, World w) {
        if (kind == null) {
            return null;
        }
        double dx = ax + 0.5 - me.x(), dz = az + 0.5 - me.z();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d >= kind.near && d <= kind.far) {
            return null;
        }
        double want = Math.max(kind.near, Math.min(kind.far, d));
        double ux, uz;
        if (d < 0.5) {
            double[] f = facing(me.yaw());
            ux = f[0];
            uz = f[1];
        } else {
            ux = dx / d;
            uz = dz / d;
        }
        int x = floor(me.x() + ux * want), z = floor(me.z() + uz * want);
        if (w.at(x, ay, z) == Cell.UNLOADED) {
            return null;
        }
        int stand = standAt(w, x, Math.max(ay, floor(me.y())), z);
        if (stand == Integer.MIN_VALUE) {
            return null;
        }
        return new Spot(x, stand, z, "slid " + Math.round(d) + "→" + Math.round(want) + " for a "
                + kind.name().toLowerCase(java.util.Locale.ROOT) + overLava(w, x, stand, z));
    }

    /** {@code ", over lava"} when the spot's floor is lava — it is somewhere to aim, not to stand. */
    static String overLava(World w, int x, int y, int z) {
        return w.at(x, y - 1, z) == Cell.LAVA ? ", over lava" : "";
    }

    /**
     * Is this spot standing on something real? False over lava: {@link #standAt} stops at
     * a lava surface so the spot stays where he aimed, but nothing may be stood on it as it is.
     */
    public static boolean floored(World w, int x, int y, int z) {
        Cell below = w.at(x, y - 1, z);
        return below == Cell.SOLID || below == Cell.FLUID;
    }

    /**
     * Where a thing stands in this column, starting at {@code fromY}: down onto the first solid
     * block or fluid surface below (a lava surface stops it too, and {@link #floored} says it is no
     * floor); up out of solid ground or water (at most {@link #CLIMB}) when
     * it starts inside one. {@link Integer#MIN_VALUE} when there is nowhere — the void, or an
     * unloaded column.
     */
    static int standAt(World w, int x, int fromY, int z) {
        int y = Math.min(Math.max(fromY, w.bottom() + 1), w.top());
        Cell c = w.at(x, y, z);
        if (c == Cell.UNLOADED) {
            return Integer.MIN_VALUE;
        }
        if (c != Cell.AIR) {
            // Inside the ground or the water: climb to the first air above it.
            for (int k = 1; k <= CLIMB && y + k <= w.top(); k++) {
                if (w.at(x, y + k, z) == Cell.AIR) {
                    return y + k;
                }
            }
            return Integer.MIN_VALUE;
        }
        for (; y > w.bottom(); y--) {
            Cell below = w.at(x, y - 1, z);
            if (below == Cell.SOLID || below == Cell.FLUID || below == Cell.LAVA) {
                return y;
            }
            if (below == Cell.UNLOADED) {
                return Integer.MIN_VALUE;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
