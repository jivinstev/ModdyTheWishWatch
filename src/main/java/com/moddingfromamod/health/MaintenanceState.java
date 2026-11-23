package com.moddingfromamod.health;

/**
 * <b>Is the server tidying up?</b> The drain flag, and nothing else (plan §4).
 *
 * <p>Minecraft-free on purpose, so the whole of the policy — what draining means, what it does not
 * mean, and when it changed — is under Gate A and can be reasoned about without booting a game.
 * The Minecraft side is two lines: {@code /moddyop drain on|off} writes it and
 * {@link com.moddingfromamod.wish.WishService#submit} reads it.
 *
 * <h2>What draining is, and the two things it deliberately is not</h2>
 *
 * <ul>
 *   <li>It refuses <b>new</b> wishes. A wish already in flight is untouched — the whole point of a
 *       drain is that the queue empties, and cancelling the work would make "drain" a synonym for
 *       "stop", which the launcher already has.</li>
 *   <li>It is <b>not</b> a safety refusal. A child hears an "in a minute" line
 *       ({@link OpsWords#DRAINING}) and the record says {@code DRAINING}, not {@code BLOCKED}: an
 *       operator's maintenance window must never be pooled with a wish that was refused for what
 *       it said.</li>
 *   <li>It is <b>not</b> persisted. A restart is the thing a drain precedes, and a server that came
 *       back up still draining would be a server that silently answers nobody.</li>
 * </ul>
 */
public final class MaintenanceState {

    private static final MaintenanceState GLOBAL = new MaintenanceState();

    private volatile boolean draining;
    private volatile long changedAtMs;
    private volatile String reason = "";

    /** The one the game reads. Tests build their own. */
    public static MaintenanceState global() {
        return GLOBAL;
    }

    /** True while new wishes are being refused. */
    public boolean draining() {
        return draining;
    }

    /** When the flag last moved, in epoch millis; 0 if it never has. */
    public long changedAtMs() {
        return changedAtMs;
    }

    /** Why the operator drained, or {@code ""}. Free text from the operator, never shown to a child. */
    public String reason() {
        return reason;
    }

    /**
     * Turn draining on or off.
     *
     * @return true when this call actually changed something — so a command can say "already on"
     *         rather than reporting a state change that did not happen, and so a repeated RCON
     *         {@code drain on} from a retrying launcher is a visible no-op
     */
    public boolean set(boolean on, long nowMs, String why) {
        if (draining == on) {
            return false;
        }
        draining = on;
        changedAtMs = nowMs;
        reason = on ? (why == null ? "" : why.strip()) : "";
        return true;
    }

    /** @see #set(boolean, long, String) */
    public boolean set(boolean on, long nowMs) {
        return set(on, nowMs, "");
    }

    /**
     * Should this wish be refused right now?
     *
     * <p>A method rather than a bare read of the flag, because every caller has to ask the same
     * question and the answer has already grown one exception's worth of nuance once
     * ({@code in-flight work carries on}). One place to add the next.
     */
    public boolean refusesNewWishes() {
        return draining;
    }

    /** Back to "answering wishes", without stamping a change. For a test's own fixture only. */
    public void resetForTest() {
        draining = false;
        changedAtMs = 0L;
        reason = "";
    }
}
