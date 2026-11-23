package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A kind we took away still loads, and means nothing</b> (V7 of
 * docs/PARKED_CREATE_TRAINS_1211.md).
 *
 * <p>"contraption" and "train" were {@link MadeKind}s until Create and trains went. Every store that
 * carries a kind holds it as a plain string — an undo ledger entry ({@code WishRun.Made}), a watch
 * row, an artifact's handover notes and memory, a taught recipe's parts, a button that says
 * {@code wishtrain <id>} — and a child's world or shelf written before 2026-10-15 may hold one. The
 * plan says the parser maps an unknown kind to nothing, and that this must be CHECKED rather than
 * assumed: this is the check. Nothing here may throw, and nothing may claim to make, frame, undo or
 * replay one.
 */
class MadeKindTest {

    private static final List<String> OLD_KINDS = List.of("contraption", "train");

    @Test
    @DisplayName("no tool makes or removes a contraption or a train any more")
    void noToolProducesAnOldKind() {
        for (String tool : List.of("build_contraption", "build_train", "modify_contraption",
                "modify_train")) {
            assertNull(MadeKind.forTool(tool), tool);
        }
        for (String tool : List.of("remove_contraption", "remove_train")) {
            assertNull(MadeKind.forRemoveTool(tool), tool);
        }
    }

    @Test
    @DisplayName("an old kind in a ledger, a watch or a recipe's parts is read as nothing to do")
    void anOldKindIsToleratedEverywhereItIsRead() {
        for (String kind : OLD_KINDS) {
            WishRun.Made made = new WishRun.Made(kind, "mctrain");
            assertEquals("", WishParts.replayCommand(made), kind + ": no replay command");
            assertEquals(WatchIcons.UNKNOWN, WatchIcons.forKind(kind), kind + ": the fallback icon");
            assertNull(WishCardContract.rowFor(kind), kind + ": no card row, and no exception");
            assertEquals(kind, ReferentFacts.kindWord(kind), kind + ": passed through as a word");
            assertEquals(Optional.empty(),
                    com.moddingfromamod.wish.TryPlan.commandFor(kind, "mctrain"), kind);
            assertEquals("", com.moddingfromamod.wish.CameraNeed.classOfMade(kind), kind);
            assertEquals("", com.moddingfromamod.wish.PhotoSubject.choose(List.of(made), "old"),
                    kind + ": nothing of this wish's to aim the camera at");
            assertFalse(com.moddingfromamod.wish.WishUndo.undoOne(null, kind, "mctrain", null, null),
                    kind + ": an undo that has nothing to reverse says so rather than throwing");
        }
    }

    @Test
    @DisplayName("a taught button that says wishtrain or wishmachine names nothing and runs nothing")
    void anOldReplayButtonIsInert() {
        List<String> commands = List.of("wishtrain mctrain", "wishmachine grumblegrind");
        assertTrue(TaughtJoin.artifactsNamed(commands).isEmpty(),
                "an old recipe joins to no tile rather than to a thing that cannot exist");
        for (String c : commands) {
            assertFalse(com.moddingfromamod.wish.CommandGuard.isAllowed(c),
                    c + " — refused by the guard like any unknown command, never executed");
        }
    }
}
