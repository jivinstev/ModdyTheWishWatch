package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.moddingfromamod.marshal.FakeClock;
import com.moddingfromamod.marshal.Marshal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * vanilla's pause-when-empty froze every tick handler on an empty server while a test wish was
 * being granted, so the camera supervisor never ran and every such wish waited 75 s for a photograph.
 */
class KeepTickingTest {

    @Test
    @DisplayName("an unsettled wish holds the pause window at 0; none gives vanilla's back")
    void theRule() {
        assertEquals(0, KeepTicking.pauseSeconds(60, true));
        assertEquals(60, KeepTicking.pauseSeconds(60, false));
        assertEquals(0, KeepTicking.pauseSeconds(0, false), "a server that never pauses stays that way");
        assertEquals(0, KeepTicking.pauseSeconds(0, true));
    }

    @Test
    @DisplayName("'unsettled' is the Marshal's: from submit until finish, the photo after All done included")
    void unsettledIsTheMarshals() {
        Marshal m = Marshal.forTest(new FakeClock(1_000_000L), 2);
        assertFalse(m.anyLive(), "nothing submitted: the server may rest");
        UUID id = UUID.randomUUID();
        m.submit(id, UUID.randomUUID(), "put one torch on the ground next to you");
        assertTrue(m.anyLive(), "queued, before any worker claims it");
        m.assign("wish-granter-1", id);
        assertTrue(m.anyLive(), "being granted");
        assertEquals(0, KeepTicking.pauseSeconds(60, m::anyLive));
        m.finish(id);
        assertFalse(m.anyLive(), "settled");
        assertEquals(60, KeepTicking.pauseSeconds(60, m::anyLive));
    }

    @Test
    @DisplayName("#2542: a world module's own work (an island pre-generation) holds the pause, and lets go when done")
    void aWorldModulesReasonHoldsThePause() {
        // SHOULD: a module reason holds an empty server ticking
        assertEquals(0, KeepTicking.pauseSecondsFor(60, () -> KeepTicking.why(false, "island pre-generation (0 of 3 cells ready)")));
        // the pause returns the moment the module stops naming one (READY, or pre-generation off)
        assertEquals(60, KeepTicking.pauseSecondsFor(60, () -> KeepTicking.why(false, null)));
        // SHOULD NOT: hold a world with no module reason or a server that never pauses
        assertEquals(60, KeepTicking.pauseSecondsFor(60, () -> null));
        assertEquals(0, KeepTicking.pauseSecondsFor(0, () -> "anything"));
        // a wish comes first in what it says
        assertEquals(KeepTicking.WISH, KeepTicking.why(true, "island pre-generation"));
        assertEquals("x", KeepTicking.why(false, "x"));
    }

    @Test
    @DisplayName("#2542: the default world module names no work of its own")
    void theDefaultModuleNamesNothing() {
        // The dedicated server's mixin that asks it is private: PauseWhileEmptyWiringTest.
        com.moddingfromamod.world.WorldModule plain = new com.moddingfromamod.world.WorldModule() {
            @Override
            public String id() {
                return "plain";
            }

            @Override
            public com.moddingfromamod.world.Bases start(net.minecraft.server.MinecraftServer server) {
                return null;
            }
        };
        assertEquals(null, plain.keepTicking(), "a module that does not ask keeps vanilla's pause");
    }
}
