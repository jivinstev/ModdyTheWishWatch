package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The handover record — what makes pinning optional, and therefore what lets the scheduler stay
 * simple and never make a child wait for a particular agent to be free.
 *
 * <p>Two rules carry the weight, and both are here rather than in a doc: <b>it accumulates</b>
 * (overwriting is an earlier issue's rework undercount in a new place), and <b>a lazy one fails a gate</b>
 * (the risk with a record like this was never that it goes missing, it is that it says nothing).
 */
class HandoverRecordTest {

    private static HandoverRecord spikor() {
        return new HandoverRecord("spikor", "creature", "a boss Spikor, king of the kroaks",
                List.of("king kroak"),
                "canon palette only, yellow-green skin, because he asked for the REAL Spikor",
                "tried scaling the head to 3 — it clipped through the castle ceiling",
                "120,70,-340 in minecraft:overworld", 1,
                "his fire aims from the snout, so inspect_creature before moving the head",
                1000L);
    }

    @Test
    void aRecordTellsTheNextAgentWhatItCannotWorkOutForItself() {
        String brief = spikor().forAgent();
        assertTrue(brief.contains("king kroak"), "the names he uses");
        assertTrue(brief.contains("clipped through the castle ceiling"),
                "the dead end — the one thing nothing else records");
        assertTrue(brief.contains("ALREADY TRIED AND REJECTED"),
                "and it is shouted, because re-walking it is the specific failure this prevents");
    }

    @Test
    void aRecordThatSaysNothingIsTrivialAndAGateCanFailOnIt() {
        HandoverRecord lazy = new HandoverRecord("spikor", "creature", "spikor", List.of(),
                "did it", "", "", 1, "", 0L);
        assertTrue(lazy.isTrivial(),
                "the risk here was never a missing record, it is one filled in to get past the tool");
        assertFalse(spikor().isTrivial());
    }

    @Test
    void trivialityJudgesTheTwoFieldsNothingElseCanReconstruct() {
        // Name, kind and position are already in the manifest. A record missing those is thin;
        // a record missing WHY and WHAT-FAILED is useless, and only the second is worth failing on.
        HandoverRecord noPosition = new HandoverRecord("spikor", "", "", List.of(),
                "canon palette only, because he asked for the real one", "", "", 1, "", 0L);
        assertFalse(noPosition.isTrivial());
    }

    @Test
    void theSecondEditAccumulatesRatherThanOverwriting() {
        HandoverRecord second = new HandoverRecord("spikor", "creature", "", List.of("the big guy"),
                "made him bigger; scale 2.8 is the ceiling before he clips the arch",
                "tried scale 4 — he could not fit through his own gate", "", 1, "", 2000L);

        HandoverRecord merged = spikor().thenAlso(second);

        assertEquals(2, merged.revision(), "this is edit two, and the history must read as two edits");
        assertTrue(merged.triedAndRejected().contains("scale 4"), "the new dead end is kept");
        assertTrue(merged.triedAndRejected().contains("head to 3"), "…and so is the old one");
        assertTrue(merged.aliases().contains("king kroak"));
        assertTrue(merged.aliases().contains("the big guy"), "names accumulate too");
    }

    @Test
    void aFourDeepChainReadsAsFourEditsNotFourCreations() {
        HandoverRecord r = spikor();
        for (int i = 2; i <= 4; i++) {
            r = r.thenAlso(new HandoverRecord("spikor", "creature", "", List.of(),
                    "step " + i, "dead end " + i, "", 1, "", i * 1000L));
        }
        assertEquals(4, r.revision(),
                "issue #18's rework undercount, reappearing in the artifact page, is what this stops");
    }

    @Test
    void theNewestReasoningIsKeptWhenTheFieldFillsUp() {
        HandoverRecord r = new HandoverRecord("x", "", "", List.of(),
                "A".repeat(HandoverRecord.MAX_FIELD_CHARS), "", "", 1, "", 0L);
        HandoverRecord merged = r.thenAlso(new HandoverRecord("x", "", "", List.of(),
                "the newest decision", "", "", 1, "", 1L));

        assertTrue(merged.decisionsAndWhy().startsWith("the newest decision"),
                "a worker skimming a capped field must see the most recent decision, not the oldest");
        assertTrue(merged.decisionsAndWhy().length() <= HandoverRecord.MAX_FIELD_CHARS + 1);
    }

    @Test
    void anOverlongFieldIsCutOnAWordBoundary() {
        String rambling = "word ".repeat(400);
        HandoverRecord r = new HandoverRecord("x", "", "", List.of(), rambling, "", "", 1, "", 0L);
        assertTrue(r.decisionsAndWhy().length() <= HandoverRecord.MAX_FIELD_CHARS + 1);
        assertTrue(r.decisionsAndWhy().endsWith("…"),
                "and it says it was cut, rather than just stopping");
        assertFalse(r.decisionsAndWhy().contains("wor…"),
                "cutting mid-word reads as corrupted data, not as a long note");
    }

    @Test
    void aRevisionIsNeverZero() {
        assertEquals(1, new HandoverRecord("x", "", "", List.of(), "", "", "", 0, "", 0L).revision());
        assertEquals(1, new HandoverRecord("x", "", "", List.of(), "", "", "", -5, "", 0L).revision());
    }
}
