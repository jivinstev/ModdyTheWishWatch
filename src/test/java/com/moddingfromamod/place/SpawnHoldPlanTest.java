package com.moddingfromamod.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Gate A for {@link SpawnHoldPlan}: how wide a spawn-fallback hold is, what the action waits for, and
 * when it is let go. In real milliseconds, because the agent's next turn is real time; the GameTest
 * ({@code SpawnHoldGameTest}) proves the ticket really makes a summon findable.
 */
class SpawnHoldPlanTest {

    private static final SpawnHoldPlan.Key SPAWN = new SpawnHoldPlan.Key("minecraft:overworld", 0, 0);
    private static final SpawnHoldPlan.Key ANCHOR = new SpawnHoldPlan.Key("minecraft:overworld", 4, 8);

    @Test
    void theHoldCoversTheBandTheActionsOwnLookupSearches() {
        int r = SpawnHoldPlan.radiusChunks();
        assertEquals(3, r, "48 blocks round any point in the middle chunk reach 3 chunks out");
        // every block within the band, from either edge of the middle chunk, is inside the radius
        for (int x : new int[] {0, 15}) {
            for (int d = -SpawnHoldPlan.LOOKUP_BLOCKS; d <= SpawnHoldPlan.LOOKUP_BLOCKS; d++) {
                int out = Math.abs(SpawnHoldPlan.chunkOf(x + d));
                assertTrue(out <= r, "block " + (x + d) + " is " + out + " chunks out, past the hold");
            }
        }
        assertEquals(1, SpawnHoldPlan.readyRing(), "the 3x3 middle is entity-ticking under a radius-3 ticket");
    }

    @Test
    void chunksAreFoundByFloorNotByTruncation() {
        assertEquals(0, SpawnHoldPlan.chunkOf(0));
        assertEquals(0, SpawnHoldPlan.chunkOf(15));
        assertEquals(-1, SpawnHoldPlan.chunkOf(-1));
        assertEquals(-1, SpawnHoldPlan.chunkOf(-16));
        assertEquals(-2, SpawnHoldPlan.chunkOf(-17));
    }

    @Test
    void theReadySetIsTheSquareRoundTheMiddleOnceEach() {
        List<int[]> ring = SpawnHoldPlan.chunksAround(-3, 5, 1);
        assertEquals(9, ring.size());
        assertEquals(-3, ring.get(0)[0], "middle first");
        assertEquals(5, ring.get(0)[1], "middle first");
        Set<String> seen = new HashSet<>();
        for (int[] c : ring) {
            assertTrue(Math.abs(c[0] + 3) <= 1 && Math.abs(c[1] - 5) <= 1, c[0] + "," + c[1]);
            assertTrue(seen.add(c[0] + "," + c[1]), "twice: " + c[0] + "," + c[1]);
        }
        assertEquals(1, SpawnHoldPlan.chunksAround(0, 0, 0).size());
    }

    @Test
    void anAgentActionIsHeldThroughItsTailThenLetGo() {
        SpawnHoldPlan.Book book = new SpawnHoldPlan.Book();
        long t = book.open(SPAWN, 1_000);
        assertEquals(List.of(SPAWN), book.step(2_000).keep(), "open: kept");
        book.close(t, 5_000, SpawnHoldPlan.Followup.AGENT);
        SpawnHoldPlan.Step mid = book.step(5_000 + SpawnHoldPlan.AGENT_TAIL_MS - 1);
        assertEquals(List.of(SPAWN), mid.keep(), "the agent's next call, a turn later, still finds it");
        assertTrue(mid.release().isEmpty());
        SpawnHoldPlan.Step end = book.step(5_000 + SpawnHoldPlan.AGENT_TAIL_MS);
        assertTrue(end.keep().isEmpty());
        assertEquals(List.of(SPAWN), end.release(), "let go once the tail has run");
        SpawnHoldPlan.Step after = book.step(5_000 + SpawnHoldPlan.AGENT_TAIL_MS + 50);
        assertTrue(after.keep().isEmpty() && after.release().isEmpty(), "released once, not every tick");
        assertTrue(book.ticketed().isEmpty());
    }

    @Test
    void anActionNobodyFollowsUpIsLetGoAtTheNextTick() {
        SpawnHoldPlan.Book book = new SpawnHoldPlan.Book();
        long t = book.open(SPAWN, 0);
        book.close(t, 10, SpawnHoldPlan.Followup.NONE);
        assertEquals(List.of(SPAWN), book.step(10).release());
    }

    @Test
    void aSecondActionAtTheSameSpotKeepsItUntilTheLastOneEnds() {
        SpawnHoldPlan.Book book = new SpawnHoldPlan.Book();
        long first = book.open(SPAWN, 0);
        long second = book.open(SPAWN, 10_000);
        book.close(first, 10_000, SpawnHoldPlan.Followup.NONE);
        assertEquals(List.of(SPAWN), book.step(10_001).keep(), "the second is still running");
        book.close(second, 20_000, SpawnHoldPlan.Followup.AGENT);
        assertEquals(List.of(SPAWN), book.step(20_000 + SpawnHoldPlan.AGENT_TAIL_MS - 1).keep());
        assertEquals(List.of(SPAWN), book.step(20_000 + SpawnHoldPlan.AGENT_TAIL_MS).release());
    }

    @Test
    void aNewActionInTheTailRefreshesIt() {
        SpawnHoldPlan.Book book = new SpawnHoldPlan.Book();
        book.close(book.open(SPAWN, 0), 0, SpawnHoldPlan.Followup.AGENT);
        long later = SpawnHoldPlan.AGENT_TAIL_MS - 1_000;
        book.close(book.open(SPAWN, later), later, SpawnHoldPlan.Followup.AGENT);
        assertEquals(List.of(SPAWN), book.step(SpawnHoldPlan.AGENT_TAIL_MS + 1).keep(),
                "a minute after the LAST action, not the first");
    }

    @Test
    void twoSpotsAreHeldAndLetGoApart() {
        SpawnHoldPlan.Book book = new SpawnHoldPlan.Book();
        long a = book.open(SPAWN, 0);
        long b = book.open(ANCHOR, 0);
        book.close(a, 0, SpawnHoldPlan.Followup.NONE);
        SpawnHoldPlan.Step s = book.step(1);
        assertEquals(List.of(ANCHOR), s.keep());
        assertEquals(List.of(SPAWN), s.release());
        book.close(b, 1, SpawnHoldPlan.Followup.NONE);
        assertEquals(List.of(ANCHOR), book.step(2).release());
    }

    @Test
    void aHoldWhoseCloseNeverComesStillEnds() {
        SpawnHoldPlan.Book book = new SpawnHoldPlan.Book();
        book.open(SPAWN, 0);
        assertEquals(List.of(SPAWN), book.step(SpawnHoldPlan.MAX_OPEN_MS - 1).keep());
        assertEquals(List.of(SPAWN), book.step(SpawnHoldPlan.MAX_OPEN_MS).release(),
                "never the spawn loaded for good");
    }

    @Test
    void aSecondCloseDoesNotMoveTheTail() {
        SpawnHoldPlan.Book book = new SpawnHoldPlan.Book();
        long t = book.open(SPAWN, 0);
        book.close(t, 0, SpawnHoldPlan.Followup.NONE);
        book.close(t, 0, SpawnHoldPlan.Followup.AGENT);
        assertEquals(List.of(SPAWN), book.step(1).release());
    }

    @Test
    void theTailsAreWhatTheDocSays() {
        assertEquals(60_000L, SpawnHoldPlan.tailMs(SpawnHoldPlan.Followup.AGENT));
        assertEquals(0L, SpawnHoldPlan.tailMs(SpawnHoldPlan.Followup.NONE));
        assertTrue(SpawnHoldPlan.MAX_OPEN_MS > 8 * 60_000L, "longer than the Magician check's wish");
        assertTrue(SpawnHoldPlan.READY_WAIT_MS < 5_000L, "answers inside the agent's five-second hop");
    }
}
