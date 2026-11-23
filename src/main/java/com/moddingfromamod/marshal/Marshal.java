package com.moddingfromamod.marshal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * Who is granting what, right now. <b>Plain Java, deliberately not an agent.</b>
 *
 * <p>Everything it decides is a lookup: is a slot free (a counter), who holds that artifact (a map),
 * has this been stopped (a flag), which wish is this worker on (a map). A model round trip costs
 * about three seconds at the measured turn floor and the whole point of the design is that a child
 * is acknowledged within a tick, so none of those may cost an inference.
 *
 * <h2>Pull, not push</h2>
 * A worker asks for work when it is ready ({@code await_wish} → {@link #claim}), exactly as the
 * agent already behaves. That matters for testing: the fake pool models the same mechanism the real
 * one uses, so a scheduling test is not proving something about a fiction. The marshal's only job on
 * that path is to say <b>no</b> when every slot is busy.
 *
 * <h2>No queue, anywhere</h2>
 * A wish with no free slot is {@link RunState#GETTING_READY} — its beam and bar are already up and
 * from the outside it is indistinguishable from thinking. There is no position, no depth, no "next",
 * and nothing here is ever rendered to a child as a queue.
 *
 * <h2>Starvation</h2>
 * Waiting runs are a strict FIFO and {@link #claim} always takes the head, so the oldest waiting
 * wish can never be overtaken indefinitely. A worker that dies hands its wish back to the
 * <b>front</b>, because that wish is by definition older than everything behind it.
 *
 * <p>Minecraft-free on purpose, so all of the above is unit-tested in milliseconds rather than by
 * booting a game and hoping a race shows up.
 */
public final class Marshal {

    private final Clock clock;
    private final IntSupplier slots;

    /** Every live run, in arrival order. */
    private final Map<UUID, WishRun> runs = new LinkedHashMap<>();

    /** Admitted but not yet taken by a worker — strict FIFO, which is the anti-starvation rule. */
    private final Deque<UUID> waiting = new ArrayDeque<>();

    /** worker id → the wish it is granting. */
    private final Map<String, UUID> byWorker = new HashMap<>();

    /** artifact id → the wish holding its lease. */
    private final Map<String, UUID> leases = new HashMap<>();

    /**
     * worker id → the wish it was granting when the child stopped it.
     *
     * <p>The visible stop and the actual stop are decoupled on purpose: the bar and beam go within a
     * tick, server-side, with no agent involved, while the worker is mid-inference and cannot be
     * interrupted. It finds out on its <b>next tool call</b>, which is what this map is for — the
     * run itself has already released its slot and is no longer traceable from the worker.
     *
     * <p>Cleared when the worker obeys (asks for new work), so a wound-down worker is not refused
     * forever.
     *
     * <p>⚠ <b>The RUN, not its id.</b> A stopped wish id can be re-opened — {@code /moddy redirect}
     * does exactly that — and {@link #submit} then replaces that id's entry in {@code runs} with a
     * fresh run. A lookup through {@code runs} would hand this worker the <i>fresh</i> run to wind
     * down from, so its refusal would cite a run nobody cancelled and would drop the one sentence
     * that says a redirect is not an abandonment. Holding the run makes this map say what it means
     * on its own (CS-D7).
     */
    private final Map<String, WishRun> windingDown = new HashMap<>();

    /**
     * worker id → the wish it has just FINISHED and not yet moved on from.
     *
     * <p>{@link #windingDown}'s sibling for the ordinary ending. Before this, a worker that called
     * {@code finish_wish} was indistinguishable from one that had never been given a wish, so every
     * tool it called afterwards ran — and a live wish ("make it daytime forever") told its child it
     * was granted TWICE, with a second plan, a second bold "built" and a second bold "granted",
     * because nothing anywhere refused a second finish cycle. Holding the run lets
     * {@link CancelGate} refuse the child-visible tools by name and say which wish is over.
     *
     * <p>Cleared the moment the worker asks for new work ({@link #assign}/{@link #claim}), exactly
     * as {@link #windingDown} is, so a worker that did as it was told is never refused.
     */
    private final Map<String, WishRun> finishedBy = new HashMap<>();

    /**
     * Every worker this marshal has ever given a wish. {@code AgentContext.currentRun}
     * falls back to "the single live run" for a caller it cannot place; a worker in THIS set can
     * always be placed — it holds a run, or it holds none — so it never borrows another child's.
     * Only a session the marshal has never seen (a gate, a direct call, a CLI that re-initialised
     * its MCP session mid-wish) gets the fallback. Bounded by the pool's slot names in practice;
     * cleared with everything else.
     */
    private final java.util.Set<String> everAssigned = new java.util.HashSet<>();

    private static final Marshal GLOBAL = new Marshal(Clock.system(), WishSlots::slots);

    public Marshal(Clock clock, IntSupplier slots) {
        this.clock = clock == null ? Clock.system() : clock;
        this.slots = slots == null ? WishSlots::slots : slots;
    }

    /** The one marshal a running game uses. */
    public static Marshal global() {
        return GLOBAL;
    }

    /** A marshal with its own clock and slot count — the seam every test uses instead of the global. */
    public static Marshal forTest(Clock clock, int slots) {
        return new Marshal(clock, () -> Math.max(1, slots));
    }

    public long nowMs() {
        return clock.nowMs();
    }

    public int slots() {
        return Math.max(1, slots.getAsInt());
    }

    // ================= submission =================

    /**
     * A child wished. Acknowledged immediately, whether or not a worker is free — that is the
     * responsiveness contract, and it is why there is no admission failure to report.
     *
     * <p><b>Idempotent while that wish's run is LIVE; a RE-OPEN once it has finished.</b> Those are
     * two different rules and both are load-bearing:
     *
     * <ul>
     *   <li><b>Live → the same run.</b> {@code AwaitWishTool} calls this immediately before every
     *       {@link #assign}, deliberately, because a wish can reach an agent by paths that never
     *       went through {@code WishService}. Minting a second run for a wish already in flight
     *       would detach the in-flight one from its worker, its anchor and its leases.</li>
     *   <li><b>Finished → a fresh run.</b> Two of the three re-wish paths keep the wish id and
     *       throw away only the attempt: {@code /moddy redirect} stops the run and re-opens the same
     *       wish carrying what the child actually meant, and {@code /moddy no} sends a wish the
     *       agent already finished back to its list. Neither mints a new wish, on purpose — the id
     *       is what the bar, the journal and {@code /moddy stop} all name it by.</li>
     * </ul>
     *
     * <p><b>Why that second half is a bug fix and not a nicety (CS-D7).</b> Handing the finished run
     * back made every consequence quiet: {@link #assign} refuses a finished run and returns null,
     * {@link #liveFor} excludes it, and nothing downstream reads either. So a re-aimed wish was
     * narrated, built and finished by a worker this marshal had no record of, and
     * <b>{@code /moddy stop} on it afterwards found nothing at all</b> — a child who re-aims a wish
     * was told "nothing to stop" about the wish whose bar he was looking at.
     *
     * <p><b>Why here and not in {@link #cancel}.</b> A stopped run has to go on existing: it is
     * what the wind-down refusal is built from (including the line that says he wanted it aimed
     * somewhere else), {@code WishGo} looks finished runs up by short id, and a second
     * {@code cancel} reports {@code alreadyStopped} from it. So the finished run keeps every reader
     * it had except this map, whose entry means <i>the current run for this id</i>.
     *
     * <p>The one thing a superseded run does lose is its place in {@link #all} and therefore in
     * {@link #lastCompleted}/{@link #lastArtifactFor}. That is accepted rather than overlooked: it
     * only bites a {@code /moddy no}, where the run was DONE, and only until the retry finishes —
     * and the child has just said that attempt was the wrong thing. Keeping it would mean a second
     * collection touched by five queries and {@link #clear}, for one narrow window.
     */
    public synchronized WishRun submit(UUID wishId, UUID playerId, String text) {
        if (wishId == null) {
            return null;
        }
        WishRun existing = runs.get(wishId);
        if (existing != null && existing.state().isLive()) {
            return existing;
        }
        WishRun run = new WishRun(wishId, playerId, text, clock.nowMs());
        runs.put(wishId, run);
        if (!waiting.contains(wishId)) {
            // Both `cancel` and `finish` take the id out of the queue, so a re-opened wish is never
            // still in it — but queueing one twice would let a single wish be handed to two
            // workers, and the guard is cheaper than the reasoning.
            waiting.addLast(wishId);
        }
        return run;
    }

    // ================= assignment =================

    /**
     * A worker is free and asking for work.
     *
     * @return the oldest waiting wish, now assigned to this worker — or null if every slot is busy
     *         or nothing is waiting
     */
    public synchronized WishRun claim(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            return null;
        }
        // A worker that already holds a wish is not free; handing it a second is how one agent ends
        // up interleaving two wishes with no yield point, which is the design this rejects.
        UUID held = byWorker.get(workerId);
        if (held != null) {
            WishRun heldRun = runs.get(held);
            if (heldRun != null && heldRun.state() == RunState.WORKING) {
                return null;
            }
        }
        // Asking for work IS the wind-down: the worker has stopped and come back for more, so it
        // must not be refused on the next tool call for a wish that no longer exists.
        windingDown.remove(workerId);
        finishedBy.remove(workerId);
        if (activeCount() >= slots()) {
            return null;
        }
        // Stopped while it waited: drop it, don't burn the slot.
        waiting.removeIf(id -> runs.get(id) == null || runs.get(id).state().isFinished());
        // D10, the same rule the real dequeue uses (WishQueue) — so the scripted pool, and every
        // test that measures fairness through it, is measuring the scheduler a child actually meets.
        long now = clock.nowMs();
        long window = FairShare.windowMs();
        while (!pickups.isEmpty() && now - pickups.peekFirst().atMs() >= window) {
            pickups.pollFirst();
        }
        List<UUID> queue = new ArrayList<>(waiting);
        List<UUID> owners = new ArrayList<>(queue.size());
        Map<UUID, Integer> inFlight = new HashMap<>();
        Map<UUID, Integer> recent = new HashMap<>();
        for (UUID id : queue) {
            owners.add(runs.get(id).playerId());
        }
        for (WishRun r : runs.values()) {
            if (r.state() == RunState.WORKING) {
                inFlight.merge(r.playerId(), 1, Integer::sum);
            }
        }
        for (Claimed c : pickups) {
            recent.merge(c.playerId(), 1, Integer::sum);
        }
        int i = FairShare.pick(owners, inFlight, recent, FairShare.perChild());
        if (i < 0) {
            return null;
        }
        UUID next = queue.get(i);
        waiting.remove(next);
        WishRun run = runs.get(next);
        run.assignTo(workerId, now);
        byWorker.put(workerId, next);
        everAssigned.add(workerId);
        pickups.addLast(new Claimed(run.playerId(), now));
        return run;
    }

    /** D10: one claim inside the fairness window. */
    private record Claimed(UUID playerId, long atMs) {
    }

    private final Deque<Claimed> pickups = new ArrayDeque<>();

    /** How many wishes a worker is actually granting right now. */
    public synchronized int activeCount() {
        int n = 0;
        for (WishRun r : runs.values()) {
            if (r.state() == RunState.WORKING) {
                n++;
            }
        }
        return n;
    }

    public synchronized boolean hasFreeSlot() {
        return activeCount() < slots();
    }

    // ================= completion =================

    /** The worker finished this wish. Releases its slot and its leases. */
    public synchronized boolean finish(UUID wishId) {
        WishRun run = runs.get(wishId);
        if (run == null || run.state().isFinished()) {
            return false;
        }
        String worker = run.workerId();
        run.finish(clock.nowMs());
        releaseLeasesOf(run);
        if (worker != null) {
            byWorker.remove(worker);
            finishedBy.put(worker, run);
        }
        waiting.remove(wishId);
        PickupHold.global().forget(wishId);     //: a settled wish's holds are nobody's business
        PickupHold.plans().forget(wishId);
        PlanDeferral.global().forget(wishId);
        com.moddingfromamod.readymade.WishNotes.global().forget(wishId.toString());   //
        com.moddingfromamod.readymade.NudgeRace.global().forget(wishId.toString());
        return true;
    }

    /**
     * The wish this worker has finished and not yet moved on from, or null. See
     * {@link #finishedBy}.
     */
    /** True once this worker has been given any wish — see {@link #everAssigned}. */
    public synchronized boolean hasAssigned(String workerId) {
        return workerId != null && everAssigned.contains(workerId);
    }

    public synchronized WishRun finishedFor(String workerId) {
        return workerId == null ? null : finishedBy.get(workerId);
    }

    /**
     * The child stopped it.
     *
     * <p>Idempotent: a double-click on {@code [stop]} is a no-op, not an error, and stopping a wish
     * that already finished is also a no-op rather than something that looks broken. Releasing the
     * slot happens here and not on the worker's wind-down, because the visible stop and the actual
     * stop are deliberately decoupled — the child sees the bar go within a tick, the worker notices
     * on its next tool call.
     */
    public synchronized CancelResult cancel(UUID wishId, CancelMode mode, String note,
                                            String redirectTo) {
        WishRun run = runs.get(wishId);
        if (run == null) {
            return new CancelResult(false, null, false, false);
        }
        if (run.state().isFinished()) {
            return new CancelResult(false, run.cancelMode(), false, run.cancelled());
        }
        boolean wasInFlight = run.state() == RunState.WORKING;
        String worker = run.workerId();
        run.stop(mode == null ? CancelMode.STOP : mode, note, redirectTo, clock.nowMs());
        releaseLeasesOf(run);
        if (worker != null) {
            byWorker.remove(worker);
            windingDown.put(worker, run);
        }
        waiting.remove(wishId);
        // path B: a call parked on a grade hold passed CancelGate before this stop. Wake it, so
        // it re-checks the gate and is refused NOW, rather than running the moment the grade clears.
        PickupHold.global().stop(wishId);
        PickupHold.plans().stop(wishId);
        return new CancelResult(true, run.cancelMode(), wasInFlight, false);
    }

    /** The stopped wish this worker has not yet noticed, or null. */
    public synchronized WishRun windingDown(String workerId) {
        return windingDown.get(workerId);
    }

    /** The worker has noticed and stopped. */
    public synchronized void woundDown(String workerId) {
        windingDown.remove(workerId);
    }

    /** What a stop actually did — so the child can always be told which of the three he got. */
    public record CancelResult(boolean stopped, CancelMode mode, boolean wasInFlight,
                               boolean alreadyStopped) {
    }

    /**
     * A worker died mid-wish. Its wish is <b>re-dispatched, not lost</b> — the child made it and
     * nothing about an agent falling over is his problem.
     *
     * <p>Back to the front of the queue, because it is older than everything behind it.
     */
    public synchronized WishRun workerDied(String workerId) {
        windingDown.remove(workerId);
        finishedBy.remove(workerId);
        // a wish it had picked up and not bound goes back to being nobody's: its claim clock runs again.
        if (workerId != null) {
            for (WishRun r : runs.values()) {
                if (workerId.equals(r.pickedUpBy())) {
                    r.dropPickup();
                }
            }
        }
        UUID wishId = byWorker.remove(workerId);
        if (wishId == null) {
            return null;
        }
        WishRun run = runs.get(wishId);
        if (run == null || run.state().isFinished()) {
            return null;
        }
        run.releaseAllLeases().forEach(leases::remove);
        run.unassign();
        if (!waiting.contains(wishId)) {
            waiting.addFirst(wishId);
        }
        return run;
    }

    /**
     * <b>A worker died of something that was not the wish's fault</b> — its subscription quota ran
     * out, or its login failed. Exactly {@link #workerDied}, plus the wish's claim clock
     * restarts NOW: it goes back to the real workers' {@code WishQueue} (the caller offers it), and
     * a replacement is still booting, so measuring "never claimed" from the wish's creation would
     * time it out before anyone could take it.
     *
     * @return the run put back, or null when that worker held nothing live
     */
    public synchronized WishRun requeueAfterDeath(String workerId) {
        WishRun run = workerDied(workerId);
        if (run != null) {
            run.requeued(clock.nowMs());
        }
        return run;
    }

    // ================= leases =================

    /**
     * Take the leases this wish needs, <b>all or nothing, in artifact-id order</b>.
     *
     * <p>The id order is the whole trick: two wishes wanting {A,B} and {B,A} at the same moment
     * acquire in the same sequence, so neither can hold half of what the other needs. Without a
     * total order that pair wedges, and it wedges rarely — which is the worst kind.
     *
     * <p>Most interaction wishes need none of this: "make Spikor fight Dinko" creates a new thing
     * that points at two existing ones and mutates neither. Classifying the verb correctly is worth
     * more than any locking cleverness.
     */
    public synchronized boolean acquireLeases(UUID wishId, List<String> artifactIds) {
        WishRun run = runs.get(wishId);
        if (run == null || artifactIds == null) {
            return false;
        }
        List<String> want = new ArrayList<>();
        for (String id : artifactIds) {
            if (id != null && !id.isBlank() && !want.contains(id)) {
                want.add(id);
            }
        }
        want.sort(String::compareTo);
        for (String id : want) {
            UUID holder = leases.get(id);
            if (holder != null && !holder.equals(wishId)) {
                return false;   // all or nothing: take none of them
            }
        }
        for (String id : want) {
            leases.put(id, wishId);
            run.holdLease(id);
        }
        return true;
    }

    public synchronized UUID leaseHolder(String artifactId) {
        return leases.get(artifactId);
    }

    private void releaseLeasesOf(WishRun run) {
        for (String id : run.releaseAllLeases()) {
            UUID holder = leases.get(id);
            if (run.wishId().equals(holder)) {
                leases.remove(id);
            }
        }
    }

    // ================= queries =================

    public synchronized WishRun run(UUID wishId) {
        return runs.get(wishId);
    }

    public synchronized WishRun runForWorker(String workerId) {
        UUID id = byWorker.get(workerId);
        return id == null ? null : runs.get(id);
    }

    /** Is anything not finished? {@link #live()} without the list — asked every server tick (KeepTicking). */
    public synchronized boolean anyLive() {
        for (WishRun r : runs.values()) {
            if (r.state().isLive()) {
                return true;
            }
        }
        return false;
    }

    /** Everything not finished, oldest first. */
    public synchronized List<WishRun> live() {
        List<WishRun> out = new ArrayList<>();
        for (WishRun r : runs.values()) {
            if (r.state().isLive()) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * <b>A worker has taken this wish off the queue</b> and is deciding its pickup gates, or waiting for its
     * brief, before it binds it with {@link #take}. Recorded so the claim ceiling counts the wish as
     * claimed from NOW ({@code WishTimeoutPolicy.Candidate.of}) and the supervisor counts the worker as busy
     * ({@link #pickingUp}). A brief that held a worker past the claim ceiling used to cancel, as "never
     * claimed", a wish that worker held, and then the worker was stopped as deaf.
     *
     * @return the run, or null when the marshal has none live and unbound for that wish
     */
    public synchronized WishRun pickedUp(String workerId, UUID wishId) {
        WishRun run = wishId == null ? null : runs.get(wishId);
        if (run == null || !run.state().isLive() || workerId == null) {
            return null;
        }
        // A worker picks up one wish at a time: one it picked up earlier and never bound (await_wish threw
        // between the two) is nobody's again.
        for (WishRun other : runs.values()) {
            if (other != run && workerId.equals(other.pickedUpBy())) {
                other.dropPickup();
            }
        }
        return run.pickedUp(workerId, clock.nowMs()) ? run : null;
    }

    /** Live wishes a worker has picked up and not yet bound, oldest first. */
    public synchronized List<WishRun> pickingUp() {
        List<WishRun> out = new ArrayList<>();
        for (WishRun r : runs.values()) {
            if (r.state().isLive() && r.pickedUpBy() != null) {
                out.add(r);
            }
        }
        return out;
    }

    /** Everything a worker is granting right now, oldest first. */
    public synchronized List<WishRun> working() {
        List<WishRun> out = new ArrayList<>();
        for (WishRun r : runs.values()) {
            if (r.state() == RunState.WORKING) {
                out.add(r);
            }
        }
        return out;
    }

    /** Admitted, sparkles up, no worker yet — oldest first. Never shown to a child as a queue. */
    public synchronized List<WishRun> gettingReady() {
        List<WishRun> out = new ArrayList<>();
        for (UUID id : waiting) {
            WishRun r = runs.get(id);
            if (r != null && r.state().isLive()) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * <b>How many wishes are in front of this one</b> (plan §9 item 6).
     *
     * <p>The queue is a strict FIFO and {@link #claim} always takes the head (D10 — fairness stays
     * oldest-first, unchanged); this only reads the position out so the wait can be made visible.
     * <b>Every</b> waiting wish counts, not only this child's: the honest answer to "why is mine
     * taking so long" on a shared server is that his brother asked first, and a count that hid
     * other children's wishes would be a number that goes down while nothing happens.
     *
     * @return the number of live waiting wishes ahead of this one, {@code 0} when it is next, and
     *         {@code -1} when it is not waiting at all — which is the common case (it is being
     *         granted) and must not read as "0 ahead"
     */
    public synchronized int aheadOf(UUID wishId) {
        if (wishId == null || !waiting.contains(wishId)) {
            return -1;
        }
        int ahead = 0;
        for (UUID id : waiting) {
            if (wishId.equals(id)) {
                return ahead;
            }
            WishRun r = runs.get(id);
            if (r != null && r.state().isLive()) {
                ahead++;
            }
        }
        return ahead;
    }

    /** Live runs belonging to one child, oldest first. */
    public synchronized List<WishRun> liveFor(UUID playerId) {
        List<WishRun> out = new ArrayList<>();
        for (WishRun r : runs.values()) {
            if (r.state().isLive() && playerId != null && playerId.equals(r.playerId())) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * The most recently <b>completed</b> run for this child — the definition of {@code @last} under
     * out-of-order completion.
     *
     * <p>Newest completed rather than newest started, deliberately: "make it bigger" after two
     * wishes means the thing he just watched appear, not the one still being made. Whichever we
     * chose had to be pinned by a test, because under concurrency the two orders genuinely differ.
     */
    public synchronized WishRun lastCompleted(UUID playerId) {
        WishRun best = null;
        for (WishRun r : runs.values()) {
            if (r.state() != RunState.DONE) {
                continue;
            }
            if (playerId != null && !playerId.equals(r.playerId())) {
                continue;
            }
            if (best == null || r.finishedMs() > best.finishedMs()
                    || (r.finishedMs() == best.finishedMs() && r.createdMs() > best.createdMs())) {
                best = r;
            }
        }
        return best;
    }

    /**
     * <b>The last thing this child actually worked on</b> — the fallback affinity routing needs.
     *
     * <p>Not {@code lastCompleted().made()}, which was the first attempt and breaks on the first
     * refinement. A rung that MODIFIES records nothing in {@code made()} — only a rung that creates
     * does — so after one "make him tougher" the chain went empty and every later rung routed
     * {@code anyone}. Measured on the first real arm-A pair: 4 of 8 refinements failed to bind, and
     * all four were the rung after a modify-only one.
     *
     * <p>So it walks BACK through this child's finished runs, newest first, until one of them made
     * something. That is "the thing he is talking about" in the only sense available here.
     */
    public synchronized String lastArtifactFor(UUID playerId) {
        List<WishRun> mine = new ArrayList<>();
        for (WishRun r : runs.values()) {
            if (r.state() == RunState.DONE && (playerId == null || playerId.equals(r.playerId()))) {
                mine.add(r);
            }
        }
        mine.sort((a, b) -> Long.compare(b.finishedMs(), a.finishedMs()));
        for (WishRun r : mine) {
            for (WishRun.Made made : r.made()) {
                if (made.id() != null && !made.id().isBlank()) {
                    return made.id();
                }
            }
            // THEN WHAT THE WISH WAS ABOUT, for a class with no undo path yet.
            //
            // `made` only ever lists things the mod can reverse itself, so an item records nothing
            // there — and this method is the ONLY way a refinement that names nothing ("make it
            // hurt way more") can be routed at all. Reading `made` alone returned "" for every
            // item wish, so every item rung routed `anyone`: the items class's confirming matrix
            // shows 0.0 binding in BOTH arms, i.e. no arm ran. Falling through to the artifact
            // `finish_wish` declared costs nothing and reverses nothing.
            if (!r.finishedArtifact().isBlank()) {
                return r.finishedArtifact();
            }
        }
        return "";
    }

    /** Every run this marshal knows about, finished or not, oldest first. */
    public synchronized List<WishRun> all() {
        return new ArrayList<>(runs.values());
    }

    /** Server stop: a new world must not inherit the last one's in-flight bookkeeping. */
    public synchronized void clear() {
        runs.clear();
        waiting.clear();
        byWorker.clear();
        leases.clear();
        windingDown.clear();
        finishedBy.clear();
        everAssigned.clear();
        pickups.clear();
    }

    /**
     * Record that an externally-chosen wish is now this worker's — the pull path the mod already
     * has, where {@code WishQueue} decides the order and {@code await_wish} hands it over.
     *
     * <p>Separate from {@link #claim} on purpose: claim is the marshal <i>deciding</i>, this is the
     * marshal <i>recording</i>. Collapsing them would mean pretending the scheduler is already in
     * charge of dispatch, which it is not until the pool exists.
     */
    public synchronized WishRun assign(String workerId, UUID wishId) {
        if (workerId == null || workerId.isBlank() || wishId == null) {
            return null;
        }
        windingDown.remove(workerId);
        finishedBy.remove(workerId);
        WishRun run = runs.get(wishId);
        if (run == null || run.state().isFinished()) {
            if (run != null && run.state() == RunState.STOPPED) {
                stoppedBeforeTheBind(workerId, run);
            }
            return null;
        }
        // ── THE INVARIANT claim() ENFORCES AND THIS PATH DOES NOT.
        //
        // AwaitWishTool blocks on the wish QUEUE and calls this directly, so claim()'s guard --
        // "a worker that already holds a wish is not free" -- is never consulted. byWorker holds
        // one entry per worker, so the second assignment silently FORGETS the first, and nothing
        // afterwards can say the earlier wish was ever this worker's.
        //
        // Observed live 2026-08-14: one agent took the metal-factory wish at 23:20:09 and the
        // skyscraper at 23:22:00 with the first still open, which took out the child's progress bar
        // and left the skyscraper hovering on what was plausibly the factory's anchor.
        //
        // Not refused HERE on purpose: by the time this is called the wish has already been taken
        // off the queue, so returning null would drop it on the floor. The fix belongs where the
        // wish is dequeued. Until then it is at least LOUD rather than silent -- a wish quietly
        // reassigned is the kind of thing that gets diagnosed as four unrelated bugs.
        UUID alreadyHeld = byWorker.get(workerId);
        if (alreadyHeld != null && !alreadyHeld.equals(wishId)) {
            WishRun open = runs.get(alreadyHeld);
            if (open != null && open.state() == RunState.WORKING) {
                org.slf4j.LoggerFactory.getLogger(com.moddingfromamod.ModdingFromAModMod.MODID)
                        .warn("worker {} was given wish {} while still working {} — see #141. The "
                        + "earlier wish is no longer attributed to any worker, so its progress bar "
                        + "and its locked build spot are now ambiguous.",
                        workerId, wishId, alreadyHeld);
            }
        }
        waiting.remove(wishId);
        run.assignTo(workerId, clock.nowMs());
        byWorker.put(workerId, wishId);
        everAssigned.add(workerId);
        return run;
    }

    /**
     * <b>Take a wish off the queue and make it this worker's, unless the child has already stopped
     * it </b> — {@link #submit} then {@link #assign}, as one step.
     *
     * <p>{@code await_wish} used to call the two separately, after its pickup gates (which can wait
     * seconds on a grade). A stop that landed in that window left a STOPPED run, and {@code submit}
     * RE-OPENS a finished id as a fresh run (the redirect path needs exactly that) — so the stopped
     * wish was handed over, bound and granted, and the child had been told it stopped. A wish that
     * is still STOPPED here was stopped by the child and nobody re-opened it ({@code /moddy
     * redirect} re-submits its fresh run before it offers the wish), so it is never re-opened here.
     *
     * @return the run now this worker's, or null when there is none — a stopped wish (the worker is
     *         then winding down, see {@link #stoppedBeforeTheBind}), or a blank worker id
     */
    public synchronized WishRun take(String workerId, UUID wishId, UUID playerId, String text) {
        if (wishId == null) {
            return null;
        }
        WishRun existing = runs.get(wishId);
        if (existing != null && existing.state() == RunState.STOPPED) {
            if (workerId != null && !workerId.isBlank()) {
                windingDown.remove(workerId);
                finishedBy.remove(workerId);
                stoppedBeforeTheBind(workerId, existing);
            }
            return null;
        }
        submit(wishId, playerId, text);
        return assign(workerId, wishId);
    }

    /**
     * The worker reached a wish the child stopped before it was bound (path A). It is wound
     * down exactly as if the stop had landed after the bind — {@code CancelGate} refuses everything
     * but {@code await_wish} — and it counts as having been given a wish, so
     * {@code AgentContext.currentRun}'s "the single live run" fallback can never hand it another
     * child's wish.
     */
    private void stoppedBeforeTheBind(String workerId, WishRun stopped) {
        windingDown.put(workerId, stopped);
        everAssigned.add(workerId);
    }

    /**
     * {@code await_wish} dropped a wish after its pickup gates because the BOOK says it is over
     * — the same worker state as {@link #take} refusing a stopped run: wound down when the marshal holds
     * the stopped run, and counted as given a wish either way, so {@code AgentContext.currentRun} never
     * falls back to another child's run for it.
     */
    public synchronized void droppedAfterTheGates(String workerId, UUID wishId) {
        if (workerId == null || workerId.isBlank()) {
            return;
        }
        finishedBy.remove(workerId);
        WishRun run = wishId == null ? null : runs.get(wishId);
        if (run != null && run.state() == RunState.STOPPED) {
            windingDown.put(workerId, run);
        }
        everAssigned.add(workerId);
    }

    /**
     * Drop finished runs older than {@code keepMs}, so a long session doesn't grow this without
     * bound. Finished runs are kept for a while on purpose: {@code @last} and the "is that undoable"
     * question both need the record after the work has stopped.
     */
    public synchronized int forgetFinishedBefore(long cutoffMs) {
        int removed = 0;
        for (var it = runs.entrySet().iterator(); it.hasNext(); ) {
            WishRun r = it.next().getValue();
            if (r.state().isFinished() && r.finishedMs() >= 0 && r.finishedMs() < cutoffMs) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }
}
