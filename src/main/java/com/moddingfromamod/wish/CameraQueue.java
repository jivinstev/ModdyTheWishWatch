package com.moddingfromamod.wish;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.UUID;

/**
 * <b>Whose turn is it to hold the camera</b> — one camera, several wishes, an explicit order.
 *
 * <p>Minecraft-free and unit-tested, because the decision (<i>may this wish take the camera now,
 * and if not, when</i>) is the part concurrency changes and the part a gate can assert without a
 * world. {@link WishCapture} owns the freezing, teleporting and telling; this owns only the order.
 *
 * <h2>Why this exists </h2>
 *
 * <p>Before it there was a lease and no queue: a second wish was told {@code BUSY} and
 * {@code PhotographTool} spun on it six times, then fell through to a single unframed snapshot —
 * which is the wish card. {@code PhotographTool}'s own comment records what that looked like:
 * <i>"one ladder run produced no framed shot at all and an identical build produced eight per rung,
 * so the difference was never the code; it was who happened to be holding the lease."</i>
 *
 * <p>Retrying raises the odds and fixes nothing, because <b>nothing ordered the waiters</b>. Two
 * children wishing at once was decided by scheduling, and the loser's card was silently the wrong
 * picture. A queue makes the wait bounded and fair, and — the part that matters more — makes the
 * waiting VISIBLE, so {@link #depth()} can say when one camera has stopped being enough.
 *
 * <h2>⚠ A null owner never contends, and that is deliberate</h2>
 *
 * <p>Not every capture belongs to a wish: a headless probe, a manual command and several gates take
 * photographs with no {@code AgentContext} run in scope, and arrive here with a null owner. Those
 * SHARE rather than queue — exactly as the pre-queue lease did — because making them contend would
 * turn "two unrelated tests took a photo" into a stall, and because there is no second party for
 * them to be unfair to. The rule is stated here rather than discovered in a red gate.
 */
public final class CameraQueue {

    /** What a claim did. */
    public enum Outcome {
        /** The camera is yours now; go ahead. */
        GRANTED,
        /** Somebody else holds it. You are in line — ask again, do not steal it. */
        QUEUED
    }

    private record Waiter(UUID owner, long sinceMs) {
    }

    private UUID holder;
    private boolean held;
    private long heldSinceMs;
    private long lastActivityMs;
    private final Deque<Waiter> waiting = new ArrayDeque<>();

    /**
     * Take the camera, or join the queue.
     *
     * <p>Re-claiming as the current holder is how a burst of shots keeps the session open, so it
     * refreshes the activity clock rather than being refused — the same behaviour the lease had.
     */
    public Outcome claim(UUID owner, long nowMs) {
        if (holder == null) {
            holder = owner;
            held = true;
            heldSinceMs = nowMs;
            lastActivityMs = nowMs;
            waiting.removeIf(w -> Objects.equals(w.owner(), owner));
            return Outcome.GRANTED;
        }
        if (Objects.equals(holder, owner) && owner != null) {
            // Already ours, so this is the next shot of a burst -- EXCEPT the first time after a
            // promotion, where release() handed it over and only now is it actually being used.
            // Starting the hold clock at the handover would charge a waiter for however long the
            // previous wish took; never starting it at all is the wedge expire() exists to stop.
            if (!held) {
                held = true;
                heldSinceMs = nowMs;
            }
            lastActivityMs = nowMs;
            return Outcome.GRANTED;
        }
        // SHARED, NOT QUEUED, when either side is anonymous -- see the class note. Preserving this
        // is what keeps every existing headless capture working; a queue that made them contend
        // would deadlock a gate that takes two photographs and never releases in between.
        if (owner == null) {
            lastActivityMs = nowMs;
            return Outcome.GRANTED;
        }
        if (waiting.stream().noneMatch(w -> Objects.equals(w.owner(), owner))) {
            waiting.addLast(new Waiter(owner, nowMs));
        }
        return Outcome.QUEUED;
    }

    /**
     * Put the camera down and hand it to whoever is next.
     *
     * <p>Only the holder may release, so a second wish's tidy-up cannot end the first one's
     * session. {@code force} is the watchdog's exemption: the freeze must ALWAYS end however wedged
     * things get, which is a rule {@link WishCapture} already carried and this must not weaken.
     */
    public void release(UUID owner, boolean force, long nowMs) {
        // ONLY THE HOLDER, and an anonymous caller is never the holder. A headless probe shares
        // the camera rather than taking it (see the class note), so its tidy-up must not be able to
        // end a real wish's burst -- which a "null owner may release anything" rule would allow.
        if (!force && !Objects.equals(holder, owner)) {
            return;
        }
        holder = null;
        held = false;
        Waiter next = waiting.pollFirst();
        if (next != null) {
            holder = next.owner();
            // NOT held yet: the promotion is an offer, and the waiter may never come back for it.
            // The hold clock starts when it claims (above); until then expire() judges it by the
            // handover time, so a wish that gave up cannot hold the camera for the life of the
            // server -- which is the one way a queue is worse than the spin it replaced.
            heldSinceMs = nowMs;
            lastActivityMs = nowMs;
        }
    }

    /** A waiter that gave up. Without this, a wish that timed out blocks everyone behind it. */
    public void abandon(UUID owner) {
        waiting.removeIf(w -> Objects.equals(w.owner(), owner));
    }

    /**
     * Drop a holder that has held too long and waiters that have waited too long.
     *
     * <p>The queue must not be able to wedge: a wish that dies mid-burst would otherwise hold the
     * camera for the life of the server, and every wish behind it would wait forever. Called from
     * the same ticker that already runs {@link WishCapture}'s freeze watchdog.
     *
     * @return true if anything was dropped, so the caller can say so
     */
    public boolean expire(long nowMs, long maxHoldMs, long maxWaitMs) {
        boolean changed = false;
        if (holder != null && nowMs - heldSinceMs > maxHoldMs) {
            release(holder, true, nowMs);
            changed = true;
        }
        changed |= waiting.removeIf(w -> nowMs - w.sinceMs() > maxWaitMs);
        return changed;
    }

    /** Who holds it, or null. */
    public UUID holder() {
        return holder;
    }

    /** How many wishes are waiting behind the holder. */
    public int depth() {
        return waiting.size();
    }

    /**
     * 0 = holds it, 1 = next, -1 = not in line at all.
     *
     * <p>The caller reports this rather than a bare "busy", because "you are second" and "you are
     * ninth" want different responses and a boolean cannot tell them apart.
     */
    public int positionOf(UUID owner) {
        if (Objects.equals(holder, owner)) {
            return 0;
        }
        int i = 1;
        for (Waiter w : waiting) {
            if (Objects.equals(w.owner(), owner)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /** Nothing held, nobody waiting. */
    public boolean idle() {
        return holder == null && waiting.isEmpty();
    }
}
