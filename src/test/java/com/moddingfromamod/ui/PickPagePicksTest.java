package com.moddingfromamod.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** a MULTI-PICK page draws its picks inside the chest and can never be pressed past its limit. Pure. */
class PickPagePicksTest {

    static List<PickPage.Tile> tiles(int n, Set<String> chosen) {
        List<PickPage.Tile> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String id = "kit:p" + i;
            out.add(new PickPage.Tile(id, "minecraft:bone", "Part " + i, List.of("Tap to add or take off"), chosen.contains(id)));
        }
        return out;
    }

    static PickPage page(Set<String> chosen) {
        List<PickPage.Tile> t = tiles(12, chosen);
        Set<String> counted = new java.util.HashSet<>();
        t.forEach(x -> counted.add(x.id()));
        return PickPage.multi("Parts", t, new PickPage.Picks(3, PickPage.Tile.of("subject", "minecraft:cat_spawn_egg", "Cat"), counted));
    }

    @Test
    @DisplayName("the title counts, Back and Done are always there, the picks row sits above the controls")
    void theLayout() {
        PickPage p = page(Set.of("kit:p4"));
        assertEquals("Parts 1/3", p.title());
        assertTrue(p.back() && p.next());
        assertEquals(PickPage.DONE_LABEL, p.nextLabel());
        assertEquals(4, p.rows(), "two rows of tiles, the picks row, the controls");
        int row = p.picksRowSlot();
        assertEquals(18, row);
        var drawn = p.drawn();
        assertEquals("Cat", drawn.get(row).label(), "the thing being made comes first");
        assertEquals("Part 4", drawn.get(row + 1).label(), "then each pick");
        assertEquals(List.of(PickPage.REMOVE_LINE), drawn.get(row + 1).lore());
        assertEquals("2 more", drawn.get(row + 2).label());
        assertEquals("1 more", drawn.get(row + 3).label());
        assertNull(drawn.get(row + 4), "nothing past the limit");
        assertEquals(Set.of(row, row + 2, row + 3), p.showOnly(), "the subject and the empty places only show");
        assertEquals("kit:p4", p.at(row + 1), "a pick in the row takes it off: the same press as its tile");
        assertNull(p.at(row), "the subject does nothing");
        assertEquals(PickPage.NEXT_ID, p.at(p.nextSlot()));
        assertEquals(List.of(PickPage.REMOVE_LINE), drawn.get(4).lore(), "a chosen tile in the grid says how to remove it");
    }

    @Test
    @DisplayName("at the limit every unchosen tile is a Full pane that does nothing; a chosen one still comes off")
    void atTheLimit() {
        PickPage p = page(Set.of("kit:p0", "kit:p5", "kit:p9"));
        assertTrue(p.full());
        assertEquals("Parts 3/3", p.title());
        for (int i = 0; i < 12; i++) {
            PickPage.Tile t = p.drawn().get(i);
            if (i == 0 || i == 5 || i == 9) {
                assertEquals("kit:p" + i, p.at(i));
            } else {
                assertEquals(PickPage.FULL_LABEL, t.label(), "tile " + i);
                assertNull(p.at(i), "a Full tile does nothing: tile " + i);
            }
        }
        assertFalse(p.drawn().values().stream().anyMatch(t -> t.label().endsWith("more")), "no empty place left");
    }

    @Test
    @DisplayName("tiles that are not counted (a mood beside the powers) are never Full and never in the row")
    void uncountedTilesAreOrdinary() {
        List<PickPage.Tile> t = new ArrayList<>(tiles(2, Set.of("kit:p0", "kit:p1")));
        t.add(new PickPage.Tile("temper:shy", "minecraft:fern", "Shy", List.of(), false));
        t.add(new PickPage.Tile("temper:friendly", "minecraft:poppy", "Friendly", List.of(), true));
        PickPage p = PickPage.multi("Powers", t, new PickPage.Picks(2, null, Set.of("kit:p0", "kit:p1")));
        assertEquals("Powers 2/2", p.title());
        assertEquals("temper:shy", p.at(2), "a mood is still a choice when the powers are full");
        assertEquals(2, p.chosen().size(), "the chosen mood is not a pick");
    }

    @Test
    @DisplayName("a multi-pick page gives a row to its picks, so it holds one row of tiles fewer")
    void oneRowFewer() {
        Set<String> c = new java.util.HashSet<>();
        assertThrows(IllegalArgumentException.class, () -> PickPage.multi("Too many", tiles(37, Set.of()),
                new PickPage.Picks(3, null, c)));
        assertThrows(IllegalArgumentException.class, () -> new PickPage.Picks(9, null, c), "a limit wider than the row");
    }

    // ──: no typed count ─────────────────────────────────────────────────────────────────────────────────

    static PickPage open(Set<String> chosen, java.util.Map<String, String> blocked, String budget, int n) {
        List<PickPage.Tile> t = tiles(n, chosen);
        Set<String> counted = new java.util.HashSet<>();
        t.forEach(x -> counted.add(x.id()));
        return PickPage.multi("Parts", t, PickPage.Picks.open(PickPage.Tile.of("subject", "minecraft:cat_spawn_egg", "Cat"),
                counted, blocked, budget));
    }

    @Test
    @DisplayName("#2043: no count in the title; a blocked tile is grey with its reason, and still a button")
    void aBlockedTileSaysWhy() {
        PickPage p = open(Set.of("kit:p0"), java.util.Map.of("kit:p5", "Too big for this cat"), null, 12);
        assertEquals("Parts", p.title(), "no typed count");
        assertFalse(p.full(), "a page with no count is never Full");
        PickPage.Tile five = p.tiles().get(5);
        assertEquals("Too big for this cat", p.blocked(five));
        assertEquals(PickPage.BLOCKED_ITEM, p.drawn().get(5).item());
        assertEquals("Part 5", p.drawn().get(5).label(), "it keeps its name");
        assertEquals(List.of("Too big for this cat"), p.drawn().get(5).lore());
        assertEquals("kit:p5", p.at(5), "a press is answered with the reason, so the tile is a button");
        assertNull(p.blocked(p.tiles().get(0)), "a chosen tile is never blocked: a press takes it off");
    }

    @Test
    @DisplayName("#2043: the room tile follows the subject; more picks than the row holds end in '+N more'")
    void theRowShowsTheRoomAndOverflows() {
        Set<String> all = new java.util.HashSet<>();
        for (int i = 0; i < 12; i++) {
            all.add("kit:p" + i);
        }
        PickPage p = open(all, java.util.Map.of(), "Body room 9/13 boxes", 12);
        int row = p.picksRowSlot();
        assertEquals("Cat", p.drawn().get(row).label());
        assertEquals("Body room 9/13 boxes", p.drawn().get(row + 1).label());
        assertEquals(PickPage.BUDGET_LINE, p.drawn().get(row + 1).lore().get(0));
        PickPage.Tile last = p.drawn().get(row + PickPage.COLS - 1);
        assertEquals("+6 more", last.label(), "7 places less the '+N' tile show 6 picks; 6 more are ticked above");
        assertEquals(PickPage.MORE_LINE, last.lore().get(0));
        assertTrue(p.showOnly().containsAll(List.of(row, row + 1, row + PickPage.COLS - 1)), "subject, room and '+N' only show");
        assertEquals("kit:p0", p.at(row + 2), "a shown pick still takes itself off");
    }
}
