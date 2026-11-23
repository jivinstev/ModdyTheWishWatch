package com.moddingfromamod.marshal;

/**
 * Where one wish has got to.
 *
 * <p>Note what is <b>not</b> here: any notion of "queued behind". A child never sees a queue, so the
 * marshal does not model one as a state a wish is in — {@link #GETTING_READY} is what a wish looks
 * like before a worker takes it, and from the outside it is indistinguishable from thinking. The
 * beam and the bar are already up.
 */
public enum RunState {

    /** Admitted, acknowledged, sparkles up — but no worker has taken it yet. */
    GETTING_READY,

    /** A worker holds it and is granting it. */
    WORKING,

    /** Granted. */
    DONE,

    /** The child stopped it. */
    STOPPED;

    public boolean isFinished() {
        return this == DONE || this == STOPPED;
    }

    public boolean isLive() {
        return !isFinished();
    }
}
