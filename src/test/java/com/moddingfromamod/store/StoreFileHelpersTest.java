package com.moddingfromamod.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The file half of {@link WishStoreFile}: {@code readFile}/{@code writeFile}, which the
 * paintings, machines and trains stores now go through, and {@code writeBlocker}, which the four
 * stores that carry their OWN version field ({@code mobs}, {@code minted}, {@code handover},
 * {@code memory}) check before every write.
 */
class StoreFileHelpersTest {

    @TempDir
    Path tmp;

    private static JsonObject payload() {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        JsonObject one = new JsonObject();
        one.addProperty("id", "castle");
        arr.add(one);
        o.add("paintings", arr);
        return o;
    }

    @Test
    @DisplayName("readFile: no file is ABSENT; a written file reads back OK with its payload inline")
    void roundTrip() throws Exception {
        Path f = tmp.resolve("wishes").resolve("paintings.json");
        assertInstanceOf(WishStoreFile.Read.Absent.class, WishStoreFile.readFile(f, "paintings", 1));
        WishStoreFile.writeFile(f, "paintings", 1, payload());
        var read = assertInstanceOf(WishStoreFile.Read.Ok.class,
                WishStoreFile.readFile(f, "paintings", 1));
        assertEquals(1, read.root().getAsJsonArray("paintings").size());
        assertEquals(1, Files.list(f.getParent()).count(), "no temp file left beside it");
    }

    @Test
    @DisplayName("readFile: a file that EXISTS and cannot be read is REFUSED, never absent")
    void unreadableIsRefused() throws Exception {
        Path dir = tmp.resolve("paintings.json");
        Files.createDirectories(dir);   // a directory where the file should be: exists, unreadable
        Files.writeString(dir.resolve("x"), "y");
        // isRegularFile is false for a directory, so this one reads as absent — which is right: it
        // is not a store file at all. The refusal case is a file whose bytes will not parse:
        Path f = tmp.resolve("machines.json");
        Files.writeString(f, "{ half a file");
        assertInstanceOf(WishStoreFile.Read.Refused.class, WishStoreFile.readFile(f, "machines", 1));
    }

    @Test
    @DisplayName("rootOf: Ok and Legacy give the root; Absent and Refused give null")
    void rootOf() {
        assertNotNull(WishStoreFile.rootOf(WishStoreFile.read("{\"a\":1}", "s", 1)));
        assertNotNull(WishStoreFile.rootOf(WishStoreFile.read("{\"store\":\"s\",\"v\":1}", "s", 1)));
        assertNull(WishStoreFile.rootOf(WishStoreFile.read(null, "s", 1)));
        assertNull(WishStoreFile.rootOf(WishStoreFile.read("nope", "s", 1)));
    }

    // ── writeBlocker: the self-versioned stores ─────────────────────────────────────────────────

    @Test
    @DisplayName("writeBlocker: no file, an unversioned file, or one at or below our version may be written")
    void mayWrite() throws Exception {
        Path f = tmp.resolve("mobs.json");
        assertNull(WishStoreFile.writeBlocker(f, "version", 1), "no file yet");
        Files.writeString(f, "{\"handles\":[]}");
        assertNull(WishStoreFile.writeBlocker(f, "version", 1), "written before the field existed");
        Files.writeString(f, "{\"version\":1}");
        assertNull(WishStoreFile.writeBlocker(f, "version", 1));
        Files.writeString(f, "{\"version\":1}");
        assertNull(WishStoreFile.writeBlocker(f, "version", 2), "an older file is ours to upgrade");
    }

    @Test
    @DisplayName("writeBlocker: a NEWER file is never written over — the older build would drop what it cannot read")
    void newerBlocks() throws Exception {
        Path f = tmp.resolve("minted.json");
        Files.writeString(f, "{\"version\":3,\"mintedBy\":{},\"somethingNew\":true}");
        String why = WishStoreFile.writeBlocker(f, "version", 1);
        assertNotNull(why);
        assertTrue(why.contains("newer Moddy"), why);
    }

    @Test
    @DisplayName("writeBlocker: a damaged file is never written over either")
    void damagedBlocks() throws Exception {
        Path f = tmp.resolve("handover.json");
        Files.writeString(f, "{ half");
        assertNotNull(WishStoreFile.writeBlocker(f, "version", 1));
        Files.writeString(f, "[1,2,3]");
        assertNotNull(WishStoreFile.writeBlocker(f, "version", 1), "not an object");
        Files.writeString(f, "{\"version\":\"two\"}");
        assertNotNull(WishStoreFile.writeBlocker(f, "version", 1), "a version that is not a number");
    }
}
