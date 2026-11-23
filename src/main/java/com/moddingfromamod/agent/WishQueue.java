package com.moddingfromamod.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.moddingfromamod.marshal.ArtifactAffinity;
import com.moddingfromamod.marshal.Clock;
import com.moddingfromamod.marshal.WishAffinity;
import com.moddingfromamod.wish.Wish;

/**
 * The wake channel between "a child made a wish" and "the agent is working on it".
 *
 * <p>Replaces a file the outside world polled every 5 seconds behind a 45-second rate limit. An
 * agent parked on {@code await_wish} is handed the wish the instant it lands — measured at
 * <b>0.1 ms</b> in the probe that justified this design, against 20–90 s before.
 *
 * <p><b>This is a notification channel, not the source of truth.</b> {@link com.moddingfromamod.wish.WishBook}
 * holds every wish and its status; this only exists to say "something is waiting, now". That
 * distinction is what makes the bounded behaviour safe: if the queue overflows, or an entry is
 * dropped, or no agent is listening at all, nothing is lost — the wish is still PENDING in the book
 * and {@code list_wishes} will find it. So on overflow we discard the <b>oldest</b> notification,
 * because a stale nudge is worth less than a fresh one.
 *
 * <h2>It is also where affinity routing happens</h2>
 * {@link WishAffinity} needs a decision point <b>before</b> a worker starts a wish, and this is the
 * only one there is: {@code Marshal.claim} is the scripted pool's path, while a real parked agent
 * arrives here and nowhere else. Testing the control next to that seam rather than through it would
 * prove it about a fiction.
 *
 * <p>A worker that may not take a wish does not put it back on {@link #waiting} — it would poll it
 * straight off again, since it is the thread already blocked on the head. The wish is moved
 * <b>aside</b> instead ({@link #deferred}), where only a worker that may have it, or anyone at all
 * once {@link ArtifactAffinity#HOLD_MS} has passed, can pick it up. Aside rather than back also
 * removes head-of-line blocking: an unrelated wish behind it is not made to wait for a routing
 * preference that has nothing to do with it.
 *
 * <p>Pure — no Minecraft imports — so the blocking/timeout/overflow behaviour is unit-tested in
 * milliseconds without booting a game.
 */
public final class WishQueue {

    /**
     * Bounded so a child mashing the watch (or an agent that has died) can't grow this without
     * limit. Comfortably more than the handful of wishes a person can make in an agent turn.
     */
    public static final int MAX_WAITING = 64;

    /**
     * How long a parked worker sleeps before re-checking whether a held-aside wish has come free.
     *
     * <p>Only paid when something is actually deferred — with an empty {@link #deferred} the wait
     * blocks for its whole remaining timeout, exactly as it always did. So the normal path costs no
     * extra wakeups at all, and the forcing path costs two a second per parked worker.
     */
    static final long POLL_SLICE_MS = 500L;

    private static final WishQueue GLOBAL = new WishQueue();

    private final LinkedBlockingDeque<Wish> waiting = new LinkedBlockingDeque<>(MAX_WAITING);

    /** Held aside for a preferred worker, oldest first. Guarded by {@code this}. */
    private final Deque<Held> deferred = new ArrayDeque<>();

    private record Held(Wish wish, long sinceMs) {
    }

    /** How many notifications we've had to discard — surfaced so overflow is never silent. */
    private volatile int dropped;

    /**
     * <b>Which workers are parked in {@link #await} right now</b> — the pre-flight pool census
     *.
     *
     * <p>{@code ArtifactAffinity.workersSeen()} cannot answer this. It counts workers a wish has
     * been <em>offered to</em>, so it is 0 before the first rung and a harness cannot wait on it
     * without deadlocking: the pool only grows once wishes flow, and the wish is the thing you were
     * about to spend. This set is populated by the act of PARKING, which happens whether or not
     * there is any work, so it can be asked before anything is spent.
     *
     * <p>Why it matters: {@code WishAffinity.DIFFERENT} needs somebody other than the owner, so a
     * one-worker world makes arm B unsatisfiable by construction — every refinement holds for
     * {@code HOLD_MS} and falls back, and the cell still runs all five rungs and burns 30-50
     * minutes of a real client before anyone can tell.
     *
     * <p>Measured on a void cell against two good ones: the good cells had <b>3</b> distinct worker
     * ids and routed {@code stranger x4}; the void cell had <b>1</b>, and it is the cell that took
     * ~50 minutes to boot under machine load. The extra workers were not losing a race — they never
     * started. That makes this a starvation symptom, which is worth waiting a few seconds for
     * rather than discovering five rungs later.
     */
    private final java.util.Set<String> parked =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * <b>Workers the pool has stopped while they were parked</b> — MCP session ids whose
     * {@link #await} may still be blocked here although the process behind it is gone.
     *
     * <p>{@code claude stop} ends the CLI, not the server thread serving its {@code await_wish}: that
     * HTTP handler stays parked in {@link #await} until its ~110 s deadline. A wish arriving in that
     * window would be taken by it, returned to nobody and assigned to a dead worker — a wish the
     * child waits on forever. So a stopped worker is retired HERE first ({@link #retireIfParked}),
     * atomically with the check that it is still parked and has taken nothing, and a retired await
     * hands back anything it polls and returns empty. Its entry is cleared when that await exits.
     * Guarded by {@code this}.
     */
    private final java.util.Set<String> retired = new java.util.HashSet<>();

    private final ArtifactAffinity affinity;
    private final Clock clock;

    /**
     * What a wish is <b>about</b> — the rung-0 alias lookup, injected rather than called.
     *
     * <p>Injected because this class is deliberately Minecraft-free and the real resolver reads the
     * handover store, which is per-world state. It also means the routing tests declare their own
     * artifacts instead of standing up a world to own them.
     */
    private volatile Function<Wish, String> subjectOf = w -> "";

    public WishQueue() {
        this(ArtifactAffinity.global(), Clock.system());
    }

    /** Its own ownership map and its own clock — the seam every routing test uses. */
    public WishQueue(ArtifactAffinity affinity, Clock clock) {
        this.affinity = affinity == null ? ArtifactAffinity.global() : affinity;
        this.clock = clock == null ? Clock.system() : clock;
    }

    /** The queue for this process. */
    public static WishQueue global() {
        return GLOBAL;
    }

    /** Teach this queue how to name the thing a wish is about. */
    // ── D10: WHO IS NEXT WHEN TWO CHILDREN ARE WAITING ─────────────────────────
    //
    // Here, and not in Marshal.claim: a real parked agent is handed its wish by THIS class and
    // nowhere else (see the class note — claim() is the scripted pool's path). The decision is
    // FairShare's; this only supplies what it needs and executes the answer.

    /** Which of a child's wishes a worker is granting right now — the Marshal's WORKING runs. */
    private volatile Function<UUID, Set<UUID>> workingFor = p -> Set.of();

    /** One wish handed to a worker: counted against the child's cap until the Marshal sees it. */
    private record Pickup(UUID wishId, UUID playerId, long atMs) {
    }

    /** Every pickup inside the fairness window, oldest first. */
    private final Deque<Pickup> pickups = new ArrayDeque<>();

    /**
     * How long a handed-out wish counts as in flight on the queue's word alone. Between the queue
     * handing a wish over and {@code Marshal.assign} marking it WORKING, {@code AwaitWishTool}
     * grades it for safety; without this, two workers waking in that window would both see a child
     * one below his cap and take him over it.
     */
    static final long HANDOFF_MS = 30_000L;

    /** Wire the cap to the Marshal: which of this child's wishes are WORKING right now. */
    public WishQueue countingWorkWith(Function<UUID, Set<UUID>> working) {
        this.workingFor = working == null ? p -> Set.of() : working;
        return this;
    }

    public WishQueue resolvingSubjectsWith(Function<Wish, String> resolver) {
        this.subjectOf = resolver == null ? w -> "" : resolver;
        return this;
    }

    /** The ownership map this queue routes against. */
    public ArtifactAffinity affinity() {
        return affinity;
    }

    /**
     * Announce a wish that needs an agent.
     *
     * @return false if the queue was full and the oldest notification had to be discarded
     */
    public boolean offer(Wish wish) {
        if (wish == null) {
            return true;
        }
        // WAKE THE POOL for any wish handed to the workers (step 4). HERE, at the one
        // door every agent-bound wish comes through — a new wish, a refinement reopened by
        // `/moddy redirect`, a wish resumed on join — because the pool may have been stopped after
        // twenty quiet minutes, and a wish offered to a stopped pool is heard by nobody. Only the
        // global queue: a gate's private queue has no pool behind it. A no-op when the pool is up.
        if (this == GLOBAL) {
            AgentSession.wake("a wish");
        }
        while (!waiting.offerLast(wish)) {
            // Full: make room by dropping the oldest. Safe because the book still has it.
            if (waiting.pollFirst() != null) {
                dropped++;
            }
        }
        return dropped == 0;
    }

    /**
     * Block until a wish is waiting, or the timeout expires.
     *
     * <p><b>Never call this on the server thread.</b> It parks for as long as the caller asks, and
     * parking the server thread would freeze the game outright. It belongs on an HTTP handler
     * thread, which is exactly where an MCP tool body runs.
     *
     * @return the next wish, or null on timeout
     */
    public Wish await(long timeoutMs) throws InterruptedException {
        return await(timeoutMs, "").wish();
    }

    /** What one worker's wait produced, and how it was routed — see {@link Taken}. */
    public record Taken(Wish wish, ArtifactAffinity.Routing routing, String artifactId) {

        /** Nothing arrived in time. */
        static Taken nothing() {
            return new Taken(null, null, "");
        }

        public boolean isEmpty() {
            return wish == null;
        }
    }

    /**
     * Block until a wish <b>this worker may take</b> is waiting, or the timeout expires.
     *
     * <p>Under {@link WishAffinity#OFF} — the default, and all of real play today — this is exactly
     * the old behaviour: the first poll returns {@link ArtifactAffinity.Routing#ANYONE} and the wish
     * is handed straight over. The loop below only ever runs more than once when a mode is forcing.
     *
     * <p>The routing comes back with the wish rather than being looked up afterwards, because
     * "which arm granted this" has to be a fact recorded at the source. Inferring it later from
     * which file the results were saved into is how came to be misfiled.
     */
    /**
     * How many distinct workers are parked waiting for a wish right now.
     *
     * <p>Ask this BEFORE spending a rung — see {@link #parked}. A harness that needs a stranger
     * needs this to be at least 2.
     */
    public int waitingWorkers() {
        return parked.size();
    }

    /** WHICH workers are parked (their MCP session ids) — the pool's deaf check needs names. */
    public java.util.Set<String> parkedWorkers() {
        return java.util.Set.copyOf(parked);
    }

    /**
     * When a worker last parked here to listen — {@code health.json}'s {@code agent.lastAwaitAt}.
     *
     * <p>The one timestamp that distinguishes "the agent process is alive" from "the agent can hear
     * a child", which is an earlier issue's whole failure mode and plan §2.7's first bullet. Wall clock rather
     * than the injected one, deliberately: this is a fact about a real operator's real minutes, and
     * a {@code FakeClock} in a test must not make a health file claim the agent last listened in
     * 1970.
     */
    public long lastAwaitAtMs() {
        return lastAwaitAtMs;
    }

    private volatile long lastAwaitAtMs;

    /**
     * <b>Take this parked worker out of the queue's hands, if it is parked and has taken nothing</b>
     * — the pool calls this BEFORE stopping an idle extra worker, and stops it only on
     * {@code true}.
     *
     * <p>Atomic with the commit in {@link #awaitParked}: a parked await that has already committed to
     * a wish has left {@link #parked} under the same monitor, so this answers {@code false} and the
     * worker — now holding a wish — is not stopped. Once this answers {@code true}, the worker's
     * blocked await can never hand it a wish: whatever it polls goes back to the front for a live
     * worker, and it returns empty.
     *
     * @return whether the worker was parked and is now retired; {@code false} means do not stop it
     */
    public synchronized boolean retireIfParked(String workerId) {
        if (workerId == null || workerId.isBlank() || !parked.contains(workerId)) {
            return false;
        }
        parked.remove(workerId);
        retired.add(workerId);
        return true;
    }

    /** Whether {@code workerId} has been retired and its await not yet returned. */
    public synchronized boolean isRetired(String workerId) {
        return workerId != null && retired.contains(workerId);
    }

    public Taken await(long timeoutMs, String workerId) throws InterruptedException {
        return await(timeoutMs, workerId, () -> true);
    }

    /**
     * {@link #await(long, String)}, for a caller that can say whether it is STILL the worker it
     * claims to be — asked at the commit, under the same monitor as the retired check, so a
     * stale incarnation of a stopped worker can never take a wish even if it parked before anybody
     * knew it was stale. A {@code false} answer is handled exactly like a retired await: whatever it
     * polled goes back to the front for a live worker, and it returns empty.
     *
     * @param stillLive asked at every point this await could take a wish; {@code null} means always
     */
    public Taken await(long timeoutMs, String workerId, java.util.function.BooleanSupplier stillLive)
            throws InterruptedException {
        java.util.function.BooleanSupplier live = stillLive == null ? () -> true : stillLive;
        lastAwaitAtMs = System.currentTimeMillis();
        // A worker parking here has just taken a model turn, so the agent is answering: the
        // evidence that clears the "agent unresponsive".
        com.moddingfromamod.health.ModdyTired.global().workerParked(lastAwaitAtMs);
        // WALL CLOCK for the timeout, the injected clock ONLY for how long a wish has been held.
        // They are not the same measurement: the timeout is a real thread parking for real seconds,
        // while the hold is a policy deadline a test has to be able to jump three minutes of. Run
        // both off the injected clock and a FakeClock test spins forever, because the deadline it is
        // waiting on never moves.
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        WishAffinity mode = WishAffinity.current();
        // PARKED FROM HERE UNTIL THIS CALL RETURNS, however it returns — see `parked`. Registered
        // before the first poll so a harness asking "is there a stranger available" gets a true
        // answer as soon as the worker is genuinely available, not one poll-slice later.
        boolean census = workerId != null && !workerId.isBlank();
        if (census) {
            parked.add(workerId);
        }
        try {
            return awaitParked(deadline, mode, workerId, live);
        } finally {
            if (census) {
                parked.remove(workerId);
                synchronized (this) {
                    retired.remove(workerId);
                }
            }
        }
    }

    private Taken awaitParked(long deadline, WishAffinity mode, String workerId,
                              java.util.function.BooleanSupplier live) throws InterruptedException {
        while (true) {
            // Anything already held aside that this worker may now have — checked FIRST, so the
            // owner arriving back is served before a newer wish overtakes the one waiting for it.
            Taken promoted = takeDeferred(mode, workerId, live);
            if (promoted != null) {
                return promoted;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return Taken.nothing();
            }
            long slice = hasDeferred() ? Math.min(remaining, POLL_SLICE_MS) : remaining;
            Wish polled = waiting.pollFirst(slice, TimeUnit.MILLISECONDS);
            if (polled == null) {
                continue;   // slice expired: re-check the held-aside pile, then the deadline
            }
            if (isRetired(workerId) || !stillLive(live)) {
                // This await outlived its worker. The wish goes back to the FRONT, which wakes
                // a live parked worker, and this one returns to nobody.
                giveBack(polled);
                return Taken.nothing();
            }
            // THE HEAD IS A WAKE-UP, NOT A DECISION (D10). The fair wish may be further back; if
            // so the head goes back to the FRONT, keeping its place for the next worker.
            Wish next = fairPick(polled);
            if (next == null) {
                // Every waiting wish belongs to a child already at his cap. Wait for one of his to
                // finish rather than spinning on a queue that is not empty.
                Thread.sleep(Math.max(1, Math.min(deadline - System.currentTimeMillis(),
                        POLL_SLICE_MS)));
                continue;
            }
            String artifact = subject(next);
            ArtifactAffinity.Routing routing = affinity.decide(mode, workerId, artifact, 0L);
            if (routing.take()) {
                // COMMIT under the monitor retireIfParked takes, so the pool either retires
                // this worker before it takes the wish (and the wish goes back), or sees it has
                // left `parked` holding one (and does not stop it). Never both.
                synchronized (this) {
                    if ((workerId != null && retired.contains(workerId)) || !stillLive(live)) {
                        giveBack(next);
                        return Taken.nothing();
                    }
                    if (workerId != null && !workerId.isBlank()) {
                        parked.remove(workerId);
                    }
                    pickedUp(next);
                }
                // ⚠ HO-14: THE WISH'S WAIT ENDS HERE, so this is where it is measured. Everything
                // before this moment is the child watching a watch with nothing happening — a
                // worker was busy, or there was no worker yet — and `oldestPendingS` could only
                // ever say how bad it is RIGHT NOW. This says what it cost, per wish, for good.
                long waitedMs = System.currentTimeMillis() - next.createdMs();
                if (waitedMs > 0) {
                    // ⚠ COLD MEANS "the pool had to boot something for this wish", and the test is
                    // the crisp one rather than a window: a worker spawned AFTER this wish was
                    // submitted. Anything softer needs a tuned grace, and a tuned number picked
                    // before the data exists is the thing this repo keeps retracting.
                    //
                    // It is a LABEL and not a second reason because the boot is already inside
                    // this interval — a sibling counter would have counted it twice.
                    boolean cold = AgentSession.lastSpawnMs() > next.createdMs();
                    com.moddingfromamod.health.Counters.waited("worker_slot", waitedMs, cold);
                }
                return new Taken(next, routing, artifact);
            }
            hold(next);
        }
    }

    /**
     * The fair choice among the polled head and everything still waiting, or null when every one
     * of them belongs to a child at his cap — in which case the head is back at the front.
     */
    private synchronized Wish fairPick(Wish head) {
        List<Wish> candidates = new ArrayList<>();
        candidates.add(head);
        candidates.addAll(waiting);
        long now = clock.nowMs();
        forgetOldPickups(now);
        List<UUID> owners = new ArrayList<>(candidates.size());
        Map<UUID, Integer> inFlight = new HashMap<>();
        Map<UUID, Integer> recent = new HashMap<>();
        for (Wish w : candidates) {
            owners.add(w.playerId());
            inFlight.computeIfAbsent(w.playerId(), p -> inFlightFor(p, now));
        }
        for (Pickup p : pickups) {
            recent.merge(p.playerId(), 1, Integer::sum);
        }
        int i = com.moddingfromamod.marshal.FairShare.pick(owners, inFlight, recent,
                com.moddingfromamod.marshal.FairShare.perChild());
        if (i == 0) {
            return head;
        }
        waiting.offerFirst(head);           // room is guaranteed: it was just polled off
        if (i < 0) {
            return null;
        }
        Wish chosen = candidates.get(i);
        waiting.remove(chosen);
        return chosen;
    }

    /**
     * How many of this child's wishes count against his cap right now — the number
     * {@link com.moddingfromamod.marshal.FairShare} is handed. Public so a gate can ask whether the
     * count is wired without waiting on a real clock.
     */
    public synchronized int inFlight(UUID player) {
        long now = clock.nowMs();
        forgetOldPickups(now);
        return inFlightFor(player, now);
    }

    /** WORKING in the Marshal, plus handed out here and not yet seen there. */
    private int inFlightFor(UUID player, long now) {
        Set<UUID> working = new HashSet<>();
        try {
            working.addAll(workingFor.apply(player));
        } catch (RuntimeException e) {
            // Counting must never break the wake; an unreadable count is "none", which is the
            // FIFO this replaced.
        }
        for (Pickup p : pickups) {
            if (java.util.Objects.equals(p.playerId(), player) && now - p.atMs() < HANDOFF_MS) {
                working.add(p.wishId());
            }
        }
        return working.size();
    }

    private synchronized void pickedUp(Wish wish) {
        pickups.addLast(new Pickup(wish.id(), wish.playerId(), clock.nowMs()));
    }

    private void forgetOldPickups(long now) {
        long window = com.moddingfromamod.marshal.FairShare.windowMs();
        while (!pickups.isEmpty() && now - pickups.peekFirst().atMs() >= window) {
            pickups.pollFirst();
        }
    }

    private synchronized boolean hasDeferred() {
        return !deferred.isEmpty();
    }

    private synchronized void hold(Wish wish) {
        deferred.addLast(new Held(wish, clock.nowMs()));
    }

    /**
     * A wish a retired await polled goes back to the front for a live worker. Room is normally
     * guaranteed — it was just taken off — but an {@link #offer} may have filled the gap, and
     * then it is held aside, where every parked worker checks first, rather than dropped.
     */
    private synchronized void giveBack(Wish wish) {
        if (!waiting.offerFirst(wish)) {
            hold(wish);
        }
    }

    /** The oldest held-aside wish this worker may take, or null. */
    private synchronized Taken takeDeferred(WishAffinity mode, String workerId,
                                            java.util.function.BooleanSupplier live) {
        if ((workerId != null && retired.contains(workerId)) || !stillLive(live)) {
            return null;   //: a retired or stale await takes nothing
        }
        long now = clock.nowMs();
        for (var it = deferred.iterator(); it.hasNext(); ) {
            Held held = it.next();
            String artifact = subject(held.wish());
            ArtifactAffinity.Routing routing =
                    affinity.decide(mode, workerId, artifact, now - held.sinceMs());
            if (routing.take()) {
                it.remove();
                if (workerId != null && !workerId.isBlank()) {
                    parked.remove(workerId);   // committed: see retireIfParked
                }
                pickedUp(held.wish());
                return new Taken(held.wish(), routing, artifact);
            }
        }
        return null;
    }

    /** A liveness answer that throws is "not live": a wish is never handed to a doubt. */
    private static boolean stillLive(java.util.function.BooleanSupplier live) {
        try {
            return live.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String subject(Wish wish) {
        try {
            String id = subjectOf.apply(wish);
            return id == null ? "" : id.trim();
        } catch (RuntimeException e) {
            // Routing must never be able to break the wake. An unresolvable subject simply means
            // "nothing owns this", which is the same answer a creation gives.
            return "";
        }
    }

    /**
     * Everything else waiting right now — <b>looked at, not taken</b>.
     *
     * <p>This used to POLL them off the queue, which was exactly right with one agent: it woke,
     * saw its whole backlog in one result, and handled it. With a pool it is a starvation bug, and
     * a total one — <b>the first worker to wake empties the queue</b>, so every other worker
     * long-polls an empty channel forever and the pool grants wishes strictly one at a time while
     * looking perfectly healthy.
     *
     * <p>Found by the first run with two real agents: two sessions started, two wishes were made
     * seconds apart, and the maximum ever in flight at once was <b>one</b>. Nothing else could have
     * found it — every headless test drives {@code claim} directly and never goes through here.
     *
     * <p>Peeking is also simply more correct. This class is a notification channel and
     * {@code WishBook} is the source of truth, so a wish left in the queue is not lost, it is
     * available: whichever worker asks next takes it as a first-class wish rather than as a
     * footnote in somebody else's result.
     */
    public List<Wish> peekRest(int limit) {
        List<Wish> out = new ArrayList<>();
        for (Wish w : waiting) {
            if (out.size() >= Math.max(0, limit)) {
                break;
            }
            out.add(w);
        }
        // Held-aside wishes are still waiting — they are simply waiting for somebody in particular.
        // Leaving them out would make a forced arm look like a queue that had emptied itself.
        synchronized (this) {
            for (Held h : deferred) {
                if (out.size() >= Math.max(0, limit)) {
                    break;
                }
                out.add(h.wish());
            }
        }
        return out;
    }

    public synchronized int size() {
        return waiting.size() + deferred.size();
    }

    /** Waiting for somebody in particular. Zero unless a {@link WishAffinity} mode is forcing. */
    public synchronized int deferredCount() {
        return deferred.size();
    }

    public int droppedCount() {
        return dropped;
    }

    /** Server stop: a new world must not inherit the last one's pending nudges. */
    public synchronized void clear() {
        pickups.clear();
        waiting.clear();
        deferred.clear();
        retired.clear();
        dropped = 0;
    }
}
