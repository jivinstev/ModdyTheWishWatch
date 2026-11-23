package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.moddingfromamod.runtime.BedrockAnimation;
import com.moddingfromamod.runtime.CreatureSculpt;

/**
 * <b>THE MATRIX LAYER — one transform per BONE, composed down the chain, with the rest pose locked
 * into it.</b>
 *
 * <p>This is the piece {@code docs/VANILLA_RIG_PARITY.md} §9 says must exist before any animation
 * work, and the reason is that a sculpt already carries per-part rotations AND per-part
 * displacements. Those are the creature's <i>identity</i>. An animation composes ON TOP of them,
 * once, as a transform applied to a bone and inherited by everything under it — it must never be
 * re-derived per part.
 *
 * <h2>What was missing, and why a walk cycle is the thing that exposes it</h2>
 *
 * <p>{@link RigPlan.Group} carries a {@code chainRotationXyzw}/{@code chainTranslation} pair baked
 * at PLAN time from the ancestors' <b>rest</b> rotations. That is correct forever for a static
 * creature and wrong on the first keyframe: when {@code armL} swings, its four claws are separate
 * displays whose chain still says where the arm was at rest, so the arm moves and the claws stay
 * behind. The creature comes apart at the joints, and because the error is per-part it reads as a
 * modelling bug rather than a maths one.
 *
 * <p>So this class recomputes the chain per frame, with each ancestor contributing the SAME
 * transform {@code RuntimeGeoModel.drawBone} gives it:
 *
 * <pre>  m -> pivot + animPos + [R(anim) · R(rest)] · (m - pivot)</pre>
 *
 * <p>Composed parent-first, {@code (Rp,Tp) ∘ (Rc,Tc) = (Rp·Rc, Rp·Tc + Tp)}. The animation rotation
 * is applied OUTSIDE the rest rotation, matching {@code mulPose(anim…)} then {@code mulPose(rest…)}
 * on a PoseStack, and the position channel is applied outside both — the mod translates by it
 * BEFORE it rotates, so it is not turned by the bone's own rotation. Getting either of those
 * backwards is invisible on a bone whose rest tilt is zero, which is most of them.
 *
 * <h2>🔴 Scale channels are REFUSED, on purpose</h2>
 *
 * <p>§9.2 is the hazard: a non-unit scale on a display makes its transform non-orthonormal, vanilla
 * takes the other branch in {@code PoseStack.mulPose(Matrix4f)}, and that bone's side faces shade
 * differently — measured at 0.800 against 0.737 on a north face. A scale channel would therefore
 * animate the creature AND quietly change its colour, on one bone.
 *
 * <p>{@link Frame#scaledBones()} names every bone a clip tried to scale. Nothing here silently
 * drops it and nothing silently applies it: the caller is told, so "the squash channel did nothing"
 * can never be confused with "the squash channel works". Supporting it means folding the pose scale
 * into the MODEL and regenerating the pack, which is affordable for a static scale and probably not
 * per frame — find that out on a fixture, not on a creature.
 */
public final class RigPose {

    private RigPose() {
    }

    /** One bone's composed ancestor transform, in the shape {@link RigPlan.Group} stores it. */
    public record Chain(float[] rotationXyzw, float[] translation) {

        static Chain identity() {
            return new Chain(new float[]{0, 0, 0, 1}, new float[]{0, 0, 0});
        }
    }

    /**
     * Everything a driver needs for one animation frame.
     *
     * @param chains      bone name to the composed transform of its ANCESTORS — what
     *                    {@link RigTransform#place} wants, but for this frame rather than for rest
     * @param moved       every bone whose own transform or whose ancestors' changed, so a driver can
     *                    rewrite exactly those displays and no more. An idle creature yields an
     *                    empty set, which is what makes the cost model hold
     * @param scaledBones bones the clip tried to SCALE, which this layer refuses — see the class
     *                    javadoc
     * @param posed       the subset of {@code moved} that the CLIP moved (its own keyframe or an
     *                    animated ancestor), leaving out bones marked only by head-follow. A driver
     *                    keeps it so that a bone the clip let go of is written back once &mdash; see
     *                    {@code RigDriver.animate}
     */
    public record Frame(Map<String, Chain> chains, Set<String> moved, Set<String> scaledBones,
                        Set<String> posed) {
    }

    /**
     * Compose one frame.
     *
     * @param mirroredParts the sculpt already through {@link RigTransform#mirrorIntoWorldSpace},
     *                      i.e. the same space the groups and the model file are in
     * @param pose          the clip's sample, keyed by bone name, exactly as
     *                      {@code AnimationState.pose} produces it. Angles are RAW sculpt-space
     *                      degrees and are applied positively here, for the same reason
     *                      {@link RigTransform#mirrorIntoWorldSpace} does not negate a rest tilt:
     *                      the mirror's conjugation already accounts for it
     */
    public static Frame compose(List<CreatureSculpt.Part> mirroredParts,
                                Map<String, BedrockAnimation.BonePose> pose) {
        return compose(mirroredParts, pose, null);
    }

    /**
     * <b>&hellip;with the creature LOOKING somewhere</b> &mdash; head-follow composed by the same
     * layer, so a head turn is inherited by everything under the head exactly as a clip keyframe is.
     *
     * <h2>Why head-follow belongs HERE and not in the driver</h2>
     *
     * <p>{@code RuntimeGeoModel.drawBone} applies the follow at the head BONE, inside the pose
     * stack, so every descendant is turned by it &mdash; the head's snout, eyes, horns and teeth are
     * separate bones and they all ride the turn. That is the definition of an inherited transform,
     * which is the one thing this class exists to compute.
     *
     * <p>{@link RigDriver#lookAt} used to apply it as a one-off argument to
     * {@link RigTransform#place} on the single group {@link RigPlan#isHead} matched, which is
     * correct only when the head is ONE display. It is one display on the grouped plan and it is
     * not on either of the other two: measured on {@link SpikeFixture#SPIKOR}, whose head subtree is
     * <b>26</b> boxes, {@code exactGroups} splits <b>7</b> of them onto displays of their own
     * (<code>hair2</code> and the six <code>kit_horn2x</code>) and {@link RigPlan#atoms} splits all
     * 26. So a head turn writes <b>8</b> displays on the exact arm and <b>26</b> on the atom arm,
     * where the old path wrote 1 on both and left the face behind.
     *
     * <p>So the follow is an input to the chain, and the driver's only remaining job is to write
     * the displays this frame reports as moved. One composer, three granularities.
     *
     * @param headDeg {@code {yaw, pitch}} in degrees relative to the body, or null / all-zero for
     *                no follow &mdash; in which case this is exactly {@link #compose(List, Map)}
     *                and {@code moved} is unchanged, so a creature that is not looking anywhere
     *                still costs nothing
     */
    public static Frame compose(List<CreatureSculpt.Part> mirroredParts,
                                Map<String, BedrockAnimation.BonePose> pose,
                                float[] headDeg) {
        boolean looking = headDeg != null && (headDeg[0] != 0f || headDeg[1] != 0f);
        return compose(mirroredParts, pose, headDeg, looking);
    }

    /**
     * <b>&hellip;and say separately whether the LOOK changed</b> &mdash; the form the one pose
     * writer uses.
     *
     * <p>The three-argument form marks the head subtree moved whenever the creature is looking
     * anywhere, which is right for a caller that writes the head and nothing else. It is wrong for a
     * writer that carries the head INSIDE a clip frame: a creature holding a 35-degree look would
     * repay its whole head subtree every write cycle for a look that has not moved. So here the look
     * is always applied to the GEOMETRY &mdash; every bone this frame writes under the head carries
     * it &mdash; and only {@code headChanged} decides whether the look by itself makes the head
     * subtree moved. A look returning to straight ahead is a change too, and is written, or the head
     * would stay turned.
     */
    public static Frame compose(List<CreatureSculpt.Part> mirroredParts,
                                Map<String, BedrockAnimation.BonePose> pose,
                                float[] headDeg, boolean headChanged) {
        return compose(mirroredParts, pose, headDeg, headChanged, null);
    }

    /**
     * <b>…with the WHOLE BODY moved first.</b>
     *
     * <p>{@code root} is a rigid transform in model space applied as the parent of every ROOT bone,
     * so a swim pitch, a sit, a lie-down or a landing crouch turns and lowers the creature as one
     * piece and every bone's own pose (and the head's look) composes INSIDE it. It is M2 done in
     * the one pose writer rather than beside it, which is what keeps one tween clock (ladder §5.4).
     *
     * <p>It is not done per root bone because the dragon's head is a ROOT bone: baking the body's
     * turn into the head's own pose would turn the LOOK about where the head used to be, and a
     * sitting creature that glanced sideways would swing its head off its neck.
     *
     * @param root null or identity for the rest of the creature where it stands
     */
    public static Frame compose(List<CreatureSculpt.Part> mirroredParts,
                                Map<String, BedrockAnimation.BonePose> pose,
                                float[] headDeg, boolean headChanged, Chain root) {
        boolean rooted = root != null && !isIdentity(root);
        Map<String, CreatureSculpt.Part> byName = new LinkedHashMap<>();
        for (CreatureSculpt.Part p : mirroredParts) {
            byName.putIfAbsent(p.name(), p);
        }
        float[] head = headDeg != null && (headDeg[0] != 0f || headDeg[1] != 0f) ? headDeg : null;
        Map<String, Chain> chains = new LinkedHashMap<>();
        Map<String, Chain> accumulated = new LinkedHashMap<>();
        Set<String> moved = new LinkedHashSet<>();
        Set<String> posed = new LinkedHashSet<>();
        Set<String> scaled = new LinkedHashSet<>();
        for (CreatureSculpt.Part p : mirroredParts) {
            resolve(p, byName, pose, head, headChanged, chains, accumulated, moved, posed, scaled,
                    new LinkedHashSet<>(), rooted ? root : null);
        }
        return new Frame(chains, moved, scaled, posed);
    }


    /**
     * The chain a group should be placed with, falling back to the group's own baked rest chain.
     *
     * <p>A group whose anchor bone is not in the frame — a synthetic group, or a bone the sculpt
     * renamed — keeps what the plan gave it rather than snapping to the origin. Returning identity
     * there would move that piece of the creature to its feet on the first animated tick, which is
     * a spectacular failure for a case that should simply be a no-op.
     */
    public static RigPlan.Group withChain(RigPlan.Group g, Frame frame) {
        Chain c = frame.chains().get(g.boneName());
        if (c == null) {
            return g;
        }
        return g.withChain(c.rotationXyzw(), c.translation());
    }

    /**
     * Does this frame move anything about this group — its own bone, or an ancestor?
     *
     * <p>Keyed on {@link RigPlan.Group#boneName()}, not on the group's name, so an atom rig's
     * pattern overlay ({@code "body#pattern"}) moves with the box it is drawn on. A frame is
     * composed from the SCULPT and has never heard of the suffix, so asking with it on returns
     * false forever — and false here is silent: the base cube walks and its stripes stay behind.
     */
    public static boolean movesGroup(RigPlan.Group g, Frame frame) {
        return frame.moved().contains(g.boneName());
    }

    /** Every bone any of these clips ever moves — what has to be split into its own display. */
    public static Set<String> animatedBones(Map<String, BedrockAnimation> clips) {
        Set<String> out = new LinkedHashSet<>();
        if (clips == null) {
            return out;
        }
        for (BedrockAnimation a : clips.values()) {
            if (a == null) {
                continue;
            }
            out.addAll(a.bones());
        }
        return out;
    }

    private static Chain resolve(CreatureSculpt.Part p,
                                 Map<String, CreatureSculpt.Part> byName,
                                 Map<String, BedrockAnimation.BonePose> pose,
                                 float[] headDeg,
                                 boolean headChanged,
                                 Map<String, Chain> chains,
                                 Map<String, Chain> accumulated,
                                 Set<String> moved,
                                 Set<String> posed,
                                 Set<String> scaled,
                                 Set<String> visiting,
                                 Chain root) {
        Chain done = accumulated.get(p.name());
        if (done != null) {
            return done;
        }
        if (!visiting.add(p.name())) {
            // A cycle in the sculpt's parent links. Treat it as a root rather than recursing
            // forever: a malformed creature must render wrong, not hang the render thread.
            return Chain.identity();
        }
        // A root bone's parent is the whole-body transform, when there is one -- and every bone is
        // then moved and posed, so the frame after it ends writes each one back (Frame.posed).
        Chain parent = root != null ? root : Chain.identity();
        boolean parentMoved = root != null;
        boolean parentPosed = root != null;
        String parentName = p.parent();
        if (parentName != null && !parentName.isEmpty()) {
            CreatureSculpt.Part up = byName.get(parentName);
            if (up != null) {
                parent = resolve(up, byName, pose, headDeg, headChanged, chains, accumulated,
                        moved, posed, scaled, visiting, root);
                parentMoved = moved.contains(parentName);
                parentPosed = posed.contains(parentName);
            }
        }
        chains.put(p.name(), parent);

        BedrockAnimation.BonePose anim = pose == null ? null : pose.get(p.name());
        if (anim != null && anim.isIdentity()) {
            anim = null;
        }
        if (anim != null && anim.scale() != null
                && (anim.scale()[0] != 1f || anim.scale()[1] != 1f || anim.scale()[2] != 1f)) {
            scaled.add(p.name());
        }
        // HEAD-FOLLOW IS A BONE TRANSFORM, so it makes this bone moved and every descendant with
        // it -- which is the whole point, and is why it cannot live on the driver's head group.
        boolean headBone = RigPlan.isHeadBone(p.name());
        boolean follows = headDeg != null && headBone;
        if (anim != null || parentMoved || (headChanged && headBone)) {
            moved.add(p.name());
        }
        if (anim != null || parentPosed) {
            posed.add(p.name());
        }

        // R(head) * R(anim) * R(rest) -- the pose turns the bone from where its rest tilt already
        // put it, and the follow turns that. Same order as RuntimeGeoModel.drawBone, which applies
        // the head yaw/pitch BEFORE the animation's rotation and the rest rotation on the stack.
        org.joml.Quaternionf own = RigTransform.rotation(p.rotation());
        if (anim != null && anim.rotation() != null) {
            own = RigTransform.rotation(anim.rotation()).mul(own);
        }
        if (follows) {
            own = RigTransform.headRotation(headDeg).mul(own);
        }
        org.joml.Vector3f pivot = new org.joml.Vector3f(
                p.pivot()[0], p.pivot()[1], p.pivot()[2]);
        // pivot + animPos - own*pivot. The position channel is OUTSIDE the rotation: the mod
        // translates by it before it rotates, so it moves the bone in its PARENT's frame.
        org.joml.Vector3f dOwn = new org.joml.Vector3f(pivot)
                .sub(new org.joml.Vector3f(pivot).rotate(own));
        if (anim != null && anim.position() != null) {
            dOwn.add(-anim.position()[0], anim.position()[1], anim.position()[2]);
        }

        org.joml.Quaternionf pr = new org.joml.Quaternionf(
                parent.rotationXyzw()[0], parent.rotationXyzw()[1],
                parent.rotationXyzw()[2], parent.rotationXyzw()[3]);
        org.joml.Vector3f pt = new org.joml.Vector3f(
                parent.translation()[0], parent.translation()[1], parent.translation()[2]);
        org.joml.Quaternionf r = new org.joml.Quaternionf(pr).mul(own);
        org.joml.Vector3f t = new org.joml.Vector3f(dOwn).rotate(pr).add(pt);
        Chain acc = new Chain(new float[]{r.x, r.y, r.z, r.w}, new float[]{t.x, t.y, t.z});
        accumulated.put(p.name(), acc);
        visiting.remove(p.name());
        return acc;
    }

    /** Every part name, root-first — the order a chain must be composed in. */
    /** True for a chain that moves nothing (to float noise). */
    public static boolean isIdentity(Chain c) {
        float[] q = c.rotationXyzw();
        float[] t = c.translation();
        return Math.abs(q[0]) < 1e-6f && Math.abs(q[1]) < 1e-6f && Math.abs(q[2]) < 1e-6f
                && Math.abs(t[0]) < 1e-5f && Math.abs(t[1]) < 1e-5f && Math.abs(t[2]) < 1e-5f;
    }

    /**
     * The rigid transform {@code x -> pivot + q(x - pivot) + shift}, as a chain — a turn about a
     * point plus a move, in model units.
     */
    public static Chain about(org.joml.Quaternionf q, float[] pivot, float[] shift) {
        org.joml.Vector3f p = new org.joml.Vector3f(pivot[0], pivot[1], pivot[2]);
        org.joml.Vector3f t = new org.joml.Vector3f(p).sub(new org.joml.Vector3f(p).rotate(q))
                .add(shift[0], shift[1], shift[2]);
        return new Chain(new float[]{q.x, q.y, q.z, q.w}, new float[]{t.x, t.y, t.z});
    }

    static List<String> rootFirst(List<CreatureSculpt.Part> parts) {
        List<String> out = new ArrayList<>();
        Map<String, CreatureSculpt.Part> byName = new LinkedHashMap<>();
        for (CreatureSculpt.Part p : parts) {
            byName.putIfAbsent(p.name(), p);
        }
        Set<String> placed = new LinkedHashSet<>();
        for (CreatureSculpt.Part p : parts) {
            List<String> stack = new ArrayList<>();
            CreatureSculpt.Part at = p;
            Set<String> seen = new LinkedHashSet<>();
            while (at != null && seen.add(at.name())) {
                stack.add(0, at.name());
                at = at.parent() == null ? null : byName.get(at.parent());
            }
            for (String n : stack) {
                if (placed.add(n)) {
                    out.add(n);
                }
            }
        }
        return out;
    }
}
