package com.moddingfromamod.vanilla;

import java.util.List;
import java.util.Map;

import com.moddingfromamod.runtime.AnimationState;
import com.moddingfromamod.runtime.BedrockAnimation;
import com.moddingfromamod.runtime.CreatureSculpt;
import net.minecraft.world.entity.LivingEntity;

/**
 * <b>Drive a rig from a creature that is actually moving — the last piece between a posed frame and
 * an animated creature.</b>
 *
 * <p>Everything before this hands {@link RigDriver#animate} a clip and a time. That proves the
 * MATHS: a frame lands where the mod would put it, a loop closes, a tween arrives. It proves nothing
 * about the wiring from <i>the mob took a step</i> to <i>the rig is posed at the right phase</i>,
 * because a fixture supplied both.
 *
 * <h2>Where the numbers come from, and why they are not this class's to invent</h2>
 *
 * <p>{@code WishMobRenderer} picks a clip and a time from four fields on the entity — is it
 * swinging, how far through the swing, how fast the limbs are moving, how far they have moved —
 * and hands them to {@link AnimationState#select}. This reads the SAME four fields off the base mob
 * and calls the SAME method. It deliberately does not have a walk-detection rule of its own: two
 * rules that must agree are two rules that will not, and the failure is a creature whose legs
 * animate on a different schedule from the mod's on the same input.
 *
 * <p>The one field it cannot share is time. The renderer runs per FRAME on the client with a
 * partial tick; this runs per TICK on the server, because that is where the rig lives.
 *
 * <h2>🔴 Writing every tick would throw away the whole cost model</h2>
 *
 * <p>The rig is 41 display entities and a transform write is ~53 bytes. Posing every bone every tick
 * is 20 writes a second per creature per viewer, which is the traffic the display-entity path exists
 * to avoid. So this writes every {@code interval} ticks and hands the client that gap plus one tick
 * as {@code interpolation_duration} ({@link #ease}), letting it smooth the gap with a tick to spare
 * for a late packet (a tween exactly as long as the gap froze the limbs under lag).
 *
 * <p>The tween duration is DERIVED from the interval for that reason. A duration typed separately
 * from the schedule that drives it is correct until somebody changes the other one, and then the
 * creature stutters in a way that looks like lag rather than like a constant.
 */
public final class RigAnimator {

    private RigAnimator() {
    }

    /**
     * Ticks between pose writes. Three is 6.7 writes a second — fast enough that a keyframe is never
     * more than 150 ms stale, slow enough to be a sixth of the naive cost.
     */
    public static final int DEFAULT_INTERVAL = 3;

    /**
     * What the creature is doing, read off the entity rather than guessed.
     *
     * @param talking     the carrier carries the talking flag ({@link RigTalk})
     * @param talkSeconds seconds since that flag went on
     */
    public record Motion(boolean attacking, float attackProgress,
                         float walkSpeed, float walkPosition, float ageSeconds,
                         boolean talking, float talkSeconds) {

        public Motion(boolean attacking, float attackProgress,
                      float walkSpeed, float walkPosition, float ageSeconds) {
            this(attacking, attackProgress, walkSpeed, walkPosition, ageSeconds, false, 0f);
        }
    }

    /**
     * Read the four fields the renderer reads, from the server's copy of the entity.
     *
     * <p>{@code walkAnimation} is not synced — the client keeps its own, updated from the movement
     * it observes. Both therefore derive from the same real displacement, which is why arm A's
     * client-side phase and arm B's server-side phase agree without anything being sent.
     */
    public static Motion motionOf(LivingEntity base) {
        // No start time is known here, so a talk read this way runs on the creature's age.
        return new Motion(base.isSwinging(), base.getSwingAnimation(1.0f),
                base.walkAnimation.speed(), base.walkAnimation.position(),
                base.tickCount / 20.0f, RigTalk.isTalking(base), base.tickCount / 20.0f);
    }

    /**
     * How long the client eases a pose written every {@code interval} ticks: the gap plus one tick
     * ({@link RigCadence#poseEase}), still one number with the schedule. 0 (a snap) stays 0.
     */
    public static int ease(int interval) {
        return interval <= 0 ? 0 : RigCadence.poseEase(interval);
    }

    /**
     * Should a pose be written on this tick?
     *
     * <p>Pure, so the schedule can be asserted without a world. Anchored on the game time rather
     * than on a counter per rig: several creatures then write on the same ticks instead of drifting
     * into every tick between them, which is the difference between a sixth of the traffic and all
     * of it.
     */
    public static boolean writesOn(long gameTime, int interval) {
        return interval <= 1 || gameTime % interval == 0;
    }

    /** The clip and time this motion selects — the renderer's own rule, not a second one. */
    public static AnimationState.Selection selectionFor(Motion m) {
        return AnimationState.select(m.attacking(), m.attackProgress(),
                m.walkSpeed(), m.walkPosition(), m.ageSeconds(), m.talking(), m.talkSeconds());
    }

    /**
     * Drive one creature for one tick.
     *
     * @return displays rewritten. Zero on the ticks between writes, and zero on a creature standing
     *     still whose idle clip has not moved anything — the claim the cost model rests on
     */
    public static int tick(DisplayRig rig, List<CreatureSculpt.Part> mirroredParts,
                           Map<String, BedrockAnimation> clips, long gameTime, int interval) {
        return tick(rig, mirroredParts, clips, gameTime, interval, null);
    }

    /**
     * @param motion the creature's state, or {@code null} to read it off the entity with
     *               {@link #motionOf}.
     *
     *     <p>🔴 <b>There is one caller that must override it, and the reason is vanilla's.</b>
     *     {@code updateSwingTime()} — the method that advances {@code attackAnim} from 0 to 1
     *     through a swing — is called from {@code Monster.aiStep()}, {@code Player.serverAiStep()}
     *     and {@code RemotePlayer}, and <b>nowhere else in the game</b>. Neither creature path's
     *     entity is a {@code Monster}: {@code WishMobEntity extends PathfinderMob}, and a rig
     *     carrier may not be one at all because {@code Monster.shouldDespawnInPeaceful()} deletes
     *     it on Peaceful ({@link RigBase}). So {@code getAttackAnim} is <b>structurally always 0</b>
     *     for a wished creature, on both paths, and an attack clip driven by it is selected
     *     correctly and frozen on its first frame forever.
     *
     *     <p>That is parity, and it is also just wrong, so {@link VanillaRigAnimator} keeps its own
     *     swing clock and passes the progress in here. The selection RULE stays in this class —
     *     what is overridden is one input to it, not the decision.
     */
    public static int tick(DisplayRig rig, List<CreatureSculpt.Part> mirroredParts,
                           Map<String, BedrockAnimation> clips, long gameTime, int interval,
                           Motion motion) {
        if (rig == null || clips == null || clips.isEmpty()) {
            return 0;
        }
        return tick(rig, mirroredParts, clips, gameTime, interval, motion, null);
    }

    /**
     * <b>…with the head's look riding the SAME write </b> — the shipping driver's form.
     *
     * <p>The look is sampled on the pose schedule and composed into the clip frame, so the head and
     * the body are written on the same tick with the same tween and cannot swim against each other.
     * Between writes nothing is sent: a head turn waits at most {@code interval - 1} ticks, which is
     * the same staleness every other bone already accepts, and the client tweens it in.
     *
     * @param look {@code {yaw, pitch}} relative to the body, or null for no head-follow. A creature
     *             with NO clips still turns its head through here.
     */
    public static int tick(DisplayRig rig, List<CreatureSculpt.Part> mirroredParts,
                           Map<String, BedrockAnimation> clips, long gameTime, int interval,
                           Motion motion, float[] look) {
        if (rig == null) {
            return 0;
        }
        if (!writesOn(gameTime, interval)) {
            return 0;
        }
        LivingEntity base = rig.base();
        if (base == null) {
            return 0;
        }
        Map<String, BedrockAnimation.BonePose> pose = clips == null || clips.isEmpty()
                ? Map.of()
                : AnimationState.pose(clips,
                        selectionFor(motion != null ? motion : motionOf(base)));
        if (pose.isEmpty() && look == null && rig.lastPosed().isEmpty()) {
            return 0;
        }
        // The tween covers the gap until the next write PLUS one tick (RigCadence.poseEase): a
        // tween exactly as long as the gap stopped the limbs whenever a packet came a little late.
        return RigDriver.animate(rig, mirroredParts, pose, look, ease(interval));
    }

    /**
     * <b>…with the locomotion layers on top </b> — fly, swim, hop, sit,
     * lie and sleep, generated from the sculpt ({@link RigLocomotion}) and weighted by what the
     * carrier is doing ({@link RigGait}). The clip the selection rule picks is still the base: the
     * layers turn the bones they own on top of it, and the whole-body move rides the same write as
     * the clip and the look, so there is still ONE pose writer per tick.
     *
     * @param plan  the creature's locomotion plan, or null for none
     * @param ticks wall time in ticks, the clock the beat, the stroke and the breath run on
     */
    public static int tick(DisplayRig rig, List<CreatureSculpt.Part> mirroredParts,
                           Map<String, BedrockAnimation> clips, long gameTime, int interval,
                           Motion motion, float[] look, RigLocomotion.Plan plan,
                           RigLocomotion.Weights weights, float ticks) {
        if (plan == null) {
            return tick(rig, mirroredParts, clips, gameTime, interval, motion, look);
        }
        if (rig == null || !writesOn(gameTime, interval)) {
            return 0;
        }
        LivingEntity base = rig.base();
        if (base == null) {
            return 0;
        }
        Motion m = motion != null ? motion : motionOf(base);
        Map<String, BedrockAnimation.BonePose> clip = clips == null || clips.isEmpty()
                ? Map.of()
                : AnimationState.pose(clips, selectionFor(m));
        // The walk is generated from the legs it finds, on the walk clip's own clock: the
        // same limb speed and distance the selection rule just read, so the two cannot disagree
        // about whether, or where in its stride, the creature is walking.
        RigLocomotion.Out out = RigLocomotion.pose(plan, clip, weights, ticks,
                RigLocomotion.Stride.of(m.walkSpeed(), m.walkPosition()));
        float[] keptLook = look == null ? null
                : new float[]{look[0] * out.lookKeep(), look[1] * out.lookKeep()};
        if (out.pose().isEmpty() && out.root() == null && keptLook == null
                && rig.lastPosed().isEmpty()) {
            return 0;
        }
        return RigDriver.animate(rig, mirroredParts, out.pose(), keptLook, out.root(),
                ease(interval));
    }
}
