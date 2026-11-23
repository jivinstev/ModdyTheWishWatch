package com.moddingfromamod.event;

/**
 * <b>What an event does, one tick at a time</b> (E1). The eight motions of E2 implement
 * this; the director owns the journal, the guards and the budget, so a motion only says WHERE it
 * wants what, through {@link EventWriter}.
 *
 * <p>A motion is not saved. If the server stops while an event is running, the event is rolled back
 * on the next start rather than resumed: a half-played wave cannot be picked up where it left off,
 * and a world left half-flooded is the one outcome D1 rules out.
 */
@FunctionalInterface
public interface EventMotion {

    /**
     * Advance by one tick. {@code age} counts this event's ticks from 0. Return true when the motion
     * has finished; the director then gives the world back or keeps it, as the event says.
     */
    boolean step(EventWriter writer, int age);

    /**
     * Stopped before it finished (an undo mid-play, or an error): put back anything it set OUTSIDE the
     * world's blocks and its tagged entities, which the director gives back itself — a hurricane's rain sent
     * to the players near it is the one so far (the vortex leak). Never called after {@link #step} returned true.
     */
    default void stopped(net.minecraft.server.level.ServerLevel level) {
    }
}
