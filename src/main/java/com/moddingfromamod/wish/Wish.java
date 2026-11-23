package com.moddingfromamod.wish;

import java.util.UUID;

import com.google.gson.JsonObject;

/**
 * One wish: what was asked for, by whom, where, and what became of it.
 *
 * <p>Deliberately Minecraft-free (positions are plain doubles, the dimension a plain string) so it
 * can be journalled, unit-tested, and later handed to an AI agent without dragging game types along.
 */
public record Wish(UUID id, String text, WishSource source, WishStatus status,
                   long createdMs, UUID playerId, String playerName,
                   String dimension, double x, double y, double z, String result,
                   String templateId, long settledMs) {

    public Wish {
        // Journal lines written before this field existed simply have no template.
        templateId = templateId == null ? "" : templateId;
    }

    /**
     * How long this wish took to settle, in ms — or -1 while it is still running.
     *
     * <p>{@code createdMs} alone could not answer the most basic question about the product: a wish
     * that took twenty seconds and one that took twenty minutes were indistinguishable afterwards,
     * because {@code status} moved and nothing recorded WHEN.
     */
    public long tookMs() {
        return settledMs <= 0 ? -1 : Math.max(0, settledMs - createdMs);
    }

    /** Whether this wish has stopped moving — granted, rejected or abandoned. */
    public boolean isSettled() {
        return settledMs > 0;
    }

    public static Wish create(String text, WishSource source, long nowMs, UUID playerId,
                              String playerName, String dimension, double x, double y, double z) {
        return new Wish(UUID.randomUUID(), text, source, WishStatus.PENDING, nowMs,
                playerId, playerName, dimension, x, y, z, "", "", 0L);
    }

    public Wish withStatus(WishStatus newStatus, String newResult) {
        return withStatus(newStatus, newResult, 0L);
    }

    /**
     * Move the status and, if this status is a FINAL one, stamp when it settled.
     *
     * <p>Stamped here rather than by each caller: a completion time recorded in some code paths and
     * not others is worse than none, because the average it produces looks real.
     */
    public Wish withStatus(WishStatus newStatus, String newResult, long nowMs) {
        long settled = settledMs;
        if (settled <= 0 && newStatus != null && newStatus.isFinished() && nowMs > 0) {
            settled = nowMs;
        }
        return new Wish(id, text, source, newStatus, createdMs, playerId, playerName,
                dimension, x, y, z, newResult == null ? "" : newResult, templateId, settled);
    }

    /**
     * Remember which template granted this wish.
     *
     * <p>Recorded so a rejection can <b>un-teach the exact thing</b> that produced the bad result.
     * Without it, "that's not right" could only re-open the wish while the offending template kept
     * matching first and serving the same wrong answer.
     */
    public Wish withTemplate(String matchedTemplateId) {
        return new Wish(id, text, source, status, createdMs, playerId, playerName,
                dimension, x, y, z, result, matchedTemplateId, settledMs);
    }

    /**
     * <b>The same wish, in different words</b> — the one thing that may rewrite {@link #text}.
     *
     * <p>Exists for {@code /moddy redirect} alone ({@code docs/CHILD_SAFETY_PLAN.md} §12.7): a
     * re-aimed wish is still this wish, with the same id, the same anchor and the same history, and
     * what changed is the sentence describing it. Everything downstream reads the text — the input
     * grade's memo key, the wake text, the provenance intent — so moving the child's new words into
     * it is what makes them visible to all three at once.
     *
     * <p>Deliberately NOT a general setter. A wish's text is the evidence of what a child asked
     * for, and the journal keeps every version of the record; a second caller rewriting it for any
     * other reason would make that evidence a guess.
     */
    public Wish withText(String newText) {
        return new Wish(id, newText == null ? "" : newText, source, status, createdMs, playerId,
                playerName, dimension, x, y, z, result, templateId, settledMs);
    }

    /** Short id for chat/logs — a 7-year-old doesn't need a full UUID. */
    public String shortId() {
        return id.toString().substring(0, 8);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id.toString());
        o.addProperty("text", text);
        o.addProperty("source", source.name());
        o.addProperty("status", status.name());
        o.addProperty("createdMs", createdMs);
        if (settledMs > 0) {
            o.addProperty("settledMs", settledMs);
        }
        o.addProperty("playerId", playerId == null ? "" : playerId.toString());
        o.addProperty("playerName", playerName);
        o.addProperty("dimension", dimension);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("result", result);
        o.addProperty("templateId", templateId);
        return o;
    }

    /** Parse a journal line. Returns null on anything malformed — one bad line must not be fatal. */
    public static Wish fromJson(JsonObject o) {
        try {
            return new Wish(
                    UUID.fromString(o.get("id").getAsString()),
                    o.get("text").getAsString(),
                    WishSource.parse(str(o, "source")),
                    WishStatus.parse(str(o, "status")),
                    o.has("createdMs") ? o.get("createdMs").getAsLong() : 0L,
                    parseUuid(str(o, "playerId")),
                    str(o, "playerName"),
                    str(o, "dimension"),
                    dbl(o, "x"), dbl(o, "y"), dbl(o, "z"),
                    str(o, "result"),
                    str(o, "templateId"),
                    o.has("settledMs") ? o.get("settledMs").getAsLong() : 0L);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static double dbl(JsonObject o, String k) {
        try {
            return o.has(k) ? o.get(k).getAsDouble() : 0.0;
        } catch (RuntimeException e) {
            return 0.0;
        }
    }

    private static UUID parseUuid(String s) {
        try {
            return s.isEmpty() ? null : UUID.fromString(s);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
