package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** what a claim becomes once the world has been asked. */
class ClaimJudgeTest {

    private static final ClaimCheck COURT = new ClaimCheck(ClaimCheck.Kind.CREATURE_COUNT,
            "pickleball_player", 2, ClaimCheck.Near.WISH, 8);

    @Test
    @DisplayName("works:false is ✘; works:true with no check is TRIED, never ✔")
    void noCheckIsNeverATick() {
        assertEquals(ClaimJudge.Outcome.BROKEN, ClaimJudge.judge(false, COURT, null));
        assertEquals(ClaimJudge.Outcome.TRIED, ClaimJudge.judge(true, null, null));
        // The world could not be asked: still not a tick.
        assertEquals(ClaimJudge.Outcome.TRIED, ClaimJudge.judge(true, COURT, null));
    }

    @Test
    @DisplayName("the pickleball court: two at the start and none three seconds later is CONTRADICTED")
    void theCompanionsWalkedOff() {
        var seen = ClaimJudge.count(COURT, 2, 0);
        assertFalse(seen.holds());
        assertTrue(seen.saw().contains("2 when I started watching"), seen.saw());
        assertEquals(ClaimJudge.Outcome.CONTRADICTED, ClaimJudge.judge(true, COURT, seen));
        assertEquals(ClaimJudge.Outcome.WORKS, ClaimJudge.judge(true, COURT, ClaimJudge.count(COURT, 2, 2)));
    }

    @Test
    @DisplayName("follows: set to follow AND with him or coming — near alone, or set alone, is not following")
    void follows() {
        assertFalse(ClaimJudge.follows("spikor", false, false, 0, 0).holds());
        // Spikor live: follow was set on a llama, so the Spikor beside him is not set.
        assertFalse(ClaimJudge.follows("spikor", true, false, 3, 3).holds());
        assertTrue(ClaimJudge.follows("spikor", true, true, 5, 5).holds());
        assertTrue(ClaimJudge.follows("spikor", true, true, 30, 20).holds());
        assertFalse(ClaimJudge.follows("spikor", true, true, 30, 30).holds());
        assertFalse(ClaimJudge.follows("spikor", true, true, 20, 26).holds());
    }

    @Test
    @DisplayName("a creature matches on its leading words: the Muse arm's pickleball_player_a and 'Pickleball Player Red'")
    void leadingWords() {
        String w = ClaimJudge.words("pickleball_player");
        assertTrue(ClaimJudge.leading(ClaimJudge.words("pickleball_player_a"), w));
        assertTrue(ClaimJudge.leading(ClaimJudge.words("Pickleball Player Red"), w));
        assertTrue(ClaimJudge.leading(ClaimJudge.words("pickleball player"), w));
        assertFalse(ClaimJudge.leading(ClaimJudge.words("spikor"), ClaimJudge.words("bow")));
        assertFalse(ClaimJudge.leading(ClaimJudge.words("red pickleball player"), w));
    }

    @Test
    @DisplayName("stays healthy: alive and no health lost")
    void staysHealthy() {
        assertTrue(ClaimJudge.staysHealthy("spikor", true, true, 40, 40).holds());
        assertFalse(ClaimJudge.staysHealthy("spikor", true, true, 40, 37).holds());
        assertFalse(ClaimJudge.staysHealthy("spikor", true, false, 40, 0).holds());
        assertFalse(ClaimJudge.staysHealthy("spikor", false, false, 0, 0).holds());
    }

    @Test
    @DisplayName("the refusal names the claim, the check and what was seen")
    void refusalNamesIt() {
        String r = ClaimJudge.refusal(List.of(new ClaimJudge.Contradiction("2 mobs playing pickleball",
                COURT, ClaimJudge.count(COURT, 2, 0))));
        assertTrue(r.contains("2 mobs playing pickleball") && r.contains("creature_count")
                && r.contains("0× pickleball_player") && r.contains("works:false"), r);
    }

    @Test
    @DisplayName("#1261: only BROKEN is told to the child — WORKS and TRIED say nothing")
    void childSeesTried() {
        WishScope.as("claims-judge-test", () -> {
            WishClaims.state(List.of("it follows me", "it breathes fire", "2 on the court"));
            WishClaims.answer("it follows me", WishClaims.Verdict.WORKS, "");
            WishClaims.answer("it breathes fire", WishClaims.Verdict.TRIED, "");
            WishClaims.answer("2 on the court", WishClaims.Verdict.BROKEN, "");
            String said = WishClaims.brokenSentence();
            assertTrue(said != null && said.contains("2 on the court"), String.valueOf(said));
            assertFalse(said.contains("follows me") || said.contains("breathes fire"), said);
            assertTrue(WishClaims.unanswered().isEmpty(), "TRIED is an answer");
            assertFalse(WishClaims.allWork());
            WishClaims.forget("claims-judge-test");
        });
    }

    @Test
    @DisplayName("#1344: a wish key matches EXACTLY, namespace optional — bow_plain is not spikor_plain")
    void wishKeysMatchExactly() {
        assertTrue(ClaimJudge.keyMatches("boomerang_plain", "boomerang_plain"));
        assertTrue(ClaimJudge.keyMatches("boomerang_plain", "moddingfromamod:boomerang_plain"));
        assertTrue(ClaimJudge.keyMatches("boomerang_plain", " Boomerang_Plain "));
        assertFalse(ClaimJudge.keyMatches("spikor_plain", "bow_plain"));
        assertFalse(ClaimJudge.keyMatches("bow_plain", "spikor_plain"));
        assertFalse(ClaimJudge.keyMatches("boomerang_plain", "boomerang"));
        assertFalse(ClaimJudge.keyMatches("", "boomerang_plain"));
        assertFalse(ClaimJudge.keyMatches(null, "x"));
    }

    @Test
    @DisplayName("#1344: item_in_inventory counts what THIS wish handed over — never more, never something it did not")
    void itemsAreThisWishsHandOvers() {
        ClaimCheck one = new ClaimCheck(ClaimCheck.Kind.ITEM_IN_INVENTORY, "boomerang_plain", 1, ClaimCheck.Near.WISH, 16);
        assertTrue(ClaimJudge.items(one, 1, 1).holds());
        var never = ClaimJudge.items(one, 0, 0);
        assertFalse(never.holds());
        assertTrue(never.saw().contains("never handed him"), never.saw());
        var thrown = ClaimJudge.items(one, 1, 0);
        assertFalse(thrown.holds());
        assertTrue(thrown.saw().contains("the rest are gone"), thrown.saw());
        // Capped at what it handed over: 64 he had before do not stand in for 3 it gave.
        ClaimCheck three = new ClaimCheck(ClaimCheck.Kind.ITEM_IN_INVENTORY, "diamond", 3, ClaimCheck.Near.WISH, 16);
        assertFalse(ClaimJudge.items(three, 2, 64).holds());
        assertTrue(ClaimJudge.items(three, 3, 64).holds());
    }

    @Test
    @DisplayName("#1344: a count names how many are this wish's own, wherever they are")
    void ownCreaturesAreCountedByIdentity() {
        ClaimCheck spikor = new ClaimCheck(ClaimCheck.Kind.CREATURE_COUNT, "spikor", 1, ClaimCheck.Near.WISH, 16);
        var seen = ClaimJudge.count(spikor, 1, 1, 1);
        assertTrue(seen.holds());
        assertTrue(seen.saw().contains("this wish's own, wherever it is"), seen.saw());
        assertFalse(ClaimJudge.count(spikor, 0, 0, 0).holds());
        assertTrue(ClaimJudge.count(spikor, 0, 0, 0).saw().contains("within 16 blocks of the wish's spot"));
    }
}
