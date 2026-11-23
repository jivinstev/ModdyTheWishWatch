package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

/**
 * Gate A for the "is the newest pack the one this rejected wish produced?" decision — the guard that
 * lets a reject turn OFF a wrong wish's datapack without ever disabling an innocent, unrelated pack.
 */
class DatapackReloaderTest {

    private static final long WINDOW = 10 * 60_000L; // WishRejection.MAX_AGE_MS
    private static final long GRACE = 60_000L;

    @Test
    void aPackWrittenRightAfterTheWishIsTheCulprit() {
        long wish = 1_000_000L;
        long pack = wish + 2_000L;      // authored ~2s into granting the wish
        long now = wish + 5_000L;
        assertTrue(DatapackReloader.packMatchesRejectedWish(pack, wish, now, WINDOW, GRACE),
                "a pack written just after the wish, seen right away, is the culprit");
    }

    @Test
    void anOlderPackFromBeforeThisWishIsNotTheCulprit() {
        // The template-only / hand-built rejection: the newest pack on disk predates this wish, so it
        // belongs to some EARLIER wish and must never be disabled by rejecting this one.
        long wish = 1_000_000L;
        long pack = wish - 5 * 60_000L; // five minutes older than the wish
        long now = wish + 5_000L;
        assertFalse(DatapackReloader.packMatchesRejectedWish(pack, wish, now, WINDOW, GRACE),
                "an older pack from a previous wish is not this wish's fault");
    }

    @Test
    void aPackSeenLongAfterItWasWrittenIsTooStale() {
        // Even a pack authored after the wish shouldn't be disabled if we're only reacting to it much
        // later — that's outside the "the child is looking at it right now" window.
        long wish = 1_000_000L;
        long pack = wish + 2_000L;
        long now = pack + 20 * 60_000L; // twenty minutes later
        assertFalse(DatapackReloader.packMatchesRejectedWish(pack, wish, now, WINDOW, GRACE),
                "a pack we only get around to hours later is out of the reaction window");
    }

    @Test
    void aPackWrittenJustBeforeTheWishIsStillWithinGrace() {
        // Clock skew / a pack staged a moment before the wish record lands: the grace window keeps it.
        long wish = 1_000_000L;
        long pack = wish - 30_000L;     // 30s before, inside the 60s grace
        long now = wish + 5_000L;
        assertTrue(DatapackReloader.packMatchesRejectedWish(pack, wish, now, WINDOW, GRACE),
                "a small lead within grace still counts as this wish's pack");
    }

    // ---- the reload-coalescing guard ----
    //
    // Why these exist: a reload occupies the server thread for tens of seconds on a big modpack, so
    // a second reload started during the first cannot even be SCHEDULED and times out — which reads
    // as failure, invites a retry, and queues yet another reload. Live on 2026-08-03 that loop ran
    // 35 times and made the game unplayable. reloadInProgress() is what lets a caller decline.

    @Test
    void aReloadStillRunningIsReportedAsInProgress() {
        CompletableFuture<Void> running = new CompletableFuture<>();
        DatapackReloader.trackReload(running);
        assertTrue(DatapackReloader.reloadInProgress(),
                "while the server thread is driving a reload, callers must be told to stand off");
        running.complete(null);
    }

    @Test
    void aFinishedReloadNoLongerBlocksTheNextOne() {
        DatapackReloader.trackReload(CompletableFuture.completedFuture(null));
        assertFalse(DatapackReloader.reloadInProgress(),
                "once the reload finished, a genuine recovery reload must be allowed through");
    }

    @Test
    void aReloadThatFailedDoesNotWedgeTheGuardForever() {
        // A reload that blows up still COMPLETES its future (exceptionally). If we treated that as
        // "still running", reload_datapacks would refuse forever and the recovery path would be dead.
        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("bad pack"));
        DatapackReloader.trackReload(failed);
        assertFalse(DatapackReloader.reloadInProgress(),
                "a failed reload is finished, not in flight — the guard must release");
    }
}
