package com.moddingfromamod.wish;

/** Where a wish is in its life. */
public enum WishStatus {
    /** Waiting to be fulfilled (by the template engine now, or by an agent later). */
    PENDING,
    /** Being worked on right now. */
    RUNNING,
    /** Fulfilled. */
    DONE,
    /** Tried and couldn't. {@code result} says why, in kid-friendly words. */
    FAILED,
    /** Refused before doing anything (rate limit, empty text, unsafe). */
    REJECTED,
    /**
     * The child stopped it.
     *
     * <p>Its own status rather than folded into {@link #REJECTED}: "he asked us not to" and "we
     * would not" are different facts, and the improvement loop mines this file to answer <i>what
     * does he keep asking for that we do badly</i>. Counting a stop as a refusal would make the mod
     * look like it declines work it in fact started.
     */
    STOPPED,
    /**
     * Nobody ever picked it up, or the work ran past its ceiling.
     *
     * <p>Its own status rather than {@link #FAILED}, for the reason {@link #STOPPED} is not
     * {@link #REJECTED}: "we tried and could not" and "nothing ever tried" are different facts, and
     * the improvement loop mines this file to answer <i>what do we do badly</i>. Counting a timeout
     * as a failure would attribute an agent outage — an expired login, a rate limit, a pool that
     * never started — to the quality of the wish.
     *
     * <p>Before this existed a wish nobody claimed simply never settled: the bar stayed up, the
     * journal kept it {@code PENDING} forever, and the child watched a progress bar that had
     * nothing behind it.
     */
    TIMED_OUT;

    public static WishStatus parse(String s) {
        for (WishStatus v : values()) {
            if (v.name().equalsIgnoreCase(s)) {
                return v;
            }
        }
        return PENDING;
    }

    public boolean isFinished() {
        return this == DONE || this == FAILED || this == REJECTED || this == STOPPED
                || this == TIMED_OUT;
    }
}
