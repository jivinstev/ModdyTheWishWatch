package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.moddingfromamod.runtime.BedrockAnimation;
import org.junit.jupiter.api.Test;

/** Gate A 's rig half: the motion carries the talking flag, and rig.look_at's angles. */
class RigTalkFacingTest {

    @Test
    void theRigsMotionCarriesTheTalkFlagIntoTheSharedRule() {
        var talking = new RigAnimator.Motion(false, 0f, 0f, 0f, 50f, true, 0.4f);
        var sel = RigAnimator.selectionFor(talking);
        assertEquals(BedrockAnimation.Clip.TALK, sel.clip());
        assertEquals(0.4f, sel.seconds(), 1e-6);
        // The five-argument motion every older caller builds does not talk.
        assertEquals(BedrockAnimation.Clip.IDLE,
                RigAnimator.selectionFor(new RigAnimator.Motion(false, 0f, 0f, 0f, 50f)).clip());
    }

    @Test
    void facingYawIsMinecraftsYaw() {
        // Minecraft: yaw 0 looks along +Z, 90 along -X, -90 (= 270) along +X, 180 along -Z.
        assertEquals(0f, RigFacing.yawTo(0, 1), 1e-4);
        assertEquals(90f, RigFacing.yawTo(-1, 0), 1e-4);
        assertEquals(-90f, RigFacing.yawTo(1, 0), 1e-4);
        assertEquals(180f, Math.abs(RigHeadYaw.wrap(RigFacing.yawTo(0, -1))), 1e-4);
    }

    @Test
    void facingPitchIsNegativeLookingUp() {
        assertEquals(0f, RigFacing.pitchTo(3, 0, 4), 1e-4);
        assertEquals(-45f, RigFacing.pitchTo(1, 1, 0), 1e-4);
        assertEquals(45f, RigFacing.pitchTo(0, -2, 2), 1e-4);
    }
}
