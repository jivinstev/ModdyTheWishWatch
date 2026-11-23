package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.FinishWishTool;
import com.moddingfromamod.marshal.MadeKind;
import com.moddingfromamod.marshal.Marshal;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b> — the guard, driven through the real {@code finish_wish}.</b>
 *
 * <p>{@code TestedClaimTest} owns the rule; this owns the <b>wiring</b>, and the sibling gate is
 * exactly why it exists: an earlier issue's note rule had <i>fourteen</i> green unit tests before the first one
 * that drove the real tool, which went red immediately and exposed two guards whose enabling
 * condition was false forever. A rule tested only where it is DEFINED is not tested where it RUNS.
 *
 * <p>The specific way this one could go quietly inert is the run lookup. The guard asks whether the
 * wish made anything, so if {@code Marshal.run(...)} were ever null at that point the check would
 * see "nothing made" for every wish and refuse honest notes everywhere — or, with the null handled
 * the other way, pass every note forever. Both failure directions are covered below.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class TestedClaimGateGameTest {

    /** The recorded note from the drawbridge cell. Do not tidy it. */
    private static final String CLAIMED =
            "Pull the lever and three chunks of stone brick slide out across the moat so you can "
            + "walk over. Pull it again and the bridge pulls back in. I tested it both ways!";

    /** The cheaper of the two ways out the refusal names — keep the build, drop the claim. */
    private static final String HONEST =
            "Pull the lever and three chunks of stone brick slide out across the moat so you can "
            + "walk over. Pull it again and it pulls back in. I could not test it myself, so go "
            + "and give it a try!";

    /**
     * A wish that built nothing may not tell the child it was tested — and the honest version of
     * the same note must go straight through, or the guard is a trap.
     */
    @GameTest(template = "empty_test", batch = "testedClaimGate")
    public static void aNoteMayNotClaimATestWhenNothingWasBuiltToTest(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        UUID player = UUID.nameUUIDFromBytes(
                ("gt-tested-" + helper.getLevel().getGameTime()).getBytes());

        Wish wish = WishBook.get(server).add(Wish.create(
                "make me a drawbridge that opens up when I pull a lever, out of stone bricks",
                WishSource.TYPED, System.currentTimeMillis(), player, "Steve",
                "minecraft:overworld", 0, 64, 0));
        Marshal.global().submit(wish.id(), player, wish.text());
        // Deliberately NOTHING recorded as made — that IS the case. The agent hand-placed the
        // bridge with 35 setblocks and 42 fills, so the mod knows about no artifact at all and
        // every other finish-time guard is unreachable by construction.
        Marshal.global().assign("gt-tested-w1", wish.id());

        AtomicReference<String> claimed = new AtomicReference<>();
        AtomicReference<String> honest = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean();

        // OFF THE SERVER THREAD — the tool hops onto it and AgentBridge refuses a re-entrant hop.
        Thread t = new Thread(() -> {
            // The first call is eaten by BuildGate's "you never called finish_building" refusal.
            new FinishWishTool().call(args(wish.shortId(), CLAIMED));
            claimed.set(text(new FinishWishTool().call(args(wish.shortId(), CLAIMED))));
            honest.set(text(new FinishWishTool().call(args(wish.shortId(), HONEST))));
            done.set(true);
        }, "tested-claim-gametest");
        t.setDaemon(true);
        t.start();

        helper.succeedWhen(() -> {
            helper.assertTrue(done.get(), Component.literal("waiting for finish_wish to return"));

            String refused = claimed.get() == null ? "" : claimed.get();
            helper.assertTrue(refused.contains("tells him you tested it"),
                    Component.literal("a note claiming a test, on a wish that built nothing testable, must not go "
                            + "through. This is the drawbridge cell: no ContraptionPlan, no "
                            + "MachineHealth, no verify_contraption — the whole verification "
                            + "apparatus bypassed by one routing decision. Got: " + refused));
            helper.assertTrue(refused.contains("tested it both ways"),
                    Component.literal("the refusal must quote the claim back, or the agent goes hunting for which "
                            + "sentence was the problem. Got: " + refused));

            String ok = honest.get() == null ? "" : honest.get();
            helper.assertTrue(!ok.contains("tells him you tested it"),
                    Component.literal("the SAME build, described honestly, must be allowed through — the refusal "
                            + "promises in as many words that keeping what you made and saying you "
                            + "could not test it is a way out, and a guard the agent cannot satisfy "
                            + "is a trap rather than a gate. Got: " + ok));

            Marshal.global().finish(wish.id());
            BuildGate.settle(wish.id());
        });
    }

    /**
     * The other direction, and the one that would make this guard cry wolf: a wish that really did
     * build something may claim it tested it, because something really could have tested it.
     */
    @GameTest(template = "empty_test", batch = "testedClaimGate")
    public static void aWishThatBuiltSomethingMayStillSayItTestedIt(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        UUID player = UUID.nameUUIDFromBytes(
                ("gt-tested-ok-" + helper.getLevel().getGameTime()).getBytes());

        Wish wish = WishBook.get(server).add(Wish.create(
                "build me a lookout tower", WishSource.TYPED, System.currentTimeMillis(),
                player, "Steve", "minecraft:overworld", 0, 64, 0));
        Marshal.global().submit(wish.id(), player, wish.text());
        var run = Marshal.global().assign("gt-tested-w2", wish.id());
        run.made(MadeKind.BUILD, "lookout_" + helper.getLevel().getGameTime());

        AtomicReference<String> answer = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean();
        Thread t = new Thread(() -> {
            new FinishWishTool().call(args(wish.shortId(), CLAIMED));
            answer.set(text(new FinishWishTool().call(args(wish.shortId(), CLAIMED))));
            done.set(true);
        }, "tested-claim-ok-gametest");
        t.setDaemon(true);
        t.start();

        helper.succeedWhen(() -> {
            helper.assertTrue(done.get(), Component.literal("waiting for finish_wish to return"));
            String ok = answer.get() == null ? "" : answer.get();
            helper.assertTrue(!ok.contains("tells him you tested it"),
                    Component.literal("a wish that BUILT something was refused for claiming a test. The check is a "
                            + "conjunction on purpose — an artifact exists, so the ordinary guards "
                            + "apply and the claim may be perfectly true. Firing here is the "
                            + "cry-wolf failure that gets the whole gate routed around. Got: " + ok));
            Marshal.global().finish(wish.id());
            BuildGate.settle(wish.id());
        });
    }

    private static JsonObject args(String shortId, String note) {
        JsonObject o = new JsonObject();
        o.addProperty("id", shortId);
        // ROUGH rather than DONE, so honestVerdict's downgrade cannot be confused with this guard.
        o.addProperty("verdict", "rough");
        o.addProperty("note", note);
        return o;
    }

    private static String text(ToolResult r) {
        return r == null ? "" : String.valueOf(r.text());
    }
}
