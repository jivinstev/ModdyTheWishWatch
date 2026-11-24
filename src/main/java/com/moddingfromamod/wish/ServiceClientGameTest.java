package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ActingChild;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.PlayerKind;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A client the mod launched for itself is never mistaken for a child</b> — the eligibility
 * filter, as distinct from the owner selector.
 *
 * <p>These are different questions and neither answers the other. {@link WishAudience} asks
 * <i>which of the two children asked for this</i>; {@link PlayerKind} asks <i>is this a child at
 * all</i>. The camera client proposes is not a child with a different owner — it is <b>not a
 * child</b>, and nothing it can be asked would say so: it is a real connected client with a real
 * channel, so {@code Send.canReceive} answers yes. The mod is the thing that knows, because the mod
 * launched it.
 *
 * <p><b>Provable today, with no camera in existence:</b> the registry does not care what kind of
 * client a UUID belongs to, so a mock declared a service client reproduces exactly the failure a
 * camera would cause — and it is the shape from the other side, a player miles from world
 * spawn silently becoming the origin every distance query is measured from.
 *
 * <p><b>⚠ ITS OWN BATCH, and that is load-bearing rather than tidiness.</b> The first cut of this
 * lived in {@code TwoChildrenGameTest}, whose batch holds five tests that run <b>concurrently</b>
 * and create two mock children each. So the arm with the fix removed still passed: the player list
 * held up to nine players and the tool picked somebody, and the end-to-end assertion could not tell
 * "the filter worked" from "somebody else's child happened to do". A batch with one test in it is
 * what makes "the only player in this world is the camera" a true statement — and only then does
 * the tool's behaviour mean anything.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ServiceClientGameTest {

    private static final String BATCH = "serviceclient";

    /**
     * <b>The direct claim.</b> Asserted on {@link ActingChild} itself rather than only through a
     * tool, because a tool's answer has other ways of being right — which is how the first version
     * of this test passed with the fix deleted.
     *
     * <p><b>Verified to fail</b> without the {@code registerServiceClient} call: the camera is the
     * only player in the world, so it is picked as the child.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void theModsOwnClientIsNotTheChild(GameTestHelper helper) {
        ServerPlayer camera = MockPlayerNetwork.inLevel(helper);
        PlayerKind.registerServiceClient(camera.getUUID());
        try {
            var server = helper.getLevel().getServer();
            helper.assertFalse(PlayerKind.isChild(camera.getUUID()),
                    Component.literal("a registered service client must not read as a child"));
            helper.assertTrue(ActingChild.of(server) == null,
                    Component.literal("the only player in this world is the mod's own client, so there is NO child "
                            + "to act for — and answering with the camera is the bug: "
                            + ActingChild.of(server)));
            helper.assertTrue(ActingChild.all(server).isEmpty(),
                    Component.literal("and it is not in the room either"));

            // The other half, so the filter cannot pass by refusing everybody.
            PlayerKind.forgetServiceClient(camera.getUUID());
            helper.assertTrue(ActingChild.of(server) == camera,
                    Component.literal("an ordinary player must still be found — this is the arm that fails if the "
                            + "filter simply refuses everyone"));
        } finally {
            PlayerKind.forgetServiceClient(camera.getUUID());
            MockPlayerNetwork.remove(camera);
        }
        helper.succeed();
    }

    /**
     * <b>A stand-in whose own test has taken him out of the world is nobody.</b>
     *
     * <p>{@link ActingChild}'s stand-in is one global. In full Gate B runs this class's first test was answered with
     * {@code 'gtnextwish'} and {@code 'gtfollows'} — mocks from {@code BuilderEditGameTest}, each already
     * {@code removed=UNLOADED_WITH_PLAYER} by its own test — because a test's clean-up had put back a "before"
     * stand-in that was itself retired. That clean-up is fixed ({@code MockPlayerNetwork.actingChildFor}); this pins
     * the product half: a player who has left the world is never the child a tool acts for, whoever registered
     * him. Staged the way the long run left it: the stand-in is a mock that has been removed.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void aStandInWhoHasLeftTheWorldIsNobody(GameTestHelper helper) {
        ServerPlayer camera = MockPlayerNetwork.inLevel(helper);
        ServerPlayer gone = MockPlayerNetwork.inLevel(helper, "gtgone");
        ServerPlayer before = ActingChild.currentStandIn();
        PlayerKind.registerServiceClient(camera.getUUID());
        try {
            var server = helper.getLevel().getServer();
            ActingChild.standIn(gone);
            MockPlayerNetwork.remove(gone);   // what the stand-in's own test does when it ends
            helper.assertTrue(gone.isRemoved(), Component.literal("precondition: the stand-in has left the world"));
            // WHOSE WISH: NONE. ActingChild.of(server) asks AgentContext.currentRun() first, and on the server thread
            // (no worker bound) that falls back to the ONE wish in flight when there is exactly one: another test's,
            // whose owner is not this stand-in. On a GitHub runner that turned this red (#40) with "a stand-in in the
            // world must still be the child: null". This test is about the stand-in rule, so it asks with no owner.
            helper.assertTrue(ActingChild.of(server, null) == null,
                    Component.literal("a stand-in who has left the world must not be the child: "
                            + ActingChild.of(server, null)));
            helper.assertTrue(ActingChild.person(server, null) == null,
                    Component.literal("nor the person a tool acts for: " + ActingChild.person(server, null)));

            // The other half: a stand-in still in the world is found, so this cannot pass by ignoring stand-ins.
            ServerPlayer here = MockPlayerNetwork.inLevel(helper, "gthere");
            try {
                PlayerKind.registerServiceClient(here.getUUID());   // not in the child list: ONLY the stand-in finds him
                ActingChild.standIn(here);
                helper.assertTrue(ActingChild.of(server, null) == here,
                        Component.literal("a stand-in in the world must still be the child: "
                                + ActingChild.of(server, null) + " (wishes in flight: "
                                + Marshal.global().working().size() + ", run the global lookup sees: "
                                + AgentContext.currentRun() + ")"));
            } finally {
                PlayerKind.forgetServiceClient(here.getUUID());
                MockPlayerNetwork.remove(here);
            }
        } finally {
            ActingChild.standIn(before);
            PlayerKind.forgetServiceClient(camera.getUUID());
            MockPlayerNetwork.remove(camera);
            MockPlayerNetwork.remove(gone);
        }
        helper.succeed();
    }

    /**
     * <b>THE D9 GATE: the owner wins over join order.</b>
     *
     * <p>One assertion for thirty-four sites, and that is the point of the sweep — they all ask
     * {@link ActingChild} now, so the behaviour is testable once instead of per tool. A per-site
     * gate would be thirty-four fixtures asserting the same sentence, and the thirty-fifth site
     * would still be unguarded; {@code SourceRuleSweepTest} covers that half by refusing the idiom
     * in {@code agent/tools} at all.
     *
     * <p><b>Verified to fail</b> against the pre-sweep resolution: Alice joined first, so
     * {@code findFirst()} answers Alice, and Bob's {@code take_me_to} teleports his brother.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 1200)
    public static void theOwnerWinsOverWhoeverJoinedFirst(GameTestHelper helper) {
        ServerPlayer alice = MockPlayerNetwork.inLevel(helper);
        ServerPlayer bob = MockPlayerNetwork.inLevel(helper);
        UUID bobsWish = UUID.nameUUIDFromBytes("serviceclient-owner-bob".getBytes());
        Marshal m = Marshal.global();
        String worker = "gt-worker-" + bobsWish;
        try {
            var server = helper.getLevel().getServer();
            // Join order is the whole premise: if the framework ever hands them back the other way
            // round this test would pass with the fix deleted, so it is stated rather than assumed.
            helper.assertTrue(server.getPlayerList().getPlayers().indexOf(alice)
                            < server.getPlayerList().getPlayers().indexOf(bob),
                    Component.literal("this test needs Alice to be FIRST in the list; she is not"));

            m.submit(bobsWish, bob.getUUID(), "a rocket ship");
            m.assign(worker, bobsWish);
            AgentContext.bind(worker);
            try {
                helper.assertTrue(ActingChild.of(server) == bob,
                        Component.literal("the wish is Bob's, so the tool must act on BOB — it answered "
                                + (ActingChild.of(server) == alice ? "Alice, who merely joined "
                                        + "first" : String.valueOf(ActingChild.of(server)))));
            } finally {
                AgentContext.unbind();
            }

            // ⚠ UNBINDING THE WORKER IS NOT THE SAME AS THERE BEING NO RUN, and the first version
            // of this assertion got that wrong — it read "with nothing bound" and expected Alice,
            // and the gate went red on the PASS arm. AgentContext.currentRun falls through to
            // marshal.working() and returns the sole live run when there is exactly one: it only
            // refuses to guess when two are live. So after unbind, Bob's wish was still the only
            // one working and the owner still resolved to Bob. The product was right; the test
            // was asserting something false about it.
            //
            // Finish the wish, so there genuinely is no run to resolve. THEN the seam's floor is
            // the thing under test: with no owner it keeps today's answer — join order — rather
            // than refusing. That is D10's deliberate floor, and asserting it here means changing
            // it is a visible edit to a test rather than a quiet drift.
            m.finish(bobsWish);
            helper.assertTrue(ActingChild.of(server) == alice,
                    Component.literal("with no run resolvable at all, the seam still answers join order, on purpose "
                            + "— it answered " + ActingChild.of(server)));
        } finally {
            try {
                m.finish(bobsWish);   // idempotent; the body finishes it too, and may not reach it
            } catch (RuntimeException ignored) {
                // best effort — a failed test must not poison the next one
            }
            MockPlayerNetwork.remove(alice);
            MockPlayerNetwork.remove(bob);
        }
        helper.succeed();
    }

    /**
     * <b>Our offline-mode UUID must be vanilla's, or the camera is registered as a player that
     * does not exist and joins as an ordinary child.</b>
     *
     * <p>{@link PlayerKind#offlineId} reimplements {@code UUIDUtil.createOfflinePlayerUUID} rather
     * than calling it, so the rule class stays Minecraft-free and a launcher can declare the camera
     * a non-child without booting a server. That is a copy, and a copy needs an oracle — the unit
     * test beside it can only pin our answer against our own formula, which is circular.
     *
     * <p>This asks <b>vanilla</b>. It is cheap and it is the difference between "the two agree" and
     * "the two agreed when I wrote it down".
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void ourOfflineIdIsTheOneMinecraftWouldMint(GameTestHelper helper) {
        for (String name : new String[] {"Moddy-Camera", "Steve", "a", "MOD_camera-01"}) {
            helper.assertTrue(
                    PlayerKind.offlineId(name).equals(
                            net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(name)),
                    Component.literal("offlineId(\"" + name + "\") must equal vanilla's: ours "
                            + PlayerKind.offlineId(name) + " vs "
                            + net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(name)));
        }
        helper.succeed();
    }

    /**
     * <b>The viewpoint prefers the camera, and taking it costs the child nothing.</b>
     *
     * <p>Two claims, and the second is the one the feature exists for: a camera in the world is
     * chosen over the child, AND choosing it does not freeze him, does not make him invulnerable
     * and does not spin his view. "The camera was used" and "the child was left alone" are
     * different statements and only the second is what a seven-year-old notices.
     *
     * <p><b>Verified to fail</b> with {@code Viewpoint.acquire}'s camera branch removed: it answers
     * {@code CHILDS_EYES(...)} and the child is frozen for a photograph he is not in.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void theCameraIsPreferredAndTheChildIsLeftAlone(GameTestHelper helper) {
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        ServerPlayer camera = MockPlayerNetwork.inLevel(helper);
        PlayerKind.registerServiceClient(camera.getUUID());
        // ⚠ STAGED: ONE OTHER WISH IS LIVE, as it is in a long Gate B run whenever an earlier test left one working
        //. With no worker bound, AgentContext.currentRun() falls through to "the sole live run", so the
        // take() below is attributed to THAT wish and holds the camera for it — and the first claim further down
        // was queued behind a wish nobody here owns ("a free camera goes to the wish that asked", red twice in
        // full runs, green alone). Staged here so the test means the same thing in a fresh world and a long run.
        Marshal m = Marshal.global();
        UUID strayWish = UUID.randomUUID();
        String strayWorker = "gt-serviceclient-stray-" + strayWish;
        m.submit(strayWish, UUID.randomUUID(), "somebody else's wish, still being granted");
        m.assign(strayWorker, strayWish);
        UUID childsWish = UUID.randomUUID();
        String childsWorker = "gt-serviceclient-child-" + childsWish;
        try {
            var server = helper.getLevel().getServer();

            Viewpoint view = Viewpoint.acquire(server);
            helper.assertTrue(view != null && view.isCamera(),
                    Component.literal("a camera in the world must be preferred over the child; got " + view));
            helper.assertTrue(view.player() == camera, Component.literal("and it must be THE camera; got " + view));

            helper.assertTrue(view.take() == WishCapture.Lease.TAKEN,
                    Component.literal("a free camera must be granted at once"));
            // Put the take down before the queue is asked about, as every real take is: the claims below are
            // the two wishes' own, and a lease this call left behind for whatever run it was attributed to
            // would answer for them.
            view.release();
            // ⚠ THIS ASSERTION'S MESSAGE USED TO SAY "a camera has nothing to lease", AND IT WAS
            // WRONG. A camera has ONE BODY, so two wishes cannot both spin it -- they did,
            // and both photographs were of somewhere else with every gate green. What a camera has
            // nothing of is the RITUAL: no freeze, no invulnerability, nobody to tell. Leasing and
            // freezing are different things and only the second one is the child's.
            UUID firstWish = UUID.randomUUID();
            UUID secondWish = UUID.randomUUID();
            helper.assertTrue(WishCapture.claimCameraFor(camera.getUUID(), firstWish),
                    Component.literal("a free camera goes to the wish that asked"));
            helper.assertFalse(WishCapture.claimCameraFor(camera.getUUID(), secondWish),
                    Component.literal("🔴 a second wish must WAIT for the camera, not aim it mid-settle"));
            WishCapture.releaseCameraLease(camera.getUUID(), firstWish);
            // The SAME wish, deliberately: it was put in line, so it is the one that must be
            // served. "somebody eventually got it" would pass with no order at all.
            helper.assertTrue(WishCapture.claimCameraFor(camera.getUUID(), secondWish),
                    Component.literal("...and the wish that waited is the one that gets it"));
            WishCapture.forgetForTest(camera.getUUID());
            helper.assertFalse(WishCapture.isCapturing(child.getUUID()),
                    Component.literal("🔴 the child must not have been frozen for a photograph he is not in"));
            helper.assertFalse(child.isInvulnerable(),
                    Component.literal("nor made invulnerable for one"));
            helper.assertFalse(WishCapture.isCapturing(camera.getUUID()),
                    Component.literal("and the camera itself needs no capture session either"));

            // The other half: take the camera away and the child's eyes are borrowed as before, so
            // this cannot pass by never using anybody.
            // Asked as the child's OWN wish: with the stray wish the only live run, an unbound call is that
            // stranger's, and his eyes are rightly nobody's to borrow for it (ActingChild.online).
            PlayerKind.forgetServiceClient(camera.getUUID());
            m.submit(childsWish, child.getUUID(), "his own wish");
            m.assign(childsWorker, childsWish);
            AgentContext.bind(childsWorker);
            try {
                Viewpoint fallback = Viewpoint.acquire(server);
                helper.assertTrue(fallback != null && !fallback.isCamera() && fallback.player() == child,
                        Component.literal("with no camera the agent falls back to the child's eyes; got " + fallback));
            } finally {
                AgentContext.unbind();
            }
        } finally {
            for (UUID w : new UUID[] {strayWish, childsWish}) {
                try {
                    m.finish(w);
                } catch (RuntimeException ignored) {
                    // best effort — a failed test must not poison the next one
                }
            }
            PlayerKind.forgetServiceClient(camera.getUUID());
            WishCapture.exit(child);
            WishCapture.exit(camera);
            MockPlayerNetwork.remove(child);
            MockPlayerNetwork.remove(camera);
        }
        helper.succeed();
    }

    // ⚠ THERE WAS A SECOND TEST HERE, AND IT WAS DELETED RATHER THAN TUNED.
    //
    // It drove modify_mob end to end: a pig at world spawn, a camera far away, and the claim that
    // with the camera correctly not a child the tool falls back to world spawn and finds the pig.
    // It passed. It also passed with the registerServiceClient call removed — twice, once sharing a
    // batch and once alone in its own — so it could not tell the fix from its absence.
    //
    // Why it could not is unestablished, and that is the point: modify_mob has a referent path and
    // a nearest path ahead of the distance search, so "the tool succeeded" has several ways of being
    // true that have nothing to do with which body it measured from. An end-to-end assertion over a
    // subject whose resolution you cannot observe is not weak coverage, it is DECORATION — green
    // over the exact defect it names, which is the shape this repo keeps recording.
    //
    // What the claim actually needs is an assertion on the resolution itself, and that is the test
    // above: it asks ActingChild directly and carries its own A/B, registering the camera and then
    // forgetting it in one run. Restoring an end-to-end leg would need a tool whose choice of body
    // is observable in the world it leaves behind.
}
