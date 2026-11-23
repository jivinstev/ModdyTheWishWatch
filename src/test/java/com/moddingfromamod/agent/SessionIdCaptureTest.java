package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Reading a replacement worker's id out of an APPENDED log. The id is the only handle
 * {@code claude stop} accepts, so a miss here is a worker nothing can stop.
 */
class SessionIdCaptureTest {

    /** The CLI's real banner, captured 2026-09-23 — note the two-byte middle dots. */
    private static String banner(String id, String name) {
        return "backgrounded · " + id + " · " + name + "\n"
                + "  claude agents             list sessions\n"
                + "  claude attach " + id + "    open in this terminal\n"
                + "  claude stop " + id + "      stop this session\n";
    }

    @Test
    void theNewIdIsReadAfterAnEarlierBanner() {
        byte[] before = banner("ae09d6b0", "wish-granter-3").getBytes(StandardCharsets.UTF_8);
        byte[] all = (banner("ae09d6b0", "wish-granter-3") + banner("baaa8c36", "wish-granter-3"))
                .getBytes(StandardCharsets.UTF_8);
        assertEquals("baaa8c36", AgentSession.sessionIdAfter(all, before.length),
                "the replacement's id, not the previous worker's and not nothing");
    }

    /** Several earlier banners push a character index further behind the byte offset each time. */
    @Test
    void manyEarlierBannersDoNotShiftTheSlice() {
        StringBuilder old = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            old.append(banner("old0000" + i, "wish-granter-2"));
        }
        long offset = old.toString().getBytes(StandardCharsets.UTF_8).length;
        byte[] all = (old + banner("1b330aee", "wish-granter-2")).getBytes(StandardCharsets.UTF_8);
        assertEquals("1b330aee", AgentSession.sessionIdAfter(all, offset));
    }

    @Test
    void aFreshLogIsReadFromTheStart() {
        byte[] all = banner("37ec518d", "wish-granter-1").getBytes(StandardCharsets.UTF_8);
        assertEquals("37ec518d", AgentSession.sessionIdAfter(all, 0));
    }

    @Test
    void nothingNewYetIsNull() {
        byte[] all = banner("ae09d6b0", "wish-granter-3").getBytes(StandardCharsets.UTF_8);
        assertNull(AgentSession.sessionIdAfter(all, all.length),
                "the previous worker's id must never be taken for the new one");
    }
}
