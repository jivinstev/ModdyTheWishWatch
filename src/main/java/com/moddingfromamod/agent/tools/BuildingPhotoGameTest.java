package com.moddingfromamod.agent.tools;

import net.minecraft.network.chat.Component;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.wish.WishCapture;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The wish card must be a photograph OF the building.</b>
 *
 * <p>A real ladder run photographed a real Spikor castle for its wish card and produced a close-up
 * of a wall from wherever the child happened to be standing — with every gate green. The framing
 * code was correct and had thirteen unit tests; the Gate-C fixture drove that framing code directly
 * and never asked {@link PhotographTool} anything; and the tool, when refused the camera, fell
 * through to the single unframed snapshot <b>silently</b>. Three green gates, one bad picture.
 *
 * <p><b>Every one of these runs the tool on a worker thread.</b> {@code AgentBridge.onServer}
 * refuses to be called <i>from</i> the server thread — it would deadlock waiting for itself — and a
 * GameTest body IS the server thread. So each test starts a thread, and {@code succeedWhen} keeps
 * ticking the server so the work the thread schedules actually gets run. The client fixture had to
 * learn the same thing for a different reason (it sleeps between stops, and sleeping on the render
 * thread stops the rendering).
 *
 * <p>These tests sit on the seam nothing was watching: the decision inside
 * {@code PhotographTool.captureBuilding} about whether this subject gets the eight-shot treatment at
 * all. They deliberately do not take a photograph — that needs a client, and
 * {@code BuildShotClientTest} does it. What they pin down is that the decision is made for the right
 * reasons and that "the camera is busy" is not one of them.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class BuildingPhotoGameTest {

    private static BuildPlan plan(String id) {
        return BuildPlan.fromJson(JsonParser.parseString("""
            {"id":"%s","name":"Photo test","shapes":[
              {"kind":"box","width":9,"height":6,"depth":9,"block":"minecraft:stone","hollow":true}]}
            """.formatted(id)).getAsJsonObject());
    }

    /**
     * A subject that is not a building is refused, and says so — the creature orbit gets its turn.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600)
    public static void somethingThatIsNotABuildingIsHandedOn(GameTestHelper helper) {
        Attempt a = shoot("gt_no_such_building_" + helper.getLevel().getGameTime());
        helper.succeedWhen(() -> {
            helper.assertTrue(a.done(), Component.literal("waiting for the tool"));
            helper.assertTrue(a.result.get() == null,
                    Component.literal("a creature must not be run through the building path"));
            helper.assertTrue(a.why.get() != null && a.why.get().contains("no saved design"),
                    Component.literal("and it must say why, or a broken wish card looks like a working one: "
                            + a.why.get()));
        });
    }

    /**
     * A design nobody has built yet has no anchor, so there is nowhere to stand.
     *
     * <p>Worth its own case because it is the plausible race — {@code finish_building} runs moments
     * after {@code build_structure} — and because the answer must be a clear reason rather than the
     * same "not a building" the creature path gets.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600)
    public static void aDesignThatWasNeverBuiltHasNowhereToStand(GameTestHelper helper) {
        String id = "gt_photo_unbuilt_" + helper.getLevel().getGameTime();
        BuildRegistry.put(helper.getLevel().getServer(), plan(id));
        Attempt a = shoot(id);
        helper.succeedWhen(() -> {
            helper.assertTrue(a.done(), Component.literal("waiting for the tool"));
            helper.assertTrue(a.result.get() == null,
                    Component.literal("there is no anchor, so there is no camera position"));
            helper.assertTrue(a.why.get() != null && a.why.get().contains("never built"),
                    Component.literal("the reason must distinguish this from 'not a building': " + a.why.get()));
        });
    }

    /**
     * <b>The one that was actually happening.</b> Another wish holding the camera must make this
     * WAIT, not degrade.
     *
     * <p>{@link WishCapture#enter} documents its own contract — <i>"the caller should shoot later
     * rather than steal it"</i> — and this caller heard "busy" and quietly took the single unframed
     * snapshot instead. That is the wish card, so the child's picture of his castle depended on who
     * happened to hold a lease.
     *
     * <p>Held by a <b>different</b> owner on purpose: the same wish's own lease is renewable and
     * would not reproduce it. The lease is claimed without a player, which is what
     * {@code claimCameraFor} exists for — putting a real player in a GameTest's player list fires
     * our sync payloads at an embedded channel and fails runs for unrelated reasons.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600)
    public static void aBusyCameraIsWaitedForRatherThanGivenUpOn(GameTestHelper helper) {
        String id = "gt_photo_busy_" + helper.getLevel().getGameTime();
        BuildRegistry.put(helper.getLevel().getServer(), plan(id));
        BuildRegistry.rememberAnchor(id, helper.absolutePos(new BlockPos(1, 1, 1)).asLong(),
                helper.getLevel().dimension());

        UUID somebodyElse = UUID.randomUUID();
        helper.assertTrue(WishCapture.claimCameraFor(somebodyElse, UUID.randomUUID()),
                Component.literal("the fixture must actually take the lease, or this tests nothing"));
        helper.assertTrue(!WishCapture.claimCameraFor(somebodyElse, UUID.randomUUID()),
                Component.literal("and a third wish must be refused it, or 'busy' is not reachable at all"));

        // THE POLICY, not the timing. A GameTest world has no player, so the tool answers "nobody"
        // long before it can answer "busy" — a timing assertion here would pass on the wrong
        // reason, which is worse than a weaker gate. What is assertable, and what was actually
        // broken, is that "busy" counts as worth waiting for and a settled fact does not.
        helper.assertTrue(PhotographTool.worthWaitingFor("busy"),
                Component.literal("a contended camera must be waited out, not answered with an unframed snapshot"));
        helper.assertTrue(PhotographTool.worthWaitingFor("unavailable: bridge is down"),
                Component.literal("nor should a bridge that has not come up yet cost the child his wish card"));
        helper.assertTrue(!PhotographTool.worthWaitingFor("no saved design under that id"),
                Component.literal("but a settled fact must not be retried — the creature orbit is waiting for it"));
        helper.assertTrue(!PhotographTool.worthWaitingFor("never built this run"),
                Component.literal("same: it will be just as untrue in three seconds"));

        // ⚠ WAITING FOR LONG ENOUGH TO MATTER. The two constants that decide this live in two
        // files -- the hold cap in WishCapture, the retry budget here -- and nothing compared them,
        // so the budget was three seconds against a ten-second cap and every queued wish gave up
        // before its turn came round. Fair, ordered, and still nobody's turn is a worse failure
        // than the refusal it replaced, because it looks like it works.
        helper.assertTrue(
                PhotographTool.attemptsToOutlast(WishCapture.MAX_SESSION_MS, 500L) * 500L
                        > WishCapture.MAX_SESSION_MS,
                Component.literal("the retry budget must outlast the hold cap, or the queue can never hand over"));
        helper.assertTrue(PhotographTool.attemptsToOutlast(0L, 0L) >= 1,
                Component.literal("and it must never be zero attempts, whatever the caps are set to"));
        // a FILMED take holds the lease past the still's cap (it renews until its strip
        // lands). A bench server's picture gave up "busy" after 12 s behind two event films.
        helper.assertTrue(PhotographTool.cameraWaitBudgetMs() > 2 * PhotographTool.LONGEST_TAKE_MS,
                Component.literal("the real wait outlasts two filmed takes, not just one still"));

        WishCapture.forgetForTest(somebodyElse);
        helper.succeed();
    }

    /**
     * <b>A second wish is put in LINE, and gets its turn</b> —.
     *
     * <p>Before this it was told "busy" with nothing behind the word: no order, no position, and no
     * guarantee it would ever be served. Which of two children got a properly framed wish card was
     * decided by scheduling, and {@code PhotographTool}'s own comment records what that looked like
     * — <i>"one ladder run produced no framed shot at all and an identical build produced eight per
     * rung, so the difference was never the code; it was who happened to be holding the lease."</i>
     *
     * <p>Claimed without a player for the reason above: this is about the ORDER, which is the part
     * concurrency changes and the part a headless gate can assert.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600)
    public static void asecondWishIsQueuedAndThenServed(GameTestHelper helper) {
        UUID body = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try {
            helper.assertTrue(WishCapture.claimCameraFor(body, first), Component.literal("the first wish takes it"));
            helper.assertTrue(!WishCapture.claimCameraFor(body, second), Component.literal("the second waits"));

            helper.assertTrue(WishCapture.queueDepth(body) == 1,
                    Component.literal("a refusal must leave the waiter IN LINE, not merely turned away: depth was "
                            + WishCapture.queueDepth(body)));
            helper.assertTrue(WishCapture.queuePositionOf(body, second) == 1,
                    Component.literal("and it must know where in line it is -- 'busy' could not say"));

            // Asking again does not take a second place, or a retry loop lengthens its own wait.
            WishCapture.claimCameraFor(body, second);
            WishCapture.claimCameraFor(body, second);
            helper.assertTrue(WishCapture.queueDepth(body) == 1,
                    Component.literal("six retries must be one place in line, not six"));

            WishCapture.releaseCameraLease(body, first);
            helper.assertTrue(second.equals(WishCapture.cameraHeldBy(body)),
                    Component.literal("the wish that waited must actually be served: "
                            + WishCapture.cameraHeldBy(body)));
            helper.assertTrue(WishCapture.claimCameraFor(body, second),
                    Component.literal("and its next claim must be granted rather than re-queued"));

            // A waiter that walks away must not hold its place -- a corpse at the head of the queue
            // makes everyone behind it wait out the whole backstop for a turn that was already free.
            UUID third = UUID.randomUUID();
            WishCapture.claimCameraFor(body, third);
            helper.assertTrue(WishCapture.queueDepth(body) == 1, Component.literal("the third wish is in line"));
            WishCapture.abandonCamera(body, third);
            helper.assertTrue(WishCapture.queueDepth(body) == 0, Component.literal("and gave up its place"));
        } finally {
            WishCapture.forgetForTest(body);
        }
        helper.succeed();
    }

    /** One run of the tool on its own thread, with the answer and the reason it gave. */
    private record Attempt(AtomicReference<ToolResult> result, AtomicReference<String> why,
                           AtomicReference<Long> tookMs) {
        boolean done() {
            return tookMs.get() != null;
        }
    }

    private static Attempt shoot(String subject) {
        Attempt a = new Attempt(new AtomicReference<>(), new AtomicReference<>(),
                new AtomicReference<>());
        Thread t = new Thread(() -> {
            long began = System.currentTimeMillis();
            try {
                a.result().set(PhotographTool.captureBuilding("gt_photo", subject, null));
            } catch (Throwable e) {
                a.why().set("threw: " + e);
            }
            if (a.why().get() == null) {
                // This thread's own answer: the shared lastBuildingSkip is its neighbour's too.
                a.why().set(PhotographTool.BUILDING_SKIP_ON_THIS_THREAD.get());
            }
            a.tookMs().set(System.currentTimeMillis() - began);
        }, "gametest-building-photo");
        t.setDaemon(true);
        t.start();
        return a;
    }
}
