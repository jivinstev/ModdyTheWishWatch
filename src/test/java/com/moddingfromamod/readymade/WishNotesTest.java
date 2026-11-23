package com.moddingfromamod.readymade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** the per-wish notes queue a nudge rides on. */
class WishNotesTest {

    @Test
    @DisplayName("a note waits for the next drain, once; a none adds nothing")
    void drainOnce() {
        WishNotes n = WishNotes.forTest();
        n.expect("w");
        assertTrue(n.pending("w"));
        n.deliver("w", "use aggro");
        assertFalse(n.pending("w"));
        assertEquals("use aggro", n.drain("w"));
        assertEquals("", n.drain("w"));
        n.expect("w");
        n.deliver("w", "");
        assertEquals("", n.drain("w"));
    }

    @Test
    @DisplayName("await returns at once with nothing pending, and when the nudge lands")
    void await() throws Exception {
        WishNotes n = WishNotes.forTest();
        assertTrue(n.await("x", 10));
        n.expect("x");
        long t0 = System.currentTimeMillis();
        new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                // test thread
            }
            n.deliver("x", "hi");
        }).start();
        assertTrue(n.await("x", 5_000));
        assertTrue(System.currentTimeMillis() - t0 < 4_000);
        n.expect("y");
        assertFalse(n.await("y", 50), "a nudge that never lands stops the wait at its ceiling");
        n.forget("y");
        assertFalse(n.pending("y"));
    }

    @Test
    @DisplayName("config letters: bcd, all, none, junk")
    void config() {
        assertEquals("bcd", ReadyMadeConfig.letters(ReadyMadeConfig.parse("bcd")));
        assertEquals("abcd", ReadyMadeConfig.letters(ReadyMadeConfig.parse("all")));
        assertEquals("none", ReadyMadeConfig.letters(ReadyMadeConfig.parse("none")));
        assertEquals("none", ReadyMadeConfig.letters(ReadyMadeConfig.parse("xyz")));
        assertEquals("ad", ReadyMadeConfig.letters(ReadyMadeConfig.parse("d,a")));
        // Shipped 2026-10-27: (a) and (c) are the nudge's default, and only those.
        assertEquals("ac", ReadyMadeConfig.letters(ReadyMadeConfig.parse(ReadyMadeConfig.DEFAULT_NUDGES)));
    }
}
