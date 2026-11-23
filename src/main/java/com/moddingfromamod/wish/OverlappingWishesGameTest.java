package com.moddingfromamod.wish;

import com.moddingfromamod.testing.gt.GameTestCompat;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.FinishBuildingTool;
import com.moddingfromamod.agent.tools.FinishWishTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A second wish must never reset the first mid-grant</b>.
 *
 * <p>Live, 2026-09-26: one child's wish had called {@code finish_building}; a second wish of his
 * started, {@code WishProgress.started} re-armed "his" {@code BuildGate} — the gate was keyed by
 * PLAYER — and the first wish's {@code finish_wish} was refused as though it had never built. The
 * agent did the only thing it was told to: called {@code finish_building} again, and the child was
 * told twice that it was built. Every gate is now keyed by WISH.
 *
 * <p>The real tools, in the order the live server saw them: A builds, B starts, A finishes, B
 * builds and finishes. Once for one child with two wishes, once for two children. Each wish's
 * {@code finish_wish} must be accepted after its own {@code finish_building}, and each wish's
 * celebration must reach its child exactly once. Own batches: both drive {@code Marshal.global()}.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class OverlappingWishesGameTest {

    @GameTest(template = "empty_test", batch = "overlap937one", timeoutTicks = 1000000)
    public static void oneChildsSecondWishLeavesTheFirstWishsGateAlone(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "mfam-ov-one");
        overlap(helper, child, child);
    }

    @GameTest(template = "empty_test", batch = "overlap937two", timeoutTicks = 1000000)
    public static void twoChildrensOverlappingWishesEachFinishOnce(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer first = MockPlayerNetwork.inLevel(helper, "mfam-ov-a");
        ServerPlayer second = MockPlayerNetwork.inLevel(helper, "mfam-ov-b");
        overlap(helper, first, second);
    }

    /**
     * <b>A timed-out overlap must leave neither wish in flight</b> (the hole an earlier issue's hygiene left).
     *
     * <p>{@code TestHygiene} finishes the runs a test's BODY put in flight. Wish B is not one of them:
     * the driver thread submits it later, after A's {@code finish_building}. So when the 25 s deadline
     * fails the test while the driver is still on A (a crowded run: the release smoke's batches went at
     * 240 ticks/s and these timed out four times on 2026-11-15), B arrives after everything that tidies
     * has already run, is assigned and WORKING, and stays so for the rest of the server's life. A live
     * run nobody owns is "the single wish in flight" to every later test: in run
     * 20261115T065331Z-fb86fa a timed-out overlap's wish stamped ProvenanceWiring's species manifest
     * ("got: build me a castle") and was followed by VanillaWatch, PictureReplay, BuildSpots and
     * HomeOps failures; 20261115T072432Z-17ed0e is the same cascade in a 54-test run.
     *
     * <p>Staged here exactly: the test "ends" (its cleanup runs) before the driver has submitted B.
     * The order is not a race -- the driver's first tool call hops onto the server thread, which is
     * busy running this body, so nothing of B can exist until the body returns.
     */
    @GameTest(template = "empty_test", batch = "overlap937ended", timeoutTicks = 1000000)
    public static void anOverlapThatEndsEarlyLeavesNeitherWishInFlight(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "mfam-ov-end");
        AtomicReference<String> bAfterEnd = new AtomicReference<>();
        // Asked the moment B exists, NOT when the driver is done: the driver goes on to finish B
        // itself, so a check at the end passes while B sat WORKING through whatever ran meanwhile --
        // in a real run, the next batches.
        Overlap ov = start(helper, child, child, o -> {
            var r = Marshal.global().run(o.b.get().id());
            bAfterEnd.set(r == null ? "gone" : (r.state().isLive() ? "LIVE " : "") + r.state());
        });
        MockPlayerNetwork.afterTest(helper, ov::end);
        ov.end();   // what the deadline's failure does to the real tests, before B exists

        long startedMs = System.currentTimeMillis();
        helper.succeedWhen(() -> {
            if (!ov.done.get() && System.currentTimeMillis() - startedMs > 25_000L) {
                helper.testInfo.fail(GameTestCompat.assertion("the driver did not finish in 25 s of wall clock"));
                return;
            }
            helper.assertTrue(ov.done.get(), Component.literal("waiting for the driver"));
            Wish b = ov.b.get();
            try {
                helper.assertTrue(b != null, Component.literal("setup: the driver never submitted wish B: " + ov.died.get()));
                helper.assertFalse(live(ov.a), Component.literal("wish A outlived its test"));
                helper.assertTrue(bAfterEnd.get() != null && !bAfterEnd.get().startsWith("LIVE"),
                        Component.literal("wish B, submitted after its test had ended, was " + bAfterEnd.get()
                                + " -- every test that ran meanwhile would read it as the wish in flight"));
                helper.assertFalse(live(b), Component.literal("wish B outlived its test"));
                helper.succeed();
            } catch (GameTestAssertException e) {
                helper.testInfo.fail(GameTestCompat.failure(e));
            }
        });
    }

    private static boolean live(Wish w) {
        var r = Marshal.global().run(w.id());
        return r != null && r.state().isLive();
    }

    /** One overlap in flight: its two wishes, its driver's results, and what ends it. */
    private static final class Overlap {
        final Wish a;
        final String sayA;
        final String sayB;
        final ServerPlayer childA;
        final ServerPlayer childB;
        final AtomicReference<Wish> b = new AtomicReference<>();
        final AtomicReference<String> finishA = new AtomicReference<>("");
        final AtomicReference<String> finishB = new AtomicReference<>("");
        final AtomicReference<Throwable> died = new AtomicReference<>();
        final AtomicBoolean done = new AtomicBoolean();
        /** Set, on the server thread, when the test has ended however it ended. */
        final AtomicBoolean ended = new AtomicBoolean();

        Overlap(Wish a, String sayA, String sayB, ServerPlayer childA, ServerPlayer childB) {
            this.a = a;
            this.sayA = sayA;
            this.sayB = sayB;
            this.childA = childA;
            this.childB = childB;
        }

        /** The test is over: finish both wishes. Runs on the server thread (a GameTest listener). */
        void end() {
            ended.set(true);
            Marshal.global().finish(a.id());
            Wish w = b.get();
            if (w != null) {
                Marshal.global().finish(w.id());
            }
            WishCapture.forgetForTest(childA.getUUID());
            WishCapture.forgetForTest(childB.getUUID());
        }
    }

    private static Overlap start(GameTestHelper helper, ServerPlayer childA, ServerPlayer childB) {
        return start(helper, childA, childB, o -> { });
    }

    private static Overlap start(GameTestHelper helper, ServerPlayer childA, ServerPlayer childB,
                                 java.util.function.Consumer<Overlap> onceBExists) {
        var server = helper.getLevel().getServer();
        long tag = System.nanoTime();
        String sayA = "Your castle is ready! " + tag;
        String sayB = "Your dragon is here! " + tag;
        String workerA = "gt-overlap-a-" + tag;
        String workerB = "gt-overlap-b-" + tag;

        Wish a = WishBook.get(server).add(Wish.create("build me a castle", WishSource.TYPED,
                System.currentTimeMillis(), childA.getUUID(), "Steve", "minecraft:overworld", 0, 64, 0));
        Marshal.global().submit(a.id(), childA.getUUID(), a.text());
        Marshal.global().assign(workerA, a.id());
        WishProgress.started(childA, a);
        Overlap ov = new Overlap(a, sayA, sayB, childA, childB);

        // OFF THE SERVER THREAD — every tool hops onto it and AgentBridge refuses a re-entrant hop.
        Thread t = new Thread(() -> {
            try {
                // 1 — wish A is built and its child is told.
                AgentContext.with(workerA, () -> {
                    new FinishBuildingTool().call(reveal(sayA, "gt_overlap_a_" + tag));
                    return null;
                });
                WishCapture.forgetForTest(childA.getUUID());

                // 2 — wish B arrives while A is still between finish_building and finish_wish.
                server.submit(() -> {
                    Wish b = WishBook.get(server).add(Wish.create("make me a dragon",
                            WishSource.TYPED, System.currentTimeMillis(), childB.getUUID(),
                            "Steve", "minecraft:overworld", 8, 64, 0));
                    Marshal.global().submit(b.id(), childB.getUUID(), b.text());
                    Marshal.global().assign(workerB, b.id());
                    WishProgress.started(childB, b);
                    ov.b.set(b);
                    // The test already ended (its deadline failed it while this driver was on A), so
                    // end() ran before B existed. Same thread as end(): one of the two always sees B.
                    if (ov.ended.get()) {
                        Marshal.global().finish(b.id());
                    }
                }).join();
                onceBExists.accept(ov);

                // 3 — A finishes. Before this was "Not yet — you haven't called finish_building".
                AgentContext.with(workerA, () -> {
                    ov.finishA.set(text(new FinishWishTool().call(finish(a.shortId()))));
                    return null;
                });

                // 4 — B is built and finishes on its own gate.
                AgentContext.with(workerB, () -> {
                    new FinishBuildingTool().call(reveal(sayB, "gt_overlap_b_" + tag));
                    WishCapture.forgetForTest(childB.getUUID());
                    ov.finishB.set(text(new FinishWishTool().call(finish(ov.b.get().shortId()))));
                    return null;
                });
            } catch (Throwable e) {
                ov.died.set(e);
            } finally {
                ov.done.set(true);
            }
        }, "overlap-937-gametest");
        t.setDaemon(true);
        t.start();
        return ov;
    }

    private static void overlap(GameTestHelper helper, ServerPlayer childA, ServerPlayer childB) {
        Overlap ov = start(helper, childA, childB);
        // Counted now, and still "before": the driver's first tool call waits for this body to return.
        int beforeA = count(MockPlayerNetwork.chat(childA), ov.sayA);
        int beforeB = count(MockPlayerNetwork.chat(childB), ov.sayB);
        // However the test ends -- the wall-clock deadline below included, whose early return used to
        // skip the only cleanup there was (it lived in the verdict's `finally`, and only when done).
        MockPlayerNetwork.afterTest(helper, ov::end);

        long startedMs = System.currentTimeMillis();
        helper.succeedWhen(() -> {
            // Wall clock, not ticks: a GameTest server ticks as fast as it can (see OneEndingGameTest).
            if (!ov.done.get() && System.currentTimeMillis() - startedMs > 25_000L) {
                helper.testInfo.fail(GameTestCompat.assertion("the two overlapping wishes did not finish in 25 s of wall clock"));
                return;
            }
            helper.assertTrue(ov.done.get(), Component.literal("waiting for both wishes"));
            try {
                helper.assertTrue(ov.died.get() == null, Component.literal("the driver thread died: " + ov.died.get()));
                helper.assertTrue(ov.finishA.get().contains("ended as DONE"),
                        Component.literal("wish A's finish_wish, after its OWN finish_building, once wish B had started: "
                                + ov.finishA.get()));
                helper.assertTrue(ov.finishB.get().contains("ended as DONE"),
                        Component.literal("wish B's finish_wish after its own finish_building: " + ov.finishB.get()));
                helper.assertValueEqual(count(MockPlayerNetwork.chat(childA), ov.sayA) - beforeA, 1,
                        Component.literal("times wish A's child was told it was built"));
                helper.assertValueEqual(count(MockPlayerNetwork.chat(childB), ov.sayB) - beforeB, 1,
                        Component.literal("times wish B's child was told it was built"));
                helper.succeed();
            } catch (GameTestAssertException e) {
                // ONCE THE DRIVER IS DONE, AN ASSERTION IS A VERDICT, NOT "NOT YET".
                // succeedWhen retries a thrown assertion every tick until timeoutTicks runs out,
                // and this test's budget is a million ticks (its real deadline is the wall clock
                // above) -- so a deterministic failure was retried for 81 minutes and read as a
                // hang. Fail it now, with the message it would have died with.
                helper.testInfo.fail(GameTestCompat.failure(e));
            }
        });
    }

    private static JsonObject reveal(String say, String label) {
        answerTheCamera(label);
        JsonObject o = new JsonObject();
        o.addProperty("say", say);
        o.addProperty("label", label);
        return o;
    }

    /** Write the photograph a client would, so finish_building's camera wait ends (see OneEndingGameTest). */
    private static void answerTheCamera(String label) {
        com.moddingfromamod.testing.FakeCamera.answer(label);
    }


    private static int count(List<String> lines, String needle) {
        return (int) lines.stream().filter(l -> l.contains(needle)).count();
    }

    private static JsonObject finish(String shortId) {
        JsonObject o = new JsonObject();
        o.addProperty("id", shortId);
        o.addProperty("verdict", "done");
        o.addProperty("note", "Built.");
        return o;
    }

    private static String text(ToolResult r) {
        return r == null ? "" : String.valueOf(r.text());
    }
}
