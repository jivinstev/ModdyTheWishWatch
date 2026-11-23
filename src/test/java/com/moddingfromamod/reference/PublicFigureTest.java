package com.moddingfromamod.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** "Public" is a Wikipedia article — an outside fact — and nothing else opens a lookup. */
class PublicFigureTest {

    @AfterEach
    void reset() {
        PublicFigure.reset();
    }

    @Test
    @DisplayName("an article that exists is PUBLIC; a missing one or a disambiguation page is not")
    void readsTheApi() {
        assertEquals(PublicFigure.Verdict.PUBLIC, PublicFigure.parse(
                "{\"batchcomplete\":true,\"query\":{\"pages\":[{\"pageid\":55273994,\"ns\":0,"
                        + "\"title\":\"BlockyBob\"}]}}"));
        assertEquals(PublicFigure.Verdict.NO_ARTICLE, PublicFigure.parse(
                "{\"query\":{\"pages\":[{\"ns\":0,\"title\":\"Jimmy Fakename\",\"missing\":true}]}}"));
        assertEquals(PublicFigure.Verdict.NO_ARTICLE, PublicFigure.parse(
                "{\"query\":{\"pages\":[{\"pageid\":1,\"ns\":0,\"title\":\"Emma\","
                        + "\"pageprops\":{\"disambiguation\":\"\"}}]}}"));
        assertEquals(PublicFigure.Verdict.NO_ARTICLE, PublicFigure.parse(
                "{\"query\":{\"pages\":[{\"title\":\"<>\",\"invalid\":true}]}}"));
        // Either asked title existing is enough: the Title Case form found it.
        assertEquals(PublicFigure.Verdict.PUBLIC, PublicFigure.parse(
                "{\"query\":{\"pages\":[{\"title\":\"x\",\"missing\":true},"
                        + "{\"pageid\":7,\"title\":\"X\"}]}}"));
    }

    @Test
    @DisplayName("a rate-limit page or HTML is not an answer, so the caller fails closed")
    void notAnAnswer() {
        assertNull(PublicFigure.parse("You are making too many requests to the API."));
        assertNull(PublicFigure.parse("<html>"));
        assertNull(PublicFigure.parse("{\"error\":{}}"));
        assertNull(PublicFigure.parse(null));
    }

    @Test
    @DisplayName("the query asks for the name as given and in Title Case, in one request")
    void asksBothCasings() {
        assertEquals(java.util.List.of("blockybob", "Blockybob"),
                java.util.List.copyOf(PublicFigure.titles("blockybob")));
        String url = PublicFigure.queryUrl("stampy cat");
        assertTrue(url.startsWith("https://en.wikipedia.org/w/api.php?action=query&titles="), url);
        assertTrue(url.contains("stampy+cat%7CStampy+Cat"), url);
        assertTrue(url.contains("ppprop=disambiguation"), url);
    }

    @Test
    @DisplayName("an answer is cached; an unreachable Wikipedia is not, and opens nothing")
    void cachesAnswersNotFailures() {
        AtomicInteger asked = new AtomicInteger();
        PublicFigure.useOracle(n -> {
            asked.incrementAndGet();
            return n.equals("BlockyBob") ? PublicFigure.Verdict.PUBLIC
                    : PublicFigure.Verdict.UNREACHABLE;
        });
        assertTrue(PublicFigure.isPublic("BlockyBob"));
        assertTrue(PublicFigure.isPublic("blockybob"), "the cache is case-blind");
        assertEquals(1, asked.get());
        assertEquals(PublicFigure.Verdict.UNREACHABLE, PublicFigure.check("Somebody"));
        assertEquals(PublicFigure.Verdict.UNREACHABLE, PublicFigure.check("Somebody"));
        assertEquals(3, asked.get(), "a failure is asked again rather than remembered");
        assertEquals(PublicFigure.Verdict.NO_ARTICLE, PublicFigure.check(""));
    }

    @Test
    @DisplayName("the resolver keeps a PERSON only when Wikipedia has an article for the name")
    void thePersonKindIsGated() {
        PublicFigure.useOracle(n -> n.equals("BlockyBob") ? PublicFigure.Verdict.PUBLIC
                : PublicFigure.Verdict.NO_ARTICLE);
        var seth = CharacterResolver.parse("PERSON: BlockyBob", "a picture of BlockyBob");
        assertEquals(Optional.of(new CharacterResolver.Resolved("BlockyBob", SubjectKind.PERSON)),
                seth);
        assertEquals(seth, CharacterResolver.gatePerson(seth));
        var friend = CharacterResolver.parse("PERSON: Jake", "a poster of my friend Jake");
        assertTrue(friend.isPresent());
        assertEquals(Optional.empty(), CharacterResolver.gatePerson(friend),
                "a name with no article gets no lookup");
        var spikor = CharacterResolver.parse("CHARACTER: Spikor", "a painting of Spikor");
        assertEquals(spikor, CharacterResolver.gatePerson(spikor),
                "the gate never touches a character");
    }
}
