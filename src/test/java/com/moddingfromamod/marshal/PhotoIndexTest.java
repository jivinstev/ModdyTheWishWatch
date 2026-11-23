package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which photographs belong to which thing.
 *
 * <p>The rules here are all about <b>not showing him a picture of something else</b>, which is a
 * worse failure than showing him none: a card with no photo is plainly a card with no photo, while
 * a card with the wrong photo is a confident lie about what he made.
 */
class PhotoIndexTest {

    private static void shot(Path dir, String stem) throws IOException {
        Files.writeString(dir.resolve(stem + ".png"), "x");
    }

    @Test
    void aTurnaroundComesBackInItsOwnOrderWithTheFaceFirst(@TempDir Path dir) throws IOException {
        // Sculpts already shoot these four; alphabetical order would put the BACK of the
        // creature's head first about a quarter of the time, and the first frame is what the card
        // shows before he touches anything.
        shot(dir, "spikor__2_back");
        shot(dir, "spikor__0_face");
        shot(dir, "spikor__3_right");
        shot(dir, "spikor__1_left");

        assertEquals(List.of("spikor__0_face", "spikor__1_left", "spikor__2_back", "spikor__3_right"),
                PhotoIndex.forArtifact(dir, "spikor"));
    }

    @Test
    void aPlainShotLeadsWhenThereIsOne(@TempDir Path dir) throws IOException {
        shot(dir, "bcw_javelin__1_left");
        shot(dir, "bcw_javelin");
        assertEquals("bcw_javelin", PhotoIndex.forArtifact(dir, "bcw_javelin").get(0));
    }

    @Test
    void aPhotoOfSomethingElseThatMerelyMentionsItIsNotItsPhoto(@TempDir Path dir)
            throws IOException {
        shot(dir, "spikor");
        shot(dir, "spikor_castle_view");
        shot(dir, "spikors_lair");

        List<String> found = PhotoIndex.forArtifact(dir, "spikor");
        assertEquals(List.of("spikor"), found,
                "a prefix match would put a picture of his CASTLE on the creature's card");
    }

    @Test
    void anotherThingsPhotosAreNeverCollected(@TempDir Path dir) throws IOException {
        shot(dir, "dinko__0_face");
        assertTrue(PhotoIndex.forArtifact(dir, "spikor").isEmpty());
    }

    @Test
    void aThingWithNoPhotosGetsAnEmptyListRatherThanAnExcuse(@TempDir Path dir) {
        assertTrue(PhotoIndex.forArtifact(dir, "nothing_here").isEmpty());
        assertTrue(PhotoIndex.forArtifact(dir, "").isEmpty());
        assertTrue(PhotoIndex.forArtifact(null, "spikor").isEmpty());
    }

    @Test
    void theCarouselIsCappedSoASummerOfShotsIsNotAHundredDots(@TempDir Path dir)
            throws IOException {
        for (int i = 0; i < 20; i++) {
            shot(dir, "castle__" + i + "_angle");
        }
        assertEquals(PhotoIndex.MAX_PHOTOS, PhotoIndex.forArtifact(dir, "castle").size());
    }

    @Test
    void nonPngsAreIgnored(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("spikor.txt"), "not a photo");
        Files.writeString(dir.resolve("spikor.json"), "{}");
        assertTrue(PhotoIndex.forArtifact(dir, "spikor").isEmpty());
    }

    @Test
    void theMatchIsCaseInsensitiveBecauseAnAgentTypesTheNameItRemembers(@TempDir Path dir)
            throws IOException {
        shot(dir, "spikor");
        assertFalse(PhotoIndex.forArtifact(dir, "Spikor").isEmpty());
    }
}
