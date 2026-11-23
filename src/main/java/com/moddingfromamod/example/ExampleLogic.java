package com.moddingfromamod.example;

/**
 * <b>The pure-helper pattern — the most important convention in this template.</b>
 *
 * <p>Minecraft classes can't be instantiated without booting the game, so any gameplay logic written
 * <i>inside</i> an entity/block/item class can only be tested by launching Minecraft (slow, awkward,
 * often skipped). The fix: keep the <b>decisions</b> in small classes like this one that import
 * <b>nothing from Minecraft</b>, and let the Minecraft-facing code just execute those decisions.
 *
 * <p>Then the interesting logic runs in milliseconds under plain JUnit ({@code ./gradlew test}),
 * and the game code stays a thin shell that's hard to get wrong.
 *
 * <p>Good candidates to pull out here: damage/health/scaling math, loot or spawn weighting, state
 * machines (phases of a fight, multi-step rituals), geometry/shape planning, scoring and sorting,
 * cooldown/timing rules, parsing and validation.
 *
 * <p>This example is deliberately trivial — replace it with your own real logic.
 */
public final class ExampleLogic {

    private ExampleLogic() {
    }

    /**
     * A tiny stand-in for real gameplay math: how many mobs a "wave" should spawn, given how strong
     * each one is and the wave's budget — weaker mobs come in bigger packs, and the result is always
     * at least one and never more than {@code maxPack}.
     *
     * @param strength per-mob strength (any positive scale; &le; 0 is treated as 1)
     * @param budget   total strength this wave may spend (&le; 0 → a single mob)
     * @param maxPack  hard cap on pack size (&lt; 1 → 1)
     * @return the pack size, clamped to {@code [1, maxPack]}
     */
    public static int packSize(double strength, double budget, int maxPack) {
        int cap = Math.max(1, maxPack);
        if (budget <= 0) {
            return 1;
        }
        double s = strength <= 0 ? 1.0 : strength;
        int n = (int) Math.floor(budget / s);
        return Math.max(1, Math.min(cap, n));
    }
}
