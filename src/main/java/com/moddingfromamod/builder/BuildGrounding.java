package com.moddingfromamod.builder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * <b>Nothing in a build floats</b> (): the deterministic check, on the blocks as they will stand
 * (the plan composed, {@link BuildDoors}' doors hung), never a model's opinion.
 *
 * <p>A LUMP is a set of blocks that touch, a corner counting (a stepped roof's layers and a spiral stair's steps touch
 * only at their edges, and stand). A lump is GROUNDED when its lowest block is at the ground ({@code dy ≤ 0}: the
 * Builder stands {@code dy 0} on the world's ground, which is at {@code −1}). Every other lump stands on nothing.
 *
 * <p>An AIRBORNE build (no lump reaches the ground at all: a floating island, a sky castle, a balloon) keeps its
 * exemption, as {@link BuildFooting} does: its largest lump is its body, and only the OTHER lumps float — its parts must
 * not float off it.
 *
 * <p> found this by hand in five Builder buildings: a mushroom village's stems drawn from where their author meant
 * their middle ({@code dy} is a shape's base), a glowstone in mid-air in a temple's shrine, a well's chain under a
 * hollow roof, a ship's masts a block above its deck. Pure: {@code BuildGroundingTest}, and the corpus in
 * {@code corpus/build-grounding/} ({@code BuildGroundingCorpusTest}).
 */
public final class BuildGrounding {

    private BuildGrounding() {
    }

    /**
     * One lump that stands on nothing: how many blocks, its lowest block and where, and the GAP under it — the fewest
     * blocks of air between its bottom and whatever is under it (another lump, or the ground), over every column it
     * stands on; {@code -1} when nothing at all is under it in any column.
     */
    public record Floating(int blocks, String lowest, int x, int y, int z, int gap, Set<Long> cells) {
        public String describe() {
            String what = lowest.replaceFirst("\\[.*", "").replaceFirst("^minecraft:", "").replace('_', ' ');
            return blocks + " block" + (blocks == 1 ? "" : "s") + " (" + what + ") at dx" + x + " dy" + y + " dz" + z
                    + (gap < 0 ? ", over nothing" : ", " + gap + " block" + (gap == 1 ? "" : "s") + " above what is under it");
        }
    }

    /** The result: whether the build is airborne (its body exempt), and every lump that floats. */
    public record Report(boolean airborne, List<Floating> floating) {
        public boolean clean() {
            return floating.isEmpty();
        }
    }

    /** The plan as it will stand: its blocks with {@link BuildDoors}' rewrite over them. */
    public static Report of(BuildPlan plan) {
        if (plan == null || plan.shapes().isEmpty()) {
            return new Report(false, List.of());
        }
        Map<Long, String> c = BuildDoors.applied(plan, BuildDoors.of(plan));
        c.values().removeIf(Objects::isNull);
        return of(c);
    }

    /** The same over a block map keyed by {@code ComposedPlan.key} (no air in it). */
    public static Report of(Map<Long, String> c) {
        List<Set<Long>> lumps = new ArrayList<>();
        List<Long> lowest = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (long k : c.keySet()) {
            if (!seen.add(k)) {
                continue;
            }
            Set<Long> lump = new HashSet<>();
            Deque<Long> q = new ArrayDeque<>();
            q.add(k);
            long low = k;
            while (!q.isEmpty()) {
                long m = q.poll();
                lump.add(m);
                if (ComposedPlan.ky(m) < ComposedPlan.ky(low)) {
                    low = m;
                }
                int x = ComposedPlan.kx(m);
                int y = ComposedPlan.ky(m);
                int z = ComposedPlan.kz(m);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long n = ComposedPlan.key(x + dx, y + dy, z + dz);
                            if (c.containsKey(n) && seen.add(n)) {
                                q.add(n);
                            }
                        }
                    }
                }
            }
            lumps.add(lump);
            lowest.add(low);
        }
        boolean anyGrounded = false;
        int body = -1;
        for (int i = 0; i < lumps.size(); i++) {
            anyGrounded |= ComposedPlan.ky(lowest.get(i)) <= 0;
            if (body < 0 || lumps.get(i).size() > lumps.get(body).size()) {
                body = i;
            }
        }
        boolean airborne = !anyGrounded && !lumps.isEmpty();
        List<Floating> out = new ArrayList<>();
        for (int i = 0; i < lumps.size(); i++) {
            long low = lowest.get(i);
            boolean stands = airborne ? i == body : ComposedPlan.ky(low) <= 0;
            if (!stands) {
                Set<Long> lump = lumps.get(i);
                out.add(new Floating(lump.size(), c.get(low), ComposedPlan.kx(low), ComposedPlan.ky(low),
                        ComposedPlan.kz(low), gap(c, lump, airborne), lump));
            }
        }
        out.sort((a, b) -> a.y() != b.y() ? Integer.compare(a.y(), b.y())
                : a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()));
        return new Report(airborne, out);
    }

    /** The fewest air blocks under the lump's bottom, over its columns, before a block of the build (or the ground). */
    static int gap(Map<Long, String> c, Set<Long> lump, boolean airborne) {
        int best = -1;
        for (long k : lump) {
            int x = ComposedPlan.kx(k);
            int y = ComposedPlan.ky(k);
            int z = ComposedPlan.kz(k);
            if (lump.contains(ComposedPlan.key(x, y - 1, z))) {
                continue;   // not the bottom of its column
            }
            for (int d = 1; d <= 512; d++) {
                int under = y - d;
                boolean ground = !airborne && under < 0;
                if (ground || c.containsKey(ComposedPlan.key(x, under, z))) {
                    int g = d - 1;
                    best = best < 0 ? g : Math.min(best, g);
                    break;
                }
                if (under < -512) {
                    break;
                }
            }
        }
        return best;
    }

    /**
     * What {@code build_structure} tells the agent: advice, never a refusal. Empty when nothing floats. A
     * child sees a part hanging in the air as a broken build, so each is named with where it is and how far it hangs.
     */
    public static String note(Report r) {
        if (r == null || r.clean()) {
            return "";
        }
        StringBuilder s = new StringBuilder(" " + r.floating().size() + " part" + (r.floating().size() == 1 ? "" : "s")
                + " of this build stand" + (r.floating().size() == 1 ? "s" : "") + " on nothing"
                + (r.airborne() ? " (it floats on purpose; these float off it)" : "") + ": ");
        int shown = 0;
        for (Floating f : r.floating()) {
            if (shown++ == 4) {
                s.append("; and ").append(r.floating().size() - 4).append(" more");
                break;
            }
            s.append(shown > 1 ? "; " : "").append(f.describe());
        }
        return s.append(". A shape's dy is its BASE (centre anchor or not): start a stem, post or trunk at the ground, hang a"
                + " lantern or chain from the block above it, and let every part touch what holds it up.").toString();
    }

    // ── the fix (PR 2): a placement rewrite, like BuildDoors — never saved into the plan ─────────────────────

    /** A light the fix may hang from the block above it (with a chain) when it floats. */
    static boolean light(String block) {
        String b = base(block);
        return b.endsWith("lantern") || b.equals("glowstone") || b.equals("shroomlight") || b.endsWith("froglight")
                || b.equals("redstone_lamp") || b.equals("end_rod") || b.equals("iron_chain") || b.endsWith("candle");
    }

    /** A stem, post, trunk or column the fix may extend straight down to what is under it. */
    static boolean post(String block) {
        String b = base(block);
        return b.endsWith("_stem") || b.endsWith("_log") || b.endsWith("_wood") || b.endsWith("_hyphae")
                || (b.endsWith("_fence") && !b.endsWith("_gate")) || b.endsWith("_wall") || b.contains("pillar")
                || b.equals("iron_chain") || b.endsWith("_bars") || b.equals("bamboo_block");
    }

    private static String base(String block) {
        return block == null ? "" : block.replaceFirst("\\[.*", "").replaceFirst("^minecraft:", "");
    }

    /** The most chain a light is hung on, the most a post is extended, and the biggest gap a lump is dropped by. */
    public static final int HANG_MOST = 12;
    public static final int EXTEND_MOST = 16;
    public static final int DROP_MOST = 2;
    /** A light lump bigger than this is not a lamp (a glowstone wall), and is not hung. */
    static final int LAMP_MOST = 27;
    /** A lump bigger than this is a part of the design (a tower, a pool), and is never moved: it is reported. */
    public static final int DROP_LUMP_MOST = 200;

    /**
     * The rewrite: {@code cells} to place over the plan's own blocks as it goes up ({@code minecraft:air} where a
     * dropped lump left), what was done to each lump ({@code actions}, for the agent), and what still floats.
     */
    public record Fixed(Map<Long, String> cells, List<String> actions, Report before, Report after) {
        public static Fixed none() {
            return new Fixed(Map.of(), List.of(), new Report(false, List.of()), new Report(false, List.of()));
        }
    }

    /**
     * <b>OFF by default</b> (reviewed before it changes what players get): the rewrite runs only
     * with {@code groundfix=on}. The advice ({@code FLOATING —}) is on either way; it changes nothing.
     */
    public static boolean enabled() {
        return "on".equalsIgnoreCase(System.getProperty("moddingfromamod.groundfix"));
    }

    /** The fix for this plan as it will stand (doors hung), when switched on ({@code groundfix=on}). Pure. */
    public static Fixed fix(BuildPlan plan) {
        return enabled() ? compute(plan) : Fixed.none();
    }

    /** The rewrite whether or not it is switched on: what {@link #fix} would place (the corpus and the tests). */
    public static Fixed compute(BuildPlan plan) {
        if (plan == null || plan.shapes().isEmpty()) {
            return Fixed.none();
        }
        Map<Long, String> c = BuildDoors.applied(plan, BuildDoors.of(plan));
        c.values().removeIf(Objects::isNull);
        return fix(c, openings(plan));
    }

    /**
     * The OPENINGS the plan cuts on purpose: the cells of every air shape named as a way through (a door, doorway, gate,
     * entrance, hatch, window, arch, opening). The fix never fills one. Other air shapes are not openings: a "cut"
     * that hollows a sphere or a "clear" that empties a deck claims space the fix may need (an earlier issue's stems and masts
     * stood exactly over such cuts).
     */
    static Set<Long> openings(BuildPlan plan) {
        Set<Long> out = new HashSet<>();
        for (BuildShape s : plan.shapes()) {
            String n = s.name() == null ? "" : s.name().toLowerCase(java.util.Locale.ROOT);
            if (BuildSupport.isAir(s.block()) && n.matches(".*(door|gate|entrance|entry|hatch|window|arch|opening).*")) {
                s.forEachBlock((x, y, z) -> out.add(ComposedPlan.key(x, y, z)));
            }
        }
        return out;
    }

    /** {@link #fix(Map, Set)} with nothing the plan cleared on purpose. */
    public static Fixed fix(Map<Long, String> c) {
        return fix(c, Set.of());
    }

    /**
     * The fix over a block map (no air in it). Each floating lump gets the FIRST rule that applies, in order:
     * <ol>
     *   <li><b>hang</b> — a lamp (lights and chain only, at most {@value #LAMP_MOST} blocks) with a block of the build
     *       above it within {@value #HANG_MOST}: a chain from its top up to that block, and a lantern turned to hang;</li>
     *   <li><b>extend</b> — a lump whose bottom is a stem, post, trunk or column: each column of it (a bottom block with
     *       more of the post above it, so a hollow stem's walls and not its floor) carried straight down, in its own
     *       block, to what is under it, if that is within {@value #EXTEND_MOST};</li>
     *   <li><b>drop</b> — a lump of at most {@value #DROP_LUMP_MOST} blocks whose gap is at most {@value #DROP_MOST},
     *       moved straight down by the gap, if every block it moves into is empty (a bigger lump is a tower or a pool:
     *       moving it is a design change, so it is reported);</li>
     *   <li>else it is left, and reported.</li>
     * </ol>
     * Nothing outside the lump and its new support changes, and nothing is put in an opening the plan cut on purpose
     * (a doorway, a window): {@code cleared}, {@link #openings}.
     */
    public static Fixed fix(Map<Long, String> c, Set<Long> cleared) {
        Report before = of(c);
        if (before.clean()) {
            return new Fixed(Map.of(), List.of(), before, before);
        }
        Map<Long, String> w = new java.util.HashMap<>(c);
        List<String> actions = new ArrayList<>();
        for (Floating f : before.floating()) {
            String done = null;
            boolean lamp = f.blocks() <= LAMP_MOST && f.cells().stream().allMatch(k -> light(c.get(k)));
            if (lamp) {
                done = hang(w, f, cleared);
            }
            if (done == null && f.cells().stream().anyMatch(k -> post(c.get(k)))) {
                done = extend(w, f, before.airborne(), cleared);
            }
            if (done == null) {
                done = drop(w, f);
            }
            actions.add(done != null ? done : "left " + f.describe() + " (no rule fits)");
        }
        Map<Long, String> cells = new java.util.LinkedHashMap<>();
        for (Map.Entry<Long, String> e : w.entrySet()) {
            if (!e.getValue().equals(c.get(e.getKey()))) {
                cells.put(e.getKey(), e.getValue());
            }
        }
        for (long k : c.keySet()) {
            if (!w.containsKey(k)) {
                cells.put(k, "minecraft:air");
            }
        }
        return new Fixed(cells, actions, before, of(w));
    }

    /** The block map with the fix over it (no air): what stands. */
    public static Map<Long, String> applied(Map<Long, String> c, Fixed fixed) {
        Map<Long, String> w = new java.util.HashMap<>(c);
        fixed.cells().forEach((k, v) -> {
            if (base(v).equals("air")) {
                w.remove(k);
            } else {
                w.put(k, v);
            }
        });
        return w;
    }

    private static String where(Floating f) {
        return base(f.lowest()).replace('_', ' ') + " at dx" + f.x() + " dy" + f.y() + " dz" + f.z();
    }

    private static String hang(Map<Long, String> w, Floating f, Set<Long> cleared) {
        int top = Integer.MIN_VALUE;
        for (long k : f.cells()) {
            top = Math.max(top, ComposedPlan.ky(k));
        }
        double cx = 0;
        double cz = 0;
        for (long k : f.cells()) {
            cx += ComposedPlan.kx(k);
            cz += ComposedPlan.kz(k);
        }
        cx /= f.cells().size();
        cz /= f.cells().size();
        long pick = 0;
        double best = Double.MAX_VALUE;
        for (long k : f.cells()) {
            if (ComposedPlan.ky(k) != top) {
                continue;
            }
            double d = Math.hypot(ComposedPlan.kx(k) - cx, ComposedPlan.kz(k) - cz);
            if (d < best || (d == best && k < pick)) {
                best = d;
                pick = k;
            }
        }
        int x = ComposedPlan.kx(pick);
        int z = ComposedPlan.kz(pick);
        for (int up = 1; up <= HANG_MOST; up++) {
            long a = ComposedPlan.key(x, top + up, z);
            if (cleared.contains(a)) {
                return null;   // the chain would run through a cell the plan cleared
            }
            // Hung from the block straight above; or, where the chain's next link would touch a block beside or
            // above it (the inside of a peaked roof), from that.
            boolean above = w.containsKey(a) && !f.cells().contains(a);
            boolean beside = !above && !w.containsKey(a) && touchesFromAbove(w, f, x, top + up, z);
            if (above || beside) {
                int last = above ? top + up - 1 : top + up;
                for (int y = top + 1; y <= last; y++) {
                    w.put(ComposedPlan.key(x, y, z), "minecraft:iron_chain");
                }
                for (long k : f.cells()) {
                    String b = w.get(k);
                    if (base(b).endsWith("lantern")) {
                        w.put(k, "minecraft:" + base(b) + "[hanging=true]");
                    }
                }
                int links = last - top;
                return "hung the " + where(f) + " from the block " + (above ? "" : "beside the chain ") + up
                        + " above it" + (links > 0 ? " on " + links + " chain" : "");
            }
        }
        return null;
    }

    /** Does the cell touch (a corner counting) a block of the build at its own height or above, not in the lump? */
    private static boolean touchesFromAbove(Map<Long, String> w, Floating f, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long n = ComposedPlan.key(x + dx, y + dy, z + dz);
                    if ((dx != 0 || dy != 0 || dz != 0) && w.containsKey(n) && !f.cells().contains(n)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String extend(Map<Long, String> w, Floating f, boolean airborne, Set<Long> cleared) {
        Map<Long, String> fill = new java.util.HashMap<>();
        for (long k : f.cells()) {
            String b = w.get(k);
            int x = ComposedPlan.kx(k);
            int y = ComposedPlan.ky(k);
            int z = ComposedPlan.kz(k);
            // Only a post's WALL goes down: a bottom cell with more of the post straight above it. The floor of a hollow
            // stem (the bottom of a tube, with its room above it) stays where it is, so the room is not filled.
            if (!post(b) || f.cells().contains(ComposedPlan.key(x, y - 1, z))
                    || !post(w.get(ComposedPlan.key(x, y + 1, z))) || !f.cells().contains(ComposedPlan.key(x, y + 1, z))) {
                continue;
            }
            for (int d = 1; d <= EXTEND_MOST + 1; d++) {
                int under = y - d;
                boolean ground = !airborne && under < 0;
                if (ground || (w.containsKey(ComposedPlan.key(x, under, z)) && !f.cells().contains(ComposedPlan.key(x, under, z)))) {
                    for (int yy = under + 1; yy < y; yy++) {
                        long cell = ComposedPlan.key(x, yy, z);
                        if (!cleared.contains(cell)) {   // round a doorway the plan cut, never through it
                            fill.put(cell, b);
                        }
                    }
                    break;
                }
            }
        }
        if (fill.isEmpty()) {
            return null;
        }
        w.putAll(fill);
        return "carried the " + where(f) + " down to what is under it (" + fill.size() + " block"
                + (fill.size() == 1 ? "" : "s") + " of " + base(f.lowest()).replace('_', ' ') + ")";
    }

    private static String drop(Map<Long, String> w, Floating f) {
        int d = f.gap();
        if (d < 1 || d > DROP_MOST || f.blocks() > DROP_LUMP_MOST) {
            return null;
        }
        for (long k : f.cells()) {
            long to = ComposedPlan.key(ComposedPlan.kx(k), ComposedPlan.ky(k) - d, ComposedPlan.kz(k));
            if (w.containsKey(to) && !f.cells().contains(to)) {
                return null;
            }
        }
        Map<Long, String> moved = new java.util.HashMap<>();
        for (long k : f.cells()) {
            moved.put(ComposedPlan.key(ComposedPlan.kx(k), ComposedPlan.ky(k) - d, ComposedPlan.kz(k)), w.get(k));
        }
        f.cells().forEach(w::remove);
        w.putAll(moved);
        return "set the " + where(f) + " down " + d + " block" + (d == 1 ? "" : "s") + " onto what is under it";
    }

    /** What the agent is told the rewrite did, and what still floats. Empty when nothing floated. */
    public static String note(Fixed fx) {
        if (fx == null || fx.before().clean()) {
            return "";
        }
        StringBuilder s = new StringBuilder();
        List<String> done = new ArrayList<>();
        fx.actions().forEach(a -> {
            if (!a.startsWith("left ")) {
                done.add(a);
            }
        });
        if (!done.isEmpty()) {
            s.append(" As it went up, parts that stood on nothing were grounded: ").append(String.join("; ", done)).append('.');
        }
        String still = note(fx.after());
        return s + still;
    }
}
