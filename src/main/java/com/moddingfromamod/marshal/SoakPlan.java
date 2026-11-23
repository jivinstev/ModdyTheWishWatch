package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>The schedule of a pool soak</b> — what a household server does to the wish pool over
 * hours nobody is watching, compressed into cycles.
 *
 * <p>A cycle is the shape of one play session: somebody joins, makes two wishes a minute apart
 * (the second arrives while the first is mid-grant, which is exactly when an earlier issue's deaf worker 2 is
 * booted), plays on, and leaves. The world then sits empty long enough for {@link IdlePolicy} to
 * reap the pool, and the next cycle's join has to bring it back. Every cycle therefore
 * exercises the three things the pool is for — boot on join, top up under load, reap when empty —
 * and the second cycle onwards is the one that matters, because it runs against a pool that has
 * already been stopped once.
 *
 * <p>Pure, so the timeline is unit-tested rather than discovered two hours into a paid run: the
 * gap between LEAVE and the next JOIN must exceed the reap grace, or the soak never sees a reap and
 * quietly measures a pool that was never stopped. {@link #of} refuses a cycle that short.
 */
public final class SoakPlan {

    /** What happens at one moment of the soak. */
    public enum Kind { JOIN, WISH, LEAVE, END }

    /** One scheduled step: at {@code atMs} after the soak began, do {@code kind}. */
    public record Step(long atMs, Kind kind, String wish) {
    }

    /** Minutes into a cycle. Named so the javadoc above and the numbers cannot disagree. */
    static final long FIRST_WISH_MS = 2 * 60_000L;
    static final long SECOND_WISH_MS = 3 * 60_000L;
    static final long LEAVE_MS = 25 * 60_000L;

    /** The shortest cycle that still leaves the empty world long enough to be reaped, with room. */
    public static long minCycleMs() {
        return LEAVE_MS + IdlePolicy.GRACE_MS + 5 * 60_000L;
    }

    private final List<Step> steps;

    private SoakPlan(List<Step> steps) {
        this.steps = List.copyOf(steps);
    }

    public List<Step> steps() {
        return steps;
    }

    /**
     * Build the schedule.
     *
     * @param totalMs how long the whole soak runs; the last step is END at exactly this
     * @param cycleMs how long one join→leave→empty cycle lasts; at least {@link #minCycleMs()}
     * @param wishes  the wishes to make, two per cycle, taken in order and reused round-robin
     */
    public static SoakPlan of(long totalMs, long cycleMs, List<String> wishes) {
        if (cycleMs < minCycleMs()) {
            throw new IllegalArgumentException("a cycle of " + cycleMs / 60_000 + " min never "
                    + "lets the pool be reaped — the minimum is " + minCycleMs() / 60_000 + " min");
        }
        if (wishes == null || wishes.isEmpty()) {
            throw new IllegalArgumentException("a soak needs at least one wish to make");
        }
        List<Step> out = new ArrayList<>();
        int next = 0;
        for (long start = 0; start + LEAVE_MS <= totalMs; start += cycleMs) {
            // The first cycle's JOIN is the server starting — the pool is booted by world load,
            // as it is for a real household. It is still recorded, so every cycle reads alike.
            out.add(new Step(start, Kind.JOIN, null));
            out.add(new Step(start + FIRST_WISH_MS, Kind.WISH, wishes.get(next++ % wishes.size())));
            out.add(new Step(start + SECOND_WISH_MS, Kind.WISH, wishes.get(next++ % wishes.size())));
            out.add(new Step(start + LEAVE_MS, Kind.LEAVE, null));
        }
        out.add(new Step(totalMs, Kind.END, null));
        return new SoakPlan(out);
    }

    /** Is anybody "online" at {@code atMs}? Between a JOIN and its LEAVE, and not after END. */
    public boolean presentAt(long atMs) {
        boolean present = false;
        for (Step s : steps) {
            if (s.atMs() > atMs) {
                break;
            }
            switch (s.kind()) {
                case JOIN -> present = true;
                case LEAVE, END -> present = false;
                default -> { }
            }
        }
        return present;
    }
}
