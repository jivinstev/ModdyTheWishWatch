package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>The watch, as a grid of things to press — Minecraft-free, so the LAYOUT is unit-tested.</b>
 *
 * <p>This is the vanilla-client substitute for {@code WishScreen}'s scrolling list. A vanilla client
 * has no mod screen, but it renders a vanilla container menu perfectly well, so the panel is a 9×6
 * chest whose slots are presses rather than items. Everything about <i>which</i> slot means
 * <i>what</i> is a question about a list, needs no game, and is answered here in microseconds —
 * exactly the split {@code BarBook} already makes for progress bars, and for the same reason: the
 * bugs in a panel are off-by-ones and precedence, not rendering.
 *
 * <h2>Why presets come FIRST, when the mod screen puts them last</h2>
 *
 * <p>{@code WishScreen} scrolls, and pins its controls <i>below</i> the scroll area so they are
 * always reachable however long the history gets. A chest menu cannot scroll, so the same guarantee
 * has to be bought a different way: the section a child most needs is placed where it can never be
 * pushed off. <b>Pressing a preset is the whole zero-typing path</b> — a seven-year-old types about
 * eight words a minute — so a layout where forty creations can displace the buttons is a layout
 * that takes wishing away from the child who needs it most. Presets first is the same decision as
 * pinning the controls, expressed in the only way a grid allows.
 *
 * <p>Everything else keeps the screen's order: what is happening now, then his things. What does
 * not fit on a page flows onto the next, behind "More wishes ▶".
 *
 * <h2>The controls are pinned to the last row, and to its two ends</h2>
 *
 * <p>Close and 👎 sit at opposite corners of the bottom row. Adjacent would put "that wasn't right"
 * one slot from "leave", and a misclick between those two is not a misclick a child can undo.
 */
public final class WatchPanel {

    public static final int COLS = 9;
    public static final int ROWS = 6;
    public static final int SIZE = COLS * ROWS;

    /** The last row is controls only, so Close is always in the same place. */
    public static final int CONTROL_ROW_START = (ROWS - 1) * COLS;

    /**
     * <b>Where the flowing sections may go — everything above the control row, LESS one buffer row.</b>
     *
     * <p>The buffer exists because the control row is the one row a child must never hit by
     * accident: it holds "that was wrong", "leave" and the only way to wish for something new. A
     * history that grew right up against it would put a re-makeable creation directly above
     * "leave", and the two are one row apart on a screen a seven-year-old is clicking quickly.
     * Costing nine slots to keep the destructive row isolated is the right trade.
     */
    public static final int FLOW_SLOTS = CONTROL_ROW_START - COLS;

    /**
     * <b>How many grid lines a page offers the flowing sections</b> — everything above the buffer
     * row. Four: presets, then what is happening, then his things, share these, and
     * whatever does not fit flows onto the next page behind "More wishes ▶".
     */
    public static final int LINES_PER_PAGE = FLOW_SLOTS / COLS;

    /** Where "◀ Back" sits: two slots in from 👎, two short of Compose. */
    public static final int PREV_PAGE_SLOT = CONTROL_ROW_START + 2;

    /** Where "More wishes ▶" sits: two slots in from Close, two past Compose. */
    public static final int NEXT_PAGE_SLOT = CONTROL_ROW_START + 6;

    /** The 👎 tile's lore: what happens to the thing, then what happens to the wish. */
    public static final String REJECT_LORE_TAKES = "Moddy takes the last one away";
    public static final String REJECT_LORE_BACK = "and you get that wish back";

    /**
     * Where "📖 What do I do now?" sits: between Compose and "More wishes ▶". The control
     * row's rule is that a DESTRUCTIVE press (👎, ✖ Close) is never next to another press; the guide
     * and both of its neighbours only open or turn something, and a misclick between them costs a
     * glance. Slots +1 and +7 would put it beside 👎 or Close, which is the one thing the row forbids.
     */
    public static final int GUIDE_SLOT = CONTROL_ROW_START + COLS / 2 + 1;

    /**
     * Where "🛠 Wish Builder" sits: between ◀ Back and Compose — the ONE free slot of the row
     * the rule allows (+1 and +7 are beside 👎 and Close). On the flat Pocket grid it is the first tile
     * slot, because the free control slot there (3) sits between Close and 👎.
     */
    public static final int BUILDER_SLOT = CONTROL_ROW_START + COLS / 2 - 1;

    public static final String BUILDER_LABEL = "✦ Wish Builder";   // ✦ draws on Bedrock (BedrockGlyphs.DRAWS); 🛠 is a box

    /** Whether this world's Wish Builder is on — set at boot to the manifest's answer (BuilderSettings). */
    public static volatile java.util.function.BooleanSupplier builderOn = () -> false;

    /** What pressing a slot does. */
    public enum Action {
        /** Nothing — an empty cell. Never sent to the server as a press. */
        NONE,
        /** Submit this preset's wish text, naming its recipe. */
        WISH,
        /** Go and look at a wish being granted right now. */
        GO,
        /** Open the card for one of his things. */
        CARD,
        /** "That wasn't right!" */
        REJECT,
        /**
         * <b>Wish for something NEW — free text, no typing a slash command.</b>
         *
         * <p>The panel shipped with presets and re-makes only, which means the one thing the whole
         * product is for -- wishing for something nobody thought of first -- was reachable ONLY by
         * opening chat and typing {@code /wish <text>}. A seven-year-old opening a console is not a
         * zero-install path in any useful sense. This opens a vanilla anvil as a text field.
         */
        COMPOSE,
        /** Shut the panel. */
        CLOSE,
        /**
         * <b>🛠 The Wish Builder</b>: make something by picking, no typing and no model. Shown
         * only on a world whose Builder is on ({@link #builderOn}).
         */
        BUILDER,
        /**
         * <b>Turn the page</b> — {@code arg} is the page to show. The SAME chest is
         * re-laid, so a stock client sees its items change and nothing else.
         */
        PAGE,
        /**
         * <b>"📖 What do I do now?"</b> — re-lays the SAME chest as the
         * guide ({@link GuidePanel}): his next goal and help with it on a world with a goal tree, ideas
         * to wish for everywhere else. Opening it is free.
         */
        GUIDE,
        /** On the guide: open the compose anvil PRE-FILLED with {@code arg}, a wish he can edit. */
        HELP_WISH,
        /** On the guide: say this world's intro and "how to use Moddy" in chat ({@code /moddy help}). */
        HOW_TO,
        /** On the guide: give a fresh copy of the world's guide book ({@code /moddy guide}). */
        GUIDE_BOOK,
        /**
         * <b>✋ Stop my wish</b> (2026-11-10, a Bedrock design decision): while one of his wishes is
         * being made, 👎's slot stops it — {@code /moddy stop}'s own path. Replaces the mid-wish "[✋ stop]"
         * chat link, which a Bedrock phone popped up as a form over the wish arriving (D1).
         */
        STOP,
        /**
         * <b>↩ Take me back</b>: after "Go see it" carried him somewhere, the first tile takes him home —
         * {@code /moddy back}'s path. Replaces the "[take me back]" chat link (D1, converged).
         */
        BACK_HOME,
        /** On the guide: the world's goals, done and to do ({@code GoalsPage}) — every edition. */
        GOALS,
        /** On the guide: "Become a Wish Magician", help, report ({@code HelpLinks}) — every edition. */
        LINKS,
        /**
         * On the guide, a Magician's only: "Typed wishes: Magician ✪ / Apprentice ✨". A press flips who
         * answers his typed wishes and re-lays the page. LAST in this enum on purpose: nothing may renumber the
         * actions before it.
         */
        WISH_MODE
    }

    /** Which family a cell belongs to — decides the icon, and nothing else. */
    public enum Family { PRESET, HAPPENING, CREATION, CONTROL, GUIDE }

    /**
     * One pressable cell.
     *
     * <p>The three payload fields are NAMED rather than positional because two of them are things
     * the mod screen carries and a naive substitute silently drops — and both drops are invisible.
     *
     * @param arg      the wish text, the short id or the artifact id — whatever the action needs
     * @param recipeId <b>load-bearing.</b> A button that names its recipe is served that exact
     *                 design with no matching and no gates; one submitted without it is re-derived
     *                 from its own label, and {@code WishService.submit}'s own comment records what
     *                 that cost — a taught recipe escalated to the agent on every press, so the
     *                 child waited a full round trip for a design he already owned. On a
     *                 CREATION cell it is the taught recipe that replays that very thing (
     *                 {@code TaughtJoin}): its card's [Make another one] runs it, and a
     *                 LEFT-click on the tile runs it too ({@link WatchClick}) — right-click opens
     *                 the card
     * @param seed     the phrase GUARANTEED to resolve to this thing (the recipe's own terms).
     *                 {@code WishCardScreen} seeds its box with this rather than with the display
     *                 name, precisely because the name is what failed to separate his things
     * @param lore     extra tooltip lines, already rendered as strings (the panel does no
     *                 arithmetic, for the reason {@code WatchRow} gives)
     */
    public record Cell(int slot, Family family, Action action, String label, String arg,
                       String recipeId, String seed, String icon, List<String> lore) {

        public Cell {
            label = label == null ? "" : label;
            icon = icon == null || icon.isBlank() ? WatchIcons.UNKNOWN : icon;
            arg = arg == null ? "" : arg;
            recipeId = recipeId == null ? "" : recipeId;
            seed = seed == null ? "" : seed;
            lore = lore == null ? List.of() : List.copyOf(lore);
        }

        /** Can this thing be made again from its own recipe, with no matching at all? */
        public boolean canRemake() {
            return !recipeId.isEmpty();
        }

        /**
         * <b>The ICON is chosen here, in the pure layer, because this is where the KIND is.</b>
         *
         * <p>The menu used to pick one item per {@code Family}, which made the panel six identical
         * shards over six identical books. Moving the choice up to the layout is not tidying: by
         * the time a cell reaches the menu its {@code WatchRow.Creation} -- and with it the only
         * thing that says whether it is a creature or a castle -- is gone. The alternative on offer
         * was reading the kind back out of {@code lore[0]}, which happens to be true today and
         * couples the icons to the order of a tooltip.
         */
        static Cell of(int slot, Family f, Action a, String label, String arg, String recipeId,
                       String seed, String icon, List<String> lore) {
            return new Cell(slot, f, a, label, arg, recipeId, seed, icon, lore);
        }
    }

    /**
     * What one page of the panel contains, and what it had to leave out.
     *
     * <p>With paging nothing has to be left out, so the three {@code omitted*} counts are zero from
     * {@link WatchPanel#lay}; they stay so a caller that ever caps a section reports it rather than
     * dropping it silently.
     */
    public record Layout(List<Cell> cells, int omittedPresets, int omittedHappening,
                         int omittedCreations, int page, int pageCount) {

        public Layout {
            cells = cells == null ? List.of() : List.copyOf(cells);
            pageCount = Math.max(1, pageCount);
        }

        public Layout(List<Cell> cells, int omittedPresets, int omittedHappening,
                      int omittedCreations) {
            this(cells, omittedPresets, omittedHappening, omittedCreations, 0, 1);
        }

        /** The cell at a slot, or null. */
        public Cell at(int slot) {
            for (Cell c : cells) {
                if (c.slot() == slot) {
                    return c;
                }
            }
            return null;
        }

        public boolean droppedAnything() {
            return omittedPresets + omittedHappening + omittedCreations > 0;
        }
    }

    /**
     * A preset, flattened out of {@code WishCatalog.Preset} so this class stays Minecraft-free.
     *
     * @param icon the vanilla item this button NAMES for itself, or {@code ""} to let
     *             {@link WatchIcons} scan its words. The three-argument form is the
     *             scan-my-words case and is what every built-in uses.
     */
    public record PresetRow(String label, String wishText, String recipeId, String icon,
                            boolean starter, long updatedAt, List<String> parts) {

        public PresetRow {
            parts = parts == null ? List.of() : List.copyOf(parts);
        }

        public PresetRow(String label, String wishText, String recipeId, String icon,
                         boolean starter, long updatedAt) {
            this(label, wishText, recipeId, icon, starter, updatedAt, List.of());
        }

        /** The same button, the WISH entry for these parts: pressing it makes all of them. */
        public PresetRow withParts(List<String> names) {
            return new PresetRow(label, wishText, recipeId, icon, starter, updatedAt, names);
        }

        /**
         * A LIBRARY STARTER — no owner and no creation time, so it is laid after all of his own
         * things. The shape every caller built before his own buttons were told apart.
         */
        public PresetRow(String label, String wishText, String recipeId, String icon) {
            this(label, wishText, recipeId, icon, true, 0L);
        }

        public PresetRow(String label, String wishText, String recipeId) {
            this(label, wishText, recipeId, "");
        }

        /** One of HIS taught buttons, last taught at {@code updatedAt} (0 = unknown). */
        public static PresetRow his(String label, String wishText, String recipeId, String icon,
                                    long updatedAt) {
            return new PresetRow(label, wishText, recipeId, icon, false, updatedAt);
        }
    }

    private WatchPanel() {
    }

    /**
     * <b>The chest the panel is laid out in</b>. Java's chest is 9 wide, and so is Bedrock's
     * Classic UI. Bedrock's <b>Pocket UI</b> (a phone's default) wraps the same 54-slot chest at however
     * many columns fit the SCREEN — measured on a real client 2026-11-08: <b>7</b> on one phone,
     * <b>6</b> at 2400×1080, <b>5</b> at 854×480 — and nothing the client sends says which. A 2-D layout
     * cannot survive that (the 9-wide one scattered its control row and a player could not find "Wish for
     * something new"; a 7-wide one scattered the same way at 5 and 6). So a Pocket player gets a
     * <b>flat</b> layout, whose meaning is its reading ORDER, true at every width:
     * <pre>
     *   0 ✍ Wish for something new · 1 ❓ guide · 2 ✖ close · 3 (gap) · 4 👎   — the first line at ANY width ≥ 5
     *   5 … 51  the tiles: what is happening, his things newest first, the starters (no padding)
     *   52 ◀ · 53 ▶  paging, at the END of the list, where a scrolling list ends
     * </pre>
     *
     * @param cols  the chest's width as the client draws it (flat: the narrowest width measured)
     * @param rows  the rows a layout uses (the control row is the last); unused when flat
     * @param flat  lay the panel out by reading order rather than by rows and columns
     */
    public record Grid(int cols, int rows, int reject, int prev, int compose, int guide, int next, int close,
                       int builder, boolean flat) {
        /** Java, and Bedrock's Classic UI: exactly the layout. */
        public static final Grid JAVA = new Grid(COLS, ROWS, CONTROL_ROW_START, PREV_PAGE_SLOT,
                CONTROL_ROW_START + COLS / 2, GUIDE_SLOT, NEXT_PAGE_SLOT, SIZE - 1, BUILDER_SLOT, false);
        /** Bedrock's Pocket UI: the flat layout above. */
        public static final Grid POCKET = new Grid(5, 0, 4, SIZE - 2, 0, 1, SIZE - 1, 2, 5, true);
        /** Flat: the first tile's slot, and how many tiles a page holds (up to the paging pair). */
        public static final int FLAT_FIRST_TILE = 5;
        public static final int FLAT_TILES = SIZE - 2 - FLAT_FIRST_TILE;

        public int controlRowStart() {
            return (rows - 1) * cols;
        }

        /** Grid lines a page offers the flowing sections: everything above the buffer row. */
        public int linesPerPage() {
            return (controlRowStart() - cols) / cols;
        }
    }

    /** Page one of the panel. */
    /** The control 👎's slot becomes while one of his wishes is being made. */
    public static final String STOP_LABEL = "✋ Stop my wish";

    public static Layout lay(List<PresetRow> presets, List<WatchRow.Happening> happening,
                             List<WatchRow.Creation> creations) {
        return lay(presets, happening, creations, 0);
    }

    /**
     * <b>Lay one PAGE of the panel out</b>.
     *
     * <p>The panel is ONE flow of grid lines, packed {@link #LINES_PER_PAGE} to a page:
     * <ol>
     *   <li>what is <b>happening</b> right now, from slot 0;</li>
     *   <li><b>his things</b>, starting on a fresh line: his creations and his ORPHAN taught
     *       wishes ({@code WishPresets.forPanel}) in ONE sort, newest first ({@link NewestFirst},
     *). A taught wish whose thing is on the panel is not here: that thing's creation
     *       tile carries its recipe (one tile per thing he made, {@code TaughtJoin});</li>
     *   <li>the library's <b>starters</b>, in their curated order, starting on a fresh line —
     *       after all of his own things, because they are not his and have no creation time.</li>
     * </ol>
     *
     * <p>A section never starts halfway along a line, where the eye does not look for it; a
     * section MAY run on from one page to the next, because the lines are one flow. There are no
     * row headers: the operator controls how much of page one the presets take by how many starter
     * wishes they promote. Every preset, happening and creation is reachable on SOME page through
     * "More wishes ▶", so nothing is omitted — a panel that quietly shows nine of a child's forty
     * things looks exactly like a child with nine things.
     *
     * <p>Paging RE-LAYS THE SAME CHEST ({@code WatchMenu}), so a stock client sees items change in
     * a vanilla 9×6 container and nothing else.
     *
     * @param page the page to lay, clamped into range
     */
    public static Layout lay(List<PresetRow> presets, List<WatchRow.Happening> happening,
                             List<WatchRow.Creation> creations, int page) {
        return lay(presets, happening, creations, page, Grid.JAVA);
    }

    /** As {@link #lay(List, List, List, int)}, for the chest a client draws. */
    public static Layout lay(List<PresetRow> presets, List<WatchRow.Happening> happening,
                             List<WatchRow.Creation> creations, int page, Grid g) {
        return lay(presets, happening, creations, page, g, false);
    }

    /**
     * …and {@code wayBack}: he was carried somewhere by "Go see it" and can still go back, so "↩ Take me
     * back" leads the tiles (it replaced the "[take me back]" chat link, 2026-11-10).
     */
    public static Layout lay(List<PresetRow> presets, List<WatchRow.Happening> happening,
                             List<WatchRow.Creation> creations, int page, Grid g, boolean wayBack) {
        return lay(presets, happening, creations, page, g, wayBack, builderOn.getAsBoolean());
    }

    /** …with whether the Builder is on for THIS viewer ({@code BuilderSettings.forPlayer}). */
    public static Layout lay(List<PresetRow> presets, List<WatchRow.Happening> happening,
                             List<WatchRow.Creation> creations, int page, Grid g, boolean wayBack, boolean builder) {
        final int cols = g.cols();
        List<PresetRow> all = presets == null ? List.<PresetRow>of() : presets;
        Maker<PresetRow> presetCell = pr -> Cell.of(0, Family.PRESET, Action.WISH,
                "✨ " + pr.label(), pr.wishText(), pr.recipeId(), "",
                WatchIcons.forWish(pr.label(), pr.wishText(), pr.icon()), presetLore(pr));

        // EVERY WISH HISTORY IS NEWEST FIRST. What is happening now
        // leads; then HIS things — his creations and his own taught buttons in ONE sort, newest
        // first, so page one starts with the thing he made last; then the library's starters,
        // which have no creation time and are not his. Starters used to lead page one.
        List<List<Cell>> flow = new ArrayList<>();
        // ↩ TAKE ME BACK first, when there is a way back: a child far from home looks for it before anything.
        List<WatchRow.Happening> live = happening == null ? List.<WatchRow.Happening>of() : happening;
        List<Cell> top = new ArrayList<>();
        if (wayBack) {
            top.add(Cell.of(0, Family.HAPPENING, Action.BACK_HOME, "↩ Take me back", "", "", "",
                    WatchIcons.BACK_HOME, List.of("Back to where you were", "Press to go back")));
        }
        for (WatchRow.Happening h : live) {
            top.add(Cell.of(0, Family.HAPPENING, Action.GO,
                    "⏳ " + h.subject() + (h.percent() > 0 ? "  (" + h.percent() + "%)" : ""),
                    h.shortId(), "", "", WatchIcons.forKind(h.kind()),
                    List.of("Moddy is making this now", "Press to go and see it")));
        }
        flow.addAll(lines(cols, top, c -> c));
        List<Timed> mine = new ArrayList<>();
        for (WatchRow.Creation c : creations == null ? List.<WatchRow.Creation>of() : creations) {
            mine.add(new Timed(c.updatedAt(), Cell.of(0, Family.CREATION, Action.CARD,
                    c.name() + (c.inFlight() ? "  ✨" : ""), c.id(), c.recipeId(),
                    c.seedPhrase(),
                    // What it IS when the server knows, else what kind of thing it is.
                    c.icon().isBlank() ? WatchIcons.forKind(c.kind()) : c.icon(),
                    cardLore(c))));
        }
        for (PresetRow pr : all) {
            if (!pr.starter()) {
                mine.add(new Timed(pr.updatedAt(), presetCell.make(pr)));
            }
        }
        flow.addAll(lines(cols, NewestFirst.sort(mine, Timed::when), Timed::cell));
        flow.addAll(lines(cols, all.stream().filter(PresetRow::starter).toList(), presetCell));

        int pageCount;
        int p;
        List<Cell> cells = new ArrayList<>();
        if (g.flat()) {
            // Reading order only: the same sections in the same order, never padded to a line, because
            // on a chest of unknown width a "line" means nothing (see Grid).
            List<Cell> tiles = new ArrayList<>();
            flow.forEach(tiles::addAll);
            // the Builder takes the first tile slot when it is on, and the tiles move up one.
            int firstTile = Grid.FLAT_FIRST_TILE + (builder ? 1 : 0);
            int perPage = Grid.FLAT_TILES - (builder ? 1 : 0);
            pageCount = Math.max(1, (tiles.size() + perPage - 1) / perPage);
            p = Math.max(0, Math.min(page, pageCount - 1));
            int first = p * perPage;
            for (int i = 0; i < perPage && first + i < tiles.size(); i++) {
                Cell c = tiles.get(first + i);
                cells.add(new Cell(firstTile + i, c.family(), c.action(), c.label(), c.arg(),
                        c.recipeId(), c.seed(), c.icon(), c.lore()));
            }
        } else {
            final int linesPerPage = g.linesPerPage();
            pageCount = Math.max(1, (flow.size() + linesPerPage - 1) / linesPerPage);
            p = Math.max(0, Math.min(page, pageCount - 1));
            int first = p * linesPerPage;
            for (int line = 0; line < linesPerPage && first + line < flow.size(); line++) {
                List<Cell> l = flow.get(first + line);
                for (int col = 0; col < l.size(); col++) {
                    Cell c = l.get(col);
                    cells.add(new Cell(line * cols + col, c.family(), c.action(), c.label(), c.arg(),
                            c.recipeId(), c.seed(), c.icon(), c.lore()));
                }
            }
        }

        // WISH FOR SOMETHING NEW, in the MIDDLE of the control row.
        //
        // Centre rather than beside the others on purpose: it is the primary action of the whole
        // panel -- everything above it is a shortcut to a wish somebody already made -- and it sits
        // four slots clear of both controls, so the row reads "no ... NEW ... leave" rather than a
        // cluster a small hand can mis-hit.
        if (builder) {
            cells.add(Cell.of(g.builder(), Family.CONTROL, Action.BUILDER, BUILDER_LABEL, "", "", "",
                    WatchIcons.BUILDER, List.of("Make something by picking", "No typing: half a wish")));
        }
        cells.add(Cell.of(g.compose(), Family.CONTROL, Action.COMPOSE,
                "✍ Wish for something new!", "", "", "", WatchIcons.COMPOSE,
                List.of("Type anything you can imagine", "Moddy will try to make it")));

        // Pinned, and at opposite ends: see the class note.
        //
        // The lore SAYS WHAT HAPPENS rather than what the button is for, because a child pressing
        // this one is unhappy and the question in his head is "will it be gone". It is also the
        // sentence Moddy already speaks when he offers this -- WishFeedback: "Don't like it? Open
        // me up and press the 👎 button and I'll take it away." -- and the two saying different
        // things is how a promise stops being one.
        //
        // The second line is the refund (2026-10-14; made 👎 give the wish
        // back to his bank, once). It is worded to be true on EVERY world, because this lore list is
        // one list and the panel does not know the world: on a bank world the wish goes back in the
        // bank; on a world with no bank, wishes never run out, so he still has it.
        // ✋ STOP while one of his wishes is being made, 👎 otherwise — one slot, never both (2026-11-10:
        // stopping lives on the watch on every edition, not in a chat link).
        if (!live.isEmpty()) {
            cells.add(Cell.of(g.reject(), Family.CONTROL, Action.STOP, STOP_LABEL, "", "", "",
                    WatchIcons.STOP, List.of("Moddy stops making it", "Press to stop")));
        } else {
            cells.add(Cell.of(g.reject(), Family.CONTROL, Action.REJECT,
                    "👎 That wasn't right!", "", "", "", WatchIcons.REJECT,
                    List.of(REJECT_LORE_TAKES, REJECT_LORE_BACK)));
        }
        cells.add(Cell.of(g.close(), Family.CONTROL, Action.CLOSE, "✖ Close", "", "", "",
                WatchIcons.CLOSE, List.of("Back to your world")));
        // On EVERY world, every page — the question a child asks most has one button.
        cells.add(Cell.of(g.guide(), Family.CONTROL, Action.GUIDE, GuidePanel.BUTTON, "", "", "",
                WatchIcons.GUIDE, List.of(GuidePanel.BUTTON_LORE)));

        // PAGING. Each sits two slots from an exit and two from Compose, so the control
        // row reads "no . back . NEW . more . leave" with a gap between every pair.
        if (p > 0) {
            cells.add(Cell.of(g.prev(), Family.CONTROL, Action.PAGE, "◀ Back",
                    Integer.toString(p - 1), "", "", WatchIcons.PREV_PAGE,
                    List.of("Page " + p + " of " + pageCount)));
        }
        if (p < pageCount - 1) {
            cells.add(Cell.of(g.next(), Family.CONTROL, Action.PAGE, "More wishes ▶",
                    Integer.toString(p + 1), "", "", WatchIcons.NEXT_PAGE,
                    List.of("Page " + (p + 2) + " of " + pageCount)));
        }

        return new Layout(cells, 0, 0, 0, p, pageCount);
    }

    /**
     * A button's lines. A WISH of several parts says what it makes, so the two levels read on the
     * panel itself: this one makes all of them, each part's own tile makes only that part.
     */
    static List<String> presetLore(PresetRow pr) {
        if (pr.parts().size() < 2) {
            return List.of(WatchClick.presetHint(false));
        }
        List<String> lore = new ArrayList<>();
        lore.add("Makes all of it:");
        int shown = 0;
        for (String part : pr.parts()) {
            if (shown++ >= MAX_PART_LINES) {
                lore.add("  …and " + (pr.parts().size() - MAX_PART_LINES) + " more");
                break;
            }
            lore.add("  • " + part);
        }
        lore.add(WatchClick.presetHint(true));
        return lore;
    }

    /** How many parts a wish button lists before "…and N more". */
    static final int MAX_PART_LINES = 6;

    /** The card's own lines, so a thing's numbers are readable without a second screen. */
    private static List<String> cardLore(WatchRow.Creation c) {
        List<String> lore = new ArrayList<>();
        if (!c.partOf().isEmpty()) {
            // the other level — this is ONE PART of a bigger wish, and its own "Make
            // another one" makes only this part.
            lore.add("Part of: " + String.join(", ", c.partOf()));
        }
        if (!c.kind().isEmpty()) {
            lore.add(c.kind());
        }
        lore.addAll(c.vitals());
        if (!c.powers().isEmpty()) {
            lore.add(String.join(", ", c.powers()));
        }
        // what each button does — left remakes it when it can be, right opens the card.
        lore.add(WatchClick.creationHint(!c.recipeId().isEmpty()));
        return lore;
    }

    /** A cell of his, and when its thing was made or last changed. */
    private record Timed(long when, Cell cell) {
    }

    private interface Maker<T> {
        Cell make(T row);
    }

    /** One section as whole grid lines — a section never starts halfway along a line. */
    private static <T> List<List<Cell>> lines(int cols, List<T> rows, Maker<T> maker) {
        List<List<Cell>> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += cols) {
            List<Cell> line = new ArrayList<>();
            for (T row : rows.subList(i, Math.min(rows.size(), i + cols))) {
                line.add(maker.make(row));
            }
            out.add(line);
        }
        return out;
    }
}
