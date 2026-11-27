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

    /** What adding a wish does. {@link #WISH_BAG}: the hotbar is full, so it goes in his Wish Bag (#189). */
    public enum Step { PLACE, WISH_BAG, ASK_WHICH, NO_ROOM }

    /** @param slot the hotbar slot for {@link Step#PLACE}, else -1 */
    public record Plan(Step step, int slot) {
    }

    /**
     * @param hotbarEmpty  one entry per hotbar slot, true where it is empty
     * @param bagHasRoom   is any slot of the rest of his inventory empty?
     */
    public static Plan add(List<Boolean> hotbarEmpty, boolean bagHasRoom) {
        return add(hotbarEmpty, bagHasRoom, false);
    }

    /**
     * …and, with {@code wishBagTakes}, a full hotbar puts it in his Wish Bag (#189) rather than asking which slot to
     * give up: a pin from the menu always goes somewhere he can find it. Only a Wish Bag that cannot take it (full, or
     * its file refused) asks, as before.
     */
    public static Plan add(List<Boolean> hotbarEmpty, boolean bagHasRoom, boolean wishBagTakes) {
        for (int i = 0; i < SLOTS && i < hotbarEmpty.size(); i++) {
            if (Boolean.TRUE.equals(hotbarEmpty.get(i))) {
                return new Plan(Step.PLACE, i);
            }
        }
        if (wishBagTakes) {
            return new Plan(Step.WISH_BAG, -1);
        }
        return new Plan(bagHasRoom ? Step.ASK_WHICH : Step.NO_ROOM, -1);
    }

    /** What he is told when a pin goes in his Wish Bag (#189): new, or already there. Pure. */
    public static String inWishBag(boolean already) {
        return already
                ? "🎒 Your hotbar is full, and it is already in your Wish Bag (slot " + (WatchPin.BAG_HOME + 1)
                        + "). Take it out to use it."
                : "🎒 Your hotbar is full, so it is in your Wish Bag (slot " + (WatchPin.BAG_HOME + 1)
                        + "). Take it out to use it.";
    }

    /** What he is told when a new wish is pinned in his Wish Bag (#189); {@code label} empty when it is not said. */
    public static String pinnedInBag(String label) {
        String l = label == null ? "" : label.replace("✨", "").strip();
        return "🎒 " + (l.isEmpty() ? "Your new wish" : l) + " is in your Wish Bag now. Use it to make another one any time!";
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

    /** The longest rest a sweep shows (a day of play), so a charge's answer can never overflow the game's clock. */
    public static final int MAX_REST_TICKS = 24 * 60 * 60 * 20;

    /**
     * The rest a proxy's cooldown sweep is set to, from the world's charge ({@link WishCharge#restLeft}).
     *
     * @param fired      true right after a fire (allowed or refused), false when a proxy is given to him again
     * @param chargeSays the charge's answer: ticks left, {@code 0} ready, {@code -1} (any negative) it does not say
     * @param coreRest   the core's own rest after a fire ({@link WishHotbar#REST_TICKS})
     * @return the ticks to set the cooldown to ({@code 0} clears it), or {@code -1}: leave it as it is
     */
    public static int shownRest(boolean fired, int chargeSays, int coreRest) {
        if (chargeSays < 0) {
            return fired ? coreRest : -1;        // the charge does not say: one second after a fire, else untouched
        }
        int rest = Math.min(chargeSays, MAX_REST_TICKS);
        return fired ? rest : rest > 0 ? rest : -1;   // given again: only a rest the charge names is put back
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
