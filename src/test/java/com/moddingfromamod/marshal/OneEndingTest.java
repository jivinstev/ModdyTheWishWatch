package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * <b>One wish, one ending</b> — the worker half, at the seam every tool call passes through.
 *
 * <p>Live, 2026-09-24: "make it daytime forever" told its child it was granted TWICE — a second
 * plan, a second bold "built", a second bold "granted" and a second "Want to change it?". Nothing
 * anywhere refused a second finish cycle: {@link CancelGate} refused tools only after a STOP, and a
 * worker that had called {@code finish_wish} looked exactly like one that had never had a wish.
 */
class OneEndingTest {

    private static final UUID CHILD = UUID.nameUUIDFromBytes("steve".getBytes());

    private static WishRun submit(Marshal m, String t) {
        return m.submit(UUID.nameUUIDFromBytes(t.getBytes()), CHILD, t);
    }

    /** The tools the second cycle on the live server was made of, plus the other voices. */
    private static final List<String> SECOND_CYCLE = List.of(
            "plan_wish", "conjure_at", "run_command", "author_datapack", "finish_building",
            "finish_wish", "notify_player", "set_status", "photograph", "give_items");

    @Test
    void aFinishedWorkerIsRefusedEveryToolThatSpeaksOrChangesTheWorld() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        WishRun run = submit(m, "make it daytime forever");
        m.claim("w1");
        assertTrue(m.finish(run.wishId()));

        assertSame(run, m.finishedFor("w1"), "the marshal forgot which wish this worker ended");
        for (String tool : SECOND_CYCLE) {
            String refusal = CancelGate.refusalFor(m, "w1", tool);
            assertNotNull(refusal, tool + " ran after the wish was finished — that is how the "
                    + "child heard 'granted' twice");
            assertTrue(refusal.contains("REFUSED") && refusal.contains("await_wish")
                            && refusal.contains(run.shortId()),
                    "the refusal must say it did not run, name the wish, and name the way on: "
                            + refusal);
            assertFalse(refusal.contains("again"),
                    "a refusal that says 'again' reads as an invitation to retry, and the "
                            + "retry is the bug: " + refusal);
        }
    }

    @Test
    void awaitWishAndReadOnlyLookUpsStillAnswer() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        WishRun run = submit(m, "make it daytime forever");
        m.claim("w1");
        m.finish(run.wishId());

        for (String tool : List.of("await_wish", "list_wishes", "list_datapacks",
                "inspect_build", "find_content", "cbscript_help", "look_up_character")) {
            assertNull(CancelGate.refusalFor(m, "w1", tool),
                    tool + " neither speaks nor builds; refusing it only confuses the agent");
        }
    }

    @Test
    void theRefusalEndsTheMomentTheWorkerTakesItsNextWish() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        WishRun first = submit(m, "make it daytime forever");
        m.claim("w1");
        m.finish(first.wishId());
        WishRun second = submit(m, "make me a dragon");

        assertNotNull(m.assign("w1", second.wishId()), "await_wish hands it the next wish");
        assertNull(m.finishedFor("w1"));
        assertNull(CancelGate.refusalFor(m, "w1", "finish_building"),
                "a worker that did as it was told must never be refused for its NEXT wish");
    }

    @Test
    void claimingAlsoEndsIt() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        WishRun first = submit(m, "A");
        m.claim("w1");
        m.finish(first.wishId());
        submit(m, "B");
        assertNotNull(m.claim("w1"));
        assertNull(CancelGate.refusalFor(m, "w1", "plan_wish"));
    }

    @Test
    void anotherWorkerIsNeverRefusedForThisOnesEnding() {
        Marshal m = Marshal.forTest(new FakeClock(0), 2);
        WishRun a = submit(m, "A");
        WishRun b = submit(m, "B");
        m.claim("w1");
        m.claim("w2");
        m.finish(a.wishId());
        assertNull(CancelGate.refusalFor(m, "w2", "finish_building"),
                "w2 is still granting B and must be left alone");
        assertNotNull(CancelGate.refusalFor(m, "w1", "finish_building"));
        assertTrue(m.finish(b.wishId()));
    }

    @Test
    void aSecondFinishOfTheSameRunIsFalseAndChangesNothing() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        WishRun run = submit(m, "make it daytime forever");
        m.claim("w1");
        assertTrue(m.finish(run.wishId()));
        assertFalse(m.finish(run.wishId()), "a second finish must report that it did nothing");
        assertSame(run, m.finishedFor("w1"));
    }

    @Test
    void aStopStillWinsOverAFinishedMarker() {
        // A stop's wording is the more specific one — it carries "he wanted it aimed elsewhere".
        Marshal m = Marshal.forTest(new FakeClock(0), 2);
        WishRun a = submit(m, "A");
        m.claim("w1");
        m.finish(a.wishId());
        WishRun b = submit(m, "B");
        m.assign("w1", b.wishId());
        m.cancel(b.wishId(), CancelMode.STOP, "", null);
        String refusal = CancelGate.refusalFor(m, "w1", "build_structure");
        assertNotNull(refusal);
        assertTrue(refusal.startsWith("STOP"), refusal);
    }
}
