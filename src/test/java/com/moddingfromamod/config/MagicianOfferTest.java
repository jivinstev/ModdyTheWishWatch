package com.moddingfromamod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** steps 2–3: what the setup offers, and the plain reason for what it does not. */
class MagicianOfferTest {

    private static final SupportedModels.Model OPUS = SupportedModels.find(SupportedModels.OPUS).orElseThrow();
    private static final SupportedModels.Model SPARK = SupportedModels.find(SupportedModels.SPARK).orElseThrow();
    private static final SupportedModels.Model EMBER = SupportedModels.find(SupportedModels.EMBER).orElseThrow();
    private static final SupportedModels.Model HAIKU = SupportedModels.find(SupportedModels.HAIKU).orElseThrow();
    private static final SupportedModels.Mode SUB = SupportedModels.Mode.SUBSCRIPTION;
    private static final SupportedModels.Mode API = SupportedModels.Mode.API;

    private static HarnessProbe.Result works(SupportedModels.Harness h, boolean sub, boolean api) {
        return new HarnessProbe.Result(h, true, "1.0", Map.of(SUB, sub ? "" : "not signed in", API, api ? "" : "refused"));
    }

    private static final Map<String, String> BOTH = Map.of("ANTHROPIC_API_KEY", "k", "MUSE_API_KEY", "k");

    @Test
    void nothingIsOfferedBeforeTheProgramsAreChecked() {
        assertEquals(Optional.of(MagicianOffer.CHECK_FIRST), MagicianOffer.blocked(OPUS, SUB, Map.of(), BOTH::get, false));
    }

    @Test
    void anApiKeyIsOfferedOnlyWhenItsVariableIsSet() {
        var probed = Map.of(SupportedModels.Harness.CLAUDE_CODE, works(SupportedModels.Harness.CLAUDE_CODE, true, true));
        var none = MagicianOffer.blocked(OPUS, API, probed, k -> null, false);
        assertTrue(none.orElse("").contains("ANTHROPIC_API_KEY"), none.toString());
        assertTrue(MagicianOffer.blocked(OPUS, API, probed, BOTH::get, false).isEmpty());
    }

    @Test
    void aSignInThatDoesNotAnswerIsNamed() {
        var probed = Map.of(SupportedModels.Harness.CLAUDE_CODE, works(SupportedModels.Harness.CLAUDE_CODE, false, true));
        var b = MagicianOffer.blocked(HAIKU, SUB, probed, BOTH::get, false);
        assertTrue(b.orElse("").contains("sign-in") && b.get().contains("not signed in"), b.toString());
    }

    @Test
    void aMissingProgramSaysToInstallIt() {
        var probed = Map.of(SupportedModels.Harness.MUSE_CODE, HarnessProbe.Result.missing(SupportedModels.Harness.MUSE_CODE));
        assertEquals(Optional.of("Install Muse Code first."), MagicianOffer.blocked(EMBER, SUB, probed, BOTH::get, false));
    }

    @Test
    void aMuseWishModelRunsOnMuseCodeEachWayItAnswers() {
        // Spark on Muse Code's sign-in and on MUSE_API_KEY, each offered only where Muse Code answered that way.
        var signIn = Map.of(SupportedModels.Harness.MUSE_CODE, works(SupportedModels.Harness.MUSE_CODE, true, false));
        assertTrue(MagicianOffer.blocked(SPARK, SUB, signIn, BOTH::get, false).isEmpty());
        assertTrue(MagicianOffer.blocked(SPARK, API, signIn, BOTH::get, false).orElse("").contains("API key"));
        var claudeOnly = Map.of(SupportedModels.Harness.CLAUDE_CODE, works(SupportedModels.Harness.CLAUDE_CODE, true, true));
        assertEquals(Optional.of(MagicianOffer.CHECK_FIRST), MagicianOffer.blocked(SPARK, API, claudeOnly, BOTH::get, false),
                "Claude Code no longer runs a Muse wish model");
    }

    @Test
    void wonderIsOfferedBothWaysOnThePlayersOwnMuseCode() {
        // D27: a player's own key may pick Wonder (its price is shown); a sign-in may too.
        var wonder = SupportedModels.find(SupportedModels.WONDER).orElseThrow();
        var both = Map.of(SupportedModels.Harness.MUSE_CODE, works(SupportedModels.Harness.MUSE_CODE, true, true));
        assertTrue(MagicianOffer.blocked(wonder, SUB, both, BOTH::get, false).isEmpty());
        assertTrue(MagicianOffer.blocked(wonder, API, both, BOTH::get, false).isEmpty());
        assertTrue(MagicianOffer.blocked(wonder, SUB, both, BOTH::get, true).isEmpty(), "a server may use the sign-in");
    }

    @Test
    void claudesSubscriptionIsRefusedOnAServer() {
        var probed = Map.of(SupportedModels.Harness.CLAUDE_CODE, works(SupportedModels.Harness.CLAUDE_CODE, true, true));
        assertTrue(MagicianOffer.blocked(OPUS, SUB, probed, BOTH::get, true).orElse("").contains("API key"));
        assertTrue(MagicianOffer.blocked(OPUS, probed, BOTH::get, true).isEmpty(), "the key still works there");
    }

    @Test
    void picksSayWhatIsMissingInWords() {
        assertEquals(4, new MagicianPicks("", "", "", "").problems(BOTH::get, false).size());
        var p = new MagicianPicks(SupportedModels.OPUS, "api", SupportedModels.EMBER, "subscription");
        assertTrue(p.complete());
        assertTrue(p.problems(BOTH::get, false).isEmpty());
        assertFalse(p.problems(k -> null, false).isEmpty(), "Opus by key with no ANTHROPIC_API_KEY");
        assertTrue(p.problems(k -> null, false).get(0).contains("ANTHROPIC_API_KEY"));
        var fable = new MagicianPicks("fable", "api", SupportedModels.EMBER, "api");
        assertFalse(fable.complete(), "a model not in the table is never a pick");
    }
}
