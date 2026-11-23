package com.moddingfromamod.perf;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The [chunks] line: the numbers, and which of device or connection they point at. */
class ChunkPaceTest {

    @Test
    void readsTheDeviceTheConnectionOrNobody() {
        assertTrue(ChunkPace.reading(0.4f, 200, 3).contains("DEVICE is the limit"));
        assertTrue(ChunkPace.reading(9f, 200, 150).contains("CONNECTION is the limit"));
        assertTrue(ChunkPace.reading(0.4f, 200, 150).contains("AND"));
        assertTrue(ChunkPace.reading(9f, 200, 4).contains("kept up"));
        assertTrue(ChunkPace.reading(0.4f, 0, 0).contains("nothing was waiting"));
        assertTrue(ChunkPace.reading(Float.NaN, 50, 2).contains("kept up"));   // no acks: no device claim
        // MEASURED on loopback (a stock client, same machine): the join's burst touched the cap with 16
        // chunks waiting. A short burst at the cap is not a slow connection.
        assertTrue(ChunkPace.reading(11.7f, 12, 10).contains("kept up"), ChunkPace.reading(11.7f, 12, 10));
        // MEASURED, the same client both ways: loopback held 0 of 22 waiting ticks; a 15 KB/s relay 37 of 78.
        assertTrue(ChunkPace.reading(15.1f, 22, 0).contains("kept up"));
        assertTrue(ChunkPace.reading(8.4f, 78, 37).contains("CONNECTION is the limit"));
        assertTrue(ChunkPace.reading(11.7f, 60, 15).contains("kept up"));   // held a quarter of it: not enough
    }

    @Test
    void theLineSaysMillisecondsPerChunkAndResetsTheWindow() {
        ChunkPace p = new ChunkPace();
        p.joined("a", 100);
        p.ack("a", 0.5f);
        p.ack("a", 0.7f);
        p.ack("a", 2.0f);
        p.sent("a", 40, 300, 6);
        p.sent("a", 20, 250, 10);
        String l = p.line("a", "kid", "first 15 s", 15, 120);
        assertTrue(l.startsWith("kid (first 15 s): client asks 0.70 chunks/tick (min 0.50, max 2.00, 3 acks) = ~10.0 ms"), l);
        assertTrue(l.contains("server sent 60 chunks (4.0/s), up to 300 waiting to send; chunks waited 2 tick(s), "
                + "held at the 10-batch acknowledgement cap for 1 of them"), l);
        assertTrue(l.endsWith("DEVICE is the limit (it takes over 7 ms per chunk)"), l);
        String next = p.line("a", "kid", "last 60 s", 60, 0);
        assertTrue(next.contains("acknowledged no chunk batch") && next.contains("sent 0 chunks"), next);
    }

    @Test
    void theFirstLineIsDueOnceFifteenSecondsAfterTheJoin() {
        ChunkPace p = new ChunkPace();
        p.joined("a", 1000);
        assertEquals(List.of(), p.firstDue(1000 + ChunkPace.FIRST_LINE_TICKS - 1));
        assertEquals(List.of("a"), p.firstDue(1000 + ChunkPace.FIRST_LINE_TICKS));
        assertEquals(List.of(), p.firstDue(1000 + ChunkPace.FIRST_LINE_TICKS + 5));
        p.left("a");
        assertEquals(List.of(), p.firstDue(99999));
        // joined just before the minute line: that line covered the join, so no empty "first 15 s" after it
        p.joined("b", 5000);
        p.line("b", "b", "last minute", 60, 0);
        assertEquals(List.of(), p.firstDue(5000 + ChunkPace.FIRST_LINE_TICKS));
    }
}
