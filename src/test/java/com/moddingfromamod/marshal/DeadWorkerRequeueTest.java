package com.moddingfromamod.marshal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.moddingfromamod.agent.WishQueue;
import com.moddingfromamod.health.WishTimeoutPolicy;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishSource;
import com.moddingfromamod.wish.WishStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A wish whose worker died of the subscription quota is re-claimed, not timed out</b>.
 *
 * <p>Driven through the path REAL workers take — {@link WishQueue#await} then
 * {@link Marshal#assign} — not {@code Marshal.claim}, which only the scripted pool uses: a re-queue
 * proven there would prove something no live worker does (the gap the survey found).
 */
class DeadWorkerRequeueTest {

    private static Wish wish(String text) {
        return new Wish(UUID.randomUUID(), text, WishSource.TYPED, WishStatus.PENDING, 0L,
                UUID.randomUUID(), "kid", "minecraft:overworld", 0, 0, 0, "", "", 0L);
    }

    /** One worker taking the next wish the way AwaitWishTool does. */
    private static Wish take(WishQueue q, Marshal m, String mcp) throws Exception {
        AtomicReference<Wish> got = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                got.set(q.await(2000, mcp).wish());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        t.start();
        t.join(3000);
        Wish w = got.get();
        if (w != null) {
            m.submit(w.id(), w.playerId(), w.text());
            m.assign(mcp, w.id());
        }
        return w;
    }

    @Test
    @DisplayName("worker A dies of the quota mid-wish -> the wish goes back and worker B re-claims it")
    void theWishInFlightIsReclaimedByTheNextWorker() throws Exception {
        FakeClock clock = new FakeClock(1_000_000);
        Marshal m = Marshal.forTest(clock, 2);
        WishQueue q = new WishQueue();
        Wish dragon = wish("a dragon");
        m.submit(dragon.id(), dragon.playerId(), dragon.text());   // WishService made the run
        clock.advance(5_000);
        q.offer(dragon);

        assertSame(dragon, take(q, m, "mcp-a"));
        WishRun run = m.run(dragon.id());
        assertEquals("mcp-a", run.workerId());

        // Twenty minutes into the grant the window runs out; the shim records the death.
        clock.advance(20 * 60_000);
        Map<String, DeadWorkerRequeue.Death> deaths = new HashMap<>();
        deaths.put("wish-granter-1", new DeadWorkerRequeue.Death("quota", clock.nowMs()));
        Map<String, String> names = Map.of("mcp-a", "wish-granter-1", "mcp-b", "wish-granter-1");

        List<DeadWorkerRequeue.Requeued> back = DeadWorkerRequeue.sweep(m, names::get,
                w -> Optional.ofNullable(deaths.get(w)));
        assertEquals(1, back.size());
        assertSame(run, back.get(0).run());
        assertEquals("quota", back.get(0).reason());
        assertNull(run.workerId(), "the dead worker no longer holds it");
        assertEquals(RunState.GETTING_READY, run.state(), "live, not settled");

        // Not "never claimed": its claim clock restarted at the re-queue, though it is 20 min old.
        WishTimeoutPolicy policy = new WishTimeoutPolicy(120, 900, 300, clock);
        clock.advance(10_000);
        assertTrue(policy.judge(WishTimeoutPolicy.Candidate.of(run), clock.nowMs()).isEmpty(),
                "a re-queued wish must not time out before a replacement can boot");

        // The caller offers it back; the replacement (same slot name, new session) takes it.
        q.offer(dragon);
        deaths.remove("wish-granter-1");     // HealthService consumes the death once it acts
        assertSame(dragon, take(q, m, "mcp-b"), "the next worker re-claims the SAME wish");
        assertEquals("mcp-b", run.workerId());
    }

    @Test
    @DisplayName("a death older than the claim never takes the wish from the live worker holding it")
    void anOldDeathDoesNotTouchTheReplacementsWish() throws Exception {
        FakeClock clock = new FakeClock(1_000_000);
        Marshal m = Marshal.forTest(clock, 1);
        WishQueue q = new WishQueue();
        long diedAt = clock.nowMs();
        clock.advance(30_000);
        Wish castle = wish("a castle");
        m.submit(castle.id(), castle.playerId(), castle.text());
        q.offer(castle);
        take(q, m, "mcp-b");

        List<DeadWorkerRequeue.Requeued> back = DeadWorkerRequeue.sweep(m, mcp -> "wish-granter-1",
                w -> Optional.of(new DeadWorkerRequeue.Death("quota", diedAt)));
        assertTrue(back.isEmpty());
        assertEquals("mcp-b", m.run(castle.id()).workerId());
    }

    @Test
    @DisplayName("no recorded death: nothing moves (a server without the shim)")
    void noDeathNoChange() throws Exception {
        FakeClock clock = new FakeClock(1_000_000);
        Marshal m = Marshal.forTest(clock, 1);
        WishQueue q = new WishQueue();
        Wish w = wish("a boat");
        m.submit(w.id(), w.playerId(), w.text());
        q.offer(w);
        take(q, m, "mcp-a");
        assertTrue(DeadWorkerRequeue.sweep(m, mcp -> "wish-granter-1", x -> Optional.empty()).isEmpty());
        assertEquals("mcp-a", m.run(w.id()).workerId());
        assertEquals(m.run(w.id()).createdMs(), m.run(w.id()).waitingSinceMs(),
                "never re-queued: the claim clock is exactly what it was before #1572");
    }
}
