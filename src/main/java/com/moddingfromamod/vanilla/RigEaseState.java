package com.moddingfromamod.vanilla;

/**
 * <b>What the tracker needs to keep a look change from jumping a pose ({@link RigCadence#REARM}).</b>
 * Every display carries it (the {@code RigDisplayEaseMixin}): on the server it notes, between two sends, whether the
 * display's pose ease was restarted ({@code start_interpolation} re-sent) and whether anything that makes a client
 * rebuild the display's render state changed (its light, its shadow, its item: a tint, a hurt flash, the shimmer).
 * The tracker reads and clears it when it sends the display's data ({@code RigTrackerCadenceMixin}).
 */
public interface RigEaseState {

    /** {@code start_interpolation} was set since the last send: a new ease starts with this send. */
    boolean moddingfromamod$easeStarted();

    /** A field that rebuilds the client's render state changed since the last send. */
    boolean moddingfromamod$lookChanged();

    /** Forget both, after a send. */
    void moddingfromamod$clearEaseFlags();

    /** The {@code interpolation_duration} the display holds now. */
    int moddingfromamod$easeDuration();

    /** The tracker tick at which the client's current ease ends (as sent), or {@code Long.MIN_VALUE}. */
    long moddingfromamod$easeEnd();

    void moddingfromamod$setEaseEnd(long trackerTick);

    /** Restart the client's ease now, over {@code ticks}, toward the pose the display already holds. */
    void moddingfromamod$rearm(int ticks);
}
