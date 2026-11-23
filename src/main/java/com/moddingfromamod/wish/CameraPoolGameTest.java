package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.PlayerKind;
import com.moddingfromamod.testing.MockPlayerNetwork;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Two concurrent wishes each get a body of their own</b>, the wish cam pool.
 *
 * <p>Two children, two cameras, two wishes photographing at once. Each wish acquires its viewpoint
 * and takes the lease exactly as {@code PhotographTool} does (same server-thread lambda, owner read
 * off {@link AgentContext}), and Bob asks while Alice's wish still holds hers.
 *
 * <p><b>Seen red before the pool:</b> {@link Viewpoint#acquire} returned the FIRST non-child in the
 * player list, so both wishes landed on camera one, Bob's lease came back {@code BUSY} and camera
 * two stood idle — the unframed-fallback path the plan's exit criterion names.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class CameraPoolGameTest {

    /**
     * One batch PER TEST: mock players and the service-client set are process-wide, and tests in
     * one batch run concurrently — sharing one made each test see the other's cameras.
     */
    private static final String BATCH = "cameraPool";
    private static final String BATCH_ONE = "cameraPoolOne";

    private static final int TIMEOUT_TICKS = 40_000;

    private CameraPoolGameTest() {
    }

    private static Thread as(UUID child, String label, Runnable body) {
        UUID wish = UUID.randomUUID();
        String worker = "gt-campool-" + label + "-" + wish;
        Marshal.global().submit(wish, child, "gt camera pool " + label);
        Marshal.global().assign(worker, wish);
        Thread t = new Thread(() -> {
            AgentContext.bind(worker);
            try {
                body.run();
            } finally {
                AgentContext.unbind();
                Marshal.global().finish(wish);
            }
        }, "gametest-campool-" + label);
        t.setDaemon(true);
        return t;
    }

    /** What one wish got: which body, and what the lease said. */
    private record Got(UUID body, String lease) {
    }

    /** {@code PhotographTool}'s own two steps, in one server-thread lambda as it does them. */
    private static Got shoot() {
        return AgentBridge.onServer(() -> {
            Viewpoint v = Viewpoint.acquire(AgentBridge.serverOrNull());
            if (v == null || v.player() == null) {
                return new Got(null, "nobody");
            }
            return new Got(v.player().getUUID(), v.take().name());
        });
    }

    private static void releaseAll(ServerPlayer... cams) {
        for (ServerPlayer c : cams) {
            WishCapture.forgetForTest(c.getUUID());
            PlayerKind.forgetServiceClient(c.getUUID());
            MockPlayerNetwork.remove(c);
        }
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void twoConcurrentWishesEachGetTheirOwnCamera(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer camOne = MockPlayerNetwork.inLevel(helper);
        ServerPlayer camTwo = MockPlayerNetwork.inLevel(helper);
        PlayerKind.registerServiceClient(camOne.getUUID());
        PlayerKind.registerServiceClient(camTwo.getUUID());
        ServerPlayer alice = MockPlayerNetwork.inLevel(helper);
        ServerPlayer bob = MockPlayerNetwork.inLevel(helper);

        AtomicReference<Got> aliceFirst = new AtomicReference<>();
        AtomicReference<Got> aliceAgain = new AtomicReference<>();
        AtomicReference<Got> bobGot = new AtomicReference<>();
        AtomicReference<Boolean> aliceHeldWhenBobAsked = new AtomicReference<>();

        Thread a = as(alice.getUUID(), "alice", () -> {
            aliceFirst.set(shoot());
            Thread b = as(bob.getUUID(), "bob", () -> {
                Got mine = aliceFirst.get();
                aliceHeldWhenBobAsked.set(mine.body() != null
                        && WishCapture.cameraHeldBy(mine.body()) != null);
                bobGot.set(shoot());
            });
            b.start();
            try {
                b.join(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // The next angle of Alice's orbit — after Bob took the other body.
            aliceAgain.set(shoot());
        });
        a.start();

        helper.succeedWhen(() -> {
            helper.assertTrue(aliceAgain.get() != null, Component.literal("waiting for both wishes"));
            try {
                Got first = aliceFirst.get();
                Got bobs = bobGot.get();
                helper.assertValueEqual(first.lease(), "TAKEN", Component.literal("Alice's lease"));
                helper.assertTrue(first.body().equals(camOne.getUUID())
                                || first.body().equals(camTwo.getUUID()),
                        Component.literal("Alice was photographed through a child, not a camera: " + first.body()));
                helper.assertTrue(Boolean.TRUE.equals(aliceHeldWhenBobAsked.get()),
                        Component.literal("Alice must still hold her camera when Bob asks, or this proves nothing"));
                helper.assertValueEqual(bobs.lease(), "TAKEN",
                        Component.literal("Bob's lease while Alice holds a camera and the OTHER camera is idle — "
                                + "BUSY means both wishes were sent to one body and Bob's card "
                                + "falls back to the unframed snapshot"));
                helper.assertTrue(!bobs.body().equals(first.body()),
                        Component.literal("both wishes were given the same camera"));
                helper.assertTrue(bobs.body().equals(camOne.getUUID())
                                || bobs.body().equals(camTwo.getUUID()),
                        Component.literal("Bob was photographed through a child, not a camera: " + bobs.body()));
                helper.assertValueEqual(aliceAgain.get().body(), first.body(),
                        Component.literal("Alice's next angle hopped to another body mid-orbit"));
                helper.assertValueEqual(aliceAgain.get().lease(), "TAKEN",
                        Component.literal("Alice's next angle"));
            } finally {
                releaseAll(camOne, camTwo);
                MockPlayerNetwork.remove(alice);
                MockPlayerNetwork.remove(bob);
            }
        });
    }

    /**
     * <b>With ONE camera the second wish queues, and the queue says so</b> — the measurement
     * {@link CameraSupervisor} grows the pool from. If {@code queueDepth} stopped reporting a waiter,
     * the pool would never grow and this test is where that goes red.
     */
    @GameTest(template = "empty_test", batch = BATCH_ONE, timeoutTicks = TIMEOUT_TICKS)
    public static void oneCameraQueuesTheSecondWishAndReportsTheDepth(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer cam = MockPlayerNetwork.inLevel(helper);
        PlayerKind.registerServiceClient(cam.getUUID());
        ServerPlayer alice = MockPlayerNetwork.inLevel(helper);
        ServerPlayer bob = MockPlayerNetwork.inLevel(helper);

        AtomicReference<Got> aliceGot = new AtomicReference<>();
        AtomicReference<Got> bobGot = new AtomicReference<>();
        AtomicReference<Integer> depth = new AtomicReference<>();

        Thread a = as(alice.getUUID(), "alice1", () -> {
            aliceGot.set(shoot());
            Thread b = as(bob.getUUID(), "bob1", () -> {
                bobGot.set(shoot());
                depth.set(AgentBridge.onServer(() -> CameraSupervisor.poolWaiting(
                        AgentBridge.serverOrNull())));
            });
            b.start();
            try {
                b.join(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        a.start();

        helper.succeedWhen(() -> {
            helper.assertTrue(depth.get() != null, Component.literal("waiting for both wishes"));
            try {
                helper.assertValueEqual(aliceGot.get().lease(), "TAKEN", Component.literal("Alice's lease"));
                helper.assertValueEqual(bobGot.get().body(), cam.getUUID(),
                        Component.literal("with one camera Bob must queue on it, never borrow a child's eyes"));
                helper.assertValueEqual(bobGot.get().lease(), "BUSY",
                        Component.literal("one body, two wishes: the second waits"));
                helper.assertValueEqual(depth.get(), 1,
                        Component.literal("the pool's measurement must count Bob waiting"));
            } finally {
                releaseAll(cam);
                MockPlayerNetwork.remove(alice);
                MockPlayerNetwork.remove(bob);
            }
        });
    }
}
