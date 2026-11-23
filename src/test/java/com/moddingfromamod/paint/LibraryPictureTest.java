package com.moddingfromamod.paint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** a picture the library can hang again, read with the rules. */
class LibraryPictureTest {

    private static final String RECT = "\"rects\":[{\"x\":0,\"y\":0,\"w\":8,\"h\":8,\"colour\":\"red\"}]";

    @Test
    void theMintedShapeReads() {
        LibraryPicture p = LibraryPicture.parse("{\"store\":\"library-picture\",\"v\":1,"
                + "\"wide\":3,\"tall\":2,\"size\":64," + RECT + "}");
        assertEquals(3, p.wide());
        assertEquals(2, p.tall());
        assertEquals(1, p.recipe().rects().size());
    }

    @Test
    void thePublishedPaintingJsonWithNoEnvelopeIsV1() {
        // What ProvenancePublisher writes for a painted wish — so promotion is a copy.
        LibraryPicture p = LibraryPicture.parse("{\"size\":64," + RECT + "}");
        assertEquals(2, p.wide(), "an unstated span hangs 2x2");
    }

    @Test
    void aNewerSchemaIsRefusedByNameNeverReadAsBlank() {
        var e = assertThrows(IllegalArgumentException.class, () -> LibraryPicture.parse(
                "{\"store\":\"library-picture\",\"v\":2,\"size\":64," + RECT + "}"));
        assertTrue(e.getMessage().contains("v2"), e.getMessage());
    }

    @Test
    void anotherStoresFileAndAnEmptyPictureAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> LibraryPicture.parse(
                "{\"store\":\"library-icons\",\"v\":1}"));
        assertThrows(IllegalArgumentException.class, () -> LibraryPicture.parse(
                "{\"size\":64,\"rects\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> LibraryPicture.parse("nope"));
    }

    @Test
    void theStarterCreeperFaceReads() throws Exception {
        Path f = Path.of("src/main/resources/starter-library/pictures/starter_creeper_face",
                LibraryPicture.FILE);
        LibraryPicture p = LibraryPicture.parse(Files.readString(f));
        assertEquals(64, p.recipe().rects().size(), "an 8x8 face, one rectangle per cell");
        assertEquals(3, p.wide());
        assertFalse(p.recipe().isEmpty());
    }
}
