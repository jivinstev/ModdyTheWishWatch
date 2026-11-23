package com.moddingfromamod.bag;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;

/**
 * <b>The Wish Bag's pages, and what a click on one did</b>. Pure: no Minecraft
 * import — an entry is any {@code T} (an {@code ItemStack} in the game, a record in the tests).
 *
 * <p>The bag is a list, NEWEST FIRST: the most recently added thing is the top-left cell of page 1.
 * A page is the top five rows of a vanilla 9×6 chest — 45 things — and the
 * bottom row is the bag's own trim ({@link #bottomRow}): "◀ Older wishes" on the left,
 * "Newer wishes ▶" on the right (each only when there is a page that way), a star counting the pages
 * in the middle, and purple glass between. Unlimited, with a backstop of {@link #MAX_PAGES} pages — a runaway source is fixed
 * at its root, never by a small cap here.
 *
 * <h2>Why a click is reconciled rather than intercepted</h2>
 *
 * <p>A chest click has eight vanilla meanings (pick up, place, half, swap with a number key,
 * shift-move, double-click gather, drag, throw), and a stock client predicts each one itself. The
 * bag lets vanilla run every one of them against a page of REAL slots — whose {@code mayPlace}
 * refuses an ordinary item — and then asks {@link #reconcile} what changed: a cell that emptied or
 * shrank was taken out; a cell that filled, or now holds a different thing, was put in, and goes to
 * the front of the bag. So no click type can be forgotten, which is how a hand-rolled interceptor
 * (the watch's eight-row refusal table) would leak an item one day.
 */
public final class WishBagPages {

    /** Item cells on a page: the top five rows of nine. */
    public static final int PER_PAGE = 45;

    /** The backstop: unlimited in practice, never a small cap. */
    public static final int MAX_PAGES = 1000;

    /** The most entries the bag holds. */
    public static final int CAPACITY = PER_PAGE * MAX_PAGES;

    /** The chest's bottom row: the older arrow, the star counter, the newer arrow. */
    public static final int OLDER_SLOT = 45;
    public static final int LABEL_SLOT = 49;
    public static final int NEWER_SLOT = 53;

    /** The chest's 54 cells: five rows of things and the trim row. */
    public static final int SLOTS = 54;

    /** What one cell of the bottom row shows. */
    public enum Trim { OLDER, NEWER, COUNTER, GLASS }

    private WishBagPages() {
    }

    /** How many pages {@code size} entries fill — at least one, so an empty bag still opens. */
    public static int pages(int size) {
        return Math.max(1, (Math.max(0, size) + PER_PAGE - 1) / PER_PAGE);
    }

    /** A page number brought into range. */
    public static int clampPage(int page, int size) {
        return Math.max(0, Math.min(page, pages(size) - 1));
    }

    /** The entries page {@code page} shows, in cell order (may be fewer than a page). */
    public static <T> List<T> page(List<T> entries, int page) {
        int from = clampPage(page, entries.size()) * PER_PAGE;
        int to = Math.min(entries.size(), from + PER_PAGE);
        return from >= to ? List.of() : new ArrayList<>(entries.subList(from, to));
    }

    /** "Page 2 of 7". */
    public static String label(int page, int size) {
        return "Page " + (clampPage(page, size) + 1) + " of " + pages(size);
    }

    /**
     * The bottom row of page {@code page}, left to right: nine cells. An arrow appears only when
     * there is a page that way; where it is absent the cell is glass, so a missing arrow reads as
     * "no further", never as an empty slot he might put something in.
     */
    public static List<Trim> bottomRow(int page, int size) {
        int p = clampPage(page, size);
        List<Trim> row = new ArrayList<>();
        for (int slot = PER_PAGE; slot < SLOTS; slot++) {
            if (slot == OLDER_SLOT && p < pages(size) - 1) {
                row.add(Trim.OLDER);
            } else if (slot == NEWER_SLOT && p > 0) {
                row.add(Trim.NEWER);
            } else if (slot == LABEL_SLOT) {
                row.add(Trim.COUNTER);
            } else {
                row.add(Trim.GLASS);
            }
        }
        return row;
    }

    /**
     * The page a press on bottom-row cell {@code slot} turns to, or {@code page} itself when the
     * cell is not an arrow there (glass, the star, or an arrow with no page behind it).
     */
    public static int turn(int slot, int page, int size) {
        int p = clampPage(page, size);
        if (slot == OLDER_SLOT && p < pages(size) - 1) {
            return p + 1;
        }
        if (slot == NEWER_SLOT && p > 0) {
            return p - 1;
        }
        return p;
    }

    /** True when there is room for {@code more} entries under the backstop. */
    public static boolean hasRoom(int size, int more) {
        return (long) size + more <= CAPACITY;
    }

    /**
     * The bag after a click on page {@code page}.
     *
     * @param entries the bag before the click, newest first
     * @param before  the page's cells before the click (nulls for empty), {@link #PER_PAGE} long
     * @param after   the same cells after vanilla ran the click
     * @param same    "the same thing" (same item, same components), ignoring count
     * @param count   an entry's count
     * @return the bag after, newest first: whatever was put in at the front, in cell order
     */
    public static <T> List<T> reconcile(List<T> entries, int page, List<T> before, List<T> after,
                                        BiPredicate<T, T> same, ToIntFunction<T> count) {
        int from = clampPage(page, entries.size()) * PER_PAGE;
        List<T> kept = new ArrayList<>(entries);
        List<T> putIn = new ArrayList<>();
        // Walk the cells; an entry index is only meaningful for cells that held something.
        List<Integer> remove = new ArrayList<>();
        for (int cell = 0; cell < PER_PAGE; cell++) {
            T b = cell < before.size() ? before.get(cell) : null;
            T a = cell < after.size() ? after.get(cell) : null;
            boolean hadB = b != null && count.applyAsInt(b) > 0;
            boolean hasA = a != null && count.applyAsInt(a) > 0;
            int index = from + cell;
            if (!hadB && !hasA) {
                continue;
            }
            if (hadB && hasA && same.test(b, a)) {
                if (count.applyAsInt(a) != count.applyAsInt(b) && index < kept.size()) {
                    kept.set(index, a);         // took some out, or topped it up: it stays where it is
                }
                continue;
            }
            if (hadB && index < kept.size()) {
                remove.add(index);              // emptied, or swapped for something else
            }
            if (hasA) {
                putIn.add(a);                   // newly put in: to the front
            }
        }
        for (int i = remove.size() - 1; i >= 0; i--) {
            kept.remove((int) remove.get(i));
        }
        List<T> out = new ArrayList<>(putIn);
        out.addAll(kept);
        return out;
    }
}
