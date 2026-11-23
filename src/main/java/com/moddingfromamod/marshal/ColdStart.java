package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How long a worker takes to become a worker.
 *
 * <p><b>Why measure it rather than reason about it.</b> Starting the pool when the world opens
 * rather than on the first wish is a bet: that the cost is paid before a child can walk to his
 * watch. The comment claiming it says <i>"start-up costs ~40s (measured)"</i> and was written when
 * there was ONE worker. Now there are up to four, started in a loop, and nobody has checked whether
 * four cost 40 seconds or 160 — which is the difference between the bet holding and a child making
 * his second wish into a pool that is still booting.
 *
 * <p>Pure and Minecraft-free: it is fed two timestamps per worker and answers questions about them,
 * so the arithmetic is unit-tested and the game only has to say <i>when</i>.
 *
 * <p><b>What it deliberately does not do:</b> decide anything. Nothing branches on these numbers
 * yet. Measuring first is the point — a pool sized or pre-warmed against a guess is how the ~40s
 * figure came to be quoted for a case it was never taken in.
 */
public final class ColdStart {

    /** Beyond this a child has finished walking to his watch and the bet has been lost. */
    public static final long COMFORTABLE_MS = 60_000L;

    private final Map<String, Long> readyAtMs = new LinkedHashMap<>();
    private long startedAtMs;

    /**
     * Explicitly, rather than inferring it from {@code startedAtMs > 0}.
     *
     * <p>Zero is a perfectly good timestamp, and a sentinel that collides with a real value reads as
     * "the pool never started" for one of them. The unit tests use a start of 0 and every one of
     * them failed on exactly that.
     */
    private boolean started;

    /** The pool was asked to start. */
    public void poolStarting(long nowMs) {
        this.startedAtMs = nowMs;
        this.started = true;
        this.readyAtMs.clear();
    }

    /**
     * A worker spoke for the first time — which is the only honest definition of ready.
     *
     * <p>Not "the process launched": a launched CLI that has not yet connected, authenticated and
     * parked on {@code await_wish} cannot take a wish, and counting it would measure {@code
     * ProcessBuilder} rather than readiness. First contact through the MCP seam is the moment it
     * could actually be given work.
     */
    public void workerReady(String sessionId, long nowMs) {
        if (sessionId == null || sessionId.isBlank() || !started) {
            return;
        }
        readyAtMs.putIfAbsent(sessionId, nowMs);
    }

    /** How long each worker took, in the order they arrived. */
    public List<Long> waits() {
        List<Long> out = new ArrayList<>();
        for (long at : readyAtMs.values()) {
            out.add(at - startedAtMs);
        }
        return out;
    }

    public int ready() {
        return readyAtMs.size();
    }

    /** How long until the FIRST worker could take a wish — what a single-wish child feels. */
    public long firstReadyMs() {
        List<Long> w = waits();
        return w.isEmpty() ? -1 : w.get(0);
    }

    /** How long until the WHOLE pool was up — what decides whether two wishes can really overlap. */
    public long allReadyMs() {
        List<Long> w = waits();
        return w.isEmpty() ? -1 : w.get(w.size() - 1);
    }

    /**
     * What the extra workers cost over the first one.
     *
     * <p>The number that decides whether the pool should be started in a loop at all. Near zero
     * means they boot in parallel and more slots are free; close to N× the first means they are
     * effectively serialised and a child's second wish waits for a boot rather than for an agent.
     */
    public long extraForTheRestMs() {
        List<Long> w = waits();
        return w.size() < 2 ? 0 : w.get(w.size() - 1) - w.get(0);
    }

    /** True while the bet the design makes still holds. */
    public boolean comfortable() {
        long all = allReadyMs();
        return all >= 0 && all <= COMFORTABLE_MS;
    }

    /** One line for the log, and for a gate to print. */
    public String report() {
        if (readyAtMs.isEmpty()) {
            return "no workers reported ready";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(ready()).append(" worker(s) ready: first after ")
                .append(firstReadyMs() / 1000).append("s, all after ")
                .append(allReadyMs() / 1000).append("s");
        if (ready() > 1) {
            sb.append(" (the rest cost ").append(extraForTheRestMs() / 1000).append("s more)");
        }
        return sb.toString();
    }
}
