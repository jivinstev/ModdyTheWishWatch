package com.moddingfromamod.health;

import java.util.Optional;

/**
 * <b>A server's own health reporting, plugged in</b>. A dedicated server may register one: its
 * health thread drives the {@link WishSweeper}, reads its quota and spend files after each sweep, and answers the
 * pack host's {@code /healthz} and {@code /canary}. With none registered (the public single-player mod) the sweeper
 * drives itself and the pack host serves neither page.
 *
 * <p><b>An internal seam, not a documented extension point</b>: it exists so a private plug-in's health thread
 * can drive the public sweeper without the core naming it. Its shape may change.
 */
public interface HealthHook {

    /** After each wish sweep, before Moddy's tiredness is announced: read the evidence a sweep cannot see. */
    default void afterSweep(long nowMs) {
    }

    /** {@code /healthz}: is this server fresh and able to grant a wish? */
    boolean healthy(long nowMs);

    /** {@code /canary}: the canary's last verdict line, or empty when there is none. */
    default Optional<String> canary() {
        return Optional.empty();
    }
}
