package com.moddingfromamod.marshal;

import com.moddingfromamod.ModdingFromAModMod;

/**
 * How many wishes may be granted <b>at the same time</b> — and the A/B seam that makes a concurrency
 * bug provable.
 *
 * <p>Default <b>10</b>, ceiling <b>10</b> — D10 (a setting).
 * The paragraphs below are the history of the 1 → 3 step and are kept because the reasons still
 * hold; the one they no longer support is "four is the ceiling because parked workers poll". Since
 * {@code PoolPlan} keeps ONE spare worker and starts another only when a wish takes the last
 * free one, so the cap is how many can be busy at once, never how many sit idle. One child is still
 * held to three at a time by D10's other number, {@code queue.perChildConcurrent}; ten only matters
 * with four or more children wishing together.
 *
 * <p>It was 3 before D10. It was 1 for the whole of development — deliberately, so that every gate
 * written before this class existed stayed green and nothing a child saw changed until the
 * concurrency was proven. It then stayed 1 after it WAS proven, which meant every parallel
 * measurement came from a harness passing {@code wishslots=2} and real play was always serial. Steve
 * made three wishes at once, watched them come out one after another, and asked why. That is the
 * whole reason for the change.
 *
 * <p><b>Three, rather than two or four.</b> Two is enough for the average arrival rate (a wish takes
 * p50 117s and one arrives every 66s), but the average is not what a child does — he fires several
 * in one breath, and three is what he actually did. Cold start says the extra workers are close to
 * free: measured at 9s for the first worker to park on {@code await_wish} and 10s for the second, so
 * a slot costs about a second rather than another full boot. Four remains the ceiling, and remains
 * a deliberate step rather than the default, because each parked session adds its own idle
 * {@code await_wish} polling.
 *
 * <p><b>Why a flag rather than a constant.</b> Concurrency is the one area where a green suite lies
 * most easily: a race that fires one run in twenty reads as flakiness. With the number injectable,
 * any new failure can be pinned — it must go <b>red at 3 and green at 1</b>, or it is an ordinary
 * bug wearing a concurrency costume. That is the same shape as the {@code GIGAMUTE} A/B this family
 * of repos already uses for sound suppression.
 *
 * <p>Set it with {@code -Ptestprops="wishslots=3"} on a client or GameTest run, or
 * {@code -Dmoddingfromamod.wishslots=3} directly. Tests that need to flip it mid-JVM use
 * {@link #override(Integer)} in a try/finally — the property is read on every call rather than
 * cached, so an override takes effect immediately and cannot leak a stale value into the next test.
 */
public final class WishSlots {

    /** {@code -Dmoddingfromamod.wishslots=N} (or {@code -Ptestprops="wishslots=N"}). */
    public static final String PROPERTY = ModdingFromAModMod.MODID + ".wishslots";

    /** D10's ten workers. Idle cost is one spare whatever this says ({@code PoolPlan.MIN_SPARE}). */
    public static final int DEFAULT = 10;

    /**
     * Ceiling on the pool, and the {@code pool.slots} knob's upper bound (the catalogue reads this
     * constant, so the two cannot disagree again). Ten, because D10 decided ten; it was four while
     * every worker was started at world open and parked, which removed.
     */
    public static final int MAX = 10;

    private static volatile Integer override;

    private WishSlots() {
    }

    /**
     * {@code -Dmoddingfromamod.wishslots.cap=N}: a CEILING on whatever {@link #slots()} would say,
     * the knob included. A world's manifest may ask for 10 workers, and 10
     * workers (~3.8 GB) fit beside its server on a small machine only once the wish camera is off it.
     * So while a camera runs on the same machine, the server's launcher passes a lower
     * number here and the pool stops at it — and a runtime {@code world.set} of 10 cannot lift it,
     * because the knob is capped too. When the camera moves off, the launcher stops passing it and the
     * next boot runs the full 10. Unset: no ceiling.
     */
    public static final String CAP_PROPERTY = PROPERTY + ".cap";

    /** How many wishes may be in flight right now. Always in {@code [1, MAX]}, and never above the cap. */
    public static int slots() {
        Integer forced = override;
        if (forced != null) {
            return clamp(forced);
        }
        return Math.min(uncapped(), cap(System.getProperty(CAP_PROPERTY)));
    }

    /**
     * The ceiling from {@link #CAP_PROPERTY}'s raw text: {@link #MAX} (no ceiling) when it is unset or
     * not a whole number, else that number clamped to {@code [1, MAX]}. Pure.
     */
    static int cap(String raw) {
        if (raw == null || raw.isBlank()) {
            return MAX;
        }
        try {
            return clamp(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return MAX;
        }
    }

    private static int uncapped() {
        // The operator's runtime knob beats the boot property. The catalogue bounds it to
        // [1, MAX] with this class's own constant; the clamp is the same belt the property gets.
        var knob = com.moddingfromamod.config.ModdySettings.get("pool.slots");
        if (knob.isPresent()) {
            return clamp(Integer.parseInt(knob.get()));
        }
        String raw = System.getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        try {
            return clamp(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            // A typo'd flag must not silently become "concurrency off" in a run whose whole point is
            // concurrency, nor silently become 4. Fall back to the documented default, loudly enough
            // to find in a log.
            return DEFAULT;
        }
    }

    /** True when this run is the serial control arm — useful for a test to say which arm it is in. */
    public static boolean serial() {
        return slots() <= 1;
    }

    /**
     * Force a value for the current JVM; {@code null} restores the property/default.
     *
     * <p>Deliberately public and named for what it does rather than hidden behind a package-private
     * back door: a seam nobody can find gets reinvented, worse, in every test that needs it.
     */
    public static void override(Integer value) {
        override = value == null ? null : clamp(value);
    }

    private static int clamp(int n) {
        return Math.max(1, Math.min(MAX, n));
    }
}
