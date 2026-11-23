package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>Which worker is which</b> (an earlier issue's blocker): one row per pool SLOT, joining the three
 * names a single worker goes by.
 *
 * <ul>
 *   <li>its <b>slot name</b> ({@code wish-granter-2}) — ours, chosen at launch, and carried on
 *       every MCP request in the {@code X-Moddy-Worker} header of its own config file;</li>
 *   <li>its <b>CLI session id</b> — the CLI's, read out of the launcher's log, and the only handle
 *       {@code claude stop} accepts;</li>
 *   <li>its <b>MCP session id</b> — the server's, minted at {@code initialize}, and the id the
 *       marshal and the queue know it by. It can CHANGE: a session idle past its TTL is
 *       re-initialised under a new id, which is why the header is read on every request rather
 *       than once.</li>
 * </ul>
 *
 * <p>Before this, the three were separate namespaces nothing correlated, so the pool could COUNT a
 * deaf worker but never say which one it was — and a worker you cannot name is one you can
 * only stop by stopping all of them. That is why reaping was whole-pool only, and why a deaf worker
 * held its slot until the world closed.
 *
 * <p>Pure: no Minecraft, no processes, no clock of its own. {@code AgentSession} owns the launchers
 * and asks this class one question per supervisor tick — {@link #deaf} — and acts on the answer.
 */
public final class WorkerRoster {

    /** One slot. Mutable, and only ever touched under the roster's monitor. */
    public static final class Slot {
        private final String name;
        private final long bootedAtMs;
        private String cliId;
        private String mcpId;
        private long lastHeardMs;
        private long unaccountedSinceMs;
        private long lastBusyMs;
        /** When it was first sent the tool list, or 0. */
        private long listedAtMs;
        /** Whether it has ever called a tool. */
        private boolean calledTool;
        /** Every MCP session this incarnation has been heard on — see {@link #sessionsOf}. */
        private final Set<String> sessions = new LinkedHashSet<>();

        Slot(String name, long bootedAtMs) {
            this.name = name;
            this.bootedAtMs = bootedAtMs;
        }

        public String name() {
            return name;
        }

        public long bootedAtMs() {
            return bootedAtMs;
        }

        public String cliId() {
            return cliId;
        }

        public String mcpId() {
            return mcpId;
        }

        public long lastHeardMs() {
            return lastHeardMs;
        }

        /** When it was first sent the tool list, or 0. */
        public long listedAtMs() {
            return listedAtMs;
        }

        /** Whether it has ever called a tool. */
        public boolean calledTool() {
            return calledTool;
        }

        /**
         * When this worker last stopped holding a wish, or its boot if it never held one — the
         * clock {@link PoolPlan#shrinkCandidate} reads. Kept by {@link #idleClock}.
         */
        public long idleSinceMs() {
            return Math.max(bootedAtMs, lastBusyMs);
        }
    }

    private final Map<String, Slot> slots = new LinkedHashMap<>();

    /**
     * Every slot name this roster has ever launched. Never cleared — not even by
     * {@link #clear()} — because a name it once launched is a name whose stopped incarnations may
     * still be talking: see {@link #mayTake}.
     */
    private final Set<String> launched = new HashSet<>();

    /** A worker was launched into {@code name}. Replaces whatever held that name before. */
    public synchronized void booted(String name, long nowMs) {
        slots.put(name, new Slot(name, nowMs));
        launched.add(name);
    }

    /** The CLI printed this worker's session id. */
    public synchronized void cliId(String name, String id) {
        Slot s = slots.get(name);
        if (s != null) {
            s.cliId = id;
        }
    }

    /**
     * A request arrived carrying {@code name} in its identity header, on MCP session {@code mcpId}.
     *
     * <p>Only a name the pool actually launched is recorded — the header is authenticated (it
     * arrives beside the bearer token) but a map keyed by whatever a client typed is still a map
     * that grows without bound.
     *
     * @return whether the name belonged to a live slot
     */
    public synchronized boolean heard(String name, String mcpId, long nowMs) {
        Slot s = name == null ? null : slots.get(name);
        if (s == null) {
            return false;
        }
        s.mcpId = mcpId;
        s.lastHeardMs = nowMs;
        if (mcpId != null && !mcpId.isBlank()) {
            s.sessions.add(mcpId);
        }
        return true;
    }

    /**
     * What a request on this slot asked for: the first {@code tools/list}, and whether it has
     * ever made a {@code tools/call}. Together they name the worker that connected, was given its tools
     * and then said nothing — see {@link #deaf}.
     */
    public synchronized void asked(String name, String method, long nowMs) {
        Slot s = name == null ? null : slots.get(name);
        if (s == null || method == null) {
            return;
        }
        if ("tools/list".equals(method) && s.listedAtMs == 0) {
            s.listedAtMs = nowMs;
        } else if ("tools/call".equals(method)) {
            s.calledTool = true;
        }
    }

    /**
     * <b>May this caller take a wish?</b> — {@code false} only for a request that names a
     * slot this pool launched but is NOT that slot's live incarnation on its current MCP session.
     *
     * <p>Measured on a live server, 2026-10-13: every time the pool stopped an idle extra worker, an
     * {@code await_wish} carrying the stopped slot's name arrived within half a second on a BRAND-NEW
     * MCP session — after {@link #forget}, so nothing knew it, and on a session id the queue had
     * never retired, so {@link com.moddingfromamod.agent.WishQueue#retireIfParked} could not reach
     * it. It parked like any worker, took the next wish, and returned it to a process that no longer
     * existed: wish 05de5b59 was "claimed by wish-granter-1" and then nothing happened for 905 s,
     * while the REAL wish-granter-1 (booted 40 s later under the same name) was told "no wish
     * arrived". Twice in one evening, the second time taking a 👎 re-wish.
     *
     * <p>So the identity that may take a wish is (slot name, the session that slot was last heard
     * on): a stopped incarnation's session never matches, whether the slot is still forgotten or a
     * new worker has since been booted into the name.
     *
     * <p>A request with no slot name (a gate, a direct call) or a name this pool never launched
     * (not ours to judge) is allowed, exactly as before.
     */
    public synchronized boolean mayTake(String name, String mcpId) {
        if (name == null || name.isBlank() || !launched.contains(name)) {
            return true;
        }
        Slot s = slots.get(name);
        return s != null && s.mcpId != null && s.mcpId.equals(mcpId);
    }

    /**
     * Every MCP session the live incarnation of {@code name} has been heard on — so "is this worker
     * still holding a wish?" can be asked of each one. A session can change under a worker
     * (a re-initialise after the TTL), and a busy check keyed by the CURRENT session alone would let
     * a worker that re-initialised mid-wish take a second one. Empty for an unknown name.
     */
    public synchronized Set<String> sessionsOf(String name) {
        Slot s = name == null ? null : slots.get(name);
        return s == null ? Set.of() : Set.copyOf(s.sessions);
    }

    /** The slot name whose live incarnation was last heard on {@code mcpId}, or null. */
    public synchronized String nameOfSession(String mcpId) {
        if (mcpId == null || mcpId.isBlank()) {
            return null;
        }
        for (Slot s : slots.values()) {
            if (mcpId.equals(s.mcpId) || s.sessions.contains(mcpId)) {
                return s.name;
            }
        }
        return null;
    }

    /** The slot is gone — stopped, or never launched. */
    public synchronized Slot forget(String name) {
        return slots.remove(name);
    }

    public synchronized void clear() {
        slots.clear();
    }

    public synchronized List<Slot> snapshot() {
        return new ArrayList<>(slots.values());
    }

    /**
     * The lowest free slot name. {@code base} alone when the pool has one slot, so a single-worker
     * pool keeps the name it has always shown in {@code claude agents}.
     */
    public synchronized String nextName(String base, int cap) {
        if (cap <= 1) {
            return base;
        }
        for (int i = 1; ; i++) {
            String name = base + "-" + i;
            if (!slots.containsKey(name)) {
                return name;
            }
        }
    }

    /**
     * <b>Which slots are deaf</b> — booted, past the boot grace, and neither parked on
     * {@code await_wish} nor holding a wish, continuously for {@link PoolPlan#DEAF_STOP_MS}.
     *
     * <p>Called once per supervisor tick; it keeps each slot's clock, so the answer is about a
     * persisting absence and never one tick's snapshot. A slot that is accounted for, even once,
     * starts again from zero: a worker between two {@code await_wish} calls is unaccounted for a
     * second or two on every lap, and that must never add up to a stop.
     *
     * @param accountedMcpIds MCP session ids that are parked or hold a wish right now
     */
    public synchronized List<String> deaf(Set<String> accountedMcpIds, long nowMs) {
        List<String> out = new ArrayList<>();
        for (Slot s : slots.values()) {
            // DEAF FROM BIRTH: it connected, was sent its tools and never called one. A worker's
            // first action is a tool call (await_wish), measured 1.5 s after its tool list; one whose
            // first turn was built before the tools arrived ends that turn with no tools and never
            // speaks again. Nothing to wait 180 s for — it is replaced now.
            if (s.listedAtMs > 0 && !s.calledTool && nowMs - s.listedAtMs >= PoolPlan.LISTED_SILENT_MS) {
                out.add(s.name);
                continue;
            }
            boolean accounted = s.mcpId != null && accountedMcpIds.contains(s.mcpId);
            if (accounted || nowMs - s.bootedAtMs < PoolPlan.BOOT_GRACE_MS) {
                s.unaccountedSinceMs = 0;
                continue;
            }
            if (s.unaccountedSinceMs == 0) {
                s.unaccountedSinceMs = nowMs;
            }
            if (nowMs - s.unaccountedSinceMs >= PoolPlan.deafStopMs()) {
                out.add(s.name);
            }
        }
        return out;
    }

    /**
     * <b>Keep each slot's idle clock</b>: every slot whose MCP session holds a wish right now
     * is stamped busy at {@code nowMs}, so its {@link Slot#idleSinceMs()} is "the last tick it was
     * seen busy". Called once per supervisor tick, before {@link PoolPlan#shrinkCandidate}.
     *
     * <p>Sampled, not evented, on purpose: the marshal's assign and finish happen on MCP handler
     * threads and the wish-submission path, where nothing may block; a 3 s sampling error on a
     * two-minute grace is noise.
     *
     * @param busyMcpIds MCP session ids that hold a wish (working or winding down)
     */
    public synchronized void idleClock(Set<String> busyMcpIds, long nowMs) {
        for (Slot s : slots.values()) {
            if (s.mcpId != null && busyMcpIds.contains(s.mcpId)) {
                s.lastBusyMs = nowMs;
            }
        }
    }

    /** Names only, for logs. */
    public static List<String> names(Collection<Slot> slots) {
        return slots.stream().map(Slot::name).toList();
    }
}
