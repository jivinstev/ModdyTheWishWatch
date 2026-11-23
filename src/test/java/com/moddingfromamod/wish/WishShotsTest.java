package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A wish's photographs are filed under the wish, every retake kept, nothing twice, and
 * only while a wish is actually taking them.
 */
class WishShotsTest {

    @TempDir
    Path photos;

    private Path shot(String name, String bytes) throws Exception {
        Path p = photos.resolve(name);
        Files.writeString(p, bytes);
        return p;
    }

    @Test
    void aShotTakenForAWishIsFiledUnderItsEightCharacters() throws Exception {
        Path p = shot("volcano__0_face.png", "A");
        Path filed = WishShots.during("8af34b53", () -> WishShots.keep(p));
        assertEquals(photos.resolve("wish/8af34b53/volcano__0_face.png"), filed);
        assertEquals("A", Files.readString(filed));
    }

    @Test
    void aShotTakenOutsideAnyWishIsNotFiled() throws Exception {
        Path p = shot("probe.png", "A");
        assertNull(WishShots.keep(p));
        assertTrue(Files.notExists(photos.resolve("wish")));
    }

    @Test
    void aMalformedWishIdFilesNothing() throws Exception {
        Path p = shot("x.png", "A");
        assertNull(WishShots.during("????????", () -> WishShots.keep(p)));
        assertNull(WishShots.during("../../etc", () -> WishShots.keep(p)));
    }

    @Test
    void aRetakeIsKeptBesideTheOriginalAndAnIdenticalOneIsNot() throws Exception {
        Path p = shot("photo.png", "first");
        WishShots.during("c615e0e2", () -> WishShots.keep(p));
        Files.writeString(p, "second");                         // the agent fixed it and re-shot
        Path retake = WishShots.during("c615e0e2", () -> WishShots.keep(p));
        assertEquals("photo-2.png", retake.getFileName().toString());
        Path again = WishShots.during("c615e0e2", () -> WishShots.keep(p));
        assertEquals(retake, again);                             // same bytes: not a third copy
        assertEquals("first", Files.readString(photos.resolve("wish/c615e0e2/photo.png")));
    }

    @Test
    void theContextEndsWithTheCall() throws Exception {
        WishShots.during("336ea709", () -> null);
        assertEquals("", WishShots.current());
    }

    @Test
    void namesAreOnesTheTowerServes() {
        assertEquals("leaper__0_face", WishShots.stemOf("Leaper__0_Face.png"));
        assertEquals("hut-22222222__clip", WishShots.stemOf("hut~22222222__clip.png"));
        assertEquals("photo", WishShots.stemOf("__.png"));
        assertEquals("a-3.png", WishShots.nextName(Set.of("a.png", "a-2.png"), "a"));
    }

    @Test
    void aRunawayWishStopsBeingFiled() throws Exception {
        Path dir = photos.resolve("wish/aaaaaaaa");
        Files.createDirectories(dir);
        for (int i = 0; i < WishShots.PER_WISH; i++) {
            Files.writeString(dir.resolve("f" + i + ".png"), "x" + i);
        }
        Path p = shot("one-more.png", "y");
        assertNull(WishShots.during("aaaaaaaa", () -> WishShots.keep(p)));
    }
}
