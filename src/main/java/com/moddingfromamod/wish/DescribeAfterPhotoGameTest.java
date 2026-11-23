package com.moddingfromamod.wish;

import com.moddingfromamod.testing.gt.GameTestCompat;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.FinishBuildingTool;
import com.moddingfromamod.agent.tools.FinishWishTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.reveal.Reveals;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>"It's here!" at the reveal; WHAT it is after the photo has been judged</b>.
 *
 * <p>Reveal lines described the plan: a garage announced with a spiral track and a T-Rex that its
 * photograph did not have, because {@code finish_building}'s {@code say} was written before the
 * photo existed. Now the reveal says {@link WishFeedback#REVEAL_SHORT} by itself, and the
 * description is {@code finish_wish}'s {@code say}. Counted in a real mock player's chat:
 *
 * <ol>
 *   <li>the short line comes at the reveal and the description is NOT in it; the description comes
 *       after {@code finish_wish}, after the short line, once; still one celebration;</li>
 *   <li>when the reveal never finishes, {@code Reveals.finale}'s deadline still says the short line,
 *       and the description still waits for it (the order).</li>
 * </ol>
 * A wish that never calls {@code finish_wish} is (1)'s first snapshot: the short line, and nothing
 * else. Wall-clock deadlines, because a GameTest server ticks as fast as it can.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class DescribeAfterPhotoGameTest {

    private static final String DESC = "A blue garage with a red post and a grey ramp!";
    private static final String NOTE = "Built a small garage; no spiral track yet.";

    @GameTest(template = "empty_test", batch = "describeAfterPhoto1342", timeoutTicks = 1000000)
    public static void theShortLineComesAtTheRevealAndTheDescriptionAfterFinishWish(
            GameTestHelper helper) {
        drive(helper, false);
    }

    @GameTest(template = "empty_test", batch = "describeAfterPhoto1342deadline", timeoutTicks = 1000000)
    public static void aRevealThatNeverFinishesStillSaysTheShortLineFirst(GameTestHelper helper) {
        drive(helper, true);
    }

    private static void drive(GameTestHelper helper, boolean stuckReveal) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        var server = helper.getLevel().getServer();
        String worker = "gt-describe-" + (stuckReveal ? "stuck-" : "") + helper.getLevel().getGameTime();
        Wish wish = WishBook.get(server).add(Wish.create("build me a garage", WishSource.TYPED,
                System.currentTimeMillis(), child.getUUID(), "Steve", "minecraft:overworld", 0, 64, 0));
        Marshal.global().submit(wish.id(), child.getUUID(), wish.text());
        Marshal.global().assign(worker, wish.id());
        BuildGate.startWish(wish.id());
        Object dome = new Object();
        if (stuckReveal) {
            // A whirlwind nobody shatters: the reveal never finishes, so only the deadline speaks.
            var c = helper.absolutePos(net.minecraft.core.BlockPos.ZERO);
            Reveals.domeFor(dome, helper.getLevel(), wish.id(), c.getX() + 0.5, c.getY(),
                    c.getZ() + 0.5, 2, 2, 3);
        }
        int before = MockPlayerNetwork.chat(child).size();
        AtomicReference<List<String>> atReturn = new AtomicReference<>();
        AtomicReference<List<String>> atReveal = new AtomicReference<>();
        AtomicReference<List<String>> atFinish = new AtomicReference<>();
        AtomicReference<String> finished = new AtomicReference<>("");
        AtomicLong waitedMs = new AtomicLong();
        AtomicReference<Throwable> died = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean();

        Thread t = new Thread(() -> {
            try {
                AgentContext.with(worker, () -> {
                    String label = "gt_describe_" + System.nanoTime();
                    answerTheCamera(label);
                    JsonObject reveal = new JsonObject();
                    reveal.addProperty("label", label);
                    new FinishBuildingTool().call(reveal);
                    WishCapture.forgetForTest(child.getUUID());
                    atReturn.set(mine(child, before));
                    if (!stuckReveal) {
                        // The reveal plays for a couple of seconds; the short line follows it.
                        long until = System.currentTimeMillis() + 8_000L;
                        while (count(mine(child, before), WishFeedback.REVEAL_SHORT) == 0
                                && System.currentTimeMillis() < until) {
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                        atReveal.set(mine(child, before));
                    }
                    long start = System.currentTimeMillis();
                    JsonObject f = new JsonObject();
                    f.addProperty("id", wish.shortId());
                    f.addProperty("verdict", "done");
                    f.addProperty("note", NOTE);
                    f.addProperty("say", DESC);
                    finished.set(text(new FinishWishTool().call(f)));
                    waitedMs.set(System.currentTimeMillis() - start);
                    atFinish.set(mine(child, before));
                    return null;
                });
            } catch (Throwable e) {
                died.set(e);
            } finally {
                done.set(true);
            }
        }, "describe-after-photo-gametest");
        t.setDaemon(true);
        t.start();

        long startedMs = System.currentTimeMillis();
        long budget = stuckReveal ? 45_000L : 25_000L;
        helper.succeedWhen(() -> {
            if (!done.get() && System.currentTimeMillis() - startedMs > budget) {
                helper.testInfo.fail(GameTestCompat.assertion("the finish cycle did not complete in " + budget / 1000 + " s of wall clock"));
                return;
            }
            helper.assertTrue(done.get(), Component.literal("waiting for the finish cycle"));
            try {
                helper.assertTrue(died.get() == null, Component.literal("the driver thread died: " + died.get()));
                helper.assertTrue(finished.get().contains("ended as DONE"),
                        Component.literal("finish_wish did not end the wish: " + finished.get()));
                List<String> end = atFinish.get();
                String transcript = "\n  " + String.join("\n  ", end);
                if (stuckReveal) {
                    // THE END COMES FIRST: finish_building plays the end and
                    // waits for it before its photograph, so a reveal that never finishes is spoken
                    // for by the deadline BEFORE finish_building returns — the short line is already
                    // said, once, and finish_wish has nothing left to wait for.
                    helper.assertValueEqual(count(atReturn.get(), WishFeedback.REVEAL_SHORT), 1,
                            Component.literal("the end was not told before the photograph —\n  "
                                    + String.join("\n  ", atReturn.get())));
                    helper.assertTrue(waitedMs.get() < Reveals.FINALE_DEADLINE_MS - 3_000L,
                            Component.literal("finish_wish waited " + waitedMs.get() + " ms for an end already told"));
                } else {
                    // A wish that never calls finish_wish has exactly this: the short line only.
                    List<String> reveal = atReveal.get();
                    helper.assertValueEqual(count(reveal, WishFeedback.REVEAL_SHORT), 1,
                            Component.literal("the short line at the reveal —\n  " + String.join("\n  ", reveal)));
                    helper.assertValueEqual(count(reveal, DESC), 0,
                            Component.literal("the description was said at the reveal, before the photo was judged"));
                }
                helper.assertValueEqual(count(end, WishFeedback.REVEAL_SHORT), 1,
                        Component.literal("the short line, once —" + transcript));
                helper.assertValueEqual(count(end, DESC), 1, Component.literal("the description, once —" + transcript));
                helper.assertTrue(index(end, DESC) > index(end, WishFeedback.REVEAL_SHORT),
                        Component.literal("the description came BEFORE \"it's here!\" —" + transcript));
                helper.assertValueEqual(count(end, NOTE), 0,
                        Component.literal("a 'done' note read out as a second celebration —" + transcript));
                helper.assertValueEqual(count(end, "Want to change it?"), 1,
                        Component.literal("the one follow-up line —" + transcript));
                System.out.println("DescribeAfterPhotoGameTest (" + (stuckReveal ? "deadline" : "reveal")
                        + ") — the child's chat:" + transcript);
                helper.succeed();
            } catch (GameTestAssertException e) {
                helper.testInfo.fail(GameTestCompat.failure(e));    // a verdict once the driver is done
            } finally {
                if (done.get()) {
                    Reveals.dropDome(dome);
                    Marshal.global().finish(wish.id());
                    BuildGate.settle(wish.id());
                    WishCapture.forgetForTest(child.getUUID());
                }
            }
        });
    }

    /** What THIS test's child was sent since it started. */
    private static List<String> mine(ServerPlayer child, int before) {
        List<String> all = MockPlayerNetwork.chat(child);
        return List.copyOf(all.subList(Math.min(before, all.size()), all.size()));
    }

    /** Be the client for one photograph (see OneEndingGameTest.answerTheCamera). */
    private static void answerTheCamera(String label) {
        com.moddingfromamod.testing.FakeCamera.answer(label);
    }


    private static int count(List<String> lines, String needle) {
        return lines == null ? 0 : (int) lines.stream().filter(l -> l.contains(needle)).count();
    }

    private static int index(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static String text(ToolResult r) {
        return r == null ? "" : String.valueOf(r.text());
    }
}
