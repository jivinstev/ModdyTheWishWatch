package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.FinishWishTool;
import com.moddingfromamod.marshal.ArtifactAffinity;
import com.moddingfromamod.marshal.MadeKind;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B for <b>the other half of artifact ownership</b>: a wish that CREATES something must leave
 * that thing owned by the worker that made it.
 *
 * <p>{@code WishAffinityGameTest} covers the refinement half, where the wish already names an
 * existing artifact and {@code await_wish} records the take. A creation names nothing — the artifact
 * only acquires an id here, when {@code finish_wish} writes its handover record. Record at one end
 * only and every ladder loses its first rung: wish 1 makes Zippy and is forgotten, wish 2 routes as
 * though Zippy had no history, and <b>the arm silently does not hold</b> while every other gate stays
 * green. That is the failure this exists for, and it is invisible from anywhere else.
 *
 * <p>Driven through the REAL tool rather than the private method it calls, because the wiring is the
 * thing at risk — the recording rule itself is already a unit test on {@link ArtifactAffinity}.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WishOwnershipGameTest {

    /** Its own batch: {@link ArtifactAffinity} is a process-wide map. Ids are unique regardless. */
    @GameTest(template = "empty_test", batch = "wishOwnership")
    public static void finishingACreationLeavesTheThingOwnedByItsMaker(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        String artifact = "gt_owned_" + helper.getLevel().getGameTime();
        String worker = "gt-owner-w1";
        UUID player = UUID.nameUUIDFromBytes("gt-owner-child".getBytes());

        Wish wish = WishBook.get(server).add(Wish.create(
                "make me a " + artifact, WishSource.TYPED, System.currentTimeMillis(),
                player, "Steve", "minecraft:overworld", 0, 64, 0));
        Marshal.global().submit(wish.id(), player, wish.text());
        var run = Marshal.global().assign(worker, wish.id());
        run.made(MadeKind.CREATURE, artifact);

        // NO clear() HERE, and that is the fix rather than an omission. This used to wipe
        // the process-wide registry to make the assertion below true, which also deleted every
        // OTHER in-flight test's ownership — so this test was not merely a victim of overlap, it
        // was a cause of it. The artifact id already carries the game time and is unique to this
        // run, so asking about THIS artifact is both sufficient and a stronger claim than asking
        // whether the whole registry is empty.
        helper.assertTrue(ArtifactAffinity.global().ownerOf(artifact).isEmpty(),
                Component.literal("nothing may own '" + artifact + "' before the wish ends"));

        JsonObject args = new JsonObject();
        args.addProperty("id", wish.shortId());
        args.addProperty("verdict", "done");
        args.addProperty("artifact", artifact);
        args.addProperty("tried", "scaled the head to 3 and it clipped through the ceiling");
        args.addProperty("careful", "the tail is what makes it too wide for a doorway");

        // OFF THE SERVER THREAD. The tool hops onto the server thread to do its work, and
        // AgentBridge refuses that outright when it is already there — so calling it from a
        // GameTest body deadlocks by design and fails with exactly that message.
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        Thread t = new Thread(() -> {
            // TWICE, on purpose. BuildGate refuses the first finish when finish_building was never
            // called and then gives way — so a single call here would assert about the guard rather
            // than about ownership, and would pass for the wrong reason if the guard were removed.
            new FinishWishTool().call(args);
            new FinishWishTool().call(args);
            done.set(true);
        }, "wish-ownership-gametest");
        t.setDaemon(true);
        t.start();

        helper.succeedWhen(() -> {
            helper.assertTrue(done.get(), Component.literal("waiting for finish_wish to return"));
            helper.assertTrue(worker.equals(ArtifactAffinity.global().ownerOf(artifact)),
                    Component.literal("a creation must leave its artifact owned by the worker that made it — "
                            + "otherwise the next wish about it routes as though it had no history, "
                            + "and a forced arm quietly does not hold"));
            // The one row this test added stays: it is keyed by an id nobody else uses, the
            // registry is bounded at MAX_REMEMBERED and evicts oldest, and clearing it here is
            // what took out other tests mid-measurement.
            Marshal.global().finish(wish.id());
            BuildGate.settle(wish.id());
        });
    }

    /**
     * <b>…and a thing the mod cannot undo is still the thing he last worked on.</b>
     *
     * <p>Proving the WIRING, not the rule — {@code MarshalSchedulingTest} already owns the rule.
     * The distinction has cost this repo twice (the held-item camera and the Spikor wish card):
     * deleting the line in {@code finish_wish} that stamps the run leaves every unit test green,
     * because they set the field themselves.
     *
     * <p>The run deliberately records NOTHING in {@code made()}, which is what an item wish really
     * does — there is no {@code MadeKind.ITEM}. Before this, that made
     * {@code lastArtifactFor} return {@code ""}, so a refinement naming nothing ("make it hurt way
     * more") had no subject and routed {@code anyone} — 16 of 16 rungs, in both arms of the items
     * class's own confirming matrix.
     */
    @GameTest(template = "empty_test", batch = "wishOwnership")
    public static void aWishAboutAnUndoableNothingIsStillTheLastThingHeWorkedOn(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        String artifact = "gt_lastart_" + helper.getLevel().getGameTime();
        String worker = "gt-lastart-w1";
        UUID player = UUID.nameUUIDFromBytes(("gt-lastart-" + artifact).getBytes());

        Wish wish = WishBook.get(server).add(Wish.create(
                "i want a " + artifact + ", a purple wand that shoots lightning",
                WishSource.TYPED, System.currentTimeMillis(),
                player, "Steve", "minecraft:overworld", 0, 64, 0));
        Marshal.global().submit(wish.id(), player, wish.text());
        var run = Marshal.global().assign(worker, wish.id());
        // NOTHING recorded in made(): an item has no reversal, so nothing may claim it does.

        helper.assertTrue(Marshal.global().lastArtifactFor(player).isEmpty(),
                Component.literal("nothing has been finished yet"));

        JsonObject args = new JsonObject();
        args.addProperty("id", wish.shortId());
        args.addProperty("verdict", "done");
        args.addProperty("artifact", artifact);
        args.addProperty("tried", "a straight lightning template, before the colour was settled");
        args.addProperty("careful", "the effect list is the whole list, not the new part");

        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        Thread t = new Thread(() -> {
            // Twice, for the same BuildGate reason the test above documents.
            new FinishWishTool().call(args);
            new FinishWishTool().call(args);
            done.set(true);
        }, "wish-lastartifact-gametest");
        t.setDaemon(true);
        t.start();

        helper.succeedWhen(() -> {
            helper.assertTrue(done.get(), Component.literal("waiting for finish_wish to return"));
            helper.assertTrue(run.made().isEmpty(),
                    Component.literal("the fixture must stay honest: an item records nothing undoable"));
            Marshal.global().finish(wish.id());
            helper.assertTrue(artifact.equals(Marshal.global().lastArtifactFor(player)),
                    Component.literal("finish_wish must stamp what the wish was about, or the next refinement has "
                            + "no subject and routes `anyone` — got '"
                            + Marshal.global().lastArtifactFor(player) + "'"));
            BuildGate.settle(wish.id());
        });
    }

    /**
     * <b>A wish whose child is no longer online still takes its progress bar down</b>.
     *
     * <p>{@code finish_wish} cleared the bar only inside {@code if (player != null)}. A child who
     * logs off before his wish finishes — or any canary, whose wisher is a stand-in no player list
     * holds — left the bar in {@code WishProgress} for good. Nobody could see it; but
     * {@code WishProgress.inFlight()} counted it, so on a dedicated server the wish cam never believed
     * the server was idle and drained the full five-minute cap every time, logging "a wish is wedged".
     *
     * <p>The wisher here is an id no player list holds, exactly as in the test above; the bar is
     * shown to a mock player, because a bar needs someone to be drawn for when it is made.
     */
    @GameTest(template = "empty_test", batch = "wishOwnership")
    public static void aFinishedWishTakesItsBarDownEvenWhenItsChildHasGone(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        String worker = "gt-owner-w3";
        UUID gone = UUID.nameUUIDFromBytes("gt-owner-gone-child".getBytes());
        Wish wish = WishBook.get(server).add(Wish.create(
                "make me a lantern", WishSource.TYPED, System.currentTimeMillis(),
                gone, "Steve", "minecraft:overworld", 0, 64, 0));
        Marshal.global().submit(wish.id(), gone, wish.text());
        Marshal.global().assign(worker, wish.id());
        var viewer = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper);
        WishProgress.startThinking(viewer, wish.id(), wish.text());
        // …and ANOTHER wish still being granted, by another worker. finish_wish used to look its own
        // wish up through AgentContext.currentRun() AFTER Marshal.finish had dropped it, and fell
        // through to "the only wish still running" — this one — taking down the wrong bar.
        Wish other = WishBook.get(server).add(Wish.create(
                "make me a boat", WishSource.TYPED, System.currentTimeMillis(),
                gone, "Steve", "minecraft:overworld", 0, 64, 0));
        Marshal.global().submit(other.id(), gone, other.text());
        Marshal.global().assign("gt-owner-w4", other.id());
        WishProgress.startThinking(viewer, other.id(), other.text());
        helper.assertTrue(WishProgress.planFor(wish.id()) != null,
                Component.literal("the fixture must put a bar up, or the test proves nothing"));
        helper.assertTrue(server.getPlayerList().getPlayer(gone) == null,
                Component.literal("the wisher must NOT be online — that is the case under test"));

        JsonObject args = new JsonObject();
        args.addProperty("id", wish.shortId());
        args.addProperty("verdict", "done");
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<String> said = new java.util.concurrent.atomic.AtomicReference<>("");
        Thread t = new Thread(() -> {
            // As the worker, so finish_wish knows WHICH wish's bar is its own; twice, for BuildGate
            // (see the first test in this class).
            com.moddingfromamod.agent.AgentContext.with(worker, () -> new FinishWishTool().call(args));
            var r = com.moddingfromamod.agent.AgentContext.with(worker, () -> new FinishWishTool().call(args));
            said.set(String.valueOf(r.text()));
            done.set(true);
        }, "wish-bar-gametest");
        t.setDaemon(true);
        t.start();

        helper.succeedWhen(() -> {
            helper.assertTrue(done.get(), Component.literal("waiting for finish_wish to return"));
            helper.assertTrue(WishProgress.planFor(wish.id()) == null,
                    Component.literal("a finished wish's bar must come down even when its child has gone, or "
                            + "WishProgress.inFlight() counts it forever and the wish cam never "
                            + "believes the server is idle (finish_wish said: " + said.get() + ")"));
            helper.assertTrue(WishProgress.planFor(other.id()) != null,
                    Component.literal("finishing one wish must not take down ANOTHER wish's bar"));
            Marshal.global().finish(wish.id());
            Marshal.global().finish(other.id());
            WishProgress.clearWishBar(other.id());
            WishProgress.clearWishBar(wish.id());
            BuildGate.settle(wish.id());
        });
    }
}
