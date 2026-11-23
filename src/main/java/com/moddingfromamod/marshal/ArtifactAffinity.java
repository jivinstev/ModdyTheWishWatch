package com.moddingfromamod.marshal;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * <b>Who last worked on a thing, and whether this worker may take the next wish about it.</b>
 *
 * <p>The picker behind {@link WishAffinity}. Pure — no game types, no I/O, no clock of its own — so
 * every routing rule below is a unit test costing milliseconds rather than a 117-second grant.
 *
 * <h2>The starvation rule, which is the whole reason this is not two lines</h2>
 * Forcing SAME means a wish waits for one specific worker; forcing DIFFERENT with a one-worker pool
 * means it waits for a worker that does not exist. Both wedge, and a wedged wish is a child staring
 * at sparkles that never resolve. So a preference is a <b>hold, not a lock</b>: after
 * {@link #HOLD_MS} anyone may take it, and the routing says so with {@link Routing#FALLBACK}.
 *
 * <p>{@link Routing#held()} is the honest half of that. A wish that fell back was <b>not</b> granted
 * under the arm it is labelled with, and scoring it as though it were is how an experiment reports a
 * result it never measured. The scorer excludes it; it does not quietly average it in.
 *
 * <h2>Ownership is remembered at both ends</h2>
 * A refinement names a thing that already exists, so ownership is known when the wish is taken. A
 * <i>creation</i> names nothing — the artifact only acquires an id when the wish finishes and writes
 * its handover record. Recording at just one of those two points loses exactly half the ladders:
 * wish 1 makes Zippy and is forgotten, so wish 2 routes as though Zippy had no history.
 */
public final class ArtifactAffinity {

    /**
     * How long a wish may be held aside for its preferred worker before anyone may take it.
     *
     * <p>Three minutes — about 1.5× the measured p50 wish (117 s), so in a sequential ladder (which
     * is what every arm run is) the owner is free long before this bites, and it only ever fires
     * when the preference genuinely cannot be met. It is a backstop against a wedge, not a queueing
     * policy: in real play {@link WishAffinity#DEFAULT} is {@code OFF} and nothing is ever held.
     */
    public static final long HOLD_MS = 180_000L;

    /**
     * Bounded, because this is keyed by artifact and a long-lived world accumulates them. Ordered
     * so the eviction is oldest-first rather than arbitrary.
     */
    public static final int MAX_REMEMBERED = 512;

    private static final ArtifactAffinity GLOBAL = new ArtifactAffinity();

    private final Map<String, String> owner = new LinkedHashMap<>();

    /**
     * Every worker id this world has offered a wish to — see {@link #workersSeen()}.
     *
     * <p>Unbounded on purpose, unlike {@link #owner}: a pool is a handful of agents per world and
     * the set is cleared between worlds, so there is nothing here to evict. Capping it would make
     * the one number it exists to report wrong in exactly the direction that hides the bug.
     */
    private final java.util.Set<String> seen = new java.util.LinkedHashSet<>();

    /** The one the running game uses. */
    public static ArtifactAffinity global() {
        return GLOBAL;
    }

    /** A private one — the seam every test uses instead of the global. */
    public static ArtifactAffinity forTest() {
        return new ArtifactAffinity();
    }

    /** What routing decided, and — just as importantly — whether the arm actually held. */
    public enum Routing {

        /** No mode is forcing, or nothing has ever worked this thing. Take it. */
        ANYONE(true, true),

        /** {@code SAME}: this worker made it. Take it. */
        OWNER(true, true),

        /** {@code DIFFERENT}: this worker did not make it. Take it. */
        STRANGER(true, true),

        /**
         * The hold expired and the preference could not be met. Taken anyway — <b>and the arm did
         * not hold</b>, so this wish is not evidence about the arm it is labelled with.
         */
        FALLBACK(true, false),

        /** {@code SAME}: somebody else made it and has not come back yet. */
        WAIT_FOR_OWNER(false, true),

        /** {@code DIFFERENT}: this worker is the one that made it. */
        WAIT_FOR_STRANGER(false, true);

        private final boolean take;
        private final boolean held;

        Routing(boolean take, boolean held) {
            this.take = take;
            this.held = held;
        }

        /** May this worker have the wish? */
        public boolean take() {
            return take;
        }

        /**
         * Did routing honour the mode it was asked for?
         *
         * <p>False only for {@link #FALLBACK}. A run whose wishes fell back measured whatever the
         * scheduler did that night, not the arm — which is the failure the whole control exists to
         * remove.
         */
        public boolean held() {
            return held;
        }

        /** The label the action log carries, so an arm can be audited from the data. */
        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * <b>How many distinct workers this world has ever offered a wish to.</b>
     *
     * <p>Not a dashboard number — it decides whether a forcing arm was <em>answerable at all</em>.
     * {@link WishAffinity#DIFFERENT} can only return {@link Routing#STRANGER} when somebody other
     * than the owner asks, so <b>a pool of one makes arm B unsatisfiable by construction</b>: every
     * refinement waits out {@link #HOLD_MS} and falls back, and the cell still finishes and reports
     * a clean pass.
     *
     * <p>Measured: two class-5 arm-B cells came up with a one-worker pool and routed
     * {@code anyone ×5} and {@code fallback ×4}. Both reported {@code PASS 5/5} with
     * {@code final_continuity 1.0} and every declared property delivered. The only way anyone found
     * out was counting worker ids in the action log by hand, which is not a thing a gate can do.
     *
     * <p>Counted on <b>both</b> {@link #decide} and {@link #remember}, and it needs to be both. A
     * worker that is <em>refused</em> is still a worker that existed — and it is precisely the
     * existence of a second one that arm B needs — so counting only takers would report 1 for
     * exactly the pool this is meant to detect. But a worker that takes a wish demonstrably exists
     * too, and nothing should require it to have been refused first for the census to notice it.
     * Either alone is a census that under-counts on some path.
     */
    public synchronized int workersSeen() {
        return seen.size();
    }

    /** This worker is now the one that has most recently worked this thing. */
    public synchronized void remember(String artifactId, String workerId) {
        String key = key(artifactId);
        if (key.isEmpty() || workerId == null || workerId.isBlank()) {
            return;
        }
        seen.add(workerId);
        // Re-insert so recency drives eviction: put() on an existing key keeps the OLD position in
        // a LinkedHashMap, which would evict the thing being actively refined.
        owner.remove(key);
        owner.put(key, workerId);
        while (owner.size() > MAX_REMEMBERED) {
            owner.remove(owner.keySet().iterator().next());
        }
    }

    /** The worker that last worked this thing, or "" if nobody has. */
    public synchronized String ownerOf(String artifactId) {
        String key = key(artifactId);
        if (key.isEmpty()) {
            return "";
        }
        String w = owner.get(key);
        return w == null ? "" : w;
    }

    /** Between worlds: one world's ownership must never route another world's wishes. */
    public synchronized void clear() {
        owner.clear();
        seen.clear();
    }

    public synchronized int size() {
        return owner.size();
    }

    /**
     * May {@code workerId} take a wish about {@code artifactId}?
     *
     * @param heldForMs how long this wish has already been held aside for a preferred worker; 0 the
     *                  first time it is offered
     */
    public Routing decide(WishAffinity mode, String workerId, String artifactId, long heldForMs) {
        if (workerId != null && !workerId.isBlank()) {
            synchronized (this) {
                seen.add(workerId);
            }
        }
        WishAffinity m = mode == null ? WishAffinity.DEFAULT : mode;
        if (!m.forcing()) {
            return Routing.ANYONE;
        }
        String holder = ownerOf(artifactId);
        if (holder.isEmpty()) {
            // Nothing owns it — a creation, or a thing made before this control existed. Both arms
            // agree here, and pretending otherwise would make arm B unable to start a ladder at all.
            return Routing.ANYONE;
        }
        // A worker with no identity (a direct call, a unit test) cannot be compared to an owner.
        // Refusing it would wedge those paths; letting it through under a forcing mode would
        // silently mislabel the wish. FALLBACK says both: it was taken, and it is not evidence.
        if (workerId == null || workerId.isBlank()) {
            return Routing.FALLBACK;
        }
        boolean isOwner = holder.equals(workerId);
        boolean wanted = m == WishAffinity.SAME ? isOwner : !isOwner;
        if (wanted) {
            return m == WishAffinity.SAME ? Routing.OWNER : Routing.STRANGER;
        }
        if (heldForMs >= HOLD_MS) {
            return Routing.FALLBACK;
        }
        return m == WishAffinity.SAME ? Routing.WAIT_FOR_OWNER : Routing.WAIT_FOR_STRANGER;
    }

    private static String key(String artifactId) {
        return artifactId == null ? "" : artifactId.trim().toLowerCase(Locale.ROOT);
    }
}
