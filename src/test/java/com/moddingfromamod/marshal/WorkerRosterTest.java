package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Which worker is which, and which one is deaf.
 *
 * <p>The costly mistake here is stopping a HEALTHY worker, so most of these are about what must
 * NOT come back deaf.
 */
class WorkerRosterTest {

    private static final long GRACE = PoolPlan.BOOT_GRACE_MS;
    private static final long STOP = PoolPlan.DEAF_STOP_MS;

    /** Tick the roster every 3 s from {@code from} to {@code to}, returning the last answer. */
    private static List<String> tick(WorkerRoster r, Set<String> accounted, long from, long to) {
        List<String> last = List.of();
        for (long t = from; t <= to; t += 3_000L) {
            last = r.deaf(accounted, t);
        }
        return last;
    }

    @Test
    void aWorkerThatNeverListensIsDeafAfterGracePlusStop() {
        WorkerRoster r = new WorkerRoster();
        r.booted("wish-granter-2", 0);
        assertEquals(List.of(), tick(r, Set.of(), 0, GRACE + STOP - 3_000L), "not yet");
        assertEquals(List.of("wish-granter-2"), tick(r, Set.of(), GRACE + STOP - 3_000L, GRACE + STOP + 3_000L));
    }

    /**
     * the CLI built worker 1's first turn 23 ms before its tool list arrived, so it ended that turn with
     * no tools and never spoke again. It had connected and been sent its tools — that is the tell, and it is
     * replaced as soon as the tools are {@link PoolPlan#LISTED_SILENT_MS} old, not after grace plus three
     * minutes.
     */
    @Test
    void aWorkerSentItsToolsThatNeverCallsOneIsReplacedAtOnce() {
        WorkerRoster r = new WorkerRoster();
        r.booted("wish-granter-1", 0);
        r.heard("wish-granter-1", "mcp-a", 1_500L);
        r.asked("wish-granter-1", "initialize", 1_500L);
        r.asked("wish-granter-1", "tools/list", 1_600L);
        assertEquals(List.of(), r.deaf(Set.of(), 1_600L + PoolPlan.LISTED_SILENT_MS - 1));
        assertEquals(List.of("wish-granter-1"), r.deaf(Set.of(), 1_600L + PoolPlan.LISTED_SILENT_MS));
        assertTrue(1_600L + PoolPlan.LISTED_SILENT_MS < GRACE + STOP, "much sooner than the old rule");
    }

    /** The healthy worker beside it: tools, then await_wish 1.5 s later — never deaf, even when it is slow. */
    @Test
    void aWorkerThatCallsAToolAfterItsListIsNotDeafFromBirth() {
        WorkerRoster r = new WorkerRoster();
        r.booted("wish-granter-2", 0);
        r.heard("wish-granter-2", "mcp-b", 1_000L);
        r.asked("wish-granter-2", "tools/list", 1_000L);
        r.asked("wish-granter-2", "tools/call", 2_500L);
        assertEquals(List.of(), tick(r, Set.of("mcp-b"), 0, 10 * 60_000L));
        // a worker still CONNECTING (no tool list yet) is the old rule's, not this one's
        WorkerRoster q = new WorkerRoster();
        q.booted("wish-granter-3", 0);
        q.heard("wish-granter-3", "mcp-c", 1_000L);
        assertEquals(List.of(), q.deaf(Set.of(), PoolPlan.LISTED_SILENT_MS + 5_000L));
    }

    @Test
    void aParkedWorkerIsNeverDeaf() {
        WorkerRoster r = new WorkerRoster();
        r.booted("wish-granter-1", 0);
        r.heard("wish-granter-1", "mcp-a", 5_000L);
        assertEquals(List.of(), tick(r, Set.of("mcp-a"), 0, 60 * 60_000L));
    }

    /** Between two await_wish calls a worker is unaccounted for a moment on every lap. */
    @Test
    void aWorkerMidStrideIsNeverDeaf() {
        WorkerRoster r = new WorkerRoster();
        r.booted("wish-granter-1", 0);
        r.heard("wish-granter-1", "mcp-a", 5_000L);
        List<String> seen = List.of();
        for (long t = 0; t < 60 * 60_000L; t += 3_000L) {
            // parked except for one 3-second sample in every minute
            boolean between = (t / 3_000L) % 20 == 0;
            seen = r.deaf(between ? Set.of() : Set.of("mcp-a"), t);
            assertEquals(List.of(), seen, "stopped a healthy worker at t=" + t);
        }
    }

    /** A worker holding a wish for twenty minutes is working, not deaf. */
    @Test
    void aLongWishIsNotDeafness() {
        WorkerRoster r = new WorkerRoster();
        r.booted("wish-granter-1", 0);
        r.heard("wish-granter-1", "mcp-a", 5_000L);
        assertEquals(List.of(), tick(r, Set.of("mcp-a"), 0, 20 * 60_000L));
    }

    /** A worker that listened once and then fell silent (an earlier issue's SILENT class) is caught too. */
    @Test
    void aWorkerThatStopsListeningLaterIsDeafToo() {
        WorkerRoster r = new WorkerRoster();
        r.booted("wish-granter-1", 0);
        r.heard("wish-granter-1", "mcp-a", 5_000L);
        tick(r, Set.of("mcp-a"), 0, 10 * 60_000L);
        long silentFrom = 10 * 60_000L + 3_000L;
        assertEquals(List.of("wish-granter-1"), tick(r, Set.of(), silentFrom, silentFrom + STOP + 3_000L));
    }

    /** The MCP session id can change under a worker; the slot follows it. */
    @Test
    void aReinitialisedSessionIsFollowed() {
        WorkerRoster r = new WorkerRoster();
        r.booted("wish-granter-1", 0);
        r.heard("wish-granter-1", "mcp-old", 5_000L);
        r.heard("wish-granter-1", "mcp-new", 70 * 60_000L);
        assertEquals("mcp-new", r.snapshot().get(0).mcpId());
        assertEquals(List.of(), tick(r, Set.of("mcp-new"), 70 * 60_000L, 90 * 60_000L));
    }

    @Test
    void anUnknownNameIsNotRecorded() {
        WorkerRoster r = new WorkerRoster();
        assertFalse(r.heard("somebody-else", "mcp-x", 0));
        assertTrue(r.snapshot().isEmpty());
    }

    @Test
    void aReplacementTakesTheLowestFreeName() {
        WorkerRoster r = new WorkerRoster();
        assertEquals("wish-granter-1", r.nextName("wish-granter", 3));
        r.booted("wish-granter-1", 0);
        r.booted("wish-granter-2", 0);
        r.booted("wish-granter-3", 0);
        r.forget("wish-granter-2");
        assertEquals("wish-granter-2", r.nextName("wish-granter", 3));
    }

    @Test
    void aOneSlotPoolKeepsItsPlainName() {
        assertEquals("wish-granter", new WorkerRoster().nextName("wish-granter", 1));
    }

    @Test
    void theCliIdJoinsTheSlot() {
        WorkerRoster r = new WorkerRoster();
        r.booted("wish-granter-1", 0);
        assertNull(r.snapshot().get(0).cliId());
        r.cliId("wish-granter-1", "a1b2c3d4");
        assertEquals("a1b2c3d4", r.snapshot().get(0).cliId());
    }

    /** A worker booting right now is not late yet, whatever it has or has not said. */
    @Test
    void theBootGraceHolds() {
        WorkerRoster r = new WorkerRoster();
        r.booted("wish-granter-1", 0);
        assertEquals(List.of(), tick(r, Set.of(), 0, GRACE - 3_000L));
    }

    // ── The idle clock the shrink rule reads ───────────────────────────────────────────

    @Test
    void aWorkerThatNeverHeldAWishHasBeenIdleSinceItsBoot() {
        WorkerRoster r = new WorkerRoster();
        r.booted("wish-granter-2", 5_000L);
        r.heard("wish-granter-2", "mcp-2", 9_000L);
        r.idleClock(Set.of(), 60_000L);
        assertEquals(5_000L, r.snapshot().get(0).idleSinceMs());
    }

    @Test
    void holdingAWishRestartsTheIdleClockAtTheLastTickItWasSeenBusy() {
        WorkerRoster r = new WorkerRoster();
        r.booted("wish-granter-2", 0L);
        r.heard("wish-granter-2", "mcp-2", 1_000L);
        r.idleClock(Set.of("mcp-2"), 100_000L);
        r.idleClock(Set.of("mcp-2"), 400_000L);
        r.idleClock(Set.of(), 403_000L);   // finished: the clock stays at the last busy tick
        assertEquals(400_000L, r.snapshot().get(0).idleSinceMs());
        r.idleClock(Set.of("mcp-other"), 900_000L);
        assertEquals(400_000L, r.snapshot().get(0).idleSinceMs(), "somebody else's wish is not ours");
    }

    @Test
    void aSlotNeverHeardFromCannotBeSeenBusy() {
        WorkerRoster r = new WorkerRoster();
        r.booted("wish-granter-2", 0L);
        r.idleClock(Set.of("mcp-2"), 100_000L);
        assertEquals(0L, r.snapshot().get(0).idleSinceMs());
    }
}
