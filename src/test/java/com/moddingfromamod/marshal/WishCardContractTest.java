package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.moddingfromamod.provenance.ProvenanceContract;
import org.junit.jupiter.api.Test;

/**
 * §1.8 — the wish card's per-class contract. All pure, because a card is a projection from a stored
 * record.
 *
 * <p>The load-bearing test is {@link #everyPublishingKindHasARowOrTheBuildFails()}. A wish class with
 * no row ships a <b>blank card</b> while every other test in the suite is green, which is exactly the
 * failure {@code StressRegistry.unclaimed()} already exists to prevent for corpus cases.
 */
class WishCardContractTest {

    @Test
    void everyPublishingKindHasARowOrTheBuildFails() {
        List<String> missing = new ArrayList<>();
        for (String kind : ProvenanceContract.PUBLISHING_KINDS) {
            if (WishCardContract.rowFor(kind) == null) {
                missing.add(kind);
            }
        }
        assertTrue(missing.isEmpty(),
                "these wish classes would render a BLANK card while every other test passes: "
                        + missing + " — add a row to WishCardContract, the same way adding a "
                        + "restaurant is one entry in RestaurantStyles");
    }

    @Test
    void everyClassFillsAllFourVitalsSlots() {
        for (String kind : WishCardContract.kinds()) {
            var row = WishCardContract.rowFor(kind);
            assertEquals(WishCardContract.VITALS, row.vitals().size(),
                    kind + " must fill all four — a hole in a fixed grid reads as a bug, so a class "
                            + "with no fourth number shows a DERIVED one rather than a gap");
        }
    }

    @Test
    void everyVitalSpellsOutItsUnit() {
        for (String kind : WishCardContract.kinds()) {
            for (var v : WishCardContract.rowFor(kind).vitals()) {
                assertFalse(v.unit().isBlank(),
                        kind + "/" + v.label() + " has no unit — '2.2' is an internal number and no "
                                + "child has an intuition for it");
                assertFalse(v.icon().isBlank(), kind + "/" + v.label() + " has no icon");
                assertFalse(v.label().isBlank());
            }
        }
    }

    @Test
    void everyClassHasItsOwnGlyphSoTheCardIsIdentifiableWithNoPhotoAtAll() {
        Set<String> glyphs = new HashSet<>();
        for (String kind : WishCardContract.kinds()) {
            String glyph = WishCardContract.rowFor(kind).glyph();
            assertFalse(glyph.isBlank(), kind + " has no glyph");
            assertTrue(glyphs.add(glyph),
                    "two classes share the glyph " + glyph + " — it is the last fallback before a "
                            + "broken-image box, so it has to distinguish them");
        }
    }

    @Test
    void everyClassSaysWhatItsTierBadgeMeans() {
        for (String kind : WishCardContract.kinds()) {
            assertFalse(WishCardContract.rowFor(kind).tierMeans().isBlank(),
                    kind + " has a tier badge that means nothing in particular");
            assertFalse(WishCardContract.rowFor(kind).powers().isBlank(),
                    kind + " has no source for its power chips");
        }
    }

    // ---- a vital is never invented ---------------------------------------------------------

    @Test
    void anAbsentFigureRendersAsADashNotAsAZero() {
        var health = new WishCardContract.Vital("❤️", "health", "hearts");
        assertEquals("❤️ " + WishCardContract.ABSENT, WishCardContract.render(health, null),
                "a confidently wrong stats sheet is worse than an incomplete one, and '0 hearts' is "
                        + "a claim");
    }

    @Test
    void aFigureIsShownWithItsUnitSpelledOut() {
        var height = new WishCardContract.Vital("📏", "height", "blocks tall");
        assertEquals("📏 7 blocks tall", WishCardContract.render(height, 7.0));
        assertEquals("📏 7.2 blocks tall", WishCardContract.render(height, 7.2),
                "…and a whole number never renders as 7.0");
    }

    @Test
    void scaleIsTranslatedIntoSomethingAChildCanPicture() {
        // Spikor's real sculpt: 52 model units tall at scale 2.2.
        Double blocks = WishCardContract.blocksTall(52.0, 2.2);
        assertNotNull(blocks);
        assertTrue(blocks > 6.5 && blocks < 7.5,
                "Spikor should read as about 7 blocks tall, not as 2.2; got " + blocks);
    }

    // ---- the tier badge ----------------------------------------------------------------------

    @Test
    void theRealSpikorAndTheRealCastleDoNotLandInTheSameBucketAsEverythingElse() {
        // Both figures are from the artifacts actually on disk.
        WishCardContract.Tier spikor =
                WishCardContract.tierFor(WishCardContract.creatureScore(300.0, 12.0));
        WishCardContract.Tier castle =
                WishCardContract.tierFor(WishCardContract.buildScore(159_705));
        WishCardContract.Tier chicken =
                WishCardContract.tierFor(WishCardContract.creatureScore(4.0, 0.0));

        assertEquals(WishCardContract.Tier.LEGENDARY, spikor);
        assertEquals(WishCardContract.Tier.LEGENDARY, castle);
        assertNotEquals(spikor, chicken, "a boss and a chicken must not read the same");
        assertEquals(WishCardContract.Tier.COMMON, chicken);
    }

    @Test
    void aMissingScoreFallsBackRatherThanInventingALegend() {
        assertEquals(WishCardContract.Tier.COMMON, WishCardContract.tierFor(null),
                "inventing a tier from a missing number is the confidently-wrong stats sheet again");
        assertEquals(null, WishCardContract.creatureScore(300.0, null),
                "and a half-known creature has no score at all, rather than a wrong one");
    }

    @Test
    void theTiersActuallySeparate() {
        assertEquals(WishCardContract.Tier.COMMON, WishCardContract.tierFor(10.0));
        assertEquals(WishCardContract.Tier.RARE, WishCardContract.tierFor(100.0));
        assertEquals(WishCardContract.Tier.EPIC, WishCardContract.tierFor(1000.0));
        assertEquals(WishCardContract.Tier.LEGENDARY, WishCardContract.tierFor(5000.0));
        for (var t : WishCardContract.Tier.values()) {
            assertFalse(t.badge().isBlank());
        }
    }

    // ---- the story zone -----------------------------------------------------------------------

    @Test
    void anEmptyHistoryReadsAsAFirstWishNotAsAnEmptyList() {
        assertEquals("you wished for this once", WishCardContract.timesWishedAbout(0),
                "a blank space where a number belongs reads as a bug");
    }

    @Test
    void spikorsFourRefinementsReadAsFiveWishes() {
        // His real manifest carries four, so this is a field lookup rather than new plumbing.
        assertEquals("you've wished about this 5 times", WishCardContract.timesWishedAbout(4));
    }

    @Test
    void aNegativeHistoryCannotProduceNonsense() {
        assertEquals("you wished for this once", WishCardContract.timesWishedAbout(-3));
    }
}
