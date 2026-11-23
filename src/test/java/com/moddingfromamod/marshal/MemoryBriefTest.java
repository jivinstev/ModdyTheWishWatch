package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The accrual and the budget: what survives eight turns, and what is sacrificed when it does not fit.
 *
 * <p>These are the tests the old design could not have had, because it had no notion of a turn — it
 * merged everything into five fields and truncated. The rules asserted here are the ones that
 * mattered in Steve's real save, where {@code crushing_plant} reached revision 8 with two surviving
 * segments of reasoning.
 */
class MemoryBriefTest {

    @AfterEach
    void reset() {
        System.clearProperty(MemoryBrief.BUDGET_PROPERTY);
    }

    private static ArtifactMemory.Turn turn(int n, String wish, String why, String... rejected) {
        return new ArtifactMemory.Turn("wish-" + n, 1000L + n, "w1", wish, "", "did " + n, why,
                List.of(rejected), "told him about " + n, "done", "");
    }

    private static ArtifactMemory ladder(int turns) {
        ArtifactMemory m = ArtifactMemory.of("crushing_plant", "contraption");
        for (int i = 1; i <= turns; i++) {
            // EVERY turn offers a description, exactly as FinishWishTool does — it passes the
            // wish text every time. A fixture that only offers one on turn 1 cannot catch the
            // overwrite bug at all, which is how this test first passed with the fix removed.
            m = m.plus(turn(i, "wish number " + i, "because reason " + i, "dead end " + i),
                    i == 1 ? "a machine that grinds trees" : "wish number " + i,
                    "-1861,-1,1054", List.of(), List.of());
        }
        return m;
    }

    // ================= accrual =================

    /**
     * <b>The headline correction.</b> Eight turns keep eight turns' reasoning, where the old design
     * kept two segments of it.
     */
    @Test
    void eightTurnsOfReasoningAllSurviveTheStore() {
        ArtifactMemory m = ladder(8);
        assertEquals(8, m.revision());
        for (int i = 1; i <= 8; i++) {
            assertTrue(m.turns().get(i - 1).why().contains("reason " + i),
                    "turn " + i + "'s reasoning must still be there — under the 600-char merge, six "
                            + "of these eight were silently truncated away");
        }
    }

    @Test
    void everyDeadEndEverWalkedIsKeptAndDeduplicated() {
        ArtifactMemory m = ArtifactMemory.of("zippy", "creature");
        m = m.plus(turn(1, "make zippy", "why", "levitation is overridden by FLIES"), "", "",
                List.of(), List.of());
        m = m.plus(turn(2, "faster", "why", "levitation is overridden by FLIES", "scale 3 clipped"),
                "", "", List.of(), List.of());
        assertEquals(List.of("levitation is overridden by FLIES", "scale 3 clipped"),
                m.allRejected(), "one entry per dead end, and the repeat counted once");
    }

    /**
     * The bug in the real save: {@code whatItIs} had become the newest wish text, so an agent waking
     * on turn 9 was told the artifact IS turn 8's request.
     */
    @Test
    void whatItIsIsFilledInOnceAndNeverOverwrittenByALaterWish() {
        ArtifactMemory m = ladder(8);
        assertEquals("a machine that grinds trees", m.whatItIs(),
                "the real record's 'what it is' had become the NEWEST wish text, so an agent waking "
                        + "on turn 9 was told the artifact IS turn 8's request");
        assertFalse(m.whatItIs().contains("wish number"));
    }

    @Test
    void recordingTheSameWishTwiceReplacesItsTurnRatherThanAddingASecond() {
        // What makes two workers on one artifact safe without a lease.
        ArtifactMemory m = ArtifactMemory.of("zippy", "creature");
        m = m.plus(turn(1, "make zippy", "first attempt"), "", "", List.of(), List.of());
        m = m.plus(turn(1, "make zippy", "corrected"), "", "", List.of(), List.of());
        assertEquals(1, m.revision());
        assertEquals("corrected", m.turns().get(0).why());
    }

    @Test
    void stateIsReplacedNotAccumulatedBecauseItIsWhatIsTrueNow() {
        ArtifactMemory m = ArtifactMemory.of("tails", "creature");
        m = m.plus(turn(1, "fly", "why"), "", "", List.of("speed 0.3"), List.of());
        m = m.plus(turn(2, "faster", "why"), "", "", List.of("speed 0.6, the maximum"), List.of());
        assertEquals(List.of("speed 0.6, the maximum"), m.state(),
                "stale state is worse than none — it is a fact the agent will act on");
    }

    /**
     * An alias is only a name if he comes back to it.
     *
     * <p>The real {@code crushing_plant} collected {@code [grinds, magically, butt cheek water,
     * nothing happens, animate]} — four emphasis words and one name — because the backfill reads
     * SHOUTING as naming. "nothing happens" as an alias means <i>"I built a dragon but nothing
     * happens"</i> resolves to the crushing plant, and rung 0 is now what routing keys on too.
     */
    @Test
    void aWordHeShoutedOnceIsNotAName() {
        ArtifactMemory m = ArtifactMemory.of("crushing_plant", "contraption")
                .plus(turn(1, "make the crushing plant grind trees MAGICALLY", "why"), "", "",
                        List.of(), List.of("magically", "crushing plant"))
                .plus(turn(2, "the crushing plant is too slow", "why"), "", "",
                        List.of(), List.of());
        assertEquals(List.of("crushing plant"), m.confirmedAliases(),
                "said twice, so it is a name; 'magically' was said once, so it was emphasis");
        assertTrue(m.aliases().contains("magically"), "…but nothing is deleted, only not shown");
    }

    @Test
    void aBrandNewThingKeepsItsAliasesBecauseThereIsNothingToConfirmAgainstYet() {
        ArtifactMemory m = ArtifactMemory.of("zippy", "creature")
                .plus(turn(1, "make me ZIPPY", "why"), "", "", List.of(), List.of("zippy"));
        assertEquals(List.of("zippy"), m.confirmedAliases());
    }

    /**
     * The edit number must count the edits, including the ones folded into the migration. Telling a
     * returning agent "edit #5" about a machine changed twelve times is a lie in the one field whose
     * job is to say how much history it is looking at.
     */
    @Test
    void migratedEditsStillCountTowardsTheEditNumber() {
        ArtifactMemory m = ladder(3).carryingOver(7);
        assertEquals(10, m.revision());
        assertTrue(MemoryBrief.render(m).text().contains("edit #11"));
    }

    // ================= the budget =================

    @Test
    void aShortLadderFitsWholeAndDropsNothing() {
        var r = MemoryBrief.render(ladder(3));
        assertEquals(0, r.turnsDropped());
        assertEquals(0, r.rejectionsDropped());
        assertTrue(r.text().contains("reason 3"));
        assertTrue(r.chars() < MemoryBrief.DEFAULT_BUDGET_CHARS);
    }

    @Test
    void theBriefStaysInsideItsBudgetEvenForAnAbsurdlyLongLadder() {
        var r = MemoryBrief.render(ladder(200), 3_000);
        assertTrue(r.chars() <= 3_000,
                "a budget that is not enforced is a comment; was " + r.chars());
        assertTrue(r.turnsDropped() > 0);
    }

    /**
     * The sacrifice ORDER is the design. Re-walking a dead end is the most expensive mistake
     * available and the only one that gets worse the more history there is, so rejections are the
     * last thing to go — after the middle turns and after demoting the older full ones.
     */
    @Test
    void olderTurnsAreSacrificedBeforeAnyRejectionIs() {
        var r = MemoryBrief.render(ladder(40), 2_500);
        assertTrue(r.turnsDropped() > 0, "turns had to go");
        assertEquals(0, r.rejectionsDropped(),
                "…and not one dead end was dropped to make room for them");
        assertTrue(r.text().contains("dead end 1"), "including the very oldest one");
    }

    @Test
    void whatHeOriginallyAskedForSurvivesEvenWhenTheMiddleDoesNot() {
        var r = MemoryBrief.render(ladder(40), 2_500);
        assertTrue(r.text().contains("ASKED FOR (turn 1):\n  \"wish number 1\""),
                "everything since is a modifier on the original intent. Matched on the QUOTED "
                        + "string: 'wish number 1' is a prefix of 'wish number 10', so the loose "
                        + "check could never have failed.");
    }

    @Test
    void theMostRecentTurnsAreAlwaysThereInFull() {
        var r = MemoryBrief.render(ladder(40), 2_500);
        assertTrue(r.text().contains("reason 40"), "'thinner than that' needs the last attempt");
        assertTrue(r.text().contains("told him about 40"),
                "and what he was already told, so it is not re-explained");
    }

    /** A brief that quietly omits history teaches the agent it has been told everything. */
    @Test
    void anythingLeftOutIsAnnouncedInTheBriefItself() {
        var r = MemoryBrief.render(ladder(40), 2_500);
        assertTrue(r.text().contains("left out to fit"),
                "the silent clip was always the bug, not the clip");
    }

    @Test
    void theMiddleIsCompressedToHisOwnWordsBeforeAnythingIsDroppedOutright() {
        // His words are the smallest and highest-value part of a turn, so the agent's own
        // reasoning — the bulk, and the most reconstructible — is what gets compressed first.
        var r = MemoryBrief.render(ladder(12), 20_000);
        assertTrue(r.text().contains("EARLIER TURNS"));
        assertTrue(r.text().contains("\"wish number 5\""), "his words kept");
        assertFalse(r.text().contains("reason 5"), "the agent's own reasoning for it dropped");
        assertEquals(0, r.turnsDropped(), "compressed, not dropped");
    }

    @Test
    void theBudgetIsAFlagSoAnArmCanMoveItAndMeasureIt() {
        assertEquals(MemoryBrief.DEFAULT_BUDGET_CHARS, MemoryBrief.budgetChars());
        System.setProperty(MemoryBrief.BUDGET_PROPERTY, "1200");
        assertEquals(1200, MemoryBrief.budgetChars());
        System.setProperty(MemoryBrief.BUDGET_PROPERTY, "nonsense");
        assertEquals(MemoryBrief.DEFAULT_BUDGET_CHARS, MemoryBrief.budgetChars(),
                "a typo'd budget must not silently become zero context");
    }

    /**
     * The budget is ~10x the whole of the old per-field cap and is still under 2% of one agent turn.
     * Pinned so nobody re-introduces a limit chosen to protect the wrong thing.
     */
    @Test
    void theBudgetIsGenerousComparedToWhatATurnAlreadyCosts() {
        assertTrue(MemoryBrief.DEFAULT_BUDGET_CHARS >= 6_000,
                "600 chars a field was 0.5% of a turn's context and was being truncated");
        assertTrue(MemoryBrief.DEFAULT_BUDGET_CHARS / 4 <= 1_500,
                "criterion 6 caps the injected block at 1500 tokens");
    }

    // ================= storage is not where the budget lives =================

    @Test
    void storageKeepsFarMoreThanAnyOneBriefShows() {
        ArtifactMemory m = ladder(40);
        var r = MemoryBrief.render(m, 2_000);
        assertEquals(40, m.revision(), "everything is still on disk…");
        assertTrue(r.turnsDropped() > 0, "…even though this brief could not show it all");
        // The point: a better renderer tomorrow can still reach turn 7. Under the old design it
        // was gone the moment turn 8 was written.
        assertTrue(m.turns().get(6).why().contains("reason 7"));
    }

    @Test
    void aFieldIsOnlyEverCutAsALastResortAndSaysSoWhenItIs() {
        String huge = "x".repeat(ArtifactMemory.MAX_FIELD_CHARS * 2);
        var t = new ArtifactMemory.Turn("w", 0, "w1", "wish", "", "", huge, List.of(), "", "done", "");
        assertTrue(t.why().endsWith("[…cut]"),
                "a record ending mid-word reads as corrupted data and the agent cannot tell which");
        assertTrue(t.why().length() <= ArtifactMemory.MAX_FIELD_CHARS + 16);
    }

    @Test
    void aThinTurnIsDetectableSoAGateCanFailOnIt() {
        assertTrue(turn(1, "wish", "").isThin());
        assertFalse(turn(1, "wish", "because the FLIES power overrides levitation entirely").isThin());
    }

    // ================= nothing to say =================

    @Test
    void anArtifactWithNoHistoryRendersNothingAtAll() {
        assertTrue(MemoryBrief.render(ArtifactMemory.of("nobody", "creature")).isEmpty());
        assertTrue(MemoryBrief.render(null).isEmpty());
    }

    @Test
    void aLadderRendersAsSomethingAHumanCanRead() {
        // Not a formatting assertion for its own sake: this block is read by a model, and the
        // sections are the only thing telling it what is state, what is history and what is a
        // standing prohibition.
        List<String> want = new ArrayList<>(List.of("YOUR NOTES ON", "WHAT IS TRUE NOW",
                "ALREADY TRIED AND REJECTED", "WHAT HE ORIGINALLY ASKED FOR",
                "WHAT HAS HAPPENED SINCE"));
        ArtifactMemory m = ArtifactMemory.of("zippy", "creature")
                .plus(turn(1, "make me zippy", "blue because he is blue", "green looked wrong"),
                        "a fast blue hedgehog", "0,64,0", List.of("scale 1.76"), List.of("sanic"));
        String text = MemoryBrief.render(m).text();
        want.removeIf(text::contains);
        assertTrue(want.isEmpty(), "missing sections: " + want);
    }
}
