package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptOrientation;

/**
 * <b>Which of a sculpt's boxes are its legs, wings, tail, fins, tentacles and eyes, and where each
 * one is JOINTED</b> — read off the parts in rig space, so every
 * creature already in the world gets a flap, a swim, a hop and a sit with no keyframe authored.
 *
 * <p>This is how vanilla does it: a parrot flaps on {@code flapAngle}, a bee on
 * {@code cos(ageInTicks)}, a cod's tail on {@code sin(0.6·ageInTicks)}, a wolf sits on
 * {@code isInSittingPose} — each a few lines in its own model's {@code setupAnim}, keyed on the
 * model's own named parts. A wished creature has no model class, so the names and positions of
 * its boxes are the only anatomy there is.
 *
 * <h2>The two rules worth knowing before changing this</h2>
 *
 * <ul>
 *   <li><b>A leg is a limb that REACHES THE GROUND, and front or hind is a position, not a
 *       name.</b> The starter cat calls its hind legs {@code armL}/{@code armR} and the wolf does
 *       the same; a biped's arms are limbs that do not reach the ground and are left to the clip.
 *       A name rule alone would have sat the cat on its front paws.</li>
 *   <li><b>Every joint is a HINGE at the edge where the part meets the body</b>, never the bone's
 *       own pivot. The wing kit pivots each panel at its own middle (it was written for a rest
 *       tilt), so a flap about the pivot would spin each panel in place — three detached plates
 *       rotating in the air. The hinge is computed here and the pose generator turns about it by
 *       an exact position correction, so the rest geometry is untouched.</li>
 * </ul>
 *
 * <p>Pure: no Minecraft. Everything is in sculpt units, rig space (mirrored and mass-centred,
 * {@link RigFit#intoRigSpace}).
 */
public final class RigAnatomy {

    /**
     * A ground-reaching limb. {@code hip} is the hinge it turns about: the top of the box for a leg
     * that stands under the body, and the point where it JOINS the body for a {@code sprawl}ing one
     * (an insect's or a spider's, whose foot is planted far out to the side of its hip) — turning a
     * sprawled leg about the middle of its own box would swing its root out of the body.
     */
    public record Leg(String bone, boolean front, float[] hip, float length, boolean sprawl,
                      float[] foot) {

        public Leg(String bone, boolean front, float[] hip, float length) {
            this(bone, front, hip, length, false, new float[]{hip[0], hip[1] - length, hip[2]});
        }
    }

    /** A wing panel; {@code side} is the sign of its X, and every panel of a side shares a hinge. */
    public record Wing(String bone, int side, float[] hinge, int rank, boolean hanging) {
    }

    /** One tail segment, joint at the end nearest the body; {@code index} counts outwards. */
    public record Tail(String bone, float[] joint, int index) {
    }

    /** A fin ({@code side} 0 for a dorsal fin on the centre line), hinged at its inner edge. */
    public record Fin(String bone, int side, float[] hinge) {
    }

    /** A tentacle, hinged at its top; {@code out} is the horizontal unit vector away from the body. */
    public record Tentacle(String bone, float[] hinge, float[] out) {
    }

    /** An eye or pupil, and the move (model units) that sinks it into the head when it closes. */
    public record Eye(String bone, float[] sink) {
    }

    private final List<CreatureSculpt.Part> parts;
    private final int front;
    private final float groundY;
    private final float topY;
    private final float bodyBottomY;
    private final String body;
    private final String head;
    private final List<Leg> legs;
    private final List<Wing> wings;
    private final List<Tail> tails;
    private final List<Fin> fins;
    private final List<Tentacle> tentacles;
    private final List<Eye> eyes;

    private RigAnatomy(List<CreatureSculpt.Part> parts, int front, float groundY, float topY,
                       float bodyBottomY, String body, String head, List<Leg> legs,
                       List<Wing> wings, List<Tail> tails, List<Fin> fins,
                       List<Tentacle> tentacles, List<Eye> eyes) {
        this.parts = parts;
        this.front = front;
        this.groundY = groundY;
        this.topY = topY;
        this.bodyBottomY = bodyBottomY;
        this.body = body;
        this.head = head;
        this.legs = legs;
        this.wings = wings;
        this.tails = tails;
        this.fins = fins;
        this.tentacles = tentacles;
        this.eyes = eyes;
    }

    public List<CreatureSculpt.Part> parts() {
        return parts;
    }

    /** +1 when the creature's face is at +Z in rig space, -1 at -Z (the usual). */
    public int front() {
        return front;
    }

    public float groundY() {
        return groundY;
    }

    public float topY() {
        return topY;
    }

    /** The underside of the body box: the height a belly sits at when it lies down. */
    public float bodyBottomY() {
        return bodyBottomY;
    }

    public String body() {
        return body;
    }

    public String head() {
        return head;
    }

    public List<Leg> legs() {
        return legs;
    }

    public List<Wing> wings() {
        return wings;
    }

    public List<Tail> tails() {
        return tails;
    }

    public List<Fin> fins() {
        return fins;
    }

    public List<Tentacle> tentacles() {
        return tentacles;
    }

    public List<Eye> eyes() {
        return eyes;
    }

    /** Legs both before and behind the middle: it sits like a dog rather than like a doll. */
    public boolean quadruped() {
        boolean f = false;
        boolean h = false;
        for (Leg l : legs) {
            f |= l.front();
            h |= !l.front();
        }
        return f && h;
    }

    /** The longest leg, or 0 for a creature with none. */
    public float legLength() {
        float out = 0f;
        for (Leg l : legs) {
            out = Math.max(out, l.length());
        }
        return out;
    }

    // ===================== reading a sculpt =====================

    private static final String[] LIMB = {"leg", "arm", "paw", "foot", "feet", "hoof", "thigh",
            "shin", "limb"};

    /**
     * Read a creature's anatomy.
     *
     * @param rigSpaceParts the parts as the rig holds them ({@link RigFit#intoRigSpace})
     */
    public static RigAnatomy of(List<CreatureSculpt.Part> rigSpaceParts) {
        List<CreatureSculpt.Part> parts = rigSpaceParts == null ? List.of() : rigSpaceParts;
        Map<String, CreatureSculpt.Part> byName = new LinkedHashMap<>();
        for (CreatureSculpt.Part p : parts) {
            byName.putIfAbsent(p.name(), p);
        }
        // Where each box really is at rest -- a rest tilt moves corners, and the ground is where
        // the lowest corner is, not where the lowest `from` is.
        Map<String, float[]> box = new HashMap<>();
        Map<String, float[][]> rest = RigLocomotion.corners(parts, Map.of(), null);
        float ground = Float.MAX_VALUE;
        float top = -Float.MAX_VALUE;
        for (CreatureSculpt.Part p : parts) {
            float[] b = bounds(rest.get(p.name()));
            if (b == null) {
                continue;
            }
            box.put(p.name(), b);
            ground = Math.min(ground, b[1]);
            top = Math.max(top, b[4]);
        }
        if (box.isEmpty()) {
            return new RigAnatomy(parts, -1, 0f, 0f, 0f, null, null, List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of());
        }

        String headName = null;
        String bodyName = null;
        float bodyVolume = -1f;
        for (CreatureSculpt.Part p : parts) {
            String n = lower(p);
            if (headName == null && RigPlan.isHeadBone(p.name())) {
                headName = p.name();
            }
            if (bodyName == null && (n.equals("body") || n.equals("torso"))) {
                bodyName = p.name();
            }
        }
        if (bodyName == null) {
            for (CreatureSculpt.Part p : parts) {
                float[] b = box.get(p.name());
                float v = b == null ? 0 : (b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2]);
                if (p.parent() == null && v > bodyVolume) {
                    bodyVolume = v;
                    bodyName = p.name();
                }
            }
        }
        float[] bodyBox = bodyName == null ? null : box.get(bodyName);
        float cx = bodyBox == null ? 0f : (bodyBox[0] + bodyBox[3]) / 2f;
        float cz = bodyBox == null ? 0f : (bodyBox[2] + bodyBox[5]) / 2f;

        // Which way it faces. The head is the best witness; the kit rule's named face and tail
        // are the next; -Z is what SculptOrientation.faceForward leaves every stored sculpt at.
        int front = -1;
        float[] headBox = headName == null ? null : box.get(headName);
        if (headBox != null && Math.abs((headBox[2] + headBox[5]) / 2f - cz) > 0.5f) {
            front = (headBox[2] + headBox[5]) / 2f > cz ? 1 : -1;
        } else {
            int s = SculptOrientation.frontSign(parts);
            if (s != 0) {
                front = s;
            }
        }

        // ---- legs: ground-reaching limbs, the top of each limb chain only
        List<Leg> legs = new ArrayList<>();
        for (CreatureSculpt.Part p : parts) {
            if (!limb(p) || ancestorMatches(p, byName, RigAnatomy::limb)) {
                continue;
            }
            float[] sub = subtreeBounds(p, parts, box);
            if (sub == null || sub[1] > ground + 1.5f) {
                continue;   // an arm that hangs in the air -- the clip keeps it
            }
            float[] b = box.get(p.name());
            // A paw stub under the body (the cat's legL2, two units tall) is the bottom of a leg
            // whose top the body hides: it hinges where that leg would, at the body's underside,
            // or folding it would swing a foot through the floor.
            float under = bodyBox == null ? b[4] : bodyBox[1];
            float[] hip = {(b[0] + b[3]) / 2f, Math.max(b[4], under), (b[2] + b[5]) / 2f};
            // A SPRAWLED leg: its foot is planted well out to the side of where it leaves
            // the body -- an insect's, a spider's. It is jointed where it meets the body: the face
            // of its top box nearest the body's middle line.
            float[] root = innerFace(rest.get(p.name()), cx);
            float[] foot = foot(p, parts, rest);
            // Measured from the top box's own middle, past its own half width: a thick upright leg
            // (the dragon's, four wide and six tall) has its foot right under that middle.
            float mid = (b[0] + b[3]) / 2f;
            boolean sprawl = root != null && foot != null
                    && Math.abs(foot[0] - mid) > (b[3] - b[0]) / 2f + 1f
                    && Math.abs(foot[0] - mid) > 0.4f * Math.max(1f, root[1] - ground)
                    && Math.abs(foot[0] - cx) > Math.abs(root[0] - cx) + 1f;
            if (sprawl) {
                hip = root;
            }
            float length = Math.max(1f, hip[1] - ground);
            legs.add(new Leg(p.name(), false, hip, length, sprawl,
                    foot != null ? foot : new float[]{hip[0], ground, hip[2]}));
        }
        if (!legs.isEmpty()) {
            // Front or hind by WHERE it stands, split at the legs' own middle along the facing
            // axis. Under a third of the body's length apart and it is one pair: a biped.
            float zMin = Float.MAX_VALUE;
            float zMax = -Float.MAX_VALUE;
            for (Leg l : legs) {
                zMin = Math.min(zMin, l.hip()[2]);
                zMax = Math.max(zMax, l.hip()[2]);
            }
            float bodyLen = bodyBox == null ? 8f : bodyBox[5] - bodyBox[2];
            if (zMax - zMin >= Math.max(3f, bodyLen / 3f)) {
                float mid = (zMin + zMax) / 2f;
                List<Leg> sided = new ArrayList<>(legs.size());
                for (Leg l : legs) {
                    boolean f = (l.hip()[2] - mid) * front > 0;
                    sided.add(new Leg(l.bone(), f, l.hip(), l.length(), l.sprawl(), l.foot()));
                }
                legs = sided;
            }
        }

        // ---- wings: by side, all panels of a side about ONE hinge at the body's edge
        List<CreatureSculpt.Part> wingParts = new ArrayList<>();
        for (CreatureSculpt.Part p : parts) {
            if (lower(p).contains("wing") && !ancestorMatches(p, byName,
                    q -> lower(q).contains("wing"))) {
                float[] b = box.get(p.name());
                if (b != null && Math.abs((b[0] + b[3]) / 2f - cx) >= 1f) {
                    wingParts.add(p);
                }
            }
        }
        List<Wing> wings = new ArrayList<>();
        for (int side : new int[]{-1, 1}) {
            List<CreatureSculpt.Part> mine = new ArrayList<>();
            for (CreatureSculpt.Part p : wingParts) {
                float[] b = box.get(p.name());
                if (Math.signum((b[0] + b[3]) / 2f - cx) == side) {
                    mine.add(p);
                }
            }
            if (mine.isEmpty()) {
                continue;
            }
            // Innermost panel first: its inner edge is the shoulder.
            mine.sort(Comparator.comparingDouble(p -> {
                float[] b = box.get(p.name());
                return Math.abs((b[0] + b[3]) / 2f - cx);
            }));
            float[] in = box.get(mine.get(0).name());
            float innerX = side > 0 ? in[0] : in[3];
            if (bodyBox != null) {
                // A wing that overlaps the body is hinged at the body's side, not inside it.
                float edge = side > 0 ? bodyBox[3] : bodyBox[0];
                innerX = side > 0 ? Math.min(innerX, edge) : Math.max(innerX, edge);
            }
            float w = in[3] - in[0];
            float h = in[4] - in[1];
            // A slab hanging at the side (a chicken's) hinges at its TOP; one that reaches
            // outwards (a dragon's, a bat's) at its middle height.
            boolean hanging = h > w;
            float hy = hanging ? in[4] : (in[1] + in[4]) / 2f;
            float hz = (in[2] + in[5]) / 2f;
            for (int i = 0; i < mine.size(); i++) {
                wings.add(new Wing(mine.get(i).name(), side, new float[]{innerX, hy, hz}, i,
                        hanging));
            }
        }

        // ---- tail: the top-level tail boxes, ordered outwards from the body
        List<CreatureSculpt.Part> tailParts = new ArrayList<>();
        for (CreatureSculpt.Part p : parts) {
            String n = lower(p);
            if ((n.contains("tail") || n.contains("fluke"))
                    && !ancestorMatches(p, byName, q -> lower(q).contains("tail"))) {
                tailParts.add(p);
            }
        }
        final int f = front;
        tailParts.sort(Comparator.comparingDouble(p -> {
            float[] b = box.get(p.name());
            return b == null ? 0 : -f * ((b[2] + b[5]) / 2f);
        }));
        List<Tail> tails = new ArrayList<>();
        for (int i = 0; i < tailParts.size(); i++) {
            float[] b = box.get(tailParts.get(i).name());
            if (b == null) {
                continue;
            }
            // The end nearest the body: the front face when the tail runs backwards.
            float jz = front < 0 ? b[2] : b[5];
            tails.add(new Tail(tailParts.get(i).name(),
                    new float[]{(b[0] + b[3]) / 2f, (b[1] + b[4]) / 2f, jz}, i));
        }

        // ---- fins
        List<Fin> fins = new ArrayList<>();
        for (CreatureSculpt.Part p : parts) {
            String n = lower(p).replace("finger", "");
            if (!(n.contains("fin") || n.contains("flipper")) || n.contains("tail")
                    || ancestorMatches(p, byName, q -> lower(q).replace("finger", "").contains("fin"))) {
                continue;
            }
            float[] b = box.get(p.name());
            float mx = (b[0] + b[3]) / 2f - cx;
            int side = Math.abs(mx) < 1f ? 0 : (int) Math.signum(mx);
            float[] hinge = side == 0
                    ? new float[]{(b[0] + b[3]) / 2f, b[1], (b[2] + b[5]) / 2f}
                    : new float[]{side > 0 ? b[0] : b[3], (b[1] + b[4]) / 2f, (b[2] + b[5]) / 2f};
            fins.add(new Fin(p.name(), side, hinge));
        }

        // ---- tentacles
        List<Tentacle> tentacles = new ArrayList<>();
        for (CreatureSculpt.Part p : parts) {
            String n = lower(p);
            if (!(n.contains("tentacle") || n.contains("tendril"))
                    || ancestorMatches(p, byName, q -> lower(q).contains("tentacle"))) {
                continue;
            }
            float[] b = box.get(p.name());
            float ox = (b[0] + b[3]) / 2f - cx;
            float oz = (b[2] + b[5]) / 2f - cz;
            float len = (float) Math.hypot(ox, oz);
            float[] out = len < 0.01f ? new float[]{0, 0, -front} : new float[]{ox / len, 0, oz / len};
            tentacles.add(new Tentacle(p.name(),
                    new float[]{(b[0] + b[3]) / 2f, b[4], (b[2] + b[5]) / 2f}, out));
        }

        // ---- eyes: sink each one into the head along the axis it sticks out of
        List<Eye> eyes = new ArrayList<>();
        if (headBox != null) {
            float[] hc = centre(headBox);
            float[] half = {(headBox[3] - headBox[0]) / 2f, (headBox[4] - headBox[1]) / 2f,
                    (headBox[5] - headBox[2]) / 2f};
            for (CreatureSculpt.Part p : parts) {
                String n = lower(p);
                if (!(n.contains("eye") || n.contains("pupil")) || n.contains("brow")
                        || n.contains("lash")) {
                    continue;
                }
                float[] b = box.get(p.name());
                float[] c = centre(b);
                int axis = 0;
                float best = -1f;
                for (int a = 0; a < 3; a += 2) {   // out of the side or out of the face, never up
                    float r = Math.abs(c[a] - hc[a]) / Math.max(0.5f, half[a]);
                    if (r > best) {
                        best = r;
                        axis = a;
                    }
                }
                float sign = Math.signum(c[axis] - hc[axis]);
                float[] sink = new float[3];
                // Far enough that its outer face is behind the head's surface.
                float outer = sign > 0 ? b[axis + 3] : b[axis];
                float surface = hc[axis] + sign * half[axis];
                float depth = Math.abs(outer - surface) + (b[axis + 3] - b[axis]) + 0.1f;
                sink[axis] = -sign * depth;
                eyes.add(new Eye(p.name(), sink));
            }
        }

        float bodyBottom = bodyBox == null ? ground : bodyBox[1];
        return new RigAnatomy(parts, front, ground, top, bodyBottom, bodyName, headName,
                List.copyOf(legs), List.copyOf(wings), List.copyOf(tails), List.copyOf(fins),
                List.copyOf(tentacles), List.copyOf(eyes));
    }

    /** Is this a limb by name (and not a finger, a claw or an "armour" plate)? */
    static boolean limb(CreatureSculpt.Part p) {
        String n = lower(p);
        if (n.contains("armor") || n.contains("armour") || n.contains("finger")
                || n.contains("claw")) {
            return false;
        }
        if (n.startsWith(com.moddingfromamod.runtime.SculptKits.MARK)) {
            // A kit's boxes are decoration -- except the leg row, which IS the legs.
            return n.startsWith(com.moddingfromamod.runtime.SculptKits.MARK + "leg_row");
        }
        for (String w : LIMB) {
            if (n.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static boolean ancestorMatches(CreatureSculpt.Part p,
                                           Map<String, CreatureSculpt.Part> byName,
                                           java.util.function.Predicate<CreatureSculpt.Part> test) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        CreatureSculpt.Part at = p.parent() == null ? null : byName.get(p.parent());
        while (at != null && seen.add(at.name())) {
            if (test.test(at)) {
                return true;
            }
            at = at.parent() == null ? null : byName.get(at.parent());
        }
        return false;
    }

    private static float[] subtreeBounds(CreatureSculpt.Part root, List<CreatureSculpt.Part> parts,
                                         Map<String, float[]> box) {
        Map<String, CreatureSculpt.Part> byName = new HashMap<>();
        for (CreatureSculpt.Part p : parts) {
            byName.putIfAbsent(p.name(), p);
        }
        float[] out = null;
        for (CreatureSculpt.Part p : parts) {
            boolean under = p.name().equals(root.name());
            if (!under) {
                java.util.Set<String> seen = new java.util.HashSet<>();
                CreatureSculpt.Part at = p.parent() == null ? null : byName.get(p.parent());
                while (at != null && seen.add(at.name())) {
                    if (at.name().equals(root.name())) {
                        under = true;
                        break;
                    }
                    at = at.parent() == null ? null : byName.get(at.parent());
                }
            }
            float[] b = box.get(p.name());
            if (!under || b == null) {
                continue;
            }
            out = out == null ? b.clone() : new float[]{Math.min(out[0], b[0]),
                    Math.min(out[1], b[1]), Math.min(out[2], b[2]), Math.max(out[3], b[3]),
                    Math.max(out[4], b[4]), Math.max(out[5], b[5])};
        }
        return out;
    }

    /** The middle of the corners nearest the line {@code x = cx}: where a leg meets the body. */
    private static float[] innerFace(float[][] corners, float cx) {
        if (corners == null || corners.length == 0) {
            return null;
        }
        float near = Float.MAX_VALUE;
        for (float[] c : corners) {
            near = Math.min(near, Math.abs(c[0] - cx));
        }
        float[] s = new float[3];
        int n = 0;
        for (float[] c : corners) {
            if (Math.abs(c[0] - cx) <= near + 0.5f) {
                for (int a = 0; a < 3; a++) {
                    s[a] += c[a];
                }
                n++;
            }
        }
        return new float[]{s[0] / n, s[1] / n, s[2] / n};
    }

    /** The middle of the lowest corners of a limb chain: where its foot is planted. */
    private static float[] foot(CreatureSculpt.Part root, List<CreatureSculpt.Part> parts,
                                Map<String, float[][]> rest) {
        java.util.Set<String> chain = RigLocomotion.subtree(parts, root.name());
        float low = Float.MAX_VALUE;
        for (String bone : chain) {
            float[][] cs = rest.get(bone);
            if (cs != null) {
                for (float[] c : cs) {
                    low = Math.min(low, c[1]);
                }
            }
        }
        float[] s = new float[3];
        int n = 0;
        for (String bone : chain) {
            float[][] cs = rest.get(bone);
            if (cs == null) {
                continue;
            }
            for (float[] c : cs) {
                if (c[1] <= low + 0.5f) {
                    for (int a = 0; a < 3; a++) {
                        s[a] += c[a];
                    }
                    n++;
                }
            }
        }
        return n == 0 ? null : new float[]{s[0] / n, s[1] / n, s[2] / n};
    }

    /** {@code [minX,minY,minZ,maxX,maxY,maxZ]} of eight corners, or null. */
    static float[] bounds(float[][] corners) {
        if (corners == null || corners.length == 0) {
            return null;
        }
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (float[] c : corners) {
            for (int a = 0; a < 3; a++) {
                b[a] = Math.min(b[a], c[a]);
                b[a + 3] = Math.max(b[a + 3], c[a]);
            }
        }
        return b;
    }

    private static float[] centre(float[] b) {
        return new float[]{(b[0] + b[3]) / 2f, (b[1] + b[4]) / 2f, (b[2] + b[5]) / 2f};
    }

    private static String lower(CreatureSculpt.Part p) {
        return p.name() == null ? "" : p.name().toLowerCase(Locale.ROOT);
    }

    /** The bones a locomotion layer can move, for the plan's "animated bones" print. */
    public java.util.Set<String> movable() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        legs.forEach(l -> out.add(l.bone()));
        wings.forEach(w -> out.add(w.bone()));
        tails.forEach(t -> out.add(t.bone()));
        fins.forEach(fn -> out.add(fn.bone()));
        tentacles.forEach(t -> out.add(t.bone()));
        eyes.forEach(e -> out.add(e.bone()));
        if (head != null) {
            out.add(head);
        }
        if (body != null) {
            out.add(body);
        }
        return out;
    }
}
