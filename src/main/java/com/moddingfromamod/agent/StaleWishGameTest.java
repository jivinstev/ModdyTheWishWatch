package com.moddingfromamod.agent;

import net.minecraft.network.chat.Component;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.AwaitWishTool;
import com.moddingfromamod.marshal.CancelMode;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.safety.SafetyGrader;
import com.moddingfromamod.safety.SafetyLexicon;
import com.moddingfromamod.safety.SafetyVerdict;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishBook;
import com.moddingfromamod.wish.WishCancel;
import com.moddingfromamod.wish.WishProgress;
import com.moddingfromamod.wish.WishService;
import com.moddingfromamod.wish.WishSource;
import com.moddingfromamod.wish.WishStatus;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A wish the child has ended is never handed to an agent</b> — CS-D7 finding (5).
 *
 * <p>Nothing prunes {@link WishQueue} when a wish is stopped. A wish cancelled while it was still
 * WAITING — no worker had taken it, so there was no worker to wind down — therefore sat in the
 * queue and was handed to the next worker that woke up, because {@code AwaitWishTool} consulted
 * neither the book nor the marshal about what it had just dequeued. The child's stop took the bar
 * and the beam down within a tick and the thing got built anyway, minutes later, with the journal
 * saying {@code STOPPED} the whole time.
 *
 * <p><b>With no agent running it is worse, and that is the case a child actually meets.</b> The
 * wish waits in the queue for however long it takes a worker to appear — a restart, a re-join, the
 * next time somebody plays — so "I stopped it" holds only until an agent next wakes.
 *
 * <p>The dequeue is the right place and not merely a convenient one: it is the one point that sees
 * every path a wish can reach an agent by (its own comment names the three re-wish paths that skip
 * {@code WishService.submit}), and it is the one point where a wish has been taken off the queue
 * and nothing has been assigned — so dropping one there can neither lose a wish nor half-assign it.
 * That is the same argument the busy-worker refusal makes for sitting <i>above</i>
 * {@code queue.await}, read the other way round.
 *
 * <h2>What only Gate B can see</h2>
 *
 * <p>There is no new pure logic to unit-test: the predicate is {@link WishStatus#isFinished()},
 * which already exists and is already tested. The whole change is wiring — the real
 * {@code AwaitWishTool} reading the real {@link WishBook} for a wish the real {@link WishCancel}
 * ended — and whether it holds is a fact about four classes talking to each other.
 *
 * <p>So the negative test is the point and the two positive ones are the reason the negative one is
 * allowed to exist: both re-wish paths put a FINISHED wish back to {@code PENDING} before they
 * offer it, and a check that could not tell those apart from a stop would break the two features
 * the child uses to recover from a wrong guess — a strictly worse bug than the one being fixed.
 *
 * <h2>ONE BATCH PER TEST</h2>
 *
 * <p>These share {@link Marshal#global()}, the wish book and {@link SafetyGrader}'s injected stub,
 * and tests within a batch run concurrently while batches are strictly sequential (CLAUDE.md).
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class StaleWishGameTest {

    private record Wishing(ServerPlayer player, Wish wish) { }

    /**
     * A wish that has been acknowledged and is WAITING — no worker has taken it.
     *
     * <p>That is finding (5)'s exact state, and it is why the bug is invisible to the wind-down: a
     * wish nobody holds has no worker for {@code CancelGate} to refuse, so the queue entry is the
     * only thing left carrying it.
     */
    private static Wishing begin(GameTestHelper helper, String tag) {
        SafetyGrader.forTest(text -> SafetyVerdict.fine(SafetyVerdict.Provenance.HAIKU));
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);

        String text = "make a huge glass tower " + tag + " "
                + UUID.randomUUID().toString().substring(0, 8);
        Wish wish = WishBook.get(helper.getLevel().getServer()).add(Wish.create(text, WishSource.COMMAND,
                System.currentTimeMillis(), p.getUUID(), p.getGameProfile().name(),
                p.level().dimension().identifier().toString(), p.getX(), p.getY(), p.getZ()));
        Marshal.global().submit(wish.id(), p.getUUID(), text);
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
        WishQueue.global().clear();
        WishProgress.clearWishBar(w.wish().id());
        MockPlayerNetwork.remove(w.player());
    }

    /**
     * Drive {@link AwaitWishTool} on its own thread and hand back somewhere to poll.
     *
     * <p>A {@code @GameTest} body IS the server thread and this tool hops onto it partway through,
     * so calling it synchronously trips the bridge's own deadlock guard.
     *
     * <p>ITS OWN QUEUE, never the global one: a test parked on the global queue steals another
     * test's wish, which {@code AwaitWishTool}'s own constructor javadoc records.
     */
    private static AtomicReference<ToolResult> await(WishQueue queue, long seconds,
                                                     AtomicReference<Throwable> blew) {
        JsonObject args = new JsonObject();
        args.addProperty("timeoutSeconds", seconds);
        AtomicReference<ToolResult> out = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                out.set(new AwaitWishTool(queue).call(args));
            } catch (Throwable e) {
                blew.set(e);
            }
        }, "mfam-test-await-stale");
        t.setDaemon(true);
        t.start();
        return out;
    }

    // ── the defect ────────────────────────────────────────────────────────────────────────────

    /**
     * A wish stopped while it was still waiting is dropped at the dequeue, and the LIVE wish
     * behind it is handed over in the same call.
     *
     * <p>Two claims, and the second is why the drop LOOPS instead of returning. A stale entry is
     * not an answer to give a parked worker: returning "nothing arrived" would cost it a whole turn
     * to call back for a queue that had a real wish in it all along, and on a busy queue a stopped
     * wish would delay every wish behind it by one turn each.
     *
     * <p>It is also what makes this test independent of wall-clock time. The tool returns the
     * moment it takes the second wish, so nothing here waits out a budget — and a
     * {@code @GameTest} server ticks as fast as it can, so a test whose subject is a one-second
     * wait is a test whose tick allowance is a guess (CLAUDE.md). The earlier shape of this test
     * asserted "nothing arrived" and was marginal at 600 ticks for exactly that reason.
     */
    @GameTest(template = "empty_test", batch = "wish_stale_stopped", timeoutTicks = 1200)
    public static void aWishTheChildStoppedIsNeverHandedToAnAgent(GameTestHelper helper) {
        Wishing w = begin(helper, "stale-stopped");
        String text = w.wish().text();

        // AND NOBODY IS WORKING ON IT — the state that has no worker to wind down, which is why
        // the queue entry was the only thing still carrying this wish.
        helper.assertTrue(Marshal.global().working().stream()
                        .noneMatch(r -> w.wish().id().equals(r.wishId())),
                Component.literal("the fixture assigned a worker, so this is not finding (5)'s case"));
        helper.assertTrue(WishCancel.stop(w.player(), w.wish().shortId(), CancelMode.STOP, "")
                        .stopped(),
                Component.literal("the stop never ran, so nothing below is being asserted"));
        Wish stopped = WishBook.get(helper.getLevel().getServer()).byId(w.wish().id()).orElse(null);
        helper.assertTrue(stopped != null && stopped.status() == WishStatus.STOPPED,
                Component.literal("the book does not say STOPPED, so the dequeue has nothing to read: "
                        + (stopped == null ? "gone" : stopped.status().name())));

        // A SECOND, LIVE WISH BEHIND IT — the same child, still waiting, nobody's yet.
        String liveText = "make a friendly golem " + UUID.randomUUID().toString().substring(0, 8);
        Wish live = WishBook.get(helper.getLevel().getServer()).add(Wish.create(liveText, WishSource.COMMAND,
                System.currentTimeMillis(), w.player().getUUID(),
                w.player().getGameProfile().name(),
                w.player().level().dimension().identifier().toString(),
                w.player().getX(), w.player().getY(), w.player().getZ()));
        helper.assertValueEqual(live.status(), WishStatus.PENDING, Component.literal("the second wish's status"));

        // In queue order: the stopped one first, so the tool must get past it to reach the other.
        WishQueue queue = new WishQueue();
        helper.assertTrue(queue.offer(w.wish()), Component.literal("the test queue refused the stopped wish"));
        helper.assertTrue(queue.offer(live), Component.literal("the test queue refused the live wish"));

        AtomicReference<Throwable> blew = new AtomicReference<>();
        AtomicReference<ToolResult> out = await(queue, 5L, blew);

        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("await_wish threw: " + blew.get()));
            ToolResult result = out.get();
            helper.assertTrue(result != null, Component.literal("await_wish has not returned yet"));
            helper.assertTrue(!result.isError(), Component.literal("await_wish failed: " + result.text()));

            // 1 — THE AGENT NEVER SEES THE STOPPED ONE. The child ended it; it is over.
            helper.assertTrue(!result.text().contains(text),
                    Component.literal("a wish the child stopped was handed to an agent anyway, so the stop held "
                            + "only until a worker next woke up: " + result.text()));

            // 2 — AND THE ONE BEHIND IT IS NOT LOST, in the same call.
            helper.assertTrue(result.text().contains(liveText),
                    Component.literal("the live wish behind the stopped one was not handed over, so a stale queue "
                            + "entry costs every wish behind it a turn: " + result.text()));
        });
    }

    /**
     * <b>, the live report's own shape</b>: {@code /wish undo} 4 s after the wish, while
     * {@code await_wish} had already dequeued it and was waiting on its safety grade. The book check
     * at the dequeue had passed; the stop landed in the grade; and the old submit-then-assign
     * re-opened the stopped wish as a fresh run and handed it over — the hat landed 24 s after the
     * child read "I stopped and put it back!".
     *
     * <p>Driven through the real {@link WishCancel#stop} (what {@code /moddy undo} and the watch's
     * Stop button call) and the real {@link AwaitWishTool}, with the grade held open by the stub until
     * the stop has landed, so the window is hit every run rather than by luck.
     */
    @GameTest(template = "empty_test", batch = "wish_stale_stopped_in_grade", timeoutTicks = 1200)
    public static void aWishStoppedWhileItsGradeRunsIsNeverHandedOver(GameTestHelper helper) {
        Wishing w = begin(helper, "stopped-in-grade");
        String text = w.wish().text();
        java.util.concurrent.CountDownLatch inGrade = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        SafetyGrader.forTest(t -> {
            if (t.contains("stopped-in-grade")) {
                inGrade.countDown();
                try {
                    release.await(20, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return SafetyVerdict.fine(SafetyVerdict.Provenance.HAIKU);
        });
        WishQueue queue = new WishQueue();
        helper.assertTrue(queue.offer(w.wish()), Component.literal("the test queue refused the wish"));
        String worker = "gt-1085-" + UUID.randomUUID();
        AtomicReference<Throwable> blew = new AtomicReference<>();
        AtomicReference<ToolResult> out = new AtomicReference<>();
        JsonObject args = new JsonObject();
        args.addProperty("timeoutSeconds", 5L);
        Thread t = new Thread(() -> {
            AgentContext.bind(worker);
            try {
                out.set(new AwaitWishTool(queue).call(args));
            } catch (Throwable e) {
                blew.set(e);
            } finally {
                AgentContext.unbind();
            }
        }, "mfam-test-await-1085");
        t.setDaemon(true);
        t.start();
        java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();

        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("await_wish threw: " + blew.get()));
            helper.assertTrue(inGrade.getCount() == 0, Component.literal("await_wish has not reached the grade yet"));
            if (stopped.compareAndSet(false, true)) {
                // /moddy undo, on the server thread, while the grade is still out.
                WishCancel.Outcome o = WishCancel.stop(w.player(), w.wish().shortId(), CancelMode.UNDO, "");
                release.countDown();
                helper.assertTrue(o.stopped(), Component.literal("the stop never ran, so nothing is being asserted"));
            }
            ToolResult result = out.get();
            helper.assertTrue(result != null, Component.literal("await_wish has not returned yet"));
            helper.assertTrue(!result.text().contains(text),
                    Component.literal("#1085: a wish the child stopped during its grade was handed to an agent: "
                            + result.text()));
            var run = Marshal.global().run(w.wish().id());
            helper.assertTrue(run != null && run.state() == com.moddingfromamod.marshal.RunState.STOPPED,
                    Component.literal("#1085: the stopped wish was re-opened as a live run: " + run));
            helper.assertTrue(Marshal.global().runForWorker(worker) == null,
                    Component.literal("#1085: the worker was bound to a stopped wish"));
            helper.assertTrue(Marshal.global().windingDown(worker) != null,
                    Component.literal("#1085: the worker is not winding down, so its next tool call would run"));
        });
    }

    // ── /moddy no (undone, never back) and /moddy redirect (back) ───────────────────────────────────────

    /**
     * {@code /moddy no}: a refused wish is UNDONE — settled STOPPED — and must NEVER reach
     * an agent again, even from a queue entry left over from before.
     *
     * <p>Until this test asserted the opposite (a refused wish was {@code PENDING} again and
     * had to reach the agent). made 👎 just undo: live, a child pressed it twice and the
     * wish ran three times. So the dequeue's stale-wish check is now what keeps an undone wish from
     * coming back through a stale entry, and the live wish behind it still goes in the same call.
     */
    @GameTest(template = "empty_test", batch = "wish_stale_rejected", timeoutTicks = 1200)
    public static void aRejectedWishIsNeverHandedToAnAgent(GameTestHelper helper) {
        Wishing w = begin(helper, "stale-rejected");
        UUID id = w.wish().id();

        // The agent granted it: the run is DONE and the book says so.
        helper.assertTrue(Marshal.global().finish(id), Component.literal("the fixture's run was never finished"));
        WishBook.get(helper.getLevel().getServer()).complete(id, WishStatus.DONE, "");

        helper.assertValueEqual(WishService.reject(w.player(), "that's not right"),
                WishService.Outcome.UNDONE, Component.literal("reject outcome"));
        Wish undone = WishBook.get(helper.getLevel().getServer()).byId(id).orElse(null);
        helper.assertTrue(undone != null && undone.status() == WishStatus.STOPPED,
                Component.literal("a refused wish must settle STOPPED, not go back on the list: "
                        + (undone == null ? "gone" : undone.status().name())));
        helper.assertTrue(Marshal.global().liveFor(w.player().getUUID()).stream()
                        .noneMatch(r -> id.equals(r.wishId())),
                Component.literal("a refused wish was given a new live run, so it would be granted again"));

        String liveText = "make a friendly golem " + UUID.randomUUID().toString().substring(0, 8);
        Wish live = WishBook.get(helper.getLevel().getServer()).add(Wish.create(liveText, WishSource.COMMAND,
                System.currentTimeMillis(), w.player().getUUID(),
                w.player().getGameProfile().name(),
                w.player().level().dimension().identifier().toString(),
                w.player().getX(), w.player().getY(), w.player().getZ()));
        WishQueue queue = new WishQueue();
        helper.assertTrue(queue.offer(w.wish()), Component.literal("the test queue refused the stale entry"));
        helper.assertTrue(queue.offer(live), Component.literal("the test queue refused the live wish"));
        AtomicReference<Throwable> blew = new AtomicReference<>();
        AtomicReference<ToolResult> out = await(queue, 5L, blew);

        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("await_wish threw: " + blew.get()));
            ToolResult result = out.get();
            helper.assertTrue(result != null, Component.literal("await_wish has not returned yet"));
            helper.assertTrue(!result.text().contains(w.wish().text()),
                    Component.literal("a wish the child undid with 👎 was handed to an agent anyway: " + result.text()));
            helper.assertTrue(result.text().contains(liveText),
                    Component.literal("the live wish behind it was not handed over: " + result.text()));
        });
    }

    /**
     * {@code /moddy redirect}: the same, for the path the safety layer sends a child down.
     *
     * <p>A re-aimed wish was STOPPED a moment earlier — {@code WishCancel.stop} writes that status
     * before {@code reaim} re-opens it — so the ordering of those two writes is load-bearing and
     * nothing else asserts it. A check that saw the STOPPED write and not the {@code PENDING} one
     * would turn every redirect into a silent refusal.
     */
    @GameTest(template = "empty_test", batch = "wish_stale_reaimed", timeoutTicks = 600)
    public static void aReAimedWishIsStillHandedToAnAgent(GameTestHelper helper) {
        Wishing w = begin(helper, "stale-reaimed");
        String aim = "the little wooden house by the lake";
        helper.assertTrue(SafetyLexicon.shared().check(aim).isEmpty(),
                Component.literal("the lexicon has an opinion about this re-aim, so the wish is never re-opened: "
                        + aim));
        helper.assertTrue(WishCancel.stop(w.player(), w.wish().shortId(), CancelMode.REDIRECT, aim)
                        .stopped(),
                Component.literal("the re-aim never ran"));
        Wish reopened = WishBook.get(helper.getLevel().getServer()).byId(w.wish().id()).orElse(null);
        helper.assertTrue(reopened != null && reopened.status() == WishStatus.PENDING,
                Component.literal("the re-aimed wish is not PENDING, so the dequeue will read it as finished: "
                        + (reopened == null ? "gone" : reopened.status().name())));

        WishQueue queue = new WishQueue();
        helper.assertTrue(queue.offer(reopened), Component.literal("the test queue refused the wish"));
        AtomicReference<Throwable> blew = new AtomicReference<>();
        AtomicReference<ToolResult> out = await(queue, 1L, blew);

        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("await_wish threw: " + blew.get()));
            ToolResult result = out.get();
            helper.assertTrue(result != null, Component.literal("await_wish has not returned yet"));
            helper.assertTrue(result.text().contains(aim),
                    Component.literal("a re-aimed wish never reached the agent, so every redirect is now a silent "
                            + "refusal: " + result.text()));
        });
    }
}
