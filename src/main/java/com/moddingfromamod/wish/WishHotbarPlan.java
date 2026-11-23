package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>Where a wish goes on the hotbar</b>: the pure half of {@link WishHotbar}, so the choice has unit tests.
 * The hotbar is nine slots; a wish takes the first empty one. With none empty he is asked which slot to give up,
 * and what was there moves into his bag, so it needs one free place; with no free place anywhere nothing moves.
 *
 * <p>When he is knocked out his wishes are taken off at the death and given back on respawn ({@link #restore}): each
 * to the slot it was in, or, when something else took that slot meanwhile, to the first free one.
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

    /** The main inventory: the hotbar, then the bag. A wish whose slot was taken goes to the first free one here. */
    public static final int MAIN = 36;

    /**
     * Where each wish he had at his death goes back on respawn.
     *
     * @param empty  one entry per inventory slot of the new body, true where it is empty
     * @param wanted the slot each wish was in when he died, in order
     * @return one entry per wish: the slot it goes to, or -1 when there is no free slot left (it stays in My wishes)
     */
    public static List<Integer> restore(List<Boolean> empty, List<Integer> wanted) {
        Set<Integer> taken = new HashSet<>();
        List<Integer> out = new ArrayList<>(wanted.size());
        // Own slots first, so a wish that moved never pushes another out of the slot it had.
        for (int w : wanted) {
            boolean free = w >= 0 && w < empty.size() && Boolean.TRUE.equals(empty.get(w)) && taken.add(w);
            out.add(free ? w : null);
        }
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i) != null) {
                continue;
            }
            int to = -1;
            for (int s = 0; s < MAIN && s < empty.size(); s++) {
                if (Boolean.TRUE.equals(empty.get(s)) && !taken.contains(s)) {
                    to = s;
                    break;
                }
            }
            if (to >= 0) {
                taken.add(to);
            }
            out.set(i, to);
        }
        return out;
    }
}
