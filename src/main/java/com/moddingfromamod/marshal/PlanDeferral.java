package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * <b>What a plan shows the child, held until the plan safety grade has passed it</b>.
 *
 * <p>The plan grade used to block {@code plan_wish} for 3–4.5 s (Haiku 5.5, measured) while the agent
 * waited. Now {@code plan_wish} runs at once for the AGENT — its result, the wish's kind, the anchor —
 * and the grade runs beside the agent's next turn. What the plan shows the CHILD (the bar's name and
 * steps, "✨ Here's my plan", the sparkles line) is queued here instead of shown, then {@link #flush
 * flushed} when the grade passes or {@link #drop dropped} when it refuses — so, exactly as before, a
 * plan the grade refuses never reaches him.
 *
 * <p>Pure: Runnables in a map. The caller runs a flush on the server thread ({@code PlanDeferralTest}).
 */
public final class PlanDeferral {

    private static final PlanDeferral GLOBAL = new PlanDeferral();

    private final Map<UUID, List<Runnable>> held = new HashMap<>();
    /** Plans the grade refused, until the wish's next plan is held: a late show of one is never run. */
    private final Set<UUID> dropped = new HashSet<>();

    public static PlanDeferral global() {
        return GLOBAL;
    }

    public static PlanDeferral forTest() {
        return new PlanDeferral();
    }

    /** From now until a flush or a drop, what this wish's plan would show the child is held. */
    public synchronized void hold(UUID wishId) {
        if (wishId != null) {
            held.put(wishId, new ArrayList<>());
            dropped.remove(wishId);
        }
    }

    public synchronized boolean holding(UUID wishId) {
        return wishId != null && held.containsKey(wishId);
    }

    /**
     * Show it now, hold it while this wish's plan is graded, or — when the grade already REFUSED the
     * plan it belongs to (the grade can finish before the tool body reaches this line) — never.
     * Returns what the caller must run now, or null.
     */
    public synchronized Runnable show(UUID wishId, Runnable show) {
        if (show == null || (wishId != null && dropped.contains(wishId))) {
            return null;
        }
        List<Runnable> q = wishId == null ? null : held.get(wishId);
        if (q == null) {
            return show;
        }
        q.add(show);
        return null;
    }

    /** The grade passed: everything held, in order. The caller runs it on the server thread. */
    public synchronized List<Runnable> flush(UUID wishId) {
        List<Runnable> q = wishId == null ? null : held.remove(wishId);
        return q == null ? List.of() : q;
    }

    /** The grade refused: nothing held is ever shown, nor anything this plan tries to show later. */
    public synchronized void drop(UUID wishId) {
        if (wishId != null) {
            held.remove(wishId);
            dropped.add(wishId);
        }
    }

    /** The wish is over: forget it either way. */
    public synchronized void forget(UUID wishId) {
        if (wishId != null) {
            held.remove(wishId);
            dropped.remove(wishId);
        }
    }
}
