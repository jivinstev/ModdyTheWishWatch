package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * the wait made visible, and the two cases where saying nothing is the answer.
 *
 * <p>Fairness itself is untouched (D10, oldest-first); this is only the position read out.
 */
class QueueNoticeTest {

    @Test
    void itSaysNothingWhenHeIsNextAndNothingWhenHeIsNotWaitingAtAll() {
        assertEquals("", QueueNotice.aheadLine(0),
                "\"0 wishes ahead of yours\" is a queue he has been made to think about for "
                        + "no reason");
        assertEquals("", QueueNotice.aheadLine(-1),
                "-1 is the ORDINARY case — his wish is being granted — and must be silent");
    }

    @Test
    void oneIsSingularAndTheRestAreCounted() {
        assertEquals("1 wish ahead of yours", QueueNotice.aheadLine(1));
        assertEquals("2 wishes ahead of yours", QueueNotice.aheadLine(2));
        assertEquals("7 wishes ahead of yours", QueueNotice.aheadLine(7));
    }

    // ── the position itself ──────────────────────────────────────────────────────────────────

    private static Marshal oneSlot() {
        return Marshal.forTest(new FakeClock(1000), 1);
    }

    /**
     * 🔴 It counts EVERY waiting wish, not only this child's. On a shared server the honest answer
     * to "why is mine taking so long" is that his brother asked first, and a count that hid other
     * children's wishes would go down while nothing happened.
     */
    @Test
    void aWishBehindTwoOtherChildrensReadsTwo() {
        Marshal m = oneSlot();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID mine = UUID.randomUUID();
        m.submit(first, alice, "a castle");
        m.submit(second, alice, "a dragon");
        m.submit(mine, bob, "a rocket");
        assertEquals(2, m.aheadOf(mine));
        assertEquals(0, m.aheadOf(first), "the head of the queue has nobody in front of it");
    }

    /** A wish being GRANTED is not in the queue, and must not read as "0 ahead". */
    @Test
    void aClaimedWishIsNotWaitingAtAll() {
        Marshal m = oneSlot();
        UUID player = UUID.randomUUID();
        UUID wish = UUID.randomUUID();
        m.submit(wish, player, "a castle");
        assertEquals(0, m.aheadOf(wish));
        assertTrue(m.claim("w1") != null);
        assertEquals(-1, m.aheadOf(wish),
                "once it is being granted it has left the queue; -1 is the honest answer");
        assertEquals("", QueueNotice.aheadLine(m.aheadOf(wish)));
    }

    @Test
    void aWishNobodyHasHeardOfIsNotWaitingEither() {
        assertEquals(-1, oneSlot().aheadOf(UUID.randomUUID()));
        assertEquals(-1, oneSlot().aheadOf(null));
    }
}
