package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** the hidden-tiles store on a child's shelf. */
class WatchHidesTest {

    private static final String KID = "1f2e3d4c-0000-4000-8000-00000000abcd";

    @TempDir
    Path root;

    @Test
    @DisplayName("hide, read back, hide again (one row, its first time kept), un-hide")
    void roundTrip() throws Exception {
        assertEquals(Set.of(), WatchHides.hiddenFor(root, KID), "nothing hidden: no file");
        assertTrue(WatchHides.hide(root, KID, "floating_island~07e1e78c", 1000));
        assertTrue(WatchHides.hide(root, KID, "id:cake_fork_heal", 2000));
        assertFalse(WatchHides.hide(root, KID, "id:cake_fork_heal", 3000), "already hidden");
        assertEquals(Set.of("floating_island~07e1e78c", "id:cake_fork_heal"), WatchHides.hiddenFor(root, KID));
        JsonObject doc = JsonParser.parseString(Files.readString(WatchHides.file(root, KID))).getAsJsonObject();
        assertEquals("hidden", doc.get("store").getAsString());
        assertEquals(WatchHides.SCHEMA, doc.get("v").getAsInt());
        assertEquals(2000, doc.getAsJsonArray("hidden").get(1).getAsJsonObject().get("at").getAsLong(),
                "a second hide keeps the first time");
        assertTrue(WatchHides.unhide(root, KID, "floating_island~07e1e78c"));
        assertFalse(WatchHides.unhide(root, KID, "floating_island~07e1e78c"));
        assertEquals(Set.of("id:cake_fork_heal"), WatchHides.hiddenFor(root, KID));
    }

    @Test
    @DisplayName("a file a newer Moddy wrote is read as nothing and never written over")
    void refusesNewer() throws Exception {
        Path f = WatchHides.file(root, KID);
        Files.createDirectories(f.getParent());
        String newer = "{\"store\":\"hidden\",\"v\":99,\"hidden\":[{\"id\":\"dragon\",\"at\":1}]}";
        Files.writeString(f, newer);
        assertEquals(Set.of(), WatchHides.hiddenFor(root, KID));
        assertFalse(WatchHides.hide(root, KID, "castle", 5));
        assertEquals(newer, Files.readString(f), "the newer file is untouched");
    }

    @Test
    @DisplayName("an owner that cannot name a shelf, and an id we would not write, are refused")
    void refusesJunk() throws Exception {
        assertFalse(WatchHides.hide(root, "../../etc", "dragon", 1));
        assertFalse(WatchHides.hide(root, KID, "two words", 1));
        assertFalse(WatchHides.hide(root, KID, "x\nmoddyop drain on", 1));
        assertFalse(WatchHides.hide(root, KID, "", 1));
        assertTrue(WatchHides.isTileId("hotwheels_garage@hotwheels_trex"));
        assertTrue(WatchHides.isTileId("id:cake_fork_heal"));
        assertTrue(WatchHides.isTileId("name#icon#7"));
        assertFalse(WatchHides.isTileId("a\"b"));
        assertFalse(Files.exists(root.resolve("..").resolve("etc").resolve(WatchHides.FILE)));
    }

    @Test
    @DisplayName("the pure root edits: add keeps one row per id, remove takes every copy")
    void pureEdits() {
        JsonObject r = new JsonObject();
        assertTrue(WatchHides.add(r, "a", 1));
        assertFalse(WatchHides.add(r, "a", 2));
        assertTrue(WatchHides.add(r, "b", 3));
        assertEquals(List.of("a", "b"), List.copyOf(WatchHides.ids(r)));
        r.getAsJsonArray("hidden").add(JsonParser.parseString("{\"id\":\"a\",\"at\":9}"));
        assertTrue(WatchHides.remove(r, "a"));
        assertEquals(List.of("b"), List.copyOf(WatchHides.ids(r)));
        assertEquals(Set.of(), WatchHides.ids(null));
    }

    @Test
    @DisplayName("another server's write behind our back is seen (the cache follows the file)")
    void cacheFollowsTheFile() throws Exception {
        WatchHides.hide(root, KID, "dragon", 1);
        assertEquals(Set.of("dragon"), WatchHides.hiddenFor(root, KID));
        Path f = WatchHides.file(root, KID);
        Files.writeString(f, "{\"store\":\"hidden\",\"v\":1,\"hidden\":[{\"id\":\"castle\",\"at\":2},"
                + "{\"id\":\"dragon\",\"at\":1}]}");
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() + 5000));
        assertEquals(Set.of("castle", "dragon"), WatchHides.hiddenFor(root, KID));
    }
}
