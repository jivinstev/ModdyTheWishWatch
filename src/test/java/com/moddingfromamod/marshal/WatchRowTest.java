package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The wire format for the watch.
 *
 * <p>Worth its own tests because of where a mistake here shows up: not as an exception, but as a
 * <b>blank on a card</b> — or worse, as one creature's numbers under another creature's name, which
 * is a stats sheet confidently lying. Every field is round-tripped.
 */
class WatchRowTest {

    private static final WatchRow.Creation SPIKOR = new WatchRow.Creation(
            "spikor", "species", "Spikor", List.of("king kroak", "the kroak king"),
            List.of("❤️ 300 hearts", "⚔️ 12 hearts a hit", "📏 7 blocks tall", "👟 0.3 how fast"),
            "⭐ LEGENDARY", List.of("hostile", "fireproof", "boss"),
            "make me a SPIKOR king of the kroaks", 5, false,
            List.of("spikor__0_face", "spikor__1_left", "spikor__2_back", "spikor__3_right"),
            "spikor_king", "spikor king");

    @Test
    void everyFieldSurvivesTheRoundTrip() {
        assertEquals(SPIKOR, WatchRow.Creation.decode(SPIKOR.encode()));
    }

    @Test
    void itsPhotographsSurviveTheWireInOrder() {
        // The card draws the FIRST one before he touches anything, so the order crossing intact
        // matters as much as the names doing.
        var back = WatchRow.Creation.decode(SPIKOR.encode());
        assertEquals(SPIKOR.photos(), back.photos());
        assertEquals("spikor__0_face", back.photos().get(0), "the face leads, not the back of his head");
    }

    @Test
    void aWishInFlightSurvivesToo() {
        var h = new WatchRow.Happening("1a2b3c4d", "your ice dragon", 40, "wish");
        assertEquals(h, WatchRow.Happening.decode(h.encode()));
    }

    @Test
    void aNameWithPunctuationCannotBreakTheRowItRidesIn() {
        // "Spikor | King", "a 5x5 house", "he's BIG!" — a child names things however he likes, and
        // an agent authoring a name is not a child typing.
        var awkward = new WatchRow.Creation("x", "species", "Spikor | King, the 5x5 \"boss\"",
                List.of("a, b", "c|d"), List.of("❤️ 1 hearts"), "🟫 COMMON", List.of("a·b"),
                "he said: \"make it 5x5, please!\"", 2, true, List.of("x__0_face"), "", "");
        var back = WatchRow.Creation.decode(awkward.encode());
        assertEquals(awkward.name(), back.name());
        assertEquals(awkward.intent(), back.intent());
        assertEquals(awkward.aliases(), back.aliases());
        assertTrue(back.inFlight());
    }

    @Test
    void aSeparatorSmuggledIntoAFieldIsStrippedRatherThanShiftingEverythingAfterIt() {
        var sneaky = new WatchRow.Creation("x", "species",
                "Bow" + WatchRow.SEP + "ser", List.of(), List.of("❤️ 1"), "", List.of(), "", 1, false, List.of(), "", "");
        var back = WatchRow.Creation.decode(sneaky.encode());
        assertEquals("Bow ser", back.name(), "one stray separator would shift every field after it");
        assertEquals(List.of("❤️ 1"), back.vitals(), "…and the vitals would end up under the wrong name");
    }

    @Test
    void aShortRowFromAnotherBuildDrawsWhatItCanRatherThanThrowing() {
        // A screen that refuses to open because one field moved is worse than a card with a blank
        // on it, and the child cannot tell either way what went wrong.
        var back = WatchRow.Creation.decode("spikor" + WatchRow.SEP + "species");
        assertEquals("spikor", back.id());
        assertEquals("species", back.kind());
        assertTrue(back.vitals().isEmpty());
        assertEquals(1, back.timesWished(), "and a missing count reads as a first wish");
    }

    @Test
    void thereAreAlwaysExactlyFourVitals() {
        var thin = new WatchRow.Creation("x", "train", "a train", List.of(),
                List.of("🚃 3 carriages"), "", List.of(), "", 1, false, List.of(), "", "");
        var four = thin.fourVitals();
        assertEquals(WishCardContract.VITALS, four.size(),
                "a hole in a fixed grid reads as a bug, not as an absence");
        assertEquals(WishCardContract.ABSENT, four.get(3));
    }

    @Test
    void tooManyVitalsAreTrimmedRatherThanOverflowingTheGrid() {
        var fat = new WatchRow.Creation("x", "species", "a", List.of(),
                List.of("1", "2", "3", "4", "5", "6"), "", List.of(), "", 1, false, List.of(), "", "");
        assertEquals(WishCardContract.VITALS, fat.fourVitals().size());
    }

    @Test
    void anEmptyHistoryIsAFirstWishNotAZero() {
        var fresh = new WatchRow.Creation("x", "species", "a", List.of(), List.of(), "",
                List.of(), "", 0, false, List.of(), "", "");
        assertEquals(1, fresh.timesWished());
        assertFalse(WishCardContract.timesWishedAbout(fresh.timesWished() - 1).contains("0"));
    }

    @Test
    void theRecipeAndItsSeedSurviveTheWire() {
        // Both are what the card's two buttons run on: one names the recipe to replay, the other is
        // the phrase seeded into his box. A row that loses them silently drops the buttons.
        var back = WatchRow.Creation.decode(SPIKOR.encode());
        assertEquals("spikor_king", back.recipeId());
        assertEquals("spikor king", back.seedPhrase());
        assertTrue(back.canRemake());
    }

    @Test
    void aRowFromAnOlderBuildSimplyHasNoRemakeButton() {
        // The two fields are appended at the END precisely so this stays true. An older row must
        // decode to "no recipe" rather than shifting every field along by two.
        var old = "spikor" + WatchRow.SEP + "species" + WatchRow.SEP + "Spikor";
        var back = WatchRow.Creation.decode(old);
        assertEquals("Spikor", back.name(), "the fields before them must not shift");
        assertFalse(back.canRemake());
    }

    @Test
    void aThingWithNoRecipeStillHasSomethingToSeedTheBoxWith() {
        // Otherwise "Wish about this" opens an empty box and he has to name the thing himself —
        // which is the guessing this was built to avoid.
        var c = new WatchRow.Creation("x", "build", "my big castle", List.of(), List.of(), "",
                List.of(), "", 1, false, List.of(), "", "");
        assertEquals("my big castle", c.seedPhrase());
    }

    /** the two fields appended last survive the wire, and an old row still reads. */
    @Test
    void partOfAndUpdatedAtRoundTrip() {
        var c = new WatchRow.Creation("cat", "species", "Cat", java.util.List.of(), java.util.List.of(),
                "", java.util.List.of(), "", 1, false, java.util.List.of(), "menagerie@cat", "")
                .withUpdatedAt(42L).withPartOf(java.util.List.of("Menagerie", "Zoo"));
        var back = WatchRow.Creation.decode(c.encode());
        org.junit.jupiter.api.Assertions.assertEquals(42L, back.updatedAt());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("Menagerie", "Zoo"), back.partOf());
        String old = c.encode().substring(0, c.encode().lastIndexOf('\u001f'));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(),
                WatchRow.Creation.decode(old).partOf(), "a row from before #1349 reads as no wish");
    }
}
