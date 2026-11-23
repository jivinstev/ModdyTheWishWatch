package com.moddingfromamod.wish;

import com.moddingfromamod.testing.gt.GameTestCompat;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.FinishBuildingTool;
import com.moddingfromamod.agent.tools.FinishWishTool;
import com.moddingfromamod.agent.tools.PlanWishTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>One wish, one ending — measured in the child's chat</b>.
 *
 * <p>Live, 2026-09-24, "make it daytime forever": nine chat lines — two plans in two wordings, and
 * then TWICE a bold "built", a bold "granted" and "Want to change it?". Two defects, both pinned
 * here by counting the lines a real player was sent rather than by reading a tool's result:
 *
 * <ol>
 *   <li><b>Nothing stopped a second finish cycle.</b> After {@code finish_wish} released the run,
 *       {@code finish_building} still announced (to every online player) and re-armed
 *       {@code BuildGate}, and {@code finish_wish} never asked whether the wish had already ended.
 *       The second cycle below must add <b>zero</b> lines.</li>
 *   <li><b>Every grant celebrated twice by design</b> — {@code built} and {@code granted} were both
 *       bold "it's done!" lines. The first cycle below must celebrate once, and a re-plan must not
 *       read out a second plan.</li>
 * </ol>
 *
 * <p>The real tools, on a real mock player that records its chat
 * ({@link MockPlayerNetwork#chat}). Its own batch: it drives {@code Marshal.global()} and a named
 * worker.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class OneEndingGameTest {

    private static final String SAY = "The sun will never set again!";
    private static final String NOTE = "Your sky is locked to daytime forever.";

    @GameTest(template = "empty_test", batch = "oneEnding770", timeoutTicks = 1000000)
    public static void aSecondFinishCycleSaysNothingAndTheFirstCelebratesOnce(
            GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        var server = helper.getLevel().getServer();
        String worker = "gt-one-ending-" + helper.getLevel().getGameTime();

        Wish wish = WishBook.get(server).add(Wish.create(
                "make it daytime forever", WishSource.TYPED, System.currentTimeMillis(),
                child.getUUID(), "Steve", "minecraft:overworld", 0, 64, 0));
        Marshal.global().submit(wish.id(), child.getUUID(), wish.text());
        Marshal.global().assign(worker, wish.id());
        BuildGate.startWish(wish.id());
        // put back on EVERY ending. This lived in the verdict's `finally`, which the 25 s deadline's early
        // return skipped -- and the wish it left WORKING was "the single wish in flight" to every later test:
        // ProvenanceWiringGameTest stamped its manifest with "make it daytime forever".
        MockPlayerNetwork.afterTest(helper, () -> {
            Marshal.global().finish(wish.id());
            BuildGate.settle(wish.id());
            WishCapture.forgetForTest(child.getUUID());
        });
        AtomicReference<List<String>> afterFirst = new AtomicReference<>();
        AtomicReference<List<String>> afterSecond = new AtomicReference<>();
        AtomicReference<String> secondBuild = new AtomicReference<>("");
        AtomicReference<String> secondFinish = new AtomicReference<>("");
        AtomicReference<String> firstFinish = new AtomicReference<>("");
        AtomicReference<Throwable> died = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean();

        // OFF THE SERVER THREAD — every tool hops onto it and AgentBridge refuses a re-entrant hop.
        Thread t = new Thread(() -> {
            try {
                AgentContext.with(worker, () -> {
                    // ── CYCLE ONE: plan, RE-plan, build, finish ─────────────────────────────
                    new PlanWishTool().call(plan("Making it stay daytime forever"));
                    new PlanWishTool().call(plan("Making daytime last forever"));
                    String label = "gt_one_ending_" + System.nanoTime();
                    answerTheCamera(label);
                    JsonObject reveal = say(SAY);
                    reveal.addProperty("label", label);
                    new FinishBuildingTool().call(reveal);
                    firstFinish.set(text(new FinishWishTool().call(finish(wish.shortId()))));
                    // The photo borrowed his eyes; nothing answers a camera here, so the session
                    // would be ended later by the watchdog — with a chat line of its own, landing
                    // in the middle of cycle two. End it now, quietly: it belongs to cycle one.
                    WishCapture.forgetForTest(child.getUUID());
                    afterFirst.set(MockPlayerNetwork.chat(child));

                    // ── CYCLE TWO, after DONE: exactly what the live server did next ──────────
                    new PlanWishTool().call(plan("Locking the sun at noon"));
                    // Answered too: this second call went on to photograph, and a
                    // camera nobody answers would turn the RED run into a timeout instead of the
                    // counted lines it is supposed to report.
                    String again = "gt_one_ending_again_" + System.nanoTime();
                    answerTheCamera(again);
                    JsonObject stale = say("It's daytime FOREVER! Go play in endless sunshine!");
                    stale.addProperty("label", again);
                    secondBuild.set(text(new FinishBuildingTool().call(stale)));
                    secondFinish.set(text(new FinishWishTool().call(finish(wish.shortId()))));
                    afterSecond.set(MockPlayerNetwork.chat(child));
                    return null;
                });
            } catch (Throwable e) {
                died.set(e);
            } finally {
                done.set(true);
            }
        }, "one-ending-gametest");
        t.setDaemon(true);
        t.start();

        long startedMs = System.currentTimeMillis();
        helper.succeedWhen(() -> {
            // A WALL-CLOCK deadline, because the tick budget is not one: a GameTest server ticks as
            // fast as it can, and finish_building's photograph waits out a real 4 s quiet period
            // that nothing in a GameTest answers. 25 s is also inside the mock sweep's 30 s.
            if (!done.get() && System.currentTimeMillis() - startedMs > 25_000L) {
                // Failed through the test itself: an assertion exception would be retried until
                // the tick budget runs out, and any other exception kills the server's tick loop.
                helper.testInfo.fail(GameTestCompat.failure(GameTestCompat.assertion("the two finish cycles did not complete in 25 s of wall clock")));
                return;
            }
            helper.assertTrue(done.get(), Component.literal("waiting for both finish cycles"));
            try {
                helper.assertTrue(died.get() == null, Component.literal("the driver thread died: " + died.get()));
                List<String> first = afterFirst.get();
                List<String> second = afterSecond.get();
                String transcript = "\n  " + String.join("\n  ", second);

                helper.assertTrue(firstFinish.get().contains("ended as DONE"),
                        Component.literal("the first finish_wish did not end the wish: " + firstFinish.get()));

                // 1 — THE SECOND CYCLE IS SILENT. The headline.
                helper.assertValueEqual(second.size(), first.size(),
                        Component.literal("lines the child was sent by a SECOND plan/finish_building/finish_wish "
                                + "after the wish was DONE —" + transcript));
                helper.assertTrue(secondBuild.get().contains("no wish in progress"),
                        Component.literal("a stale finish_building must say why it did nothing: "
                                + secondBuild.get()));
                helper.assertTrue(secondFinish.get().contains("already ended"),
                        Component.literal("a second finish_wish must be a no-op that says so: "
                                + secondFinish.get()));
                helper.assertValueEqual(WishBook.get(server).byId(wish.id())
                                .map(Wish::status).orElse(null), WishStatus.DONE,
                        Component.literal("the wish's recorded status"));

                // 2 — THE FIRST CYCLE: ONE PLAN, ONE CELEBRATION, ONE FOLLOW-UP.
                helper.assertValueEqual(count(first, "Here's my plan"), 1,
                        Component.literal("plan lines for one wish that planned twice —" + transcript));
                helper.assertValueEqual(count(first, SAY), 1,
                        Component.literal("the celebration (finish_building's `say`) —" + transcript));
                helper.assertValueEqual(count(first, NOTE), 0,
                        Component.literal("finish_wish read its note out as a SECOND celebration after the reveal "
                                + "had already celebrated —" + transcript));
                helper.assertValueEqual(count(first, "Want to change it?"), 1,
                        Component.literal("the one follow-up line —" + transcript));
                // What he read, for a human reviewing the wording (an earlier issue's PR quotes it).
                System.out.println("OneEndingGameTest — the child's chat, both cycles:" + transcript);
                helper.succeed();
            } catch (net.minecraft.gametest.framework.GameTestAssertException e) {
                // ONCE THE DRIVER IS DONE, AN ASSERTION IS A VERDICT, NOT "NOT YET".
                // succeedWhen retries a thrown assertion every tick until timeoutTicks runs out,
                // and this test's budget is a million ticks (its real deadline is the wall clock
                // above) -- so a deterministic failure was retried for 81 minutes and read as a
                // hang. Fail it now, with the message it would have died with.
                helper.testInfo.fail(GameTestCompat.failure(e));
            }
        });
    }

    /**
     * <b>Be the client for one photograph.</b> {@code finish_building} photographs through the
     * child's eyes and then waits for the file his client writes; a mock has no client, and the
     * wait for a file that never appears runs to the camera's 30 s hard cap — longer than a mock
     * lives. So this writes the file, as a client would, a moment after the shot is asked for.
     * The photograph is not what this test is about; the words around it are.
     */
    private static void answerTheCamera(String label) {
        com.moddingfromamod.testing.FakeCamera.answer(label);
    }


    private static int count(List<String> lines, String needle) {
        return (int) lines.stream().filter(l -> l.contains(needle)).count();
    }

    private static JsonObject plan(String step) {
        JsonObject o = new JsonObject();
        JsonArray steps = new JsonArray();
        steps.add(step);
        o.add("steps", steps);
        return o;
    }

    private static JsonObject say(String say) {
        JsonObject o = new JsonObject();
        o.addProperty("say", say);
        return o;
    }

    private static JsonObject finish(String shortId) {
        JsonObject o = new JsonObject();
        o.addProperty("id", shortId);
        o.addProperty("verdict", "done");
        o.addProperty("note", NOTE);
        return o;
    }

    private static String text(ToolResult r) {
        return r == null ? "" : String.valueOf(r.text());
    }
}
