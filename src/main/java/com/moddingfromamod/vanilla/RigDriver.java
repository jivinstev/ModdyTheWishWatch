package com.moddingfromamod.vanilla;

import java.util.List;
import java.util.Map;

import com.moddingfromamod.runtime.BedrockAnimation;
import com.moddingfromamod.runtime.CreatureSculpt;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * <b>Q2 — moving the rig, and what it costs.</b>
 *
 * <p>Two motions, deliberately separated, because they have completely different prices:
 *
 * <ul>
 *   <li><b>Locomotion</b> — the creature walks. Every display moves the same way, so this is a
 *       position update and nothing else: no transform is rewritten and no model changes.</li>
 *   <li><b>Articulation</b> — the head turns, the arm swings. This rewrites one display's
 *       {@code transformation}, and it is the only thing that costs per-bone traffic.</li>
 * </ul>
 *
 * <p>Keeping them apart is what makes the spike's cost model honest. Lumping them would report a
 * walking creature as costing six transform updates a tick when it really costs none, and would
 * have made the answer to "how many creatures before the tick budget breaks" wrong by roughly the
 * bone count.
 *
 * <h2>Interpolation is what buys the update rate down</h2>
 *
 * <p>The client tweens between poses on its own if told how long to take, so the server does not
 * have to send a pose per tick. {@link #articulate} takes the interval it will next be called at
 * and hands it to the client as {@code interpolation_duration}, so the two cannot disagree — a
 * duration typed separately from the schedule is the same class of bug as the train-axis literal in
 * {@code docs/CAMERA.md}: correct until someone changes the other number.
 */
public final class RigDriver {

    private RigDriver() {
    }

    /**
     * Put the whole rig where the creature is. No transform is touched.
     *
     * <p>Position, not riding. Riding (D2) makes locomotion literally free because the client
     * derives a passenger's position itself, but it also fixes the display's offset to the
     * vehicle's passenger attachment point, which is not where a creature's bones are. Measured as
     * a separate option rather than assumed to be the better one.
     */
    public static void follow(DisplayRig rig, Vec3 to, float yawDeg) {
        follow(rig, to, yawDeg, yawDeg);
    }

    /**
     * {@link #follow(DisplayRig, Vec3, float)} with the body DRAWN at {@code yawDeg} while the
     * carrier keeps {@code carrierYaw}: the drawn turn is damped ({@link RigTurn}) and the
     * carrier's own AI must not be steered by that.
     */
    public static void follow(DisplayRig rig, Vec3 to, float carrierYaw, float yawDeg) {
        // The BASE is a real mob and takes the creature's own yaw. A DISPLAY does not: it takes
        // DisplayRig.displayYaw, because the sculpt is mirrored into world space and the half turn
        // is what cancels that. Writing yawDeg here instead is the bug that shipped -- see the
        // javadoc on DisplayRig.displayYaw for why nothing but a human eye caught it.
        float rigYaw = DisplayRig.displayYaw(yawDeg);
        rig.base().snapTo(to.x, to.y, to.z, carrierYaw, 0f);
        rig.base().setYBodyRot(carrierYaw);
        for (Entity d : rig.displays()) {
            d.snapTo(to.x, to.y, to.z, rigYaw, 0f);
        }
        // The name plate is the ONE piece of a rig that does not ride at the carrier's feet, and
        // it takes no yaw at all (it is billboarded). Looping it in with the bones would leave the
        // name lying on the floor -- the same class of miss as the half turn this method used to
        // drop, so it goes through the rig's own helper rather than being open-coded here.
        rig.followNameplate(to.x, to.z);
        // the hit proxies stand over their parts, turned with the body.
        rig.followProxies(to, yawDeg);
    }

    /**
     * Rewrite the transforms of the bones an animation frame actually moves.
     *
     * @param pose               bone name to pose, as {@code AnimationState.pose} already produces
     * @param interpolationTicks how long until the next call — handed to the client so it tweens
     *                           over exactly the gap rather than snapping and waiting
     * @return how many displays were rewritten. This is the number Q2 turns into bytes per second,
     *     and returning it rather than logging it means a test can assert an idle creature costs
     *     ZERO — the claim the whole cost model rests on
     */
    public static int articulate(DisplayRig rig, Map<String, BedrockAnimation.BonePose> pose,
                                 int interpolationTicks) {
        if (pose == null || pose.isEmpty()) {
            return 0;
        }
        List<RigPlan.Group> groups = rig.groups();
        List<Entity> displays = rig.displays();
        int written = 0;
        for (int i = 0; i < groups.size() && i < displays.size(); i++) {
            RigPlan.Group g = groups.get(i);
            // boneName(), not name(): an atom rig's pattern overlay is a second display of the SAME
            // bone, and `pose` is keyed by the sculpt's own names. See RigPlan.Group.boneName().
            BedrockAnimation.BonePose bp = pose.get(g.boneName());
            if (bp == null || bp.isIdentity()) {
                // A BONE THAT DID NOT MOVE COSTS NOTHING. Rewriting every display every frame is
                // the easy version and it is six times the traffic for a creature that is only
                // turning its head.
                continue;
            }
            // ROTATIONS COMPOSE, THEY DO NOT ADD -- see RigTransform.place's javadoc. This used to
            // be `rest[i] + pose[i]` per axis, which is only correct when the rest tilt and the
            // keyframe share one axis, and silently wrong for every creature where they do not.
            DisplayRig.apply(displays.get(i),
                    placed(rig, g, g.restRotation(), bp.rotation(), bp.position(), bp.scale(), null),
                    interpolationTicks);
            written++;
        }
        return written;
    }

    /**
     * <b>Drive a WHOLE BODY from one animation frame — the matrix layer's driver.</b>
     *
     * <p>{@link #articulate} rewrites a display when that display's OWN bone has a keyframe. That is
     * right for a head turn and wrong for anything with a hierarchy: when {@code armL} swings, its
     * four claws are separate displays whose baked chain still describes where the arm was at rest,
     * so the arm moves and the claws stay behind. This walks {@link RigPose}'s frame instead, which
     * knows both the composed ancestor transform and which bones an ancestor has moved.
     *
     * <p>Everything else about the cost model is unchanged and deliberately so: a bone nothing moved
     * is not rewritten, so an idle creature still costs <b>zero</b> updates.
     *
     * @param mirroredParts the sculpt in the same space the groups are in
     * @param pose          this frame's sample, as {@code AnimationState.pose} produces it
     * @return how many displays were rewritten
     */
    public static int animate(DisplayRig rig, List<CreatureSculpt.Part> mirroredParts,
                              Map<String, BedrockAnimation.BonePose> pose,
                              int interpolationTicks) {
        return animate(rig, mirroredParts, pose, null, interpolationTicks);
    }

    /**
     * <b>THE pose writer: one clip frame AND the head's look, composed once and written on one
     * tick.</b>
     *
     * <p>Until the head had a writer of its own. {@link #lookAt} rewrote the head subtree every
     * tick, as rest + look, straight after the clip had written it as clip pose; so the head lost
     * the clip's keys (the idle sway) whenever the look was not exactly zero, and it was re-tweened
     * every tick while the body was re-tweened every third. Two writers, two tween clocks, and a
     * head that swam against its body. Here there is one frame &mdash; {@code R(look)·R(clip)·R(rest)}
     * down the chain &mdash; and every display it touches starts its tween on the same tick.
     *
     * <p>What is written: every bone the frame moves (the clip's bones, and the head subtree when
     * the look CHANGED), plus every bone the PREVIOUS clip frame moved and this one does not, once,
     * so a clip that lets go of a bone puts it back rather than freezing it mid-stride. That second
     * set is what {@code lookAt}'s rewrite used to do by accident for the head, and nothing did for
     * the legs.
     *
     * @param head {@code {yaw, pitch}} relative to the body, or null to keep the look the rig last
     *             wrote (so a caller with no look never undoes somebody else's)
     */
    public static int animate(DisplayRig rig, List<CreatureSculpt.Part> mirroredParts,
                              Map<String, BedrockAnimation.BonePose> pose, float[] head,
                              int interpolationTicks) {
        boolean headChanged = head != null && rig.headMoved(head[0], head[1]);
        // The look RECORDED, not the one sampled: when a pitch drift is below the write step
        // the sample is not recorded, and a clip that moves the head anyway must compose
        // the same look every other head display was last written with.
        float[] look = rig.currentHead();
        if (look == null && head != null) {
            look = head;
        }
        RigPose.Frame frame = RigPose.compose(mirroredParts, pose, look, headChanged);
        return animate(rig, frame, pose, interpolationTicks);
    }

    /**
     * {@link #animate(DisplayRig, List, Map, float[], int)} with the whole body moved first — a
     * swim pitch, a sit, a crouch ({@link RigPose#compose(List, Map, float[], boolean,
     * RigPose.Chain)}). Same single writer, same tween.
     */
    public static int animate(DisplayRig rig, List<CreatureSculpt.Part> mirroredParts,
                              Map<String, BedrockAnimation.BonePose> pose, float[] head,
                              RigPose.Chain root, int interpolationTicks) {
        if (root == null) {
            return animate(rig, mirroredParts, pose, head, interpolationTicks);
        }
        boolean headChanged = head != null && rig.headMoved(head[0], head[1]);
        float[] look = rig.currentHead();
        if (look == null && head != null) {
            look = head;
        }
        RigPose.Frame frame = RigPose.compose(mirroredParts, pose, look, headChanged, root);
        return animate(rig, frame, pose, interpolationTicks);
    }

    /**
     * As above, with a frame already composed — so a caller can inspect it before driving. The
     * head bone's own display is given the rig's CURRENT look ({@link DisplayRig#currentHead}),
     * which is the look {@code animate(rig, parts, pose, head, …)} has just recorded and composed.
     */
    public static int animate(DisplayRig rig, RigPose.Frame frame,
                              Map<String, BedrockAnimation.BonePose> pose,
                              int interpolationTicks) {
        float[] look = rig.currentHead();
        List<RigPlan.Group> groups = rig.groups();
        List<Entity> displays = rig.displays();
        java.util.Set<String> released = rig.lastPosed();
        int written = 0;
        for (int i = 0; i < groups.size() && i < displays.size(); i++) {
            RigPlan.Group g = groups.get(i);
            if (!RigPose.movesGroup(g, frame) && !released.contains(g.boneName())) {
                continue;
            }
            BedrockAnimation.BonePose bp = pose == null ? null : pose.get(g.boneName());
            // The head bone's OWN display takes the look in its own rotation; a descendant has it
            // already, inside the chain the frame handed it. Passing it to both would turn the face
            // twice and the head once.
            DisplayRig.apply(displays.get(i),
                    placed(rig, RigPose.withChain(g, frame), g.restRotation(),
                            bp == null ? null : bp.rotation(),
                            bp == null ? null : bp.position(),
                            bp == null ? null : bp.scale(),
                            RigPlan.isHead(g) ? look : null),
                    interpolationTicks);
            written++;
        }
        rig.posed(pose, frame.posed());
        return written;
    }

    /**
     * <b>Lay the whole creature over — the death flop (ladder 4.1), on M2.</b>
     *
     * <p>One rotation about the display origin, applied to <b>every</b> display on top of its rest
     * placement. The curve is vanilla's own ({@link RigFlop}); the axis is the display's local
     * {@code +Z}, which is vanilla's {@code Axis.ZP} in the frame it does the same rotation in —
     * the pose stack has had {@code Axis.YP.rotationDegrees(180 − bodyRot)} applied by then, and
     * that is precisely the frame the rig's {@code YAW_OFFSET} calibration already put every
     * display into.
     *
     * <p>⚠ <b>Rest placement, not the last clip pose, and that is a decision rather than a
     * shortcut.</b> A creature that dies mid-stride keeps no walk pose here. Vanilla's own
     * {@code walkAnimation} decays to zero over the same window, so a dead mob converges to its
     * rest pose either way — but if a later row wants the two composed, the composition is
     * {@code RigTransform.about(<the animated Placed>, q)} and nothing else changes.
     *
     * <p>⚠ <b>Every display, every call.</b> Unlike {@code animate} and {@code lookAt}, there is no
     * "did it move" skip: the whole body is turning, so every display's transform is different
     * every tick. That is the M2 price the ladder quotes (~6× a bone write), and it is only paid
     * for the twenty ticks a death lasts.
     *
     * @param degrees how far over, 0 = upright, 90 = flat. Feed it {@link RigFlop#degrees}
     * @return how many displays were rewritten — always the whole rig
     */
    public static int flop(DisplayRig rig, float degrees, int interpolationTicks) {
        org.joml.Quaternionf q = new org.joml.Quaternionf()
                .rotateZ((float) Math.toRadians(degrees));
        List<RigPlan.Group> groups = rig.groups();
        List<Entity> displays = rig.displays();
        int written = 0;
        for (int i = 0; i < groups.size() && i < displays.size(); i++) {
            DisplayRig.apply(displays.get(i),
                    RigTransform.about(rig.placed(groups.get(i)), q),
                    interpolationTicks);
            written++;
        }
        return written;
    }

    /**
     * <b>One tick of a dying creature</b> — the flop and the red flash together, because in vanilla
     * they are the same event.
     *
     * <p>{@code LivingEntityRenderer.getOverlayCoords} is literally
     * {@code v = hurtTime > 0 || deathTime > 0}, so a mob is red for the WHOLE death animation and
     * not merely for the blow that killed it. Driving the two from one place is what stops a rig
     * flopping in its ordinary colours.
     *
     * <p><b>The flop is a pose like any other, so it goes out on the pose beat with the pose ease.</b>
     * It was written every tick, and the death film drove it with a one-tick ease: a write's ease as long
     * as the gap to the next one, so the fall froze for whole ticks whenever a packet came late and then
     * caught up at double speed (measured, 60 fps). Now it is written on {@link RigAnimator#writesOn},
     * eased over {@link RigAnimator#ease} (the gap plus a tick of slack), and aimed that far ahead on
     * vanilla's curve, so the drawn body reaches each angle about when a vanilla mob would: the shape
     * of the fall is kept, a third of the packets are sent, and a late one no longer stops it. The first
     * tick of the death writes too, off the beat, so the fall starts when the blow lands rather than up
     * to two ticks later.
     *
     * @param gameTime the server's game time, the clock the beat runs on
     * @param interval ticks between pose writes ({@link VanillaRigAnimator#INTERVAL})
     * @return how many displays were rewritten
     */
    public static int dying(DisplayRig rig, int hurtTime, int deathTime, long gameTime, int interval) {
        int n = rig.tint(RigTint.hurtFlash(hurtTime, deathTime));
        if (deathTime == 1 || (deathTime > 1 && RigAnimator.writesOn(gameTime, interval))) {
            int ease = RigAnimator.ease(interval);
            n += flop(rig, RigFlop.aimed(deathTime, ease), ease);
        }
        return n;
    }

    /**
     * <b>Turn the creature's head to look where the carrier is looking.</b>
     *
     * <p>Rewrites only the head group, and only when the angles have actually moved — a creature
     * standing still costs nothing, which is the same rule the animation path follows and the
     * reason Q2's cost model holds.
     *
     * @param headYawDeg   head yaw RELATIVE TO THE BODY, as {@code WishMobRenderer} computes it
     *                     ({@code yHeadRot - bodyRot}, clamped)
     * @param headPitchDeg the pitch, straight off {@code getXRot()}
     * @return how many displays were rewritten &mdash; the head bone and everything under it, so
     *     <b>26</b> on an atom-planned Spikor and <b>8</b> on an exact-planned one, against the
     *     <b>1</b> this method used to write on either. 0 if the creature has no head bone
     */
    public static int lookAt(DisplayRig rig, float headYawDeg, float headPitchDeg,
                             int interpolationTicks) {
        // A HEAD THAT DID NOT MOVE COSTS NOTHING -- the same rule `articulate` follows for a bone
        // that did not move, and the reason Q2's cost model can claim an idle creature is free. A
        // creature holding a 35-degree turn must not repay for it every tick either, so the guard
        // is "unchanged since the last write", not "looking straight ahead".
        if (!rig.headMoved(headYawDeg, headPitchDeg)) {
            return 0;
        }
        List<RigPlan.Group> groups = rig.groups();
        List<Entity> displays = rig.displays();
        float[] head = {headYawDeg, headPitchDeg};
        // 🔴 THE WHOLE HEAD, not the head BOX. Head-follow is a bone transform and therefore
        // INHERITED, so it goes through the one chain composer rather than being applied by hand to
        // whichever single group `isHead` matched. See RigPose.compose(parts, pose, headDeg).
        //
        // 🔴 AND ON TOP OF THE CLIP. This composed with a NULL pose, so the head subtree was
        // written as rest + look: the clip's own head keys and any animated ancestor were thrown
        // away every time the creature looked anywhere. The shipping driver no longer calls this
        // (VanillaRigAnimator hands the look to `animate`, one writer on one tick), but any caller
        // that does gets the head the clip left, turned -- never the head at rest.
        Map<String, BedrockAnimation.BonePose> pose = rig.lastPose();
        RigPose.Frame frame = RigPose.compose(bones(groups), pose, head, true);
        int written = 0;
        for (int i = 0; i < groups.size() && i < displays.size(); i++) {
            RigPlan.Group g = groups.get(i);
            if (!RigPose.movesGroup(g, frame)) {
                continue;
            }
            BedrockAnimation.BonePose bp = pose.get(g.boneName());
            // The head bone's OWN display needs the follow in its own rotation; a descendant has it
            // already, inside the chain the frame just handed it. Passing it to both would apply
            // the turn twice to the face and once to the head.
            DisplayRig.apply(displays.get(i),
                    placed(rig, RigPose.withChain(g, frame), g.restRotation(),
                            bp == null ? null : bp.rotation(),
                            bp == null ? null : bp.position(),
                            bp == null ? null : bp.scale(),
                            RigPlan.isHead(g) ? head : null),
                    interpolationTicks);
            written++;
        }
        return written;
    }

    /**
     * <b>The creature's own bones, recovered from the rig it built.</b>
     *
     * <p>Every plan partitions the sculpt: {@link RigPlan#groups} buckets each part into exactly one
     * group, {@link RigPlan#exactGroups} splits some of those out, and {@link RigPlan#atoms} gives
     * each one its own. So the union of every group's parts IS the creature, on all three, and
     * {@link RigPose#compose} needs nothing else &mdash; only names, parents, pivots and rotations.
     *
     * <p>Derived rather than passed in on purpose. {@link #lookAt} is called from the tick loop with
     * no sculpt in hand, and threading one through would be a second copy of the parts that can
     * disagree with the rig's own &mdash; the {@code TrainFraming} shape {@code docs/CAMERA.md}
     * records, where a value is right at one call site and re-derived at another.
     */
    private static List<CreatureSculpt.Part> bones(List<RigPlan.Group> groups) {
        List<CreatureSculpt.Part> out = new java.util.ArrayList<>();
        for (RigPlan.Group g : groups) {
            out.addAll(g.parts());
        }
        return out;
    }

    /**
     * <b>Where one group goes, at this pose — the ONE place the two granularities are chosen
     * between.</b>
     *
     * <p>Every driver in this class goes through here rather than calling {@link RigTransform}
     * directly, because the difference between a grouped rig and an atom rig is exactly one line
     * (the display's scale) repeated at five call sites — and five call sites is five chances for
     * the next driver method to be written with the branch missing. That failure would not throw:
     * an atom rig driven at group scale draws every box as a full block, which is a creature made
     * of cubes rather than an error.
     *
     * <p>Same shape as {@code DisplayRig.displayYaw}, and for the same reason
     * ({@code docs/CAMERA.md}'s train-axis entry): a value applied at one call site and re-derived
     * at another is how a rig comes out perfect standing still and wrong the moment it moves.
     */
    private static RigTransform.Placed placed(DisplayRig rig, RigPlan.Group g, float[] rest,
                                              float[] poseRot, float[] posePos, float[] poseScale,
                                              float[] head) {
        double scale = rigScale(rig);
        RigTransform.Placed p = RigTransform.place(g, scale, rest, poseRot, posePos,
                poseScale, head);
        return RigTransform.atomise(p, g, scale);
    }

    /**
     * The creature's own scale — the one the group transforms were built with.
     *
     * <p>Deliberately NOT {@link RigBase.Choice#scale()}, which is the base mob's <i>attribute</i>
     * value: how far the vanilla hitbox had to be stretched to reach the creature's height. Those
     * two numbers are different and using the wrong one is invisible at rest and obvious the moment
     * a bone animates — the group would jump to a different size on its first keyframe, which reads
     * as a broken animation rather than as a wrong constant.
     */
    private static double rigScale(DisplayRig rig) {
        return rig.scale();
    }
}
