package com.moddingfromamod.health;


import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The drain flag. Its own instance per test — the global is the game's. */
class MaintenanceStateTest {

    @Test
    void aFreshServerAnswersWishes() {
        MaintenanceState s = new MaintenanceState();
        assertFalse(s.draining());
        assertFalse(s.refusesNewWishes());
        assertEquals(0L, s.changedAtMs());
        assertEquals("", s.reason());
    }

    @Test
    void drainingRefusesNewWishesAndStampsWhen() {
        MaintenanceState s = new MaintenanceState();
        assertTrue(s.set(true, 1_000L, "  deploying  "));
        assertTrue(s.draining());
        assertTrue(s.refusesNewWishes());
        assertEquals(1_000L, s.changedAtMs());
        assertEquals("deploying", s.reason(), "the operator's reason is kept, stripped");
    }

    /**
     * A retrying launcher must be able to tell a no-op from a change, which is the whole reason
     * {@code set} returns a boolean rather than {@code void}.
     */
    @Test
    void settingTheSameStateTwiceChangesNothingAndSaysSo() {
        MaintenanceState s = new MaintenanceState();
        assertTrue(s.set(true, 1_000L, "deploy"));
        assertFalse(s.set(true, 9_000L, "a different reason"));
        assertEquals(1_000L, s.changedAtMs(), "an unchanged flag must not re-stamp the time");
        assertEquals("deploy", s.reason(), "…nor overwrite the reason it was drained for");
    }

    @Test
    void drainingOffClearsTheReason() {
        MaintenanceState s = new MaintenanceState();
        s.set(true, 1_000L, "deploy");
        assertTrue(s.set(false, 2_000L));
        assertFalse(s.draining());
        assertFalse(s.refusesNewWishes());
        assertEquals(2_000L, s.changedAtMs());
        assertEquals("", s.reason(),
                "a reason left behind would show up in health.json on a server that is answering");
    }

    /**
     * <b>A wish arriving DURING the drain is refused, and one arriving after it is lifted is not.</b>
     *
     * <p>The plan's exit criterion, expressed against the only thing this class decides. The
     * GameTest drives the real {@code WishService}; this is the state machine underneath it.
     */
    @Test
    void aWishArrivingDuringTheDrainIsRefusedAndOneAfterwardsIsNot() {
        MaintenanceState s = new MaintenanceState();
        assertFalse(s.refusesNewWishes(), "before");
        s.set(true, 10L, "countdown");
        assertTrue(s.refusesNewWishes(), "during");
        s.set(false, 20L);
        assertFalse(s.refusesNewWishes(), "after");
    }

    @Test
    void theGlobalIsOneObject() {
        assertTrue(MaintenanceState.global() == MaintenanceState.global());
    }
}
