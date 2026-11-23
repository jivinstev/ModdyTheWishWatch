package com.moddingfromamod.wish;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What it means when the child says <b>"that's not right"</b> — the watch's 👎 and {@code /moddy no}.
 *
 * <p><b>It is JUST UNDO</b>. What the wish made is taken away, the
 * learned template that produced it is forgotten and its datapack switched off, the wish settles as
 * {@link WishStatus#STOPPED} and the child gets the wish back from his bank. It is <b>not</b> sent
 * back to the agent: a 👎 re-opened the wish for a retry, and live a child pressed it
 * twice on a stomp power that ran three times, the third hanging until it timed out — with no way
 * to say "stop, it isn't working" and no wish given back for either press.
 *
 * <p>Forgetting the template still matters for the reason it was added: a wrong answer learned as
 * a template is otherwise served instantly, offline, forever (the wither storm learned as a costumed
 * spider). Un-teaching it is part of undoing it.
 *
 * <p>Pure — no Minecraft imports — so the policy is unit-tested without booting the game.
 */
public final class WishRejection {

    /**
     * How far back "that one" can reach. Long enough that a child can look at the thing, decide he
     * doesn't like it and go find the watch; short enough that it never silently un-teaches
     * something from a previous play session.
     */
    public static final long MAX_AGE_MS = 10 * 60_000L;

    /**
     * Starts the book's result for a wish the child undid. It is how a second 👎 knows the newest
     * wish is already gone — and so neither undoes an earlier wish he was happy with, nor deletes an
     * innocent template behind it, nor pays him twice.
     */
    public static final String MARKER = "REJECTED BY THE CHILD";

    private WishRejection() {
    }

    /**
     * Which wish is the child complaining about? The most recent one of his own that was actually
     * acted on — <b>including one he has already undone</b>, which the caller answers with
     * {@link #alreadyUndone()} and nothing else. A double press must land on the SAME wish: walking
     * back past it would take away an earlier wish he never complained about (the live
     * incident was two presses in a row).
     *
     * @param recent wishes oldest-first, as {@code WishBook.recent} returns them
     */
    public static Optional<Wish> pick(List<Wish> recent, UUID playerId, long nowMs) {
        if (recent == null) {
            return Optional.empty();
        }
        for (int i = recent.size() - 1; i >= 0; i--) {
            Wish w = recent.get(i);
            if (w == null || !isRejectable(w, playerId, nowMs)) {
                continue;
            }
            return Optional.of(w);
        }
        return Optional.empty();
    }

    private static boolean isRejectable(Wish w, UUID playerId, long nowMs) {
        if (playerId != null && !playerId.equals(w.playerId())) {
            return false;   // never let one player un-teach another's wish
        }
        if (nowMs - w.createdMs() > MAX_AGE_MS) {
            return false;
        }
        // Nothing happened, so there is nothing to be unhappy about.
        return w.status() != WishStatus.REJECTED && !w.text().isBlank();
    }

    /** True when the child has already undone this wish with 👎 / {@code /moddy no}. */
    public static boolean alreadyRejected(Wish w) {
        return w != null && w.result() != null && w.result().startsWith(MARKER);
    }

    /**
     * The book's result for an undone wish: the marker, and — when he said — what was wrong, plus
     * the template that was forgotten. The improvement loop mines it; no agent is sent it.
     */
    public static String undoneNote(String complaint, String forgottenTemplateId) {
        StringBuilder sb = new StringBuilder(MARKER);
        sb.append(" — he pressed \"that wasn't right\": what it made was taken away and the wish"
                + " was NOT tried again.");
        String said = complaint == null ? "" : complaint.trim();
        if (!said.isEmpty()) {
            sb.append(" The child says: \"").append(said).append("\".");
        }
        if (forgottenTemplateId != null && !forgottenTemplateId.isBlank()) {
            sb.append(" The learned template '").append(forgottenTemplateId)
              .append("' that produced it has been DELETED.");
        }
        return sb.toString();
    }

    /**
     * What the child hears back. Kind, never blames him, and honest about the two things
     * that may or may not have happened: something was taken away, and a wish came back.
     *
     * @param tookAway true when the undo reversed at least one thing (or switched its datapack off)
     * @param refunded the bank after the refund ({@code "✨ 7/10"}), or null when nothing was paid
     *                 back (no bank on this world, never charged, or already refunded)
     */
    public static String sayToChild(boolean tookAway, String refunded) {
        // Never "I took it away" when nothing came away (a wish that ran a raw command has no
        // inverse): he forgives "I wrote it down"; he does not forgive being told it is gone.
        String first = tookAway ? "Gone! I took it away" : "Okay! I've written down that it wasn't right";
        if (refunded == null || refunded.isBlank()) {
            return first + ".";
        }
        return first + " and gave you your wish back — " + refunded + ".";
    }

    /** A second 👎 on a wish he has already undone: nothing more happens, and he is told so. */
    public static String alreadyUndone() {
        return "I already took that one away! Make a new wish whenever you're ready.";
    }

    /** When there's nothing recent to undo. */
    public static String nothingToUndo() {
        return "I can't find a recent wish to undo! Make a wish first, then say no if it's wrong.";
    }
}
