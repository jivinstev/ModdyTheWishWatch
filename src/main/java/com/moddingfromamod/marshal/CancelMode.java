package com.moddingfromamod.marshal;

/**
 * The three ways a child stops a wish — and we always say which one he got.
 *
 * <p>The order matters as a design statement. {@link #REDIRECT} is first because it is the outcome a
 * wrong "make it bigger" actually wants: the child does not want the work cancelled, he wants it
 * applied to the <i>other</i> thing. A bare ✕ throws away work he was happy with.
 *
 * <p>{@link #UNDO} is offered <b>only when it is genuinely available</b> — every tool the wish used
 * had an inverse. A child forgives "I stopped!"; he does not forgive an undo button that lied.
 */
public enum CancelMode {

    /** "No — the other one." Re-aims the work and keeps it. */
    REDIRECT("🔄", "I'll do the other one instead!"),

    /** "Stop and put it back." Only offered when every tool used had an inverse. */
    UNDO("↩", "I stopped and put it back!"),

    /** "Just stop." Always available. */
    STOP("✋", "I stopped!");

    private final String emoji;
    private final String saidToChild;

    CancelMode(String emoji, String saidToChild) {
        this.emoji = emoji;
        this.saidToChild = saidToChild;
    }

    public String emoji() {
        return emoji;
    }

    /** What the child is told. Read-aloud short, and it names the outcome he actually got. */
    public String saidToChild() {
        return emoji + " " + saidToChild;
    }
}
