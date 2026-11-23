package com.moddingfromamod.wish;

/**
 * <b>Which words describe the thing, and where they came from</b>. Pure.
 *
 * <p>The reveal used to carry the description, written by {@code finish_building} before the
 * photograph existed, and it described the plan. Now the reveal says only
 * {@link WishFeedback#REVEAL_SHORT}, and the description is {@code finish_wish}'s {@code say},
 * written after the agent has judged the photo. When that is missing, a {@code say} the agent still
 * passed to {@code finish_building} is the fallback — said, because a child told nothing about his
 * wish is worse off, but labelled {@code plan} in the log so the reveal-vs-photo instrument
 * ({@code judge/RevealCheck}) can tell the two apart.
 */
public final class RevealWords {

    private RevealWords() {
    }

    /** Where a description came from: after the photo, from the plan, or nowhere. */
    public enum From { PHOTO, PLAN, NONE }

    public record Description(String text, From from) {
    }

    public static Description choose(String finishWishSay, String plannedSay) {
        String after = finishWishSay == null ? "" : finishWishSay.strip();
        if (!after.isEmpty()) {
            return new Description(after, From.PHOTO);
        }
        String before = plannedSay == null ? "" : plannedSay.strip();
        if (!before.isEmpty()) {
            return new Description(before, From.PLAN);
        }
        return new Description("", From.NONE);
    }
}
