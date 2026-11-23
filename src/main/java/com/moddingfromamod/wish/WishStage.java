package com.moddingfromamod.wish;

/**
 * <b>Which line the action bar shows while a wish is granted</b> — the rule, kept pure so
 * Gate A pins it and its timing is tested with a clock rather than in ticks.
 *
 * <p>The bar names the stage the mod has OBSERVED, never a guess from the clock:
 * <ol>
 *   <li>before a worker has picked the wish up — {@link WishWaitWords#READING}, or
 *       {@link WishWaitWords#lookingUp} while the character it names is being looked up;</li>
 *   <li>picked up, no plan — {@link WishWaitWords#WORKING_OUT};</li>
 *   <li>planned — the plan's current step;</li>
 *   <li>the agent's own line or {@code ToolNarration}'s, when it is newer than the stage.</li>
 * </ol>
 *
 * <p>The two "still" lines key off the <b>last real event</b> (submit, claim, plan, a step, a tool
 * call, a status), not the time since the wish — so a long but busy wish keeps showing its real
 * steps, and only a quiet one says it is still going. The timer tiers this replaced said
 * "this one is BIG" at 8 s for every wish.
 */
public final class WishStage {

    private WishStage() {
    }

    /** Nothing new for this long: {@link WishWaitWords#STILL_WORKING}. */
    public static final long STILL_WORKING_MS = 30_000;

    /** Nothing new for this long: {@link WishWaitWords#STILL_GOING}. */
    public static final long STILL_GOING_MS = 60_000;

    /**
     * One wish's observed moments, in epoch ms (0 = not yet). Written from the server thread, a
     * worker's dispatch thread and the narrator's, so each field is volatile and each write only
     * ever moves a clock forward.
     */
    public static final class Track {
        private volatile long claimedMs;
        private volatile long plannedMs;
        private volatile long stageMs;
        private volatile long lastEventMs;
        private volatile String lookingUp;

        public Track(long submittedMs) {
            this.stageMs = submittedMs;
            this.lastEventMs = submittedMs;
        }

        /** A worker picked it up. Idempotent: the first claim is the stage change. */
        public void claimed(long nowMs) {
            if (claimedMs == 0) {
                claimedMs = nowMs;
                stage(nowMs);
            }
        }

        /**
         * the character this wish names is being looked up before a worker can take it (a cold
         * brief, up to {@code WishBriefs.BRIEF_WAIT_MS}). A stage of its own, so the bar says so.
         */
        public void lookingUp(String name, long nowMs) {
            if (claimedMs == 0 && name != null && !name.isBlank()) {
                lookingUp = name;
                stage(nowMs);
            }
        }

        /** The character being looked up, or null. */
        public String lookingUp() {
            return lookingUp;
        }

        /** A real plan arrived (or a re-plan), or the plan moved on a step. */
        public void planned(long nowMs) {
            if (claimedMs == 0) {
                claimedMs = nowMs;     // a plan is made by somebody who has it
            }
            plannedMs = nowMs;
            stage(nowMs);
        }

        /** Something real happened that does not change the stage (a tool call, a status). */
        public void event(long nowMs) {
            if (nowMs > lastEventMs) {
                lastEventMs = nowMs;
            }
        }

        private void stage(long nowMs) {
            if (nowMs > stageMs) {
                stageMs = nowMs;
            }
            event(nowMs);
        }

        public boolean isClaimed() {
            return claimedMs != 0;
        }

        public boolean isPlanned() {
            return plannedMs != 0;
        }

        public long stageMs() {
            return stageMs;
        }

        public long lastEventMs() {
            return lastEventMs;
        }
    }

    /**
     * What the bar says.
     *
     * @param agent true when {@code words} is the agent's (or the narrator's) own line, which the
     *              bar draws with its phase's magic rather than the spinner alone
     */
    public record Line(String words, boolean agent) {
    }

    /**
     * Choose the line.
     *
     * @param nowMs      the clock
     * @param track      the wish's observed moments; null reads as "just submitted, nothing since"
     * @param planStep   the plan's current step label, or "" when there is no real plan
     * @param agentWords the agent's or narrator's current line, or null
     * @param agentMs    when that line was set
     */
    public static Line choose(long nowMs, Track track, String planStep, String agentWords,
                              long agentMs) {
        boolean said = agentWords != null && !agentWords.isBlank();
        long last = track == null ? nowMs : track.lastEventMs();
        if (said && agentMs > last) {
            last = agentMs;
        }
        long quiet = nowMs - last;
        if (quiet >= STILL_GOING_MS) {
            return new Line(WishWaitWords.STILL_GOING, false);
        }
        if (quiet >= STILL_WORKING_MS) {
            return new Line(WishWaitWords.STILL_WORKING, false);
        }
        long stageAt = track == null ? 0 : track.stageMs();
        if (said && agentMs >= stageAt) {
            return new Line(agentWords.strip(), true);
        }
        boolean planned = track != null && track.isPlanned() && planStep != null && !planStep.isBlank();
        if (planned) {
            return new Line(WishWaitWords.step(planStep), false);
        }
        if (track != null && track.isClaimed()) {
            return new Line(WishWaitWords.WORKING_OUT, false);
        }
        if (track != null && track.lookingUp() != null) {
            return new Line(WishWaitWords.lookingUp(track.lookingUp()), false);
        }
        return new Line(WishWaitWords.READING, false);
    }

    /**
     * Whether "see the sparkles?" is said now: once per wish, only after {@code conjure_at} has
     * marked the spot, and only for a thing that APPEARS in the world — a building, a creature, a
     * picture or an event. Not an item handed over, not a teleport or anything else
     * ({@link WishAim.Kind#OTHER}), and not before the wish has said what it is: a kind not known
     * yet waits for {@code plan_wish} rather than guessing.
     */
    public static boolean saySparkles(boolean conjured, String planKind, boolean alreadySaid) {
        if (!conjured || alreadySaid || planKind == null || planKind.isBlank()) {
            return false;
        }
        return switch (WishAim.Kind.parse(planKind)) {
            case BUILDING, CREATURE, PICTURE, EVENT -> true;
            case ITEM, OTHER -> false;
        };
    }
}
