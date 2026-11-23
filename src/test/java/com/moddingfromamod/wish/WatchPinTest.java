package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Moddy's slot, one row per attempt in the table.</b> The GameTests prove each
 * event reaches this; these prove what it decides.
 */
class WatchPinTest {

    /** A 41-slot inventory: {@code w} watches at these slots, {@code f} other items at these. */
    private static final class Inv {
        final boolean[] watch = new boolean[WatchPin.SLOTS];
        final boolean[] empty = new boolean[WatchPin.SLOTS];

        Inv() {
            java.util.Arrays.fill(empty, true);
        }

        Inv w(int... slots) {
            for (int s : slots) {
                watch[s] = true;
                empty[s] = false;
            }
            return this;
        }

        Inv f(int... slots) {
            for (int s : slots) {
                empty[s] = false;
            }
            return this;
        }

        Inv full() {
            for (int s = 0; s < WatchPin.MAIN_END; s++) {
                empty[s] = false;
            }
            return this;
        }

        WatchPin.Plan plan() {
            return WatchPin.plan(watch, empty, false, false);
        }
    }

    @Test
    @DisplayName("home already: nothing to do")
    void alreadyHome() {
        WatchPin.Plan p = new Inv().w(0).f(3, 4).plan();
        assertTrue(p.idle(), p.toString());
    }

    @Test
    @DisplayName("the Wish Bag already in slot 2: nothing to do (it read as work every tick, live 2026-10-30)")
    void bagAlreadyHomeIsIdle() {
        Inv i = new Inv().w(WatchPin.BAG_HOME).f(0, 3);
        WatchPin.Plan p = WatchPin.plan(WatchPin.BAG_HOME, java.util.Set.of(WatchPin.HOME), i.watch, i.empty,
                false, false);
        assertTrue(p.idle(WatchPin.BAG_HOME), p.toString());
        assertTrue(!p.idle(), "against the WATCH's home it is not idle — the question pinBag used to ask");
    }

    @Test
    @DisplayName("Q / thrown away / /clear: none left, slot 1 empty → mint one into slot 1")
    void noneLeftMintsHome() {
        WatchPin.Plan p = new Inv().f(5).plan();
        assertEquals(WatchPin.MINT, p.source());
        assertEquals(WatchPin.NONE, p.displacedTo());
    }

    @Test
    @DisplayName("/item replace: slot 1 holds a sword, no watch → mint, the sword moves to the first free slot")
    void noneLeftAndSlotOneTaken() {
        WatchPin.Plan p = new Inv().f(0, 1).plan();
        assertEquals(WatchPin.MINT, p.source());
        assertEquals(2, p.displacedTo(), "the first free main slot after slot 1");
    }

    @Test
    @DisplayName("full inventory, slot 1 taken, no watch → mint, the old item is dropped at his feet, never destroyed")
    void fullInventoryDrops() {
        WatchPin.Plan p = new Inv().full().plan();
        assertEquals(WatchPin.MINT, p.source());
        assertEquals(WatchPin.DROP, p.displacedTo());
    }

    @Test
    @DisplayName("dragged to slot 7 (slot 1 empty) → it just moves home")
    void draggedIntoTheBag() {
        WatchPin.Plan p = new Inv().w(7).plan();
        assertEquals(7, p.source());
        assertEquals(WatchPin.NONE, p.displacedTo());
        assertTrue(p.remove().isEmpty());
    }

    @Test
    @DisplayName("number key: watch in slot 4, slot 1 holds bread → they swap")
    void numberKeySwap() {
        WatchPin.Plan p = new Inv().w(4).f(0).plan();
        assertEquals(4, p.source());
        assertEquals(4, p.displacedTo(), "the bread goes where the watch was");
    }

    @Test
    @DisplayName("F: watch in the offhand, slot 1 holds a torch → they swap back")
    void offhandSwap() {
        WatchPin.Plan p = new Inv().w(WatchPin.OFFHAND).f(0).plan();
        assertEquals(WatchPin.OFFHAND, p.source());
        assertEquals(WatchPin.OFFHAND, p.displacedTo());
    }

    @Test
    @DisplayName("/item replace into a helmet slot: the displaced item does NOT go on his head")
    void armourIsNotASwapTarget() {
        WatchPin.Plan p = new Inv().w(39).f(0).plan();
        assertEquals(39, p.source());
        assertEquals(1, p.displacedTo(), "a free bag slot, not the helmet slot");
    }

    @Test
    @DisplayName("armour watch, full bag → the displaced item is dropped")
    void armourWatchFullBag() {
        WatchPin.Plan p = new Inv().full().w(39).plan();
        assertEquals(WatchPin.DROP, p.displacedTo());
    }

    @Test
    @DisplayName("middle-click copies: the one in slot 1 stays, every other copy goes")
    void creativeCopies() {
        WatchPin.Plan p = new Inv().w(0, 3, 20, WatchPin.OFFHAND).plan();
        assertEquals(WatchPin.HOME, p.source());
        assertEquals(List.of(3, 20, WatchPin.OFFHAND), p.remove());
    }

    @Test
    @DisplayName("two copies, neither home, slot 1 taken → keep the first, the other's slot is free for the displaced item")
    void copiesFreeTheirSlots() {
        WatchPin.Plan p = new Inv().full().w(39, 12).plan();
        assertEquals(12, p.source(), "a main-inventory watch is found before the armour one");
        assertEquals(List.of(39), p.remove());
        assertEquals(12, p.displacedTo());
    }

    @Test
    @DisplayName("on the cursor: that IS the watch — nothing moves, inventory copies go")
    void carried() {
        WatchPin.Plan p = WatchPin.plan(new Inv().w(5).watch, new Inv().w(5).empty, true, false);
        assertEquals(WatchPin.ELSEWHERE, p.source());
        assertEquals(List.of(5), p.remove());
        assertTrue(WatchPin.plan(new Inv().watch, new Inv().empty, true, false).idle());
    }

    @Test
    @DisplayName("in the chest he has open: wait for the close, do not mint a second one")
    void storedWhileOpen() {
        Inv i = new Inv().f(0);
        assertTrue(WatchPin.plan(i.watch, i.empty, false, true).idle());
    }

    @Test
    @DisplayName("in an open chest AND one in his bag: the bag's one is pinned (the stored copy goes at close)")
    void storedAndHeld() {
        Inv i = new Inv().w(9);
        WatchPin.Plan p = WatchPin.plan(i.watch, i.empty, false, true);
        assertEquals(9, p.source());
    }

    @Test
    @DisplayName("41 slots or it refuses")
    void shape() {
        assertThrows(IllegalArgumentException.class,
                () -> WatchPin.plan(new boolean[36], new boolean[36], false, false));
    }
}
