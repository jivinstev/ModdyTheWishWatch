package com.moddingfromamod.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SafeFileName}: a store key as a file name Windows accepts too. The bug it closes: an item's id is
 * {@code id:<name>}, the handover and memory stores saved it as {@code id:<name>.json}, and on Windows
 * {@code Path.resolve} threw inside a server task, so a Builder edit of an item never finished (the platform smoke).
 * These tests judge the NAME by Windows' rules on any OS, so they fail against the old naming on Linux too.
 */
public class SafeFileNameTest {

    /** What Windows refuses in a file name: these characters, control characters, a trailing dot or space. */
    private static final Pattern WINDOWS_ILLEGAL = Pattern.compile("[<>:\"/\\\\|?*\\x00-\\x1f]|[. ]$");
    private static final List<String> RESERVED = List.of("con", "prn", "aux", "nul", "com1", "com9", "lpt1", "lpt9");

    public static void assertWindowsSafe(String fileName) {
        assertFalse(WINDOWS_ILLEGAL.matcher(fileName).find(), "Windows refuses the file name '" + fileName + "'");
        String base = fileName.contains(".") ? fileName.substring(0, fileName.indexOf('.')) : fileName;
        assertFalse(RESERVED.contains(base.toLowerCase(Locale.ROOT)), "Windows reserves '" + base + "' for a device");
        assertFalse(fileName.isEmpty(), "an empty file name");
    }

    @Test
    @DisplayName("an item's id:<name> key is a name Windows accepts, and a plain id keeps the name it had")
    void itemKeysAreSafeAndPlainOnesUnchanged() {
        assertEquals("id%3aedit_zap_lightning", SafeFileName.of("id:edit_zap_lightning"));
        assertWindowsSafe(SafeFileName.of("id:edit_zap_lightning") + ".json");
        for (String plain : List.of("freebie_df3440", "spikor", "a-b_c", "v1.2")) {
            assertEquals(plain, SafeFileName.of(plain), "an id that was already a safe name keeps it");
        }
    }

    @Test
    @DisplayName("every awkward key becomes a Windows-safe name: separators, wildcards, dots at the ends, devices")
    void awkwardKeysAreSafe() {
        for (String k : List.of("id:x", "a/b", "a\\b", "what?", "star*", "<x>", "\"q\"", "p|q", ".hidden", "trail.",
                "space ", "con", "nul.json", "com1", "lpt9", "CON", "aux.x", "a.", "a..", "...", "", "%", "é", "a\tb", "日本")) {
            assertWindowsSafe(SafeFileName.of(k) + ".json");
        }
    }

    @Test
    @DisplayName("a long key is cut to a bounded name that still never meets another key's")
    void longKeysAreBounded() {
        String a = "id:" + "a very long typed name ".repeat(20);
        String b = a + "!";
        String na = SafeFileName.of(a);
        assertTrue(na.length() <= SafeFileName.MAX, na.length() + " characters");
        assertWindowsSafe(na + ".json");
        assertNotEquals(na, SafeFileName.of(b), "two long keys that share their start");
        assertTrue(na.contains("~"));
        String plainLong = "x".repeat(SafeFileName.MAX);
        assertEquals(plainLong, SafeFileName.of(plainLong), "a key at the limit is not cut");
        assertNotEquals(plainLong + "x", SafeFileName.of(plainLong + "x"));
    }

    @Test
    @DisplayName("device names are found in any case and with any extension; others are not")
    void reservedNames() {
        for (String r : List.of("con", "CON", "nul.json", "aux.x.y", "com1", "lpt9.undo-123", "prn")) {
            assertTrue(SafeFileName.isWindowsReserved(r), r);
        }
        for (String ok : List.of("cone", "console", "con_", "com10", "lpt", "a.con", "", "nul_x")) {
            assertFalse(SafeFileName.isWindowsReserved(ok), ok);
        }
    }

    @Test
    @DisplayName("a label's stem: bounded, safe, never a device name, and a fallback when empty")
    void labelStems() {
        assertEquals("con_", SafeFileName.stem("con", 64, "photo"));
        assertEquals("photo", SafeFileName.stem("", 64, "photo"));
        assertEquals("a_b_c", SafeFileName.stem("A:b/c", 64, "photo"));
        assertEquals(10, SafeFileName.stem("x".repeat(300), 10, "photo").length());
        assertEquals("nu_", SafeFileName.stem("nul", 3, "photo"));
        for (String l : List.of("con", "my photo!", "id:zap", "日本", "x".repeat(300), "trail.")) {
            assertWindowsSafe(SafeFileName.stem(l, 64, "photo") + ".png");
        }
    }

    @Test
    @DisplayName("a segment: a valid name never moves (a fork key's ~ included); an invalid one is escaped")
    void segments() {
        for (String ok : List.of("spikor~1f2e3d4c", "castle_v2", "a.b", "pet-dragon")) {
            assertEquals(ok, SafeFileName.segment(ok));
        }
        for (String bad : List.of("castle:v2", "tower.", "con", "", "a/b", "x".repeat(200))) {
            String seg = SafeFileName.segment(bad);
            assertNotEquals(bad, seg, bad);
            assertWindowsSafe(seg);
        }
    }

    @Test
    @DisplayName("two different keys never share a file")
    void injective() {
        Map<String, String> seen = new HashMap<>();
        for (String k : List.of("id:x", "id%3ax", "id_x", "idx", "con", "%63on", "a.", "a%2e", ".a", "%2ea", "", "%",
                "%25", "é", "%c3%a9", "a b", "a%20b")) {
            String prev = seen.put(SafeFileName.of(k), k);
            assertEquals(null, prev, "'" + k + "' and '" + prev + "' both map to " + SafeFileName.of(k));
        }
    }

    @Test
    @DisplayName("a file saved under the raw name moves to the safe one on first read; a read-only probe leaves it")
    void migratesTheOldName(@TempDir Path dir) throws Exception {
        Path old;
        try {
            old = dir.resolve("id:zap.json");
        } catch (InvalidPathException windows) {
            assumeTrue(false, "this OS cannot hold the old name, so no save of it can exist here");
            return;
        }
        Files.writeString(old, "{}", StandardCharsets.UTF_8);
        assertEquals(old, SafeFileName.resolve(dir, "id:zap", ".json", false), "a probe reads the old file in place");
        assertTrue(Files.exists(old));
        Path moved = SafeFileName.resolve(dir, "id:zap", ".json", true);
        assertEquals(dir.resolve("id%3azap.json"), moved);
        assertTrue(Files.exists(moved));
        assertFalse(Files.exists(old), "moved, not copied");
        assertEquals(moved, SafeFileName.resolve(dir, "id:zap", ".json", true), "and found there next time");
        assertNotEquals(old, moved);
    }

    @Test
    @DisplayName("with neither file there, the safe name is the one to write")
    void newKey(@TempDir Path dir) {
        assertEquals(dir.resolve("id%3anew.json"), SafeFileName.resolve(dir, "id:new", ".json", true));
        assertEquals(dir.resolve("plain.json"), SafeFileName.resolve(dir, "plain", ".json", true));
    }
}
