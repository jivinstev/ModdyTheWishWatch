package com.moddingfromamod.safety;

import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.RuntimeConfig;
import com.moddingfromamod.safety.SafetyInputGate.Act;
import com.moddingfromamod.safety.SafetyInputGate.Decision;
import com.moddingfromamod.safety.SafetyVerdict.Band;
import com.moddingfromamod.safety.SafetyVerdict.Provenance;
import com.moddingfromamod.safety.SafetyVerdict.Reason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The INPUT decision, with no model, no queue, no marshal and no child.</b>
 *
 * <p>The whole INPUT column of {@code docs/CHILD_SAFETY_PLAN.md} §2 in milliseconds — including the
 * two rows that are otherwise reachable only through a real agent and a real ten-second grade:
 * the redirect a child reads, and shadow mode, which by definition does nothing observable.
 *
 * <p>Written first, because CS-S1b's own finding is that the expensive mistake in this layer is a
 * false positive — and that is not something a live run can be asked about cheaply enough to ask
 * twice.
 */
class SafetyInputGateTest {

    private static final String MODE = ModdingFromAModMod.MODID + ".childsafety";
    private static final String STRICTNESS = ModdingFromAModMod.MODID + ".childSafetyStrictness";
    private static final String UNAVAILABLE = ModdingFromAModMod.MODID + ".childSafetyUnavailable";

    private static final String[] ALL = {MODE, STRICTNESS, UNAVAILABLE};

    /** The child's own words, so nothing here can accidentally assert on a hard-coded string. */
    private static final String WISH = "make a zombie that bonks villagers";

    @BeforeEach
    void enforcing() {
        for (String k : ALL) {
            System.clearProperty(k);
        }
        // THE ENFORCING ARM, EXPLICITLY. The shipped default is SHADOW (§8), and every row below is
        // about what enforcement does; the shadow tests set it themselves.
        System.setProperty(MODE, "on");
        RuntimeConfig.invalidate();
    }

    @AfterEach
    void clean() {
        for (String k : ALL) {
            System.clearProperty(k);
        }
        RuntimeConfig.invalidate();
    }

    private static void set(String key, String value) {
        System.setProperty(key, value);
        RuntimeConfig.invalidate();
    }

    /** A model verdict with one axis at a band. */
    private static SafetyVerdict model(Band violence, String redirect, Reason... reasons) {
        return new SafetyVerdict(violence, Band.FINE, Band.FINE, Band.FINE,
                List.of(reasons), redirect, Provenance.HAIKU, SafetyVerdict.NO_STAGE);
    }

    /** What the lexicon returns for an explicit term. */
    private static SafetyVerdict lexiconClearly(Band sexual) {
        return new SafetyVerdict(Band.FINE, sexual, Band.FINE, Band.FINE,
                List.of(Reason.SEXUAL), "", Provenance.LEXICON, SafetyVerdict.NO_STAGE);
    }

    // ── §2's INPUT column, row by row ─────────────────────────────────────────────────────────

    @Test
    void fineIsAssignedWithNothingSaidToAnybody() {
        Decision d = SafetyInputGate.decide(null, SafetyVerdict.fine(Provenance.HAIKU), WISH);
        assertEquals(Act.PASS, d.act());
        assertFalse(d.refuses());
        assertEquals("", d.childMessage());
        assertEquals("", d.guidance());
        assertEquals(WISH, d.runText(), "a wish nobody objected to is granted as it was typed");
    }

    @Test
    void maybePassesAndTheGuidanceRidesOutOnTheWake() {
        Decision d = SafetyInputGate.decide(null, model(Band.MAYBE, "", Reason.GORE), WISH);
        assertEquals(Act.NOTE, d.act());
        assertFalse(d.refuses());
        assertEquals(WISH, d.runText(), "MAYBE is the child's own wish and stays so (§2)");
        assertEquals("", d.childMessage(), "a MAYBE is never announced to the child");
        assertTrue(d.guidance().contains("cartoon"),
                "the violence axis wants the cartoon advice: " + d.guidance());
        assertTrue(d.guidance().contains("gore"), "the reason rides along: " + d.guidance());
        // §5: the child is never told what was wrong with his wish, and an agent left to itself
        // narrates its own guidance cheerfully.
        assertTrue(d.guidance().toLowerCase().contains("do not mention"), d.guidance());
        assertTrue(d.guidance().toLowerCase().contains("not a refusal"),
                "a builder that reads this as a refusal will hedge the whole wish: " + d.guidance());
    }

    @Test
    void theGuidanceIsAboutTheAxisThatEarnedTheBand() {
        SafetyVerdict hate = new SafetyVerdict(Band.FINE, Band.FINE, Band.MAYBE, Band.FINE,
                List.of(), "", Provenance.HAIKU, SafetyVerdict.NO_STAGE);
        SafetyVerdict danger = new SafetyVerdict(Band.FINE, Band.FINE, Band.FINE, Band.MAYBE,
                List.of(), "", Provenance.HAIKU, SafetyVerdict.NO_STAGE);
        assertTrue(SafetyInputGate.guidanceFor(hate).contains("kind"));
        assertTrue(SafetyInputGate.guidanceFor(danger).contains("Minecraft"));
        // The DANGER line has to say what is ALLOWED as well, or a builder reads "no real weapons"
        // with no counterweight and refuses a sword. CS-S4's ledger found that exact gap.
        assertTrue(SafetyInputGate.guidanceFor(danger).contains("TNT"),
                SafetyInputGate.guidanceFor(danger));
    }

    @Test
    void likelyWithARedirectRunsOnTheRedirectAndTheChildIsToldFirst() {
        String safer = "a zombie that bonks villagers with a rubber chicken";
        Decision d = SafetyInputGate.decide(null, model(Band.LIKELY, safer, Reason.GORE), WISH);
        assertEquals(Act.REDIRECT, d.act());
        assertFalse(d.refuses());
        assertEquals(safer, d.runText(), "the redirected text is what the agent gets (§2)");
        assertTrue(d.redirectShown(), "the log field that makes §2's promise checkable");
        // VERBATIM, because that is the whole transparency mechanism: a child who reads a
        // paraphrase has still had his wish quietly substituted.
        assertTrue(d.childMessage().contains(safer), d.childMessage());
        assertEquals(SafetyWords.sayRedirect(safer), d.childMessage());
    }

    @Test
    void likelyWithNOTHINGToOfferIsABlockRatherThanAnAssignment() {
        // The correction to CS-S1b's map. A redirect that does not exist is not an action, and the
        // wish would otherwise be assigned unchanged -- which is the one outcome the band does not
        // mean. §2 gives a redirect to LIKELY alone, so there is nowhere else for this to go.
        Decision d = SafetyInputGate.decide(null, model(Band.LIKELY, "", Reason.GORE), WISH);
        assertEquals(Act.BLOCK, d.act());
        assertTrue(d.refuses());
        assertEquals(SafetyWords.sayBlocked(), d.childMessage());
        assertFalse(d.redirectShown());
    }

    // ── decision 12.5: the redirect goes through L0 before anybody sees it ────────────────────

    @Test
    void aRedirectTHEFLOORREFUSESIsNoRedirectAtAll() {
        // The redirect is the one free-text field a MODEL writes and a CHILD reads -- shown to him
        // as his own next wish. So it goes through the same floor every other piece of text does,
        // and a CLEARLY term in it means there is no usable redirect: §2's rule then makes a
        // LIKELY with nothing to offer a BLOCK, which is the row above this one.
        //
        // A real lexicon term, not a fixture: the point is that the SHIPPED list is consulted.
        Decision d = SafetyInputGate.decide(null,
                model(Band.LIKELY, "two villagers that have sex in a cottage", Reason.GORE), WISH);
        assertEquals(Act.BLOCK, d.act(),
                "a redirect the floor refuses is not a redirect, so LIKELY has nothing to offer");
        assertTrue(d.refuses());
        assertFalse(d.redirectShown(), "nothing was substituted, so nothing was shown");
        assertEquals(WISH, d.runText(), "the agent must not be handed the refused string either");
        assertEquals(SafetyWords.sayBlocked(), d.childMessage());
    }

    @Test
    void aCleanRedirectIsUntouched() {
        // The control, and the one that would catch an over-eager floor: the vast majority of
        // redirects are ordinary sentences and must pass exactly as they did before.
        String safer = "a zombie that bonks villagers with a rubber chicken";
        Decision d = SafetyInputGate.decide(null, model(Band.LIKELY, safer, Reason.GORE), WISH);
        assertEquals(Act.REDIRECT, d.act());
        assertEquals(safer, d.runText());
        assertTrue(d.redirectShown());
    }

    @Test
    void theVerdictKEEPSTheRefusedStringWhileRefusingToOfferIt() {
        // The two halves the log then writes (asserted on the RECORD itself in
        // SafetyActionLogTest): the raw field must survive so a reader can see what was refused,
        // and redirectIfUsable must refuse to hand it on. A verdict that erased the string would
        // make "how often does the model propose a redirect the floor will not have" uncountable.
        SafetyVerdict refused = model(Band.LIKELY, "two villagers that have sex", Reason.GORE);
        assertTrue(refused.redirectRejected());
        assertEquals("two villagers that have sex", refused.redirect(),
                "the raw field survives: a verdict that erased the string would leave the log "
                        + "unable to say what was refused");
        assertTrue(refused.redirectIfUsable().isEmpty());

        SafetyVerdict fine = model(Band.LIKELY, "a rubber chicken", Reason.GORE);
        assertFalse(fine.redirectRejected());
        assertEquals("a rubber chicken", fine.redirectIfUsable().orElse(""));
    }

    @Test
    void clearlyIsBlockedAndGetsNoCleverRewording() {
        // §2: a CLEARLY wish gets no redirect even when the model proposed one, because that
        // invites "how close can I get". redirectIfUsable() is where the rule lives; this is the
        // assertion that the gate asks it rather than reading the field.
        Decision d = SafetyInputGate.decide(null,
                model(Band.CLEARLY, "something almost as bad", Reason.SEXUAL), WISH);
        assertEquals(Act.BLOCK, d.act());
        assertTrue(d.refuses());
        assertEquals(SafetyWords.sayBlocked(), d.childMessage());
        assertFalse(d.redirectShown());
        assertNotEquals(d.runText(), "something almost as bad");
    }

    // ── UNAVAILABLE, and the config that decides what it means ────────────────────────────────

    @Test
    void unavailableOpenPassesAndUnavailableClosedRefuses() {
        // Explicit, both ways. (The UNSET default is closed in a real run and open in any test
        // run — ChildSafetyArmTest pins both, with RunContext.FORCE_REAL_RUN for the first.)
        set(UNAVAILABLE, "open");
        assertEquals(Act.UNAVAILABLE_OPEN,
                SafetyInputGate.decide(null, SafetyVerdict.unavailable(), WISH).act());
        set(UNAVAILABLE, "closed");
        Decision d = SafetyInputGate.decide(null, SafetyVerdict.unavailable(), WISH);
        assertEquals(Act.UNAVAILABLE_CLOSED, d.act());
        assertTrue(d.refuses());
        // The "genie is resting" register, not the block wording: an outage is a temporary state of
        // the world, not a judgement about the wish, and a child who cannot hear the difference
        // learns the wrong lesson from a flaky subprocess.
        assertEquals(SafetyWords.sayUnavailable(), d.childMessage());
    }

    // ── MODDY IS TIRED — closed for EVERY grant, whatever `unavailable` says ────────

    @Test
    void anUnobtainedGradeWhileTiredIsClosedWithTheTiredLineEvenUnderOpen() {
        // Even an operator who set `open` gets a tired refusal when the grade could not be
        // OBTAINED while Moddy is tired: `open` is about one malformed reply, not an outage.
        set(UNAVAILABLE, "open");
        Decision d = SafetyInputGate.decide(null, SafetyVerdict.unavailable(), WISH, true);
        assertEquals(Act.UNAVAILABLE_CLOSED, d.act(), "the existing word for 'closed on outage'");
        assertTrue(d.refuses());
        assertEquals(com.moddingfromamod.health.OpsWords.sayTired(), d.childMessage(),
                "the tired line, not the block line: it is about Moddy, not about the wish");
        // ...and the same grade when Moddy is NOT tired follows the dial (here, `open`).
        assertEquals(Act.UNAVAILABLE_OPEN,
                SafetyInputGate.decide(null, SafetyVerdict.unavailable(), WISH, false).act());
    }

    @Test
    void aLexiconMaybeWithNoModelGradeIsStillAGrantNobodyGraded() {
        // combine() hands back the lexicon's own verdict here, so the combined answer is NOT
        // unavailable — and without the model check this wish would sail through a tired Moddy.
        SafetyVerdict lexMaybe = new SafetyVerdict(Band.MAYBE, Band.FINE, Band.FINE, Band.FINE,
                List.of(Reason.GORE), "", Provenance.LEXICON, SafetyVerdict.NO_STAGE);
        Decision d = SafetyInputGate.decide(lexMaybe, SafetyVerdict.unavailable(), WISH, true);
        assertEquals(Act.UNAVAILABLE_CLOSED, d.act());
    }

    @Test
    void tirednessNeverOverridesABlockOrShadow() {
        assertEquals(Act.BLOCK, SafetyInputGate.decide(lexiconClearly(Band.CLEARLY),
                SafetyVerdict.unavailable(), WISH, true).act(), "a refusal is a refusal");
        // A model that DID answer is not tiredness, however the caller flags it.
        assertEquals(Act.PASS, SafetyInputGate.decide(null,
                SafetyVerdict.fine(Provenance.HAIKU), WISH, true).act());
        set(MODE, "shadow");
        Decision shadow = SafetyInputGate.decide(null, SafetyVerdict.unavailable(), WISH, true);
        assertEquals(Act.PASS, shadow.act(), "shadow acts on nothing");
        assertEquals(Act.UNAVAILABLE_CLOSED, shadow.assessed(), "and still says what it would do");
    }

    @Test
    void unavailableIsNotFineEvenThoughItsBandsAre() {
        // The distinction the log lives on. Both answers pass the wish under `open`, and only one
        // of them is a measurement.
        set(UNAVAILABLE, "open");
        assertEquals(Act.UNAVAILABLE_OPEN,
                SafetyInputGate.actionOf(SafetyVerdict.unavailable()));
        assertEquals(Act.PASS, SafetyInputGate.actionOf(SafetyVerdict.fine(Provenance.HAIKU)));
    }

    // ── the two graders, merged ─────────────────────────────────────────────────────────────

    @Test
    void aLexiconCLEARLYBeatsAModelFINE() {
        // CS-S1b measured it: "make two mobs have sex" reads LIKELY from Haiku and CLEARLY from the
        // lexicon. The shipped layer is L0 ∨ L1, so the arm's recall figure is a LOWER bound on it.
        Decision d = SafetyInputGate.decide(lexiconClearly(Band.CLEARLY),
                SafetyVerdict.fine(Provenance.HAIKU), WISH);
        assertEquals(Act.BLOCK, d.act());
        assertEquals(Provenance.LEXICON, d.verdict().provenance(),
                "who DECIDED is the question that field answers");
    }

    @Test
    void theModelStillDecidesWhenTheLexiconSaidNothing() {
        Decision d = SafetyInputGate.decide(null, model(Band.MAYBE, ""), WISH);
        assertEquals(Provenance.HAIKU, d.verdict().provenance());
    }

    @Test
    void aLexiconHitOverAnUNAVAILABLEModelIsStillAMeasurement() {
        // Otherwise the record would read UNAVAILABLE over a floor that fired, i.e. "nobody looked"
        // about a decision somebody made.
        Decision d = SafetyInputGate.decide(lexiconClearly(Band.CLEARLY),
                SafetyVerdict.unavailable(), WISH);
        assertEquals(Act.BLOCK, d.act());
        assertFalse(d.verdict().isUnavailable());
    }

    @Test
    void theReasonsOfBothGradersSurviveIntoTheRecord() {
        Decision d = SafetyInputGate.decide(lexiconClearly(Band.CLEARLY),
                model(Band.MAYBE, "", Reason.GORE), WISH);
        assertTrue(d.verdict().reasons().contains(Reason.SEXUAL));
        assertTrue(d.verdict().reasons().contains(Reason.GORE));
    }

    // ── the parent's dial ─────────────────────────────────────────────────────────────────────

    @Test
    void strictPromotesAMaybeIntoARedirectAndRelaxedDemotesALikely() {
        set(STRICTNESS, "strict");
        assertEquals(Act.BLOCK, SafetyInputGate.actionOf(model(Band.MAYBE, "")),
                "promoted to LIKELY with no redirect to offer, which is a block");
        assertEquals(Act.REDIRECT, SafetyInputGate.actionOf(model(Band.MAYBE, "a safer wish")));
        set(STRICTNESS, "relaxed");
        assertEquals(Act.NOTE, SafetyInputGate.actionOf(model(Band.LIKELY, "a safer wish")),
                "relaxed is log-and-guide, never a redirect");
    }

    @Test
    void aPromotedMaybeCarriesItsRedirectAllTheWayTHROUGH() {
        // 🔴 A DEFECT FOUND WHILE BUILDING CS-D3, not one of its four. `actionOf` decides on the
        // ADJUSTED verdict, so under `strict` a promoted MAYBE comes back REDIRECT -- and `decide`
        // was asking the UNADJUSTED verdict for the string, where redirectIfUsable() gates on
        // LIKELY and the raw band is still MAYBE. It answered EMPTY.
        //
        // So on any machine with child_safety_strictness=strict, every promoted wish told the
        // child "✨ I'll make it like this: ''" and handed the agent an EMPTY WISH -- which then
        // became the run's subject and the name on his progress bar. The comment on the branch
        // said "present by construction", and it was, of the other verdict.
        //
        // The test that existed asked `actionOf` alone, so it saw the promotion and never the
        // string: the whole bug lived in the gap between the two.
        set(STRICTNESS, "strict");
        String safer = "a zombie that bonks villagers with a rubber chicken";
        Decision d = SafetyInputGate.decide(null, model(Band.MAYBE, safer, Reason.GORE), WISH);
        assertEquals(Act.REDIRECT, d.act(), "strict promotes MAYBE to LIKELY, which redirects");
        assertEquals(safer, d.runText(), "the agent must be handed the redirect, not nothing");
        assertTrue(d.childMessage().contains(safer),
                "and the child must be shown the same words: " + d.childMessage());
        assertTrue(d.redirectShown());
    }

    @Test
    void aPromotedMaybeWithNoUsableRedirectIsStillABlockRatherThanAnEmptyWish() {
        // The other half of the same gap, and the one that must NOT become a redirect: promotion
        // with nothing to offer is §2's block. Asserting it keeps the fix above from being read as
        // "REDIRECT is now unconditional under strict".
        set(STRICTNESS, "strict");
        Decision d = SafetyInputGate.decide(null, model(Band.MAYBE, "", Reason.GORE), WISH);
        assertEquals(Act.BLOCK, d.act());
        assertEquals(WISH, d.runText(), "a blocked wish is not a substituted one");
        assertFalse(d.redirectShown());

        // …including when the floor is what removed it (decision 12.5), which is the two fixes
        // meeting: promoted to LIKELY, and its redirect refused, so there is nothing to offer.
        Decision refused = SafetyInputGate.decide(null,
                model(Band.MAYBE, "two villagers that have sex", Reason.GORE), WISH);
        assertEquals(Act.BLOCK, refused.act());
        assertFalse(refused.redirectShown());
    }

    @Test
    void neitherDialTouchesCLEARLY() {
        // The dial tunes the grey area. One that could switch off the unambiguous cases would be a
        // different feature, and a parent would have no way of knowing they had.
        for (String dial : new String[]{"relaxed", "standard", "strict"}) {
            set(STRICTNESS, dial);
            assertEquals(Act.BLOCK, SafetyInputGate.actionOf(model(Band.CLEARLY, "")), dial);
        }
    }

    @Test
    void theLOGGEDVerdictIsTheRawGradeRatherThanTheAdjustedOne() {
        // CS-S1b's rule, kept: the record already names the strictness, and logging the adjusted
        // bands instead would make every measured base rate a function of one machine's parent
        // setting -- so a floor would mean something different on every machine.
        set(STRICTNESS, "strict");
        Decision d = SafetyInputGate.decide(null, model(Band.MAYBE, "a safer wish"), WISH);
        assertEquals(Act.REDIRECT, d.act(), "the ACTION follows the dial");
        assertEquals(Band.MAYBE, d.verdict().overall(), "and the RECORD does not");
    }

    // ── the arms ──────────────────────────────────────────────────────────────────────────────

    @Test
    void shadowAssessesEverythingAndDoesNothing() {
        set(MODE, "shadow");
        Decision d = SafetyInputGate.decide(null,
                model(Band.CLEARLY, "", Reason.SEXUAL), WISH);
        assertEquals(Act.PASS, d.act(), "shadow acts on nothing");
        assertEquals(Act.BLOCK, d.assessed(), "and still says what it would have done");
        assertFalse(d.refuses());
        assertEquals(WISH, d.runText());
        assertEquals("", d.childMessage(), "nothing a child could notice");
        assertEquals("SHADOW_BLOCK", d.logAction());
    }

    @Test
    void shadowDoesNotEvenAppendTheGuidance() {
        // A line the agent reads and builds differently for IS an action, so a shadow arm that
        // appended it would have quietly become an enforcement arm -- and the base rate it was
        // collecting would be measuring its own interference.
        set(MODE, "shadow");
        Decision d = SafetyInputGate.decide(null, model(Band.MAYBE, ""), WISH);
        assertEquals("", d.guidance());
        assertEquals("SHADOW_NOTE", d.logAction());
    }

    @Test
    void shadowDoesNotRedirectTheRunTextEither() {
        set(MODE, "shadow");
        Decision d = SafetyInputGate.decide(null, model(Band.LIKELY, "a safer wish"), WISH);
        assertEquals(WISH, d.runText(), "the agent builds what the child typed");
        assertFalse(d.redirectShown());
        assertEquals("SHADOW_REDIRECT", d.logAction());
    }

    @Test
    void theControlArmGradesNothingAndDecidesNothing() {
        set(MODE, "off");
        Decision d = SafetyInputGate.decide(lexiconClearly(Band.CLEARLY),
                model(Band.CLEARLY, ""), WISH);
        assertEquals(Act.PASS, d.act());
        assertEquals(Act.PASS, d.assessed());
        assertEquals(WISH, d.runText());
    }

    @Test
    void theEnforcingArmWritesTheBareWordAndTheTwoVocabulariesMatch() {
        // The base rate collected in shadow has to predict the arm it was collected for, so every
        // action has to exist in both spellings and differ only by the prefix.
        for (Act a : Act.values()) {
            assertFalse(a.name().startsWith("SHADOW_"), a.name());
        }
        Decision d = SafetyInputGate.decide(null, model(Band.CLEARLY, ""), WISH);
        assertEquals("BLOCK", d.logAction());
    }

    // ── the shapes nothing may throw on ───────────────────────────────────────────────────────

    @Test
    void nullsAndBlanksDecideRatherThanThrow() {
        // Nothing to go on is an outage (here under explicit `open`, so it passes).
        set(UNAVAILABLE, "open");
        assertEquals(Act.UNAVAILABLE_OPEN, SafetyInputGate.actionOf(null));
        Decision d = SafetyInputGate.decide(null, null, null);
        assertEquals(Act.UNAVAILABLE_OPEN, d.act());
        assertEquals("", d.runText());
        assertEquals(SafetyVerdict.unavailable(), SafetyInputGate.combine(null, null));
    }
}
