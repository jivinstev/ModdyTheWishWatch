package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Gate A for the contextual gate.
 *
 * <p>The interesting half of these tests is the <b>negative</b> half. Over-flagging is not a
 * harmless bias here: it would push the mod's most-used wishes through a slow agent round-trip and
 * destroy the thing that makes the watch feel instant.
 */
class WishReferenceTest {

    @Test
    void wishesThatPointAtSomethingAreContextual() {
        for (String wish : new String[]{
                "make it bigger",
                "make it pink",
                "make them glow purple",
                "make him stronger",
                "make that one smaller",
                "make it red please",
                "can you make it scarier"}) {
            assertTrue(WishReference.isContextual(wish), "should be contextual: " + wish);
        }
    }

    @Test
    void backReferencesAreContextualOnTheirOwn() {
        for (String wish : new String[]{"do it again", "another one", "do that again please",
                                        "undo", "more"}) {
            assertTrue(WishReference.isContextual(wish), "should be contextual: " + wish);
        }
    }

    /**
     * <b>The one that matters.</b> Three flagship built-ins contain "it" and must stay instant —
     * gating on pronouns alone would break the most-used wishes in the mod.
     */
    @Test
    void theBuiltInWishesAreNotContextual() {
        for (String wish : new String[]{
                "make it rain chickens",
                "make it night and spooky",
                "make it sunny",
                "make it day",
                "give me treasure",
                "make me super fast",
                "build me a tower",
                "make me fly",
                "i want a giant slime",
                "make a mega chicken",
                "rainbow sheep",
                "i want a robot",
                "cake"}) {
            assertFalse(WishReference.isContextual(wish),
                    "must stay INSTANT and offline: " + wish);
        }
    }

    @Test
    void addingSomethingToAThingThatExistsIsContextual() {
        // The lighthouse case. This has a pronoun but no comparative and no colour, so it used to
        // read as a brand-new wish — and a learned castle template rebuilt the whole castle instead
        // of adding a lighthouse to the one he was looking at.
        assertTrue(WishReference.isContextual(
                "that's a cool castle in the sky, can you add a lighthouse on top of it?"));
        assertTrue(WishReference.isContextual("put a door on it"));
        assertTrue(WishReference.isContextual("add a tower next to them"));
        assertTrue(WishReference.isContextual("move it over there"));
    }

    @Test
    void describingOneNewThingWithPartsIsNotContextual() {
        // The other half of the rule: an attach word WITHOUT a pronoun still describes a single
        // thing to make from scratch, and must stay cacheable rather than costing an agent turn.
        assertFalse(WishReference.isContextual("build a castle with a lighthouse on top"));
        assertFalse(WishReference.isContextual("make a tower with a door"));
    }

    @Test
    void aNewThingDescribedWithARelativeClauseIsNotContextual() {
        // item 3: "that" and "it" point back at the thing he just named, and "into" is part
        // of where it sends him, not where something is attached. Read as pointing back, the agent
        // was told "it" might be one of his things and built a magic block instead of a creature.
        assertFalse(WishReference.isContextual(
                "a bubblehop that makes me float up into the air when i touch it"));
        assertFalse(WishReference.isContextual("make me an owl which flies behind me"));
        assertFalse(WishReference.isContextual("i want a robot who puts flowers on top of my house"));
        // ...while a wish that ACTS on something, or has no relative clause, still points back.
        assertTrue(WishReference.isContextual("add a lighthouse on top of it"));
        assertTrue(WishReference.isContextual("a hat on top of it"));
        assertTrue(WishReference.isContextual("a dragon that is bigger again"));
    }

    @Test
    void namingANewThingIsNotContextualEvenWithAColour() {
        // "a pink dragon" says exactly what to make; nothing needs resolving.
        assertFalse(WishReference.isContextual("i want a pink dragon"));
        assertFalse(WishReference.isContextual("give me a red sword"));
        assertFalse(WishReference.isContextual("summon a purple slime"));
    }

    @Test
    void pronounsAreMatchedAsWholeWordsNotSubstrings() {
        // "rabbit", "kitten", "white" and friends all contain a pronoun as a substring.
        assertFalse(WishReference.isContextual("i want a rabbit"));
        assertFalse(WishReference.isContextual("give me a kitten"));
        assertFalse(WishReference.isContextual("a white horse"),
                "'white' contains 'hi' and is itself a colour, but there is no pronoun");
        assertFalse(WishReference.isContextual("build a pit"));
    }

    @Test
    void survivesRubbishInput() {
        assertFalse(WishReference.isContextual(null));
        assertFalse(WishReference.isContextual(""));
        assertFalse(WishReference.isContextual("   "));
        assertFalse(WishReference.isContextual("!!!! ???? ...."));
        assertFalse(WishReference.isContextual("wwwwwwwwwwwwwwwww"));
    }

    /**
     * Keeps the Gate C assertion honest.
     *
     * <p>The {@code handles} client test proves the gate works by wishing "make it bigger like a
     * diamond" and asserting the child received <b>no diamonds</b>. That is only meaningful if the
     * wish genuinely WOULD have matched the shipped {@code diamonds} template without the gate —
     * otherwise zero diamonds proves nothing and the test quietly measures nothing at all.
     *
     * <p>⚠ Since the built-ins were deleted the shipped jar has no
     * {@code diamonds} template, so in a real client that assertion now holds trivially unless a
     * library or learned recipe names diamonds. This keeps the GATE's half honest — the contextual
     * wish would still be served by a loose diamonds template if the gate were removed — against
     * {@link OldBuiltInsFixture}.
     */
    @Test
    void theGateCFixtureReallyWouldMatchATemplate() {
        String fixture = "make it bigger like a diamond";
        assertTrue(WishReference.isContextual(fixture), "the fixture must be contextual");
        assertTrue(OldBuiltInsFixture.templates().matchDetailed(WishText.sanitize(fixture))
                        .map(m -> m.templateId().equals("diamonds")).orElse(false),
                "without the gate this wish hands over diamonds — that is what makes the client "
                        + "test's 'diamonds == 0' assertion mean something");
    }
}
