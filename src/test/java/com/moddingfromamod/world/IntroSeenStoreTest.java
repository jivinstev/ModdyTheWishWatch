package com.moddingfromamod.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The intro-seen world store keeps WISH_STORES.md §2's three rules. */
class IntroSeenStoreTest {

    @Test
    void writesTheEnvelopeAndReadsItBack(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("wishes").resolve(IntroSeenStore.FILE);
        UUID kid = UUID.fromString("00000000-0000-4000-8000-000000000001");
        IntroSeenStore s = new IntroSeenStore(f);
        assertTrue(s.usable(), "no file yet is a new world, and empty is right");
        assertFalse(s.seen(kid));
        assertTrue(s.markSeen(kid, 1759000000000L));
        assertTrue(s.markSeen(kid, 1759999999999L), "marking twice is fine, and keeps the first time");
        String text = Files.readString(f);
        assertTrue(text.contains("\"store\":\"intro-seen\"") && text.contains("\"v\":1")
                && text.contains("\"ownerId\":\"" + kid + "\"") && text.contains("1759000000000"), text);
        assertTrue(new IntroSeenStore(f).seen(kid));
    }

    @Test
    void anUnreadableFileIsARefusal_neverEveryoneIsNew_andIsNeverWrittenOver(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(IntroSeenStore.FILE);
        String damaged = "{\"store\":\"intro-seen\",\"v\":99,\"players\":[]}";
        Files.writeString(f, damaged);
        IntroSeenStore s = new IntroSeenStore(f);
        assertNotNull(s.load());
        assertFalse(s.usable());
        UUID kid = UUID.randomUUID();
        assertFalse(JoinIntro.due(true, true, s.usable(), s.seen(kid)), "nobody is introduced over a refusal");
        assertFalse(s.markSeen(kid, 1L));
        assertEquals(damaged, Files.readString(f), "a refused file is left exactly as it was");
    }

    @Test
    void aDamagedRowIsSkipped_theRestKept(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(IntroSeenStore.FILE);
        UUID kid = UUID.randomUUID();
        Files.writeString(f, "{\"store\":\"intro-seen\",\"v\":1,\"players\":[{\"ownerId\":\"nope\"},"
                + "{\"ownerId\":\"" + kid + "\",\"at\":5}]}");
        IntroSeenStore s = new IntroSeenStore(f);
        assertTrue(s.usable());
        assertTrue(s.seen(kid));
    }
}
