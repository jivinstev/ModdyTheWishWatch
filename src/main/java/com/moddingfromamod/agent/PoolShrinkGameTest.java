package com.moddingfromamod.agent;

import net.minecraft.network.chat.Component;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.Clock;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.PoolPlan;
import com.moddingfromamod.marshal.WorkerRoster;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishSource;
import com.moddingfromamod.wish.WishStatus;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B: <b>the pool shrinks back to one spare after a burst</b>, driven where it RUNS.
 *
 * <p>{@code PoolPlanTest} proves the rule where it is DEFINED. This drives
 * {@link AgentSession#shrinkStep} — the method the supervisor calls every three seconds — against a
 * real {@link WorkerRoster}, a real {@link Marshal} holding a real wish, and a real {@link WishQueue}
 * with real threads parked in {@code await}, which is where the one failure that would cost a child
 * lives: a stopped worker's still-blocked {@code await_wish} taking the next wish to a dead process.
 *
 * <p>Everything is a PRIVATE instance (roster, queue, marshal), never the globals: GameTests in a
 * batch run concurrently and share every static, and a thread parked on the global queue would
 * steal a neighbour's wish. A gate never launches a real worker, so the {@code claude stop} half is
 * not reached here; it is the same {@code stopOne} the deaf-worker path has always used.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class PoolShrinkGameTest {

    private static Wish wish(String text) {
        return new Wish(UUID.randomUUID(), text, WishSource.TYPED, WishStatus.PENDING, 0L,
                UUID.randomUUID(), "kid", "minecraft:overworld", 0, 0, 0, "", "", 0L);
    }

    private static void park(WishQueue q, String mcpId, AtomicReference<Wish> got) {
        Thread t = new Thread(() -> {
            try {
                got.set(q.await(20_000L, mcpId).wish());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "gt-shrink-park-" + mcpId);
        t.setDaemon(true);
        t.start();
    }

    /** A slot launched long ago, heard on {@code mcpId}, with a CLI session id it can be stopped by. */
    private static void slot(WorkerRoster r, String name, String mcpId, long bootedAt) {
        r.booted(name, bootedAt);
        r.cliId(name, "cli-" + mcpId);
        r.heard(name, mcpId, bootedAt + 5_000L);
    }

    /**
     * After a burst: three workers parked and idle past the grace, a fourth holding a wish. The pool
     * drains ONE per tick from the top, keeps the busy one and the last spare, and a wish offered
     * afterwards goes to the spare — never to a stopped worker's ghost await.
     */
    @GameTest(template = "empty_test", timeoutTicks = 2000)
    public static void aBurstDrainsToOneSpareAndAGhostNeverTakesAWish(GameTestHelper helper) {
        WorkerRoster roster = new WorkerRoster();
        WishQueue queue = new WishQueue();
        Marshal marshal = Marshal.forTest(Clock.system(), 4);
        long now = System.currentTimeMillis();
        long longAgo = now - 30 * 60_000L;
        long grace = PoolPlan.EXTRA_IDLE_STOP_MS;

        slot(roster, "wish-granter-1", "gt-shrink-1", longAgo);
        slot(roster, "wish-granter-2", "gt-shrink-2", longAgo);
        slot(roster, "wish-granter-3", "gt-shrink-3", longAgo);
        slot(roster, "wish-granter-4", "gt-shrink-4", longAgo);
        // wish-granter-4 is granting a wish: in the marshal, not parked.
        Wish held = wish("a castle");
        marshal.submit(held.id(), held.playerId(), held.text());
        marshal.assign("gt-shrink-4", held.id());

        AtomicReference<Wish> got1 = new AtomicReference<>();
        AtomicReference<Wish> got2 = new AtomicReference<>();
        AtomicReference<Wish> got3 = new AtomicReference<>();
        park(queue, "gt-shrink-1", got1);
        park(queue, "gt-shrink-2", got2);
        park(queue, "gt-shrink-3", got3);
        Wish next = wish("a dragon");

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(queue.waitingWorkers() == 3,
                        Component.literal("three workers parked (" + queue.waitingWorkers() + ")")))
                .thenExecute(() -> {
                    // Inside the grace: a worker that finished a wish a moment ago is kept for the
                    // child's follow-up. The idle clock sees 4 busy and 1..3 idle since boot, so
                    // pretend the boot was recent for this one probe.
                    helper.assertTrue(AgentSession.shrinkStep(roster, queue, marshal,
                                    longAgo + PoolPlan.BOOT_GRACE_MS + 1, grace) == null,
                            Component.literal("nothing has been idle for the grace yet"));

                    String first = AgentSession.shrinkStep(roster, queue, marshal, now, grace);
                    helper.assertTrue("wish-granter-3".equals(first),
                            Component.literal("the top idle slot goes first, got " + first));
                    helper.assertTrue(queue.isRetired("gt-shrink-3"),
                            Component.literal("it is retired from the queue BEFORE the process is stopped"));
                    helper.assertTrue(queue.waitingWorkers() == 2, Component.literal("and no longer counted as listening"));
                    roster.forget(first);   // what AgentSession.stopIdleExtra does next

                    String second = AgentSession.shrinkStep(roster, queue, marshal, now + 3_000L, grace);
                    helper.assertTrue("wish-granter-2".equals(second), Component.literal("one per tick: then -2, got " + second));
                    roster.forget(second);

                    String third = AgentSession.shrinkStep(roster, queue, marshal, now + 6_000L, grace);
                    helper.assertTrue(third == null, Component.literal("the last free worker is the spare and stays, got " + third));
                    helper.assertTrue(roster.snapshot().stream().anyMatch(s -> s.name().equals("wish-granter-4")),
                            Component.literal("the worker holding a wish is never stopped"));
                    helper.assertTrue(PoolPlan.spawnsNeeded(2, 1, 2, 4) == 0,
                            Component.literal("two running, one busy: the spare left means the top-up does not relaunch"));

                    queue.offer(next);
                })
                .thenWaitUntil(() -> helper.assertTrue(got1.get() != null,
                        Component.literal("the spare (wish-granter-1) must be handed the wish")))
                .thenExecute(() -> {
                    helper.assertTrue(got1.get() == next, Component.literal("the spare got a different wish"));
                    helper.assertTrue(got2.get() == null && got3.get() == null,
                            Component.literal("a stopped worker's await took the wish — it would be granted by nobody"));
                    helper.assertTrue(queue.size() == 0, Component.literal("nothing left waiting"));
                })
                .thenSucceed();
    }

    /**
     * The idle clock is the MARSHAL's: a worker that held a wish until a moment ago is not idle,
     * however long ago it booted — the follow-up wish ("now give him wings") gets its warm worker.
     */
    @GameTest(template = "empty_test", timeoutTicks = 2000)
    public static void aWorkerThatJustFinishedAWishIsKeptForTheGrace(GameTestHelper helper) {
        WorkerRoster roster = new WorkerRoster();
        WishQueue queue = new WishQueue();
        Marshal marshal = Marshal.forTest(Clock.system(), 2);
        long now = System.currentTimeMillis();
        long longAgo = now - 30 * 60_000L;
        slot(roster, "wish-granter-1", "gt-shrink-b1", longAgo);
        slot(roster, "wish-granter-2", "gt-shrink-b2", longAgo);
        Wish w = wish("a boat");
        marshal.submit(w.id(), w.playerId(), w.text());
        marshal.assign("gt-shrink-b2", w.id());

        AtomicReference<Wish> got1 = new AtomicReference<>();
        AtomicReference<Wish> got2 = new AtomicReference<>();
        park(queue, "gt-shrink-b1", got1);

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(queue.waitingWorkers() == 1, Component.literal("worker 1 parked")))
                .thenExecute(() -> {
                    // A tick while -2 holds the wish stamps its clock.
                    helper.assertTrue(AgentSession.shrinkStep(roster, queue, marshal, now, 120_000L) == null,
                            Component.literal("one listener and one busy worker: nothing to stop"));
                    marshal.finish(w.id());
                    park(queue, "gt-shrink-b2", got2);
                })
                .thenWaitUntil(() -> helper.assertTrue(queue.waitingWorkers() == 2, Component.literal("both parked")))
                .thenExecute(() -> {
                    String stopped = AgentSession.shrinkStep(roster, queue, marshal, now + 60_000L, 120_000L);
                    helper.assertTrue("wish-granter-1".equals(stopped),
                            Component.literal("-2 finished a wish a minute ago, so it is NOT past the grace; -1 has been "
                                    + "idle since its boot and is the extra to stop — got " + stopped));
                    roster.forget(stopped);
                    helper.assertTrue(AgentSession.shrinkStep(roster, queue, marshal,
                                    now + 10 * 60_000L, 120_000L) == null,
                            Component.literal("-2 is now the only listener: the spare, kept however long it idles"));
                })
                .thenSucceed();
    }
}
