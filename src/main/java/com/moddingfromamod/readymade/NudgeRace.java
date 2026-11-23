package com.moddingfromamod.readymade;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * <b>Did the nudge land before the worker committed?</b>
 *
 * <p>The nudge never holds a wish: it is asked at the hand-over and rides the next reply. That is a
 * race against the worker's FIRST BUILD call, and the design chose not to hold the build on the
 * strength of a measurement — in real grants the first build starts 10–35 s after the hand-over and
 * the nudge lands in ~6 s, so it loses ~1% of the time. This records every race so a reader can
 * show whether that stays true: a {@code won} race and how far ahead, or a {@code lost} one and by
 * how long, with whether the nudge had picked anything (a lost race with nothing picked cost
 * nothing).
 *
 * <p>Pure, clock passed in; {@link Outcome} is what gets logged. One per wish, at most.
 */
public final class NudgeRace {

    /** The calls that COMMIT a wish to a way of being granted — routing-arm's ACTS, plus the ready-mades. */
    public static final Set<String> BUILDS = Set.of(
            "sculpt_creature", "define_creature", "define_species", "modify_mob", "paint_creature",
            "build_structure", "make_item", "modify_player", "change_drops", "set_rule", "paint_surface",
            "stage_event", "run_command", "author_datapack", "apply_effect");

    /** A decided race: {@code won} with {@code ms} the nudge's lead, or lost with {@code ms} its lag. */
    public record Outcome(String wishId, boolean won, long ms, boolean picked) {
    }

    private static final NudgeRace GLOBAL = new NudgeRace();

    public static NudgeRace global() {
        return GLOBAL;
    }

    public static NudgeRace forTest() {
        return new NudgeRace();
    }

    private static final class State {
        Long landedAt;
        boolean picked;
        Long firstBuildAt;
        boolean decided;
    }

    private final Map<String, State> races = new HashMap<>();

    /** A nudge for this wish has started. */
    public synchronized void started(String wishId) {
        if (wishId != null) {
            races.put(wishId, new State());
        }
    }

    /** The nudge landed at {@code now}. A lost race is decided here, by how long it lagged. */
    public synchronized Outcome landed(String wishId, long now, boolean picked) {
        State s = wishId == null ? null : races.get(wishId);
        if (s == null || s.landedAt != null) {
            return null;
        }
        s.landedAt = now;
        s.picked = picked;
        if (s.firstBuildAt != null && !s.decided) {
            s.decided = true;
            return new Outcome(wishId, false, now - s.firstBuildAt, picked);
        }
        return null;
    }

    /**
     * A call RAN, having begun at {@code now}; only the FIRST build call of a raced wish decides
     * anything. A refused call is never reported, because it committed to nothing.
     */
    public synchronized Outcome called(String wishId, String tool, long now) {
        State s = wishId == null ? null : races.get(wishId);
        if (s == null || s.firstBuildAt != null || tool == null || !BUILDS.contains(tool)) {
            return null;
        }
        s.firstBuildAt = now;
        if (s.landedAt != null) {
            // `now` is when the build call BEGAN, and it is reported once it has run — so the nudge can
            // have landed while the call was running. A call that began first never saw the note.
            s.decided = true;
            return now >= s.landedAt ? new Outcome(wishId, true, now - s.landedAt, s.picked)
                    : new Outcome(wishId, false, s.landedAt - now, s.picked);
        }
        return null;     // still in flight: landed() decides it, as a loss
    }

    public synchronized void forget(String wishId) {
        if (wishId != null) {
            races.remove(wishId);
        }
    }
}
