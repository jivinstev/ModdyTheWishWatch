package com.moddingfromamod.datapack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import com.moddingfromamod.item.delivery.DeliveryPlan.Kind;

/**
 * <b>A press that arrived before its pack had loaded, kept until the pack has.</b>
 *
 * <p>{@code wishdatapack <id> <entry>} on a pack that is not loaded yet installs it and starts an
 * asynchronous reload, so the entry cannot run that tick. It used to answer "Loaded — do that
 * again!" and return success, and nothing ever pressed again: a starter button answered
 * <i>"Here are your Tiny Cookies!"</i> and left his hands empty (4 runs of 4 in an earlier pass,
 * wherever the world had not preinstalled its packs — a pack the library poll brings mid-session,
 * a first boot, every Gate D world). Now the press waits here and runs once the pack answers.
 *
 * <p>Minecraft-free, so the decision (run it, give up on it, keep waiting) is a unit test; the
 * tick that executes it is {@link PendingPressTicker}.
 */
public final class PendingPresses {

    /** A resource reload is 10–30 s on a modded instance (DatapackReloader); a minute covers the tail. */
    public static final long DEADLINE_MS = 60_000L;

    /**
     * One waiting press: whose, which pack, which entry, when to give up, and the scope that
     * pressed — its wish, and whether its give is a hand-over or a thing's own behaviour.
     */
    public record Press(UUID player, String id, String entry, long deadlineMs, String wishKey, Kind kind) {

        public Press(UUID player, String id, String entry, long deadlineMs) {
            this(player, id, entry, deadlineMs, "", Kind.HANDOVER);
        }

        public Press(UUID player, String id, String entry, long deadlineMs, String wishKey) {
            this(player, id, entry, deadlineMs, wishKey, Kind.HANDOVER);
        }
    }

    /** What a tick should do: run these, and tell these they took too long. */
    public record Due(List<Press> ready, List<Press> expired) {
    }

    private final List<Press> waiting = new ArrayList<>();

    public synchronized void add(UUID player, String id, String entry, long nowMs) {
        add(player, id, entry, nowMs, "");
    }

    /**
     * The same, remembering the wish that pressed: the give runs ticks later, after the
     * pack loads, when no wish is in scope — and an undo must still know whose items they were.
     */
    public synchronized void add(UUID player, String id, String entry, long nowMs, String wishKey) {
        add(player, id, entry, nowMs, wishKey, Kind.HANDOVER);
    }

    /** The same, remembering whether the press was a hand-over or a thing's own behaviour. */
    public synchronized void add(UUID player, String id, String entry, long nowMs, String wishKey, Kind kind) {
        // The same press twice (a child pressing again while it loads) is ONE press: he asked
        // for one cookie, not two when the pack arrives.
        waiting.removeIf(p -> p.player().equals(player) && p.id().equals(id) && p.entry().equals(entry));
        waiting.add(new Press(player, id, entry, nowMs + DEADLINE_MS, wishKey == null ? "" : wishKey,
                kind == null ? Kind.HANDOVER : kind));
    }

    public synchronized boolean isEmpty() {
        return waiting.isEmpty();
    }

    /**
     * Take every press whose pack has loaded (ready) or whose deadline has passed (expired); the
     * rest keep waiting. A loaded pack wins over a passed deadline: late is better than nothing.
     */
    public synchronized Due take(Predicate<String> loaded, long nowMs) {
        List<Press> ready = new ArrayList<>();
        List<Press> expired = new ArrayList<>();
        waiting.removeIf(p -> {
            if (loaded.test(p.id())) {
                ready.add(p);
                return true;
            }
            if (nowMs >= p.deadlineMs()) {
                expired.add(p);
                return true;
            }
            return false;
        });
        return new Due(ready, expired);
    }
}
