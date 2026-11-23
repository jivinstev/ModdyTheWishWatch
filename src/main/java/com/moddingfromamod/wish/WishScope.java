package com.moddingfromamod.wish;

/**
 * <b>Which wish is this state about?</b>,.
 *
 * <p>{@link BuiltAbility}, {@link WishClaims} and {@code MachineHealth} used to be ONE global each,
 * wiped by {@code BuildGate.startWish} whenever any wish started — so a child's second wish, or a
 * second child's wish, erased the first wish's claims and trigger sentences mid-grant ("SPIKOR IS
 * HERE!" three times). They now keep one state per wish, and this is the key: the worker's bound
 * run ({@code AgentContext.currentRun()}, which {@code AgentBridge.onServer} carries onto the server
 * thread), else the single live run, else {@code ""} — the honest "no wish" bucket for a direct call
 * with nothing bound, never a guess between two.
 */
public final class WishScope {

    /** A test's explicit key (a GameTest has no bound worker); wins over the run when set. */
    private static final ThreadLocal<String> FORCED = new ThreadLocal<>();

    private WishScope() {
    }

    public static String key() {
        String forced = FORCED.get();
        if (forced != null) {
            return forced;
        }
        try {
            var run = com.moddingfromamod.agent.AgentContext.currentRun();
            return run == null || run.wishId() == null ? "" : run.wishId().toString();
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }

    /** Run {@code body} as if the current wish were {@code wishKey} (tests, and a caller that knows its wish). */
    public static void as(String wishKey, Runnable body) {
        String prev = FORCED.get();
        FORCED.set(wishKey == null ? "" : wishKey);
        try {
            body.run();
        } finally {
            if (prev == null) {
                FORCED.remove();
            } else {
                FORCED.set(prev);
            }
        }
    }
}
