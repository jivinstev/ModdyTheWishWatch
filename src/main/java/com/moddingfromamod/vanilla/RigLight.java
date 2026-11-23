package com.moddingfromamod.vanilla;

/**
 * <b>The light a rig is drawn in: the light a vanilla mob in the carrier's place would be drawn
 * in</b>. Pure, so the rule is unit-tested without the game; {@code DisplayRig.followLight}
 * reads the three inputs off the carrier and writes every display's {@code brightness} override
 * only when this value CHANGES.
 *
 * <p><b>The vanilla rule, from the 1.21.4 bytecode</b> ({@code EntityRenderer.getPackedLightCoords}):
 * a mob is lit ONCE, at {@code BlockPos.containing(entity.getLightProbePosition(t))} — its eye —
 * with {@code getBlockLightLevel} = {@code isOnFire() ? 15 : level.getBrightness(BLOCK, pos)} and
 * {@code getSkyLightLevel} = {@code level.getBrightness(SKY, pos)}. The sky value is the stored
 * light level, not the time of day: nightfall is applied by the client's lightmap, so a rig needs
 * no write at dusk (measured: day → night is 0 writes) and darkens exactly as the mob does.
 *
 * <p><b>What it replaced.</b> {@code DisplayRig.matchLight} wrote that override once, at SPAWN, and
 * never again. A creature that appeared in a dark room stayed black after the room was lit (an earlier issue's
 * boss on his throne, lanterns added after), and one that appeared in the sun stayed sunlit in a
 * cave. Each display is never lit where IT stands — every one carries the override — so the issue's
 * first reading ("a display inside the throne takes its light from the block") was not the cause;
 * Gate C {@code vanillalight} measured a rig seated on a throne in an already-lit room at parity.
 *
 * @param block block light 0..15 (15 while the carrier burns, as vanilla draws a burning mob)
 * @param sky   sky light 0..15, the stored level
 */
public record RigLight(int block, int sky) {

    /** The brightest a light level gets. */
    public static final int MAX = 15;

    /**
     * {@code -Ptestprops="riglight=off"}: the A/B seam. Off, a rig keeps the light it was SPAWNED
     * in, which is what shipped — for the film and the cost probe, not a switch to
     * ship.
     */
    public static final boolean FOLLOW =
            !"off".equalsIgnoreCase(System.getProperty("moddingfromamod.riglight", "on"));

    /**
     * Vanilla's rule for a mob with this light at its eye.
     *
     * @param block  {@code level.getBrightness(BLOCK, eye)}
     * @param sky    {@code level.getBrightness(SKY, eye)}
     * @param onFire the carrier's {@code isOnFire()}
     */
    public static RigLight of(int block, int sky, boolean onFire) {
        return new RigLight(onFire ? MAX : clamp(block), clamp(sky));
    }

    /**
     * What to write now, or null for nothing. {@code shown} is what the displays carry (null before
     * this rig's first call, which always writes: an adopted rig's saved override may be stale).
     */
    public static RigLight next(RigLight shown, RigLight now) {
        return now.equals(shown) ? null : now;
    }

    /**
     * Whether to look at the light this tick. On the animator's own write ticks, so a change rides
     * the SAME {@code SetEntityData} packet as the pose a moving bone already sends — one packet
     * per display per tick carries every dirty field — instead of a packet of its own the next tick.
     * The light a child sees is at most {@code interval - 1} ticks late, 100 ms at the shipped 3.
     */
    public static boolean sampleOn(long gameTime, int interval) {
        return RigAnimator.writesOn(gameTime, interval);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(MAX, v));
    }
}
