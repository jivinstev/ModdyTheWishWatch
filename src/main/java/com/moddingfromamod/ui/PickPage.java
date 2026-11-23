package com.moddingfromamod.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>A PICK screen: one page of tiles</b> — the first of the two Moddy menu forms (docs/BEDROCK_COMPAT.md
 * "Menu mapping"). Pure; {@link MenuKit} opens it as a BUTTON chest, which Java draws as a chest and a
 * Bedrock Pocket player gets as a one-tap grid.
 *
 * <p>The layout is LOCKED here so every menu reads the same on every edition:
 * <ul>
 *   <li>tiles from slot 0 in reading order — a Pocket grid lists tiles in slot order, so the choices
 *       come first;</li>
 *   <li>the controls in the LAST row: ◀ Back at its first slot, Next at its middle, ✖ Close at its
 *       last (measured on a phone with this order);</li>
 *   <li>a choice that is ON/OFF (a power, a setting) is a tile with {@link Tile#selected}, never a dialog
 *       toggle: a Bedrock toggle flips only on a touch at the switch's right end. A selected tile's words
 *       END with ✔, because the Pocket grid drops a leading symbol;</li>
 *   <li>at most {@link #MAX_TILES} tiles; a longer list is split by its caller into pages.</li>
 * </ul>
 */
public record PickPage(String title, List<Tile> tiles, boolean back, boolean next, String nextLabel, Picks picks) {

    /** A tile's {@code item} that means Moddy's own watch ("✨ I wish for…"), drawn as the old chest's wish tile. */
    public static final String WATCH_ICON = "moddy:wish_watch";
    /** The back arrow every "◀ Back" wears: Moddy's left-pointing sprite, {@code WatchIcons.BACK}. */
    public static final String BACK_ICON = com.moddingfromamod.marshal.WatchIcons.BACK;


    public static final int COLS = 9;
    public static final int MAX_ROWS = 6;
    /** Five rows of tiles above the control row. */
    public static final int MAX_TILES = COLS * (MAX_ROWS - 1);

    public static final String BACK_ID = "@back";
    public static final String NEXT_ID = "@next";
    public static final String CLOSE_ID = "@close";

    /** A multi-pick page's Next: always there, for fewer picks than the limit. */
    public static final String DONE_LABEL = "Done";

    /**
     * <b>A MULTI-PICK page's picks</b>: any of the tiles whose ids are {@code counted} may be chosen (a
     * tile's {@link Tile#selected}), as many as the thing being made can REALLY take. The page draws them in a "Your
     * picks" row INSIDE the chest — never in his inventory, where a drawn item would be a real one and Bedrock has
     * none — after {@code subject}, the thing being made (null for none), and {@code budget}, the real room it has
     * ("Body room 7/12 boxes"; null when nothing is counted out). A tile in {@code blocked} cannot be added now and is
     * drawn grey with the reason ("Can't with Breathes fire"); pressing it says the reason. Tiles NOT counted (a
     * creature's mood beside its powers) are ordinary one-of choices on the same page.
     *
     * <p>{@code most} is a hand-set count (0 = none). The Builder uses none — its limits are the tools'
     * (wishbuilder/BuilderLimits) — and a page that still sets one gets an earlier issue's "n/m" title and grey Full panes.
     */
    public record Picks(int most, Tile subject, java.util.Set<String> counted, Map<String, String> blocked, String budget) {
        public Picks {
            counted = java.util.Set.copyOf(counted);
            blocked = Map.copyOf(blocked == null ? Map.of() : blocked);
            if (most < 0 || most > COLS - 1) {
                throw new IllegalArgumentException("a multi-pick page holds 1.." + (COLS - 1) + " picks, not " + most);
            }
        }

        /** an earlier issue's form: at most {@code most} picks. */
        public Picks(int most, Tile subject, java.util.Set<String> counted) {
            this(most, subject, counted, Map.of(), null);
        }

        /** an earlier issue's form: no count, only what cannot be added now and why, and the real room left. */
        public static Picks open(Tile subject, java.util.Set<String> counted, Map<String, String> blocked, String budget) {
            return new Picks(0, subject, counted, blocked, budget);
        }
    }

    /** A blocked tile's picture: grey, so it reads as "not now" on both editions. */
    public static final String BLOCKED_ITEM = "minecraft:gray_concrete";
    /** The first lore line of the picks row's room tile (Bedrock reads it as data, as it does {@link #REMOVE_LINE}). */
    public static final String BUDGET_LINE = "Room it has";
    /** The first lore line of the picks row's "+2 more" tile, when the row cannot hold every pick. */
    public static final String MORE_LINE = "Ticked above";

    /** The picks row's "remove" line, the Full pane's words, and an empty place's. Bedrock reads them as data. */
    public static final String REMOVE_LINE = "Click to remove";
    public static final String FULL_LABEL = "Full";
    public static final String FULL_LINE = "Remove one first";

    /** One tile: what pressing it means ({@code id}), the vanilla item drawn, its words. */
    public record Tile(String id, String item, String label, List<String> lore, boolean selected) {
        public Tile {
            lore = List.copyOf(lore == null ? List.of() : lore);
            if (id == null || id.isBlank() || id.startsWith("@")) {
                throw new IllegalArgumentException("a tile id is plain words, not '" + id + "'");
            }
        }

        public static Tile of(String id, String item, String label) {
            return new Tile(id, item, label, List.of(), false);
        }

        /**
         * The words drawn: a selected tile ENDS with ✔ (its glint is MenuKit's). Not first: the Pocket grid
         * drops a LEADING symbol (it switches Bedrock's line to a smaller fallback font), so "✔ Glows"
         * reached a phone as "Glows" and nobody could see which powers were on (2026-11-08).
         */
        public String shown() {
            return selected ? label + " ✔" : label;
        }
    }

    public PickPage {
        tiles = List.copyOf(tiles);
        int max = picks == null ? MAX_TILES : MAX_TILES - COLS;   // a multi-pick page gives a row to its picks
        if (tiles.size() > max) {
            throw new IllegalArgumentException(tiles.size() + " tiles on one page; at most " + max);
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("a pick page has a title");
        }
        nextLabel = nextLabel == null || nextLabel.isBlank() ? "Next" : nextLabel;   // "Next" + an arrow truncated to "Nex." in a Bedrock Pocket grid (phone, 2026-11-08)
    }

    public PickPage(String title, List<Tile> tiles, boolean back, boolean next, String nextLabel) {
        this(title, tiles, back, next, nextLabel, null);
    }

    public static PickPage of(String title, List<Tile> tiles, boolean back) {
        return new PickPage(title, tiles, back, false, "");
    }

    /**
     * A MULTI-PICK page: the title carries the count ("Parts 1/3"), Back and {@link #DONE_LABEL} are always
     * there, and the picks row sits between the tiles and the controls. Every page that lets him choose several
     * things is one of these (MenuMappingTest): the limit is drawn, never only refused.
     */
    public static PickPage multi(String title, List<Tile> tiles, Picks picks) {
        long n = tiles.stream().filter(t -> t.selected() && picks.counted().contains(t.id())).count();
        return new PickPage(picks.most() > 0 ? title + " " + n + "/" + picks.most() : title, tiles, true, true, DONE_LABEL, picks);
    }

    public boolean multi() {
        return picks != null;
    }

    /** The counted tiles chosen so far, in the page's order. */
    public List<Tile> chosen() {
        return picks == null ? List.of() : tiles.stream().filter(t -> t.selected() && picks.counted().contains(t.id())).toList();
    }

    /** At the limit: every unchosen counted tile is Full. */
    public boolean full() {
        return picks != null && picks.most() > 0 && chosen().size() >= picks.most();
    }

    private boolean isFull(Tile t) {
        return full() && !t.selected() && picks.counted().contains(t.id());
    }

    /** Why this tile cannot be added now, or null. */
    public String blocked(Tile t) {
        return picks == null || t.selected() ? null : picks.blocked().get(t.id());
    }

    /** The picks row's places for picks: the row less the subject and the room tile. */
    private int pickPlaces() {
        return COLS - (picks.subject() == null ? 0 : 1) - (picks.budget() == null ? 0 : 1);
    }

    /** Rows the chest needs: the tiles' rows, a multi-pick page's picks row, and the control row. */
    public int rows() {
        return Math.max(1, (tiles.size() + COLS - 1) / COLS) + (picks == null ? 1 : 2);
    }

    /** The picks row's first slot, or -1 on a single-choice page. */
    public int picksRowSlot() {
        return picks == null ? -1 : (rows() - 2) * COLS;
    }

    /**
     * Every TILE the chest draws, by slot — the grid (a chosen tile says how to remove it; at the limit an unchosen
     * one is a Full pane) and a multi-pick page's picks row (the subject, each pick, a pane per place left). The
     * controls are {@link #backSlot()} and the rest. Pure: the same map Java's chest and the Bedrock form are drawn from.
     */
    public Map<Integer, Tile> drawn() {
        Map<Integer, Tile> out = new LinkedHashMap<>();
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            if (isFull(t)) {
                t = new Tile(t.id(), BLOCKED_ITEM, FULL_LABEL, List.of(FULL_LINE), false);
            } else if (blocked(t) != null) {
                t = new Tile(t.id(), BLOCKED_ITEM, t.label(), List.of(blocked(t)), false);
            } else if (picks != null && t.selected() && picks.counted().contains(t.id())) {
                t = new Tile(t.id(), t.item(), t.label(), List.of(REMOVE_LINE), true);
            }
            out.put(i, t);
        }
        if (picks != null) {
            int s = picksRowSlot();
            if (picks.subject() != null) {
                out.put(s++, picks.subject());
            }
            if (picks.budget() != null) {
                out.put(s++, new Tile("budget", "minecraft:bundle", picks.budget(), List.of(BUDGET_LINE), false));
            }
            List<Tile> chosen = chosen();
            int shown = chosen.size() > pickPlaces() ? pickPlaces() - 1 : chosen.size();
            for (Tile c : chosen.subList(0, shown)) {
                out.put(s++, new Tile(c.id(), c.item(), c.label(), List.of(REMOVE_LINE), true));
            }
            if (shown < chosen.size()) {   // more picks than the row holds: they are all ticked in the grid above
                out.put(s++, new Tile("morepicks", "minecraft:paper", "+" + (chosen.size() - shown) + " more",
                        List.of(MORE_LINE), false));
            }
            for (int left = picks.most() - chosen().size(); left > 0; left--) {
                out.put(s++, new Tile("more" + left, "minecraft:light_gray_concrete",
                        left + " more", List.of("Pick one above"), false));
            }
        }
        return out;
    }

    /** The picks-row slots that only SHOW (the subject, an empty place): pressing them does nothing. */
    public java.util.Set<Integer> showOnly() {
        java.util.Set<Integer> out = new java.util.TreeSet<>();
        if (picks != null) {
            int s = picksRowSlot();
            if (picks.subject() != null) {
                out.add(s++);
            }
            if (picks.budget() != null) {
                out.add(s++);
            }
            int n = chosen().size();
            if (n > pickPlaces()) {
                s += pickPlaces() - 1;
                out.add(s++);   // "+N more"
            } else {
                s += n;
            }
            for (int left = picks.most() - n; left > 0; left--) {
                out.add(s++);
            }
        }
        return out;
    }

    public int size() {
        return rows() * COLS;
    }

    public int backSlot() {
        return (rows() - 1) * COLS;
    }

    public int nextSlot() {
        return (rows() - 1) * COLS + COLS / 2;
    }

    public int closeSlot() {
        return size() - 1;
    }

    /**
     * Slot → what pressing it means: a tile id, or {@link #BACK_ID}/{@link #NEXT_ID}/{@link #CLOSE_ID}. A Full tile
     * and a show-only slot mean nothing; a pick in the picks row means its tile (a press takes it off); a blocked
     * tile means its tile, so a press is answered with the reason.
     */
    public Map<Integer, String> slots() {
        Map<Integer, String> out = new LinkedHashMap<>();
        java.util.Set<Integer> show = showOnly();
        drawn().forEach((slot, t) -> {
            if (!show.contains(slot) && !(slot < tiles.size() && isFull(tiles.get(slot)))) {
                out.put(slot, t.id());
            }
        });
        if (back) {
            out.put(backSlot(), BACK_ID);
        }
        if (next) {
            out.put(nextSlot(), NEXT_ID);
        }
        out.put(closeSlot(), CLOSE_ID);
        return out;
    }

    /** What a press on {@code slot} means, or null for an empty slot. */
    public String at(int slot) {
        return slots().get(slot);
    }

    /** Split a long list into pages of at most {@link #MAX_TILES}. */
    public static List<List<Tile>> pages(List<Tile> all) {
        List<List<Tile>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += MAX_TILES) {
            out.add(List.copyOf(all.subList(i, Math.min(all.size(), i + MAX_TILES))));
        }
        return out.isEmpty() ? List.of(List.of()) : out;
    }
}
