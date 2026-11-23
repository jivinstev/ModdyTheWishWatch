package com.moddingfromamod.place;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.builder.BuildAutoSpots;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildSpot;
import com.moddingfromamod.builder.BuildSpots;

/**
 * <b>Where {@code at: "<thing>:<where>"} is, once the thing has been found</b> — the one
 * rule every placing tool shares, so "on the roof" means the same block to a dragon, a cake, a
 * meteor and a lighthouse.
 *
 * <p>The server side ({@code PlaceFinder}) finds the THING — a building standing in the world, a
 * creature, the child — and hands it here as a {@link Subject}; this answers the WHERE. Nothing
 * here reads the live world, so every rule is a row in {@code PlaceResolverTest}.
 */
public final class PlaceResolver {

    private PlaceResolver() {
    }

    /** What an {@code at} named, once found. */
    public sealed interface Subject permits Building, Body {
    }

    /**
     * A building standing at {@code ax,ay,az}; {@code towardDx/Dz} is where he stood when it went
     * up, relative to the anchor (which wall is its front when it has no door).
     */
    public record Building(BuildPlan plan, int ax, int ay, int az, int towardDx, int towardDz) implements Subject {
    }

    /**
     * A creature or the child: its feet at {@code x,y,z}, looking {@code yaw} (Minecraft degrees,
     * south = 0), {@code width} across and {@code height} tall.
     */
    public record Body(String id, boolean isMe, double x, double y, double z, float yaw, double width,
                       double height) implements Subject {
    }

    /**
     * Where it resolved to.
     *
     * @param x,y,z  the FEET of whatever is put there (a block centre for a building's spot)
     * @param yaw    which way it faces, or {@code NaN} for "however it was facing"
     * @param ring   {@code 0} for one spot; otherwise the radius of the ring {@code around} names,
     *               centred on x,z — never put one thing ON the centre ({@link #onRing})
     * @param link   {@code "castle:top"}, what the agent is told and the wish remembers
     * @param spot   the spot's own name ({@code top})
     * @param top    for a building: the y of the top of the column at x,z (an event lands on it);
     *               for a body, the top of its head
     * @param why    null when it resolved; otherwise what to tell the agent, with what it offers
     */
    public record Resolved(double x, double y, double z, float yaw, double ring, String link, String spot,
                           double top, String why) {

        public boolean ok() {
            return why == null;
        }

        static Resolved no(String why) {
            return new Resolved(0, 0, 0, Float.NaN, 0, null, null, 0, why);
        }
    }

    /** The words a creature or the child answers to (a building's are {@link BuildSpots#names}). */
    public static final List<String> BODY_SPOTS = List.of("front", "behind", "beside", "top", "around",
            "north", "east", "south", "west", "here");

    /** How far in front of / beside a body a thing is put, past its own half-width. */
    public static final double BODY_GAP = 1.5;
    /** How far around a body the ring runs, past its half-width. */
    public static final double BODY_RING = 3.0;

    /**
     * <b>Resolve {@code where} on {@code s}</b>; an empty where takes {@code dflt} (each tool's
     * own: {@code front} for a creature or a thing, {@code top} for an event, …).
     */
    public static Resolved resolve(Subject s, String where, String dflt) {
        String w = BuildSpot.normalise(where == null || where.isBlank() ? dflt : where);
        if (w.isEmpty()) {
            w = BuildSpots.FRONT;
        }
        return switch (s) {
            case Building b -> building(b, w);
            case Body b -> body(b, w);
        };
    }

    private static Resolved building(Building b, String w) {
        BuildSpot spot = BuildSpots.spot(b.plan(), w, b.towardDx(), b.towardDz());
        String id = b.plan().id();
        if (spot == null) {
            return Resolved.no("'" + id + "' has no spot called '" + w + "' (it has: "
                    + BuildSpots.names(b.plan()) + ")");
        }
        boolean around = BuildAutoSpots.AROUND.equals(spot.name())
                || (!w.equals(spot.name()) && BuildAutoSpots.AROUND.equals(w));
        double ring = around ? BuildAutoSpots.aroundRadius(b.plan()) : 0;
        Float yaw = spot.yaw();
        double x = b.ax() + spot.dx() + 0.5, y = b.ay() + spot.dy(), z = b.az() + spot.dz() + 0.5;
        return new Resolved(x, y, z, yaw == null ? Float.NaN : yaw, ring, id + ":" + spot.name(), spot.name(),
                y, null);
    }

    private static Resolved body(Body b, String w0) {
        String w = switch (w0) {
            case "in_front", "infront", "ahead", "front_of", "facing" -> "front";
            case "back", "rear", "behind_me" -> "behind";
            case "next_to", "nextto", "by", "side", "near", "nearby", "beside_me", "with" -> "beside";
            case "on", "on_top", "over", "above", "head", "on_head", "back_of", "riding" -> "top";
            case "ring", "round", "surrounding", "all_around" -> "around";
            case "feet", "at", "on_it", "self", "itself" -> "here";
            case "n" -> "north";
            case "e" -> "east";
            case "s" -> "south";
            case "w" -> "west";
            default -> w0;
        };
        String who = b.isMe() ? At.ME : b.id();
        double r = Math.toRadians(b.yaw());
        // Minecraft: yaw 0 looks south (+z); forward is (-sin, +cos).
        double fx = -Math.sin(r), fz = Math.cos(r);
        double out = b.width() / 2.0 + BODY_GAP;
        float facingIt = (float) wrap(b.yaw() + 180.0);
        return switch (w) {
            case "front" -> new Resolved(b.x() + fx * out, b.y(), b.z() + fz * out, facingIt, 0, who + ":front",
                    "front", b.y(), null);
            case "behind" -> new Resolved(b.x() - fx * out, b.y(), b.z() - fz * out, b.yaw(), 0, who + ":behind",
                    "behind", b.y(), null);
            // Its RIGHT hand: forward turned clockwise seen from above, which in Minecraft's axes
            // (x east, z south) is (-fz, fx).
            case "beside" -> new Resolved(b.x() - fz * out, b.y(), b.z() + fx * out, b.yaw(), 0, who + ":beside",
                    "beside", b.y(), null);
            case "top" -> new Resolved(b.x(), b.y() + b.height(), b.z(), b.yaw(), 0, who + ":top", "top",
                    b.y() + b.height(), null);
            case "around" -> new Resolved(b.x(), b.y(), b.z(), Float.NaN, b.width() / 2.0 + BODY_RING,
                    who + ":around", "around", b.y(), null);
            case "here" -> new Resolved(b.x(), b.y(), b.z(), b.yaw(), 0, who, "here", b.y(), null);
            case "north" -> compass(b, who, "north", 0, -1, out);
            case "east" -> compass(b, who, "east", 1, 0, out);
            case "south" -> compass(b, who, "south", 0, 1, out);
            case "west" -> compass(b, who, "west", -1, 0, out);
            default -> Resolved.no((b.isMe() ? "he" : "'" + b.id() + "'") + " has no spot called '" + w0
                    + "' (" + (b.isMe() ? "he has" : "a creature has") + ": " + String.join(", ", BODY_SPOTS)
                    + ")" + (w.startsWith("in") ? " — only a BUILDING has an inside" : ""));
        };
    }

    private static Resolved compass(Body b, String who, String name, int dx, int dz, double out) {
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        return new Resolved(b.x() + dx * out, b.y(), b.z() + dz * out, yaw, 0, who + ":" + name, name, b.y(), null);
    }

    private static double wrap(double deg) {
        double d = deg % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        }
        if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    /**
     * <b>The {@code index}th of {@code count} things put on a resolved place.</b> A point: the
     * first on it, the rest in a small herd round it ({@code gap} apart). A ring: spread evenly
     * round it, the first at {@code startYaw} (the way the child is, so the first one is the one he
     * sees), each facing OUT. Returns {x, z, yaw} (yaw NaN when the place had none).
     */
    public static double[] onRing(Resolved r, int index, int count, double startYaw, double gap) {
        if (r.ring() <= 0) {
            if (index <= 0) {
                return new double[]{r.x(), r.z(), r.yaw()};
            }
            int[][] herd = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
            int[] h = herd[(index - 1) % herd.length];
            int k = 1 + (index - 1) / herd.length;
            return new double[]{r.x() + h[0] * gap * k, r.z() + h[1] * gap * k, r.yaw()};
        }
        int n = Math.max(1, count);
        double a = Math.toRadians(startYaw) + 2 * Math.PI * index / n;
        double x = r.x() - Math.sin(a) * r.ring(), z = r.z() + Math.cos(a) * r.ring();
        return new double[]{x, z, wrap(Math.toDegrees(a))};
    }

    /** Every spot a building answers to, own and automatic, as {@code at} strings — for a refusal. */
    public static List<String> offered(BuildPlan plan) {
        List<String> out = new ArrayList<>();
        for (String n : BuildSpots.names(plan).split(", ")) {
            out.add(plan.id() + ":" + n);
        }
        return out;
    }
}
