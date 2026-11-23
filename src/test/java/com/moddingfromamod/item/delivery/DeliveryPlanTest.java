package com.moddingfromamod.item.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.moddingfromamod.item.delivery.DeliveryPlan.Cell;
import com.moddingfromamod.item.delivery.DeliveryPlan.Into;
import org.junit.jupiter.api.Test;

/** The owner's landing order, one row each: hotbar, then the bag, never elsewhere. */
class DeliveryPlanTest {

    private static final Set<Integer> PINS = Set.of(0, 1);   // the watch and the bag

    private static Cell[] bar(Cell... nine) {
        return nine;
    }

    private static Cell[] allFree() {
        Cell[] c = new Cell[9];
        Arrays.fill(c, Cell.free());
        return c;
    }

    private static Cell[] full() {
        Cell[] c = new Cell[9];
        Arrays.fill(c, Cell.other());
        return c;
    }

    @Test
    void aFreeHotbarTakesItInTheFirstSlotThatIsNotPinned() {
        var p = DeliveryPlan.plan(allFree(), PINS, 1, 64);
        assertEquals(List.of(new Into(2, 1, false)), p.hotbar());
        assertEquals(0, p.toBag());
    }

    @Test
    void aMatchingStackIsToppedUpBeforeAFreeSlotIsTaken() {
        Cell[] c = allFree();
        c[6] = Cell.holding(40);
        var p = DeliveryPlan.plan(c, PINS, 30, 64);
        assertEquals(List.of(new Into(6, 24, true), new Into(2, 6, false)), p.hotbar());
        assertEquals(0, p.toBag());
    }

    @Test
    void aFullHotbarSendsEverythingToTheBagAndNeverTheMainInventory() {
        var p = DeliveryPlan.plan(full(), PINS, 32, 64);
        assertTrue(p.hotbar().isEmpty());
        assertEquals(32, p.toBag());
    }

    @Test
    void pinnedSlotsAreNeverTakenEvenWhenEmpty() {
        Cell[] c = full();
        c[0] = Cell.free();
        c[1] = Cell.free();
        var p = DeliveryPlan.plan(c, PINS, 1, 64);
        assertTrue(p.hotbar().isEmpty(), "slot 1 and 2 are Moddy's even for the tick they are empty");
        assertEquals(1, p.toBag());
    }

    @Test
    void whatDoesNotFitInTheHotbarOverflowsToTheBag() {
        Cell[] c = full();
        c[7] = Cell.free();
        c[8] = Cell.free();
        var p = DeliveryPlan.plan(c, PINS, 200, 64);
        assertEquals(List.of(new Into(7, 64, false), new Into(8, 64, false)), p.hotbar());
        assertEquals(72, p.toBag());
        assertEquals(128, p.toHotbar());
    }

    @Test
    void anUnstackableThingTakesOneSlotEach() {
        var p = DeliveryPlan.plan(bar(Cell.other(), Cell.other(), Cell.free(), Cell.other(),
                Cell.free(), Cell.other(), Cell.other(), Cell.other(), Cell.other()), PINS, 3, 1);
        assertEquals(List.of(new Into(2, 1, false), new Into(4, 1, false)), p.hotbar());
        assertEquals(1, p.toBag());
    }

    @Test
    void notNineSlotsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> DeliveryPlan.plan(new Cell[36], PINS, 1, 64));
    }

    // ── a thing's own behaviour: hotbar, main inventory, feet — never the bag ──────────

    private static Cell[] main(Cell fill) {
        Cell[] c = new Cell[DeliveryPlan.MAIN];
        Arrays.fill(c, fill);
        return c;
    }

    @Test
    void aBehaviourGiveTakesTheHotbarFirstLikeAHandOver() {
        var p = DeliveryPlan.planBehaviour(allFree(), main(Cell.free()), PINS, 8, 64);
        assertEquals(List.of(new Into(2, 8, false)), p.hotbar());
        assertTrue(p.inventory().isEmpty());
        assertEquals(0, p.toBag());
        assertEquals(0, p.toFeet());
    }

    @Test
    void aBehaviourGiveIntoAFullHotbarGoesToTheMainInventoryNeverTheBag() {
        Cell[] inv = main(Cell.other());
        inv[4] = Cell.free();
        inv[20] = Cell.holding(60);
        var p = DeliveryPlan.planBehaviour(full(), inv, PINS, 30, 64);
        assertTrue(p.hotbar().isEmpty());
        assertEquals(List.of(new Into(29, 4, true), new Into(13, 26, false)), p.inventory(),
                "a matching stack first, then the lowest free slot, numbered as vanilla numbers them");
        assertEquals(0, p.toBag(), "a behaviour give can never reach the Wish Bag");
        assertEquals(0, p.toFeet());
    }

    @Test
    void aBehaviourGiveIntoAFullInventoryGoesToHisFeetNeverTheBag() {
        var p = DeliveryPlan.planBehaviour(full(), main(Cell.other()), PINS, 2304, 64);
        assertTrue(p.hotbar().isEmpty());
        assertTrue(p.inventory().isEmpty());
        assertEquals(0, p.toBag(), "a behaviour give can never reach the Wish Bag");
        assertEquals(2304, p.toFeet());
    }

    @Test
    void aBehaviourGiveKeepsModdysPinsEvenWhenEmpty() {
        Cell[] c = full();
        c[0] = Cell.free();
        c[1] = Cell.free();
        var p = DeliveryPlan.planBehaviour(c, main(Cell.other()), PINS, 1, 64);
        assertTrue(p.hotbar().isEmpty());
        assertEquals(1, p.toFeet());
    }

    @Test
    void aBehaviourPlanWantsTwentySevenMainSlots() {
        assertThrows(IllegalArgumentException.class,
                () -> DeliveryPlan.planBehaviour(allFree(), new Cell[9], PINS, 1, 64));
    }
}
