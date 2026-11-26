package com.moddingfromamod.redstone;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Routes a buried wire on one level ({@code y}) from a control's port to one of a module's ports. The rules
 * (docs/REDSTONE.md §3):
 * <ul>
 *   <li>Every cell on the way must be air or natural ground, with no fluid next to it and nothing locked or built
 *       there.</li>
 *   <li>No wire cell touches redstone or a mechanism part that is not its own (no cross-talk), and the path never
 *       touches itself except at the step it came from.</li>
 *   <li>A repeater goes on a straight stretch before the signal would fall below what the far end needs; it faces
 *       the way the signal comes from (Minecraft's repeater {@code facing} names its input side).</li>
 * </ul>
 */
public final class ChannelRouter {

    /** Cells further than this from the start-goal box are not searched. */
    public static final int MARGIN = 12;
    /** The longest run the router will build. */
    public static final int MAX_LENGTH = 120;

    /** What the route found. Empty cells and a reason when it found nothing. */
    public record Route(List<RsCell> cells, int arrival, int length, String why, Map<RsPos, Integer> strengths) {
        public Route(List<RsCell> cells, int arrival, int length, String why) {
            this(cells, arrival, length, why, Map.of());
        }

        public boolean ok() {
            return why == null;
        }
    }

    private ChannelRouter() {
    }

    /**
     * @param world     the world
     * @param y         channel level
     * @param start     the control's port (already a wire cell, signal {@code strength})
     * @param strength  the signal at {@code start}
     * @param goals     the module's ports (any one will do)
     * @param need      the strength that must arrive at a goal
     * @param ownCells  positions of every cell already in the plan (avoided, except as the path's own ends)
     * @param foreign   positions of redstone already in the world that is not ours (kept away from)
     */
    public static Route route(WorldView world, int y, RsPos start, int strength, List<RsPos> goals, int need,
                              Set<RsPos> ownCells, Set<RsPos> foreign) {
        return route(world, y, start, strength, goals, need, ownCells, Set.of(), foreign);
    }

    /**
     * As above, with {@code inert}: planned cells the wire may not occupy but may pass beside, because touching them
     * changes nothing (a repeater's side, a stone support). Only wire and strongly powered blocks need keeping apart.
     */
    public static Route route(WorldView world, int y, RsPos start, int strength, List<RsPos> goals, int need,
                              Set<RsPos> ownCells, Set<RsPos> inert, Set<RsPos> foreign) {
        Set<RsPos> goalSet = new HashSet<>(goals);
        int minX = start.x(), maxX = start.x(), minZ = start.z(), maxZ = start.z();
        for (RsPos g : goals) {
            minX = Math.min(minX, g.x()); maxX = Math.max(maxX, g.x());
            minZ = Math.min(minZ, g.z()); maxZ = Math.max(maxZ, g.z());
        }
        minX -= MARGIN; maxX += MARGIN; minZ -= MARGIN; maxZ += MARGIN;
        Map<RsPos, RsPos> parent = new HashMap<>();
        ArrayDeque<RsPos> q = new ArrayDeque<>();
        parent.put(start, start);
        q.add(start);
        RsPos found = goalSet.contains(start) ? start : null;
        while (!q.isEmpty() && found == null) {
            RsPos c = q.poll();
            for (RsDir d : RsDir.values()) {
                RsPos n = c.step(d);
                if (parent.containsKey(n) || n.x() < minX || n.x() > maxX || n.z() < minZ || n.z() > maxZ) {
                    continue;
                }
                boolean goal = goalSet.contains(n);
                if (!goal && !free(world, n, ownCells, inert, foreign)) {
                    continue;
                }
                if (touchesOwnPath(n, c, parent)) {
                    continue;
                }
                parent.put(n, c);
                if (goal) {
                    found = n;
                    break;
                }
                q.add(n);
            }
        }
        if (found == null) {
            return new Route(List.of(), 0, 0, "no buried path for the wire between the control and the mechanism "
                    + "(built rooms, water or other redstone in the way)");
        }
        List<RsPos> path = new ArrayList<>();
        for (RsPos p = found; ; p = parent.get(p)) {
            path.add(0, p);
            if (p.equals(start)) {
                break;
            }
        }
        if (path.size() > MAX_LENGTH) {
            return new Route(List.of(), 0, path.size(), "the wire would be " + path.size()
                    + " blocks long; the most is " + MAX_LENGTH + ", so the control has to be closer");
        }
        return lay(world, path, strength, need);
    }

    /** Wire (and repeaters where the signal runs low) along {@code path}; path[0] is the start, already laid. */
    static Route lay(WorldView world, List<RsPos> path, int strength, int need) {
        List<RsCell> cells = new ArrayList<>();
        Map<RsPos, Integer> strengths = new HashMap<>();
        int s = strength;
        for (int i = 1; i < path.size(); i++) {
            RsPos p = path.get(i);
            RsPos prev = path.get(i - 1);
            boolean last = i == path.size() - 1;
            int remaining = path.size() - 1 - i;              // wire cells after this one
            boolean straight = !last && RsDir.between(prev, p) == RsDir.between(p, path.get(i + 1));
            // a repeater only where the signal would otherwise arrive too weak, and only once it is running low
            boolean repeat = straight && i >= 2 && s - 1 - remaining < need && s - 1 <= need + 6;
            RsPos support = p.up(-1);
            if (world.kind(support) == WorldView.Kind.AIR) {
                cells.add(RsCell.of(support, "minecraft:stone", RsCell.Role.CASING));
            }
            if (repeat) {
                RsDir in = RsDir.between(p, prev);
                cells.add(RsCell.of(p, "minecraft:repeater", RsCell.Role.CHANNEL, "facing", in.id(), "delay", "1"));
                s = 15;
            } else {
                cells.add(RsCell.of(p, "minecraft:redstone_wire", RsCell.Role.CHANNEL));
                s--;
            }
            strengths.put(p, s);
            if (s < 1) {
                return new Route(List.of(), 0, path.size(), "the signal would die before reaching the mechanism "
                        + "(no straight stretch for a repeater)");
            }
        }
        if (s < need) {
            return new Route(List.of(), s, path.size(), "the signal arrives at " + s + " but the mechanism needs "
                    + need + " (no straight stretch for a repeater near the end)");
        }
        return new Route(cells, s, path.size(), null, strengths);
    }

    static boolean free(WorldView world, RsPos n, Set<RsPos> own, Set<RsPos> foreign) {
        return free(world, n, own, Set.of(), foreign);
    }

    static boolean free(WorldView world, RsPos n, Set<RsPos> own, Set<RsPos> inert, Set<RsPos> foreign) {
        WorldView.Kind k = world.kind(n);
        if (k != WorldView.Kind.AIR && k != WorldView.Kind.NATURAL) {
            return false;
        }
        if (own.contains(n) || foreign.contains(n)) {
            return false;
        }
        WorldView.Kind below = world.kind(n.up(-1));
        if (below == WorldView.Kind.FLUID || below == WorldView.Kind.OTHER
                || below == WorldView.Kind.LOCKED && !world.conductor(n.up(-1))) {
            return false;
        }
        for (RsDir d : RsDir.values()) {
            RsPos side = n.step(d);
            if (world.kind(side) == WorldView.Kind.FLUID || foreign.contains(side)
                    || own.contains(side) && !inert.contains(side)) {
                return false;
            }
            // wire also connects one up and one down at a step: keep those clear of other redstone
            if (foreign.contains(side.up(1)) || foreign.contains(side.up(-1))) {
                return false;
            }
        }
        return world.kind(n.up(1)) != WorldView.Kind.FLUID;
    }

    /** True when {@code n} would touch the path that leads to {@code from}, other than {@code from} itself. */
    static boolean touchesOwnPath(RsPos n, RsPos from, Map<RsPos, RsPos> parent) {
        Set<RsPos> path = new HashSet<>();
        for (RsPos p = parent.get(from); p != null && !path.contains(p); p = parent.get(p)) {
            path.add(p);
            if (parent.get(p).equals(p)) {
                break;
            }
        }
        path.remove(from);
        for (RsDir d : RsDir.values()) {
            if (path.contains(n.step(d))) {
                return true;
            }
        }
        return false;
    }
}
