package com.moddingfromamod.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** three deaths on his own base inside two minutes is a loop, offered help once per window. */
class RespawnLoopTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    void theThirdDeathInsideTheWindowIsTheOffer() {
        RespawnLoop l = new RespawnLoop();
        assertFalse(l.died(A, 0));
        assertFalse(l.died(A, 10_000));
        assertTrue(l.died(A, 20_000), "three deaths in 20 s is a loop");
    }

    @Test
    void deathsSpreadOutAreNotALoop() {
        RespawnLoop l = new RespawnLoop();
        assertFalse(l.died(A, 0));
        assertFalse(l.died(A, 70_000));
        assertFalse(l.died(A, 140_000), "the first death fell out of the window");
        assertTrue(l.died(A, 150_000));
    }

    @Test
    void theOfferIsMadeOncePerWindowNotOnEveryDeath() {
        RespawnLoop l = new RespawnLoop();
        l.died(A, 0);
        l.died(A, 1_000);
        assertTrue(l.died(A, 2_000));
        assertFalse(l.died(A, 3_000), "a fourth death a second later is not offered again");
        assertFalse(l.died(A, 60_000));
        assertFalse(l.died(A, 124_000));
        assertTrue(l.died(A, 125_000), "a struggle that goes on is offered help again after a window");
    }

    @Test
    void eachPlayerIsCountedAlone() {
        RespawnLoop l = new RespawnLoop();
        l.died(A, 0);
        l.died(A, 1_000);
        assertFalse(l.died(B, 2_000), "B's first death is not A's third");
        assertTrue(l.died(A, 3_000));
    }

    @Test
    void forgettingStartsAfresh() {
        RespawnLoop l = new RespawnLoop();
        l.died(A, 0);
        l.died(A, 1_000);
        l.forget(A);
        assertFalse(l.died(A, 2_000));
        assertFalse(l.died(null, 2_000));
    }
}
