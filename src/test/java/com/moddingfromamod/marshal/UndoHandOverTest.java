package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A hand-over has an inverse now: every item goes through WishDelivery, which records
 * it against the wish, so {@code give_items} — and a {@code run_command} batch made only of gives —
 * no longer cost a wish its [undo]. Anything else in the batch still does.
 */
class UndoHandOverTest {

    @Test
    void giveItemsIsReversible() {
        assertTrue(UndoContract.undoable(List.of("make_item", "give_items")));
    }

    @Test
    void aBatchOfOnlyGivesIsAHandOver() {
        assertTrue(UndoContract.onlyHandsOver(List.of("wishdatapack hot_coffee give")));
        assertTrue(UndoContract.onlyHandsOver(List.of("/give @s minecraft:melon_slice 32",
                "wishdatapack oasis_bar give")));
    }

    @Test
    void anythingElseInTheBatchIsStillRunCommand() {
        assertFalse(UndoContract.onlyHandsOver(List.of("give @s minecraft:melon_slice 32",
                "setblock ~ ~ ~ minecraft:stone")));
        assertFalse(UndoContract.onlyHandsOver(List.of("wishdatapack oasis_bar")),
                "turning a pack on is not a hand-over");
        assertFalse(UndoContract.onlyHandsOver(List.of("wishdatapack oasis_bar fire")));
        assertFalse(UndoContract.onlyHandsOver(List.of("execute as @a run give @s minecraft:dirt")));
        assertFalse(UndoContract.onlyHandsOver(List.of()));
        assertFalse(UndoContract.undoable(List.of("run_command")));
    }
}
