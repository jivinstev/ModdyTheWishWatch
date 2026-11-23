package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * the watch asked {@link PhotoIndex#forArtifact} once per creation, per player, twice a
 * second — and every call LISTED the whole photos directory. A thread dump of a hung Gate B had the
 * server thread spending 727 of 769 s in {@code Files.list}. The directory is read once and reused
 * until it changes.
 */
class PhotoIndexListingTest {

    @TempDir
    Path photos;

    @Test
    void aHundredLookupsListTheDirectoryOnce() throws Exception {
        for (int i = 0; i < 50; i++) {
            Files.writeString(photos.resolve("thing_" + i + ".png"), "x");
        }
        Files.writeString(photos.resolve("spikor__0_face.png"), "x");
        PhotoIndex.forgetListingForTest();
        long before = PhotoIndex.listingsForTest();
        for (int i = 0; i < 100; i++) {
            assertEquals(List.of("spikor__0_face"), PhotoIndex.forArtifact(photos, "spikor"));
        }
        assertEquals(1, PhotoIndex.listingsForTest() - before,
                "100 lookups of one unchanged directory listed it more than once");
    }

    @Test
    void aNewPhotographIsSeenAtOnce() throws Exception {
        Files.writeString(photos.resolve("dragon.png"), "x");
        PhotoIndex.forgetListingForTest();
        assertEquals(List.of("dragon"), PhotoIndex.forArtifact(photos, "dragon"));
        Files.writeString(photos.resolve("dragon__1_left.png"), "x");
        // A coarse filesystem can write the same directory mtime twice in one second; move it on
        // the way a real write a second later would.
        Files.setLastModifiedTime(photos, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        List<String> now = PhotoIndex.forArtifact(photos, "dragon");
        assertTrue(now.contains("dragon__1_left"), "a new photograph was hidden by the cache: " + now);
    }

    @Test
    void theRulesAreUnchanged() throws Exception {
        for (String n : new String[]{"spikor", "spikor__2_back", "spikor__0_face", "spikor_castle_view",
                "notes.txt"}) {
            Files.writeString(photos.resolve(n.contains(".") ? n : n + ".png"), "x");
        }
        PhotoIndex.forgetListingForTest();
        assertEquals(List.of("spikor", "spikor__0_face", "spikor__2_back"),
                PhotoIndex.forArtifact(photos, "Spikor"));
    }
}
