package com.moddingfromamod.builder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * <b>Scaling a building by ROLE instead of multiplying every number</b> (docs/BUILD_CIRCULATION_PLAN.md).
 *
 * <p>Each shape gets a role, inferred from what it already is: a shell, a fixture, a ladder, a doorway, a tread of a
 * stair run, or a piece of a WAY (the post behind a ladder, the hole or shaft it climbs through). Then, per axis, the
 * building is laid out like a 9-slice picture: the distinct edges of its shapes cut the axis into intervals; a
 * doorway's width and height are RIGID, a one-block wall or floor is a UNIT (one block at every size), and everything
 * else is ELASTIC and shares what is left of the scaled length. Two shapes that shared an edge still share it. Fixtures
 * keep their size and stay against what they were against; a ladder and its way move as one thing; a stair run is
 * re-walked, one tread per block of rise; a doorway is cut on until it is through its wall.
 *
 * <p>At factor 1 it returns the same blocks ({@code BuildScaleCorpusTest}). Pure: no Minecraft. The Builder's
 * lowering calls it unless {@code partscale=off} ({@link #enabled()}).
 */
public final class BuildScale {

    private BuildScale() {
    }

    /**
     * Is the Builder's scaling this one? On since it was graded;
     * {@code -Ptestprops=partscale=off} is the old multiply-every-number arm, kept as the A/B seam.
     */
    public static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty("moddingfromamod.partscale"));
    }

    /**
     * Does a one-block wall, floor or post thicken at factor 2? No (decision 1): a doorway in a
     * one-block wall still takes a door and a storey keeps its headroom. The other arm is kept for the measurement.
     */
    static boolean UNIT_GROWS = "1".equals(System.getenv("CIRC_THICK"));

    static java.util.Map<Long, String> compose(List<BuildShape> shapes) {
        java.util.Map<Long, String> out = new java.util.HashMap<>();
        for (BuildShape s : shapes) {
            String block = air(s) ? null : s.block();
            s.forEachBlock((x, y, z) -> out.put(ComposedPlan.key(x, y, z), block));
        }
        return out;
    }

    public enum Role { SHELL, FIXTURE, LADDER, DOORWAY, TREAD, WAY, UNDER }

    /** A shape, its role, its stair run (or -1), and for a doorway the axis its WIDTH runs along (0 x, 2 z, -1 both). */
    public record Part(BuildShape s, Role role, int run, int widthAxis) {
    }

    public static String base(String block) {
        return block.replaceFirst("\\[.*", "").replaceFirst("^minecraft:", "");
    }

    static boolean air(BuildShape s) {
        String b = base(s.block());
        return b.equals("air") || b.equals("cave_air");
    }

    static int lo(BuildShape s, int axis) {
        return axis == 0 ? s.originX() : axis == 1 ? s.dy() : s.originZ();
    }

    static int len(BuildShape s, int axis) {
        return axis == 0 ? s.width() : axis == 1 ? s.height() : s.depth();
    }

    static boolean fixtureBlock(String b) {
        return b.endsWith("_bed") || b.endsWith("_door") || b.endsWith("_trapdoor") || b.endsWith("_sign")
                || b.endsWith("_banner") || b.endsWith("_fence_gate") || b.equals("chest") || b.equals("barrel");
    }

    // ── inference ────────────────────────────────────────────────────────────────────────────────

    public static List<Part> infer(List<BuildShape> shapes) {
        int n = shapes.size();
        int[] run = new int[n];
        Arrays.fill(run, -1);
        int runs = 0;
        for (int i = 0; i < n; ) {
            int j = i;
            // A run goes one way: up (a stair) or down (a slide's chute, drawn from its top; filmed coming apart at
            // size 4 when only rising runs were known).
            int way = i + 1 < n ? shapes.get(i + 1).dy() - shapes.get(i).dy() : 0;
            while (j + 1 < n && Math.abs(way) == 1 && nextTread(shapes.get(j), shapes.get(j + 1), way)) {
                j++;
            }
            if (j - i + 1 >= 3) {
                for (int k = i; k <= j; k++) {
                    run[k] = runs;
                }
                runs++;
            }
            i = j + 1;
        }
        Map<Long, String> cells = compose(shapes);
        List<Part> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BuildShape s = shapes.get(i);
            String b = base(s.block());
            boolean one = s.width() == 1 && s.height() == 1 && s.depth() == 1;
            if (run[i] >= 0) {
                out.add(new Part(s, Role.TREAD, run[i], -1));
            } else if ((b.equals("ladder") || b.equals("vine") || b.equals("scaffolding")) && s.width() == 1 && s.depth() == 1) {
                out.add(new Part(s, Role.LADDER, -1, -1));
            } else if (!air(s) && (one || fixtureBlock(b))) {   // air joins a fixture below, if it is cut out of one
                out.add(new Part(s, Role.FIXTURE, -1, -1));
            } else if (air(s) && s.kind() == BuildShape.Kind.BOX && s.height() >= 2 && s.height() <= 3
                    && ((s.width() == 1 && s.depth() <= 2) || (s.depth() == 1 && s.width() <= 2))) {
                out.add(new Part(s, Role.DOORWAY, -1, widthAxis(s, cells)));
            } else {
                out.add(new Part(s, Role.SHELL, -1, -1));
            }
        }
        // Air cut out of a fixture (the gap in a counter of barrels) is part of that fixture.
        for (int i = 0; i < n; i++) {
            BuildShape a = out.get(i).s();
            if (out.get(i).role() != Role.SHELL || !air(a)) {
                continue;
            }
            for (int j = 0; j < i; j++) {
                BuildShape x = out.get(j).s();
                boolean inside = out.get(j).role() == Role.FIXTURE;
                for (int ax = 0; ax < 3 && inside; ax++) {
                    inside = lo(x, ax) <= lo(a, ax) && lo(a, ax) + len(a, ax) <= lo(x, ax) + len(x, ax);
                }
                if (inside) {
                    out.set(i, new Part(a, Role.FIXTURE, -1, -1));
                    break;
                }
            }
        }
        // What holds a tread up (a column drawn under it) belongs to the stair: it is re-made with the treads.
        for (int i = 0; i < n; i++) {
            if (out.get(i).role() != Role.TREAD) {
                continue;
            }
            BuildShape t = out.get(i).s();
            for (int j = 0; j < n; j++) {
                BuildShape u = out.get(j).s();
                if (out.get(j).role() == Role.SHELL && !air(u) && u.kind() == BuildShape.Kind.BOX
                        && u.dy() + u.height() == t.dy()
                        && t.originX() <= u.originX() && u.originX() + u.width() <= t.originX() + t.width()
                        && t.originZ() <= u.originZ() && u.originZ() + u.depth() <= t.originZ() + t.depth()) {
                    out.set(j, new Part(u, Role.UNDER, out.get(i).run(), i));
                }
            }
        }
        // A ladder's WAY: the post behind it, the hatch or hole over it, the shaft round it. Small things in or beside
        // its column; they keep their plan and move with it, so a ladder never parts from its exit (§2½).
        for (int i = 0; i < n; i++) {
            if (out.get(i).role() != Role.LADDER) {
                continue;
            }
            BuildShape l = out.get(i).s();
            for (int j = 0; j < n; j++) {
                BuildShape w = out.get(j).s();
                if (out.get(j).role() != Role.SHELL || w.width() > 3 || w.depth() > 3
                        || w.dy() > l.dy() + l.height() + 1 || l.dy() > w.dy() + w.height() + 1) {
                    continue;
                }
                boolean holds = w.originX() <= l.originX() && l.originX() < w.originX() + w.width()
                        && w.originZ() <= l.originZ() && l.originZ() < w.originZ() + w.depth();
                boolean beside = w.originX() - 1 <= l.originX() && l.originX() <= w.originX() + w.width()
                        && w.originZ() - 1 <= l.originZ() && l.originZ() <= w.originZ() + w.depth();
                int[] back = behind(l);
                boolean backing = w.width() == 1 && w.depth() == 1 && back != null
                        && w.originX() == l.originX() + back[0] && w.originZ() == l.originZ() + back[1];
                // The hole or shaft he climbs through (air in or beside the column), the tube round it, the post behind it.
                if ((air(w) && beside) || (w.hollow() && holds) || backing) {
                    out.set(j, new Part(w, Role.WAY, -1, -1));
                }
            }
        }
        return out;
    }

    /** Where the block a ladder hangs on is, from its {@code facing} (it faces away from that block); null if unsaid. */
    static int[] behind(BuildShape ladder) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("facing=(north|south|east|west)").matcher(ladder.block());
        if (!m.find()) {
            return null;
        }
        return switch (m.group(1)) {
            case "north" -> new int[] {0, 1};
            case "south" -> new int[] {0, -1};
            case "west" -> new int[] {1, 0};
            default -> new int[] {-1, 0};
        };
    }

    /** Consecutive treads: the same flat box, one block higher (or lower: {@code way}), close by. */
    static boolean nextTread(BuildShape a, BuildShape b, int way) {
        return a.kind() == BuildShape.Kind.BOX && b.kind() == BuildShape.Kind.BOX && !air(a)
                && a.height() == 1 && b.height() == 1 && a.width() == b.width() && a.depth() == b.depth()
                && base(a.block()).equals(base(b.block())) && b.dy() == a.dy() + way
                && Math.abs(b.originX() - a.originX()) <= a.width() + 1 && Math.abs(b.originZ() - a.originZ()) <= a.depth() + 1;
    }

    /** Which way a doorway's width runs: the axis with a solid jamb each side at his feet; -1 when unsure. */
    static int widthAxis(BuildShape s, Map<Long, String> cells) {
        return widthAxisScaled(s, cells);
    }

    // ── the per-axis layout ──────────────────────────────────────────────────────────────────────

    /** One axis: the old edges and where each lands. */
    static final class Axis {
        final int[] edge;
        final int[] to;

        Axis(int[] edge, int[] to) {
            this.edge = edge;
            this.to = to;
        }

        double real(double c, double f) {
            int n = edge.length;
            if (c <= edge[0]) {
                return to[0] + (c - edge[0]) * f;
            }
            if (c >= edge[n - 1]) {
                return to[n - 1] + (c - edge[n - 1]) * f;
            }
            for (int i = 0; i + 1 < n; i++) {
                if (c <= edge[i + 1]) {
                    return to[i] + (c - edge[i]) * (to[i + 1] - to[i]) / (double) (edge[i + 1] - edge[i]);
                }
            }
            return c * f;
        }

        int at(int c, double f) {
            int i = Arrays.binarySearch(edge, c);
            return i >= 0 ? to[i] : (int) Math.round(real(c, f));
        }

        /** A thing {@code size} long at {@code c} that keeps its size: it stays against the edge it was against. */
        int snap(int c, int size, double f) {
            int n = edge.length;
            for (int i = 0; i + 1 < n; i++) {
                if (c >= edge[i] && c < edge[i + 1]) {
                    int below = c - edge[i];
                    int above = edge[i + 1] - (c + size);
                    if (below <= 1 && below <= Math.abs(above)) {
                        return to[i] + below;
                    }
                    if (above >= 0 && above <= 1) {
                        return to[i + 1] - above - size;
                    }
                    double mid = real(c + size / 2.0, f);
                    return (int) Math.round(mid - size / 2.0);
                }
            }
            return at(c, f);
        }
    }

    static Axis axis(List<Part> parts, int axis, double f) {
        TreeSet<Integer> edges = new TreeSet<>();
        if (axis == 1) {
            edges.add(0);
        }
        for (Part p : parts) {
            boolean in = p.role() == Role.SHELL || p.role() == Role.DOORWAY
                    || ((p.role() == Role.LADDER || p.role() == Role.WAY) && axis == 1);
            if (in) {
                edges.add(lo(p.s(), axis));
                edges.add(lo(p.s(), axis) + len(p.s(), axis));
            }
        }
        int[] e = edges.stream().mapToInt(Integer::intValue).toArray();
        int m = e.length - 1;
        int[] size = new int[Math.max(m, 0)];
        // 0 elastic, 1 unit (a one-block wall, floor, post), 2 rigid (a doorway's width or height, a ring's height)
        int[] cls = new int[Math.max(m, 0)];
        for (int i = 0; i < m; i++) {
            int a = e[i];
            int b = e[i + 1];
            for (Part p : parts) {
                BuildShape s = p.s();
                int l = lo(s, axis);
                int h = l + len(s, axis);
                boolean rigid = false;
                if (p.role() == Role.DOORWAY) {
                    rigid = axis == 1 || p.widthAxis() == -1 || p.widthAxis() == axis;
                } else if (p.role() == Role.SHELL && axis == 1 && s.hollow() && s.height() <= 2 && !air(s)) {
                    rigid = true;   // a ring (a fence, a railing) stays a ring: at three tall it would grow caps
                }
                if (rigid && l <= a && b <= h) {
                    cls[i] = 2;
                } else if (cls[i] == 0 && p.role() == Role.SHELL && !air(s) && b - a == 1 && l == a && h == b) {
                    cls[i] = 1;
                }
            }
        }
        int unit = UNIT_GROWS ? Math.max(1, (int) Math.floor(f)) : 1;
        int[] to = new int[e.length];
        if (axis != 1) {
            // Across: the whole width is solved at once and stays about its own middle, so a round tower stays
            // centred on its door and its ladder. (Solved as two halves about 0, a tower came out half a block off
            // its ladder, and the ladder's hole through each floor was at the edge of the round well: seen on film.)
            solve(e, cls, size, 0, m, f, unit, axis);
            int total = 0;
            for (int i = 0; i < m; i++) {
                total += size[i];
            }
            to[0] = m == 0 ? (int) Math.round(e[0] * f) : (int) Math.floor((e[0] + e[m]) / 2.0 * f - total / 2.0 + 0.5);
            for (int i = 0; i < m; i++) {
                to[i + 1] = to[i] + size[i];
            }
            return new Axis(e, to);
        }
        // Up and down: the ground plane stays where it is.
        int zero = Arrays.binarySearch(e, 0);
        solve(e, cls, size, zero, m, f, unit, axis);
        solve(e, cls, size, 0, zero, f, unit, axis);
        to[zero] = 0;
        for (int i = zero; i < m; i++) {
            to[i + 1] = to[i] + size[i];
        }
        for (int i = zero - 1; i >= 0; i--) {
            to[i] = to[i + 1] - size[i];
        }
        return new Axis(e, to);
    }

    /** Sizes for intervals {@code from..until-1}: rigid and unit first, the rest of the scaled length shared out. */
    static void solve(int[] e, int[] cls, int[] size, int from, int until, double f, int unit, int axis) {
        if (until <= from) {
            return;
        }
        int total = (int) Math.round((e[until] - e[from]) * f);
        int fixed = 0;
        int elastic = 0;
        for (int i = from; i < until; i++) {
            int l = e[i + 1] - e[i];
            if (cls[i] == 2) {
                size[i] = l;
                fixed += l;
            } else if (cls[i] == 1) {
                size[i] = unit;
                fixed += unit;
            } else {
                elastic += l;
            }
        }
        if (elastic == 0) {
            return;
        }
        int budget = Math.max(0, total - fixed);
        double[] want = new double[until];
        int given = 0;
        for (int i = from; i < until; i++) {
            if (cls[i] == 0) {
                int l = e[i + 1] - e[i];
                want[i] = l * (double) budget / elastic;
                size[i] = (int) Math.floor(want[i]);
                given += size[i];
            }
        }
        double middle = (e[from] + e[until]) / 2.0;
        boolean[] passed = new boolean[until];
        while (given < budget) {
            // Largest remainder; on a tie, the one nearest the middle. An interval and its MIRROR IMAGE about the middle
            // grow together or not at all: a spare block given to one side of a pair put a glass dome half a block
            // off the air inside it, and the sea came in through the gap (the underwater dome at size 3, on film).
            int best = -1;
            for (int i = from; i < until; i++) {
                if (cls[i] != 0 || passed[i]) {
                    continue;
                }
                double r = want[i] - size[i];
                double rb = best < 0 ? -1 : want[best] - size[best];
                double di = Math.abs((e[i] + e[i + 1]) / 2.0 - middle);
                double db = best < 0 ? 0 : Math.abs((e[best] + e[best + 1]) / 2.0 - middle);
                if (best < 0 || r > rb + 1e-9 || (Math.abs(r - rb) <= 1e-9 && di < db - 1e-9)) {
                    best = i;
                }
            }
            if (best < 0) {
                // only halves of pairs are left and one block to give: the pair nearest the middle takes it after all
                java.util.Arrays.fill(passed, false);
                for (int i = from; i < until; i++) {
                    if (cls[i] == 0 && (best < 0 || Math.abs((e[i] + e[i + 1]) / 2.0 - middle) < Math.abs((e[best] + e[best + 1]) / 2.0 - middle))) {
                        best = i;
                    }
                }
                size[best]++;
                given++;
                continue;
            }
            int mirror = -1;
            for (int i = from; i < until; i++) {
                if (i != best && cls[i] == 0 && e[i + 1] - e[i] == e[best + 1] - e[best]
                        && Math.abs((e[i] + e[i + 1]) / 2.0 - middle + ((e[best] + e[best + 1]) / 2.0 - middle)) < 1e-9
                        && size[i] == size[best]) {
                    mirror = i;
                }
            }
            if (mirror >= 0 && budget - given < 2) {
                passed[best] = true;
                passed[mirror] = true;
                continue;
            }
            size[best]++;
            given++;
            if (mirror >= 0) {
                size[mirror]++;
                given++;
            }
        }
        for (int i = from; i < until; i++) {
            if (cls[i] == 0) {
                int l = e[i + 1] - e[i];
                // Never smaller than a person needs: headroom three, a passage two; growing never shrinks anything.
                int least = f >= 1 ? l : Math.min(l, axis == 1 ? 3 : 2);
                size[i] = Math.max(size[i], least);
            }
        }
    }

    // ── scaling ──────────────────────────────────────────────────────────────────────────────────

    /** The scaled shapes, and where an old coordinate lands on each axis. */
    public record Scaled(List<BuildShape> shapes, java.util.function.IntUnaryOperator[] map) {
    }

    public static List<BuildShape> scale(List<BuildShape> shapes, double f) {
        return scaled(shapes, f).shapes();
    }

    public static Scaled scaled(List<BuildShape> shapes, double f) {
        List<Part> parts = infer(shapes);
        Axis[] ax = {axis(parts, 0, f), axis(parts, 1, f), axis(parts, 2, f)};
        int n = parts.size();
        // Fixtures and ladders that touch move as one thing in plan (a bed's two halves, a hatch's ladder).
        int[] group = new int[n];
        for (int i = 0; i < n; i++) {
            group[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (loose(parts.get(i)) && loose(parts.get(j)) && touching(parts.get(i).s(), parts.get(j).s())) {
                    int a = find(group, i);
                    int b = find(group, j);
                    group[Math.max(a, b)] = Math.min(a, b);
                }
            }
        }
        int[][] shift = new int[n][];
        for (int i = 0; i < n; i++) {
            if (!loose(parts.get(i)) || find(group, i) != i) {
                continue;
            }
            int x0 = Integer.MAX_VALUE;
            int x1 = Integer.MIN_VALUE;
            int z0 = Integer.MAX_VALUE;
            int z1 = Integer.MIN_VALUE;
            for (int j = 0; j < n; j++) {
                if (loose(parts.get(j)) && find(group, j) == i) {
                    BuildShape s = parts.get(j).s();
                    x0 = Math.min(x0, s.originX());
                    x1 = Math.max(x1, s.originX() + s.width());
                    z0 = Math.min(z0, s.originZ());
                    z1 = Math.max(z1, s.originZ() + s.depth());
                }
            }
            int y0 = Integer.MAX_VALUE;
            int y1 = Integer.MIN_VALUE;
            for (int j = 0; j < n; j++) {
                if (loose(parts.get(j)) && find(group, j) == i) {
                    y0 = Math.min(y0, parts.get(j).s().dy());
                    y1 = Math.max(y1, parts.get(j).s().dy() + parts.get(j).s().height());
                }
            }
            int[] box = {x0, y0, z0, x1, y1, z1};
            shift[i] = new int[] {hosted(parts, ax, 0, box, f) - x0, hosted(parts, ax, 2, box, f) - z0};
        }
        Map<Long, String> drawn = compose(shapes);
        List<BuildShape> out = new ArrayList<>();
        Map<String, Integer> widthOf = new java.util.HashMap<>();   // a doorway's width axis, by its place in `out`
        int doneRun = -1;
        for (int i = 0; i < n; i++) {
            Part p = parts.get(i);
            BuildShape s = p.s();
            switch (p.role()) {
                case TREAD -> {
                    if (p.run() != doneRun) {
                        doneRun = p.run();
                        List<BuildShape> treads = new ArrayList<>();
                        List<BuildShape> under = new ArrayList<>();
                        for (int k = 0; k < n; k++) {
                            Part q = parts.get(k);
                            if (q.role() == Role.TREAD && q.run() == p.run()) {
                                treads.add(q.s());
                                BuildShape holds = null;
                                for (Part u : parts) {
                                    if (u.role() == Role.UNDER && u.widthAxis() == k) {
                                        holds = u.s();
                                    }
                                }
                                under.add(holds);
                            }
                        }
                        if (treads.size() > 1 && treads.get(0).dy() > treads.get(1).dy()) {
                            java.util.Collections.reverse(treads);   // re-walked from its foot, whichever end was drawn first
                            java.util.Collections.reverse(under);
                        }
                        out.addAll(run(treads, under, ax, f));
                    }
                }
                case UNDER -> {
                    // re-made with its stair
                }
                case FIXTURE, LADDER, WAY -> {
                    int[] d = shift[find(group, i)];
                    if (p.role() == Role.WAY && air(s)) {
                        widthOf.put("" + out.size(), -9);   // a hole or shaft: cut through what is over and under it
                    }
                    boolean tall = p.role() != Role.FIXTURE;
                    // A thing that HANGS (nothing under it, something over it: a lantern, a swing's seat) stays hung.
                    boolean hangs = !tall && drawn.get(ComposedPlan.key(s.originX(), s.dy() - 1, s.originZ())) == null
                            && drawn.get(ComposedPlan.key(s.originX(), s.dy() + s.height(), s.originZ())) != null;
                    int y = tall ? ax[1].at(s.dy(), f) : hangs ? ax[1].at(s.dy() + s.height(), f) - s.height() : hosted(parts, ax, 1,
                            new int[] {s.originX(), s.dy(), s.originZ(), s.originX() + s.width(), s.dy() + s.height(),
                                    s.originZ() + s.depth()}, f);
                    int h = tall ? Math.max(1, ax[1].at(s.dy() + s.height(), f) - y) : s.height();
                    out.add(new BuildShape(s.kind(), s.originX() + d[0], y, s.originZ() + d[1], s.width(), h, s.depth(),
                            s.block(), s.hollow(), BuildShape.Anchor.CORNER, s.name(), s.gen()));
                }
                default -> {
                    if (p.role() == Role.DOORWAY) {
                        widthOf.put("" + out.size(), p.widthAxis());
                    }
                    int x = ax[0].at(s.originX(), f);
                    int y = ax[1].at(s.dy(), f);
                    int z = ax[2].at(s.originZ(), f);
                    out.add(new BuildShape(s.kind(), x, y, z,
                            Math.max(1, ax[0].at(s.originX() + s.width(), f) - x),
                            Math.max(1, ax[1].at(s.dy() + s.height(), f) - y),
                            Math.max(1, ax[2].at(s.originZ() + s.depth(), f) - z),
                            s.block(), s.hollow(), BuildShape.Anchor.CORNER, s.name(), s.gen()));
                }
            }
        }
        pierce(out, widthOf, f);
        backLadders(out, f);
        return new Scaled(out, new java.util.function.IntUnaryOperator[] {
                c -> ax[0].at(c, f), c -> ax[1].at(c, f), c -> ax[2].at(c, f)});
    }

    /**
     * Where a thing that keeps its size lands on one axis. A thing set INTO a shape's own face (a gate in a fence, a
     * pane in a wall, a hatch in a floor) stays in that face; anything else stays against the edge it was against.
     */
    static int hosted(List<Part> parts, Axis[] ax, int axis, int[] box, double f) {
        int c = box[axis];
        int size = box[axis + 3] - c;
        int high = Integer.MIN_VALUE;
        for (Part p : parts) {
            BuildShape h = p.s();
            if (p.role() != Role.SHELL || air(h)) {
                continue;
            }
            boolean holds = true;
            for (int a = 0; a < 3; a++) {
                holds &= lo(h, a) <= box[a] && box[a + 3] <= lo(h, a) + len(h, a);
            }
            if (!holds) {
                continue;
            }
            if (lo(h, axis) == c) {
                return ax[axis].at(c, f);
            }
            if (lo(h, axis) + len(h, axis) == c + size) {
                high = ax[axis].at(c + size, f) - size;
            }
        }
        return high != Integer.MIN_VALUE ? high : ax[axis].snap(c, size, f);
    }

    /** How far a ladder is moved toward its wall before it is given a post of its own instead. */
    static final int LADDER_SHIFT_MOST = 3;

    /**
     * Every rung needs a block behind it, or the ladder falls off as items (seen on film: the lighthouse and the
     * treehouse at size 3). A round wall's inside face is not where its box's edge is, so after scaling a ladder is
     * moved toward its wall until every rung has one; if nothing is within {@link #LADDER_SHIFT_MOST}, the missing
     * backing is filled with a post of the block the rest of it hangs on.
     */
    static void backLadders(List<BuildShape> out, double f) {
        if (f == 1.0) {
            return;
        }
        for (int i = 0; i < out.size(); i++) {
            BuildShape l = out.get(i);
            String b = base(l.block());
            int[] back = (b.equals("ladder") && l.width() == 1 && l.depth() == 1) ? behind(l) : null;
            if (back == null) {
                continue;
            }
            List<BuildShape> others = new ArrayList<>(out);
            others.remove(i);
            Map<Long, String> cells = compose(others);
            // The ladder stays in the OPEN: it never moves into a cell something is drawn in (a wall's own blocks, or
            // a band drawn after it, would cut it — seen on film: the lighthouse's ladder moved into its wall and its
            // stripes took rungs out).
            int free = 0;
            while (free < LADDER_SHIFT_MOST && open(cells, l, back, free + 1)) {
                free++;
            }
            int best = -1;
            for (int k = 0; k <= free && best < 0; k++) {
                boolean ok = open(cells, l, back, k);
                for (int y = l.dy(); y < l.dy() + l.height() && ok; y++) {
                    ok = cells.get(ComposedPlan.key(l.originX() + back[0] * (k + 1), y, l.originZ() + back[1] * (k + 1))) != null;
                }
                if (ok) {
                    best = k;
                }
            }
            int k = best >= 0 ? best : free;
            int lx = l.originX() + back[0] * k;
            int lz = l.originZ() + back[1] * k;
            if (k > 0) {
                out.set(i, new BuildShape(l.kind(), lx, l.dy(), lz, 1, l.height(), 1, l.block(), l.hollow(),
                        BuildShape.Anchor.CORNER, l.name(), l.gen()));
            }
            if (best < 0) {
                String post = null;
                int lowest = Integer.MAX_VALUE;
                int highest = Integer.MIN_VALUE;
                for (int y = l.dy(); y < l.dy() + l.height(); y++) {
                    String at = cells.get(ComposedPlan.key(lx + back[0], y, lz + back[1]));
                    if (at == null) {
                        lowest = Math.min(lowest, y);
                        highest = Math.max(highest, y);
                    } else if (post == null) {
                        post = at.replaceFirst("\\[.*", "");
                    }
                }
                if (lowest <= highest) {
                    // Put LAST but one: after everything that might clear the cell, before the ladder is re-added.
                    BuildShape ladder = out.remove(i);
                    out.add(new BuildShape(BuildShape.Kind.BOX, lx + back[0], lowest, lz + back[1], 1,
                            highest - lowest + 1, 1, post == null ? "minecraft:oak_planks" : post, false,
                            BuildShape.Anchor.CORNER, l.name() + "_backing", true));
                    out.add(i, ladder);
                }
            }
        }
    }

    /** Is every cell of this ladder, moved {@code k} toward its wall, empty of anything else? */
    static boolean open(Map<Long, String> cells, BuildShape l, int[] back, int k) {
        for (int y = l.dy(); y < l.dy() + l.height(); y++) {
            long key = ComposedPlan.key(l.originX() + back[0] * k, y, l.originZ() + back[1] * k);
            if (cells.get(key) != null) {
                return k == 0;   // where it was drawn it stays, whatever it overwrote there
            }
        }
        return true;
    }

    /** How far a doorway is cut on to find the air behind a wall that grew thicker than it (a round wall does). */
    static final int PIERCE_MOST = 5;

    /**
     * A doorway goes THROUGH its wall. A hollow cylinder's wall thickens with its radius and a solid wall may double, so
     * a doorway that keeps a person's size is cut on, along the way he walks, until his feet find air on both sides.
     */
    static void pierce(List<BuildShape> out, Map<String, Integer> widthOf, double f) {
        if (f == 1.0) {
            return;
        }
        Map<Long, String> cells = compose(out);
        for (int i = 0; i < out.size(); i++) {
            BuildShape s = out.get(i);
            if (!widthOf.containsKey("" + i)) {
                continue;
            }
            if (widthOf.get("" + i) == -9) {
                // A vertical opening (§2½): the same cut, turned on its back. A lid of up to three blocks over a
                // shaft, or a floor of up to three under a hatch, with open space beyond it, is cut through.
                int up = 0;
                while (up <= 3 && cells.get(ComposedPlan.key(s.originX(), s.dy() + s.height() + up, s.originZ())) != null) {
                    up++;
                }
                int down = 0;
                while (down <= 3 && cells.get(ComposedPlan.key(s.originX(), s.dy() - 1 - down, s.originZ())) != null) {
                    down++;
                }
                up = up > 3 ? 0 : up;
                down = down > 3 || s.dy() - down < 0 && s.dy() >= 0 ? 0 : down;   // never dug below the ground it stands on
                if (up + down > 0) {
                    out.set(i, new BuildShape(s.kind(), s.originX(), s.dy() - down, s.originZ(), s.width(),
                            s.height() + up + down, s.depth(), s.block(), s.hollow(), BuildShape.Anchor.CORNER, s.name(), s.gen()));
                }
                continue;
            }
            int wa = widthOf.get("" + i) >= 0 ? widthOf.get("" + i) : widthAxisScaled(s, cells);
            if (wa < 0) {
                continue;
            }
            boolean alongZ = wa == 0;   // he walks along z when the width runs along x
            int x = s.originX();
            int z = s.originZ();
            int w = s.width();
            int d = s.depth();
            int before = 0;
            int after = 0;
            while (before < PIERCE_MOST && cells.get(ComposedPlan.key(alongZ ? x : x - before - 1, s.dy(), alongZ ? z - before - 1 : z)) != null) {
                before++;
            }
            while (after < PIERCE_MOST && cells.get(ComposedPlan.key(alongZ ? x : x + w + after, s.dy(), alongZ ? z + d + after : z)) != null) {
                after++;
            }
            if (before == PIERCE_MOST) {
                before = 0;   // no air that way within reach: that side is ground or mass, not a wall
            }
            if (after == PIERCE_MOST) {
                after = 0;
            }
            if (before + after > 0) {
                out.set(i, new BuildShape(s.kind(), alongZ ? x : x - before, s.dy(), alongZ ? z - before : z,
                        alongZ ? w : w + before + after, s.height(), alongZ ? d + before + after : d,
                        s.block(), s.hollow(), BuildShape.Anchor.CORNER, s.name(), s.gen()));
            }
        }
    }

    /** The scaled doorway's width axis: the one with a jamb on both sides at his feet, anywhere along its depth. */
    static int widthAxisScaled(BuildShape s, Map<Long, String> cells) {
        int x0 = s.originX();
        int z0 = s.originZ();
        boolean jx = false;
        boolean jz = false;
        for (int k = 0; k < s.depth(); k++) {
            jx |= cells.get(ComposedPlan.key(x0 - 1, s.dy(), z0 + k)) != null && cells.get(ComposedPlan.key(x0 + s.width(), s.dy(), z0 + k)) != null;
        }
        for (int k = 0; k < s.width(); k++) {
            jz |= cells.get(ComposedPlan.key(x0 + k, s.dy(), z0 - 1)) != null && cells.get(ComposedPlan.key(x0 + k, s.dy(), z0 + s.depth())) != null;
        }
        return jx == jz ? -1 : jx ? 0 : 2;
    }

    static boolean loose(Part p) {
        return p.role() == Role.FIXTURE || p.role() == Role.LADDER || p.role() == Role.WAY;
    }

    static int find(int[] g, int i) {
        while (g[i] != i) {
            i = g[i];
        }
        return i;
    }

    static boolean touching(BuildShape a, BuildShape b) {
        for (int axis = 0; axis < 3; axis++) {
            if (lo(a, axis) > lo(b, axis) + len(b, axis) || lo(b, axis) > lo(a, axis) + len(a, axis)) {
                return false;
            }
        }
        return true;
    }

    /** A stair run re-walked: one tread per block of rise, along the path the treads drew. */
    static List<BuildShape> run(List<BuildShape> treads, List<BuildShape> under, Axis[] ax, double f) {
        int n = treads.size();
        BuildShape first = treads.get(0);
        int w = Math.max(1, (int) Math.round(first.width() * f));
        int d = Math.max(1, (int) Math.round(first.depth() * f));
        if (f == 1.0) {
            w = first.width();
            d = first.depth();
        }
        double[] cx = new double[n];
        double[] cz = new double[n];
        for (int k = 0; k < n; k++) {
            cx[k] = ax[0].real(treads.get(k).originX() + first.width() / 2.0, f);
            cz[k] = ax[2].real(treads.get(k).originZ() + first.depth() / 2.0, f);
        }
        int bottom = ax[1].at(first.dy(), f);
        int top = ax[1].at(first.dy() + n, f) - 1;
        int steps = Math.max(1, top - bottom + 1);
        // +1/-1: every tread steps the same way along x; +2/-2: along z; 0: it turns (a spiral).
        int straight = 0;
        for (int k = 0; k + 1 < n; k++) {
            int ddx = treads.get(k + 1).originX() - treads.get(k).originX();
            int ddz = treads.get(k + 1).originZ() - treads.get(k).originZ();
            int way = ddz == 0 && ddx != 0 ? Integer.signum(ddx) : ddx == 0 && ddz != 0 ? 2 * Integer.signum(ddz) : 0;
            if (way == 0 || (k > 0 && way != straight)) {
                straight = 0;
                break;
            }
            straight = way;
        }
        List<BuildShape> out = new ArrayList<>();
        for (int j = 0; j < steps; j++) {
            double u = steps == 1 ? 0 : j * (n - 1) / (double) (steps - 1);
            int k = Math.min(n - 1, (int) Math.floor(u));
            int k1 = Math.min(n - 1, k + 1);
            double t = u - k;
            double x = cx[k] + (cx[k1] - cx[k]) * t;
            double z = cz[k] + (cz[k1] - cz[k]) * t;
            int tx = (int) Math.round(x - w / 2.0);
            int tz = (int) Math.round(z - d / 2.0);
            // A straight flight that came out steeper than one step forward per step up is laid back from its TOP, one
            // block a tread, so every riser is one block; its foot then stands further out, on the ground.
            if (straight != 0 && f != 1.0) {
                boolean alongX = Math.abs(straight) == 1;
                int topAt = (int) Math.round((alongX ? cx[n - 1] : cz[n - 1]) - (alongX ? w : d) / 2.0);
                int footAt = (int) Math.round((alongX ? cx[0] : cz[0]) - (alongX ? w : d) / 2.0);
                if (Math.abs(topAt - footAt) < steps - 1) {
                    int at = topAt - Integer.signum(straight) * (steps - 1 - j);
                    if (alongX) {
                        tx = at;
                    } else {
                        tz = at;
                    }
                }
            }
            BuildShape holds = under.get(Math.min(n - 1, (int) Math.round(u)));
            if (holds != null) {
                if (f == 1.0) {
                    out.add(holds);
                } else {
                    int from = ax[1].at(holds.dy(), f);
                    if (bottom + j > from) {
                        out.add(new BuildShape(BuildShape.Kind.BOX, tx, from, tz, w, bottom + j - from, d, holds.block(), false,
                                BuildShape.Anchor.CORNER, "under" + (j + 1), true));
                    }
                }
            }
            out.add(new BuildShape(BuildShape.Kind.BOX, tx, bottom + j, tz,
                    w, 1, d, first.block(), false, BuildShape.Anchor.CORNER, "step" + (j + 1), f != 1.0));
        }
        return out;
    }
}
