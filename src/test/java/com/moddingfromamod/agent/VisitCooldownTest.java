package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/** the visit cooldown is per (visitor, host) pair and a real-time window. */
class VisitCooldownTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID cat = UUID.randomUUID();

    @Test
    void aSecondVisitInsideTheWindowIsTurnedAwayAndSaysHowLong() {
        AtomicLong now = new AtomicLong(1_000_000);
        VisitCooldown c = new VisitCooldown(now::get, 60_000);
        assertEquals(0, c.tryVisit(alice, bob));
        now.addAndGet(20_000);
        assertEquals(40, c.tryVisit(alice, bob));
        now.addAndGet(39_999);
        assertTrue(c.tryVisit(alice, bob) >= 1, "never 0 while still inside");
        now.addAndGet(1);
        assertEquals(0, c.tryVisit(alice, bob), "the window is over");
    }

    @Test
    void anotherHostOrTheVisitBackIsADifferentPair() {
        AtomicLong now = new AtomicLong(0);
        VisitCooldown c = new VisitCooldown(now::get, 60_000);
        assertEquals(0, c.tryVisit(alice, bob));
        assertEquals(0, c.tryVisit(alice, cat));
        assertEquals(0, c.tryVisit(bob, alice));
    }
}
