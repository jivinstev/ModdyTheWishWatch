package com.moddingfromamod.marshal;

/**
 * Where the time comes from — handed in, never read from a static.
 *
 * <p><b>A GameTest server ticks as fast as it can.</b> 120 ticks went by in about a fifth of a
 * second on this machine, so anything whose behaviour is timed in real seconds — a stickiness
 * window, a cancel deadline, a resolver deadline, a warm-spare reap — cannot be asserted in ticks.
 * It fails while the feature works, twice, and reads as a dead feature both times.
 *
 * <p>So every deadline in the marshal is expressed against one of these. Gate A asserts the timing
 * with a {@link FakeClock} and no sleeping at all; Gate B proves only the wiring.
 */
@FunctionalInterface
public interface Clock {

    long nowMs();

    /** Real time. The only implementation production uses. */
    static Clock system() {
        return System::currentTimeMillis;
    }
}
