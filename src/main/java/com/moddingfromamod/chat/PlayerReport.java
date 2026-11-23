package com.moddingfromamod.chat;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * <b>{@code /moddy report <name> [what happened]} — the decision and the record</b>. Pure; the
 * command and the file are {@link PlayerReports}.
 *
 * <p>A report is a message to the people who run Moddy, and NOTHING ELSE: nobody is kicked, muted or
 * removed by one, because a report is one person's account and the reported player has not been
 * heard. The server's operator reads it
 * (with their own tools) and decides.
 *
 * <p>Rate-limited per reporter ({@link #PER_HOUR} an hour, and one per reported player per
 * {@link #SAME_PLAYER_MS}), so a report cannot be used to flood the operator. The reporter's own
 * words are NOT swept by the lexicon: describing what somebody said is exactly the kind of sentence
 * that contains it, and a report refused for its evidence would be the worst possible answer.
 */
public final class PlayerReport {

    /** Reports one player may send in an hour. */
    public static final int PER_HOUR = 3;
    /** The gap before the same reporter may report the same player again. */
    public static final long SAME_PLAYER_MS = 10 * 60_000L;
    /** What the reporter wrote, clipped. */
    public static final int NOTE_MAX = 300;
    /** A Java Edition name. */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    /** The answer to one {@code /moddy report}. */
    public enum Verdict { SAVE, SELF, BAD_NAME, TOO_MANY }

    private final Map<UUID, Deque<Long>> sent = new HashMap<>();
    private final Map<String, Long> lastPair = new HashMap<>();

    /**
     * May {@code reporter} (named {@code reporterName}) report {@code name} now? A {@link Verdict#SAVE}
     * is COUNTED here — the caller saves the record or says it failed.
     */
    public synchronized Verdict check(UUID reporter, String reporterName, String name, long nowMs) {
        if (name == null || !NAME.matcher(name).matches()) {
            return Verdict.BAD_NAME;
        }
        if (reporterName != null && reporterName.equalsIgnoreCase(name)) {
            return Verdict.SELF;
        }
        Deque<Long> d = sent.computeIfAbsent(reporter, k -> new ArrayDeque<>());
        while (!d.isEmpty() && nowMs - d.peekFirst() > 3_600_000L) {
            d.removeFirst();
        }
        String pair = reporter + "|" + name.toLowerCase(Locale.ROOT);
        Long last = lastPair.get(pair);
        if (d.size() >= PER_HOUR || (last != null && nowMs - last < SAME_PLAYER_MS)) {
            return Verdict.TOO_MANY;
        }
        d.addLast(nowMs);
        lastPair.put(pair, nowMs);
        return Verdict.SAVE;
    }

    /** Forget everybody (a server stop). */
    public synchronized void clear() {
        sent.clear();
        lastPair.clear();
    }

    /**
     * The record — one line of {@code player-reports.jsonl}. The reported player's lines are attached
     * as typed: the file stays on the server, and an operator's tools should show them only in a
     * private view, never in a notification.
     *
     * @param id          a fresh id (the caller's {@code UUID.randomUUID()})
     * @param world       this world's manifest id, or empty
     * @param reportedId  the reported player's id when he is online or known, else null
     * @param online      whether he was on this world at the time
     * @param flagged     how many of his lines the lexicon would hold back (counted by the caller)
     */
    public static JsonObject record(String id, long nowMs, String world, UUID reporter, String reporterName,
                                    String name, UUID reportedId, boolean online, String note,
                                    List<ChatHistory.Line> lines, int flagged, boolean test) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("kind", "player-report");
        o.addProperty("id", id);
        o.addProperty("at", Instant.ofEpochMilli(nowMs).toString());
        o.addProperty("world", world == null ? "" : world);
        o.addProperty("test", test);
        JsonObject by = new JsonObject();
        by.addProperty("id", reporter.toString());
        by.addProperty("name", reporterName == null ? "" : reporterName);
        o.add("reporter", by);
        JsonObject who = new JsonObject();
        if (reportedId != null) {
            who.addProperty("id", reportedId.toString());
        }
        who.addProperty("name", name);
        who.addProperty("online", online);
        o.add("reported", who);
        String n = note == null ? "" : note.strip().replaceAll("\\s+", " ");
        o.addProperty("note", n.length() > NOTE_MAX ? n.substring(0, NOTE_MAX) : n);
        JsonArray ls = new JsonArray();
        for (ChatHistory.Line l : lines == null ? List.<ChatHistory.Line>of() : lines) {
            JsonObject x = new JsonObject();
            x.addProperty("at", Instant.ofEpochMilli(l.atMs()).toString());
            x.addProperty("surface", l.surface());
            x.addProperty("text", l.text());
            if (!l.to().isEmpty()) {
                x.addProperty("to", l.to());
            }
            ls.add(x);
        }
        o.add("lines", ls);
        o.addProperty("flagged", Math.max(0, flagged));
        return o;
    }
}
