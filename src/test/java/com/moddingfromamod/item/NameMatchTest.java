package com.moddingfromamod.item;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate A for the rule behind "give me that one again".
 *
 * <p>It is here rather than inside either registry's test because the whole point of
 * {@link NameMatch} is that ONE rule serves items and creatures. A copy of these assertions per
 * registry would be the second implementation arriving through the test suite instead of through
 * the source.
 */
class NameMatchTest {

    private static NameMatch.Candidate c(String key, String name) {
        return new NameMatch.Candidate(key, name);
    }

    private static List<Integer> resolve(List<NameMatch.Candidate> cs, String q) {
        return NameMatch.resolve(cs, q);
    }

    @Test
    @DisplayName("a child says the name, not the key")
    void aNameMatches() {
        List<NameMatch.Candidate> cs = List.of(c("id:sword_of_fire", "Sword of Fire"));
        assertEquals(List.of(0), resolve(cs, "Sword of Fire"));
        assertEquals(List.of(0), resolve(cs, "sword of fire"), "case is not something he types");
    }

    @Test
    @DisplayName("the key still works, because that is what a chat link sends")
    void aKeyMatches() {
        // Both shapes: id:<feature>, and the pre-#353 keepsake key, which has spaces
        // and '#' in it -- the reason the command's argument is greedy.
        assertEquals(List.of(0), resolve(List.of(c("id:x", "X")), "id:x"));
        assertEquals(List.of(0),
                resolve(List.of(c("Big Sword#sword#{}", "Big Sword")), "Big Sword#sword#{}"));
    }

    @Test
    @DisplayName("a partial name is enough when only one thing could be meant")
    void anUnambiguousLooseMatch() {
        List<NameMatch.Candidate> cs = List.of(c("id:a", "Butt Cheek Water Sword"), c("id:b", "Hat"));
        assertEquals(List.of(0), resolve(cs, "butt cheek"));
    }

    @Test
    @DisplayName("🔴 an EXACT name beats the looser matches it sits inside")
    void exactNameWinsOutright() {
        // Without the tier rule a child who owns a "wand" AND a "wand of fire" could never ask for
        // the first: contains() matches both, so his unambiguous question gets "which do you mean?"
        // forever. This is the assertion that makes the tiers a decision rather than a ranking.
        List<NameMatch.Candidate> cs = List.of(c("id:a", "wand"), c("id:b", "wand of fire"));
        assertEquals(List.of(0), resolve(cs, "wand"));
        assertEquals(List.of(1), resolve(cs, "wand of fire"));
    }

    @Test
    @DisplayName("an exact key wins over an exact name, so a link is never ambiguous")
    void exactKeyWinsOverExactName() {
        // A link sends a key. If a DIFFERENT thing happens to be NAMED that key, the link must
        // still deliver the thing it points at -- otherwise clicking a choice in an ambiguous
        // answer could reopen the same ambiguity.
        List<NameMatch.Candidate> cs = List.of(c("id:relic", "Old Relic"), c("id:other", "id:relic"));
        assertEquals(List.of(0), resolve(cs, "id:relic"));
    }

    @Test
    @DisplayName("two things sharing a name are BOTH returned, never silently picked between")
    void ambiguityIsReported() {
        List<NameMatch.Candidate> cs = List.of(c("id:a", "Sword"), c("id:b", "Sword"));
        assertEquals(List.of(0, 1), resolve(cs, "Sword"));
    }

    @Test
    @DisplayName("nothing he could have meant is an empty answer, not a guess")
    void noMatchIsEmpty() {
        List<NameMatch.Candidate> cs = List.of(c("id:a", "Sword"));
        assertTrue(resolve(cs, "aeroplane").isEmpty());
    }

    @Test
    @DisplayName("blank, null and an empty roster never match anything")
    void degenerateInputs() {
        List<NameMatch.Candidate> cs = List.of(c("id:a", "Sword"));
        assertTrue(resolve(cs, "").isEmpty());
        assertTrue(resolve(cs, "   ").isEmpty());
        assertTrue(NameMatch.resolve(cs, null).isEmpty());
        assertTrue(NameMatch.resolve(List.of(), "sword").isEmpty());
        assertTrue(NameMatch.resolve(null, "sword").isEmpty());
        // A blank query must not fall through to contains(""), which every candidate satisfies --
        // "give me one of ''" answering with the whole catalogue is the shape worth pinning.
        assertTrue(resolve(cs, " ").isEmpty());
    }

    @Test
    @DisplayName("a null field in a row is skipped rather than thrown over")
    void nullFieldsAreSurvivable() {
        List<NameMatch.Candidate> cs = List.of(c(null, null), c("id:a", "Sword"));
        assertEquals(List.of(1), resolve(cs, "Sword"));
    }

    @Test
    @DisplayName("the indexes point back at the caller's own rows, in order")
    void indexesAreTheContract() {
        // The reason this returns indexes rather than Candidates: the two registries map back to
        // their own row type, and neither has to be known here.
        List<NameMatch.Candidate> cs =
                List.of(c("id:a", "Hat"), c("id:b", "Sword"), c("id:c", "Sword"));
        assertEquals(List.of(1, 2), resolve(cs, "sword"));
    }
}
