package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * §1.3 — cancellation, at every point in a wish's life.
 *
 * <p>The whole guess-don't-ask policy is gated on stopping being one click, so these are the tests
 * that let the ladder be aggressive later. The recurring shape: <b>the visible stop is instant and
 * unconditional; the worker's wind-down is a refusal, not a suggestion.</b>
 */
class CancelPolicyTest {

    private static final UUID CHILD = UUID.nameUUIDFromBytes("steve".getBytes());

    private static UUID wish(String t) {
        return UUID.nameUUIDFromBytes(t.getBytes());
    }

    private static WishRun submit(Marshal m, String t) {
        return m.submit(wish(t), CHILD, t);
    }

    // ---- 1. before a worker ever sees it ---------------------------------------------------

    @Test
    void cancelBeforeDispatch() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        WishRun run = submit(m, "a dragon");

        var result = m.cancel(run.wishId(), CancelMode.STOP, "", null);

        assertTrue(result.stopped());
        assertFalse(result.wasInFlight(), "no worker had it, so nothing has to wind down");
        assertEquals(RunState.STOPPED, run.state());
        assertTrue(m.gettingReady().isEmpty());
        assertNull(m.claim("w1"), "and it is never handed out afterwards");
    }

    // ---- 2. dispatched, but no tool call yet -----------------------------------------------

    @Test
    void cancelAfterDispatchBeforeTheFirstToolCall() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        WishRun run = submit(m, "a dragon");
        m.claim("w1");

        var result = m.cancel(run.wishId(), CancelMode.STOP, "", null);

        assertTrue(result.wasInFlight());
        assertEquals(0, m.activeCount(), "the slot is back within a tick, not when the agent notices");
        assertNotNull(CancelGate.refusalFor(m, "w1", "build_structure"),
                "and its very first tool call is refused");
    }

    // ---- 3. mid-tool -----------------------------------------------------------------------

    @Test
    void theSeamRefusesEveryToolExceptTheOneThatParksTheWorkerAgain() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        WishRun run = submit(m, "a dragon");
        m.claim("w1");
        run.usedTool("build_structure");
        m.cancel(run.wishId(), CancelMode.STOP, "", null);

        for (String tool : List.of("build_structure", "author_datapack", "run_command",
                "sculpt_creature", "finish_wish", "notify_player", "list_wishes", "set_status")) {
            String refusal = CancelGate.refusalFor(m, "w1", tool);
            assertNotNull(refusal, tool + " must be REFUSED, not merely informed — an agent that "
                    + "keeps calling tools is invisible except as blocks appearing after the bar went");
            assertTrue(refusal.contains("REFUSED"));
        }
        assertNull(CancelGate.refusalFor(m, "w1", "await_wish"),
                "await_wish is the way back to being useful and must always be allowed");
    }

    @Test
    void onceTheWorkerHasParkedItIsNotRefusedForever() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        WishRun run = submit(m, "a dragon");
        m.claim("w1");
        m.cancel(run.wishId(), CancelMode.STOP, "", null);

        assertNull(CancelGate.refusalFor(m, "w1", "await_wish"));
        assertNull(CancelGate.refusalFor(m, "w1", "build_structure"),
                "it obeyed; refusing it after that would wedge the agent permanently");
    }

    @Test
    void claimingNewWorkAlsoEndsTheWindDown() {
        Marshal m = Marshal.forTest(new FakeClock(0), 2);
        WishRun a = submit(m, "A");
        m.claim("w1");
        m.cancel(a.wishId(), CancelMode.STOP, "", null);
        submit(m, "B");

        assertNotNull(m.claim("w1"));
        assertNull(CancelGate.refusalFor(m, "w1", "build_structure"),
                "the refusal belongs to the stopped wish, not to the worker");
    }

    // ---- 4. after it finished ---------------------------------------------------------------

    @Test
    void cancellingAFinishedWishIsANoOpNotAnError() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        WishRun run = submit(m, "a dragon");
        m.claim("w1");
        m.finish(run.wishId());

        var result = m.cancel(run.wishId(), CancelMode.STOP, "", null);

        assertFalse(result.stopped());
        assertEquals(RunState.DONE, run.state(), "a finished wish stays finished, not re-labelled");
        // The stop adds nothing: the worker is not told "STOP — the child cancelled". It IS still
        // refused a world-changing tool, but 's reason — its wish is finished, and a
        // finished wish is not built on — which is a different sentence from a different rule.
        String refusal = CancelGate.refusalFor(m, "w1", "build_structure");
        assertTrue(refusal == null || !refusal.startsWith("STOP"),
                "a stop on a finished wish must not wind the worker down as if it cancelled: "
                        + refusal);
        assertNull(m.windingDown("w1"));
    }

    // ---- 5. one of two in flight -------------------------------------------------------------

    @Test
    void stoppingOneOfTwoLeavesTheOtherCompletelyAlone() {
        Marshal m = Marshal.forTest(new FakeClock(0), 3);
        WishRun a = submit(m, "a dragon");
        WishRun b = submit(m, "a castle");
        a.lockAnchor(new Spot("minecraft:overworld", 1, 2, 3));
        b.lockAnchor(new Spot("minecraft:overworld", 400, 70, 400));
        m.claim("w1");
        m.claim("w2");

        m.cancel(a.wishId(), CancelMode.STOP, "", null);

        assertEquals(RunState.WORKING, b.state());
        assertEquals("w2", b.workerId());
        assertEquals(new Spot("minecraft:overworld", 400, 70, 400), b.anchor());
        assertNull(CancelGate.refusalFor(m, "w2", "build_structure"),
                "w2 is working on a wish nobody stopped and must not be refused");
        assertNotNull(CancelGate.refusalFor(m, "w1", "build_structure"));
    }

    // ---- 8. idempotence ----------------------------------------------------------------------

    @Test
    void doubleCancelIsIdempotent() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        WishRun run = submit(m, "a dragon");
        m.claim("w1");

        var first = m.cancel(run.wishId(), CancelMode.STOP, "", null);
        var second = m.cancel(run.wishId(), CancelMode.STOP, "", null);

        assertTrue(first.stopped());
        assertFalse(second.stopped());
        assertTrue(second.alreadyStopped(), "a double-click on [stop] is a no-op, not an error");
        assertSame(CancelMode.STOP, run.cancelMode());
    }

    // ---- 10. redirect ------------------------------------------------------------------------

    @Test
    void redirectSaysWhereItShouldHaveGone() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        WishRun run = submit(m, "make it bigger");
        m.claim("w1");

        m.cancel(run.wishId(), CancelMode.REDIRECT, "", "the ice dragon");

        assertSame(CancelMode.REDIRECT, run.cancelMode());
        assertEquals("the ice dragon", run.redirectTo());
        String refusal = CancelGate.refusalFor(m, "w1", "sculpt_creature");
        assertTrue(refusal.contains("the ice dragon"),
                "the worker is told what he actually meant, so it does not re-ask");
        assertFalse(refusal.toLowerCase().contains("abandon it"));
    }

    // ---- 11. undo availability, computed truthfully -------------------------------------------

    @Test
    void undoIsOfferedOnlyWhenEveryToolUsedHadAnInverse() {
        assertTrue(UndoContract.undoable(List.of("build_structure", "look_around", "photograph")));
        assertFalse(UndoContract.undoable(List.of("build_structure", "run_command")),
                "run_command is 35% of all calls and is the hole; never offer an undo that lies");
        assertEquals(List.of("run_command"),
                UndoContract.blockers(List.of("build_structure", "run_command")));
    }

    @Test
    void anUnknownToolMakesUndoDisappearRatherThanLie() {
        assertFalse(UndoContract.undoable(List.of("some_tool_added_next_month")),
                "the safe default: a tool nobody classified removes the button, it does not enable it");
    }

    @Test
    void lookingAroundCanNeverBeWhyUndoIsUnavailable() {
        assertTrue(UndoContract.undoable(List.of(
                "look_around", "find_content", "list_creatures", "photograph", "set_status",
                "plan_wish", "verify_datapack", "notify_player", "finish_building", "finish_wish")));
    }

    @Test
    void stopIsAlwaysOfferedAndRedirectComesFirstWhenThereIsSomewhereToAim() {
        assertEquals(List.of(CancelMode.STOP),
                CancelOffer.offerFor(List.of("run_command"), true, false));
        assertEquals(List.of(CancelMode.REDIRECT, CancelMode.STOP),
                CancelOffer.offerFor(List.of("run_command"), true, true),
                "a wrong 'make it bigger' wants re-aiming, not a bare ✕");
        assertEquals(List.of(CancelMode.REDIRECT, CancelMode.UNDO, CancelMode.STOP),
                CancelOffer.offerFor(List.of("build_structure"), true, true));
    }

    @Test
    void undoIsNotOfferedOnAWishThatHasNotMadeAnythingYet() {
        // The moment a wish is submitted it has used no tools, so judging on tools ALONE offers an
        // undo — over a wish that has produced nothing at all. Not technically a lie (stopping it
        // does leave the world as it was), but it reads as a promise that something will be taken
        // away, and two buttons where only one means anything is worse than one honest button.
        assertEquals(List.of(CancelMode.STOP),
                CancelOffer.offerFor(List.of(), false, false));
        assertEquals(List.of(CancelMode.UNDO, CancelMode.STOP),
                CancelOffer.offerFor(List.of("build_structure"), true, false),
                "…and once it HAS built something, the undo is real and is offered");
    }

    // ---- 7. an edit: the base artifact survives ------------------------------------------------

    @Test
    void stoppingAnEditNeverRemovesTheThingBeingEdited() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        WishRun creation = submit(m, "make me a spikor");
        m.claim("w1");
        creation.made(MadeKind.CREATURE, "spikor");
        m.finish(creation.wishId());

        WishRun edit = m.submit(wish("make spikor bigger"), CHILD, "make spikor bigger");
        m.claim("w2");
        edit.usedTool("sculpt_creature");      // it edited, it did not create
        m.cancel(edit.wishId(), CancelMode.UNDO, "", null);

        assertTrue(edit.made().isEmpty(),
                "an undo reverses only what THIS wish recorded making, so Spikor is untouched");
        assertEquals(1, creation.made().size(), "and the wish that created him still owns him");
    }

    // ---- the affordances themselves ------------------------------------------------------------

    @Test
    void everyAffordanceRunsARealCommand() {
        for (CancelMode mode : CancelMode.values()) {
            String cmd = CancelOffer.command(mode, "1a2b3c4d");
            assertTrue(cmd.startsWith("/moddy "), mode + " must be a thin skin over a typed /moddy verb (#1107)");
            assertTrue(cmd.contains("1a2b3c4d"), mode + " must target THIS wish, not the newest one");
            assertFalse(CancelOffer.label(mode).isBlank());
            assertFalse(CancelOffer.hover(mode).isBlank());
        }
    }

    @Test
    void nothingAChildIsToldReadsAsAQueue() {
        // The one principle: a seven-year-old never sees a queue. Cheap to check, and it fails by
        // name so the rule cannot erode as messages are added.
        for (CancelMode mode : CancelMode.values()) {
            assertFalse(CancelOffer.readsAsAQueue(mode.saidToChild()), mode + " said to the child");
            assertFalse(CancelOffer.readsAsAQueue(CancelOffer.label(mode)), mode + " label");
            assertFalse(CancelOffer.readsAsAQueue(CancelOffer.hover(mode)), mode + " hover");
        }
        assertTrue(CancelOffer.readsAsAQueue("I'll get to it right after this one"),
                "…and the check itself really does fire");
    }
}
