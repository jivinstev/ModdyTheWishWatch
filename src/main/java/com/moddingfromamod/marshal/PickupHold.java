package com.moddingfromamod.marshal;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * <b>A wish handed to a worker before its pickup verdicts landed</b> — the ratchet's state.
 *
 * <p>The input safety grade (and, on a world that denies anything, the world-rules grade) used to
 * hold every wish at the dequeue: measured, the worker sat idle 5.4–7.4 s on every wish (61 of 61
 * local grants, 88 of 99 prod wishes) for a verdict that changed nothing 99% of the time (1 block in
 * 109 real wishes, 0 redirects). Now, when those verdicts are still running, the wish is handed over
 * at once and OPENED here; the agent may look around and think, but every tool that could put a word
 * in front of the child or change the world parks ({@link PickupGatedTools}) until the hold is
 * decided:
 *
 * <ul>
 *   <li>{@link #clear} — every verdict passed the wish unchanged; parked calls go on, and the first
 *       one carries any note the verdicts produced (a MAYBE band's guidance, a world-rules note);</li>
 *   <li>{@link #rescind} — a verdict refused or REWORDED the wish. That worker's session is marked
 *       too, so every call it makes from then on is refused while the pool stops it: an agent that
 *       read words it should not have never acts on them, and a redirect is granted by a FRESH worker
 *       that only ever saw the redirect (AwaitWishTool's rule, kept exactly).</li>
 * </ul>
 *
 * <p>Pure apart from the latch: no Minecraft, no clock of its own ({@code PickupHoldTest}).
 */
public final class PickupHold {

    /** Where a wish's pickup stands. */
    public enum State { NONE, PENDING, CLEARED, RESCINDED, REFUSED, STOPPED }

    private static final class Hold {
        final CountDownLatch decided = new CountDownLatch(1);
        volatile State state = State.PENDING;
        volatile String note = "";
    }

    private static final PickupHold GLOBAL = new PickupHold();
    private static final PickupHold PLANS = new PickupHold();

    private final Map<UUID, Hold> holds = new ConcurrentHashMap<>();
    private final Set<String> rescindedSessions = ConcurrentHashMap.newKeySet();

    /** The INPUT verdicts' holds: a wish handed over before its input (and world-rules) grade landed. */
    public static PickupHold global() {
        return GLOBAL;
    }

    /**
     * The PLAN grade's holds: a plan the agent has made, still being graded. While one is open
     * the world-changing and child-visible tools park exactly as they do for the input verdicts; a
     * plan the grade refuses is {@link #refuse refused} with the grader's message, which the next
     * parked call returns so the agent revises the plan IN PLACE (the plan is its own, and its
     * context is not the child's words) — then a new plan_wish opens a new hold.
     */
    public static PickupHold plans() {
        return PLANS;
    }

    /** A fresh instance, for a test that must not share the global one. */
    public static PickupHold forTest() {
        return new PickupHold();
    }

    /** This wish was handed over with its verdicts still running. */
    public void open(UUID wishId) {
        if (wishId != null) {
            holds.put(wishId, new Hold());
        }
    }

    /** Every verdict passed it as it was: let the parked calls go, carrying {@code note} once. */
    public void clear(UUID wishId, String note) {
        Hold h = wishId == null ? null : holds.get(wishId);
        if (h != null && h.state == State.PENDING) {
            h.note = note == null ? "" : note;
            h.state = State.CLEARED;
            h.decided.countDown();
        }
    }

    /** A verdict refused or reworded it: this session never acts again; parked calls are refused. */
    public void rescind(UUID wishId, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            rescindedSessions.add(sessionId);
        }
        Hold h = wishId == null ? null : holds.get(wishId);
        if (h != null && h.state == State.PENDING) {
            h.state = State.RESCINDED;
            h.decided.countDown();
        }
    }

    /**
     * The child stopped the wish: wake every parked call. The caller re-asks
     * {@code CancelGate}, which refuses it — a stop must never wait for a grade to land, and a grade
     * landing afterwards must not let the call through.
     */
    public void stop(UUID wishId) {
        Hold h = wishId == null ? null : holds.get(wishId);
        if (h != null && h.state == State.PENDING) {
            h.state = State.STOPPED;
            h.decided.countDown();
        }
    }

    /** The grade refused it: parked calls are refused with {@code message} (the agent revises). */
    public void refuse(UUID wishId, String message) {
        Hold h = wishId == null ? null : holds.get(wishId);
        if (h != null && h.state == State.PENDING) {
            h.note = message == null ? "" : message;
            h.state = State.REFUSED;
            h.decided.countDown();
        }
    }

    /** A refused hold's message, for every call it refuses (not handed out once like a note). */
    public String refusal(UUID wishId) {
        Hold h = wishId == null ? null : holds.get(wishId);
        return h == null || h.state != State.REFUSED ? "" : h.note;
    }

    public State state(UUID wishId) {
        Hold h = wishId == null ? null : holds.get(wishId);
        return h == null ? State.NONE : h.state;
    }

    /** Was this session's wish taken away from it? Then nothing it calls may run. */
    public boolean rescinded(String sessionId) {
        return sessionId != null && rescindedSessions.contains(sessionId);
    }

    /**
     * Wait up to {@code timeoutMs} for the wish's hold to be decided. Returns the state after the
     * wait: {@link State#PENDING} means the ceiling passed with nothing decided, and the caller
     * refuses (fail closed — a call that cannot be cleared does not run).
     */
    public State await(UUID wishId, long timeoutMs) throws InterruptedException {
        Hold h = wishId == null ? null : holds.get(wishId);
        if (h == null) {
            return State.NONE;
        }
        if (h.state == State.PENDING) {
            h.decided.await(Math.max(0, timeoutMs), TimeUnit.MILLISECONDS);
        }
        return h.state;
    }

    /** The note a cleared hold carries, handed out ONCE (the first parked call's result says it). */
    public String takeNote(UUID wishId) {
        Hold h = wishId == null ? null : holds.get(wishId);
        if (h == null || h.state != State.CLEARED || h.note.isEmpty()) {
            return "";
        }
        synchronized (h) {
            String n = h.note;
            h.note = "";
            return n;
        }
    }

    /** The wish is over or re-queued: its hold is no longer anybody's business. */
    public void forget(UUID wishId) {
        if (wishId != null) {
            holds.remove(wishId);
        }
    }

    private final Set<String> retireAfterWish = ConcurrentHashMap.newKeySet();

    /**
     * A safety layer flagged this session's wish IN its context (a plan refused or diverted): it
     * finishes the wish, and is stopped before it can take another — a flagged wish never sits in the
     * context of a later one.
     */
    public void retireAfterWish(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            retireAfterWish.add(sessionId);
        }
    }

    /** Must this session be stopped instead of being handed another wish? Answered once. */
    public boolean takeRetire(String sessionId) {
        return sessionId != null && retireAfterWish.remove(sessionId);
    }

    /** A stopped session is gone: stop remembering it (the pool never reuses a session id). */
    public void forgetSession(String sessionId) {
        if (sessionId != null) {
            rescindedSessions.remove(sessionId);
        }
    }
}
