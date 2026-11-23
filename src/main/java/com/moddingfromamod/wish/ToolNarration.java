package com.moddingfromamod.wish;

import java.util.List;
import java.util.Locale;

/**
 * <b>What to say to a child while the model is thinking.</b>
 *
 * <h2>The dead air</h2>
 *
 * <p>Every {@code set_status} call costs a <b>full model round trip</b> — about 4 seconds — because
 * choosing the words requires an inference. The tool itself executes in 0–8ms; the mod is never the
 * bottleneck. Measured over one play session that was <b>12 turns, 48 seconds, 12.1% of all
 * latency</b>, second only to {@code author_datapack}.
 *
 * <p>Worse, the remaining <b>67%</b> is authoring turns — {@code author_datapack} 39.7%,
 * {@code plan_wish} 12.8%, {@code sculpt_creature} 10.5% — and during those the model is <i>mid
 * inference</i> and physically cannot send a status. That window is where the disconnected feeling
 * comes from, and it is a thing the agent is <b>structurally incapable</b> of fixing. Only the mod
 * can talk there.
 *
 * <h2>Not fewer words. Fewer TURNS.</h2>
 *
 * <p>Verbosity was deliberately turned up, and stays up: a line like "Measuring his mouth" sitting
 * on screen for 52 seconds is the bug, not the cure. Two changes, neither of which costs an
 * inference:
 *
 * <ol>
 *   <li><b>{@link #rideAlong}</b> — the slow tools take a {@code status} string, so the agent says
 *       what it is about to do <i>in the same turn as doing it</i>. Its own words, wish-specific,
 *       zero extra round trips.</li>
 *   <li><b>{@link #lineFor}</b> — the mod's own lines, played while the call is in flight. No
 *       inference at all: the mod knows which tool is running and for how long.</li>
 * </ol>
 *
 * <h2>The honesty rule</h2>
 *
 * <p>These lines must never claim <b>progress the mod cannot observe</b> (guardrail L1). The mod
 * knows the tool and the elapsed time and nothing else, so every line is a statement about what is
 * <i>happening</i> — never a percentage, never "almost done", never a step count. When something is
 * taking a long time the honest line says exactly that.
 *
 * <p>And the mod only speaks once the agent's own line has gone <b>stale</b>. The agent's words are
 * always better than a generic one, so a generic line that talks over a fresh, specific one is a
 * downgrade wearing the clothes of an improvement.
 *
 * <p>Pure and Minecraft-free.
 */
public final class ToolNarration {

    private ToolNarration() {
    }

    /**
     * How long the agent's own line is allowed to stand before the mod fills in.
     *
     * <p>Roughly one model turn: below this the agent may well be about to speak for itself, and
     * talking over it swaps a specific line for a generic one.
     */
    public static final long AGENT_LINE_FRESH_MS = 6000;

    /** One thing to say, and which magic icon it belongs to. */
    public record Line(String doing, String phase) {
    }

    private record Beat(long afterMs, String doing, String phase) {
    }

    /**
     * The lines for one kind of work, in order. Deliberately a small hand-written table rather than
     * anything generated: these are the words a seven-year-old reads, and there are six of them.
     */
    private static final List<Beat> AUTHORING = List.of(
            new Beat(2_500, "Writing the magic words…", "building"),
            new Beat(12_000, "Still writing — this spell has a lot of parts", "building"),
            new Beat(25_000, "Making sure the spell actually works…", "checking"),
            new Beat(45_000, "This one's a big spell — still going!", "building"));

    private static final List<Beat> SCULPTING = List.of(
            new Beat(2_500, "Shaping it out of blocks…", "building"),
            new Beat(12_000, "Still sculpting — getting the shape right", "building"),
            new Beat(25_000, "Adding the little details…", "building"),
            new Beat(45_000, "Nearly there — this one has a LOT of pieces!", "building"));

    private static final List<Beat> PLANNING = List.of(
            new Beat(2_500, "Thinking about how to make this…", "dreaming"),
            new Beat(12_000, "Still dreaming it up…", "dreaming"),
            new Beat(25_000, "This is a big idea — working out the pieces", "dreaming"));

    private static final List<Beat> BUILDING = List.of(
            new Beat(2_500, "Building it, block by block…", "building"),
            new Beat(12_000, "Still building — this one is big", "building"),
            new Beat(25_000, "Putting the last blocks in…", "building"));

    private static final List<Beat> LOOKING = List.of(
            new Beat(4_000, "Having a good look at it…", "checking"),
            new Beat(15_000, "Still looking — checking every side", "checking"));

    /**
     * Which table a tool belongs to, or null when it is not worth narrating.
     *
     * <p>Most tools return in single-digit milliseconds. A line for those would flicker on and off
     * faster than a child can read it, which is noise rather than reassurance.
     */
    private static List<Beat> beatsFor(String tool) {
        String t = tool == null ? "" : tool.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "author_datapack" -> AUTHORING;
            case "sculpt_creature", "paint_creature", "define_species" -> SCULPTING;
            case "plan_wish" -> PLANNING;
            case "build_structure" -> BUILDING;
            // NOT finish_building: it ENDS the wish for the child, sets its own
            // own end (its bar comes down at "All done"), and a "Still looking —
            // checking every side" after "All done" told him the opposite (filmed, first e2e).
            case "photograph" -> LOOKING;
            default -> null;
        };
    }

    /** Is this a tool slow enough that the child would otherwise be watching nothing happen? */
    public static boolean narratable(String tool) {
        return beatsFor(tool) != null;
    }

    /**
     * What to show for a tool that has been running this long, or {@code null} for "say nothing".
     *
     * @param elapsedMs        how long this tool call has been running
     * @param agentLineAgeMs   how long ago the AGENT last set the status itself; the mod stays
     *                         quiet while that is still fresh, because a specific line beats a
     *                         generic one every time
     */
    public static Line lineFor(String tool, long elapsedMs, long agentLineAgeMs) {
        List<Beat> beats = beatsFor(tool);
        if (beats == null || agentLineAgeMs < AGENT_LINE_FRESH_MS) {
            return null;
        }
        Beat chosen = null;
        for (Beat b : beats) {
            if (elapsedMs >= b.afterMs()) {
                chosen = b;
            }
        }
        return chosen == null ? null : new Line(chosen.doing(), chosen.phase());
    }

    /**
     * The line the agent attached to the work itself, or {@code null}.
     *
     * <p>This is the half that costs nothing and reads best: the agent's own wish-specific words,
     * delivered in the same turn as the work rather than in a turn of their own.
     */
    public static String rideAlong(String status) {
        if (status == null) {
            return null;
        }
        String s = status.trim();
        if (s.isEmpty()) {
            return null;
        }
        // A status line is one glanceable sentence on a child's screen, not a paragraph.
        return s.length() > 120 ? s.substring(0, 117) + "…" : s;
    }
}
