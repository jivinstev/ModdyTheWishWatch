package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.tools.FinishWishTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.provenance.ProvenanceContext;
import com.moddingfromamod.ModdingFromAModMod;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A finished wish must not leave its intent lying about —.</b>
 *
 * <p>{@code await_wish} stamps the wish being granted into {@link ProvenanceContext}, so anything
 * the agent persists while working is attributed to it. That is the Provenance Contract.
 * <b>Nothing ever took it down again.</b> {@code ProvenanceContext.clear()} was written for exactly
 * this — <i>"forget the current intent + note so nothing is mis-attributed later"</i> — and had
 * <b>zero production callers</b>, so in a real session it had never once run.
 *
 * <p>Left set, a finished wish's intent stays ambient and is inherited by the next artifact
 * published before the next {@code await_wish} stamps its own. That is the one thing the contract
 * forbids: attributing one wish's reasoning to another wish's artifact.
 *
 * <p><b>Why this drives the real tool.</b> A unit test on {@code clear()} would have passed all
 * along — the method worked; nobody called it. The same shape as, and the reason this repo's
 * rule is that a guard tested only where it is DEFINED is not tested where it RUNS. The cheap
 * check that finds it: take the condition that enables the guard, ask who writes what it reads.
 * Here nobody did.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class AmbientIntentGameTest {

    private static JsonObject args(String shortId) {
        JsonObject o = new JsonObject();
        o.addProperty("id", shortId);
        o.addProperty("verdict", "rough");
        o.addProperty("note", "I made a start on it and it is not finished yet.");
        return o;
    }

    @GameTest(template = "empty_test", timeoutTicks = 400, batch = "ambientIntent")
    public static void afinishedwishleavesnointentbehind(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        long stamp = helper.getLevel().getGameTime();
        UUID player = UUID.nameUUIDFromBytes(("gt-ambient-" + stamp).getBytes());
        String worker = "gt-ambient-w" + stamp;
        String text = "make me a lighthouse that blinks " + stamp;

        // SNAPSHOT, NOT CLEAR -- the rule ProvenanceWiringGameTest records four times over. A
        // clear() here would delete what a concurrently running neighbour set, and the neighbour
        // goes green having asserted nothing rather than red.
        String priorIntent = ProvenanceContext.currentIntent();
        String priorNote = ProvenanceContext.currentNote();

        try {
            Wish wish = WishBook.get(server).add(Wish.create(
                    text, WishSource.TYPED, System.currentTimeMillis(), player, "Steve",
                    "minecraft:overworld", 0, 64, 0));
            Marshal.global().submit(wish.id(), player, wish.text());
            Marshal.global().assign(worker, wish.id());

            // What await_wish does, which is the state this test is about.
            ProvenanceContext.begin(wish.text());
            helper.assertTrue(text.equals(ProvenanceContext.currentIntent()),
                    Component.literal("setup: the intent must be stamped before finishing, or this test proves "
                            + "nothing about clearing it. Got: " + ProvenanceContext.currentIntent()));

            AtomicReference<String> out = new AtomicReference<>();
            AtomicBoolean done = new AtomicBoolean();

            // OFF THE SERVER THREAD -- the tool hops onto it and AgentBridge refuses a re-entrant
            // hop. The first call is eaten by BuildGate's one-shot "you never called
            // finish_building" refusal, which every finish here must get past first.
            Thread t = new Thread(() -> {
                new FinishWishTool().call(args(wish.shortId()));
                out.set(String.valueOf(new FinishWishTool().call(args(wish.shortId()))));
                done.set(true);
            }, "ambient-intent-gametest");
            t.setDaemon(true);
            t.start();

            helper.succeedWhen(() -> {
                helper.assertTrue(done.get(), Component.literal("waiting for finish_wish to return"));
                String now = ProvenanceContext.currentIntent();
                helper.assertTrue(now == null || now.isBlank(),
                        Component.literal("the work is over, so the intent must not still be ambient -- the next "
                                + "artifact published would inherit it and provenance would "
                                + "attribute one wish's reasoning to another wish's artifact. "
                                + "Still set to: " + now));
            });
        } finally {
            ProvenanceContext.restoreForTest(priorIntent, priorNote);
        }
    }
}
