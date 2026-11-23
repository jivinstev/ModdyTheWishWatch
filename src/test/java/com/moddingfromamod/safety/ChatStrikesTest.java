package com.moddingfromamod.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The break schedule — three in ten minutes, and longer each time within a day. */
class ChatStrikesTest {

    private static final UUID A = UUID.randomUUID();
    private static final long MIN = 60_000L;

    @Test
    void twoStrikesAreForgivenAndOldOnesAgeOut() {
        ChatStrikes s = new ChatStrikes();
        assertEquals(0L, s.strike(A, 0));
        assertEquals(0L, s.strike(A, MIN));
        assertEquals(0L, s.strike(A, 12 * MIN), "the first has aged out of the window");
        assertEquals(0L, s.mutedUntil(A, 12 * MIN));
    }

    @Test
    void breaksGrowTenThirtySixtyThenStayAtSixty() {
        ChatStrikes s = new ChatStrikes();
        long t = 0;
        long[] want = {10, 30, 60, 60};
        for (long w : want) {
            s.strike(A, t);
            s.strike(A, t + 1);
            long until = s.strike(A, t + 2);
            assertEquals(t + 2 + w * MIN, until);
            assertEquals(until, s.mutedUntil(A, t + 3));
            t = until + MIN;
            assertEquals(0L, s.mutedUntil(A, t));
        }
    }

    @Test
    void aDayLaterTheBreakIsShortAgain() {
        ChatStrikes s = new ChatStrikes();
        s.strike(A, 0);
        s.strike(A, 1);
        s.strike(A, 2);
        long t = ChatStrikes.REPEAT_MS + 10 * MIN;
        s.strike(A, t);
        s.strike(A, t + 1);
        assertEquals(t + 2 + 10 * MIN, s.strike(A, t + 2));
    }

    @Test
    void minutesLeftRoundsUpAndIsNeverZero() {
        assertEquals(10, ChatStrikes.minutesLeft(10 * MIN, 0));
        assertEquals(1, ChatStrikes.minutesLeft(1, 0));
        assertEquals(1, ChatStrikes.minutesLeft(0, 5));
        assertEquals(2, ChatStrikes.minutesLeft(MIN + 1, 0));
    }
}
