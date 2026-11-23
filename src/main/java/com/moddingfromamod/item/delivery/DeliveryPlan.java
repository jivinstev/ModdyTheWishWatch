package com.moddingfromamod.item.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * <b>Where a wished stack lands</b> (the split). Pure: no Minecraft import,
 * so every row of the rule is a unit test.
 *
 * <p>The rule: <b>if Moddy says it, it can go in the Wish Bag; nothing else
 * can.</b> So there are two plans, one per {@link Kind}:
 *
 * <ul>
 *   <li><b>{@link Kind#HANDOVER}</b> ({@link #plan}) — the granting of a wish, which Moddy
 *       announces: the numbered hotbar slots 1–9, an existing matching stack there first (so a
 *       second batch of melon slices joins the first rather than taking a second slot); then the
 *       Wish Bag, which is unlimited; <b>never</b> the 27 main-inventory slots.</li>
 *   <li><b>{@link Kind#BEHAVIOUR}</b> ({@link #planBehaviour}) — the item's or the world's own
 *       doing (a vending machine's pull, a rule, a creature's ability, a clock), which Moddy does
 *       NOT announce: the hotbar 1–9, then the 27 main-inventory slots, then the floor at his
 *       feet. <b>Never</b> the Wish Bag — a runaway rule fills his inventory and then the floor,
 *       where vanilla merges and despawns it, and never grows the bag without bound.</li>
 * </ul>
 *
 * <p>Slots Moddy pins for himself (the watch in 1, the bag in 2) are RESERVED even when they are
 * momentarily empty: the pin puts its item back within a tick, and a wished thing that slipped in
 * there would be shoved aside by it, unannounced.
 */
public final class DeliveryPlan {

    /** The numbered hotbar slots, 0-based as vanilla numbers them. */
    public static final int HOTBAR = 9;

    /** The main-inventory slots, vanilla's 9–35. */
    public static final int MAIN = 27;

    /**
     * Why a stack is arriving, which decides where it may land and whether Moddy says so.
     * Stamped where a give ENTERS ({@code mob.SpawnScope}), never guessed from the stack.
     */
    public enum Kind {
        /** Moddy handing over the granting of a wish: hotbar, then the Wish Bag, announced. */
        HANDOVER,
        /** The item's or the world's own behaviour: hotbar, main inventory, feet; never the bag, never said. */
        BEHAVIOUR
    }

    /** One hotbar slot as the planner sees it. */
    public record Cell(boolean empty, boolean same, int count) {

        public static Cell free() {
            return new Cell(true, false, 0);
        }

        /** Holding something that is not this stack. */
        public static Cell other() {
            return new Cell(false, false, 0);
        }

        /** Holding {@code count} of this very stack (same item, same components). */
        public static Cell holding(int count) {
            return new Cell(false, true, count);
        }
    }

    /**
     * {@code count} into inventory {@code slot}, as vanilla numbers it (0–8 the hotbar, 9–35 the
     * main inventory); grows an existing stack when {@code merge}.
     */
    public record Into(int slot, int count, boolean merge) {
    }

    /**
     * The whole answer: these into the hotbar, these into the main inventory (a behaviour give
     * only), and the rest into the bag (a hand-over) or onto the floor at his feet (a behaviour).
     */
    public record Placement(List<Into> hotbar, List<Into> inventory, int toBag, int toFeet) {

        /** A hand-over's answer: the hotbar, then the bag. */
        public Placement(List<Into> hotbar, int toBag) {
            this(hotbar, List.of(), toBag, 0);
        }

        public int toHotbar() {
            return sum(hotbar);
        }

        public int toInventory() {
            return sum(inventory);
        }

        private static int sum(List<Into> into) {
            int n = 0;
            for (Into i : into) {
                n += i.count();
            }
            return n;
        }
    }

    private DeliveryPlan() {
    }

    /**
     * @param hotbar   the nine hotbar slots, in order
     * @param reserved 0-based slots no wished thing may take (Moddy's pins)
     * @param count    how many to hand over
     * @param maxStack how many fit in one slot
     */
    public static Placement plan(Cell[] hotbar, Set<Integer> reserved, int count, int maxStack) {
        if (hotbar == null || hotbar.length != HOTBAR) {
            throw new IllegalArgumentException("nine hotbar slots, as vanilla numbers them");
        }
        int max = Math.max(1, maxStack);
        List<Into> out = new ArrayList<>();
        int left = fill(hotbar, 0, reserved, Math.max(0, count), max, out);
        // WHATEVER IS LEFT goes in the Wish Bag. Never the main inventory.
        return new Placement(List.copyOf(out), left);
    }

    /**
     * A {@link Kind#BEHAVIOUR} give: the hotbar exactly as {@link #plan} fills it, then the
     * 27 main-inventory slots the same way (a matching stack first, then a free slot), and whatever
     * is left goes on the floor at his feet. The Wish Bag is not an answer this plan can give.
     *
     * @param main the 27 main-inventory slots, vanilla's 9–35, in order
     */
    public static Placement planBehaviour(Cell[] hotbar, Cell[] main, Set<Integer> reserved, int count,
                                          int maxStack) {
        if (hotbar == null || hotbar.length != HOTBAR) {
            throw new IllegalArgumentException("nine hotbar slots, as vanilla numbers them");
        }
        if (main == null || main.length != MAIN) {
            throw new IllegalArgumentException("27 main-inventory slots, as vanilla numbers them");
        }
        int max = Math.max(1, maxStack);
        List<Into> bar = new ArrayList<>();
        int left = fill(hotbar, 0, reserved, Math.max(0, count), max, bar);
        List<Into> inv = new ArrayList<>();
        left = fill(main, HOTBAR, Set.of(), left, max, inv);
        return new Placement(List.copyOf(bar), List.copyOf(inv), 0, left);
    }

    /**
     * Fill {@code cells} (vanilla slot {@code base + i}): onto a matching stack first, then a free
     * slot, lowest number first; never a {@code reserved} slot. Returns what did not fit.
     */
    private static int fill(Cell[] cells, int base, Set<Integer> reserved, int count, int max, List<Into> out) {
        int left = count;
        // 1. ONTO A MATCHING STACK FIRST — the same item with the same components, room permitting.
        for (int i = 0; i < cells.length && left > 0; i++) {
            Cell c = cells[i];
            if (c == null || c.empty() || !c.same() || reserved.contains(base + i)) {
                continue;
            }
            int room = max - c.count();
            if (room > 0) {
                int n = Math.min(room, left);
                out.add(new Into(base + i, n, true));
                left -= n;
            }
        }
        // 2. THEN A FREE SLOT, lowest number first, a whole stack each.
        for (int i = 0; i < cells.length && left > 0; i++) {
            Cell c = cells[i];
            if ((c != null && !c.empty()) || reserved.contains(base + i)) {
                continue;
            }
            int n = Math.min(max, left);
            out.add(new Into(base + i, n, false));
            left -= n;
        }
        return left;
    }
}
