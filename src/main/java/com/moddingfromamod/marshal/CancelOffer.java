package com.moddingfromamod.marshal;

import java.util.List;

/**
 * The words and commands behind the stop affordances — pure, so what a child is offered can be
 * asserted without a client.
 *
 * <p>Two rules encoded here rather than left to whoever writes the next chat line:
 *
 * <ul>
 *   <li><b>An affordance is never shown when it would lie.</b> {@code [undo]} appears only when
 *       every tool the wish used had an inverse; {@code [no — the other one]} only when there is a
 *       better candidate to aim at.</li>
 *   <li><b>No queue vocabulary, anywhere.</b> A seven-year-old never sees a queue, so the words
 *       <i>queued, waiting, busy, in line, position, later, next</i> must not appear on any of these
 *       paths. {@link #QUEUE_WORDS} is what the sweep test checks against.</li>
 * </ul>
 */
public final class CancelOffer {

    /**
     * Vocabulary that reveals a queue. Checked by a source sweep over every user-visible string on
     * a concurrent path, in the same spirit as the existing {@code SourceRuleSweepTest}: cheap, and
     * it fails by file and by name so the rule cannot erode.
     */
    public static final List<String> QUEUE_WORDS = List.of(
            "queued", "queue", "waiting for", "in line", "your turn", "position in",
            "i'll get to it", "right after this one");

    private CancelOffer() {
    }

    /** The clickable label. Short, and it says what happens rather than what it is. */
    public static String label(CancelMode mode) {
        return switch (mode) {
            case REDIRECT -> "[no — the other one]";
            case UNDO -> "[↩ undo]";
            case STOP -> "[✋ stop]";
        };
    }

    /**
     * The command the click runs — or, for {@link #suggests} modes, leaves in his chat box.
     * Everything is a thin skin over a {@code /moddy} verb that can also be typed.
     */
    public static String command(CancelMode mode, String shortId) {
        return switch (mode) {
            case REDIRECT -> com.moddingfromamod.wish.ChildCommands.redirectPrompt(shortId);
            case UNDO -> com.moddingfromamod.wish.ChildCommands.undo(shortId);
            case STOP -> com.moddingfromamod.wish.ChildCommands.stop(shortId);
        };
    }

    /**
     * True when the click must SUGGEST rather than run: a re-aim needs the words he really meant,
     * so running {@code redirect <id>} bare could only ever fail (— it was a RUN link, and
     * clicking {@code [no — the other one]} produced an incomplete-command error).
     */
    public static boolean suggests(CancelMode mode) {
        return mode == CancelMode.REDIRECT;
    }

    /** The tooltip. Read-aloud short. */
    public static String hover(CancelMode mode) {
        return switch (mode) {
            case REDIRECT -> "Tell me which one you really meant";
            case UNDO -> "Stop, and put everything back how it was";
            case STOP -> "Stop making this one";
        };
    }

    /**
     * What may honestly be offered for a wish.
     *
     * @param hasMadeSomething   whether there is anything an undo could actually reverse
     * @param hasBetterCandidate whether there is somewhere else to aim it
     */
    public static List<CancelMode> offerFor(List<String> toolsUsed, boolean hasMadeSomething,
                                            boolean hasBetterCandidate) {
        return UndoContract.offerFor(toolsUsed, hasMadeSomething, hasBetterCandidate);
    }

    /** True if any of the banned queue words appears in this line. */
    public static boolean readsAsAQueue(String line) {
        if (line == null) {
            return false;
        }
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        for (String word : QUEUE_WORDS) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
