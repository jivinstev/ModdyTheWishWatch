package com.moddingfromamod.config;

/**
 * <b>Is Magician mode on?</b> (D5, item 4) — the one question every AI program asks before it starts.
 *
 * <p>Off (the public default, and a dedicated server whose file does not turn it on): <b>no wish worker, no CLI
 * probe and no model call</b> — {@code AgentSession} starts no worker, {@code ClaudeCli.resolve} looks for no
 * {@code claude}, {@code MuseExec} finds no {@code muse}, and the safety grader grades with the lexicon alone. A
 * single-player world used to start idle {@code claude --bg} workers whenever a signed-in CLI was on the machine
 * (PORT_26_2.md). On: the host turned it on ({@code /moddy setting magician.enabled on}, the Magician setup in
 *), the server's file did and its checks passed, or a settings override (a plug-in) does.
 *
 * <p>{@code -Dmoddingfromamod.agent=on} still forces the wish pool for a developer's own run (Gate C½); it never
 * reaches a player.
 */
public final class Magician {

    private Magician() {
    }

    /** How many setup probes or checks are running ({@link #during}). */
    private static final java.util.concurrent.atomic.AtomicInteger CHECKS = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Is the host's Magician setup checking right now? The program check and the end-to-end check must start
     * an AI program before Magician is on: that is what they are for. Only the host's own press (or a dedicated
     * server's start-up check, D28) opens this door, and only while that check runs.
     */
    public static boolean checking() {
        return CHECKS.get() > 0;
    }

    /** Run {@code body} with the AI doors open for the setup's check, and shut them after, whatever happens. */
    public static <T> T during(java.util.function.Supplier<T> body) {
        CHECKS.incrementAndGet();
        try {
            return body.get();
        } finally {
            CHECKS.decrementAndGet();
        }
    }

    /** Open the doors for a check that spans ticks (the end-to-end check); pair with {@link #endCheck}. */
    public static void beginCheck() {
        CHECKS.incrementAndGet();
    }

    public static void endCheck() {
        CHECKS.updateAndGet(n -> Math.max(0, n - 1));
    }

    public static boolean enabled() {
        return "on".equals(ModdySettings.get(SettingsCatalog.MAGICIAN).orElse("off"));
    }

    /**
     * May an AI program start at all — a worker, a CLI probe, a model call? Magician on, or a developer forcing the
     * pool with {@code -Dmoddingfromamod.agent=on}. Every place that starts one asks this first.
     */
    public static boolean allowsAi() {
        if (enabled() || checking()) {
            return true;
        }
        String forced = System.getProperty(com.moddingfromamod.ModdingFromAModMod.MODID + ".agent", "");
        return forced.equalsIgnoreCase("on") || forced.equalsIgnoreCase("true");
    }
}
