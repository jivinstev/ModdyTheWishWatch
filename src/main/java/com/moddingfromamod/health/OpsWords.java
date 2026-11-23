package com.moddingfromamod.health;

/**
 * <b>Every word a child hears from the OPERATIONS layer, in one place.</b>
 *
 * <p>Deliberately a sibling of {@link com.moddingfromamod.safety.SafetyWords} rather than more
 * entries inside it: that file is "every word a child hears from the <i>safety</i> layer", and a
 * maintenance window is not a judgement about a wish. Pooling the two would make the one number
 * that file exists to produce — how often a child is refused for what he said — unreadable the
 * first time somebody deployed a jar during play.
 *
 * <p>The rules are the same rules, and {@code OpsWordsTest} asserts them exactly as
 * {@code SafetyWordsTest} does: never blame him, never explain the category, always end pointing at
 * something he can do now, short enough to read at a glance.
 *
 * <p>Both of these are "the world is busy for a moment" messages, so both borrow the register the
 * rate limiter already established ({@code WishFeedback.tooFast}, "😴 Moddy needs a little rest!").
 * A child who cannot hear the difference between "not now" and "not ever" learns the wrong lesson
 * from a deploy.
 */
public final class OpsWords {

    private OpsWords() {
    }

    /**
     * A grown-up is servicing the server (plan §4): new wishes are refused, wishes already
     * being granted carry on.
     */
    public static final String DRAINING =
            "🧹 Moddy is tidying up for a minute — try your wish again soon!";

    /**
     * Nobody ever picked this wish up, or the work ran past its ceiling (plan §6).
     *
     * <p>The one message here that follows a <b>failure</b> rather than a decision, and the reason
     * it is worded as sleep rather than as an error is that the child has been watching a progress
     * bar for two minutes: the honest content is "that one is not coming", and the only thing worth
     * adding is that the next one might.
     */
    public static final String TIMED_OUT =
            "😴 Moddy fell asleep on that one — try it again in a bit!";

    /**
     * Moddy is tired (D8): the grader cannot be reached, the agent is not answering, or the
     * spend ceiling is reached — so no NEW wish starts, and cached wishes still work.
     *
     * <p>Honest about all three at once, which is why it does not say "a moment": over quota can
     * be hours. What it can promise truthfully is the part that still works — the watch's buttons —
     * so it names that rather than only saying no.
     *
     * <p>⚠ <b>SHORT because it lands on the ACTION BAR</b>, which is one centred line with no wrap.
     * The first wording (109 characters) passed every assertion and was photographed by Gate C cut
     * off at BOTH ends — "…ired and resting … try a ne" — so the child read neither who was tired
     * nor what to do. {@code TiredClientTest} now measures the rendered width with the client's
     * own font against the GUI-scaled screen, which is the only instrument that can see this.
     */
    public static final String TIRED =
            "😴 Moddy is tired — no new wishes for now. Your buttons still work!";

    /** @see #TIRED */
    public static String sayTired() {
        return TIRED;
    }

    /**
     * The tired line for THIS tiredness: the ordinary {@link #TIRED} for every cause that
     * does not know its end, and {@link #tiredForToday} for the daily spend cap, which does — the
     * day's budget refills at the UTC day boundary, so the child can be told when to come back
     * rather than "for now" (which, at 7 pm, would be read as "in a minute" and retried all evening).
     */
    public static String sayTired(ModdyTired.Tired tired, long nowMs) {
        if (tired != null && tired.cause() == ModdyTired.Cause.SPEND_CAP && tired.untilMs() > nowMs) {
            return tiredForToday(tired.untilMs() - nowMs);
        }
        return TIRED;
    }

    /**
     * The daily spend cap's line (D18). <b>RELATIVE time, never a clock time</b>: the cap
     * resets at UTC midnight, and the server does not know the child's time zone — "back at 5 pm"
     * would be wrong for most of them; "in 5 hours" is right for all. Rounded UP, so a child who
     * comes back when told finds Moddy awake rather than ten minutes short.
     *
     * <p>⚠ Not "your wishes refill tomorrow": on a world with a WISH BANK his bank refills every half
     * hour and may well be full — the thing that is out is Moddy's day, not his wishes. And no
     * dollars, no "budget": that is the operator's vocabulary, never a child's.
     *
     * <p>SHORT for {@link #TIRED}'s reason — the action bar does not wrap; {@code TiredClientTest}
     * measures the longest form ("24 hours") against the screen. That is why it is not "tired for
     * today": the hours already say it, and those ten characters took it eleven past TIRED's width.
     */
    public static String tiredForToday(long msLeft) {
        return "😴 Moddy is tired — new wishes " + inHowLong(msLeft) + ". Your buttons still work!";
    }

    /** "in 5 hours" / "in an hour" / "in 20 minutes" / "in a minute" — rounded up. Pure. */
    static String inHowLong(long msLeft) {
        long ms = Math.max(0L, msLeft);
        long minutes = Math.max(1L, (ms + 59_999L) / 60_000L);
        if (minutes > 60) {
            return "in " + ((minutes + 59) / 60) + " hours";
        }
        if (minutes >= 45) {
            return "in an hour";
        }
        return minutes == 1 ? "in a minute" : "in " + minutes + " minutes";
    }

    /** @see #DRAINING */
    public static String sayDraining() {
        return DRAINING;
    }

    /** @see #TIMED_OUT */
    public static String sayTimedOut() {
        return TIMED_OUT;
    }

    /**
     * Every child-facing string here, for the test that checks the whole set at once.
     *
     * <p>Listed rather than reflected, for {@code SafetyWords.all()}'s reason: adding a message and
     * forgetting the rules should be a compile-visible omission in one place.
     */
    public static java.util.List<String> all() {
        // The spend cap's line at its longest ("24 hours") and its minute form.
        return java.util.List.of(DRAINING, TIMED_OUT, TIRED, tiredForToday(24 * 3_600_000L),
                tiredForToday(44 * 60_000L));
    }
}
