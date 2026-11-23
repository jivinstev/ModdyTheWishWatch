package com.moddingfromamod.rules;

/**
 * <b>When a timed rule is due</b>: sunrise and sunset from the day clock, the rain starting and
 * stopping, and "every N seconds". Pure, so each edge is a unit test; {@link WorldRules} asks it
 * once a tick.
 *
 * <p>Edges, not levels: "the sun comes up" happens once a morning, not on every tick of the day.
 * An edge is a CROSSING between the last tick looked at and this one, so a {@code /time set} that
 * jumps over sunrise still counts once, and a server that skipped ticks still counts once.
 */
public final class RuleClock {

    private RuleClock() {
    }

    /** Ticks in a Minecraft day. */
    public static final long DAY = 24_000L;
    /** Day time at which the sun comes up (vanilla's "day" is 1000; 0 is dawn). */
    public static final long SUNRISE = 0L;
    /** Day time at which the sun goes down (vanilla's "night" starts at 13000; 12000 is dusk). */
    public static final long SUNSET = 12_000L;

    /**
     * Did the day clock cross {@code mark} (a time of day, 0..23999) going from {@code before} to
     * {@code now}? Both are absolute day times, as {@code Level.getDayTime()} returns them. A clock
     * that went BACKWARDS ({@code /time set} to earlier) crossed nothing: the child did that
     * himself, and a sunrise rule firing because he typed a command is the wrong surprise.
     */
    public static boolean crossed(long before, long now, long mark) {
        if (before < 0 || now <= before) {
            return false;
        }
        if (now - before >= DAY) {
            return true;
        }
        long first = Math.floorDiv(before - mark, DAY) * DAY + mark;
        if (first <= before) {
            first += DAY;
        }
        return first <= now;
    }

    /** Is "every {@code seconds} seconds" due on this server tick? 20 ticks to a second. */
    public static boolean due(long tick, int seconds) {
        long period = Math.max(1, seconds) * 20L;
        return tick % period == 0;
    }
}
