package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * <b>Where a creature may be put without dying of it</b>. Pure, no Minecraft: the world is
 * asked through {@link World}, so every case is a row in {@code SafeSpotTest}; the Minecraft half
 * is {@code vanilla.SafePlacement}.
 *
 * <p>Six of his creatures died by accident within minutes of being told they were here — a build
 * landed on one, one was conjured at a garage's anchor, a follower hopped to him while he flew and
 * fell, one hopped beside the lava castle and burned. Every death was a placement nobody checked.
 * Fixing WHERE creatures are put was chosen over a blanket immunity, so this is
 * the one rule every placement asks:
 * <ul>
 *   <li><b>the box fits</b> — nothing it would collide with inside the creature's box;</li>
 *   <li><b>a walker stands on something</b> — a sturdy, non-fluid floor under it, and leaves are
 *   not one;</li>
 *   <li><b>no hazard</b> — no lava, fire or magma in the box or under it, and no deep water;</li>
 *   <li><b>a flier</b> needs only a free box with no hazard; <b>a swimmer</b> needs water.</li>
 * </ul>
 *
 * <p>What a placement does when a spot fails is decided here too ({@link #fix}): a walker over
 * water, lava or open air gets a small FOOTING, so it still appears where he aimed; anything else
 * MOVES to the nearest spot that passes. Where the move is far enough for him to lose it
 * ({@link #SILENT_MOVE}), {@link #told} is what Moddy says.
 *
 * <p><b>Seam </b> (placing a creature IN a build on purpose — a boss on its throne): a
 * named spot is passed to {@link #fix(World, Body, double, double, double, Spot)} and wins whenever
 * it passes the same rule. Nothing names one yet.
 */
public final class SafeSpot {

    /** Moves of at most this many blocks are silent: he still sees it where he looked. */
    public static final double SILENT_MOVE = 3.0;

    /** How far a move looks, sideways. */
    public static final int RADIUS = 8;
    /** How far a move looks up (out of rock) and down (off a roof it was put on). */
    public static final int UP = 32, DOWN = 6;

    /** A footing is the creature's footprint, at least this wide, and at most {@link #FOOTING_MAX}. */
    public static final int FOOTING_MIN = 3, FOOTING_MAX = 7;

    /** Vanilla's follower hop: this many tries, within this many blocks of him. */
    public static final int HOP_TRIES = 10, HOP_REACH = 3;

    private SafeSpot() {
    }

    /** What one block is, as far as putting a creature there goes. */
    public enum Cell {
        /** Nothing in the way and nothing that hurts: air, grass, flowers. */
        OPEN,
        /** Something to stand on: collides, with a sturdy top. */
        FLOOR,
        /** Collides, but is no floor: a fence, a pane, a slab's air half. */
        OBSTACLE,
        /** Collides, and a walker on a canopy falls through the gaps or off the edge. */
        LEAVES,
        WATER,
        LAVA,
        /** Fire, magma, a campfire, a cactus: hurts to be in or on. */
        HAZARD,
        /** Not loaded: never read, never generated. */
        UNLOADED
    }

    /** The world, as the rule needs it. */
    public interface World {
        Cell at(int x, int y, int z);
    }

    /** How a creature gets about. */
    public enum Moves { WALK, FLY, SWIM }

    /** The creature's box, in blocks, and how it moves. */
    public record Body(double width, double height, Moves moves) {
    }

    /** Where a creature stands: its feet, centred. */
    public record Spot(double x, double y, double z) {

        public double distanceTo(Spot o) {
            double dx = x - o.x, dy = y - o.y, dz = z - o.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /** Why a spot fails, or {@link #NONE}. */
    public enum Problem {
        NONE,
        /** Something solid inside the box: rock, a wall, a build. */
        NO_ROOM,
        /** A walker with nothing under it: open air, or leaves. */
        NO_FLOOR,
        /** A walker over water, or in water over its head. */
        OVER_WATER,
        /** A walker over lava. */
        OVER_LAVA,
        /** Lava, fire or magma in the box or under it. */
        HAZARD,
        /** A swimmer with no water. */
        NO_WATER,
        UNLOADED
    }

    private static final double EPS = 1.0e-3;

    /** The blocks a box at {@code (x, y, z)} occupies: {x0, x1, y0, y1, z0, z1}, inclusive. */
    static int[] box(Body b, double x, double y, double z) {
        double hw = Math.max(b.width(), EPS) / 2.0;
        double h = Math.max(b.height(), EPS);
        return new int[]{
                floor(x - hw + EPS), floor(x + hw - EPS),
                floor(y + EPS), floor(y + h - EPS),
                floor(z - hw + EPS), floor(z + hw - EPS)};
    }

    /** <b>The rule.</b> {@link Problem#NONE} when a creature with this body may stand here. */
    public static Problem check(World w, Body b, double x, double y, double z) {
        int[] k = box(b, x, y, z);
        boolean water = false, deep = false;
        for (int bx = k[0]; bx <= k[1]; bx++) {
            for (int by = k[2]; by <= k[3]; by++) {
                for (int bz = k[4]; bz <= k[5]; bz++) {
                    switch (w.at(bx, by, bz)) {
                        case UNLOADED -> {
                            return Problem.UNLOADED;
                        }
                        case LAVA, HAZARD -> {
                            return Problem.HAZARD;
                        }
                        case FLOOR, OBSTACLE, LEAVES -> {
                            return Problem.NO_ROOM;
                        }
                        case WATER -> {
                            water = true;
                            deep |= by > k[2];
                        }
                        case OPEN -> {
                        }
                    }
                }
            }
        }
        if (b.moves() == Moves.SWIM) {
            return water ? Problem.NONE : Problem.NO_WATER;
        }
        if (b.moves() == Moves.FLY) {
            return water ? Problem.OVER_WATER : Problem.NONE;
        }
        if (deep) {
            return Problem.OVER_WATER;      // a walker with water over its head
        }
        // What it stands on: the row under its footprint.
        int below = k[2] - 1;
        boolean floor = false, overWater = false;
        for (int bx = k[0]; bx <= k[1]; bx++) {
            for (int bz = k[4]; bz <= k[5]; bz++) {
                switch (w.at(bx, below, bz)) {
                    case UNLOADED -> {
                        return Problem.UNLOADED;
                    }
                    case LAVA -> {
                        return Problem.OVER_LAVA;
                    }
                    case HAZARD -> {
                        return Problem.HAZARD;
                    }
                    case FLOOR -> floor = true;
                    case WATER -> overWater = true;
                    default -> {
                    }
                }
            }
        }
        if (floor) {
            return Problem.NONE;
        }
        return overWater || water ? Problem.OVER_WATER : Problem.NO_FLOOR;
    }

    /**
     * <b>The nearest spot that passes</b>, within {@link #RADIUS} sideways, {@link #UP} up and
     * {@link #DOWN} down — straight up out of rock, usually. Its column keeps the fraction of the
     * start (a creature centred on a block stays centred); its feet are on a whole block. Null when
     * nothing in reach passes.
     */
    public static Spot nearest(World w, Body b, double x, double y, double z) {
        return nearest(w, b, x, y, z, RADIUS, UP, DOWN);
    }

    public static Spot nearest(World w, Body b, double x, double y, double z, int radius, int up, int down) {
        int fy = floor(y + EPS);
        List<double[]> tries = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -down; dy <= up; dy++) {
                    // Straight up wins a tie with sideways or down: out of rock, onto its top.
                    double cost = dx * dx + dz * dz + dy * dy + (dy < 0 ? 0.5 : 0) + (dx != 0 || dz != 0 ? 0.25 : 0);
                    tries.add(new double[]{cost, dx, dy, dz});
                }
            }
        }
        tries.sort(Comparator.comparingDouble(t -> t[0]));
        for (double[] t : tries) {
            Spot s = new Spot(x + t[1], fy + t[2], z + t[3]);
            if (check(w, b, s.x(), s.y(), s.z()) == Problem.NONE) {
                return s;
            }
        }
        return null;
    }

    /**
     * <b>The follower hop</b>, as vanilla's {@code FollowOwnerGoal} does it: {@link #HOP_TRIES}
     * random spots within {@link #HOP_REACH} blocks of him (never right on top of him), each checked
     * by the rule; null when none passes, and then it does not hop this time.
     *
     * <p><b>No hop for a walker or a swimmer while he is airborne</b>: a walker
     * put at the height of a child flying in Creative falls to its death. It waits, and catches up
     * once he lands. A flier may hop to his height, into a free box.
     */
    public static Spot hop(World w, Body b, double ox, double oy, double oz, boolean ownerAirborne,
                           RandomGenerator random) {
        if (ownerAirborne && b.moves() != Moves.FLY) {
            return null;
        }
        int bx = floor(ox), by = floor(oy + EPS), bz = floor(oz);
        for (int i = 0; i < HOP_TRIES; i++) {
            int dx = random.nextInt(-HOP_REACH, HOP_REACH + 1);
            int dy = random.nextInt(-1, 2);
            int dz = random.nextInt(-HOP_REACH, HOP_REACH + 1);
            if (Math.abs(dx) < 2 && Math.abs(dz) < 2) {
                continue;       // vanilla: not on top of him
            }
            Spot s = new Spot(bx + dx + 0.5, by + dy, bz + dz + 0.5);
            if (check(w, b, s.x(), s.y(), s.z()) == Problem.NONE) {
                return s;
            }
        }
        return null;
    }

    /** What to do about a spot. */
    public sealed interface Fix permits Ok, Footing, Move, Stuck {
    }

    /** It is fine where it is. */
    public record Ok() implements Fix {
    }

    /**
     * Fill these blocks and it stands where he aimed; every cell is {x, y, z}, and {@code why} is
     * what was under it (the Minecraft half fills over lava with obsidian, anything else with
     * stone).
     */
    public record Footing(List<int[]> cells, Problem why) implements Fix {
    }

    /** Put it here instead. */
    public record Move(Spot to, Problem why) implements Fix {
    }

    /** Nothing in reach passes; it stays where it was put (and the caller says so in the log). */
    public record Stuck(Problem why) implements Fix {
    }

    /** {@link #fix(World, Body, double, double, double, Spot)} with no named spot. */
    public static Fix fix(World w, Body b, double x, double y, double z) {
        return fix(w, b, x, y, z, null);
    }

    /**
     * <b>Build or move</b>:
     * <ul>
     *   <li>a walker over water, lava or open air, with room for its box → a {@link Footing}
     *   under its footprint, so it still appears where he aimed;</li>
     *   <li>anything else that fails (inside rock, in a hazard, a swimmer with no water, a footing
     *   too big for a small one) → {@link Move} to the {@link #nearest} spot that passes.</li>
     * </ul>
     *
     * @param named the seam: a spot something named on purpose, which wins when it passes
     */
    public static Fix fix(World w, Body b, double x, double y, double z, Spot named) {
        if (named != null && check(w, b, named.x(), named.y(), named.z()) == Problem.NONE) {
            return new Move(named, Problem.NONE);
        }
        Problem p = check(w, b, x, y, z);
        if (p == Problem.NONE) {
            return new Ok();
        }
        if (p == Problem.UNLOADED) {
            return new Stuck(p);
        }
        if (b.moves() == Moves.WALK
                && (p == Problem.OVER_WATER || p == Problem.OVER_LAVA || p == Problem.NO_FLOOR)) {
            List<int[]> cells = footing(w, b, x, y, z);
            if (cells != null && check(overlay(w, cells), b, x, y, z) == Problem.NONE) {
                return new Footing(cells, p);
            }
        }
        Spot to = nearest(w, b, x, y, z);
        return to == null ? new Stuck(p) : new Move(to, p);
    }

    /**
     * The blocks under its footprint (at least {@link #FOOTING_MIN} square) that are water, lava or
     * open air — or null when the footprint is wider than {@link #FOOTING_MAX}, or the box itself
     * is under water (a footing at the surface would not be under its feet).
     */
    static List<int[]> footing(World w, Body b, double x, double y, double z) {
        int[] k = box(b, x, y, z);
        int below = k[2] - 1;
        int x0 = k[0], x1 = k[1], z0 = k[4], z1 = k[5];
        while (x1 - x0 + 1 < FOOTING_MIN) {
            x0--;
            if (x1 - x0 + 1 < FOOTING_MIN) {
                x1++;
            }
        }
        while (z1 - z0 + 1 < FOOTING_MIN) {
            z0--;
            if (z1 - z0 + 1 < FOOTING_MIN) {
                z1++;
            }
        }
        if (x1 - x0 + 1 > FOOTING_MAX || z1 - z0 + 1 > FOOTING_MAX) {
            return null;
        }
        List<int[]> cells = new ArrayList<>();
        for (int bx = x0; bx <= x1; bx++) {
            for (int bz = z0; bz <= z1; bz++) {
                Cell c = w.at(bx, below, bz);
                if (c == Cell.OPEN || c == Cell.WATER || c == Cell.LAVA) {
                    cells.add(new int[]{bx, below, bz});
                }
            }
        }
        return cells.isEmpty() ? null : cells;
    }

    /** {@code w} with these cells made floor — what the world will be once a footing is in. */
    public static World overlay(World w, List<int[]> floors) {
        java.util.Set<Long> at = new java.util.HashSet<>();
        for (int[] c : floors) {
            at.add(key(c[0], c[1], c[2]));
        }
        return (x, y, z) -> at.contains(key(x, y, z)) ? Cell.FLOOR : w.at(x, y, z);
    }

    // ── what Moddy says ────────────────────────────────────────────────────────────────────────

    /** Why it moved, in his terms. */
    public enum Why {
        /** The spot he picked had no room — rock, a wall. */
        PICKED_SPOT_SOLID,
        /** The spot he picked was lava, fire, or water over its head. */
        PICKED_SPOT_DANGEROUS,
        /** The spot he picked had nothing to stand on. */
        PICKED_SPOT_NO_FLOOR,
        /** A swimmer put where there is no water. */
        PICKED_SPOT_DRY,
        /** A new building was about to go up right where it stood. */
        BUILDING
    }

    /** The {@link Why} for a spawn that failed for {@code p}. */
    public static Why whyFor(Problem p) {
        return switch (p) {
            case HAZARD, OVER_LAVA, OVER_WATER -> Why.PICKED_SPOT_DANGEROUS;
            case NO_FLOOR -> Why.PICKED_SPOT_NO_FLOOR;
            case NO_WATER -> Why.PICKED_SPOT_DRY;
            default -> Why.PICKED_SPOT_SOLID;
        };
    }

    /**
     * <b>What Moddy says about a move</b>, or null for one of at most {@link #SILENT_MOVE} blocks
     * — he still sees it where he looked. E.g. <i>"Your dragon is just above — the spot you picked
     * was inside the rock!"</i>
     */
    public static String told(String name, Spot from, Spot to, Why why) {
        if (from == null || to == null || from.distanceTo(to) <= SILENT_MOVE) {
            return null;
        }
        String who = name == null || name.isBlank() ? "creature" : name.strip();
        String because = switch (why) {
            case PICKED_SPOT_SOLID -> "the spot you picked was inside the rock!";
            case PICKED_SPOT_DANGEROUS -> "the spot you picked wasn't safe to stand in!";
            case PICKED_SPOT_NO_FLOOR -> "the spot you picked had nothing to stand on!";
            case PICKED_SPOT_DRY -> "it needs water to swim in!";
            case BUILDING -> "your new building was going up right where it stood!";
        };
        return "Your " + who + " is " + where(from, to) + " — " + because;
    }

    /** "just above", "a few steps over"… — where {@code to} is from {@code from}, in his terms. */
    static String where(Spot from, Spot to) {
        double dy = to.y() - from.y();
        double flat = Math.hypot(to.x() - from.x(), to.z() - from.z());
        if (dy > 0 && dy >= flat) {
            return dy <= 12 ? "just above" : "up on top";
        } else if (dy < 0 && -dy >= flat) {
            return "just below";
        }
        return flat <= 10 ? "a few steps over" : "a little way over";
    }

    // ── a PLAYER in the way ──────────────────────────────────────────────────────────

    /**
     * How far a PLAYER is searched for once {@link #nearest} finds nothing: a child buried in the
     * middle of a wished volcano is further than {@link #RADIUS} from its side and may be more than
     * {@link #UP} under its top. One wider pass, once, only when the ordinary one failed.
     */
    public static final int WIDE_RADIUS = 24, WIDE_UP = 96;

    /** Why a player was moved — his own lines, never a creature's. */
    public enum YouWhy {
        /** A build was about to go up where he stood. */
        BUILDING,
        /** His home spot (where he respawns) has something built on it. */
        HOME_BURIED,
        /** He was taking damage inside blocks, for whatever reason. */
        STUCK
    }

    /**
     * A spot a player must be moved OUT of: no room (inside blocks) or burning. Not "no floor" or
     * "over water" — a child standing on a ledge or swimming is where he chose to be.
     */
    public static boolean buried(Problem p) {
        return p == Problem.NO_ROOM || p == Problem.HAZARD || p == Problem.OVER_LAVA;
    }

    /** {@link #nearest}, then once {@link #WIDE_RADIUS}/{@link #WIDE_UP} when that finds nothing. */
    public static Spot nearestOrWider(World w, Body b, double x, double y, double z) {
        Spot s = nearest(w, b, x, y, z);
        return s != null ? s : nearest(w, b, x, y, z, WIDE_RADIUS, WIDE_UP, DOWN);
    }

    /**
     * What Moddy tells a player he moved, or null for a step of at most {@link #SILENT_MOVE} — except
     * a buried home, which is always said: he respawns somewhere that is not quite home, and should
     * know why.
     */
    public static String toldYou(Spot from, Spot to, YouWhy why) {
        if (from == null || to == null) {
            return null;
        }
        if (why != YouWhy.HOME_BURIED && from.distanceTo(to) <= SILENT_MOVE) {
            return null;
        }
        String where = where(from, to);
        return switch (why) {
            case BUILDING -> "You're " + where + " — your new building was going up right where you stood!";
            case HOME_BURIED -> "Something was built on your home spot, so you're " + where + " instead!";
            case STUCK -> "You were stuck inside blocks, so I popped you out — you're " + where + "!";
        };
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
