package com.moddingfromamod.event.shape;

/**
 * <b>A hurricane's weather, for the players near it only</b> ("broad but localized"). Pure.
 *
 * <p>The first cut set the server's real weather, which rains on every island at once: a shared world's rules can say
 * "the weather is the same for every island", and that call went round the check that refuses {@code /weather}. Now
 * the server's weather is never touched; each player near the storm is SENT rain and thunder, full under the storm,
 * fading out to nothing at {@link #FADE_OUT} times its reach, so walking toward it the sky darkens and walking away it
 * clears. The levels move a little a tick, as vanilla's own do, so it never snaps.
 */
public final class VortexWeather {

    private VortexWeather() {
    }

    /** Full rain and thunder out to this many times the storm's reach. */
    public static final double FULL = 1.3;
    /** Nothing past this many times its reach. */
    public static final double FADE_OUT = 2.5;
    /** How far a level moves in one tick (vanilla's rain moves 0.01; this is a storm arriving). */
    public static final float STEP = 0.04f;

    /** The level (0..1) a player {@code distance} from the storm's middle should have, for a storm of {@code reach}. */
    public static float target(double distance, double reach) {
        double full = reach * FULL;
        double none = reach * FADE_OUT;
        if (distance <= full) {
            return 1f;
        }
        if (distance >= none) {
            return 0f;
        }
        return (float) (1 - (distance - full) / (none - full));
    }

    /** One tick from {@code now} toward {@code target}. */
    public static float step(float now, float target) {
        return now < target ? Math.min(target, now + STEP) : Math.max(target, now - STEP);
    }
}
