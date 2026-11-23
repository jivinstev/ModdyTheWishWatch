package com.moddingfromamod.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>"Give me that one again" — that the CATALOGUE reaches the rule, and what only it can say
 *.</b>
 *
 * <p>Pure on purpose. {@link WishItemCatalogue#resolve} takes strings and returns entries, so the
 * question it answers needs no server, no player and no mock. That matters beyond speed: the runtime
 * half of this feature was originally four GameTests, each minting a mock player, and they broke a
 * creature test three batches away by changing which player the tools resolve as "the child"
 * (an earlier issue's shape, this repo's §X18). The cheapest gate that can hold a rule should hold it.
 *
 * <h2>🔴 The rule's own spec lives in {@code NameMatchTest}, not here</h2>
 *
 * <p>This file used to carry nine cases restating precedence, ambiguity, case-folding and blank
 * input. Once {@link NameMatch} was extracted so that items and creatures share one rule, those
 * nine became a <b>second copy of a spec</b> — and a spec stated in two places drifts, with the
 * copy nobody re-reads deciding the behaviour. What is left here is the pair of claims the pure
 * rule structurally cannot make: that {@code resolve} is <i>wired</i> to it at all (one anchor case
 * that goes red if it is ever rewritten as a naive {@code contains}), that the catalogue's own key
 * shapes survive the trip, and that a row the catalogue cannot decode is never offered.
 *
 * <p>What stays in Gate B is only what needs a world: two real gives, and the command's shape.
 */
class GiveAgainResolveTest {

    private static WishItemCatalogue.Entry e(String key, String name) {
        // A minimal stack JSON: resolve() never decodes it, and isValid() only wants it non-blank.
        return new WishItemCatalogue.Entry(key, name, "{\"id\":\"minecraft:stick\",\"count\":1}");
    }

    private void given(WishItemCatalogue.Entry... entries) {
        WishItemCatalogue.replaceAll(List.of(entries));
    }

    @AfterEach
    void leaveTheCatalogueAsItWasFound() {
        WishItemCatalogue.replaceAll(List.of());
    }

    @Test
    @DisplayName("a child asks by the name he can see, and gets the one thing he means")
    void aNameFindsTheThing() {
        given(e("id:wand_of_fire", "Wand of Fire"), e("id:plain_stick", "Boring Stick"));
        List<WishItemCatalogue.Entry> found = WishItemCatalogue.resolve("Wand of Fire");
        assertEquals(1, found.size(), "one thing has that name and resolve found " + found.size());
        assertEquals("id:wand_of_fire", found.get(0).key());
    }

    @Test
    @DisplayName("the chat link sends a KEY, which a child could never type — it must still resolve")
    void theExactKeyResolves() {
        // The legacy shape, with the spaces and hashes that are exactly why /moddy give is greedy.
        given(e("old sword#12#{WishOnUse:\"x\"}", "Old Sword"));
        assertEquals(1, WishItemCatalogue.resolve("old sword#12#{WishOnUse:\"x\"}").size(),
                "the card's own link sends the key verbatim; if that does not resolve, the button "
                        + "on the card does nothing and only typing the name works");
    }

    /**
     * <b>The anchor case: proof that the catalogue is on the shared rule.</b> A naive
     * {@code contains} would return three here, so this goes red the moment {@code resolve} stops
     * calling {@link NameMatch} — which is the one thing {@code NameMatchTest} cannot notice.
     */
    @Test
    @DisplayName("the catalogue is wired to the shared rule, tiers and all")
    void resolveGoesThroughTheSharedRule() {
        given(e("id:a", "Wand"), e("id:b", "Wand of Fire"), e("id:c", "Wand of Ice"));
        List<WishItemCatalogue.Entry> found = WishItemCatalogue.resolve("Wand");
        assertEquals(1, found.size(),
                "'Wand' is the exact name of one thing, so the two longer ones must not join it — "
                        + "if they did, resolve is no longer going through NameMatch: "
                        + found.stream().map(WishItemCatalogue.Entry::name).toList());
        assertEquals("id:a", found.get(0).key());
    }

    /**
     * <b>Only the catalogue knows this</b>, and it takes TWO filters to be true.
     *
     * <p>{@link NameMatch} is handed key/name pairs and has no opinion about whether a row can be
     * rebuilt into a stack, so the filter is the catalogue's and the test is too. A damaged row
     * that still resolved would end in {@code Result.BROKEN} — an apology for a thing that should
     * never have been offered.
     *
     * <p>⚠ <b>This assertion was vacuous when first written and is kept as written only after
     * being checked.</b> {@code replaceAll} <i>already</i> drops an invalid row, so the fixture
     * below never reached the map and the test passed without {@code resolve}'s own filter
     * existing at all. Both are real — {@code replaceAll} guards the door and {@code resolve}
     * guards a row that went bad after it was let in — so the claim is genuine and the test goes
     * red only when <b>both</b> are removed, which is what was A/B'd. A test that can only fail
     * for a reason you have not established is a green tick with nothing behind it.
     */
    @Test
    @DisplayName("a row the catalogue cannot decode is never offered as a candidate")
    void anInvalidRowIsNotOffered() {
        given(e("id:good", "Twin Wand"), new WishItemCatalogue.Entry("id:bad", "Twin Wand", ""));
        List<WishItemCatalogue.Entry> found = WishItemCatalogue.resolve("Twin Wand");
        assertEquals(1, found.size(),
                "the undecodable row must be filtered before matching, or a name shared with a "
                        + "good row reads as ambiguous and the child is asked to choose between "
                        + "his sword and a broken record of one");
        assertEquals("id:good", found.get(0).key());
    }

    @Test
    @DisplayName("blank and null are empty rather than matching everything")
    void blankInputDoesNotMatchTheWholeCatalogue() {
        given(e("id:a", "Wand"), e("id:b", "Hat"));
        assertTrue(WishItemCatalogue.resolve("").isEmpty(), "blank must not match everything — a "
                + "mis-clicked link would otherwise hand over whatever sorted first");
        assertTrue(WishItemCatalogue.resolve(null).isEmpty());
    }
}
