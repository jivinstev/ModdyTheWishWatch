package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.CancelGate;
import com.moddingfromamod.marshal.CancelMode;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.RunState;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.safety.SafetyGrader;
import com.moddingfromamod.safety.SafetyLexicon;
import com.moddingfromamod.safety.SafetyVerdict;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A re-opened wish is still one the child can stop</b> — CS-D7.
 *
 * <p>Two of the three re-wish paths keep the wish id and throw away only the attempt.
 * {@code /moddy redirect} stops the run and re-opens the wish carrying what the child actually
 * meant. ({@code /moddy no} used to send a finished wish back to the agent's list too;
 * it only undoes, and its test here pins that.) Neither mints a new wish, deliberately — the id is what the bar, the journal and {@code /moddy stop} all
 * name it by.
 *
 * <p>Until CS-D7 the marshal had no way to express that: {@code submit} handed back the run it
 * already had for that id, and that run was <b>finished</b>. Every consequence was quiet.
 * {@code assign} refuses a finished run and returns null; {@code liveFor} excludes it; nothing
 * downstream reads either. So the re-opened wish was narrated, built and finished by a worker the
 * marshal had no record of, and <b>{@code /moddy stop} on it afterwards found nothing at all</b> —
 * the child got "nothing to stop" about the wish whose bar he was looking at.
 *
 * <p>It matters now because the safety layer makes the redirect a path a child is <i>sent</i> down:
 * a refused or redirected wish is one he re-aims. A re-aimed wish he cannot stop has lost the one
 * control the watch promises him. Recorded as a stated limit in the CS-D2 ledger row and closed
 * here.
 *
 * <h2>What only Gate B can see</h2>
 *
 * <p>{@code ReopenAfterFinishTest} settles the marshal semantics in milliseconds — a finished run
 * is replaced rather than handed back, one wish takes one slot, the wind-down still describes the
 * attempt that was stopped. What it cannot reach is the wiring: that the <b>real</b>
 * {@link WishCancel#stop} on a real player with a real {@link WishBook} leaves the marshal in a
 * state where a worker can be recorded, {@code liveFor} answers, and a second real stop actually
 * stops it and writes {@code STOPPED}. Those four are three different classes talking to each
 * other, and there is no cheaper place to ask.
 *
 * <h2>ONE BATCH PER TEST</h2>
 *
 * <p>These share {@code Marshal.global()}, the wish book and {@link SafetyGrader}'s injected stub,
 * and tests within a batch run concurrently while batches are strictly sequential (CLAUDE.md). A
 * batch each is real isolation.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WishReopenGameTest {

    /** The worker that was granting the attempt the child ends. */
    private static final String FIRST_WORKER = "cs-d7-worker-1";

    /** The worker that picks the re-opened wish up afterwards. */
    private static final String SECOND_WORKER = "cs-d7-worker-2";

    private record Wishing(ServerPlayer player, Wish wish) { }

    /**
     * A wish in the state {@code WishService.submit} leaves it in, already taken by a worker.
     *
     * <p>The run has to be LIVE and ASSIGNED or there is nothing to end: {@link WishCancel#stop}
     * finds the wish through {@code marshal.liveFor}, and the wind-down refusal only exists for a
     * wish a worker actually held.
     */
    private static Wishing begin(GameTestHelper helper, String tag, String worker) {
        // A FINE verdict for everything, because `reaim` starts a grade fire-and-forget and the
        // real grader is a model call. Cleared when the test ends, pass or fail.
        SafetyGrader.forTest(text -> SafetyVerdict.fine(SafetyVerdict.Provenance.HAIKU));
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);

        String text = "make it bigger " + tag + " " + UUID.randomUUID().toString().substring(0, 8);
        Wish wish = WishBook.get(p.getServer()).add(Wish.create(text, WishSource.COMMAND,
                System.currentTimeMillis(), p.getUUID(), p.getGameProfile().getName(),
                p.level().dimension().location().toString(), p.getX(), p.getY(), p.getZ()));
        Marshal.global().submit(wish.id(), p.getUUID(), text);
        Marshal.global().assign(worker, wish.id());
        WishProgress.startThinking(p, wish.id(), text);

        Wishing w = new Wishing(p, wish);
        tidyWhenTheTestEnds(helper, w);
        return w;
    }

    /**
     * Put everything back when the TEST ends — pass or fail.
     *
     * <p>⚠ A listener rather than a {@code finally} inside {@code succeedWhen}, which re-runs every
     * tick until it stops throwing: a {@code finally} would fire on the first poll and clear the
     * process-wide grader stub before the test had begun.
     */
    private static void tidyWhenTheTestEnds(GameTestHelper helper, Wishing w) {
        helper.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {
            }

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                end(w);
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                end(w);
            }

            @Override
            public void testAddedForRerun(GameTestInfo before, GameTestInfo after,
                                          GameTestRunner runner) {
                end(w);
            }
        });
    }

    private static void end(Wishing w) {
        SafetyGrader.forTest(null);
        try {
            Marshal.global().cancel(w.wish().id(), CancelMode.STOP, "", "");
        } catch (RuntimeException ignored) {
            // Already finished, which is the usual case here.
        }
        Marshal.global().woundDown(FIRST_WORKER);
        Marshal.global().woundDown(SECOND_WORKER);
        // `WishCancel.reaim` offers the re-opened wish to the GLOBAL queue, which nothing consumes
        // in a gate run. A wish left sitting on a shared queue is exactly the cross-test state a
        // batch split exists to prevent.
        com.moddingfromamod.agent.WishQueue.global().clear();
        WishProgress.clearWishBar(w.wish().id());
        MockPlayerNetwork.remove(w.player());
    }

    // ── /moddy redirect ───────────────────────────────────────────────────────────────────────

    /**
     * The CS-D2 defect, end to end: re-aim a wish, then stop the thing you re-aimed.
     *
     * <p>Four claims, in the order the child produces them, and each fails on its own: the marshal
     * can record who took the re-opened wish, the child's own list of live wishes contains it,
     * {@code /moddy stop} finds and ends it, and the journal says {@code STOPPED}. The fifth is the
     * regression guard on the other side of the same change — the worker still mid-inference on the
     * attempt he ended must still be refused, and still told it was a re-aim rather than an
     * abandonment.
     */
    @GameTest(template = "empty_test", batch = "wish_reopen_redirect", timeoutTicks = 400)
    public static void aReAimedWishCanStillBeStopped(GameTestHelper helper) {
        Wishing w = begin(helper, "reopen-redirect", FIRST_WORKER);
        String aim = "the stone tower by the lake";
        // A lexicon hit would make `reaim` return early, so the test would be asserting that
        // nothing happened to nothing.
        helper.assertTrue(SafetyLexicon.shared().check(aim).isEmpty(),
                Component.literal("the lexicon has an opinion about this re-aim, so the wish is never re-opened: "
                        + aim));

        WishCancel.Outcome redirect = WishCancel.stop(w.player(), w.wish().shortId(),
                CancelMode.REDIRECT, aim);
        helper.assertTrue(redirect.stopped(),
                Component.literal("the re-aim never ran, so nothing below is being asserted"));
        helper.assertValueEqual(redirect.mode(), CancelMode.REDIRECT, Component.literal("cancel mode"));

        Marshal marshal = Marshal.global();
        UUID id = w.wish().id();

        // 1 — THE MARSHAL CAN RECORD WHO TOOK IT. `await_wish` calls exactly this and ignores the
        // answer, which is why null here was invisible for as long as it was.
        WishRun taken = marshal.assign(SECOND_WORKER, id);
        helper.assertTrue(taken != null,
                Component.literal("assign returned null for the re-aimed wish, so it is granted by a worker the "
                        + "marshal has no record of"));
        helper.assertValueEqual(taken.state(), RunState.WORKING, Component.literal("re-opened run state"));
        helper.assertTrue(taken.text().contains(aim),
                Component.literal("the re-opened run is not carrying the re-aim: " + taken.text()));

        // 2 — AND IT IS ONE OF HIS LIVE WISHES, which is the list /moddy stop picks from.
        helper.assertTrue(marshal.liveFor(w.player().getUUID()).stream()
                        .anyMatch(r -> id.equals(r.wishId())),
                Component.literal("the re-aimed wish is not among the child's live wishes, so /moddy stop will tell "
                        + "him there is nothing to stop"));

        // 3 — SO THE REAL STOP FINDS IT AND ENDS IT. Same entry point the [stop] affordance and
        // the `/moddy stop` command both run.
        WishCancel.Outcome stopped = WishCancel.stop(w.player(), w.wish().shortId(),
                CancelMode.STOP, "");
        helper.assertTrue(stopped.stopped(),
                Component.literal("the child could not stop the wish he had just re-aimed — this is the control the "
                        + "watch promises him"));
        helper.assertValueEqual(taken.state(), RunState.STOPPED, Component.literal("run state after the stop"));
        helper.assertTrue(marshal.liveFor(w.player().getUUID()).stream()
                        .noneMatch(r -> id.equals(r.wishId())),
                Component.literal("the wish is still live after being stopped"));

        // 4 — AND THE RECORD SAYS SO, which is what a grown-up reading the journal sees.
        Wish after = WishBook.get(helper.getLevel().getServer()).byId(id).orElse(null);
        helper.assertTrue(after != null, Component.literal("the wish vanished from the book"));
        helper.assertValueEqual(after.status(), WishStatus.STOPPED, Component.literal("wish status"));

        // 5 — THE OTHER SIDE OF THE SAME CHANGE. The worker that was granting the attempt he ended
        // is mid-inference and finds out on its next tool call; re-opening the wish id must not be
        // able to substitute the live run into that refusal.
        String refusal = CancelGate.refusalFor(marshal, FIRST_WORKER, "build_structure");
        helper.assertTrue(refusal != null,
                Component.literal("the worker granting the attempt the child ended was not refused at all"));
        helper.assertTrue(refusal.contains("aimed at something else"),
                Component.literal("the refusal lost the sentence that says a redirect is not an abandonment: "
                        + refusal));
        helper.succeed();
    }

    // ── /moddy no ─────────────────────────────────────────────────────────────────────────────

    /**
     * {@code /moddy no} on a wish that is STILL BEING MADE. It used to re-open a finished
     * wish for the agent, which is why it lived beside the redirect here; since 👎 became
     * just undo it re-opens nothing — and on a live wish it must STOP the run first, or the worker
     * keeps building into the hole the undo makes and the wish is "undone" while still going up.
     */
    @GameTest(template = "empty_test", batch = "wish_reopen_rejection", timeoutTicks = 400)
    public static void aRejectedLiveWishIsStoppedAndNotReopened(GameTestHelper helper) {
        Wishing w = begin(helper, "reopen-rejection", FIRST_WORKER);
        UUID id = w.wish().id();
        Marshal marshal = Marshal.global();
        WishRun taken = marshal.run(id);
        helper.assertTrue(taken != null && taken.state().isLive(), Component.literal("the fixture's run is not live"));

        WishService.Outcome outcome = WishService.reject(w.player(), "that's not right");
        helper.assertValueEqual(outcome, WishService.Outcome.UNDONE, Component.literal("reject outcome"));

        // 1 — THE RUN IS OVER, not replaced: nothing live for this wish, nothing waiting.
        helper.assertValueEqual(taken.state(), RunState.STOPPED, Component.literal("run state after the 👎"));
        helper.assertTrue(marshal.run(id) == taken,
                Component.literal("a 👎 replaced the run with a new one, which is a retry by another name"));
        helper.assertTrue(marshal.liveFor(w.player().getUUID()).stream()
                        .noneMatch(r -> id.equals(r.wishId())),
                Component.literal("the wish is still live after the child said it was wrong"));

        // 2 — THE WORKER THAT WAS MAKING IT IS REFUSED on its next tool call.
        helper.assertTrue(CancelGate.refusalFor(marshal, FIRST_WORKER, "build_structure") != null,
                Component.literal("the worker granting the wish he undid was not refused"));

        // 3 — THE RECORD SAYS STOPPED, carrying the undo marker.
        Wish after = WishBook.get(helper.getLevel().getServer()).byId(id).orElse(null);
        helper.assertTrue(after != null, Component.literal("the wish vanished from the book"));
        helper.assertValueEqual(after.status(), WishStatus.STOPPED, Component.literal("wish status"));
        helper.assertTrue(WishRejection.alreadyRejected(after),
                Component.literal("the book's result lost the undo marker: " + after.result()));
        helper.succeed();
    }
}
