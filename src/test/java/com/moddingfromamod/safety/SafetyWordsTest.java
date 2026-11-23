package com.moddingfromamod.safety;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every string a child hears, checked against the rules that were paid for before.
 *
 * <p>These are not style assertions. Each one is a line somebody wrote, watched land on a
 * seven-year-old, and changed: "I don't know how to do that one yet" read as a refusal while a
 * giant airplane was being built for him, and {@code VILLAGER_NO} is literally the "no" noise.
 * A safety message is the one message that is genuinely a refusal, so it has the least room to be
 * careless about it.
 */
class SafetyWordsTest {

    /** Phrasings this project has already removed once, each with the reason it was removed. */
    private static final List<String> FORBIDDEN = List.of(
            "i don't know how",      // WishFeedback: read as "never", while it was being built
            "i dont know how",
            "you shouldn't",         // blames him
            "you should not",
            "not allowed",           // a rule about him rather than a fact about the game
            "bad wish",
            "naughty",
            "inappropriate",         // vocabulary he does not have, and an invitation to ask
            "unsafe",
            "error",
            "sorry, i can't");       // a full stop with nothing after it

    /**
     * Something has to be happening NEXT in every one of them.
     *
     * <p>Two shapes qualify and both are deliberate: a refusal invites the next wish ("wish for
     * something else", "try again"), and the redirect announces a build that is starting right now
     * ("here goes"). What must never appear is a message that simply ends — that is the failure
     * mode a refusal has and an ordinary message does not.
     */
    private static final List<String> FORWARD = List.of("wish", "try", "again", "here goes");

    @Test
    void everyChildFacingStringInvitesTheNextWishAndBlamesNobody() {
        for (String s : SafetyWords.all()) {
            String lower = s.toLowerCase(Locale.ROOT);
            for (String bad : FORBIDDEN) {
                assertFalse(lower.contains(bad),
                        "a child-facing safety string says \"" + bad + "\": " + s);
            }
            assertTrue(FORWARD.stream().anyMatch(lower::contains),
                    "this one leaves him with nowhere to go, which is the failure mode a refusal "
                            + "has and an ordinary message does not: " + s);
            assertTrue(s.length() <= 140,
                    "too long to read at a glance in chat (" + s.length() + "): " + s);
            assertFalse(s.isBlank());
        }
        assertEquals(5, SafetyWords.all().size(),
                "a new child-facing string was added without being listed in all(), so nothing "
                        + "above is checking it");
    }

    @Test
    void theWellbeingLineSaysTheWishIsSTILLHAPPENING() {
        // §12.1 decision 1B: this is the one message here that is NOT a refusal, and nothing is
        // being withheld. A warm sentence followed by silence would read as the wish having been
        // taken away for saying it, which is the single lesson this must not teach — so the
        // promise has to be in the sentence itself rather than in what happens next.
        String s = SafetyWords.sayWellbeingNoticed();
        String lower = s.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("still"), "it must say the wish goes on: " + s);
        assertTrue(lower.contains("wish"), "…and name the wish while saying it: " + s);
        assertFalse(lower.contains("can't") || lower.contains("cannot")
                        || lower.contains("instead"),
                "this is not a refusal and must not read like one: " + s);
    }

    @Test
    void theWellbeingLinePointsAtAGROWNUPAndNotAtAService() {
        // A seven-year-old's answer to distress is a person in the room. A helpline number, a
        // website or "talk to someone" is adult vocabulary aimed at an adult reader, and this is
        // read by the child.
        String lower = SafetyWords.sayWellbeingNoticed().toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("grown-up"), "no grown-up is named");
        for (String adult : List.of("helpline", "hotline", "http", "www", "counsel", "therap",
                "professional", "crisis")) {
            assertFalse(lower.contains(adult),
                    "a child is being handed adult vocabulary: " + adult);
        }
    }

    @Test
    void theWellbeingLineNamesNoCategoryAndAsksNoQuestion() {
        // §5's rule survives even here: the exception is that it acknowledges him, not that it
        // explains him to himself. And a question a child owes no answer to is worse than none —
        // an unanswered prompt from a game reads as being ignored by the thing that asked.
        String s = SafetyWords.sayWellbeingNoticed();
        String lower = s.toLowerCase(Locale.ROOT);
        for (String category : List.of("hurt", "harm", "suicide", "safe", "worried", "danger",
                "kill")) {
            assertFalse(lower.contains(category),
                    "the line names what he typed, which is a vocabulary lesson he did not ask "
                            + "for: " + category);
        }
        assertFalse(s.contains("?"), "it asks him something: " + s);
    }

    @Test
    void theBlockMessageIsAboutTheGameRatherThanAboutHim() {
        String s = SafetyWords.sayBlocked().toLowerCase(Locale.ROOT);
        assertTrue(s.contains("minecraft"),
                "'not a Minecraft kind of wish' is doing the work here: it is true, it is about "
                        + "the game, and it carries no vocabulary he has to ask a grown-up about");
        assertFalse(s.contains("you "), "the sentence must not turn to face him");
    }

    @Test
    void theRedirectShowsTheChildExactlyWhatWillBeMade() {
        // §2: every redirect is shown BEFORE the agent starts, so nothing is ever quietly
        // substituted. That promise is only kept if the text itself appears verbatim.
        String redirect = "a zombie that bonks villagers with a rubber chicken";
        String said = SafetyWords.sayRedirect(redirect);
        assertTrue(said.contains(redirect), "the redirect must appear verbatim: " + said);
        assertTrue(said.startsWith(SafetyWords.REDIRECT_PREFIX));
    }

    @Test
    void anEmptyRedirectFallsBackRatherThanAnnouncingNothing() {
        // A blank one would produce "I'll make it like this instead:  — here goes!", which is a
        // lie by omission about a build that is not happening.
        for (String empty : List.of("", "   ")) {
            assertEquals(SafetyWords.sayBlocked(), SafetyWords.sayRedirect(empty));
        }
        assertEquals(SafetyWords.sayBlocked(), SafetyWords.sayRedirect(null));
    }

    @Test
    void theUnavailableMessageSoundsLikeAnOutageAndNotLikeAJudgement() {
        String s = SafetyWords.sayUnavailable().toLowerCase(Locale.ROOT);
        assertTrue(s.contains("moment") || s.contains("again"),
                "an outage is a temporary state of the world; if it does not sound temporary a "
                        + "child learns the wrong lesson from a flaky CLI");
        assertTrue(SafetyWords.sayUnavailable().contains("😴"));
    }

    @Test
    void thePlanStopMessageStillOffersADifferentAngle() {
        assertTrue(SafetyWords.sayPlanStopped().toLowerCase(Locale.ROOT).contains("different way"),
                "stopping mid-wish is the harshest thing this layer does, so it is the one that "
                        + "most needs to end pointing forward");
    }
}
