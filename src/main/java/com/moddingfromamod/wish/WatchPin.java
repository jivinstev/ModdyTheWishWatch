package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>Where Moddy goes: hotbar slot 1, exactly one, always,.</b>
 *
 * <p>Pure: given which of a player's 41 slots hold a watch and which are empty, it says which watch
 * to keep, which copies to remove and where the thing in slot 1 goes when the watch needs the slot.
 * {@code VanillaWatchItem} does the moving; every case in the contract is a row in
 * {@code WatchPinTest}.
 *
 * <p>Slot numbers are vanilla {@code Inventory}'s: 0–35 the main inventory (0–8 the hotbar), 36–39
 * armour, 40 the offhand.
 *
 * <h2>The contract, and why it is about the end state</h2>
 *
 * <p>A child's client is stock, so the server cannot stop an attempt, only undo it. Within one tick
 * every child has exactly one watch, in hotbar slot 1, and none is loose in the world. A one-frame flicker is accepted.
 *
 * <h2>Two cases where the watch is somewhere legitimate for now</h2>
 *
 * <ul>
 *   <li><b>On the cursor.</b> The contract: "can be held while the screen is open; back in slot
 *       1 on release or close". So a carried watch is THE watch; nothing moves, and copies in the
 *       inventory are removed.</li>
 *   <li><b>In an open container</b> (a chest he is looking into). It comes back when the container
 *       closes; minting another meanwhile would put two in front of him. If he also has one in his
 *       inventory, that one is pinned and the stored copy is removed at close.</li>
 * </ul>
 */
public final class WatchPin {

    /** The slot Moddy lives in: hotbar slot 1. */
    public static final int HOME = 0;
    /** The slot the Wish Bag lives in: hotbar slot 2. */
    public static final int BAG_HOME = 1;
    /** Main inventory is 0–35; armour 36–39; offhand 40. */
    public static final int MAIN_END = 36;
    public static final int OFFHAND = 40;
    public static final int SLOTS = 41;

    /** {@link Plan#source()} when there is no watch to move and one must be minted. */
    public static final int MINT = -1;
    /** {@link Plan#source()} when the watch is on the cursor or in an open container: leave it. */
    public static final int ELSEWHERE = -2;
    /** {@link Plan#displacedTo()} when slot 1's old item has nowhere to go: drop it at his feet. */
    public static final int DROP = -3;
    /** {@link Plan#displacedTo()} when nothing needs to move out of slot 1. */
    public static final int NONE = -4;

    private WatchPin() {
    }

    /**
     * What to do this tick.
     *
     * @param source      the slot the kept watch comes from, {@link #HOME} (already there),
     *                    {@link #MINT} or {@link #ELSEWHERE}
     * @param remove      slots holding a copy to clear, in slot order
     * @param displacedTo where slot 1's current item goes: a slot, {@link #DROP} or {@link #NONE}
     */
    public record Plan(int source, List<Integer> remove, int displacedTo) {

        /** Nothing to do: the one watch is in slot 1 (or legitimately elsewhere), no copies. */
        public boolean idle() {
            return idle(HOME);
        }

        /**
         * Nothing to do for a pin whose home is {@code home} — the Wish Bag's is {@link #BAG_HOME}.
         * Asking {@link #idle()} of a bag plan compared it against the WATCH's slot, so a bag sitting
         * exactly where it belongs read as work every tick: a log line and two inventory syncs, about
         * 30 a second for as long as he played (found from a live server's log, 2026-10-30).
         */
        public boolean idle(int home) {
            return (source == home || source == ELSEWHERE) && remove.isEmpty();
        }
    }

    /**
     * @param watch    per slot (41), does it hold a watch
     * @param empty    per slot (41), is it empty
     * @param carried  a watch is on his cursor
     * @param stored   a watch is in the container he has open (never one of Moddy's own menus)
     */
    public static Plan plan(boolean[] watch, boolean[] empty, boolean carried, boolean stored) {
        return plan(HOME, java.util.Set.of(), watch, empty, carried, stored);
    }

    /**
     * The same policy for any pinned thing: the watch at {@link #HOME}, the Wish Bag at
     * {@link #BAG_HOME}. {@code reserved} are other pins' homes, which a displaced item never
     * moves into — the other pin would only shove it out again, unannounced.
     *
     * @param watch which slots hold THIS pinned thing
     */
    public static Plan plan(int home, java.util.Set<Integer> reserved, boolean[] watch, boolean[] empty,
                            boolean carried, boolean stored) {
        if (watch == null || empty == null || watch.length != SLOTS || empty.length != SLOTS) {
            throw new IllegalArgumentException("41 slots, as vanilla's Inventory numbers them");
        }
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) {
            if (watch[i]) {
                found.add(i);
            }
        }
        if (carried) {
            // The one on the cursor is his; every inventory copy is a duplicate.
            return new Plan(ELSEWHERE, found, NONE);
        }
        if (found.isEmpty()) {
            if (stored) {
                return new Plan(ELSEWHERE, List.of(), NONE);   // back when the container closes
            }
            return new Plan(MINT, List.of(), empty[home] ? NONE : freeSlot(empty, List.of(), -1, home, reserved));
        }
        // Keep the one in slot 1 if there is one, else the first found (hotbar before bag).
        int keep = watch[home] ? home : found.get(0);
        List<Integer> remove = new ArrayList<>(found);
        remove.remove(Integer.valueOf(keep));
        if (keep == home || empty[home]) {
            return new Plan(keep, remove, NONE);
        }
        // Slot 1 holds something else. It swaps into the slot the watch leaves, which is exactly
        // what a number-key or F swap did — unless that slot is armour, where an arbitrary item
        // does not belong; then the first free bag slot, then the floor at his feet.
        int to = keep < MAIN_END || keep == OFFHAND ? keep : freeSlot(empty, remove, keep, home, reserved);
        return new Plan(keep, remove, to);
    }

    /**
     * The first free main-inventory slot after slot 1, counting the copies about to be removed as
     * free — they are, by the time the displaced item moves.
     */
    private static int freeSlot(boolean[] empty, List<Integer> removing, int leaving, int home,
                                java.util.Set<Integer> reserved) {
        for (int i = 0; i < MAIN_END; i++) {
            if (i == home || reserved.contains(i)) {
                continue;
            }
            if (empty[i] || removing.contains(i) || i == leaving) {
                return i;
            }
        }
        return DROP;
    }
}
