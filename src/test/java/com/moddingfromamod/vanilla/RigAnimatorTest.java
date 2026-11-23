package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.runtime.AnimationState;
import com.moddingfromamod.runtime.BedrockAnimation;
import org.junit.jupiter.api.Test;

/**
 * The scheduling half of driving a walking creature — the part that is pure and therefore cheap to
 * pin. What the pose LOOKS like is Gate C's job; this is about when a pose is written and which
 * clip the creature's own motion selects.
 */
class RigAnimatorTest {

    private static RigAnimator.Motion walking(float speed, float position) {
        return new RigAnimator.Motion(false, 0f, speed, position, 100f);
    }

    @Test
    void aStandingCreatureSelectsIdleAndAWalkingOneSelectsWalk() {
        // The threshold is AnimationState's, not this class's, and that is the point: two rules
        // that must agree are two rules that will not.
        assertEquals(BedrockAnimation.Clip.IDLE,
                RigAnimator.selectionFor(walking(0f, 0f)).clip());
        assertEquals(BedrockAnimation.Clip.IDLE,
                RigAnimator.selectionFor(walking(AnimationState.WALK_THRESHOLD, 3f)).clip());
        assertEquals(BedrockAnimation.Clip.WALK,
                RigAnimator.selectionFor(walking(AnimationState.WALK_THRESHOLD + 0.01f, 3f)).clip());
    }

    @Test
    void theWalkPhaseFollowsDISTANCEAndNotTheClock() {
        // A slow creature must not moonwalk: the phase comes from how far the limbs have swung,
        // so the same walkPosition gives the same frame whatever the speed or the tick count.
        AnimationState.Selection slow = RigAnimator.selectionFor(
                new RigAnimator.Motion(false, 0f, 0.05f, 12f, 3f));
        AnimationState.Selection fast = RigAnimator.selectionFor(
                new RigAnimator.Motion(false, 0f, 0.90f, 12f, 900f));
        assertEquals(BedrockAnimation.Clip.WALK, slow.clip());
        assertEquals(slow.seconds(), fast.seconds(), 1e-6,
                "the same distance travelled must be the same frame of the walk");
        assertNotEquals(slow.seconds(),
                RigAnimator.selectionFor(new RigAnimator.Motion(false, 0f, 0.05f, 24f, 3f)).seconds(),
                "twice the distance must be a different frame");
    }

    @Test
    void attackBeatsWalkBeatsIdle() {
        assertEquals(BedrockAnimation.Clip.ATTACK, RigAnimator.selectionFor(
                new RigAnimator.Motion(true, 0.4f, 0.8f, 5f, 10f)).clip());
    }

    @Test
    void aPoseIsWrittenOnceEveryIntervalAndNotBetween() {
        int written = 0;
        for (long t = 0; t < 60; t++) {
            if (RigAnimator.writesOn(t, RigAnimator.DEFAULT_INTERVAL)) {
                written++;
            }
        }
        assertEquals(60 / RigAnimator.DEFAULT_INTERVAL, written,
                "the schedule must be exactly one write per interval over three seconds");
    }

    @Test
    void everyCreatureWritesOnTheSAMETicks() {
        // Anchored on the game time rather than on a per-rig counter. With per-rig counters two
        // creatures spawned a tick apart write on alternating ticks, and twenty of them write on
        // every tick -- which is the whole traffic saving, gone, and invisible with one creature
        // on screen.
        for (long t = 0; t < 30; t++) {
            assertEquals(RigAnimator.writesOn(t, 3), RigAnimator.writesOn(t, 3));
        }
        long shared = 0;
        for (long t = 0; t < 30; t++) {
            if (RigAnimator.writesOn(t, 3)) {
                shared++;
            }
        }
        assertEquals(10, shared);
    }

    @Test
    void anIntervalOfOneMeansEveryTick() {
        for (long t = 0; t < 10; t++) {
            assertTrue(RigAnimator.writesOn(t, 1));
            assertTrue(RigAnimator.writesOn(t, 0));
        }
    }
}
