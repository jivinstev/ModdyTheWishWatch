package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.marshal.WatchClick.Press;
import com.moddingfromamod.marshal.WatchPanel.Action;
import com.moddingfromamod.marshal.WatchPanel.Family;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** which thing a click on a watch tile does, by button. */
class WatchClickTest {

    @Test
    @DisplayName("a remakeable creation opens its page on either button (2026-11-10: a phone has one press)")
    void remakeableCreation() {
        assertEquals(Press.CARD, WatchClick.decide(Family.CREATION, Action.CARD, false, true));
        assertEquals(Press.CARD, WatchClick.decide(Family.CREATION, Action.CARD, true, true));
    }

    @Test
    @DisplayName("a creation with no recipe opens the card on either button — never a give")
    void unremakeableCreation() {
        assertEquals(Press.CARD, WatchClick.decide(Family.CREATION, Action.CARD, false, false));
        assertEquals(Press.CARD, WatchClick.decide(Family.CREATION, Action.CARD, true, false));
    }

    @Test
    @DisplayName("a ✨ button: left re-wishes as before, right opens a card for it")
    void preset() {
        assertEquals(Press.AS_CELL, WatchClick.decide(Family.PRESET, Action.WISH, false, true));
        assertEquals(Press.CARD, WatchClick.decide(Family.PRESET, Action.WISH, true, true));
    }

    @Test
    @DisplayName("controls, happenings and the guide do what the cell says, either button")
    void everythingElse() {
        for (boolean right : new boolean[] {false, true}) {
            assertEquals(Press.AS_CELL, WatchClick.decide(Family.CONTROL, Action.CLOSE, right, false));
            assertEquals(Press.AS_CELL, WatchClick.decide(Family.CONTROL, Action.REJECT, right, false));
            assertEquals(Press.AS_CELL, WatchClick.decide(Family.CONTROL, Action.STOP, right, false));
            assertEquals(Press.AS_CELL, WatchClick.decide(Family.HAPPENING, Action.BACK_HOME, right, false));
            assertEquals(Press.AS_CELL, WatchClick.decide(Family.HAPPENING, Action.GO, right, false));
            assertEquals(Press.AS_CELL, WatchClick.decide(Family.GUIDE, Action.HELP_WISH, right, false));
        }
    }

    @Test
    @DisplayName("only a PICKUP with button 1 is a right-click")
    void secondaryIsPickupButtonOne() {
        assertTrue(WatchClick.secondary(true, 1));
        assertFalse(WatchClick.secondary(true, 0));
        assertFalse(WatchClick.secondary(false, 1), "shift/number-key/Q presses as a left click");
    }

    @Test
    @DisplayName("the tile says what each click does, and the panel writes it")
    void tilesSayWhatClicksDo() {
        var dragon = new WatchRow.Creation("dragon", "creature", "Dragon", List.of(), List.of(), "",
                List.of(), "", 1, false, List.of(), "dragon_tpl", "");
        var rock = new WatchRow.Creation("rock", "build", "Rock", List.of(), List.of(), "",
                List.of(), "", 1, false, List.of(), "", "");
        WatchPanel.Layout l = WatchPanel.lay(List.of(), List.of(), List.of(dragon, rock));
        WatchPanel.Cell d = l.cells().stream().filter(c -> "dragon".equals(c.arg())).findFirst().orElseThrow();
        WatchPanel.Cell r = l.cells().stream().filter(c -> "rock".equals(c.arg())).findFirst().orElseThrow();
        assertEquals(WatchClick.creationHint(true), d.lore().get(d.lore().size() - 1));
        assertEquals(WatchClick.creationHint(false), r.lore().get(r.lore().size() - 1));
        for (String hint : List.of(WatchClick.presetHint(false), WatchClick.presetHint(true),
                WatchClick.creationHint(true), WatchClick.creationHint(false))) {
            assertTrue(hint.startsWith("Press to "), "the account page strips a tile's click line by this prefix: " + hint);
            assertFalse(hint.toLowerCase().contains("click"), "no tile names a mouse button (#1783): " + hint);
        }
    }
}
