package com.moddingfromamod.testing;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** the class filter a targeted Gate B run registers through. */
class GameTestClassFilterTest {

    @Test
    void noFilterMeansEveryClass() {
        assertTrue(GameTestClassFilter.parse(null).isEmpty());
        assertTrue(GameTestClassFilter.parse("  ").isEmpty());
        assertTrue(GameTestClassFilter.allows(Set.of(), "AnyGameTest"), "an absent filter never drops a test");
    }

    @Test
    void aFilterKeepsExactlyItsClasses() {
        Set<String> f = GameTestClassFilter.parse("McpGameTest, WishGameTest,,");
        assertEquals(Set.of("mcpgametest", "wishgametest"), f, "kept lower case: a test's id is");
        assertTrue(GameTestClassFilter.allows(f, "McpGameTest"));
        assertFalse(GameTestClassFilter.allows(f, "HitProxyGameTest"));
        assertFalse(GameTestClassFilter.allows(f, "McpGameTestX"), "simple names match exactly, never by prefix");
    }

    @Test
    void theFlakeExclusionDropsExactlyItsMethodsWhateverTheCase() {
        Set<String> x = GameTestClassFilter.parseExclude("StressGameTest.everyCorpusCaseHoldsItsInvariants");
        assertTrue(GameTestClassFilter.excluded(x, "StressGameTest", "everyCorpusCaseHoldsItsInvariants"));
        assertTrue(GameTestClassFilter.excluded(x, "stressgametest", "EVERYCORPUSCASEHOLDSITSINVARIANTS"));
        assertFalse(GameTestClassFilter.excluded(x, "StressGameTest", "setUp"), "a batch hook is never dropped");
        assertFalse(GameTestClassFilter.excluded(x, "OtherGameTest", "everyCorpusCaseHoldsItsInvariants"),
                "a method name alone is not enough: it is Class.method");
        assertFalse(GameTestClassFilter.excluded(Set.of(), "StressGameTest", "everyCorpusCaseHoldsItsInvariants"),
                "no register, nothing left out");
    }

    @Test
    void theBedrockBatchsTestsAreRegisteredOnlyWhenTheBatchRuns() {
        // each waits for a Bedrock bot only that run starts — registered without it, it would time
        // out, and passing it would report coverage nobody measured.
        assertFalse(GameTestClassFilter.bedrockAllows("BedrockBatchGameTest", false));
        assertTrue(GameTestClassFilter.bedrockAllows("BedrockBatchGameTest", true));
        assertTrue(GameTestClassFilter.bedrockAllows("JoinWordsGameTest", false), "every other class is untouched");
    }

    @Test
    void theCoverageMapsPickOfBedrockCasesFiltersOnlyTheBedrockBatch() {   //
        assertTrue(GameTestClassFilter.bedrockCaseAllowed("BedrockBatchGameTest", "watch_panel_grid", null), "no list: every case");
        assertTrue(GameTestClassFilter.bedrockCaseAllowed("BedrockBatchGameTest", "watch_panel_grid", "a,watch_panel_grid"));
        assertFalse(GameTestClassFilter.bedrockCaseAllowed("BedrockBatchGameTest", "watch_panel_grid", "apprentice_pocket"));
        assertTrue(GameTestClassFilter.bedrockCaseAllowed("JoinWordsGameTest", "x", "apprentice_pocket"), "never another class");
    }

    @Test
    void oneTestOfAClassRunsByItselfAndCaseNeverMatters() {
        // a retry names one test, in the lower case of its id; the rest of its class stays out
        Set<String> f = GameTestClassFilter.parse("mcpgametest.handshakeworks,WishGameTest");
        assertTrue(GameTestClassFilter.allows(f, "McpGameTest"), "a class one of whose tests is named registers");
        assertTrue(GameTestClassFilter.allowsMethod(f, "McpGameTest", "handshakeWorks"));
        assertFalse(GameTestClassFilter.allowsMethod(f, "McpGameTest", "toolsList"), "only the named test of it");
        assertTrue(GameTestClassFilter.allowsMethod(f, "WishGameTest", "anything"), "a bare class keeps every test");
        assertFalse(GameTestClassFilter.allows(f, "McpGameTestX"), "a prefix is not a class");
        assertTrue(GameTestClassFilter.allowsMethod(Set.of(), "AnyGameTest", "any"), "no filter, every test");
    }

    @Test
    void theDenyListTakesEveryClassButItsOwn() {
        // the first job of a split run: whatever no other job named, a class nobody has heard of included
        Set<String> deny = GameTestClassFilter.parse("McpGameTest,WishGameTest");
        assertFalse(GameTestClassFilter.notDenied(deny, "mcpgametest"));
        assertTrue(GameTestClassFilter.notDenied(deny, "BrandNewGameTest"));
        assertTrue(GameTestClassFilter.notDenied(Set.of(), "McpGameTest"), "an empty deny list denies nothing");
    }
}
