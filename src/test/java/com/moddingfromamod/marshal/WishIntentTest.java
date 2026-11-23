package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pool is reaped after 5 quiet minutes and started on INTENT — the watch's
 * panel and the wish box opening (the anvil's per-keystroke signal went with it, D2) — so its boot overlaps the typing. These pin the
 * arithmetic of "how far ahead was it asked", and (by reading the sources, the only way to check a
 * call site exists without a game) that each door really calls it.
 */
class WishIntentTest {

    private static final UUID KID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    @DisplayName("a wish takes its run-up's FIRST signal, how long before it, and every kind seen")
    void firstSignalAndLead() {
        WishIntent w = new WishIntent();
        w.note(KID, "watch", 1_000);
        w.note(KID, "wishbox", 4_000);
        w.note(KID, "key", 5_000);
        w.note(KID, "key", 9_000);
        var t = w.take(KID, 21_000).orElseThrow();
        assertEquals("watch", t.first());
        assertEquals(20_000, t.leadMs());
        assertEquals("watch,wishbox,key", t.kinds(), "each kind once, in the order first seen");
        assertTrue(w.take(KID, 22_000).isEmpty(), "taken once: the next wish measures its own run-up");
    }

    @Test
    @DisplayName("a visit to the watch long ago is not this wish's run-up")
    void staleSignalsAreForgotten() {
        WishIntent w = new WishIntent();
        w.note(KID, "watch", 0);
        assertTrue(w.take(KID, WishIntent.FORGET_MS + 1).isEmpty(), "too old to have warmed anything");
        w.note(KID, "watch", 0);
        w.note(KID, "wishbox", WishIntent.FORGET_MS + 5);
        var t = w.take(KID, WishIntent.FORGET_MS + 10).orElseThrow();
        assertEquals("wishbox", t.first(), "a stale first signal starts a new run-up");
        assertEquals(5, t.leadMs());
    }

    @Test
    @DisplayName("a /wish typed in chat has no run-up — said as none, never invented")
    void noSignal() {
        assertTrue(new WishIntent().take(KID, 5).isEmpty());
    }

    @Test
    @DisplayName("both doors call intent: the watch's panel and the wish box opening")
    void theDoorsAreWired() throws Exception {
        Path root = Path.of("src/main/java/com/moddingfromamod");
        String watch = Files.readString(root.resolve("wish/WatchMenu.java"));
        String box = Files.readString(root.resolve("wish/WishCompose.java"));
        String submit = Files.readString(root.resolve("wish/WishService.java"));
        assertTrue(watch.contains("AgentSession.intent(player.getUUID(), \"watch\")"), "the panel opening");
        assertTrue(box.contains("AgentSession.intent(player.getUUID(), \"wishbox\")"), "the wish box opening");
        assertTrue(submit.contains("WishIntent.global()") && submit.contains("ActionLog.intent("),
                "the wish records its run-up");
    }
}
