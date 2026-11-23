package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How long did the wish take? —.
 *
 * <p>`WishJournal` recorded when a wish was CREATED and nothing about when it stopped, so the most
 * basic question about the product — how long does granting take, and how does that scale with
 * complexity — could not be answered from its own data. A twenty-second wish and a twenty-minute
 * one were indistinguishable afterwards.
 */
class WishDurationTest {

    private static Wish pending(long createdMs) {
        return Wish.create("make me a dragon", WishSource.TYPED, createdMs, UUID.randomUUID(),
                "Dev", "overworld", 0, 64, 0);
    }

    @Test
    @DisplayName("a wish that has not finished reports no duration, rather than zero")
    void unsettledIsNotZero() {
        Wish w = pending(1000);
        assertFalse(w.isSettled());
        assertEquals(-1, w.tookMs(),
                "0 would average in as an instant wish and quietly drag every mean down");
    }

    @Test
    @DisplayName("a finished wish knows how long it took")
    void settledMeasures() {
        Wish done = pending(1000).withStatus(WishStatus.DONE, "built it", 21000);
        assertTrue(done.isSettled());
        assertEquals(20000, done.tookMs());
    }

    @Test
    @DisplayName("every ending counts, not just the happy one")
    void everyFinalStatusStamps() {
        // DERIVED from isFinished() rather than listed. A hand-written list of endings is a gate
        // that expires: TIMED_OUT was added as a fifth ending and this test would have gone
        // on passing over it, which is the one case where a wish settles and nothing records when.
        for (WishStatus s : java.util.Arrays.stream(WishStatus.values())
                .filter(WishStatus::isFinished).toList()) {
            assertTrue(pending(1000).withStatus(s, "", 5000).isSettled(), s + " did not stamp");
        }
        // ...and a move that is not an ending must not stamp one.
        assertFalse(pending(1000).withStatus(WishStatus.PENDING, "", 5000).isSettled());
    }

    @Test
    @DisplayName("the first ending wins — a later touch cannot rewrite how long it took")
    void theStampIsWrittenOnce() {
        Wish done = pending(1000).withStatus(WishStatus.DONE, "built it", 21000);
        Wish touchedLater = done.withStatus(WishStatus.DONE, "same", 99000);
        assertEquals(20000, touchedLater.tookMs(),
                "re-journalling a finished wish must not restart its clock");
    }

    @Test
    @DisplayName("it survives the journal, and an old line without it still loads")
    void roundTripsAndIsBackwardCompatible() {
        Wish done = pending(1000).withStatus(WishStatus.DONE, "built it", 21000);
        assertEquals(20000, Wish.fromJson(done.toJson()).tookMs());

        // Every line written before this field existed. They must load as "unknown", not as 0.
        var old = done.toJson();
        old.remove("settledMs");
        Wish loaded = Wish.fromJson(old);
        assertFalse(loaded.isSettled());
        assertEquals(-1, loaded.tookMs());
    }
}
