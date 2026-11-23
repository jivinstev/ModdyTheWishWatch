package com.moddingfromamod.vanilla;

/**
 * <b>Vanilla's death curve, copied rather than eased by hand.</b>
 *
 * <p>Minecraft-free, so the shape of the fall is a unit test rather than something to squint at in
 * a client. Straight out of {@code LivingEntityRenderer.setupRotations}:
 *
 * <pre>
 *   float f = ((float) deathTime + partialTick - 1.0F) / 20.0F * 1.6F;
 *   f = Mth.sqrt(f);
 *   if (f &gt; 1.0F) f = 1.0F;
 *   poseStack.mulPose(Axis.ZP.rotationDegrees(f * getFlipDegrees()));   // 90 for almost everything
 * </pre>
 *
 * <p>Two details that a hand-rolled ease would get wrong, and both are visible:
 *
 * <ul>
 *   <li>the {@code sqrt} makes it <b>fast at the start and slow at the end</b> — a body giving way,
 *       not a door closing. A linear or ease-in-out fall reads as a puppet being lowered;</li>
 *   <li>{@code · 1.6} then a clamp means it reaches flat at {@code deathTime ≈ 13.5}, not at 20 —
 *       so the creature lies still for the last third of the animation before it is removed. Miss
 *       that and the body is still turning at the instant it vanishes.</li>
 * </ul>
 *
 * <p>The pivot is the entity's own origin, which for this rig is the carrier's feet: every display
 * sits AT the base position and carries its offset in the transform, so "rotate the whole creature
 * about its feet" is a rotation about the display origin and needs no pivot arithmetic at all.
 */
public final class RigFlop {

    private RigFlop() {
    }

    /** Vanilla's {@code getFlipDegrees()} default. Spider and a few others override it. */
    public static final float FLIP_DEGREES = 90f;

    /** {@code deathTime} at which a vanilla mob is removed. */
    public static final int DEATH_TICKS = 20;

    /** The 1.6 in vanilla's expression — why the body is flat well before it despawns. */
    public static final float RATE = 1.6f;

    /** How far over the body has fallen, in degrees, at this point in the death. */
    public static float degrees(int deathTime, float partialTick) {
        if (deathTime <= 0) {
            return 0f;
        }
        float f = (deathTime + partialTick - 1.0f) / 20.0f * RATE;
        f = f <= 0 ? 0 : (float) Math.sqrt(f);
        return Math.min(1.0f, f) * FLIP_DEGREES;
    }

    /**
     * <b>Where a flop written now aims </b>: vanilla's angle {@code ease} ticks on, because the client takes
     * that long to ease into it. A write aimed at NOW would draw the fall a whole ease behind a vanilla mob's.
     */
    public static float aimed(int deathTime, int ease) {
        return deathTime <= 0 ? 0f : degrees(deathTime + Math.max(0, ease), 0f);
    }

    /** The first {@code deathTime} at which the body is fully over — 14 for vanilla's numbers. */
    public static int flatAt() {
        for (int t = 1; t <= DEATH_TICKS; t++) {
            if (degrees(t, 0f) >= FLIP_DEGREES) {
                return t;
            }
        }
        return DEATH_TICKS;
    }
}
