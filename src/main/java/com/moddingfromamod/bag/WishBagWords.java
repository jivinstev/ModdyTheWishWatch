package com.moddingfromamod.bag;

import java.util.List;

/**
 * <b>Every word the Wish Bag says to a child, in one place</b>. Pure: no Minecraft import,
 * so {@code WishBagWordsTest} sweeps each line through the shipped safety lexicon.
 *
 * <p>The rule "only wished things go in" is said three ways, all the time: in
 * the screen's {@link #TITLE}, in the bag item's second {@link #LORE} line, and in chat on every
 * refused move ({@link #REFUSAL}). No one-time hint: a rule shown once is a rule he forgets.
 */
public final class WishBagWords {

    /**
     * The chest's title. Light purple, drawn by every client as a styled component. It must fit
     * the 9-wide chest's title row at the default GUI scale ({@link #TITLE_ROOM} px of the 176-px
     * screen), which {@code WishBagClientTest} measures with the client's own font.
     *
     * <p>The intended words are "✦ Wish Bag · wished things only ✦". Measured with the client's own
     * font that is <b>167 px</b>, and the last word and star ran off the chest (Gate C, 2026-10-04);
     * the two spaces beside the stars are 4 px each, so without them it is 159 px and fits.
     */
    public static final String TITLE = "✦Wish Bag · wished things only✦";

    /** The room a 9-wide chest leaves its title: 176 px wide, the label starts 8 px in. */
    public static final int TITLE_ROOM = 176 - 2 * 8;

    /** The bag item's name. */
    public static final String NAME = "Wish Bag";

    /** The bag item's tooltip, below its name. The second line is the rule. */
    public static final List<String> LORE = List.of(
            "Use it to open!",
            "Things you wished for wait in here.",
            "Only wished things can go in.");

    /** What he reads when an ordinary item will not go in. {@code %s}: block/item. */
    public static final String REFUSAL =
            "Only things you wished for go in your Wish Bag — that's a regular Minecraft %s.";

    /** The bottom-left arrow: towards older wishes (a later page — the bag is newest first). */
    public static final String OLDER = "◀ Older wishes";

    /** The bottom-right arrow: towards newer wishes (an earlier page). */
    public static final String NEWER = "Newer wishes ▶";

    private WishBagWords() {
    }

    /** The star's name: "Page 2 of 7 · 300 things". */
    public static String counter(int page, int size) {
        return WishBagPages.label(page, size) + " · " + things(size);
    }

    /** "1 thing", "45 things". */
    public static String things(int size) {
        return size + " thing" + (size == 1 ? "" : "s");
    }

    /** The refusal for a block or an item. */
    public static String refusal(boolean block) {
        return String.format(REFUSAL, block ? "block" : "item");
    }
}
