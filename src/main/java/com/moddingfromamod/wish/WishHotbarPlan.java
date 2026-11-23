package com.moddingfromamod.wish;

import java.util.List;

/**
 * <b>Where a wish goes on the hotbar</b>: the pure half of {@link WishHotbar}, so the choice has unit tests.
 * The hotbar is nine slots; a wish takes the first empty one. With none empty he is asked which slot to give up,
 * and what was there moves into his bag, so it needs one free place; with no free place anywhere nothing moves.
 */
public final class WishHotbarPlan {

    public static final int SLOTS = 9;

    /** A cooldown group is a resource path: a recipe id is hashed into one. */
    public static final String GROUP_PREFIX = "hotbar/";

    private WishHotbarPlan() {
    }

    /** What adding a wish does. */
    public enum Step { PLACE, ASK_WHICH, NO_ROOM }

    /** @param slot the hotbar slot for {@link Step#PLACE}, else -1 */
    public record Plan(Step step, int slot) {
    }

    /**
     * @param hotbarEmpty  one entry per hotbar slot, true where it is empty
     * @param bagHasRoom   is any slot of the rest of his inventory empty?
     */
    public static Plan add(List<Boolean> hotbarEmpty, boolean bagHasRoom) {
        for (int i = 0; i < SLOTS && i < hotbarEmpty.size(); i++) {
            if (Boolean.TRUE.equals(hotbarEmpty.get(i))) {
                return new Plan(Step.PLACE, i);
            }
        }
        return new Plan(bagHasRoom ? Step.ASK_WHICH : Step.NO_ROOM, -1);
    }

    /** The cooldown group one wish's proxy shares with nothing else: stable for a recipe, a legal resource path. */
    public static String group(String recipeId) {
        String r = recipeId == null ? "" : recipeId;
        long h = 1125899906842597L;
        for (int i = 0; i < r.length(); i++) {
            h = 31 * h + r.charAt(i);
        }
        return GROUP_PREFIX + Long.toHexString(h);
    }

    /** The proxy's name: says it is a WISH for the thing, never the thing ("✨ Wish: Fire Sword"). */
    public static String name(String label) {
        String l = label == null ? "" : label.replace("✨", "").strip();
        return "✨ Wish: " + (l.isEmpty() ? "one of your things" : l);
    }
}
