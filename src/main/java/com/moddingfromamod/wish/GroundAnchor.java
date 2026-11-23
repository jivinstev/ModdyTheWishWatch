package com.moddingfromamod.wish;

/**
 * <b>Where a wish lands when nobody said "in the sky".</b>
 *
 * <p>{@code conjure_at} locks the spot a wish manifests at, and every build tool anchors to it. It
 * used to take the child's own Y verbatim — so the anchor was wherever his <i>feet</i> were.
 *
 * <p>A seven-year-old in Creative spends most of his time flying. Live 2026-08-14 he asked for a
 * skyscraper while airborne over the factory he had just been given, and the wish locked to
 * {@code -1770,26,1022} — ground there is about y=4. Dad: <i>"the skyscraper looked good. it was
 * hovering above the ground."</i>
 *
 * <p>{@code up} already exists as an explicit parameter for the sky case, which means the sky is
 * meant to be <b>asked for</b>. Defaulting to the child's altitude made {@code up: 0} mean "however
 * high he happens to be", leaving no way to say "on the ground" at all.
 *
 * <h2>The rule, and the reason it only ever goes DOWN</h2>
 *
 * <p>Settling is one-directional. Raising an anchor to the surface would take a wish made in a cave,
 * a mineshaft or a basement and shove it up through the roof into the daylight — the child is
 * standing right there, and the whole point of the locked spot is that the thing appears where he is
 * looking. Falling to the surface fixes a wish that would float; rising to it breaks one that was
 * fine.
 *
 * <p>Pure and Minecraft-free so the rule can be tested exhaustively without a world.
 */
public final class GroundAnchor {

    private GroundAnchor() {
    }

    /**
     * <b>The Y a wish should manifest at.</b>
     *
     * @param aimedY        the Y the anchor would otherwise use — today, the child's own feet
     * @param surfaceY      the height of the world at that (x, z): the Y something would stand on
     * @param askedForSky   true when the agent explicitly passed {@code up}, which is a deliberate
     *                      request for altitude and is never overridden
     * @return the Y to lock the wish to
     */
    public static int settle(int aimedY, int surfaceY, boolean askedForSky) {
        if (askedForSky) {
            // "up: 40 for something in the sky" is the agent saying it means this. Honour it.
            return aimedY;
        }
        if (surfaceY >= aimedY) {
            // Already on (or under) the ground — he is standing on it, or he is in a cave and the
            // surface is somewhere over his head. Either way, do not move him.
            return aimedY;
        }
        return surfaceY;
    }

    /**
     * Whether {@link #settle} would actually move the wish — so the tool can tell the agent, rather
     * than silently relocating what it just promised the child.
     */
    public static boolean wouldSettle(int aimedY, int surfaceY, boolean askedForSky) {
        return settle(aimedY, surfaceY, askedForSky) != aimedY;
    }
}
