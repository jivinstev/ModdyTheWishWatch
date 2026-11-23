package com.moddingfromamod.vanilla;

import java.nio.file.Files;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b> — the head yaw is measured against the BODY and clamped to the mob's own limit.</b>
 *
 * <p>The live carrier's {@code yRot} is stale whenever it stands still, and the rig used to both
 * draw the body at it and pin {@code yBodyRot} to it, so a head that watched the child walk round
 * turned a full half turn against a body that never followed. See {@link RigHeadYaw}.
 */
class RigHeadYawTest {

    /** Mob.getMaxHeadYRot(), read from the 1.21.4 bytecode ({@code bipush 75}). */
    private static final float VANILLA_MAX = 75f;

    @Test
    @DisplayName("a head looking straight behind a body is clamped to the mob's own limit")
    void aHalfTurnIsClamped() {
        float d = RigHeadYaw.relative(0f, 180f, VANILLA_MAX);
        assertEquals(VANILLA_MAX, Math.abs(d), 1e-4f,
                "a 180-degree request must come out at the mob's own " + VANILLA_MAX + ", was " + d);
        assertEquals(VANILLA_MAX, RigHeadYaw.relative(0f, 120f, VANILLA_MAX), 1e-4f);
        assertEquals(-VANILLA_MAX, RigHeadYaw.relative(0f, -120f, VANILLA_MAX), 1e-4f);
    }

    @Test
    @DisplayName("inside the limit the angle is exactly head minus body, wrapped")
    void insideTheLimitItIsTheDifference() {
        assertEquals(35f, RigHeadYaw.relative(0f, 35f, VANILLA_MAX), 1e-4f);
        assertEquals(20f, RigHeadYaw.relative(350f, 10f, VANILLA_MAX), 1e-4f);
        assertEquals(-20f, RigHeadYaw.relative(10f, 350f, VANILLA_MAX), 1e-4f);
        assertEquals(-30f, RigHeadYaw.relative(-720f, -30f, VANILLA_MAX), 1e-4f);
        // The body having turned is what makes a large world head yaw small again: this is the
        // case a pinned yBodyRot turned into a half turn.
        assertEquals(10f, RigHeadYaw.relative(170f, 180f, VANILLA_MAX), 1e-4f);
    }

    @Test
    @DisplayName("a limit of 0 or >=180 means unclamped")
    void noLimit() {
        assertEquals(120f, RigHeadYaw.relative(0f, 120f, 0f), 1e-4f);
        assertEquals(120f, RigHeadYaw.relative(0f, 120f, 180f), 1e-4f);
    }

    /**
     * The driver reads it this way — a pure rule the tick loop does not call is not a fix. Read as
     * source because the tick loop needs a live Mob (and the GameTests drive it for real).
     */
    @Test
    @DisplayName("VanillaRigAnimator draws the body at yBodyRot and turns the head relative to it")
    void theDriverUsesIt() throws Exception {
        String src = Files.readString(SpikeFixture.find(
                "src/main/java/com/moddingfromamod/vanilla/VanillaRigAnimator.java"));
        assertTrue(src.contains("RigHeadYaw.relative("),
                "driveOne must compute the head yaw through RigHeadYaw.relative");
        assertTrue(src.contains("base.yBodyRot"),
                "driveOne must read the BODY yaw -- yRot is stale on a standing mob");
        assertFalse(src.contains("getYHeadRot() - base.getYRot()"),
                "the old read (head against the stale yRot, unclamped) is back");
    }

    @Test
    @DisplayName("#833: a yaw change always writes; a pitch drift alone only once it is a visible step")
    void pitchAloneWritesOnlyOnAStep() {
        // Yaw moved: write, whatever the pitch did.
        assertTrue(RigHeadYaw.worthWriting(10f, -5f, 10.01f, -5f, 2f));
        // Pitch drifted a fraction of a degree, yaw held (vanilla holds a walking mob's head at
        // exactly ±75°): no write. This is the case that cost +20% packets.
        assertFalse(RigHeadYaw.worthWriting(75f, -5f, 75f, -5.4f, 2f));
        assertFalse(RigHeadYaw.worthWriting(75f, -5f, 75f, -6.99f, 2f));
        // Pitch moved a visible step: write.
        assertTrue(RigHeadYaw.worthWriting(75f, -5f, 75f, -7f, 2f));
        assertTrue(RigHeadYaw.worthWriting(75f, -5f, 75f, -2.5f, 2f));
        // Nothing moved: nothing written.
        assertFalse(RigHeadYaw.worthWriting(75f, -5f, 75f, -5f, 2f));
        // Nothing written yet: always write.
        assertTrue(RigHeadYaw.worthWriting(Float.NaN, Float.NaN, 0f, -3f, 2f));
        // step 0 is "exact": any pitch change writes.
        assertTrue(RigHeadYaw.worthWriting(75f, -5f, 75f, -5.01f, 0f));
    }

    @Test
    @DisplayName("#833: the driver reads the pitch BEFORE follow, which zeroes it")
    void pitchIsReadBeforeFollow() throws Exception {
        String src = Files.readString(SpikeFixture.find(
                "src/main/java/com/moddingfromamod/vanilla/VanillaRigAnimator.java"));
        int driveOne = src.indexOf("private static void driveOne");
        int pitch = src.indexOf("RigHeadYaw.pitch(base.getXRot())", driveOne);
        int follow = src.indexOf("RigDriver.follow(rig,", driveOne);
        assertTrue(pitch > 0 && pitch < follow,
                "the pitch must be read before follow moves the carrier with xRot 0");
        assertFalse(src.substring(follow).contains("base.getXRot()"),
                "nothing after follow may read the carrier's pitch -- it is always 0 there");
    }
}
