package com.moddingfromamod.event.design;

/**
 * <b>"Every N days", on a clock that only goes forward</b> (E4; after an earlier mod's disaster clock).
 * Pure.
 *
 * <p>Keyed on DAY TIME, not server ticks, so flipping {@code /time set day} and {@code night} does
 * hurry it up — twenty flips is ten days, and a player found that and loved it. But time only
 * ever counts FORWARD: {@code /time set 0} banks nothing, or rewinding the clock would be an endless
 * disaster machine. And the first look after a load only re-bases, so a world left shut for a week
 * does not come back to a week of events at once.
 */
public final class DayBank {

    private DayBank() {
    }

    public static final long DAY = 24_000L;
    public static final int MIN_DAYS = 1;
    public static final int MAX_DAYS = 100;

    /** Ticks banked after the clock moved from {@code before} to {@code now}; {@code before < 0} means "just loaded". */
    public static long bank(long banked, long before, long now) {
        if (before < 0) {
            return banked;
        }
        long delta = now - before;
        return delta > 0 ? banked + delta : banked;
    }

    public static long period(int days) {
        return Math.max(MIN_DAYS, Math.min(MAX_DAYS, days)) * DAY;
    }

    public static boolean due(long banked, int days) {
        return banked >= period(days);
    }

    /** What is left in the bank once one is spent: never negative, never more than one period. */
    public static long spend(long banked, int days) {
        long p = period(days);
        return Math.max(0, Math.min(p - 1, banked - p));
    }
}
