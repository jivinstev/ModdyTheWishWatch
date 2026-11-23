package com.moddingfromamod.agent;

import net.minecraft.network.chat.Component;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.AwaitWishTool;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishRejection;
import com.moddingfromamod.wish.WishSource;
import com.moddingfromamod.wish.WishStatus;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B for the long-poll wake channel.
 *
 * <p>Gate A covers the queue's own behaviour. What only a running server can prove is the thing
 * that would be catastrophic and is easy to get wrong: <b>a blocking tool must not park the server
 * thread.</b> {@code await_wish} waits up to 55 seconds, and if that wait ever happened inside
 * {@code AgentBridge.onServer} the entire game would freeze for the duration — a bug that would
 * look like "Minecraft hangs sometimes" and be miserable to trace back here.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class AwaitWishGameTest {

    private static Wish wish(String text, String note) {
        return new Wish(UUID.randomUUID(), text, WishSource.TYPED, WishStatus.PENDING,
                System.currentTimeMillis(), UUID.randomUUID(), "Steve",
                "minecraft:overworld", 1, 64, 2, note, "", 0L);
    }

    /**
     * <b>The one that matters.</b> With a call parked in await_wish, the server must keep ticking.
     * We prove it by watching the game clock advance while the tool is blocked.
     */
    @GameTest(template = "empty_test", timeoutTicks = 2000)
    public static void awaitWishDoesNotFreezeTheServer(GameTestHelper helper) {
        WishQueue q = new WishQueue();
        AtomicBoolean returned = new AtomicBoolean();
        AtomicReference<ToolResult> reply = new AtomicReference<>();

        Thread t = new Thread(() -> {
            JsonObject args = new JsonObject();
            // 30 -> 600. The claim being tested is "the server keeps TICKING while the call is
            // parked", and it is asserted over game ticks — but the tool's timeout is REAL
            // SECONDS, so the two race. Adding two Create GameTests elsewhere in the suite was
            // enough: with more machines ticking concurrently, ten game ticks took longer than
            // thirty wall-clock seconds, the call returned by timing out, and this failed with
            // "await_wish should still be blocked". Green on main, red the moment the suite got
            // busier — which is a property of the machine, not of the code under test.
            args.addProperty("timeoutSeconds", 600);
            reply.set(new AwaitWishTool(q).call(args));
            returned.set(true);
        }, "await-wish-gametest");
        t.setDaemon(true);
        t.start();

        long startTick = helper.getLevel().getGameTime();
        AtomicInteger polls = new AtomicInteger();
        helper.succeedWhen(() -> {
            long elapsed = helper.getLevel().getGameTime() - startTick;
            // If the server thread were parked inside the tool, this body would never run again
            // and the test would time out rather than fail — so assert progress explicitly.
            helper.assertTrue(elapsed > 0, Component.literal("the server must keep ticking while await_wish blocks"));
            if (polls.incrementAndGet() < 10) {
                helper.fail(Component.literal("waiting for a few ticks to pass with the call still parked"));
            }
            helper.assertFalse(returned.get(),
                    Component.literal("await_wish should still be blocked — nothing was offered yet"));
            // Now wake it and let the test finish; the thread is a daemon either way.
            q.offer(wish("make it rain chickens", ""));
        });
    }

    /** A wish offered while an agent is parked comes straight back out of the tool. */
    @GameTest(template = "empty_test", timeoutTicks = 2000)
    public static void aWishWakesTheParkedAgent(GameTestHelper helper) {
        WishQueue q = new WishQueue();
        AtomicReference<ToolResult> reply = new AtomicReference<>();
        Thread t = new Thread(() -> {
            JsonObject args = new JsonObject();
            args.addProperty("timeoutSeconds", 20);
            reply.set(new AwaitWishTool(q).call(args));
        }, "await-wish-gametest-2");
        t.setDaemon(true);
        t.start();

        q.offer(wish("i want a giant robot buddy", ""));

        helper.succeedWhen(() -> {
            ToolResult r = reply.get();
            helper.assertTrue(r != null, Component.literal("waiting for await_wish to return"));
            helper.assertFalse(r.isError(), Component.literal("await_wish should not error: " + r.text()));
            helper.assertTrue(r.text().contains("giant robot buddy"),
                    Component.literal("the wish text must reach the agent: " + r.text()));
            helper.assertTrue(r.text().contains("await_wish again"),
                    Component.literal("the agent must be told to keep looping: " + r.text()));
        });
    }

    /** A re-opened wish must arrive carrying WHY it came back, or the agent repeats its mistake. */
    @GameTest(template = "empty_test", timeoutTicks = 2000)
    public static void aRejectedWishArrivesWithItsReason(GameTestHelper helper) {
        WishQueue q = new WishQueue();
        String note = WishRejection.MARKER + " — it was just a spider";
        AtomicReference<ToolResult> reply = new AtomicReference<>();
        Thread t = new Thread(() -> {
            JsonObject args = new JsonObject();
            args.addProperty("timeoutSeconds", 20);
            reply.set(new AwaitWishTool(q).call(args));
        }, "await-wish-gametest-3");
        t.setDaemon(true);
        t.start();

        q.offer(wish("make a wither storm", note));

        helper.succeedWhen(() -> {
            ToolResult r = reply.get();
            helper.assertTrue(r != null, Component.literal("waiting"));
            helper.assertTrue(r.text().contains(WishRejection.MARKER),
                    Component.literal("a refused wish must announce itself as refused: " + r.text()));
        });
    }

    /**
     * Timing out is normal and must read as such, so the agent loops instead of stopping.
     *
     * <p>Note the generous tick budget. The queue's timeout is <b>wall-clock</b>, but a GameTest's
     * budget is <b>ticks</b> — and the test server ticks far faster than realtime (the whole suite
     * runs in well under a second). At 200 ticks this failed every time: the tick budget expired
     * while the 1-second wait was still perfectly happily in progress. Don't treat ticks as seconds.
     */
    @GameTest(template = "empty_test", timeoutTicks = 100000)
    public static void aTimeoutTellsTheAgentToKeepLooping(GameTestHelper helper) {
        WishQueue q = new WishQueue();
        AtomicReference<ToolResult> reply = new AtomicReference<>();
        Thread t = new Thread(() -> {
            JsonObject args = new JsonObject();
            args.addProperty("timeoutSeconds", 1);
            reply.set(new AwaitWishTool(q).call(args));
        }, "await-wish-gametest-4");
        t.setDaemon(true);
        t.start();

        helper.succeedWhen(() -> {
            ToolResult r = reply.get();
            helper.assertTrue(r != null, Component.literal("waiting for the timeout"));
            helper.assertFalse(r.isError(), Component.literal("a timeout is NOT an error — it's the normal case"));
            helper.assertTrue(r.text().contains("again"),
                    Component.literal("must tell the agent to call it again: " + r.text()));
        });
    }
}
