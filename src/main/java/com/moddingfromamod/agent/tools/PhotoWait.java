package com.moddingfromamod.agent.tools;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * <b>Waiting for a photograph to land</b> — the decision, with no Minecraft and no file system in
 * it, so a fake clock can drive every branch.
 *
 * <p>The client takes the shot a few ticks after the shutter and writes a 1–2 MB PNG; the
 * timestamp moves when the file is CREATED, not when it is finished. So there are TWO waits, and
 * they must not be confused:
 *
 * <ol>
 *   <li><b>APPEAR</b> — nothing new is on disk yet (absent, or only the file an earlier shot left,
 *       {@code mtime <= before}). This is bounded by {@code appearMs} from the start, and NOTHING
 *       seen in this phase counts as progress. ⚠ That is the bug this class exists for: the old
 *       loop compared an absent file's size, 0, against an initial {@code -1}, called that growth,
 *       and — because it only remembered the size once the file had appeared — kept calling it
 *       growth on every poll, so a photo that never came ran to the 30 s hard cap however short
 *       the quiet period was meant to be.</li>
 *   <li><b>SETTLE</b> — the file has appeared. Any change in its size or timestamp resets the
 *       {@code quietMs} timer; two equal non-zero sizes in a row mean the write finished and the
 *       reader is tried. {@code quietMs} of silence without a readable file gives up.</li>
 * </ol>
 *
 * <p>{@code capMs}, from the start, is the backstop for a file that grows for ever.
 */
final class PhotoWait {

    private PhotoWait() {}

    /** One reading of the file: its timestamp and size, both 0 when it is not there. */
    record Probe(long mtime, long size) {}

    /** Reads the file once it looks finished; null means "not readable yet, keep waiting". */
    @FunctionalInterface
    interface Reader<T> {
        T read();
    }

    enum Status {
        /** It appeared, stopped growing and was read. */
        SETTLED,
        /** Nothing new ever reached the disk within {@code appearMs}. */
        NEVER_APPEARED,
        /** It appeared, then went quiet for {@code quietMs} without ever reading back. */
        WENT_QUIET_UNREADABLE,
        /** It appeared and was still changing (or unreadable) when {@code capMs} ran out. */
        STILL_GROWING_AT_CAP
    }

    record Outcome<T>(Status status, T value, long waitedMs) {
        boolean appeared() {
            return status != Status.NEVER_APPEARED;
        }
    }

    static <T> Outcome<T> await(long before, long appearMs, long quietMs, long capMs, long pollMs,
                                LongSupplier clock, Supplier<Probe> probe,
                                Reader<T> reader, LongConsumer sleeper) {
        long start = clock.getAsLong();
        boolean appeared = false;
        long lastProgressMs = start;
        long lastSize = -1L;
        long lastMtime = -1L;
        while (true) {
            long now = clock.getAsLong();
            if (now - start >= capMs) {
                return new Outcome<>(appeared ? Status.STILL_GROWING_AT_CAP : Status.NEVER_APPEARED,
                        null, now - start);
            }
            if (!appeared && now - start >= appearMs) {
                return new Outcome<>(Status.NEVER_APPEARED, null, now - start);
            }
            if (appeared && now - lastProgressMs >= quietMs) {
                return new Outcome<>(Status.WENT_QUIET_UNREADABLE, null, now - start);
            }
            Probe p = probe.get();
            if (p.mtime() > before && p.mtime() > 0) {
                if (!appeared) {
                    // The first sight of it is the first progress; its size is the baseline.
                    appeared = true;
                    lastProgressMs = now;
                    lastSize = p.size();
                    lastMtime = p.mtime();
                } else {
                    boolean sameSize = p.size() == lastSize;
                    if (!sameSize || p.mtime() != lastMtime) {
                        lastProgressMs = now;   // still arriving
                        lastSize = p.size();
                        lastMtime = p.mtime();
                    }
                    // Two equal non-zero sizes in a row: the write finished (as).
                    if (sameSize && p.size() > 0) {
                        T value = reader.read();
                        if (value != null) {
                            return new Outcome<>(Status.SETTLED, value, clock.getAsLong() - start);
                        }
                    }
                }
            }
            sleeper.accept(pollMs);
        }
    }
}
