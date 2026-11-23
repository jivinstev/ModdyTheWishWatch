package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * step 2 — the rule that decides whether to take a child's wish-granter away.
 *
 * <p>Every test here is a way this could go wrong at somebody's expense rather than the mod's. The
 * saving is real ($96 over a 72-hour world) but it is not worth one wish going unheard, so the
 * negatives are tested harder than the positives.
 */
class IdlePolicyTest {

    private static final long MINUTE = 60_000L;

    @Test
    @DisplayName("the grace is DERIVED from the two measured costs, not typed")
    void theThresholdIsArithmetic() {
        // P9: derive a threshold from real measurement, never invent one.: measured on
        // the model the pool runs (Muse) by a pool-economics measurement, 2026-09-26…30 — $0.023 a cold
        // launch (the dearest tenth), $0.087 a parked worker-hour. It was $0.57 / $1.33 on Claude.
        long expected = Math.round(0.023 / 0.087 * 3_600_000.0);
        assertEquals(expected, IdlePolicy.GRACE_MS,
                "GRACE_MS must stay the break-even of the two measured numbers");
        // Sanity on the derivation itself, so a typo in either input is visible as a silly answer.
        assertTrue(IdlePolicy.GRACE_MS > 10 * MINUTE && IdlePolicy.GRACE_MS < 20 * MINUTE,
                "break-even on Muse should land near sixteen minutes; got " + IdlePolicy.GRACE_MS + " ms");
    }

    @Test
    @DisplayName("nobody online for longer than the break-even: reap")
    void anEmptyWorldIsReaped() {
        assertTrue(IdlePolicy.shouldReap(0, IdlePolicy.GRACE_MS, 0));
        assertTrue(IdlePolicy.shouldReap(0, 72 * 60 * MINUTE, 0), "the three-day case");
    }

    @Test
    @DisplayName("A PLAYER IS ONLINE: never reaped, however long since the last wish")
    void aPlayerPresentIsNeverReaped() {
        // The whole reason presence is the signal and an idle timer is not. A child who has been
        // building quietly for two hours has not stopped wanting a wish granted.
        for (long idle : new long[]{0, MINUTE, 60 * MINUTE, 72 * 60 * MINUTE}) {
            assertFalse(IdlePolicy.shouldReap(1, idle, 0),
                    "a player is online and idle=" + idle + " ms");
        }
        assertFalse(IdlePolicy.shouldReap(4, Long.MAX_VALUE, 0));
    }

    @Test
    @DisplayName("a relog does not pay for a boot")
    void aBriefAbsenceIsNotReaped() {
        assertFalse(IdlePolicy.shouldReap(0, 0, 0), "they left this instant");
        assertFalse(IdlePolicy.shouldReap(0, 30_000L, 0), "a crash-and-rejoin");
        assertFalse(IdlePolicy.shouldReap(0, IdlePolicy.GRACE_MS - 1, 0),
                "one millisecond under the break-even is still cheaper to keep");
    }

    @Test
    @DisplayName("A WISH IN FLIGHT IS NEVER REAPED, even in an empty world")
    void aWishBeingGrantedSurvivesTheirDisconnect() {
        // A player can close the laptop mid-grant. The artifact is already paid for and is worth
        // finishing and persisting for when they come back; reaping there throws away the
        // expensive thing to save the cheap one.
        assertFalse(IdlePolicy.shouldReap(0, 72 * 60 * MINUTE, 1));
        assertFalse(IdlePolicy.shouldReap(0, Long.MAX_VALUE, 3));
        // ...and once it finishes, the same world IS reaped. Otherwise a wish that ended badly
        // would park the pool forever, which is the failure this saving exists to prevent.
        assertTrue(IdlePolicy.shouldReap(0, 72 * 60 * MINUTE, 0));
    }

    @Test
    @DisplayName("booting is on JOIN (or any touch), so the boot hides behind the loading screen")
    void arrivingStartsThePool() {
        assertTrue(IdlePolicy.shouldBoot(1, false, 0));
        assertFalse(IdlePolicy.shouldBoot(1, true, 0), "already running: start() is a no-op anyway");
        assertFalse(IdlePolicy.shouldBoot(0, false, 0), "an empty world needs no pool");
        assertFalse(IdlePolicy.shouldBoot(0, true, 0));
    }

    @Test
    @DisplayName("a pool reaped for being IGNORED is not booted straight back up")
    void anIdleReapIsNotUndoneByTheBootRule() {
        // PoolPlan.desiredWorkers publishes the level the worker container converges on. If
        // shouldBoot answered "somebody is online and nothing runs" alone, the runner would
        // relaunch the worker shouldReapWhileOnline just stopped, on the next scrape, forever.
        long quiet = IdlePolicy.ONLINE_IDLE_MS;
        assertTrue(IdlePolicy.shouldReapWhileOnline(1, quiet, 0));
        assertFalse(IdlePolicy.shouldBoot(1, false, quiet),
                "the pool was reaped because nobody touched Moddy; nothing has touched it since");
        assertTrue(IdlePolicy.shouldBoot(1, false, 0), "...until somebody does: the wake");
    }

    @Test
    @DisplayName("reap and boot cannot both be true, or the pool would flap")
    void theTwoRulesNeverAgree() {
        for (int players : new int[]{0, 1, 3}) {
            for (long idle : new long[]{0, IdlePolicy.ONLINE_IDLE_MS - 1, IdlePolicy.ONLINE_IDLE_MS,
                    IdlePolicy.GRACE_MS, 10 * IdlePolicy.GRACE_MS}) {
                boolean reap = IdlePolicy.shouldReap(players, idle, 0)
                        || IdlePolicy.shouldReapWhileOnline(players, idle, 0);
                for (boolean running : new boolean[]{true, false}) {
                    boolean boot = IdlePolicy.shouldBoot(players, running, idle);
                    assertFalse(reap && boot, "players=" + players + " idle=" + idle
                            + " running=" + running + ": would stop and start at once");
                }
            }
        }
    }

    @Test
    @DisplayName("nonsense inputs fail SAFE — towards keeping the worker")
    void badInputsDoNotReap() {
        // The two failure modes are not symmetric: an unreaped pool costs money, a wrongly reaped
        // one costs a child their wish. Anything unexpected must land on the money side.
        assertFalse(IdlePolicy.shouldReap(-1, Long.MAX_VALUE, 0), "a negative player count");
        assertFalse(IdlePolicy.shouldReap(0, Long.MIN_VALUE, 0), "a clock that went backwards");
        assertFalse(IdlePolicy.shouldReap(0, -1, 0));
    }

    @Test
    @DisplayName("#364 step 4 is its OWN rule — the empty-world rule never grew an idle timer")
    void theEmptyWorldRuleStaysAboutPresence() {
        // The UX call (2026-09-24, option C) landed as shouldReapWhileOnline, beside
        // shouldReap rather than inside it, so the two thresholds (derived vs chosen) stay apart.
        assertFalse(IdlePolicy.shouldReap(1, Long.MAX_VALUE, 0),
                "shouldReap must not have grown an idle-timeout branch");
    }

    @Test
    @DisplayName("ONLINE: twenty quiet minutes reaps; nineteen does not (a chosen threshold)")
    void theOnlineRuleIsTwentyMinutes() {
        assertEquals(20 * MINUTE, IdlePolicy.ONLINE_IDLE_MS);
        assertTrue(IdlePolicy.shouldReapWhileOnline(1, 20 * MINUTE, 0));
        assertTrue(IdlePolicy.shouldReapWhileOnline(3, 72 * 60 * MINUTE, 0));
        assertFalse(IdlePolicy.shouldReapWhileOnline(1, 20 * MINUTE - 1, 0));
        assertFalse(IdlePolicy.shouldReapWhileOnline(1, 0, 0), "he just touched the watch");
    }

    @Test
    @DisplayName("ONLINE: a wish being granted is activity, and never reaped")
    void theOnlineRuleNeverTakesAGrantInProgress() {
        assertFalse(IdlePolicy.shouldReapWhileOnline(1, 72 * 60 * MINUTE, 1));
        assertFalse(IdlePolicy.shouldReapWhileOnline(2, Long.MAX_VALUE, 3));
    }

    @Test
    @DisplayName("ONLINE: an empty or unknown world is not this rule's to answer")
    void theOnlineRuleNeedsSomebodyThere() {
        // -1 is "could not ask": it must reap on NEITHER rule. 0 is shouldReap's case.
        assertFalse(IdlePolicy.shouldReapWhileOnline(-1, Long.MAX_VALUE, 0));
        assertFalse(IdlePolicy.shouldReapWhileOnline(0, Long.MAX_VALUE, 0));
        assertFalse(IdlePolicy.shouldReapWhileOnline(1, -1, 0), "a clock that went backwards");
    }

    @Test
    @DisplayName("the wake line is said only when the wish really waits for a boot")
    void wakeSaysSoOnlyWhenTheWishWaits() {
        assertTrue(IdlePolicy.wakeSaysSo(false, Long.MAX_VALUE, 0), "the pool was stopped");
        assertTrue(IdlePolicy.wakeSaysSo(true, 2_000L, 0),
                "the watch woke it two seconds ago and nobody is listening yet");
        assertFalse(IdlePolicy.wakeSaysSo(true, 2_000L, 1),
                "a worker is already listening: this wish is answered at once");
        assertFalse(IdlePolicy.wakeSaysSo(true, PoolPlan.BOOT_GRACE_MS, 0),
                "a pool up for longer than a boot, momentarily between two polls, is not asleep");
        assertFalse(IdlePolicy.wakeSaysSo(true, -5L, 0), "a clock that went backwards");
    }

    @Test
    @DisplayName("a DEDICATED server does not boot the pool as the world opens — the first join does")
    void aDedicatedServerWaitsForItsFirstJoin() {
        //, measured 2026-09-29: a release woke a sleeping dedicated server for WORK, its
        // restart booted one worker per world with nobody on, and the server's sleep rule (never
        // sleep holding a model session) then waited ~26 min for the empty-world reap — 34-39 min
        // awake per work wake where the rule says ~3, and a cold boot plus ~26 min of polls
        // (~$1.15) paid for nobody. A dedicated server always opens EMPTY, so a boot there buys a
        // warm pool only for a child who arrives within the grace; the join boots it anyway,
        // behind the loading screen (WishAgent's join handler), as after any reap.
        assertFalse(IdlePolicy.bootAtWorldOpen(true),
                "a dedicated server opens with nobody on: booting now pays for a worker nobody asked for");
        assertTrue(IdlePolicy.shouldBoot(1, false, 0),
                "...and the first join is what boots it instead");
    }

    @Test
    @DisplayName("singleplayer still boots as the world opens — its one player is already on the way")
    void singleplayerStillBootsAtOpen() {
        assertTrue(IdlePolicy.bootAtWorldOpen(false));
    }

    @Test
    @DisplayName("the manifest's boot default (-D, seconds) sits under the runtime knob and over the code")
    void bootDefaultsAreSeconds() {
        String k = IdlePolicy.IDLE_REAP_PROPERTY;
        String old = System.getProperty(k);
        try {
            System.clearProperty(k);
            assertEquals(IdlePolicy.GRACE_MS, IdlePolicy.bootSeconds(k, IdlePolicy.GRACE_MS));
            System.setProperty(k, "600");
            assertEquals(600_000L, IdlePolicy.bootSeconds(k, IdlePolicy.GRACE_MS), "seconds, from the boot property");
            System.setProperty(k, "garbage");
            assertEquals(IdlePolicy.GRACE_MS, IdlePolicy.bootSeconds(k, IdlePolicy.GRACE_MS),
                    "a typo is ignored, never a reap every tick");
            System.setProperty(k, "0");
            assertEquals(IdlePolicy.GRACE_MS, IdlePolicy.bootSeconds(k, IdlePolicy.GRACE_MS));
        } finally {
            if (old == null) {
                System.clearProperty(k);
            } else {
                System.setProperty(k, old);
            }
        }
    }
}
