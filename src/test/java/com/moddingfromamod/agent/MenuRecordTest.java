package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.marshal.WatchClick;
import com.moddingfromamod.marshal.WatchPanel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The {@code menu_open} / {@code menu_press} records (step 1): every press on a Moddy menu is
 * logged, so the menu's order and the pre-fill's acceptance can be decided from use. A press names an ID, never a
 * child's words.
 */
class MenuRecordTest {

    @Test
    void aWatchPressIsRecordedWithItsCell() {
        var o = ActionLog.menuRecord("menu_press", "p1", "watch", "GUIDE", "CONTROL", 0, 49, "", false);
        assertEquals("menu_press", o.get("kind").getAsString());
        assertEquals("watch", o.get("menu").getAsString());
        assertEquals("GUIDE", o.get("action").getAsString());
        assertEquals("CONTROL", o.get("family").getAsString());
        assertEquals(0, o.get("page").getAsInt());
        assertEquals(49, o.get("slot").getAsInt());
        assertFalse(o.has("tile"), "an empty target is left out, never written as \"\"");
        assertFalse(o.has("secondary"), "a left click carries no flag");
        assertTrue(o.has("at") && o.has("test") && o.has("playerId"));
    }

    @Test
    void aMenuKitPressHasNoWatchFields() {
        var o = ActionLog.menuRecord("menu_press", "p1", "builder", null, null, -1, 3, "power:flies", false);
        assertEquals("builder", o.get("menu").getAsString());
        assertEquals("power:flies", o.get("tile").getAsString());
        assertFalse(o.has("action") || o.has("family") || o.has("page"));
    }

    @Test
    void anOpenIsJustTheMenu() {
        var o = ActionLog.menuRecord("menu_open", "p1", "watch", null, null, -1, -1, null, false);
        assertEquals("menu_open", o.get("kind").getAsString());
        assertFalse(o.has("slot") || o.has("tile"));
    }

    @Test
    void aLongTileIsClipped() {
        var o = ActionLog.menuRecord("menu_press", "p1", "give_which", null, null, -1, 0, "x".repeat(500), false);
        assertTrue(o.get("tile").getAsString().length() <= ActionLog.MENU_TILE_MAX + 1);
    }

    @Test
    void aPresetLogsItsRecipeNeverItsText() {
        var preset = cell(WatchPanel.Family.PRESET, WatchPanel.Action.WISH, "a pet dragon that breathes fire",
                "starter_pet_dragon");
        assertEquals("starter_pet_dragon", WatchClick.logged(preset));
        var typedBox = cell(WatchPanel.Family.CONTROL, WatchPanel.Action.HELP_WISH, "help me tame a wolf", "");
        assertEquals("", WatchClick.logged(typedBox), "a pre-filled wish's words are not logged");
        var thing = cell(WatchPanel.Family.CREATION, WatchPanel.Action.CARD, "sparky", "");
        assertEquals("sparky", WatchClick.logged(thing));
        var page = cell(WatchPanel.Family.CONTROL, WatchPanel.Action.PAGE, "2", "");
        assertEquals("2", WatchClick.logged(page));
    }

    private static WatchPanel.Cell cell(WatchPanel.Family f, WatchPanel.Action a, String arg, String recipe) {
        return new WatchPanel.Cell(0, f, a, "label", arg, recipe, "", "", List.of());
    }
}
