package com.moddingfromamod.vanilla;

/**
 * <b>How far the head is turned from the BODY — measured against the body, and never further than
 * the mob itself may turn it.</b>
 *
 * <p>The rig draws the body at one yaw and adds this to the head subtree, so the two must be read
 * against the same angle. They were not: the rig drew the body at {@code yRot} and turned the head
 * by {@code yHeadRot - yRot}, unclamped, while {@code RigDriver.follow} wrote
 * {@code yBodyRot = yRot} on the carrier every tick.
 *
 * <p>Why that reaches 180 degrees, read from vanilla's bytecode rather than assumed:
 * {@code LookControl.clampHeadRotationToBody} holds the head within {@code getMaxHeadYRot()} of
 * {@code yBodyRot} <b>only while the mob is pathing</b> ({@code if (!getNavigation().isDone())}).
 * Standing still, vanilla keeps them together from the other side — {@code BodyRotationControl}
 * turns the BODY towards the head. Overwriting {@code yBodyRot} with {@code yRot} (which nothing
 * updates on an idle mob) cancelled that every tick, so a carrier that watched the child walk round
 * it turned its head a full half turn against a body that never followed. On a "copy of me" whose
 * hair was not under the head, that put the back of the hair box over the face.
 *
 * <p>So: the body is {@code yBodyRot}, the head is relative to it, and the result is clamped to the
 * mob's own {@code getMaxHeadYRot()} — which is what the clamp in {@code RigDriver.lookAt}'s javadoc
 * always claimed. The clamp is a backstop, not the mechanism: with the body free to follow, vanilla
 * already keeps the two within it.
 *
 * <p>Pure and Minecraft-free, so the rule is pinned in Gate A.
 */
public final class RigHeadYaw {

    private RigHeadYaw() {
    }

    /**
     * @param bodyYawDeg the body's yaw ({@code yBodyRot}) — the one the rig draws the body at
     * @param headYawDeg the head's world yaw ({@code getYHeadRot()})
     * @param maxDeg     how far the mob may turn its head ({@code getMaxHeadYRot()}); a value
     *                   {@code <= 0} or {@code >= 180} means unclamped
     * @return the head yaw relative to the body, in {@code [-180, 180)}, clamped to {@code ±maxDeg}
     */
    public static float relative(float bodyYawDeg, float headYawDeg, float maxDeg) {
        float d = wrap(headYawDeg - bodyYawDeg);
        if (maxDeg > 0f && maxDeg < 180f) {
            d = Math.max(-maxDeg, Math.min(maxDeg, d));
        }
        return d;
    }

    /**
     * <b>How far the head's PITCH alone must move before it is worth rewriting the head</b>.
     *
     * <p>A yaw change always rewrites the head (exactly as before), and whatever the pitch is then
     * rides along for nothing. A pitch change on its own rewrites it only once it has moved this
     * far from the pitch last written. Measured, not guessed: an exact pitch read cost +20%
     * entity-data packets on eight walking creatures (the pitch toward a child drifts a fraction of
     * a degree nearly every tick, and each drift rewrote the whole head subtree).
     *
     * <p>The A/B seam is {@code headpitch}: {@code off} (pitch 0, the rig),
     * {@code exact} (every change writes), {@code step} (the default) with
     * {@code headpitchstep} degrees.
     */
    public static final String PITCH_MODE = System.getProperty(
            com.moddingfromamod.ModdingFromAModMod.MODID + ".headpitch", "step");

    public static final float PITCH_STEP = Float.parseFloat(System.getProperty(
            com.moddingfromamod.ModdingFromAModMod.MODID + ".headpitchstep", "2"));

    /**
     * The pitch to feed the rig, per {@link #PITCH_MODE}: 0 when pitch is switched off.
     *
     * <p>⚠ <b>Negated, and the sign was found on screen.</b> Vanilla's {@code xRot} is positive
     * looking DOWN; the rig's head channel ({@link RigTransform#headRotation}) tilts the other way
     * for a positive value. Fed {@code xRot} as it stands, a creature tilted its head AWAY from a
     * child above it (the crown to the camera) and back from one below: {@code GateC:vanillapitch}
     * photographed it. No film had ever seen a pitch, because {@code follow} zeroed
     * it, and the Gate A reference transcribes the old mod renderer's head step, which is right
     * about the chain and silent about which way a vanilla angle points.
     */
    public static float pitch(float xRotDeg) {
        return "off".equalsIgnoreCase(PITCH_MODE) ? 0f : -xRotDeg;
    }

    /**
     * Is this look different enough from the one last written to be worth a rewrite?
     *
     * @param step degrees the pitch alone must move; {@code <= 0} means any change
     */
    public static boolean worthWriting(float lastYaw, float lastPitch, float yaw, float pitch,
                                       float step) {
        if (Float.isNaN(lastYaw) || Float.isNaN(lastPitch)) {
            return true;
        }
        if (yaw != lastYaw) {
            return true;
        }
        if (step <= 0f) {
            return pitch != lastPitch;
        }
        return Math.abs(pitch - lastPitch) >= step;
    }

    /** The step {@link DisplayRig#headMoved} uses: 0 for {@code exact}, else {@link #PITCH_STEP}. */
    static float activeStep() {
        return "exact".equalsIgnoreCase(PITCH_MODE) ? 0f : PITCH_STEP;
    }

    /** Degrees into {@code [-180, 180)}, as {@code Mth.wrapDegrees} does. */
    static float wrap(float deg) {
        float d = deg % 360f;
        if (d >= 180f) {
            d -= 360f;
        }
        if (d < -180f) {
            d += 360f;
        }
        return d;
    }
}
