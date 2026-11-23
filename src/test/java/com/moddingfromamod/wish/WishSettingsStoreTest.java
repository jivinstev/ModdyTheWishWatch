package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A player's wish settings on his shelf: the Magician's Magician / Apprentice toggle. */
class WishSettingsStoreTest {

    private static final String KID = "b1fc1199-022c-444f-bb4c-84718039215a";

    @Test
    @DisplayName("a setting is kept, read back, replaced, and versioned like every shelf store")
    void keptAndReadBack(@TempDir Path players) throws IOException {
        assertEquals("magician", WishSettingsStore.get(players, KID, "mode", "magician"), "no file: the default");
        assertTrue(WishSettingsStore.set(players, KID, "mode", "apprentice"));
        assertEquals("apprentice", WishSettingsStore.get(players, KID, "mode", "magician"));
        assertTrue(WishSettingsStore.set(players, KID, "mode", "magician"));
        assertEquals("magician", WishSettingsStore.get(players, KID, "mode", "apprentice"), "replaced, not added");
        String file = Files.readString(WishSettingsStore.file(players, KID));
        assertTrue(file.contains("\"store\":\"wish-settings\"") && file.contains("\"v\":1"), file);
        assertEquals(1, file.split("\"id\"").length - 1, "one row per setting: " + file);
    }

    @Test
    @DisplayName("a newer Moddy's file is never written over: the toggle just does not stick")
    void aNewerFileIsLeftAlone(@TempDir Path players) throws IOException {
        Path f = WishSettingsStore.file(players, KID);
        Files.createDirectories(f.getParent());
        String newer = "{\"store\":\"wish-settings\",\"v\":99,\"settings\":[{\"id\":\"mode\",\"value\":\"apprentice\",\"shape\":\"unknown\"}]}";
        Files.writeString(f, newer);
        assertFalse(WishSettingsStore.set(players, KID, "mode", "magician"));
        assertEquals(newer, Files.readString(f));
        assertEquals("magician", WishSettingsStore.get(players, KID, "mode", "magician"), "unreadable: the default, never a guess");
    }

    @Test
    @DisplayName("only a plain id and value are written; a name that is not a shelf is refused")
    void refusals(@TempDir Path players) throws IOException {
        assertFalse(WishSettingsStore.set(players, KID, "mode", "Apprentice Magic!"));
        assertFalse(WishSettingsStore.set(players, KID, "../mode", "magician"));
        assertFalse(WishSettingsStore.set(players, "../../etc", "mode", "magician"));
        assertFalse(WishSettingsStore.set(null, KID, "mode", "magician"));
    }
}
