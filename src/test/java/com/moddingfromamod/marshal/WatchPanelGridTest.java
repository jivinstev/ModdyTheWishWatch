package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * the panel for Bedrock's Pocket UI, which wraps the 54-slot chest at a width that depends on the
 * screen (5, 6 and 7 measured on a real client, 2026-11-08) — so it is laid out by reading ORDER — and
 * Java's untouched.
 */
class WatchPanelGridTest {

    private static final int[] MEASURED_WIDTHS = {5, 6, 7};

    private static List<WatchPanel.PresetRow> starters(int n) {
        List<WatchPanel.PresetRow> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new WatchPanel.PresetRow("Wish " + i, "make thing " + i, "r" + i, ""));
        }
        return out;
    }

    private static Map<Integer, WatchPanel.Cell> bySlot(WatchPanel.Layout l) {
        Map<Integer, WatchPanel.Cell> m = new HashMap<>();
        for (WatchPanel.Cell c : l.cells()) {
            assertFalse(m.containsKey(c.slot()), "two cells in slot " + c.slot());
            m.put(c.slot(), c);
        }
        return m;
    }

    @Test
    void javaIsExactlyTheLayoutItWasBefore() {
        var a = WatchPanel.lay(starters(60), List.of(), List.of(), 1);
        var b = WatchPanel.lay(starters(60), List.of(), List.of(), 1, WatchPanel.Grid.JAVA);
        assertEquals(a.cells(), b.cells());
        assertEquals(49, bySlot(a).entrySet().stream().filter(e -> e.getValue().action() == WatchPanel.Action.COMPOSE)
                .findFirst().orElseThrow().getKey());
    }

    @Test
    void pocketControlsAreTheFirstLineAtEveryMeasuredWidth() {
        var l = WatchPanel.lay(starters(120), List.of(), List.of(), 1, WatchPanel.Grid.POCKET);   // a middle page: ◀ and ▶
        Map<Integer, WatchPanel.Cell> m = bySlot(l);
        assertEquals(WatchPanel.Action.COMPOSE, m.get(0).action(), "✍ is the first thing read");
        assertEquals(WatchPanel.Action.GUIDE, m.get(1).action());
        assertEquals(WatchPanel.Action.CLOSE, m.get(2).action());
        assertFalse(m.containsKey(3), "a gap before 👎");
        assertEquals(WatchPanel.Action.REJECT, m.get(4).action());
        for (int w : MEASURED_WIDTHS) {
            for (int s = 0; s < WatchPanel.Grid.FLAT_FIRST_TILE; s++) {
                assertEquals(0, s / w, "control slot " + s + " is on the first line " + w + " wide");
            }
        }
        assertEquals(WatchPanel.Action.PAGE, m.get(WatchPanel.SIZE - 2).action(), "◀ at the list's end");
        assertEquals(WatchPanel.Action.PAGE, m.get(WatchPanel.SIZE - 1).action(), "▶ at the list's end");
    }

    @Test
    void pocketTilesAreContiguousInReadingOrder() {
        var l = WatchPanel.lay(starters(80), List.of(), List.of(), 0, WatchPanel.Grid.POCKET);
        List<WatchPanel.Cell> wishes = l.cells().stream().filter(c -> c.action() == WatchPanel.Action.WISH)
                .sorted(java.util.Comparator.comparingInt(WatchPanel.Cell::slot)).toList();
        assertEquals(WatchPanel.Grid.FLAT_TILES, wishes.size(), "a full page fills every tile slot");
        for (int i = 0; i < wishes.size(); i++) {
            assertEquals(WatchPanel.Grid.FLAT_FIRST_TILE + i, wishes.get(i).slot(), "no holes");
            assertEquals("✨ Wish " + i, wishes.get(i).label(), "the order the sections give");
        }
    }

    @Test
    void pocketPagesHoldEveryWish() {
        WatchPanel.Grid g = WatchPanel.Grid.POCKET;
        var first = WatchPanel.lay(starters(80), List.of(), List.of(), 0, g);
        int shown = 0;
        for (int p = 0; p < first.pageCount(); p++) {
            var l = WatchPanel.lay(starters(80), List.of(), List.of(), p, g);
            bySlot(l);
            for (WatchPanel.Cell c : l.cells()) {
                assertTrue(c.slot() >= 0 && c.slot() < WatchPanel.SIZE, "slot " + c.slot() + " is in the chest");
            }
            shown += (int) l.cells().stream().filter(c -> c.action() == WatchPanel.Action.WISH).count();
        }
        assertEquals(80, shown, "every starter is on some page");
        assertEquals(2, first.pageCount());
    }

    @Test
    void theGuideIsTheSameFlatListOnPocket() {
        var java = GuidePanel.lay(new GuidePanel.View(false, null, 0, false, "", starters(4)));
        assertEquals(java, GuidePanel.project(java, WatchPanel.Grid.JAVA), "Java untouched");
        var pocket = GuidePanel.project(java, WatchPanel.Grid.POCKET);
        Map<Integer, WatchPanel.Cell> m = bySlot(pocket);
        assertEquals(java.cells().size(), pocket.cells().size(), "nothing lost, nothing doubled");
        assertEquals(WatchPanel.Action.PAGE, m.get(0).action(), "◀ Back to my wishes first");
        assertEquals(WatchPanel.Action.CLOSE, m.get(2).action(), "✖ where the panel keeps it");
        assertEquals(WatchPanel.Family.GUIDE, m.get(WatchPanel.Grid.FLAT_FIRST_TILE).family(),
                "the headline is the first content read");
        int content = pocket.cells().size() - 2;
        for (int i = 0; i < content; i++) {
            assertTrue(m.containsKey(WatchPanel.Grid.FLAT_FIRST_TILE + i), "content contiguous from slot 5");
        }
    }
}
