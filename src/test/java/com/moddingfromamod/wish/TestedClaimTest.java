package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * a note may not claim a test when nothing in the mod tested anything.
 *
 * <p>The cry-wolf tests below are the load-bearing ones. A finish-time guard that fires on ordinary
 * honest notes is a guard the agent learns to route around, and then the real finding goes with it
 * (WISH_LINTERS P6). Most of this file is therefore about what must NOT fire.
 */
class TestedClaimTest {

    /** The real note, from the drawbridge cell that opened the issue. */
    private static final String DRAWBRIDGE =
            "Pull the lever and three chunks of stone brick slide out across the moat so you can "
            + "walk over. Pull it again and the bridge pulls back in. I tested it both ways!";

    @Test
    @DisplayName("the drawbridge note is caught, and the complaint quotes the claim")
    void theRealCaseIsCaught() {
        String claim = TestedClaim.claim(DRAWBRIDGE);
        assertNotNull(claim, "\"I tested it both ways\" is the whole reason this exists");
        assertTrue(claim.contains("tested it both ways"));
        assertFalse(claim.contains("Pull the lever"),
                "quoting the whole note buries the finding inside the note");
    }

    @Test
    @DisplayName("it fires only when nothing checkable took part")
    void itIsAConjunction() {
        // The same note is fine on a wish that built something — then the ordinary guards apply,
        // an oracle really did run, and the claim may be perfectly true.
        assertFalse(TestedClaim.nothingCheckableWasUsed(List.of("contraption:mill"), List.of()));
        assertTrue(TestedClaim.nothingCheckableWasUsed(List.of(), List.of()));
        assertTrue(TestedClaim.nothingCheckableWasUsed(null, null));
        assertTrue(TestedClaim.nothingCheckableWasUsed(List.of(),
                List.of("run_command", "give_items", "photograph")),
                "commands and a photo are not a check");
    }

    @Test
    @DisplayName("a REFINEMENT is not an unverifiable wish, even though it makes nothing")
    void aModifyRungMustNotBeRefused() {
        // THE BUG THIS TEST EXISTS FOR, and it was in the first cut of this guard.
        //
        // The first version asked only whether the wish MADE anything. But MadeKind.forTool records
        // makers and nothing else — modify_contraption, modify_train, feed_contraption and every
        // verify_* are absent from it by design, and Marshal says so outright: "a rung that
        // MODIFIES records nothing in made()".
        //
        // So "make my factory taller" or "fill up this tank I was working on" would have been
        // REFUSED for saying it tested something, having called verify_contraption and read a real
        // answer. That is a false positive landing squarely on machines and trains — the only two
        // classes with a modify path — i.e. the cry-wolf failure this guard claims to avoid, on the
        // wishes most likely to be honest about verifying.
        for (String editOrCheck : List.of(
                "verify_datapack", "modify_mob", "inspect_creature")) {
            assertFalse(TestedClaim.nothingCheckableWasUsed(List.of(), List.of(editOrCheck)),
                    editOrCheck + " makes nothing, so made() is empty — but the mod can check what "
                    + "it touched, and asking the oracle is exactly what a test IS");
        }
    }

    @Test
    @DisplayName("every artifact-bearing tool is on the checkable list")
    void theCheckableListDoesNotDriftFromTheToolset() {
        // A new maker or verifier added without an entry here silently starts refusing honest
        // notes from its whole wish class, which is how this went wrong the first time.
        for (String maker : List.of(
                "build_structure", "sculpt_creature",
                "define_creature", "author_datapack", "make_item", "paint_surface")) {
            assertTrue(TestedClaim.checkableTools().contains(maker),
                    maker + " produces an artifact the mod can check and must be listed");
        }
        assertFalse(TestedClaim.checkableTools().contains("run_command"),
                "run_command is the whole reason this guard exists — listing it disarms it");
        assertFalse(TestedClaim.checkableTools().contains("photograph"),
                "a photograph is not a check: #218 is an entire issue about a picture that "
                + "looked right and was not");
    }

    @Test
    @DisplayName("a wish that made nothing AND claimed nothing is left alone")
    void makingNothingIsNotItselfAFault() {
        // "give me some diamonds", "make it daytime", a question answered in a sentence. These
        // build nothing and are completely correct; only a CLAIM is refused.
        for (String honest : List.of(
                "Here are your diamonds!",
                "It's daytime now — look up!",
                "Pull the lever and the bridge slides out across the moat.",
                "I built you a bridge out of stone bricks.",
                "I couldn't test it, but pull the lever and see what happens.")) {
            assertNull(TestedClaim.claim(honest),
                    "this note claims no test and must pass untouched: " + honest);
        }
    }

    @Test
    @DisplayName("a prediction in the child's own register is not a claim")
    void itWorksIsAPredictionNotAVouch() {
        // The distinction the whole check turns on: describing the thing vs vouching for it.
        // "it works" is how a note to a seven-year-old ends. Firing on it would object to most
        // honest notes ever written, and P8 asks what the note looks like if the agent obeys —
        // stripping "it works" from every note makes them worse, not more honest.
        for (String prediction : List.of(
                "Pull it and you'll see — it works!",
                "It works both ways, up and down.",
                "Try it, it really works!")) {
            assertNull(TestedClaim.claim(prediction),
                    "a prediction is not a claim about work the agent did: " + prediction);
        }
    }

    @Test
    @DisplayName("first-person past-tense verification verbs are the trigger")
    void theTriggerIsAVouch() {
        for (String vouch : List.of(
                "I tested it and it opens.",
                "I checked both directions.",
                "I verified the lever works.",
                "I made sure it closes again.",
                "I double-checked it.",
                "I watched it slide out and back.")) {
            assertNotNull(TestedClaim.claim(vouch), "this vouches for work nothing recorded: " + vouch);
        }
    }

    @Test
    @DisplayName("the remedy is named, and following it costs one sentence")
    void theRefusalNamesTheWayOut() {
        String r = TestedClaim.refusal("I tested it both ways");
        assertTrue(r.contains("I tested it both ways"), "it must quote what was actually written");
        assertTrue(r.contains("author_datapack") && r.contains("build_structure"),
                "P2: name the remedy, not just the fault");
        assertTrue(r.contains("could not test it"),
                "the cheaper way out — keep the build, drop the claim — has to be spelled out, or "
                + "the agent reads this as 'rebuild it' and rebuilds a bridge that was fine");
        assertTrue(r.contains("fine one") || r.contains("exactly right"),
                "it must not read as an objection to a redstone answer, which is legitimate");
    }

    @Test
    @DisplayName("case and punctuation do not let a claim through")
    void matchingIsNotBrittleAboutCase() {
        assertNotNull(TestedClaim.claim("I TESTED IT BOTH WAYS!"));
        assertNotNull(TestedClaim.claim("...and I've checked it opens."));
        // The clause runs from the last sentence break, so a leading ellipsis ends the previous
        // one and the quote starts at "and" — which is the right context to hand back.
        assertEquals("and I've checked it opens", TestedClaim.claim("...and I've checked it opens."));
    }

    @Test
    @DisplayName("an empty or missing note claims nothing")
    void emptyNotes() {
        assertNull(TestedClaim.claim(null));
        assertNull(TestedClaim.claim(""));
        assertNull(TestedClaim.claim("   "));
    }
}
