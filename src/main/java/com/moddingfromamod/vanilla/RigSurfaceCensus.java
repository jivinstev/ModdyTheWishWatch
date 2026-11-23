package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.List;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * <b>an earlier issue's oracle: does any surface a rig DRAWS share a plane with another?</b>
 *
 * <p>Knows nothing about {@link RigSeparation} and nothing about sculpts. It takes displays as a
 * client holds them — a translation, a left rotation and a scale on the shared unit cube — rebuilds
 * each one's box in the world, and asks three questions of every pair:
 *
 * <ol>
 *   <li><b>{@code fights}</b> — same rotation, same facing, faces closer than
 *       {@link #DEPTH_RESOLUTION_BLOCKS}, over real shared area. The flicker that was filmed.</li>
 *   <li><b>{@code buried}</b> — a SMALLER box's face lying on a patterned neighbour's surface but
 *       under that neighbour's lifted overlay, so the pattern's ink draws over it. Nothing is
 *       coplanar, so question 1 cannot see it; it is what a separation too small to clear the lift
 *       would produce.</li>
 *   <li><b>{@code inkShows}</b> — a BIGGER box's face under a smaller patterned box's ink, because
 *       that box sits recessed by less than a lift: its pattern is painted onto its neighbour.</li>
 * </ol>
 *
 * <p>Two callers, deliberately: {@code RigSeparationTest} (Gate A, every committed creature, from
 * {@link RigTransform#placeAtom}) and {@code client/test/FlickerClientTest} (Gate C, from the
 * {@code Display} entities a real client received). The same rule over the planner's output and
 * over the wire, so a fix that is right in the plan and lost on the way cannot read green.
 *
 * <p>Pure (JOML only), so it runs in a unit test.
 */
public final class RigSurfaceCensus {

    private RigSurfaceCensus() {
    }

    /**
     * Faces closer than this are one surface to the depth buffer — about its resolution at 30
     * blocks (near plane 0.05, 24 bits). A number about the CAMERA, not about the fix.
     */
    public static final float DEPTH_RESOLUTION_BLOCKS = 1e-3f;

    /**
     * One display as the client draws it.
     *
     * @param name    for messages only
     * @param overlay a pattern overlay (drawn {@link RigPlan#PATTERN_LIFT_BLOCKS} outside its base)
     * @param bone    which box this display draws; a base and its own overlay share it. Null when
     *                the caller cannot know (a client), in which case an overlay's base is found by
     *                geometry: same place, same turn, one lift smaller
     */
    public record Drawn(String name, boolean overlay, String bone, Vector3f translation,
                        Quaternionf leftRotation, Vector3f scale) {
    }

    public record Result(List<String> fights, List<String> buried, List<String> inkShows) {

        public boolean clean() {
            return fights.isEmpty() && buried.isEmpty() && inkShows.isEmpty();
        }

        @Override
        public String toString() {
            return "fights " + fights.size() + " " + fights + ", buried " + buried.size() + " "
                    + buried + ", ink-shows " + inkShows.size() + " " + inkShows;
        }
    }

    private record Box(Drawn d, String bone, Quaternionf turn, Vector3f c, Vector3f h,
                       double volume) {

        Vector3f skinH() {
            float lift = d.overlay() ? RigPlan.PATTERN_LIFT_BLOCKS : 0f;
            return new Vector3f(h).sub(lift, lift, lift);
        }
    }

    public static Result of(List<Drawn> drawn) {
        List<Box> boxes = new ArrayList<>(drawn.size());
        for (int i = 0; i < drawn.size(); i++) {
            Drawn d = drawn.get(i);
            boxes.add(new Box(d, d.bone() != null ? d.bone() : "#" + i,
                    new Quaternionf(d.leftRotation()), new Vector3f(d.translation()),
                    new Vector3f(d.scale()).mul(0.5f), 0));
        }
        // An overlay whose bone the caller could not name belongs to the base it is concentric
        // with, one lift per face smaller.
        for (int i = 0; i < boxes.size(); i++) {
            Box o = boxes.get(i);
            if (!o.d.overlay() || o.d.bone() != null) {
                continue;
            }
            for (Box b : boxes) {
                if (!b.d.overlay() && b.c.distance(o.c) < 1e-5f
                        && 1 - Math.abs(b.turn.dot(o.turn)) < 1e-6f
                        && b.h.distance(o.skinH()) < 1e-4f) {
                    boxes.set(i, new Box(o.d, b.bone, o.turn, o.c, o.h, 0));
                    break;
                }
            }
        }
        java.util.Map<String, Double> volume = new java.util.HashMap<>();
        for (Box b : boxes) {
            if (!b.d.overlay()) {
                volume.put(b.bone, 8.0 * b.h.x() * b.h.y() * b.h.z());
            }
        }
        List<String> fights = new ArrayList<>();
        List<String> buried = new ArrayList<>();
        List<String> inkShows = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = i + 1; j < boxes.size(); j++) {
                Box a = boxes.get(i);
                Box b = boxes.get(j);
                if (1 - Math.abs(a.turn.dot(b.turn)) > 1e-6f) {
                    continue;   // differently turned: no shared plane is possible
                }
                Quaternionf inv = new Quaternionf(a.turn).conjugate();
                Vector3f ca = new Vector3f(a.c).rotate(inv);
                Vector3f cb = new Vector3f(b.c).rotate(inv);
                for (int ax = 0; ax < 3; ax++) {
                    for (int side = -1; side <= 1; side += 2) {
                        float fa = ca.get(ax) + side * a.h.get(ax);
                        float fb = cb.get(ax) + side * b.h.get(ax);
                        if (Math.abs(fa - fb) < DEPTH_RESOLUTION_BLOCKS
                                && sharedArea(ca, a.h, cb, b.h, ax) > 0) {
                            fights.add(a.d.name() + "~" + b.d.name() + "@" + where(ax, side));
                        }
                        underInk(a, ca, fa, b, cb, fb, side, ax, volume, buried, inkShows);
                        underInk(b, cb, fb, a, ca, fa, side, ax, volume, buried, inkShows);
                    }
                }
            }
        }
        return new Result(fights, buried, inkShows);
    }

    /** Every display {@code DisplayRig.spawn} places for this plan at this scale, at rest. */
    public static List<Drawn> placed(List<RigPlan.Group> plan, double scale) {
        List<Drawn> out = new ArrayList<>(plan.size());
        for (RigPlan.Group g : plan) {
            out.add(drawn(g, RigTransform.placeAtom(g, scale)));
        }
        return out;
    }

    public static Drawn drawn(RigPlan.Group g, RigTransform.Placed p) {
        if (Math.abs(p.rightRotation().w) < 1 - 1e-6f) {
            throw new IllegalArgumentException(g.name() + ": an atom's right rotation is not "
                    + "identity, so its scaled cube is not a box");
        }
        return new Drawn(g.name(), g.patternOverlay(), g.boneName(),
                new Vector3f(p.translation()), new Quaternionf(p.leftRotation()),
                new Vector3f(p.scale()));
    }

    /**
     * Is {@code base}'s face on {@code overlay}'s bone's SURFACE — no further back than its skin,
     * sharing area with that skin — yet behind the ink? Then the pattern draws over it.
     */
    private static void underInk(Box base, Vector3f cBase, float fBase,
                                 Box overlay, Vector3f cOver, float fOver,
                                 int side, int ax, java.util.Map<String, Double> volume,
                                 List<String> buried, List<String> inkShows) {
        if (base.d.overlay() || !overlay.d.overlay() || base.bone.equals(overlay.bone)) {
            return;
        }
        float inkAhead = side * (fOver - fBase);
        float skinAhead = inkAhead - RigPlan.PATTERN_LIFT_BLOCKS;
        if (inkAhead <= DEPTH_RESOLUTION_BLOCKS || skinAhead >= DEPTH_RESOLUTION_BLOCKS
                || sharedArea(cBase, base.h, cOver, overlay.skinH(), ax) <= 0) {
            return;
        }
        String what = base.d.name() + " under " + overlay.d.name() + "@" + where(ax, side);
        Double vb = volume.get(base.bone);
        Double vo = volume.get(overlay.bone);
        if (vo == null || vb <= vo) {
            buried.add(what);
        } else {
            inkShows.add(what);
        }
    }

    private static String where(int ax, int side) {
        return "xyz".charAt(ax) + (side < 0 ? "-" : "+");
    }

    private static double sharedArea(Vector3f ca, Vector3f ha, Vector3f cb, Vector3f hb, int skip) {
        double area = 1;
        for (int i = 0; i < 3; i++) {
            if (i == skip) {
                continue;
            }
            double lo = Math.max(ca.get(i) - ha.get(i), cb.get(i) - hb.get(i));
            double hi = Math.min(ca.get(i) + ha.get(i), cb.get(i) + hb.get(i));
            if (hi - lo <= DEPTH_RESOLUTION_BLOCKS) {
                return 0;
            }
            area *= hi - lo;
        }
        return area;
    }
}
