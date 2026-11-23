package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// ── TRANSITIONAL — watch spike. Not production. See docs/VANILLA_WATCH_SPIKE_RESULTS.md

/**
 * The panel's layout rules, asked without a game.
 *
 * <p>Every failure these guard is silent on a real client: a preset pushed off the grid by a summer
 * of creations is a child who can no longer wish without typing, and nothing about the screen says
 * so — it just has fewer buttons than it used to.
 */
class WatchPanelTest {

    private static List<WatchPanel.PresetRow> presets(int n) {
        List<WatchPanel.PresetRow> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new WatchPanel.PresetRow("Preset " + i, "wish " + i, "recipe" + i));
        }
        return out;
    }

    private static List<WatchRow.Creation> creations(int n) {
        List<WatchRow.Creation> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new WatchRow.Creation("id" + i, "creature", "Thing " + i, List.of(),
                    List.of("❤️ 20 hearts"), "", List.of(), "", 1, false, List.of(), "r" + i, ""));
        }
        return out;
    }

    private static List<WatchRow.Happening> happening(int n) {
        List<WatchRow.Happening> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new WatchRow.Happening("sh" + i, "Subject " + i, 40 + i, "wish"));
        }
        return out;
    }

    @Test
    @DisplayName("every cell is inside the grid and no two share a slot")
    void cellsAreWellPlaced() {
        WatchPanel.Layout l = WatchPanel.lay(presets(20), happening(3), creations(30));
        List<Integer> seen = new ArrayList<>();
        for (WatchPanel.Cell c : l.cells()) {
            assertTrue(c.slot() >= 0 && c.slot() < WatchPanel.SIZE,
                    "slot " + c.slot() + " is off the grid");
            assertTrue(seen.add(c.slot()) && seen.indexOf(c.slot()) == seen.size() - 1,
                    "two cells claim slot " + c.slot());
            assertEquals(1, seen.stream().filter(s -> s == c.slot()).count(),
                    "two cells claim slot " + c.slot());
        }
    }

    @Test
    @DisplayName("Close and 👎 are always in the same place, and never next to each other")
    void controlsArePinnedAndFarApart() {
        for (int n : new int[] {0, 6, 40, 200}) {
            WatchPanel.Layout l = WatchPanel.lay(presets(6), happening(0), creations(n));
            WatchPanel.Cell close = l.at(WatchPanel.SIZE - 1);
            WatchPanel.Cell reject = l.at(WatchPanel.CONTROL_ROW_START);
            assertNotNull(close, "no Close with " + n + " creations");
            assertNotNull(reject, "no 👎 with " + n + " creations");
            assertEquals(WatchPanel.Action.CLOSE, close.action());
            assertEquals(WatchPanel.Action.REJECT, reject.action());
            // A misclick between "leave" and "that was wrong" is not one a child can undo.
            assertTrue(Math.abs(close.slot() - reject.slot()) > 1, "controls are adjacent");
        }
    }

    /**
     * <b>The 👎 tile says the wish comes back</b>. made 👎 an
     * undo that refunds the wish once; the tooltip promised only the first half. One lore list for
     * every world, so it must be true on a world with no bank too, stay tooltip-short, and pass
     * the child-safety lexicon like every other word a child reads.
     */
    @Test
    @DisplayName("the 👎 tile says the thing goes AND the wish comes back")
    void rejectLoreSaysTheWishComesBack() {
        WatchPanel.Cell reject = WatchPanel.lay(presets(6), happening(0), creations(3))
                .at(WatchPanel.CONTROL_ROW_START);
        assertEquals(List.of("Moddy takes the last one away", "and you get that wish back"),
                reject.lore());
        var lex = com.moddingfromamod.safety.SafetyLexicon.shared();
        for (String line : reject.lore()) {
            assertTrue(line.length() <= 32, "too long for a tooltip line: " + line);
            assertTrue(lex.check(line).isEmpty(), "the lexicon flags: " + line);
        }
    }

    /**
     * <b>A summer of creations pages the starters, it never drops them.</b> Since his own things lead and the library's starters come after all of them — so a
     * child with a summer of creations finds the starters a few pages on, and every one is still
     * there to press. ("Wish for something new" is pinned on every page regardless.)
     */
    @Test
    @DisplayName("a summer of creations pages the starters on, and never drops one")
    void presetsSurviveAFullHistory() {
        WatchPanel.Layout l = WatchPanel.lay(presets(6), happening(4), creations(64));
        assertEquals(WatchPanel.Family.HAPPENING, l.at(0).family(), "what is happening leads");
        assertEquals(WatchPanel.Family.CREATION, l.at(WatchPanel.COLS).family(),
                "his things follow, before any starter");
        long presetCells = allPages(presets(6), happening(4), creations(64)).stream()
                .flatMap(p -> p.cells().stream())
                .filter(c -> c.family() == WatchPanel.Family.PRESET).count();
        assertEquals(6, presetCells, "a starter was dropped rather than paged");
        assertEquals(0, l.omittedPresets());
        // 64 creations no longer fall off the end — they page. Nothing is omitted, and the
        // panel SAYS there is more.
        assertEquals(0, l.omittedCreations());
        assertTrue(l.pageCount() > 1, "64 creations should need a second page");
        assertNotNull(l.at(WatchPanel.NEXT_PAGE_SLOT), "no 'More wishes ▶' with more to show");
    }

    @Test
    @DisplayName("nothing is silently omitted: an overflow PAGES, and the counts stay honest at zero")
    void overflowPagesRatherThanBeingSwallowed() {
        List<WatchPanel.Layout> pages = allPages(presets(80), happening(12), creations(90));
        for (WatchPanel.Layout l : pages) {
            // A panel quietly showing nine of a child's forty things looks exactly like a child
            // with nine things — so nothing is omitted, and nothing needs to be.
            assertFalse(l.droppedAnything(), "page " + l.page() + " reports an omission");
        }
        assertEquals(80, count(pages, WatchPanel.Family.PRESET));
        assertEquals(12, count(pages, WatchPanel.Family.HAPPENING));
        assertEquals(90, count(pages, WatchPanel.Family.CREATION));
    }

    @Test
    @DisplayName("each section starts on a fresh row")
    void sectionsAreRowAligned() {
        WatchPanel.Layout l = WatchPanel.lay(presets(4), happening(2), creations(3));
        for (WatchPanel.Family f : List.of(WatchPanel.Family.PRESET, WatchPanel.Family.HAPPENING,
                WatchPanel.Family.CREATION)) {
            int first = l.cells().stream().filter(c -> c.family() == f)
                    .mapToInt(WatchPanel.Cell::slot).min().orElse(-1);
            assertTrue(first >= 0, f + " missing");
            assertEquals(0, first % WatchPanel.COLS, f + " starts mid-row at " + first);
        }
    }

    /**
     * <b>The recipe id is not decoration.</b> {@code WishService.submit} serves a named recipe
     * exactly, with no matching and no gates; a preset that arrives without its id is re-derived
     * from its own label and can be escalated to the agent for a design the child already owns.
     * A panel that dropped the id would look identical and cost him a full round trip.
     */
    @Test
    @DisplayName("a preset cell carries BOTH its wish text and its recipe id")
    void presetsCarryTheirRecipe() {
        WatchPanel.Layout l = WatchPanel.lay(
                List.of(new WatchPanel.PresetRow("Rain chickens!", "make it rain chickens", "t7")),
                List.of(), List.of());
        WatchPanel.Cell c = l.cells().stream()
                .filter(x -> x.family() == WatchPanel.Family.PRESET).findFirst().orElseThrow();
        assertEquals("make it rain chickens", c.arg(), "the wish text is what gets submitted");
        assertEquals("t7", c.recipeId(), "the recipe id is what stops it being re-matched");
        assertEquals(WatchPanel.Action.WISH, c.action());
    }

    @Test
    @DisplayName("an empty world still offers every preset and all four controls")
    void anEmptyWorldIsStillUsable() {
        WatchPanel.Layout l = WatchPanel.lay(presets(6), List.of(), List.of());
        // THREE controls now: reject, close, and "wish for something new". The third is the one
        // that matters on a brand-new world -- a child with no history and no preset he likes has
        // exactly one way left to make a wish, and before it existed that way was the chat console.
        // FOUR: "📖 What do I do now?" is on every page of every world.
        assertEquals(6 + 4, l.cells().size());
        assertNull(l.at(9), "nothing should be placed where no section reaches");
    }

    @Test
    @DisplayName("a creation cell carries its own figures, so the tooltip IS the card")
    void creationsCarryTheirNumbers() {
        WatchPanel.Layout l = WatchPanel.lay(List.of(), List.of(), creations(1));
        WatchPanel.Cell c = l.cells().stream()
                .filter(x -> x.family() == WatchPanel.Family.CREATION).findFirst().orElseThrow();
        assertTrue(c.lore().contains("❤️ 20 hearts"), "vitals must reach the tooltip: " + c.lore());
        assertEquals("id0", c.arg());
    }

    @Test
    @DisplayName("nothing is placed on the control row by a flowing section")
    void theControlRowIsReserved() {
        WatchPanel.Layout l = WatchPanel.lay(presets(100), List.of(), List.of());
        for (WatchPanel.Cell c : l.cells()) {
            if (c.family() != WatchPanel.Family.CONTROL) {
                assertTrue(c.slot() < WatchPanel.CONTROL_ROW_START,
                        "a " + c.family() + " landed on the control row at " + c.slot());
            }
        }
    }

    @Test
    @DisplayName("null sections are an empty panel, not a crash")
    void nullsAreTolerated() {
        WatchPanel.Layout l = WatchPanel.lay(null, null, null);
        assertEquals(4, l.cells().size(), "only the four controls (+ the guide)");
    }

    @Test
    @DisplayName("there is a way to wish for something NEW, and it is not next to the two exits")
    void composeIsPresentAndWellSeparated() {
        WatchPanel.Layout l = WatchPanel.lay(presets(3), List.of(), List.of());
        WatchPanel.Cell compose = null;
        WatchPanel.Cell reject = null;
        WatchPanel.Cell close = null;
        for (WatchPanel.Cell c : l.cells()) {
            switch (c.action()) {
                case COMPOSE -> compose = c;
                case REJECT -> reject = c;
                case CLOSE -> close = c;
                default -> { }
            }
        }
        assertNotNull(compose, "the panel offers no way to wish for something new — the ONE thing "
                + "the product is for was reachable only by typing a slash command");
        assertNotNull(reject);
        assertNotNull(close);
        assertTrue(compose.slot() >= WatchPanel.CONTROL_ROW_START,
                "the new-wish button must be pinned in the control row, not flowed with the presets "
                        + "where a long history could push it off");
        // Four slots clear of both, so a small hand cannot mis-hit "no" or "leave" instead.
        assertTrue(Math.abs(compose.slot() - reject.slot()) >= 4
                        && Math.abs(compose.slot() - close.slot()) >= 4,
                "the new-wish button is crowded against an exit: compose=" + compose.slot()
                        + " reject=" + reject.slot() + " close=" + close.slot());
    }

    @Test
    @DisplayName("every cell carries its own icon, and a row of things is not a row of one icon")
    void cellsCarryDistinctIcons() {
        // REAL wish sentences, not presets(n)'s placeholders. The icon is read off the wish text,
        // so "wish 0" through "wish 4" legitimately all fall through to the fallback -- asserting
        // over those would be asserting that the classifier can read words that are not there.
        WatchPanel.Layout l = WatchPanel.lay(List.of(
                new WatchPanel.PresetRow("Make it rain chickens!", "make it rain chickens", "a"),
                new WatchPanel.PresetRow("Give me treasure!", "give me treasure", "b"),
                new WatchPanel.PresetRow("Build me a tower!", "build me a tower", "c"),
                new WatchPanel.PresetRow("Make me fly!", "make me fly", "d")),
                List.of(), List.of());
        java.util.Set<String> presetIcons = new java.util.HashSet<>();
        for (WatchPanel.Cell c : l.cells()) {
            assertFalse(c.icon().isBlank(), "cell at " + c.slot() + " has no icon");
            if (c.family() == WatchPanel.Family.PRESET) {
                presetIcons.add(c.icon());
            }
        }
        assertEquals(4, presetIcons.size(),
                "four different kinds of wish should look like four different things");
        assertTrue(presetIcons.size() > 1,
                "every preset wears the same icon (" + presetIcons + ") — the panel is unreadable");
    }


    @Test
    @DisplayName("a long history fills the panel, and stops one row short of the controls")
    void recentsFillTheRowsButLeaveABufferRow() {
        List<WatchRow.Creation> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(new WatchRow.Creation("id" + i, "creature", "Thing " + i, List.of(), List.of(),
                    "", List.of(), "", 1, false, List.of(), "r" + i, "seed" + i));
        }
        List<WatchPanel.Layout> pages = allPages(presets(13), List.of(), many);

        long shownCreations = pages.stream().flatMap(l -> l.cells().stream())
                .filter(c -> c.family() == WatchPanel.Family.CREATION).count();
        // Every one of them, now: what does not fit on page one is a page away.
        assertEquals(40, shownCreations,
                "only " + shownCreations + " of 40 things he made are reachable");

        for (WatchPanel.Layout l : pages) {
            for (WatchPanel.Cell c : l.cells()) {
                if (c.family() == WatchPanel.Family.CONTROL) {
                    continue;
                }
                assertTrue(c.slot() < WatchPanel.FLOW_SLOTS,
                        "a " + c.family() + " landed at slot " + c.slot() + ", past the flow area");
                // THE BUFFER ROW is the point: nothing he can press may sit directly above the row
                // holding "leave" and "that was wrong".
                assertTrue(c.slot() < WatchPanel.CONTROL_ROW_START - WatchPanel.COLS,
                        "a " + c.family() + " at slot " + c.slot()
                                + " is in the buffer row, one mis-click above the controls");
            }
        }
    }

    @Test
    @DisplayName("a big menu PAGES rather than crowding out his history: every creation is reachable")
    void presetsCannotEatThePanel() {
        List<WatchPanel.Layout> pages = allPages(presets(30), List.of(), creations(12));
        long creationsShown = pages.stream().flatMap(l -> l.cells().stream())
                .filter(c -> c.family() == WatchPanel.Family.CREATION).count();
        assertEquals(12, creationsShown, "a fixed menu crowded a growing history off the panel");
        long presetsShown = pages.stream().flatMap(l -> l.cells().stream())
                .filter(c -> c.family() == WatchPanel.Family.PRESET).count();
        assertEquals(30, presetsShown, "every one of 30 presets is shown on some page");
        assertEquals(0, pages.get(0).omittedPresets());
    }

    // ──: one pool of presets, then happening, then creations — paged ──────────────────────

    /** Every page of one panel, driven the way a child drives it: by pressing "More wishes ▶". */
    private static List<WatchPanel.Layout> allPages(List<WatchPanel.PresetRow> presets,
                                                    List<WatchRow.Happening> happening,
                                                    List<WatchRow.Creation> creations) {
        List<WatchPanel.Layout> out = new ArrayList<>();
        WatchPanel.Layout l = WatchPanel.lay(presets, happening, creations, 0);
        out.add(l);
        for (int guard = 0; guard < 50; guard++) {
            WatchPanel.Cell next = l.at(WatchPanel.NEXT_PAGE_SLOT);
            if (next == null) {
                break;
            }
            assertEquals(WatchPanel.Action.PAGE, next.action());
            l = WatchPanel.lay(presets, happening, creations, Integer.parseInt(next.arg()));
            out.add(l);
        }
        assertEquals(out.get(0).pageCount(), out.size(),
                "pressing 'More wishes ▶' did not reach every page");
        return out;
    }

    private static long count(List<WatchPanel.Layout> pages, WatchPanel.Family f) {
        return pages.stream().flatMap(l -> l.cells().stream()).filter(c -> c.family() == f)
                .count();
    }

    /**
     * The starters then his ORPHAN taught wishes, as {@code WishPresets.forPanel} hands them over
     * since "one tile per thing he made".
     */
    private static List<WatchPanel.PresetRow> startersThenOrphans(int starters, int orphans) {
        List<WatchPanel.PresetRow> out = new ArrayList<>();
        for (int i = 0; i < starters; i++) {
            out.add(new WatchPanel.PresetRow("Starter " + i, "starter wish " + i,
                    "starter_" + i));
        }
        for (int i = 0; i < orphans; i++) {
            out.add(WatchPanel.PresetRow.his("My wish " + i, "my wish " + i, "mine_" + i, "", 0L));
        }
        return out;
    }

    @Test
    @DisplayName("his orphans fill from slot 0, then the starters on a fresh line — with no headers")
    void presetsAreOnePoolFromSlotZero() {
        var presets = startersThenOrphans(9, 3);
        WatchPanel.Layout l = WatchPanel.lay(presets, List.of(), List.of());
        for (int i = 0; i < 3; i++) {
            assertEquals("mine_" + i, l.at(i).recipeId(), "his own buttons lead (#1352), slot " + i);
        }
        assertNull(l.at(3), "the starters started mid-line");
        for (int i = 0; i < 9; i++) {
            WatchPanel.Cell c = l.at(WatchPanel.COLS + i);
            assertNotNull(c, "slot " + (WatchPanel.COLS + i) + " is empty inside the starters");
            assertEquals(WatchPanel.Family.PRESET, c.family());
            assertEquals("starter_" + i, c.recipeId(), "the starters keep their curated order");
        }
        for (WatchPanel.Cell c : l.cells()) {
            assertTrue(c.family() == WatchPanel.Family.PRESET
                            || c.family() == WatchPanel.Family.CONTROL,
                    "an extra cell (a header?) at " + c.slot() + ": " + c.label());
        }
    }

    @Test
    @DisplayName("what is happening and his creations each start on a FRESH line")
    void happeningAndCreationsStartFreshLines() {
        // happening at 0-1; his things (4 creations + 3 orphans) from 9; starters from 18.
        WatchPanel.Layout l = WatchPanel.lay(startersThenOrphans(9, 3), happening(2), creations(4));
        assertEquals(WatchPanel.Family.HAPPENING, l.at(0).family());
        assertEquals(WatchPanel.Family.HAPPENING, l.at(1).family());
        assertNull(l.at(2), "his things started mid-line");
        assertEquals(WatchPanel.Family.CREATION, l.at(9).family());
        assertEquals(WatchPanel.Family.CREATION, l.at(12).family());
        assertEquals(WatchPanel.Family.PRESET, l.at(13).family(), "his orphans share his section");
        assertEquals("mine_0", l.at(13).recipeId());
        assertNull(l.at(16), "the starters started mid-line");
        assertEquals("starter_0", l.at(18).recipeId());
    }

    /**
     * his creations and his own taught buttons are ONE history, newest first, and page one
     * starts with the thing he made last; the starters come after all of it.
     */
    @Test
    @DisplayName("page one starts with his newest thing, across creations and his own buttons")
    void hisNewestThingLeadsPageOne() {
        var dragon = creations(1).get(0).withUpdatedAt(1_000);
        var castle = new WatchRow.Creation("castle", "build", "Castle", List.of(), List.of(), "",
                List.of(), "", 1, false, List.of(), "", "").withUpdatedAt(3_000);
        var presets = new ArrayList<WatchPanel.PresetRow>();
        presets.add(new WatchPanel.PresetRow("Starter", "starter wish", "starter_0"));
        presets.add(WatchPanel.PresetRow.his("Rain", "chicken rain", "chicken_rain", "", 2_000));
        WatchPanel.Layout l = WatchPanel.lay(presets, List.of(), List.of(dragon, castle));
        assertEquals("castle", l.at(0).arg(), "his newest thing is the first tile");
        assertEquals("chicken_rain", l.at(1).recipeId(), "his own button sits in his history");
        assertEquals("id0", l.at(2).arg());
        assertEquals("starter_0", l.at(WatchPanel.COLS).recipeId(), "the starter comes last");
    }

    /** the two levels read on the panel — the wish says what it makes, a part what it is part of. */
    @Test
    @DisplayName("a wish's button lists its parts, and a part's card names its wish")
    void theTwoLevelsReadOnThePanel() {
        var wish = WatchPanel.PresetRow.his("Tropical Oasis", "oasis", "tropical_oasis", "", 5)
                .withParts(List.of("Oasis", "Oasis Bar", "Coconut Drink"));
        var bar = new WatchRow.Creation("id:oasis_bar", "item", "Oasis Bar", List.of(), List.of(), "",
                List.of(), "", 1, false, List.of(), "tropical_oasis@oasis_bar", "")
                .withPartOf(List.of("Tropical Oasis"));
        WatchPanel.Layout l = WatchPanel.lay(List.of(wish), List.of(), List.of(bar));
        WatchPanel.Cell barCell = l.cells().stream().filter(c -> "id:oasis_bar".equals(c.arg()))
                .findFirst().orElseThrow();
        WatchPanel.Cell wishCell = l.cells().stream()
                .filter(c -> "tropical_oasis".equals(c.recipeId())).findFirst().orElseThrow();
        assertTrue(wishCell.lore().contains("Makes all of it:")
                && wishCell.lore().contains("  • Oasis Bar"), "the wish says what it makes: "
                + wishCell.lore());
        assertTrue(barCell.lore().contains("Part of: Tropical Oasis"), "the part names its wish: "
                + barCell.lore());
        assertEquals("tropical_oasis@oasis_bar", barCell.recipeId(),
                "the part's tile remakes the part, never the whole wish");
        assertEquals(List.of(WatchClick.presetHint(false)), WatchPanel.presetLore(
                WatchPanel.PresetRow.his("Rain", "rain", "rain", "", 1)),
                "a one-thing button reads as before");
    }

    @Test
    @DisplayName("overflow flows onto later pages in order, and every item is on SOME page")
    void overflowFlowsInOrderAndEverythingIsReachable() {
        var presets = startersThenOrphans(30, 8);
        List<WatchPanel.Layout> pages = allPages(presets, happening(5), creations(40));
        List<String> order = new ArrayList<>();
        for (WatchPanel.Layout l : pages) {
            l.cells().stream().filter(c -> c.family() != WatchPanel.Family.CONTROL)
                    .sorted(java.util.Comparator.comparingInt(WatchPanel.Cell::slot))
                    .forEach(c -> order.add(c.family() + ":" + (c.family()
                            == WatchPanel.Family.HAPPENING ? c.arg() : c.recipeId().isEmpty()
                            ? c.arg() : c.recipeId())));
        }
        List<String> expected = new ArrayList<>();
        happening(5).forEach(h -> expected.add("HAPPENING:" + h.shortId()));
        creations(40).forEach(c -> expected.add("CREATION:" + c.recipeId()));
        presets.stream().filter(p -> !p.starter())
                .forEach(p -> expected.add("PRESET:" + p.recipeId()));
        presets.stream().filter(WatchPanel.PresetRow::starter)
                .forEach(p -> expected.add("PRESET:" + p.recipeId()));
        assertEquals(expected, order,
                "the flow across pages is not happening, his things, then the starters");
        // 5 happening = 1 line, 40 creations + 8 orphans = 6, 30 starters = 4: 11 lines, four to
        // a page.
        assertEquals(3, pages.size());
    }

    @Test
    @DisplayName("◀ appears only when there is a previous page, ▶ only when there is a next")
    void pagingCellsAppearOnlyWhenThereIsSomewhereToGo() {
        List<WatchPanel.Layout> pages = allPages(presets(20), happening(2), creations(40));
        assertTrue(pages.size() >= 3, "the fixture should need at least three pages");
        for (int i = 0; i < pages.size(); i++) {
            WatchPanel.Layout l = pages.get(i);
            assertEquals(i, l.page());
            WatchPanel.Cell back = l.at(WatchPanel.PREV_PAGE_SLOT);
            WatchPanel.Cell more = l.at(WatchPanel.NEXT_PAGE_SLOT);
            assertEquals(i > 0, back != null, "page " + i + " ◀");
            assertEquals(i < pages.size() - 1, more != null, "page " + i + " ▶");
            if (back != null) {
                assertEquals(Integer.toString(i - 1), back.arg());
            }
            if (more != null) {
                assertEquals(Integer.toString(i + 1), more.arg());
            }
        }
        WatchPanel.Layout single = WatchPanel.lay(presets(9), List.of(), creations(2));
        assertEquals(1, single.pageCount());
        assertNull(single.at(WatchPanel.PREV_PAGE_SLOT));
        assertNull(single.at(WatchPanel.NEXT_PAGE_SLOT));
    }

    @Test
    @DisplayName("the buffer line 36-44 is ALWAYS empty, on every page")
    void theBufferLineIsAlwaysEmpty() {
        for (int[] n : new int[][] {{0, 0, 0}, {9, 0, 0}, {36, 0, 0}, {37, 3, 50}, {5, 9, 200}}) {
            for (WatchPanel.Layout l : allPages(presets(n[0]), happening(n[1]), creations(n[2]))) {
                for (int slot = WatchPanel.FLOW_SLOTS; slot < WatchPanel.CONTROL_ROW_START;
                     slot++) {
                    assertNull(l.at(slot), "slot " + slot + " of the buffer line is filled on page "
                            + l.page() + " of " + java.util.Arrays.toString(n));
                }
            }
        }
    }

    @Test
    @DisplayName("the paging cells sit in the control row, clear of both exits and of Compose")
    void pagingCellsAreWellSeparated() {
        List<WatchPanel.Layout> pages = allPages(presets(80), List.of(), List.of());
        WatchPanel.Layout middle = pages.get(1);
        assertTrue(pages.size() >= 3, "80 presets should need three pages");
        for (int slot : List.of(WatchPanel.PREV_PAGE_SLOT, WatchPanel.NEXT_PAGE_SLOT)) {
            assertTrue(slot >= WatchPanel.CONTROL_ROW_START, "paging is not in the control row");
            for (int control : List.of(WatchPanel.CONTROL_ROW_START, WatchPanel.SIZE - 1,
                    WatchPanel.CONTROL_ROW_START + WatchPanel.COLS / 2)) {
                assertTrue(Math.abs(slot - control) >= 2,
                        "a paging cell at " + slot + " is next to the control at " + control);
            }
            assertNotNull(middle.at(slot));
        }
    }

    @Test
    @DisplayName("a page past the end is clamped to the last page")
    void aPagePastTheEndIsClamped() {
        WatchPanel.Layout l = WatchPanel.lay(presets(50), List.of(), List.of(), 99);
        assertEquals(l.pageCount() - 1, l.page());
        assertNull(l.at(WatchPanel.NEXT_PAGE_SLOT));
    }

    /**
     *, one tile per thing he made: a creation whose taught recipe the panel folded into it
     * carries that recipe on its OWN cell (so its card's [Make another one] replays it), and
     * pressing it is still the CARD, never an instant remake.
     */
    @Test
    @DisplayName("a creation tile carries its taught recipe and still opens its card")
    void aCreationTileCarriesItsTaughtRecipe() {
        var dragon = new WatchRow.Creation("dragon_1", "creature", "Fire Dragon", List.of(),
                List.of(), "", List.of(), "", 1, false, List.of(), "fire_dragon", "fire dragon");
        WatchPanel.Layout l = WatchPanel.lay(startersThenOrphans(9, 0), List.of(), List.of(dragon));
        WatchPanel.Cell c = l.cells().stream()
                .filter(x -> x.family() == WatchPanel.Family.CREATION).findFirst().orElseThrow();
        assertEquals("fire_dragon", c.recipeId(), "the creation lost its recipe");
        assertTrue(c.canRemake());
        assertEquals(WatchPanel.Action.CARD, c.action(), "pressing the tile must open its card");
        assertEquals("dragon_1", c.arg());
        assertEquals(1, l.cells().stream().filter(x -> "fire_dragon".equals(x.recipeId())).count(),
                "one thing, one tile");
    }

    @Test
    @DisplayName("2026-11-10: ✋ Stop my wish takes 👎's slot while a wish is being made, and only then")
    void stopTakesTheRejectSlotWhileAWishRuns() {
        for (WatchPanel.Grid g : List.of(WatchPanel.Grid.JAVA, WatchPanel.Grid.POCKET)) {
            WatchPanel.Layout idle = WatchPanel.lay(List.of(), List.of(), List.of(), 0, g);
            WatchPanel.Layout busy = WatchPanel.lay(List.of(),
                    List.of(new WatchRow.Happening("ab12", "a dragon", 30, "creature")), List.of(), 0, g);
            WatchPanel.Cell idleSlot = idle.at(g.reject());
            WatchPanel.Cell busySlot = busy.at(g.reject());
            assertEquals(WatchPanel.Action.REJECT, idleSlot.action(), "idle: 👎 — " + g);
            assertEquals(WatchPanel.Action.STOP, busySlot.action(), "busy: ✋ — " + g);
            assertEquals(WatchPanel.STOP_LABEL, busySlot.label());
            assertTrue(busy.cells().stream().noneMatch(c -> c.action() == WatchPanel.Action.REJECT),
                    "never both: " + g);
        }
    }

    @Test
    @DisplayName("2026-11-10: ↩ Take me back leads the tiles while there is a way back, and is gone after")
    void takeMeBackLeadsTheTiles() {
        var dragon = new WatchRow.Creation("dragon", "creature", "Dragon", List.of(), List.of(), "",
                List.of(), "", 1, false, List.of(), "", "");
        for (WatchPanel.Grid g : List.of(WatchPanel.Grid.JAVA, WatchPanel.Grid.POCKET)) {
            WatchPanel.Layout away = WatchPanel.lay(List.of(), List.of(), List.of(dragon), 0, g, true);
            WatchPanel.Cell first = away.cells().stream()
                    .filter(c -> c.family() != WatchPanel.Family.CONTROL)
                    .min(java.util.Comparator.comparingInt(WatchPanel.Cell::slot)).orElseThrow();
            assertEquals(WatchPanel.Action.BACK_HOME, first.action(), "the first tile — " + g);
            WatchPanel.Layout home = WatchPanel.lay(List.of(), List.of(), List.of(dragon), 0, g, false);
            assertTrue(home.cells().stream().noneMatch(c -> c.action() == WatchPanel.Action.BACK_HOME), g.toString());
        }
    }
}
