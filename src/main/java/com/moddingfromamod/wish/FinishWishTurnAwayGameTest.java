package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.FinishWishTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The turn-away must not advertise its own bypass.</b>
 *
 * <p>{@code BuildGate.refuseFinish()} is one-shot on purpose — <i>a wish that ends imperfectly
 * beats one that cannot end</i> — so a second {@code finish_wish} goes through however
 * {@code finish_building} went. That is right, and it was also being <b>advertised</b>: the refusal
 * used to close with <i>"just call finish_wish again and it will go through"</i>.
 *
 * <p>Which makes the cheapest possible response to the guard a retry, and a retry satisfies it. The
 * child is never told his wish is ready, the agent never looks at what it made, and the round trip
 * bought nothing at all. an earlier issue's own transcript shows {@code finish_wish} called twice <b>six
 * seconds apart</b> — too fast for a photograph in between.
 *
 * <p>Nothing tested this guard's wording before. It had a message that taught the wrong path, and
 * every gate in the repo was green.
 *
 * <p>What is asserted here is the pair that has to hold together: the refusal names the two-call
 * path and does <b>not</b> offer a retry as an alternative to it, AND the second call still goes
 * through — because a guard that traps the agent is the failure the one-shot design exists to
 * avoid, and tightening the words must not have quietly tightened the behaviour.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class FinishWishTurnAwayGameTest {

    @GameTest(template = "empty_test", batch = "finishTurnAway")
    public static void theRefusalNamesTheShortPathAndNotABypass(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        UUID player = UUID.nameUUIDFromBytes(
                ("gt-turnaway-" + helper.getLevel().getGameTime()).getBytes());

        Wish wish = WishBook.get(server).add(Wish.create(
                "make me a castle", WishSource.TYPED, System.currentTimeMillis(), player,
                "Steve", "minecraft:overworld", 0, 64, 0));
        Marshal.global().submit(wish.id(), player, wish.text());
        Marshal.global().assign("gt-turnaway-w1", wish.id());
        // finish_building deliberately never called — that is the case the guard is for.

        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean();

        // OFF THE SERVER THREAD — the tool hops onto it and AgentBridge refuses a re-entrant hop.
        Thread t = new Thread(() -> {
            first.set(text(new FinishWishTool().call(args(wish.shortId()))));
            second.set(text(new FinishWishTool().call(args(wish.shortId()))));
            done.set(true);
        }, "finish-turnaway-gametest");
        t.setDaemon(true);
        t.start();

        helper.succeedWhen(() -> {
            helper.assertTrue(done.get(), Component.literal("waiting for finish_wish to return"));

            String refused = first.get() == null ? "" : first.get();
            helper.assertTrue(refused.contains("finish_building"),
                    Component.literal("the first call must be turned away and name the step it wants: " + refused));

            // THE FIX. Any sentence that offers "call this again" as a way past the guard teaches
            // the three-turn path, and the agent will take it because it is cheaper than doing the
            // work — the guard is one-shot, so the retry genuinely succeeds.
            String lower = refused.toLowerCase(java.util.Locale.ROOT);
            int again = lower.indexOf("finish_wish again");
            helper.assertTrue(again < 0 || lower.lastIndexOf("errors", again) >= 0
                            || lower.lastIndexOf("only if", again) >= 0,
                    Component.literal("the refusal offers a retry without tying it to finish_building FAILING, which "
                    + "is a posted bypass: the cheapest response satisfies the guard, the child is "
                    + "never told and nothing was looked at. " + refused));
            helper.assertTrue(lower.contains("two calls") || lower.contains("shortest"),
                    Component.literal("…and it must name the correct path as the CHEAP one, or 'do the work' reads "
                    + "as the expensive option it is being asked to choose: " + refused));
            helper.assertTrue(refused.contains("argument"),
                    Component.literal("it should say WHY judging now is wrong — the verdict is already an argument "
                    + "of this call, so there is nothing for the photo to change: " + refused));

            // GIVE WAY, unchanged. Tightening the words must not trap the agent: finish_building
            // can genuinely fail, and a wish that cannot end is worse than one that ends badly.
            String went = second.get() == null ? "" : second.get();
            helper.assertTrue(!went.contains("Not yet"),
                    Component.literal("the second call was refused too — the guard has become a trap, which is the "
                    + "failure the one-shot design exists to prevent: " + went));
            helper.succeed();
        });
    }

    private static JsonObject args(String shortId) {
        JsonObject o = new JsonObject();
        o.addProperty("id", shortId);
        o.addProperty("verdict", "rough");
        o.addProperty("note", "The castle is up, four walls and a door, and he can walk in.");
        return o;
    }

    private static String text(ToolResult r) {
        return r == null ? "" : String.valueOf(r.text());
    }
}
