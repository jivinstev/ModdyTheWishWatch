package com.moddingfromamod.place;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>How long, and how wide, an action that fell back to the world spawn keeps that spot loaded.</b>
 * The pure half of {@link SpawnHold}: no Minecraft here, so every rule below is a Gate A test.
 *
 * <h2>Why it exists</h2>
 *
 * <p>Minecraft 1.21.9 removed spawn chunks: the world spawn is no longer always loaded. With nobody
 * near, a wish's tools still act AT the spawn (or at a build's fallback anchor scattered around it):
 * a summon there succeeds and the creature is in a chunk nothing keeps loaded, so no selector, handle
 * or lookup finds it, and it goes to disk on the next chunk tick. A block still lands, because
 * {@code setBlock} loads its chunk; an entity is not given that.
 *
 * <p>So the action that takes the spawn fallback holds a short chunk ticket around the spot, and lets
 * go of it afterwards. Never the spawn kept loaded for good, and never spawn chunks recreated: a hold
 * belongs to one action, and every hold ends.
 *
 * <h2>The numbers</h2>
 *
 * <ul>
 *   <li><b>Width</b>: {@link #LOOKUP_BLOCKS} is the band the action's own follow-up searches
 *       ({@code EntityCapture} and {@code WishProps} both diff 48 blocks round the origin), so the
 *       ticket keeps every chunk that band can touch accessible: {@link #radiusChunks()} = 3, a 7x7
 *       square. The 3x3 square in the middle ({@link #readyRing()}) is entity-ticking, and that is
 *       what the action waits for before it runs.</li>
 *   <li><b>Tail</b>: {@link #AGENT_TAIL_MS}, 60 s of real time after the action ends. The follow-up
 *       inside the tool (the handle diff, the prop record, a build's seated creature) runs while the
 *       hold is still open; the tail is for the agent's NEXT call about the same thing — a
 *       {@code modify_mob} on the handle it was just given, an {@code execute if entity} check,
 *       {@code finish_wish}'s prop sweep — which comes one model turn later, a few seconds to most
 *       of a minute. Real time, not ticks, because a model turn is real time and a GameTest server
 *       ticks as fast as it can. Each new spawn action refreshes it, so a wish worked on at the spawn
 *       keeps its creatures in reach while the agent works, and one minute after its last action
 *       the spot is let go.</li>
 *   <li><b>No follow-up</b>: a rule firing at the spawn with nobody online has nobody to look
 *       ({@link Followup#NONE}); it is let go at the first tick after it ends, so what it made is
 *       saved to disk the ordinary way instead of being left in a chunk nothing loaded.</li>
 *   <li><b>A ceiling</b>: {@link #MAX_OPEN_MS}. A hold whose close never comes (a build cancelled
 *       with its callbacks, a check that hangs) still ends: fifteen minutes is longer than the
 *       Magician check's eight-minute wish and any build, and is not "loaded for good".</li>
 * </ul>
 */
public final class SpawnHoldPlan {

    /** Who will look for what the action made, after it ends. */
    public enum Followup {
        /** The agent's next call: hold for {@link #AGENT_TAIL_MS} after the action. */
        AGENT,
        /** Nobody: let go at the first tick after the action. */
        NONE
    }

    /** The band, in blocks round the origin, the action's own lookups search. */
    public static final int LOOKUP_BLOCKS = 48;

    /** Blocks per chunk side. */
    static final int CHUNK = 16;

    /** Real time an AGENT hold is kept after its action ends. */
    public static final long AGENT_TAIL_MS = 60_000L;

    /** The longest any hold is kept open, closed or not. */
    public static final long MAX_OPEN_MS = 15 * 60_000L;

    /**
     * How long an action waits, at most, for the middle of the hold to be entity-ticking before it
     * runs anyway. Inside the agent's five-second hop to the server, with room to answer; a spawn
     * that already exists on disk loads in well under it.
     */
    public static final long READY_WAIT_MS = 3_000L;

    private SpawnHoldPlan() {
    }

    /**
     * Chunks out from the centre the ticket keeps accessible. Any block within
     * {@link #LOOKUP_BLOCKS} of any point in the centre chunk lies at most this many chunks out.
     */
    public static int radiusChunks() {
        return Math.ceilDiv(LOOKUP_BLOCKS, CHUNK);
    }

    /**
     * Chunks out from the centre that are entity-ticking under that ticket (a ticket's level falls
     * one per chunk: entity-ticking, then block-ticking, then only accessible), and so the ring the
     * action waits for before a summon there can be found.
     */
    public static int readyRing() {
        return Math.max(0, radiusChunks() - 2);
    }

    /** The chunk a block coordinate is in. */
    public static int chunkOf(int block) {
        return Math.floorDiv(block, CHUNK);
    }

    /** Every chunk within {@code ring} of the centre, centre first. */
    public static List<int[]> chunksAround(int cx, int cz, int ring) {
        List<int[]> out = new ArrayList<>();
        out.add(new int[] {cx, cz});
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                if (dx != 0 || dz != 0) {
                    out.add(new int[] {cx + dx, cz + dz});
                }
            }
        }
        return out;
    }

    /** Real time kept after an action ends. */
    public static long tailMs(Followup f) {
        return f == Followup.AGENT ? AGENT_TAIL_MS : 0L;
    }

    /** One held spot: a dimension and the chunk at the middle of the hold. */
    public record Key(String dimension, int cx, int cz) {
    }

    /** What the ticket side does this tick: refresh these, release these. */
    public record Step(List<Key> keep, List<Key> release) {
    }

    /** The holds now open or in their tail, and the spots a ticket is on. Thread-safe. */
    public static final class Book {

        private record Hold(Key key, long openedMs, long releaseAtMs, boolean closed) {
        }

        private final Map<Long, Hold> holds = new HashMap<>();
        private final Set<Key> ticketed = new LinkedHashSet<>();
        private long next = 1;

        /** A hold opens at {@code key}; returns its token. The caller puts the ticket on at once. */
        public synchronized long open(Key key, long nowMs) {
            long token = next++;
            holds.put(token, new Hold(key, nowMs, Long.MAX_VALUE, false));
            ticketed.add(key);
            return token;
        }

        /** The action behind {@code token} ended: its tail starts now. A second close is ignored. */
        public synchronized void close(long token, long nowMs, Followup followup) {
            Hold h = holds.get(token);
            if (h != null && !h.closed()) {
                holds.put(token, new Hold(h.key(), h.openedMs(), nowMs + tailMs(followup), true));
            }
        }

        /**
         * Once a tick: which spots still have a hold (refresh their ticket) and which have just lost
         * their last one (take it off). A hold counts while open (up to {@link #MAX_OPEN_MS}) and,
         * once closed, until its tail has run.
         */
        public synchronized Step step(long nowMs) {
            Set<Key> live = new LinkedHashSet<>();
            holds.values().removeIf(h -> !isLive(h, nowMs));
            for (Hold h : holds.values()) {
                live.add(h.key());
            }
            List<Key> release = new ArrayList<>();
            for (Key k : ticketed) {
                if (!live.contains(k)) {
                    release.add(k);
                }
            }
            ticketed.clear();
            ticketed.addAll(live);
            return new Step(List.copyOf(live), List.copyOf(release));
        }

        private static boolean isLive(Hold h, long nowMs) {
            if (nowMs - h.openedMs() >= MAX_OPEN_MS) {
                return false;
            }
            return !h.closed() || nowMs < h.releaseAtMs();
        }

        /** Spots a ticket is on now. */
        public synchronized Set<Key> ticketed() {
            return Set.copyOf(ticketed);
        }

        /** Everything forgotten (the server stopped: its tickets went with it). */
        public synchronized void clear() {
            holds.clear();
            ticketed.clear();
        }
    }
}
