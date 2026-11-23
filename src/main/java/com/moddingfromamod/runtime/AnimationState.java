package com.moddingfromamod.runtime;

import java.util.Map;

/**
 * Decides <b>which clip a creature is playing right now, and how far into it</b> — the little state
 * machine between "the entity is doing something" and "sample these keyframes".
 *
 * <p>Split out from the renderer and kept Minecraft-free so the rules (attack beats walk beats idle;
 * a missing clip falls back instead of freezing the creature; the walk cycle is driven by distance
 * travelled rather than wall-clock so a slow creature doesn't moonwalk) are unit-tested rather than
 * eyeballed one frame at a time.
 */
public final class AnimationState {

    private AnimationState() {
    }

    /** Below this the creature is standing still, whatever tiny drift the physics reports. */
    public static final float WALK_THRESHOLD = 0.015f;

    /**
     * Converts vanilla's distance-based "limb swing" into clip seconds. Tuned so a normal walking
     * creature takes roughly one clip cycle per two steps; larger = faster leg swing.
     */
    public static final float WALK_CYCLE_SCALE = 0.15f;

    /**
     * What to play this frame.
     *
     * @param idleSeconds where the idle clip would be now: what a {@code TALK} selection plays when
     *                    the species ships no talk clip, so falling back does not restart the idle
     */
    public record Selection(BedrockAnimation.Clip clip, float seconds, float idleSeconds) {

        public Selection(BedrockAnimation.Clip clip, float seconds) {
            this(clip, seconds, seconds);
        }
    }

    /**
     * Pick the clip and time.
     *
     * @param attacking      the creature is mid-swing
     * @param attackProgress 0→1 through the swing (vanilla's attack animation progress)
     * @param walkSpeed      how fast the limbs are swinging (0 when standing)
     * @param walkPosition   accumulated limb swing — advances with distance travelled
     * @param ageSeconds     the creature's age, for the idle clip
     */
    public static Selection select(boolean attacking, float attackProgress,
                                   float walkSpeed, float walkPosition, float ageSeconds) {
        return select(attacking, attackProgress, walkSpeed, walkPosition, ageSeconds, false, 0f);
    }

    /**
     * Pick the clip and time, with the server's talking flag.
     *
     * <p>The order is attack, talk, walk, idle. Talk is above walk because it is an op's explicit
     * ask (the media timeline says "he is speaking now"), while walking may be a wander nobody
     * asked for; the rig's generated stride still moves the legs of a creature that talks while it
     * walks ({@code RigLocomotion.Stride}). Attack stays on top: a hit lands whatever he is saying.
     *
     * @param talking     the carrier carries the talking flag
     * @param talkSeconds seconds since the flag went on, so the talk clip starts at its first frame
     */
    public static Selection select(boolean attacking, float attackProgress,
                                   float walkSpeed, float walkPosition, float ageSeconds,
                                   boolean talking, float talkSeconds) {
        if (attacking) {
            // Drive the attack clip by the swing's own progress so the chomp lands with the hit.
            return new Selection(BedrockAnimation.Clip.ATTACK, Math.max(0f, attackProgress));
        }
        if (talking) {
            return new Selection(BedrockAnimation.Clip.TALK, Math.max(0f, talkSeconds),
                    Math.max(0f, ageSeconds));
        }
        if (walkSpeed > WALK_THRESHOLD) {
            return new Selection(BedrockAnimation.Clip.WALK, walkPosition * WALK_CYCLE_SCALE);
        }
        return new Selection(BedrockAnimation.Clip.IDLE, Math.max(0f, ageSeconds));
    }

    /**
     * Resolve a selection against the clips a model actually ships, falling back rather than
     * freezing: a creature with only an idle still breathes while it walks, and one with no clips at
     * all simply stands (the caller then draws its rest pose).
     *
     * <p>The attack clip is special-cased: its {@code seconds} arrives as 0→1 progress, so it's
     * scaled to the real clip length here — that's the only place the clip's own duration matters.
     */
    public static Map<String, BedrockAnimation.BonePose> pose(
            Map<String, BedrockAnimation> clips, Selection selection) {
        if (clips == null || clips.isEmpty() || selection == null) {
            return Map.of();
        }
        BedrockAnimation chosen = BedrockAnimation.find(clips, selection.clip());
        float seconds = selection.seconds();
        if (chosen != null && selection.clip() == BedrockAnimation.Clip.ATTACK) {
            seconds = Math.min(1f, seconds) * chosen.length();
        }
        if (chosen == null) {
            // Fall back down the chain: attack -> walk -> idle; talk -> idle.
            if (selection.clip() == BedrockAnimation.Clip.ATTACK) {
                chosen = BedrockAnimation.find(clips, BedrockAnimation.Clip.WALK);
            }
            if (chosen == null) {
                chosen = BedrockAnimation.find(clips, BedrockAnimation.Clip.IDLE);
            }
            if (chosen == null) {
                return Map.of();
            }
            // No talk clip: he idles, at the idle's own time.
            if (selection.clip() == BedrockAnimation.Clip.TALK) {
                seconds = selection.idleSeconds();
            }
            // A fallback clip is a loop; drive it by its own time base, not the attack's 0→1.
            if (selection.clip() == BedrockAnimation.Clip.ATTACK) {
                seconds = selection.seconds() * chosen.length();
            }
        }
        return chosen.pose(seconds);
    }
}
