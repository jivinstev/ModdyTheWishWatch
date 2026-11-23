package com.moddingfromamod.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>The last few things each player said, in memory only</b> — what a report attaches, so
 * the operator reading it sees the words that prompted it rather than a name and an accusation.
 *
 * <p>Keyed by NAME (lower-cased), because a report names a player by name and the person reported
 * may already have left. At most {@link #KEEP} lines per player and none older than {@link #MAX_AGE_MS};
 * nothing is written to disk here — the server's own log already holds public chat, and a report is the
 * only thing that copies any of it anywhere ({@link PlayerReport}). A restart forgets everything.
 *
 * <p>A private message is attached to a report ONLY when it was sent to the reporter
 * ({@link #linesFor}): a whisper to somebody else is that somebody's business, not the reporter's.
 * Pure and clock-handed.
 */
public final class ChatHistory {

    /** Lines kept per player. */
    public static final int KEEP = 10;
    /** The oldest line a report may attach. */
    public static final long MAX_AGE_MS = 30 * 60_000L;
    /** One line, clipped. */
    public static final int MAX_CHARS = 256;

    /** One remembered line. {@code to} is the recipient's name for a private message, else empty. */
    public record Line(long atMs, String surface, String text, String to) { }

    private final Map<String, Deque<Line>> byName = new HashMap<>();

    /** Remember one line {@code name} said. Blank text is ignored. */
    public synchronized void said(String name, String surface, String text, String to, long nowMs) {
        if (name == null || name.isBlank() || text == null || text.isBlank()) {
            return;
        }
        String t = text.strip();
        if (t.length() > MAX_CHARS) {
            t = t.substring(0, MAX_CHARS);
        }
        Deque<Line> d = byName.computeIfAbsent(key(name), k -> new ArrayDeque<>());
        d.addLast(new Line(nowMs, surface == null ? "" : surface, t, to == null ? "" : to.strip()));
        while (d.size() > KEEP) {
            d.removeFirst();
        }
    }

    /**
     * What a report about {@code name} by {@code reporter} attaches: his recent public lines and
     * {@code /me}s, and his private messages TO the reporter — oldest first, none older than
     * {@link #MAX_AGE_MS}.
     */
    public synchronized List<Line> linesFor(String name, String reporter, long nowMs) {
        Deque<Line> d = byName.get(key(name));
        List<Line> out = new ArrayList<>();
        if (d == null) {
            return out;
        }
        String me = key(reporter);
        for (Line l : d) {
            if (nowMs - l.atMs() > MAX_AGE_MS) {
                continue;
            }
            if (!l.to().isEmpty() && !key(l.to()).equals(me)) {
                continue;
            }
            out.add(l);
        }
        return out;
    }

    /** Forget everybody (a server stop). */
    public synchronized void clear() {
        byName.clear();
    }

    private static String key(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
    }
}
