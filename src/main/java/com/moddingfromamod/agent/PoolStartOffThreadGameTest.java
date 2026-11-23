package com.moddingfromamod.agent;

import com.moddingfromamod.testing.GateShards;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>A join never waits for the wish pool to start.</b>
 *
 * <p>The live log of 2026-11-16 showed it: the server thread spent ~1.7 s inside
 * {@code AgentSession.start} on every first join ("item pack offered" 20:49:52.569, "wish agent 1 of
 * 6" 20:49:54.308), then "Can't keep up! … 2164ms" — the world frozen for the child who had just
 * arrived, and for the camera on its own join.
 *
 * <p>This drives the REAL join: {@link MockPlayerNetwork#inLevel} calls vanilla's
 * {@code placeNewPlayer}, which posts {@code PlayerLoggedInEvent} to the real {@code
 * WishAgent.onPlayerJoin}. The pool's start is replaced by a fake that sleeps {@value #SLOW_MS} ms (a
 * gate never launches a real worker), and the server thread's time across the join is measured
 * against {@value #BUDGET_MS} ms.
 *
 * <p>🔴 <b>Verified to fail</b> with the start moved back onto the server thread — {@code
 * AgentSession.wake("a join")} in {@code onPlayerJoin} replaced by {@code AgentSession.wakeStart.run()}:
 * the join then takes the fake's whole {@value #SLOW_MS} ms and the budget assertion names it.
 *
 * <p>Its own batch: the seams are statics, and a neighbour's wish would otherwise wake this fake.
 */
@GameTestHolder(GateShards.DEFAULT)
public class PoolStartOffThreadGameTest {

    static final long SLOW_MS = 1500;
    static final long BUDGET_MS = 400;

    @GameTest(template = "empty_test", batch = "poolStartOffThread", timeoutTicks = 2400)
    public static void aFirstJoinNeverWaitsForThePoolToStart(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        var realWanted = AgentSession.wakeWanted;
        var realStart = AgentSession.wakeStart;
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<String> startedOn = new AtomicReference<>("");
        String serverThread = Thread.currentThread().getName();
        AgentSession.wakeWanted = () -> starts.get() == 0;     // the pool is down until the fake "starts" it
        AgentSession.wakeStart = () -> {
            startedOn.set(Thread.currentThread().getName());
            try {
                Thread.sleep(SLOW_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            starts.incrementAndGet();
        };
        MockPlayerNetwork.afterTest(helper, () -> {      // put back whether it passes or fails
            AgentSession.wakeWanted = realWanted;
            AgentSession.wakeStart = realStart;
        });
        long t0 = System.nanoTime();
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);     // the real join, through the event bus
        long joinMs = (System.nanoTime() - t0) / 1_000_000L;
        MockPlayerNetwork.afterTest(helper, () -> MockPlayerNetwork.remove(child));
        helper.assertTrue(joinMs < BUDGET_MS, Component.literal("the server thread spent " + joinMs
                + " ms in a first join whose pool start takes " + SLOW_MS + " ms — the start is on the "
                + "server thread again, and the world freezes for the child who just arrived (#2487)"));
        // A second join while the first start is still running (the camera arriving with the child):
        // neither waits, and the pool is not started twice.
        long t1 = System.nanoTime();
        ServerPlayer camera = MockPlayerNetwork.inLevel(helper);
        long secondMs = (System.nanoTime() - t1) / 1_000_000L;
        MockPlayerNetwork.afterTest(helper, () -> MockPlayerNetwork.remove(camera));
        helper.assertTrue(secondMs < BUDGET_MS, Component.literal("a second join during the start took "
                + secondMs + " ms"));
        helper.succeedWhen(() -> {
            helper.assertTrue(starts.get() >= 1, Component.literal("the pool's start never ran"));
            helper.assertFalse(AgentSession.waking(), Component.literal("still starting"));
            helper.assertTrue(starts.get() == 1, Component.literal("two joins started the pool "
                    + starts.get() + " times — the off-thread start raced"));
            helper.assertTrue(startedOn.get().equals("wish-pool-wake") && !startedOn.get().equals(serverThread),
                    Component.literal("the start ran on '" + startedOn.get() + "', not its own daemon thread"));
        });
    }
}
