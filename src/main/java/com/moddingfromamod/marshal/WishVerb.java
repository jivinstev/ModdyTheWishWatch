package com.moddingfromamod.marshal;

import java.util.Locale;
import java.util.Set;

/**
 * What a wish would <b>do</b> to the thing it points at — which is what decides whether guessing is
 * allowed.
 *
 * <p>The cost asymmetry the whole ambiguity design turns on: asking costs about ten seconds of a
 * child's attention, breaks what he was doing, and sometimes gets "um…" as the answer. Guessing
 * wrong costs almost nothing <i>if the mistake is visible and reversible</i> — a wrong thing getting
 * bigger is funny and one sentence fixes it. It costs a great deal if the verb removes something he
 * loved.
 *
 * <p>So this is not "how confident are we", it is "how bad is being wrong". Three answers:
 *
 * <ul>
 *   <li>{@link Kind#REFERENCE} — makes something NEW that points at existing things ("make Spikor
 *       fight Dinko"). Changes neither, needs no lease, and a wrong guess costs one extra arena.</li>
 *   <li>{@link Kind#TWEAK} — changes a property. Bigger, redder, faster. Trivially undone.</li>
 *   <li>{@link Kind#DESTRUCTIVE} — removes, replaces or rebuilds. <b>This is the one that must
 *       ask</b>, because a wrong guess destroys something that took ten minutes to make and that he
 *       was proud of.</li>
 * </ul>
 *
 * <p>Biased towards DESTRUCTIVE on purpose. A false destructive costs one question; a false tweak
 * costs a castle.
 */
public final class WishVerb {

    public enum Kind {
        REFERENCE, TWEAK, DESTRUCTIVE;

        /** Only a destructive verb is worth interrupting him for. */
        public boolean mustAskWhenUnsure() {
            return this == DESTRUCTIVE;
        }
    }

    /**
     * Words that take something away or start it over.
     *
     * <p>"replace" and "instead" are here rather than under a tweak because both mean <i>the thing
     * that is there now stops existing</i>, however cheerfully they are phrased.
     */
    private static final Set<String> DESTRUCTIVE_WORDS = Set.of(
            "remove", "delete", "destroy", "kill", "clear", "erase", "wipe", "demolish",
            "get rid", "take away", "takeaway", "replace", "rebuild", "redo", "restart",
            "start over", "swap", "instead", "undo", "reset");

    /** Words that create a NEW thing which merely mentions the old ones. */
    private static final Set<String> REFERENCE_WORDS = Set.of(
            "fight", "fights", "race", "races", "versus", "vs", "against", "battle", "battles",
            "next to", "beside", "between", "together", "team", "friends", "meet");

    private WishVerb() {
    }

    /** What this wish would do. */
    public static Kind of(String wishText) {
        if (wishText == null || wishText.isBlank()) {
            return Kind.TWEAK;
        }
        String text = " " + wishText.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ").strip() + " ";
        for (String word : DESTRUCTIVE_WORDS) {
            if (text.contains(" " + word + " ")) {
                return Kind.DESTRUCTIVE;
            }
        }
        for (String word : REFERENCE_WORDS) {
            if (text.contains(" " + word + " ")) {
                return Kind.REFERENCE;
            }
        }
        return Kind.TWEAK;
    }

    /**
     * Above this, asking is worse than guessing: a child cannot choose between five pictures, and
     * the question itself becomes the interruption.
     */
    public static final int MAX_ASKABLE = 4;

    /**
     * Should the guess come with an offer to go and look at it?
     *
     * <p>The nicest cell in the table: instead of interrupting with a question, act on the best
     * guess and turn the result into a trip.
     */
    public static boolean offerToTakeHim(Kind kind, boolean canSeeIt) {
        return !canSeeIt && kind != Kind.DESTRUCTIVE;
    }
}
