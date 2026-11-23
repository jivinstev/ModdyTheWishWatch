package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>The keystone of the whole test plan:</b> workers with programmable latency, completion order
 * and failure modes, driven by a clock a test turns by hand.
 *
 * <p>Without it every scheduling question needs a real agent — 200–400 seconds and real tokens per
 * wish — which makes the interesting cases (out-of-order return, mid-flight cancel, starvation,
 * lease contention, a worker dying halfway) both unaffordable and flaky. With it they are Gate-A
 * tests costing milliseconds.
 *
 * <p><b>It models the real mechanism, not a convenient one.</b> A worker here does exactly what a
 * parked agent does: ask for work ({@link Marshal#claim}), hold it for a while, then report
 * {@link Marshal#finish}. If the marshal says no, the worker asks again later. So a test written
 * against this is not proving something about a fiction.
 *
 * <p>Fully deterministic — no wall-clock, no {@code Random} unless a test seeds one through
 * {@link Seeds}. A concurrency test that passes once proves nothing, so these are meant to be run
 * many times; a run that differs between repeats is an instrument bug, and instrument bugs have
 * outnumbered real ones about five to one in this repo.
 *
 * <p>Lives in {@code src/main} so Gate-B {@code @GameTest}s can use it too.
 */
public final class ScriptedWorkerPool {

    private final Marshal marshal;
    private final FakeClock clock;
    private final List<String> workers = new ArrayList<>();

    /** wish text → how long a worker holds it. */
    private final Map<String, Long> durations = new HashMap<>();

    /** wish text → how far in the worker dies instead of finishing. */
    private final Map<String, Long> deaths = new HashMap<>();

    /** worker → what it is holding and when that ends. */
    private final Map<String, Job> jobs = new LinkedHashMap<>();

    private long defaultDurationMs = 100L;
    private long stepMs = 10L;

    private final List<String> assignmentOrder = new ArrayList<>();
    private final List<String> completionOrder = new ArrayList<>();
    private final List<String> deathOrder = new ArrayList<>();

    private record Job(UUID wishId, String text, long endsAtMs, boolean fatal) {
    }

    public ScriptedWorkerPool(Marshal marshal, FakeClock clock, int workerCount) {
        this.marshal = marshal;
        this.clock = clock;
        for (int i = 1; i <= Math.max(1, workerCount); i++) {
            workers.add("w" + i);
        }
    }

    // ---- the script ------------------------------------------------------------------------

    /** This wish takes this long. */
    public ScriptedWorkerPool takes(String wishText, long ms) {
        durations.put(wishText, Math.max(0, ms));
        return this;
    }

    /**
     * The worker granting this wish <b>dies</b> this far in — no finish, no message, nothing. The
     * wish must come back, not disappear.
     */
    public ScriptedWorkerPool diesAfter(String wishText, long ms) {
        deaths.put(wishText, Math.max(0, ms));
        return this;
    }

    /** How long an unscripted wish takes. */
    public ScriptedWorkerPool defaultDuration(long ms) {
        this.defaultDurationMs = Math.max(0, ms);
        return this;
    }

    /** How finely the clock is turned. Smaller is slower but resolves closer completions apart. */
    public ScriptedWorkerPool step(long ms) {
        this.stepMs = Math.max(1, ms);
        return this;
    }

    // ---- driving ---------------------------------------------------------------------------

    /** Turn the clock until it reads at least {@code endMs}, playing the script the whole way. */
    public void runUntil(long endMs) {
        settle();
        while (clock.nowMs() < endMs) {
            clock.advance(stepMs);
            settle();
        }
    }

    /** Run until nothing is left to do, or {@code maxMs} of virtual time has passed. */
    public void runToQuiet(long maxMs) {
        long deadline = clock.nowMs() + maxMs;
        settle();
        while (clock.nowMs() < deadline
                && (!marshal.live().isEmpty() || !jobs.isEmpty())) {
            clock.advance(stepMs);
            settle();
        }
    }

    /**
     * One beat: retire anything due, then let every idle worker ask for work.
     *
     * <p>Retiring first is deliberate and is the out-of-order case: a worker that just finished is
     * immediately eligible to take the next wish, which is exactly how a real pool behaves and
     * exactly what makes completion order diverge from arrival order.
     */
    public void settle() {
        retireDue();
        offerWork();
    }

    private void retireDue() {
        long now = clock.nowMs();
        for (String worker : new ArrayList<>(jobs.keySet())) {
            Job job = jobs.get(worker);
            if (job == null || job.endsAtMs() > now) {
                continue;
            }
            jobs.remove(worker);
            // A run the child stopped while it was in flight is already finished as far as the
            // marshal is concerned; the worker's own wind-down must not resurrect or re-report it.
            WishRun run = marshal.run(job.wishId());
            boolean stillLive = run != null && run.state().isLive();
            if (job.fatal()) {
                marshal.workerDied(worker);
                deathOrder.add(worker + ":" + job.text());
            } else if (stillLive) {
                marshal.finish(job.wishId());
                completionOrder.add(job.text());
            }
        }
    }

    private void offerWork() {
        long now = clock.nowMs();
        for (String worker : workers) {
            if (jobs.containsKey(worker)) {
                continue;
            }
            WishRun run = marshal.claim(worker);
            if (run == null) {
                continue;
            }
            String text = run.text();
            Long dieAt = deaths.get(text);
            long duration = dieAt != null ? dieAt : durations.getOrDefault(text, defaultDurationMs);
            jobs.put(worker, new Job(run.wishId(), text, now + duration, dieAt != null));
            assignmentOrder.add(worker + ":" + text);
            if (dieAt != null) {
                // Only die the FIRST time: a wish that kills every worker it touches would loop
                // forever, and "re-dispatched, not lost" is the property under test.
                deaths.remove(text);
            }
        }
    }

    // ---- what happened ---------------------------------------------------------------------

    /** Wish texts in the order they were actually finished. */
    public List<String> completionOrder() {
        return new ArrayList<>(completionOrder);
    }

    /** {@code worker:text} in the order work was handed out. */
    public List<String> assignmentOrder() {
        return new ArrayList<>(assignmentOrder);
    }

    public List<String> deathOrder() {
        return new ArrayList<>(deathOrder);
    }

    /** Which worker is holding this wish right now, or null. */
    public String workerOf(String wishText) {
        for (var e : jobs.entrySet()) {
            if (e.getValue().text().equals(wishText)) {
                return e.getKey();
            }
        }
        return null;
    }

    public int busyWorkers() {
        return jobs.size();
    }

    public List<String> workers() {
        return new ArrayList<>(workers);
    }
}
