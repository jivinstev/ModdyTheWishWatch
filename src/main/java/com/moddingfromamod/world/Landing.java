package com.moddingfromamod.world;

/**
 * <b>Where a finished make goes</b> (#70): a world's answer, asked before a make's first placing step
 * ({@link WorldMakes#landing}). The default is {@link #LANDS}: the plain game, exactly as before.
 */
public enum Landing {
    /** It appears where it was aimed, as always. */
    LANDS,
    /**
     * It is made and kept on the shelf as usual, but not placed: it goes to his hotbar as a wish (or his Wish Bag when
     * the hotbar is full), and appears where he aims it when he fires it. An item is handed over as an item wish.
     */
    KEPT,
    /** Kept, and an item make hands over the real item rather than an item wish (a creature is kept as a wish). */
    KEPT_AS_ITEM
}
