package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>The watch's "📖 What do I do now?" page</b> — Minecraft-free, so its
 * slots and words are Gate A tests ({@code GuidePanelTest}), exactly as {@link WatchPanel}'s are.
 *
 * <p>The SAME 9×6 chest re-laid ({@code WatchMenu}), so a stock client sees items change and nothing
 * else. Two shapes, chosen by whether the world has a goal tree ({@code WorldGuide.hasGoals}):
 *
 * <pre>
 *   a goal tree                               no goal tree
 *   ┌───────────────────────────────┐          ┌───────────────────────────────┐
 *   │            🎯 next goal        │          │        🎨 Be creative!         │
 *   │                                │          │                                │
 *   │      ✨ help  📜 how  📖 book   │          │   ✨ a   ✨ b   ✨ c   ✨ d        │
 *   │                                │          │            📜 how              │
 *   │                                │          │                                │
 *   │      ◀ back            ✖ close │          │      ◀ back            ✖ close │
 *   └───────────────────────────────┘          └───────────────────────────────┘
 * </pre>
 *
 * <p>Opening it and reading it are free. The one press that costs is "✨ Help me with this", and it
 * costs nothing itself: it opens the compose anvil PRE-FILLED with a wish for the goal (the world's
 * own words, {@code guide.goals.help}, else "help me with &lt;goal&gt;"), which he can change or
 * confirm — and that wish then goes through the normal wish path, bank and all. So the label says
 * the price only on a world with a wish bank, and the wish asks Moddy to HELP, never to grant the
 * goal ({@code NextGoal.helpWish}).
 */
public final class GuidePanel {

    /** The watch's button. */
    public static final String BUTTON = "📖 What do I do now?";
    /** …and its one line. */
    public static final String BUTTON_LORE = "Your next goal, and ideas to try";

    /** Where the headline sits: the middle of the top row. */
    public static final int HEADLINE_SLOT = 4;
    /** The three presses under a goal, a gap between each. */
    public static final int HELP_SLOT = 2 * WatchPanel.COLS + 2;
    public static final int HOW_TO_SLOT = 2 * WatchPanel.COLS + 4;
    public static final int BOOK_SLOT = 2 * WatchPanel.COLS + 6;
    /** Starter wishes on a world with no goal tree: row three, a gap between each. */
    public static final int[] STARTER_SLOTS = {2 * WatchPanel.COLS + 1, 2 * WatchPanel.COLS + 3,
            2 * WatchPanel.COLS + 5, 2 * WatchPanel.COLS + 7};
    /** "How to use Moddy" on a world with no goal tree: under the starters. */
    public static final int HOW_TO_SLOT_CREATIVE = 3 * WatchPanel.COLS + 4;
    /** The guide book on a world with no goal tree (none today): beside "How to use Moddy". */
    public static final int BOOK_SLOT_CREATIVE = 3 * WatchPanel.COLS + 6;
    /** Back to the wishes — where "◀ Back" always sits. */
    public static final int BACK_SLOT = WatchPanel.PREV_PAGE_SLOT;
    /** 🏆 My goals: the whole goal list, on every edition — Bedrock has no L. */
    public static final int GOALS_SLOT = 3 * WatchPanel.COLS + 3;
    /** Help &amp; links: the addresses, written out, on every edition. */
    public static final int LINKS_SLOT = 3 * WatchPanel.COLS + 5;
    public static final int LINKS_SLOT_CREATIVE = 4 * WatchPanel.COLS + 4;
    public static final String GOALS_BUTTON = "🏆 My goals";
    public static final String LINKS_BUTTON = "💡 Help & links";

    /** How long a lore line may be before it wraps. */
    static final int LORE_WIDTH = 40;

    /**
     * The goal "What do I do now?" found.
     *
     * @param id    the advancement id
     * @param title its title, as the goal list shows it
     * @param hint  the hint to show (the world's own, else the advancement's description)
     * @param wish  the wish "Help me with this" pre-fills
     */
    public record Goal(String id, String title, String hint, String wish) {

        public Goal {
            id = id == null ? "" : id;
            title = title == null ? "" : title.strip();
            hint = hint == null ? "" : hint.strip();
            wish = wish == null ? "" : wish.strip();
        }
    }

    /**
     * Everything the page needs, computed by the game side.
     *
     * @param goalTree  the world has a goal tree
     * @param goal      the next goal, or null when every goal is done
     * @param price     what one wish costs here; 0 on a world with no wish bank
     * @param hasBook   the world gives a guide book
     * @param bookTitle its title
     * @param starters  wishes to suggest on a world with no goal tree (the library's starters)
     */
    public record View(boolean goalTree, Goal goal, int price, boolean hasBook, String bookTitle,
                       List<WatchPanel.PresetRow> starters) {

        public View {
            bookTitle = bookTitle == null || bookTitle.isBlank() ? "the guide book" : bookTitle.strip();
            starters = starters == null ? List.of() : List.copyOf(starters);
        }
    }

    private GuidePanel() {
    }

    /** The help button's label: the price only where wishes cost something. Pure. */
    public static String helpLabel(int price) {
        if (price <= 0) {
            return "✨ Help me with this";
        }
        return "✨ Help me with this (" + price + (price == 1 ? " wish)" : " wishes)");
    }

    /** Lay the page. Pure. */
    public static WatchPanel.Layout lay(View v) {
        List<WatchPanel.Cell> cells = new ArrayList<>();
        if (v.goalTree()) {
            Goal g = v.goal();
            if (g == null) {
                cells.add(cell(HEADLINE_SLOT, WatchPanel.Family.GUIDE, WatchPanel.Action.NONE,
                        "🏆 You have done every goal!", "", WatchIcons.ALL_DONE,
                        List.of("Type a new wish,", "or build your dream island!")));
            } else {
                List<String> lore = new ArrayList<>(List.of("Your next goal:"));
                lore.addAll(wrap(g.hint()));
                lore.add("Press " + GOALS_BUTTON + " to see them all");
                cells.add(cell(HEADLINE_SLOT, WatchPanel.Family.GUIDE, WatchPanel.Action.NONE,
                        "🎯 " + g.title(), "", WatchIcons.GOAL, lore));
                List<String> help = new ArrayList<>(List.of("Moddy can help with this goal.", "Your wish:"));
                help.addAll(wrap("\"" + g.wish() + "\""));
                help.add("You can change the words first");
                cells.add(cell(HELP_SLOT, WatchPanel.Family.GUIDE, WatchPanel.Action.HELP_WISH,
                        helpLabel(v.price()), g.wish(), WatchIcons.COMPOSE, help));
            }
            cells.add(cell(HOW_TO_SLOT, WatchPanel.Family.GUIDE, WatchPanel.Action.HOW_TO,
                    "📜 How to play", "", WatchIcons.HOW_TO, List.of("Moddy tells you in chat")));
            cells.add(cell(GOALS_SLOT, WatchPanel.Family.GUIDE, WatchPanel.Action.GOALS,
                    GOALS_BUTTON, "", WatchIcons.GOALS, List.of("Every goal: done and still to do")));
        } else {
            cells.add(cell(HEADLINE_SLOT, WatchPanel.Family.GUIDE, WatchPanel.Action.NONE,
                    "🎨 Be creative — wish for anything!", "", WatchIcons.CREATIVE,
                    List.of("Press a wish below,", "or type your own wish!")));
            int i = 0;
            for (WatchPanel.PresetRow pr : v.starters()) {
                if (i >= STARTER_SLOTS.length) {
                    break;
                }
                cells.add(WatchPanel.Cell.of(STARTER_SLOTS[i++], WatchPanel.Family.PRESET, WatchPanel.Action.WISH,
                        "✨ " + pr.label(), pr.wishText(), pr.recipeId(), "",
                        WatchIcons.forWish(pr.label(), pr.wishText(), pr.icon()), List.of(WatchClick.presetHint(false))));
            }
            cells.add(cell(HOW_TO_SLOT_CREATIVE, WatchPanel.Family.GUIDE, WatchPanel.Action.HOW_TO,
                    "📜 How to use Moddy", "", WatchIcons.HOW_TO, List.of("Moddy tells you in chat")));
        }
        if (v.hasBook()) {
            cells.add(cell(v.goalTree() ? BOOK_SLOT : BOOK_SLOT_CREATIVE, WatchPanel.Family.GUIDE, WatchPanel.Action.GUIDE_BOOK,
                    "📖 Get the " + v.bookTitle() + " book", "", WatchIcons.GUIDE_BOOK,
                    List.of("A fresh copy for your bag", "Lost it? Type /moddy guide")));
        }
        cells.add(cell(v.goalTree() ? LINKS_SLOT : LINKS_SLOT_CREATIVE, WatchPanel.Family.GUIDE, WatchPanel.Action.LINKS,
                LINKS_BUTTON, "", WatchIcons.LINKS, List.of("Where to get help,", "and how to become a Wish Magician")));
        cells.add(cell(BACK_SLOT, WatchPanel.Family.CONTROL, WatchPanel.Action.PAGE, "◀ Back to my wishes",
                "0", WatchIcons.PREV_PAGE, List.of()));
        cells.add(cell(WatchPanel.SIZE - 1, WatchPanel.Family.CONTROL, WatchPanel.Action.CLOSE, "✖ Close",
                "", WatchIcons.CLOSE, List.of("Back to your world")));
        return new WatchPanel.Layout(cells, 0, 0, 0);
    }

    /**
     * the guide for the chest a client draws. Laid out 9 wide (Java, Bedrock Classic). On Bedrock's
     * Pocket UI the chest wraps at a width nobody can know (WatchPanel.Grid), so the page becomes the same
     * flat list the panel is: ◀ Back to my wishes where the panel keeps compose (slot 0, the way out of
     * here is the first thing read), ✖ where the panel keeps it, and the page's own content in its
     * reading order (row by row, left to right) from the panel's first tile slot. Pure.
     */
    public static WatchPanel.Layout project(WatchPanel.Layout l, WatchPanel.Grid g) {
        if (g == null || !g.flat()) {
            return l;
        }
        List<WatchPanel.Cell> content = new ArrayList<>();
        List<WatchPanel.Cell> out = new ArrayList<>();
        for (WatchPanel.Cell c : l.cells()) {
            if (c.slot() == BACK_SLOT) {
                out.add(at(c, g.compose()));
            } else if (c.slot() == WatchPanel.SIZE - 1) {
                out.add(at(c, g.close()));
            } else {
                content.add(c);
            }
        }
        content.sort(java.util.Comparator.comparingInt(WatchPanel.Cell::slot));
        for (int i = 0; i < content.size(); i++) {
            out.add(at(content.get(i), WatchPanel.Grid.FLAT_FIRST_TILE + i));
        }
        return new WatchPanel.Layout(out, l.omittedPresets(), l.omittedHappening(), l.omittedCreations(),
                l.page(), l.pageCount());
    }

    /**
     * where the Magician's "Typed wishes: Magician ✪ / Apprentice ✨" toggle sits, on the fifth row, clear of
     * every other cell of either page shape. The 📖 page and not the panel: the panel's control row has no free
     * slot its own rule allows (see {@link WatchPanel#BUILDER_SLOT}).
     */
    public static final int MODE_SLOT = 4 * WatchPanel.COLS + 2;

    /** The page with the mode toggle added. Pure. */
    public static WatchPanel.Layout withMode(WatchPanel.Layout l, String label, List<String> lore) {
        List<WatchPanel.Cell> cells = new ArrayList<>(l.cells());
        cells.add(cell(MODE_SLOT, WatchPanel.Family.GUIDE, WatchPanel.Action.WISH_MODE, label, "", WatchIcons.WISH_MODE, lore));
        return new WatchPanel.Layout(cells, l.omittedPresets(), l.omittedHappening(), l.omittedCreations(),
                l.page(), l.pageCount());
    }

    private static WatchPanel.Cell at(WatchPanel.Cell c, int slot) {
        return new WatchPanel.Cell(slot, c.family(), c.action(), c.label(), c.arg(), c.recipeId(), c.seed(),
                c.icon(), c.lore());
    }

    private static WatchPanel.Cell cell(int slot, WatchPanel.Family f, WatchPanel.Action a, String label,
                                        String arg, String icon, List<String> lore) {
        return WatchPanel.Cell.of(slot, f, a, label, arg, "", "", icon, lore);
    }

    /** Text as lore lines of at most {@link #LORE_WIDTH} characters. Pure. */
    static List<String> wrap(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : (text == null ? "" : text).replace('\n', ' ').split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            if (line.length() > 0 && line.length() + 1 + word.length() > LORE_WIDTH) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }
}
