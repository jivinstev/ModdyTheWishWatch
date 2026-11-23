package com.moddingfromamod.event.motion;

import java.util.function.IntConsumer;

import com.moddingfromamod.event.EventMotion;
import com.moddingfromamod.event.EventWriter;
import com.moddingfromamod.event.shape.Countdown;

/**
 * <b>A {@link Countdown} ticking down</b> (E2). It writes no blocks: it calls {@code announce}
 * with the seconds left on every second worth saying, and {@code atZero} once, at the end — which is
 * where E4's "then" starts whatever the clock was counting down to.
 */
public final class CountdownMotion implements EventMotion {

    private final Countdown clock;
    private final IntConsumer announce;
    private final Runnable atZero;
    private int lastSaid = -1;

    public CountdownMotion(Countdown clock, IntConsumer announce, Runnable atZero) {
        this.clock = clock;
        this.announce = announce;
        this.atZero = atZero;
    }

    @Override
    public boolean step(EventWriter w, int age) {
        int left = clock.secondsLeft(age);
        if (left != lastSaid && clock.announces(left)) {
            announce.accept(left);
        }
        lastSaid = left;
        if (left == 0) {
            atZero.run();
            return true;
        }
        return false;
    }
}
