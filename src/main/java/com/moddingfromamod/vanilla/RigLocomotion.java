package com.moddingfromamod.vanilla;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.runtime.BedrockAnimation;
import com.moddingfromamod.runtime.CreatureSculpt;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * <b>Fly, swim, hop, sit, lie down and sleep — generated from the sculpt's own parts</b> (
 *).
 *
 * <p>A wished creature ships three clips (idle, walk, attack) written for conventional bone names.
 * Nothing in it flaps, swims, hops or sits, and asking the agent to author keyframes for each would
 * help only the creatures wished after the change. So this generates them, the way vanilla's own
 * models do — a parrot's {@code flapAngle}, a bee's {@code cos(ageInTicks)}, a cod's tail on
 * {@code sin(0.6·ageInTicks)}, a rabbit's {@code jumpCompletion}, a wolf's
 * {@code isInSittingPose} — from {@link RigAnatomy}'s reading of the boxes.
 *
 * <h2>What it hands back, and why it is two things</h2>
 *
 * <p>A per-bone pose map, like any clip, and a <b>root</b>: one rigid transform for the whole body
 * ({@link RigPose#compose(List, Map, float[], boolean, RigPose.Chain)}). The swim pitch, a sit, a
 * lie-down and a landing crouch move the creature as one piece, and doing that in the root rather
 * than by hand on each root bone is what keeps the head's look exact when the head is a root bone.
 *
 * <h2>Every layer is weighted, and every weight is eased</h2>
 *
 * <p>The caller ({@link RigGait}) moves each weight towards its target a little per tick; this
 * class smoothsteps it. A clip that is switched on at weight 1 snaps, and a snap is what these
 * must never do. Angles are scaled by the weight BEFORE the hinge correction, so a wing
 * half way into a flap is turned half as far about its shoulder, not half way along a chord.
 *
 * <h2>Hinges</h2>
 *
 * <p>A bone pose turns a bone about its own pivot. A wing must turn about its shoulder, a tail
 * segment about the end nearest the body, a leg about its hip. So each generated move is a rigid
 * transform {@code F} about a hinge, and the bone's pose is solved from it exactly: rotation
 * {@code q}, and the position channel {@code q·pivot − pivot + t} ({@link #onTop}). The rest
 * geometry is never edited — the agent's sculpt is what is saved.
 *
 * <p>Scale stays refused (scoreboard A6): a slime's squash is approximated by lowering the body and
 * bending the legs, never by a scale channel.
 *
 * <p>Pure: no Minecraft.
 */
public final class RigLocomotion {

    private RigLocomotion() {
    }

    // ===================== the constants that decide how it looks =====================

    /**
     * Ticks per wing beat. A multiple of the pose interval (3), so every beat is sampled at the same
     * five phases rather than drifting through them, and long enough that five samples a beat —
     * tweened linearly by the client between them — still read as a beat rather than a jitter. A
     * bee's 2.1 rad/tick would alias at our write rate to nothing at all.
     */
    public static final int FLAP_TICKS = 15;
    /** Degrees each wing swings either side of {@link #FLAP_LIFT}. */
    public static final float FLAP_DEGREES = 38f;
    /** The middle of the beat, above level: a bird's wings spend more of a beat up than down. */
    public static final float FLAP_LIFT = 8f;
    /** A grounded wing's twitch — the chicken rule, nowhere near a beat. */
    public static final float REST_WING_DEGREES = 2.5f;
    public static final int REST_WING_TICKS = 60;

    /** Ticks per tail stroke in water. A cod is ~10.5; this is the slowest that still reads as a swim. */
    public static final int SWIM_TICKS = 18;
    /** Degrees of yaw the whole tail sweeps, shared down its segments. */
    public static final float TAIL_SWEEP_DEGREES = 24f;

    /** The longest a travel pitch may be, either way. A dolphin's is its head's, clamped. */
    public static final float MAX_SWIM_PITCH = 40f;

    /** Ticks per breath while asleep. */
    public static final int BREATH_TICKS = 80;

    /**
     * Degrees a leg swings either side of hanging at a full walk -- the starter walk clip's own
     * {@code legL}/{@code legR} keys, so a biped's legs and its clip-driven arms keep one rhythm.
     */
    public static final float STRIDE_DEGREES = 25f;
    /** Degrees a SPRAWLED leg sweeps about its root, either side, at a full walk. */
    public static final float SPRAWL_SWEEP_DEGREES = 18f;
    /** The least a sprawled leg sweeps, however close its neighbours stand: below this it shuffles. */
    public static final float MIN_SWEEP_DEGREES = 8f;
    /** Degrees a sprawled leg lifts while it swings forward: the step, off the ground. */
    public static final float SPRAWL_LIFT_DEGREES = 14f;
    /** How far (a fraction of its length) an upright leg is drawn up while it swings forward. */
    public static final float STEP_LIFT = 0.07f;
    /** The limb speed at which the stride is at full swing; slower is a smaller step. */
    public static final float FULL_STRIDE_SPEED = 0.25f;

    // ===================== inputs and outputs =====================

    /**
     * Where a creature is in each layer.
     *
     * @param fly          0..1, airborne and flying
     * @param swim         0..1, swimming
     * @param sit          0..1
     * @param lie          0..1
     * @param sleep        0..1
     * @param crouch       0..1, gathering for a hop or absorbing a landing
     * @param jump         0..1, how much the jump pose is on
     * @param jumpProgress 0 at take-off, 0.5 at the top, 1 on landing
     * @param travelPitch  degrees, nose up, the way it is moving through water
     * @param moving       0..1, how much it is travelling
     */
    public record Weights(float fly, float swim, float sit, float lie, float sleep, float crouch,
                          float jump, float jumpProgress, float travelPitch, float moving) {

        public static final Weights NONE = new Weights(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        public boolean any() {
            return fly > 0 || swim > 0 || sit > 0 || lie > 0 || sleep > 0 || crouch > 0 || jump > 0;
        }
    }

    /**
     * <b>Where a walking creature is in its stride</b>: {@code phase} 0..1 through one full
     * cycle (the walk clip's own clock, {@code AnimationState}'s limb distance), and {@code amount}
     * 0..1, how big the step is. {@link #NONE} when it is not walking: the legs are then left to
     * every other layer and to the clip.
     */
    public record Stride(float phase, float amount) {

        public static final Stride NONE = new Stride(0f, 0f);

        /** The stride for the limb speed and distance the renderer reads (the walk clip's input). */
        public static Stride of(float walkSpeed, float walkPosition) {
            if (walkSpeed <= com.moddingfromamod.runtime.AnimationState.WALK_THRESHOLD) {
                return NONE;
            }
            float t = walkPosition * com.moddingfromamod.runtime.AnimationState.WALK_CYCLE_SCALE;
            return new Stride(t - (float) Math.floor(t),
                    Math.min(1f, walkSpeed / FULL_STRIDE_SPEED));
        }
    }

    /**
     * @param pose     per bone, the base clip with the generated layers on top
     * @param root     the whole body's move, or null when it is standing where it stands
     * @param lookKeep how much of the head's look to keep (0 while it sleeps)
     */
    public record Out(Map<String, BedrockAnimation.BonePose> pose, RigPose.Chain root,
                      float lookKeep) {
    }

    /**
     * What the full-weight poses need that depends on the creature's whole shape, measured once:
     * how far to tip it to sit with its front feet on the ground, how far to lower it to lie, how
     * far to lift it to sleep on its side. Everything here is solved on the geometry, so a long
     * dachshund and a short pug both land on their feet.
     */
    public record Plan(RigAnatomy anatomy, float sitPitch, float sitDrop, float[] sitPivot,
                       float lieDrop, float sleepRoll, float[] sleepPivot, float sleepLift,
                       float[] bipedLieShift, float crouchDrop) {
    }

    // ===================== the plan =====================

    /** Hind legs while sitting, degrees forward of hanging — thighs along the ground. */
    static final float SIT_HIND = 80f;
    /** Legs while lying like a sphinx. */
    static final float LIE_FRONT = 90f;
    /** Hind legs stretched out BEHIND (a sploot), so they are not under the belly it lies on. */
    static final float LIE_HIND = -85f;
    /** On its side to sleep. */
    static final float SLEEP_ROLL = 80f;
    /** Legs bent against a hop. */
    static final float CROUCH_LEG = 30f;

    public static Plan plan(RigAnatomy a) {
        float ground = a.groundY();
        float legLen = a.legLength();
        float height = Math.max(1f, a.topY() - ground);
        float crouchDrop = legLen > 0 ? legLen * (1f - (float) Math.cos(Math.toRadians(CROUCH_LEG)))
                : height * 0.08f;

        // ---- sit
        float sitPitch = 0f;
        float sitDrop;
        float[] sitPivot = {0, ground, 0};
        if (a.quadruped()) {
            sitPivot = average(a, false);
            // Bisect the tip so the front feet come down on the ground once the rump is on it.
            float lo = 5f;
            float hi = 55f;
            float drop = 0f;
            for (int i = 0; i < 24; i++) {
                float mid = (lo + hi) / 2f;
                float[] r = sitFor(a, mid);
                drop = r[0];
                if (r[1] < 0f) {
                    lo = mid;     // front feet below the ground: tip further
                } else {
                    hi = mid;
                }
            }
            sitPitch = (lo + hi) / 2f;
            float[] r = sitFor(a, sitPitch);
            // Never push the front feet through the floor to put the rump down.
            sitDrop = Math.max(0f, Math.min(r[0], r[0] + r[1]));
        } else if (!a.legs().isEmpty()) {
            // A biped sits like a doll: down onto its seat, legs out in front.
            Out o = full(a, Weights.NONE, 0f, null, legsForward(a, 85f, 85f));
            sitDrop = Math.max(0f, lowestExcept(a, o, false, true) - ground);
        } else {
            sitDrop = height * 0.12f;
        }

        // ---- lie (a sphinx: belly down, front paws out)
        float lieDrop;
        if (!a.legs().isEmpty() && !a.quadruped()) {
            lieDrop = 0f;   // a biped lies on its back -- see bipedLieShift
        } else {
            Out o = full(a, Weights.NONE, 0f, null, legsForward(a, LIE_FRONT, LIE_HIND));
            // Folded legs lie UNDER the belly, so they are what touches the ground.
            lieDrop = Math.max(0f, lowestExcept(a, o, false, true) - ground);
        }

        // ---- sleep (a quadruped on its side; a biped as it lies)
        float[] sleepPivot = {0, (a.bodyBottomY() + a.topY()) / 2f, 0};
        float sleepRoll = 0f;
        float sleepLift = 0f;
        if (a.quadruped() || a.legs().isEmpty()) {
            sleepRoll = a.quadruped() ? SLEEP_ROLL : 0f;
            RigPose.Chain root = RigPose.about(rootRotation(a, 0f, sleepRoll), sleepPivot,
                    new float[]{0, 0, 0});
            Out o = full(a, Weights.NONE, 0f, root, legsForward(a, 25f, -15f));
            // A legless creature (a snake, a blob) is already lying down: it only shuts its eyes.
            sleepLift = sleepRoll == 0f ? 0f : ground - lowestExcept(a, o, false, true);
        }

        // ---- a biped lying on its back: turned face-up about its feet, centred, on the ground
        float[] bipedShift = {0, 0, 0};
        if (!a.legs().isEmpty() && !a.quadruped()) {
            RigPose.Chain root = RigPose.about(rootRotation(a, 90f, 0f),
                    new float[]{0, ground, 0}, new float[]{0, 0, 0});
            Out o = full(a, Weights.NONE, 0f, root, Map.of());
            float[] b = allBounds(a, o);
            bipedShift = new float[]{0, ground - b[1], -(b[2] + b[5]) / 2f};
        }

        return new Plan(a, sitPitch, sitDrop, sitPivot, lieDrop, sleepRoll, sleepPivot, sleepLift,
                bipedShift, crouchDrop);
    }

    /** {@code {rumpDrop, frontFeetHeightAfterDrop}} for a sit tipped by {@code pitch}. */
    private static float[] sitFor(RigAnatomy a, float pitch) {
        float[] pivot = average(a, false);
        RigPose.Chain root = RigPose.about(rootRotation(a, pitch, 0f), pivot, new float[]{0, 0, 0});
        Map<String, float[]> legs = new HashMap<>();
        for (RigAnatomy.Leg l : a.legs()) {
            legs.put(l.bone(), new float[]{l.front() ? -pitch : SIT_HIND - pitch, 0, 0});
        }
        Out o = full(a, Weights.NONE, 0f, root, legs);
        java.util.Set<String> skip = new java.util.HashSet<>();
        for (RigAnatomy.Leg l : a.legs()) {
            if (l.front()) {
                skip.addAll(subtree(a.parts(), l.bone()));
            }
        }
        for (RigAnatomy.Tail t : a.tails()) {
            skip.addAll(subtree(a.parts(), t.bone()));
        }
        // The rump, and the folded hind legs beside it: whichever is lower sits on the ground.
        float rump = lowest(a, o, skip) - a.groundY();
        float frontFeet = Float.MAX_VALUE;
        Map<String, float[][]> c = corners(a.parts(), o.pose(), o.root());
        for (RigAnatomy.Leg l : a.legs()) {
            if (!l.front()) {
                continue;
            }
            for (String bone : subtree(a.parts(), l.bone())) {
                float[] b = RigAnatomy.bounds(c.get(bone));
                if (b != null) {
                    frontFeet = Math.min(frontFeet, b[1]);
                }
            }
        }
        return new float[]{rump, frontFeet - rump - a.groundY()};
    }

    private static Map<String, float[]> legsForward(RigAnatomy a, float front, float hind) {
        Map<String, float[]> out = new HashMap<>();
        for (RigAnatomy.Leg l : a.legs()) {
            out.put(l.bone(), new float[]{l.front() || !a.quadruped() ? front : hind, 0, 0});
        }
        return out;
    }

    /** A pose with only the given legs turned forward (degrees) about their hips. */
    private static Out full(RigAnatomy a, Weights w, float ticks, RigPose.Chain root,
                            Map<String, float[]> legDegrees) {
        Map<String, Move> moves = new LinkedHashMap<>();
        for (RigAnatomy.Leg l : a.legs()) {
            float[] d = legDegrees.get(l.bone());
            if (d != null) {
                Move m = move(moves, l.bone(), l.hip());
                m.rot[0] += pitch(a, d[0]);
            }
        }
        return new Out(assemble(a, Map.of(), moves, List.of()), root, 1f);
    }

    // ===================== the pose, every write =====================

    /**
     * The creature's pose this tick: {@code base} (the clip it would otherwise play) with every
     * layer in {@code w} on top.
     *
     * @param ticks wall time in ticks — the clock the flap, the tail and the breath run on
     */
    public static Out pose(Plan plan, Map<String, BedrockAnimation.BonePose> base, Weights w,
                           float ticks) {
        return pose(plan, base, w, ticks, Stride.NONE);
    }

    /**
     * …and the WALK: every leg {@link RigAnatomy} found steps, whatever it is called, in a
     * gait chosen by how many legs it has ({@link #strideOffsets}). While it walks the legs are the
     * layer's alone -- the walk clip's own {@code legL/legR/armL/armR} keys are dropped on every
     * bone that is a leg, so no leg is swung twice -- and the clip keeps only what is not a leg (a
     * biped's arms).
     */
    public static Out pose(Plan plan, Map<String, BedrockAnimation.BonePose> base, Weights w,
                           float ticks, Stride stride) {
        RigAnatomy a = plan.anatomy();
        Map<String, BedrockAnimation.BonePose> baseOrEmpty = base == null ? Map.of() : base;
        if (w == null) {
            w = Weights.NONE;
        }
        float fly = ease(w.fly());
        float swim = ease(w.swim());
        float sit = ease(w.sit());
        float lie = ease(w.lie());
        float sleep = ease(w.sleep());
        float crouch = ease(w.crouch());
        float jump = ease(w.jump());
        boolean quad = a.quadruped();

        // A RESTING creature does not play its standing idle (a held sit was seen to twitch).
        // The idle bobs the `body` bone and sways the head; on a sculpt whose legs and tail are
        // ROOT bones beside the body, a body-only bob slides it against them at every joint, and
        // on a body tipped into a sit that reads as a twitch. So the clip fades out with the rest
        // pose on EVERY bone, and what is left is the pose itself, the look, and the sleeper's
        // slow whole-body breath.
        float rest = Math.max(sit, Math.max(lie, sleep));
        if (rest > 0f && !baseOrEmpty.isEmpty()) {
            Map<String, BedrockAnimation.BonePose> calmed = new LinkedHashMap<>();
            for (Map.Entry<String, BedrockAnimation.BonePose> e : baseOrEmpty.entrySet()) {
                BedrockAnimation.BonePose b = scaled(e.getValue(), 1f - rest);
                if (b != null) {
                    calmed.put(e.getKey(), b);
                }
            }
            baseOrEmpty = calmed;
        }

        Map<String, Move> moves = new LinkedHashMap<>();
        int nTail = a.tails().size();
        float[] tailYaw = new float[nTail];
        float[] tailRaise = new float[nTail];
        Root root = new Root();

        // ---- wings: the beat in the air, a twitch on the ground
        double beat = 2 * Math.PI * ticks / FLAP_TICKS;
        double still = 2 * Math.PI * ticks / REST_WING_TICKS;
        for (RigAnatomy.Wing wing : a.wings()) {
            float lag = 0.35f * wing.rank();
            float u = (float) ((FLAP_LIFT + FLAP_DEGREES * (1f + 0.12f * wing.rank())
                    * Math.sin(beat - lag)) * fly
                    + REST_WING_DEGREES * Math.sin(still - lag) * (1f - fly));
            Move m = move(moves, wing.bone(), wing.hinge());
            m.rot[2] += wing.side() * u;
            m.keep = Math.min(m.keep, 1f - fly);
        }
        if (fly > 0) {
            for (RigAnatomy.Leg l : a.legs()) {
                Move m = move(moves, l.bone(), l.hip());
                m.rot[0] += pitch(a, -35f * fly);
                m.keep = Math.min(m.keep, 1f - fly);
            }
            for (int i = 0; i < nTail; i++) {
                tailRaise[i] += (float) (5f * Math.sin(beat - 0.6 * i)) * fly;
            }
            // The body rises on the downstroke, a bee's bob, and tips forward to travel.
            root.shift[1] += (float) (0.6 * Math.sin(beat + Math.PI)) * fly;
            root.add(-8f * fly * w.moving(), 0f, center(a), fly);
        }

        // ---- swim: pitch toward travel, tail sweep, fins, tentacles, paddling legs
        double stroke = 2 * Math.PI * ticks / SWIM_TICKS;
        if (swim > 0) {
            float per = TAIL_SWEEP_DEGREES / Math.max(1, nTail);
            for (int i = 0; i < nTail; i++) {
                tailYaw[i] += (float) (per * (1f + 0.4f * i) * Math.sin(stroke - 0.8 * i)) * swim;
            }
            for (RigAnatomy.Fin fin : a.fins()) {
                Move m = move(moves, fin.bone(), fin.hinge());
                if (fin.side() == 0) {
                    m.rot[1] += (float) (6 * Math.sin(stroke)) * swim;
                } else {
                    m.rot[2] += (float) (fin.side() * 14 * Math.sin(stroke + fin.side())) * swim;
                }
            }
            for (RigAnatomy.Tentacle t : a.tentacles()) {
                float open = (float) (12 + 12 * Math.sin(stroke)) * swim;
                Move m = move(moves, t.bone(), t.hinge());
                m.rot[0] += -open * t.out()[2];
                m.rot[2] += open * t.out()[0];
            }
            int k = 0;
            for (RigAnatomy.Leg l : a.legs()) {
                double ph = stroke + (l.front() ? 0 : Math.PI) + (l.hip()[0] < 0 ? 0 : Math.PI / 2);
                Move m = move(moves, l.bone(), l.hip());
                m.rot[0] += pitch(a, (float) (-25 + 20 * Math.sin(ph)) * swim);
                m.keep = Math.min(m.keep, 1f - swim);
                k++;
            }
            float p = Math.max(-MAX_SWIM_PITCH, Math.min(MAX_SWIM_PITCH, w.travelPitch()));
            root.add(p * swim, 0f, center(a), swim);
        }

        // ---- jump: rabbit's jumpCompletion through a sine, pitched along the arc
        if (jump > 0) {
            float pr = Math.max(0f, Math.min(1f, w.jumpProgress()));
            float s = (float) Math.sin(Math.PI * pr);
            for (RigAnatomy.Leg l : a.legs()) {
                Move m = move(moves, l.bone(), l.hip());
                float deg = quad ? (l.front() ? 45f * s : -50f * s) : -25f * s;
                m.rot[0] += pitch(a, deg * jump);
                m.keep = Math.min(m.keep, 1f - jump);
            }
            for (int i = 0; i < nTail; i++) {
                tailRaise[i] += 12f * s * jump;
            }
            root.add(16f * (1f - 2f * pr) * jump, 0f, center(a), jump);
        }

        // ---- crouch: gathering for the hop, or the landing taken in the legs
        if (crouch > 0) {
            for (RigAnatomy.Leg l : a.legs()) {
                Move m = move(moves, l.bone(), l.hip());
                float deg = quad && l.front() ? -CROUCH_LEG : CROUCH_LEG;
                m.rot[0] += pitch(a, deg * crouch);
                m.keep = Math.min(m.keep, 1f - crouch);
            }
            root.shift[1] -= plan.crouchDrop() * crouch;
            root.add(quad ? -4f * crouch : 0f, 0f, center(a), crouch);
        }

        // ---- sit
        if (sit > 0) {
            if (quad) {
                root.add(plan.sitPitch() * sit, 0f, plan.sitPivot(), sit);
                for (RigAnatomy.Leg l : a.legs()) {
                    Move m = move(moves, l.bone(), l.hip());
                    float deg = l.front() ? -plan.sitPitch() : SIT_HIND - plan.sitPitch();
                    m.rot[0] += pitch(a, deg * sit);
                    m.keep = Math.min(m.keep, 1f - sit);
                }
                for (int i = 0; i < nTail; i++) {
                    tailRaise[i] += (i == 0 ? plan.sitPitch() * 0.8f : 0f) * sit;
                }
            } else {
                for (RigAnatomy.Leg l : a.legs()) {
                    Move m = move(moves, l.bone(), l.hip());
                    m.rot[0] += pitch(a, 85f * sit);
                    m.keep = Math.min(m.keep, 1f - sit);
                }
            }
            root.shift[1] -= plan.sitDrop() * sit;
        }

        // ---- lie
        if (lie > 0) {
            if (!a.legs().isEmpty() && !quad) {
                lieOnBack(a, plan, root, lie);
            } else {
                for (RigAnatomy.Leg l : a.legs()) {
                    Move m = move(moves, l.bone(), l.hip());
                    m.rot[0] += pitch(a, (l.front() ? LIE_FRONT : LIE_HIND) * lie);
                    if (!l.front()) {
                        m.rot[2] += Math.signum(l.hip()[0]) * 12f * lie;   // splayed a little
                    }
                    m.keep = Math.min(m.keep, 1f - lie);
                }
                root.shift[1] -= plan.lieDrop() * lie;
                for (int i = 0; i < nTail; i++) {
                    tailYaw[i] += (i == 0 ? 30f : 10f) * lie;
                }
            }
            headDown(a, moves, 8f * lie);
        }

        // ---- sleep: on its side (or its back), eyes shut, breathing slowly
        if (sleep > 0) {
            if (!a.legs().isEmpty() && !quad) {
                lieOnBack(a, plan, root, sleep);
            } else {
                for (RigAnatomy.Leg l : a.legs()) {
                    Move m = move(moves, l.bone(), l.hip());
                    m.rot[0] += pitch(a, (l.front() ? 25f : -15f) * sleep);
                    m.keep = Math.min(m.keep, 1f - sleep);
                }
                root.add(0f, plan.sleepRoll() * sleep, plan.sleepPivot(), sleep);
                root.shift[1] += plan.sleepLift() * sleep;
                for (int i = 0; i < nTail; i++) {
                    tailYaw[i] += 28f * sleep;    // curled round towards the front
                }
            }
            headDown(a, moves, 14f * sleep);
            for (RigAnatomy.Eye e : a.eyes()) {
                Move m = move(moves, e.bone(), null);
                for (int ax = 0; ax < 3; ax++) {
                    m.shift[ax] += e.sink()[ax] * sleep;
                }
            }
            // The breath moves the WHOLE creature, never the `body` bone: legs, arms and the head
            // are usually ROOT bones beside the body, so a body-only bob opens a gap at every
            // breath (measured that on 46 of 57 archived creatures).
            root.shift[1] += (float) (0.3 * Math.sin(2 * Math.PI * ticks / BREATH_TICKS)) * sleep;
        }

        // ---- walk: every leg steps, phased by where it stands
        if (stride != null && stride.amount() > 0f && !a.legs().isEmpty()) {
            float busy = Math.max(Math.max(fly, swim), Math.max(Math.max(sit, lie),
                    Math.max(sleep, Math.max(jump, crouch))));
            float k = stride.amount() * (1f - busy);
            Map<String, float[]> gait = gait(a);
            for (RigAnatomy.Leg l : a.legs()) {
                Move m = move(moves, l.bone(), l.hip());
                // The legs belong to the walk now: the clip's keys on them would swing them twice.
                m.keep = 0f;
                if (k <= 0f) {
                    continue;
                }
                float[] g = gait.getOrDefault(l.bone(), new float[]{0f, SPRAWL_SWEEP_DEGREES});
                double ph = 2 * Math.PI * (stride.phase() + g[0]);
                // cos: +1 = the foot furthest forward. The first half of the cycle the foot goes
                // back under the body (planted, carrying it forward); the second it swings forward
                // and is LIFTED, which is what reads as a step rather than a slide.
                float forward = (float) Math.cos(ph);
                float lift = (float) Math.max(0.0, -Math.sin(ph));
                if (l.sprawl()) {
                    // A sprawled leg sweeps about its root, its foot staying on the ground, and
                    // lifts off it to come forward -- the way an insect's and a spider's do.
                    float out = Math.signum(l.foot()[0] - l.hip()[0]);
                    m.rot[1] += -out * a.front() * g[1] * forward * k;
                    m.rot[2] += out * SPRAWL_LIFT_DEGREES * lift * k;
                } else {
                    m.rot[0] += pitch(a, STRIDE_DEGREES * forward * k);
                    m.shift[1] += STEP_LIFT * l.length() * lift * k;
                }
            }
        }

        // ---- the tail as a CHAIN: each segment turns about its own joint, carried by the last
        List<TailMove> chain = new java.util.ArrayList<>();
        for (int i = 0; i < nTail; i++) {
            RigAnatomy.Tail t = a.tails().get(i);
            if (tailYaw[i] != 0f || tailRaise[i] != 0f) {
                chain.add(new TailMove(t, tailYaw[i], tailRaise[i]));
            } else {
                chain.add(new TailMove(t, 0f, 0f));
            }
        }

        Map<String, BedrockAnimation.BonePose> pose = assemble(a, baseOrEmpty, moves, chain);
        return new Out(pose, root.chain(a), 1f - sleep);
    }

    /**
     * <b>Each leg's place in the stride</b>, a fraction of a cycle, from WHERE it stands:
     * its side and its row, counted front to back on each side.
     *
     * <ul>
     *   <li>two legs: left and right alternate;</li>
     *   <li>four: a trot, the diagonal pairs together;</li>
     *   <li>six: the alternating tripod (front and back of one side with the middle of the
     *       other);</li>
     *   <li>eight and more: a wave running front to back, {@code 1/rows} of a cycle a row, the two
     *       sides in antiphase.</li>
     * </ul>
     *
     * <p>Every one of those is {@code side/2 − row·lag} (a leg further back steps later: the wave runs front to back): a lag of half a cycle a row for up to three
     * rows, {@code 1/rows} beyond. The right side is the one the walk clip's {@code legR} is on, at
     * the phase that clip gives it, so a biped's legs and its clip-driven arms keep the rhythm they
     * always had.
     */
    static Map<String, Float> strideOffsets(RigAnatomy a) {
        Map<String, Float> out = new HashMap<>();
        gait(a).forEach((bone, g) -> out.put(bone, g[0]));
        return out;
    }

    /** Legs whose hips are closer than this (units) are one leg in two boxes: they step together. */
    static final float SAME_ROW = 1.5f;

    /**
     * Per leg: {@code {phase offset, sweep degrees}}. The offset is {@link #strideOffsets}'s; the
     * sweep is how far a SPRAWLED leg may swing about its root before its foot meets the foot of the
     * leg in front of or behind it -- a spider's legs are close together and step out of phase, so
     * a fixed sweep crossed them over each other.
     */
    static Map<String, float[]> gait(RigAnatomy a) {
        Map<String, float[]> out = new HashMap<>();
        List<RigAnatomy.Leg> legs = a.legs();
        if (legs.isEmpty()) {
            return out;
        }
        float cx = 0f;
        for (RigAnatomy.Leg l : legs) {
            cx += l.hip()[0];
        }
        cx /= legs.size();
        Map<Integer, List<RigAnatomy.Leg>> bySide = new java.util.TreeMap<>();
        for (RigAnatomy.Leg l : legs) {
            int side = l.hip()[0] - cx >= 0 ? 1 : -1;
            bySide.computeIfAbsent(side, s -> new java.util.ArrayList<>()).add(l);
        }
        // Rows on each side, front first (the larger front()·z, the further forward). A box that
        // stands where another leg already stands -- the cat's paw stub under its hind leg -- is
        // the same leg, in the same row, or the stub walks away from the leg it is the foot of.
        Map<Integer, List<List<RigAnatomy.Leg>>> rowsBySide = new java.util.TreeMap<>();
        int rows = 0;
        for (Map.Entry<Integer, List<RigAnatomy.Leg>> e : bySide.entrySet()) {
            List<RigAnatomy.Leg> side = e.getValue();
            side.sort(java.util.Comparator.comparingDouble(l -> -a.front() * l.hip()[2]));
            List<List<RigAnatomy.Leg>> sideRows = new java.util.ArrayList<>();
            for (RigAnatomy.Leg l : side) {
                List<RigAnatomy.Leg> last = sideRows.isEmpty() ? null : sideRows.get(sideRows.size() - 1);
                if (last != null && Math.hypot(l.hip()[0] - last.get(0).hip()[0],
                        l.hip()[2] - last.get(0).hip()[2]) < SAME_ROW) {
                    last.add(l);
                } else {
                    List<RigAnatomy.Leg> row = new java.util.ArrayList<>();
                    row.add(l);
                    sideRows.add(row);
                }
            }
            rowsBySide.put(e.getKey(), sideRows);
            rows = Math.max(rows, sideRows.size());
        }
        float lag = rows <= 3 ? 0.5f : 1f / rows;
        // The clip's legR is +25 degrees (raw X) at the start of its cycle. A forward swing of
        // cos(phase) is raw -front·cos, so the right side starts at 0 for a creature facing -Z.
        int right = rightSign(a);
        float rightStart = a.front() < 0 ? 0f : 0.5f;
        // Two feet a phase difference d apart close by up to 2·A·sin(π·d); keep that to 60% of
        // the gap between them.
        float closeBy = 2f * (float) Math.sin(Math.PI * lag);
        for (Map.Entry<Integer, List<List<RigAnatomy.Leg>>> e : rowsBySide.entrySet()) {
            float start = e.getKey() == right ? rightStart : rightStart + 0.5f;
            List<List<RigAnatomy.Leg>> sideRows = e.getValue();
            for (int row = 0; row < sideRows.size(); row++) {
                // MINUS: a leg further back reaches each point of the stride a little LATER, so the
                // wave runs from the front legs to the back ones.
                float o = start - row * lag;
                o -= (float) Math.floor(o);
                for (RigAnatomy.Leg l : sideRows.get(row)) {
                    float sweep = SPRAWL_SWEEP_DEGREES;
                    if (l.sprawl()) {
                        float gap = Float.MAX_VALUE;
                        for (int nb : new int[]{row - 1, row + 1}) {
                            if (nb >= 0 && nb < sideRows.size()) {
                                for (RigAnatomy.Leg n : sideRows.get(nb)) {
                                    gap = Math.min(gap, (float) Math.hypot(l.foot()[0] - n.foot()[0],
                                            l.foot()[2] - n.foot()[2]));
                                }
                            }
                        }
                        float lateral = Math.max(1f, Math.abs(l.foot()[0] - l.hip()[0]));
                        if (gap < Float.MAX_VALUE) {
                            float travel = 0.6f * gap / Math.max(0.5f, closeBy);
                            sweep = Math.max(MIN_SWEEP_DEGREES, Math.min(SPRAWL_SWEEP_DEGREES,
                                    (float) Math.toDegrees(Math.atan(travel / lateral))));
                        }
                    }
                    out.put(l.bone(), new float[]{o, sweep});
                }
            }
        }
        return out;
    }

    /** Which sign of X (rig space) the creature's RIGHT is on: where its {@code legR}/{@code armR} is. */
    private static int rightSign(RigAnatomy a) {
        for (CreatureSculpt.Part p : a.parts()) {
            String n = p.name();
            int s = n.equals("legR") || n.equals("armR") ? 1
                    : n.equals("legL") || n.equals("armL") ? -1 : 0;
            if (s != 0) {
                float x = (p.from()[0] + p.to()[0]) / 2f;
                if (Math.abs(x) > 0.01f) {
                    return (int) Math.signum(x) * s;
                }
            }
        }
        return 1;
    }

    private static void lieOnBack(RigAnatomy a, Plan plan, Root root, float w) {
        root.add(90f * w, 0f, new float[]{0, a.groundY(), 0}, w);
        for (int ax = 0; ax < 3; ax++) {
            root.shift[ax] += plan.bipedLieShift()[ax] * w;
        }
    }

    private static void headDown(RigAnatomy a, Map<String, Move> moves, float degrees) {
        if (a.head() == null) {
            return;
        }
        // The head's own pivot is its neck, so a nod is the bone's own rotation.
        Move m = move(moves, a.head(), null);
        m.rot[0] += pitch(a, -degrees);
    }

    // ===================== assembly =====================

    /** One bone's generated move: Euler degrees about {@code hinge}, then a shift. */
    private static final class Move {
        final float[] hinge;
        final float[] rot = new float[3];
        final float[] shift = new float[3];
        float keep = 1f;

        Move(float[] hinge) {
            this.hinge = hinge;
        }
    }

    private record TailMove(RigAnatomy.Tail tail, float yaw, float raise) {
    }

    private static Move move(Map<String, Move> moves, String bone, float[] hinge) {
        return moves.computeIfAbsent(bone, k -> new Move(hinge));
    }

    /** The whole-body move: a pitch and a roll about a weighted pivot, then a shift. */
    private static final class Root {
        float pitch;
        float roll;
        final float[] pivotSum = new float[3];
        float pivotWeight;
        final float[] shift = new float[3];

        void add(float pitchDeg, float rollDeg, float[] pivot, float weight) {
            pitch += pitchDeg;
            roll += rollDeg;
            float k = Math.abs(pitchDeg) + Math.abs(rollDeg);
            if (k > 0 && weight > 0) {
                for (int i = 0; i < 3; i++) {
                    pivotSum[i] += pivot[i] * k;
                }
                pivotWeight += k;
            }
        }

        RigPose.Chain chain(RigAnatomy a) {
            if (pitch == 0 && roll == 0 && shift[0] == 0 && shift[1] == 0 && shift[2] == 0) {
                return null;
            }
            float[] pivot = pivotWeight > 0
                    ? new float[]{pivotSum[0] / pivotWeight, pivotSum[1] / pivotWeight,
                    pivotSum[2] / pivotWeight}
                    : new float[]{0, 0, 0};
            return RigPose.about(rootRotation(a, pitch, roll), pivot, shift);
        }
    }

    /** Nose-up {@code pitch} then a roll about the length of the body, for this creature's facing. */
    static Quaternionf rootRotation(RigAnatomy a, float pitchDeg, float rollDeg) {
        return new Quaternionf()
                .rotateZ((float) Math.toRadians(rollDeg))
                .rotateX((float) Math.toRadians(pitch(a, pitchDeg)));
    }

    /**
     * Degrees about X that tip the FRONT up (or swing a hanging leg's foot forward) by
     * {@code degrees}: {@code R_x(θ)} sends {@code (0,0,f)} to {@code (·, −f·sinθ, ·)}, so the
     * front rises when {@code θ = −f·degrees}. One sign, spelt once (ladder §2.3).
     */
    static float pitch(RigAnatomy a, float degrees) {
        return -a.front() * degrees;
    }

    private static Map<String, BedrockAnimation.BonePose> assemble(
            RigAnatomy a, Map<String, BedrockAnimation.BonePose> base, Map<String, Move> moves,
            List<TailMove> chain) {
        Map<String, CreatureSculpt.Part> byName = new HashMap<>();
        for (CreatureSculpt.Part p : a.parts()) {
            byName.putIfAbsent(p.name(), p);
        }
        Map<String, BedrockAnimation.BonePose> out = new LinkedHashMap<>(base);
        for (Map.Entry<String, Move> e : moves.entrySet()) {
            CreatureSculpt.Part part = byName.get(e.getKey());
            if (part == null) {
                continue;
            }
            Move m = e.getValue();
            Quaternionf q = RigTransform.rotation(m.rot);
            float[] h = m.hinge == null ? part.pivot() : m.hinge;
            Vector3f hv = new Vector3f(h[0], h[1], h[2]);
            Vector3f t = new Vector3f(hv).sub(new Vector3f(hv).rotate(q))
                    .add(m.shift[0], m.shift[1], m.shift[2]);
            out.put(e.getKey(), onTop(part, scaled(base.get(e.getKey()), m.keep), q, t));
        }
        // The tail: F_i = F_(i-1) ∘ turn(joint_i)
        Quaternionf accQ = new Quaternionf();
        Vector3f accT = new Vector3f();
        for (TailMove tm : chain) {
            CreatureSculpt.Part part = byName.get(tm.tail().bone());
            if (part == null) {
                continue;
            }
            float[] j = tm.tail().joint();
            Quaternionf local = new Quaternionf()
                    .rotateY((float) Math.toRadians(tm.yaw()))
                    .rotateX((float) Math.toRadians(a.front() * tm.raise()));
            Vector3f jv = new Vector3f(j[0], j[1], j[2]);
            Vector3f lt = new Vector3f(jv).sub(new Vector3f(jv).rotate(local));
            // acc ∘ local:  x -> accQ(local x + lt) + accT
            accT = new Vector3f(lt).rotate(accQ).add(accT);
            accQ = new Quaternionf(accQ).mul(local);
            if (isIdentity(accQ, accT)) {
                continue;
            }
            out.put(part.name(), onTop(part, base.get(part.name()), accQ, accT));
        }
        return out;
    }

    private static boolean isIdentity(Quaternionf q, Vector3f t) {
        return Math.abs(q.x) < 1e-7f && Math.abs(q.y) < 1e-7f && Math.abs(q.z) < 1e-7f
                && t.lengthSquared() < 1e-10f;
    }

    /**
     * <b>A rigid move {@code F(y) = q·y + t} applied on top of a bone's clip pose, as one bone
     * pose.</b> The clip moves the bone about its pivot ({@code B(y) = p + q_b(y − p) + o_b}), and
     * {@code F∘B} is {@code p + (q·q_b)(y − p) + [q(p + o_b) + t − p]} — so the rotation is
     * {@code q·q_b} and the position channel is the bracket, mirrored back into the sculpt's X the
     * way {@link RigPose} reads it.
     */
    static BedrockAnimation.BonePose onTop(CreatureSculpt.Part part, BedrockAnimation.BonePose base,
                                           Quaternionf q, Vector3f t) {
        Quaternionf qb = base == null ? new Quaternionf() : RigTransform.rotation(base.rotation());
        Vector3f ob = base == null ? new Vector3f()
                : new Vector3f(-base.position()[0], base.position()[1], base.position()[2]);
        Vector3f p = new Vector3f(part.pivot()[0], part.pivot()[1], part.pivot()[2]);
        Quaternionf r = new Quaternionf(q).mul(qb);
        Vector3f o = new Vector3f(p).add(ob).rotate(q).add(t).sub(p);
        return new BedrockAnimation.BonePose(eulerDegrees(r), new float[]{-o.x, o.y, o.z},
                base == null ? new float[]{1, 1, 1} : base.scale());
    }

    /**
     * The whole-body move {@link Out#root} as a pose of a root bone at the origin with no rest rotation (the
     * Bedrock pack's root bone), in the convention {@link #onTop} gives every other bone.
     */
    public static BedrockAnimation.BonePose rootPose(RigPose.Chain c) {
        if (c == null) {
            return new BedrockAnimation.BonePose(new float[3], new float[3], new float[]{1, 1, 1});
        }
        float[] q = c.rotationXyzw();
        float[] t = c.translation();
        return new BedrockAnimation.BonePose(eulerDegrees(new Quaternionf(q[0], q[1], q[2], q[3])),
                new float[]{-t[0], t[1], t[2]}, new float[]{1, 1, 1});
    }

    /** {@link RigTransform#rotation}'s inverse: Z·Y·X Euler degrees for a quaternion. */
    static float[] eulerDegrees(Quaternionf q) {
        Vector3f e = new Quaternionf(q).normalize().getEulerAnglesZYX(new Vector3f());
        return new float[]{(float) Math.toDegrees(e.x), (float) Math.toDegrees(e.y),
                (float) Math.toDegrees(e.z)};
    }

    private static BedrockAnimation.BonePose scaled(BedrockAnimation.BonePose b, float k) {
        if (b == null || k >= 1f) {
            return b;
        }
        if (k <= 0f) {
            return null;
        }
        float[] r = b.rotation();
        float[] p = b.position();
        return new BedrockAnimation.BonePose(new float[]{r[0] * k, r[1] * k, r[2] * k},
                new float[]{p[0] * k, p[1] * k, p[2] * k}, b.scale());
    }

    /** Smoothstep: a transition that eases out of rest and into the pose. */
    public static float ease(float w) {
        float x = Math.max(0f, Math.min(1f, w));
        return x * x * (3f - 2f * x);
    }

    // ===================== measuring a pose =====================

    /**
     * Every box's eight corners after a pose and a root, in model units — the same composition
     * {@link RigPose} and {@link RigTransform#place} draw with. The plan solves its drops on this,
     * and the tests check every pose on it.
     */
    public static Map<String, float[][]> corners(List<CreatureSculpt.Part> parts,
                                                 Map<String, BedrockAnimation.BonePose> pose,
                                                 RigPose.Chain root) {
        RigPose.Frame frame = RigPose.compose(parts, pose, null, false, root);
        Map<String, float[][]> out = new LinkedHashMap<>();
        for (CreatureSculpt.Part p : parts) {
            RigPose.Chain c = frame.chains().get(p.name());
            if (c == null) {
                c = new RigPose.Chain(new float[]{0, 0, 0, 1}, new float[]{0, 0, 0});
            }
            Quaternionf chainR = new Quaternionf(c.rotationXyzw()[0], c.rotationXyzw()[1],
                    c.rotationXyzw()[2], c.rotationXyzw()[3]);
            Vector3f chainT = new Vector3f(c.translation()[0], c.translation()[1],
                    c.translation()[2]);
            BedrockAnimation.BonePose bp = pose == null ? null : pose.get(p.name());
            Quaternionf own = RigTransform.rotation(p.rotation());
            Vector3f pivot = new Vector3f(p.pivot()[0], p.pivot()[1], p.pivot()[2]);
            if (bp != null && bp.rotation() != null) {
                own = RigTransform.rotation(bp.rotation()).mul(own);
            }
            Vector3f dOwn = new Vector3f(pivot).sub(new Vector3f(pivot).rotate(own));
            if (bp != null && bp.position() != null) {
                dOwn.add(-bp.position()[0], bp.position()[1], bp.position()[2]);
            }
            Quaternionf total = new Quaternionf(chainR).mul(own);
            Vector3f tTotal = new Vector3f(dOwn).rotate(chainR).add(chainT);
            float[][] cs = new float[8][];
            for (int i = 0; i < 8; i++) {
                Vector3f v = new Vector3f(
                        (i & 1) == 0 ? p.from()[0] : p.to()[0],
                        (i & 2) == 0 ? p.from()[1] : p.to()[1],
                        (i & 4) == 0 ? p.from()[2] : p.to()[2]);
                v.rotate(total).add(tTotal);
                cs[i] = new float[]{v.x, v.y, v.z};
            }
            out.put(p.name(), cs);
        }
        return out;
    }

    private static float lowest(RigAnatomy a, Out o, java.util.Set<String> skip) {
        float min = Float.MAX_VALUE;
        for (Map.Entry<String, float[][]> e : corners(a.parts(), o.pose(), o.root()).entrySet()) {
            if (skip.contains(e.getKey())) {
                continue;
            }
            for (float[] c : e.getValue()) {
                min = Math.min(min, c[1]);
            }
        }
        return min == Float.MAX_VALUE ? a.groundY() : min;
    }

    /** The lowest corner of every box, optionally leaving out the legs and the tail. */
    static float lowestExcept(RigAnatomy a, Out o, boolean noLegs, boolean noTail) {
        java.util.Set<String> skip = new java.util.HashSet<>();
        if (noLegs) {
            for (RigAnatomy.Leg l : a.legs()) {
                skip.addAll(subtree(a.parts(), l.bone()));
            }
        }
        if (noTail) {
            for (RigAnatomy.Tail t : a.tails()) {
                skip.addAll(subtree(a.parts(), t.bone()));
            }
        }
        float min = Float.MAX_VALUE;
        for (Map.Entry<String, float[][]> e : corners(a.parts(), o.pose(), o.root()).entrySet()) {
            if (skip.contains(e.getKey())) {
                continue;
            }
            for (float[] c : e.getValue()) {
                min = Math.min(min, c[1]);
            }
        }
        return min == Float.MAX_VALUE ? a.groundY() : min;
    }

    private static float[] allBounds(RigAnatomy a, Out o) {
        float[] b = null;
        for (float[][] cs : corners(a.parts(), o.pose(), o.root()).values()) {
            float[] x = RigAnatomy.bounds(cs);
            b = b == null ? x : new float[]{Math.min(b[0], x[0]), Math.min(b[1], x[1]),
                    Math.min(b[2], x[2]), Math.max(b[3], x[3]), Math.max(b[4], x[4]),
                    Math.max(b[5], x[5])};
        }
        return b == null ? new float[6] : b;
    }

    /** A bone and everything hanging from it. */
    public static java.util.Set<String> subtree(List<CreatureSculpt.Part> parts, String root) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        out.add(root);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (CreatureSculpt.Part p : parts) {
                if (p.parent() != null && out.contains(p.parent()) && out.add(p.name())) {
                    grew = true;
                }
            }
        }
        return out;
    }

    private static float[] average(RigAnatomy a, boolean front) {
        float[] s = new float[3];
        int n = 0;
        for (RigAnatomy.Leg l : a.legs()) {
            if (l.front() == front) {
                for (int i = 0; i < 3; i++) {
                    s[i] += l.hip()[i];
                }
                n++;
            }
        }
        return n == 0 ? center(a) : new float[]{s[0] / n, s[1] / n, s[2] / n};
    }

    private static float[] center(RigAnatomy a) {
        return new float[]{0, (a.groundY() + a.topY()) / 2f, 0};
    }
}
