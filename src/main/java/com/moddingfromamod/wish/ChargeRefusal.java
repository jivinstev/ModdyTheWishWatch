package com.moddingfromamod.wish;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * <b>What the core does with a world's {@link WishCharge#refuses} answer</b>: whether the wish stops, and the one line
 * the player then reads. Pure: the sweep is handed in, so Gate A decides every case without Minecraft.
 *
 * <ul>
 *   <li>Empty (or a charge that answered {@code null}): the wish goes on.</li>
 *   <li>{@code ""}: refused, and the charge said its own words (what an older charge's {@code true} maps to). Typed
 *       words and a recipe say nothing more, as they always did; a Builder make says {@link #BANK_EMPTY}, the line it
 *       always said for any refusal.</li>
 *   <li>Words: refused, and the player reads them, once. They are written by a world, so they pass the text sweep a
 *       chat line passes; words that do not are replaced by {@link #FALLBACK}.</li>
 * </ul>
 */
public record ChargeRefusal(boolean refused, String line) {

    /** The wish goes on. */
    public static final ChargeRefusal ALLOWED = new ChargeRefusal(false, "");

    /** What a Builder make has always said when a charge refused it without words of its own. */
    public static final String BANK_EMPTY = "Your wish bank is empty. It fills up again soon.";

    /** Said in place of a charge's words that do not pass the text sweep. */
    public static final String FALLBACK = "✨ That wish can't be made here right now. Try a different wish!";

    /** Where the wish was refused: what to say when the charge gave no words of its own. */
    public enum Where {
        /** Typed words, a recipe, a hotbar fire, "Make another one": the charge said its own words, nothing more. */
        WISH,
        /** A Builder or Apprentice make, or a Builder change: {@link #BANK_EMPTY}, as before. */
        MAKE
    }

    /**
     * @param ask    what the charge was asked about
     * @param answer the charge's answer ({@code null} reads as "go on")
     * @param where  which kind of start was refused
     * @param sweeps true when a line passes the text sweep
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public static ChargeRefusal decide(WishAsk ask, Optional<String> answer, Where where, Predicate<String> sweeps) {
        if (answer == null || answer.isEmpty()) {
            return ALLOWED;
        }
        String words = answer.get() == null ? "" : answer.get().strip();
        if (words.isEmpty()) {
            return new ChargeRefusal(true, where == Where.MAKE ? BANK_EMPTY : "");
        }
        boolean passes;
        try {
            passes = sweeps.test(words);
        } catch (RuntimeException e) {
            passes = false;                       // a line that could not be swept is not said
        }
        return new ChargeRefusal(true, passes ? words : FALLBACK);
    }
}
