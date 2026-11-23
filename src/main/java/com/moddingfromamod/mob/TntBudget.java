package com.moddingfromamod.mob;

/**
 * <b>How much LIT TNT one level may hold at once</b> — the pure half of {@link PrimedTntLimit}.
 *
 * <p>A lit TNT block is a {@code PrimedTnt} entity; each one that explodes lights the TNT around
 * it. Tens of thousands going off together made one server tick run past 60 s on a live server
 * (2026-09-27), Minecraft's watchdog stopped the server, the lit TNT was SAVED with the world, and
 * it went off again on every boot: three crashes in six minutes, a child thrown out each time.
 *
 * <p>Past {@link #MAX_LIVE} the next lit TNT simply does not appear (its block is already gone, so
 * the crater is a little smaller than it would have been). A chain of a few thousand still makes a
 * huge bang, just spread over more ticks; the server stays up and the next boot has no bomb in it.
 */
public final class TntBudget {

    /**
     * At most this many lit TNT in one level at once. MEASURED (2026-09-27, a 4-vCPU Xeon @ 2.1 GHz,
     * solid TNT cubes lit at the centre, worst single tick): uncapped, 1,000 → 0.32 s, 4,096 → 0.59 s,
     * 8,000 → 1.3 s, 15,625 → 3.8 s with 27 ticks over a second and 58 s of lag; and a solid pile lights
     * nearly all of itself at once, so the cap, not the pile, is what a tick pays for. At 2,048 even
     * the 46,656-TNT volcano that crash-looped a live server peaks at a 0.72 s tick and is over in 8.8 s —
     * ~80x under the 60 s watchdog. The first cut (256) was a guess, and it made ANY solid pile a
     * ~270-TNT bang, the rest vanishing.
     */
    public static final int MAX_LIVE = 2048;

    private TntBudget() {
    }

    /** May one more lit TNT join a level that already holds {@code live} of them? Pure. */
    public static boolean admits(int live) {
        return live < MAX_LIVE;
    }

    /** Say it at most once a second, not once per dropped block: a chain drops thousands. Pure. */
    public static boolean worthSaying(long nowMs, long lastSaidMs) {
        return nowMs - lastSaidMs >= 1000;
    }
}
