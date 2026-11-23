package com.moddingfromamod.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Gate D's item-chain verdict: forks and canned first wishes are correct paths. */
class ItemChainVerdictTest {

    private static final String CHILD = "380df991-1111-2222-3333-444444444444";

    @Test
    void aCannedWishReWishedIntoHisOwnCopyAndUndoneIsAPass() {
        // The archived Opus chain on the canned lucky block: nothing in his store after the wish,
        // his copy after the re-wish, and gone again after the undo. The old verdict said FAIL.
        String fork = "lucky_block_outcome_table_380df991";
        var r = ItemChainVerdict.judge(false, true, Map.of(),
                Map.of(fork, "magic_block|" + CHILD + "|spec"), Map.of(), true);
        assertTrue(r.pass(), r.toString());
        assertEquals(List.of(fork), r.forks());
    }

    @Test
    void anInPlaceEditOfWhatTheAgentMadeStillPasses() {
        var r = ItemChainVerdict.judge(true, false, Map.of("wand", "plain|" + CHILD + "|v1"),
                Map.of("wand", "plain|" + CHILD + "|v2"), Map.of(), true);
        assertTrue(r.pass(), r.toString());
        assertEquals(List.of("wand"), r.edited());
    }

    @Test
    void anUnrelatedNewItemIsNotAnEdit() {
        // Owned by him, but not named as his copy of anything: a rebuild, not an edit.
        var r = ItemChainVerdict.judge(true, false, Map.of("wand", "plain|" + CHILD + "|v1"),
                Map.of("wand", "plain|" + CHILD + "|v1", "wand2", "plain|" + CHILD + "|x"),
                Map.of(), true);
        assertFalse(r.pass(), r.toString());
    }

    @Test
    void aSecondMachineIsStillARebuild() {
        var r = ItemChainVerdict.judge(true, false, Map.of("mill", "machine|" + CHILD + "|v1"),
                Map.of("mill", "machine|" + CHILD + "|v2", "mill2", "machine|" + CHILD + "|x"),
                Map.of(), true);
        assertTrue(r.secondMachine());
        assertFalse(r.pass());
    }

    @Test
    void anUndoThatLeavesHisCopyIsAFail() {
        String fork = "lucky_block_outcome_table_380df991";
        var after = Map.of(fork, "magic_block|" + CHILD + "|spec");
        assertFalse(ItemChainVerdict.judge(false, true, Map.of(), after, after, true).pass());
    }

    @Test
    void aWishThatNeitherReachedTheAgentNorCameFromTheLibraryIsVacuous() {
        var r = ItemChainVerdict.judge(false, false, Map.of("wand", "plain||v1"),
                Map.of("wand", "plain||v2"), Map.of(), true);
        assertFalse(r.pass());
    }
}
