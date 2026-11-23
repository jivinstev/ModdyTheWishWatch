package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reap predicate.
 *
 * <p>This decides whether to terminate a running agent, and it was wrong in two independent ways for
 * months while looking obviously correct, because it was three inline booleans with no test.
 */
class ReapRuleTest {

    private static final String BASE = "wish-granter";

    // ── which sessions are ours ───────────────────────────────────────────────

    @Test
    @DisplayName("a NUMBERED granter is ours — the bug that hid every multi-worker session")
    void numberedGrantersAreOurs() {
        // AgentSession names workers AGENT_NAME + "-" + worker whenever more than one runs, so the
        // old exact-equality test could not see a granter on ANY multi-worker session.
        assertTrue(ReapRule.isOurs("wish-granter-1", BASE));
        assertTrue(ReapRule.isOurs("wish-granter-2", BASE));
        assertTrue(ReapRule.isOurs("wish-granter-12", BASE));
        assertTrue(ReapRule.isOurs("wish-granter", BASE), "the single-worker name still counts");
    }

    @Test
    @DisplayName("somebody else's session is never ours to stop")
    void otherSessionsAreNotOurs() {
        for (String other : new String[]{
                "wish-watcher", "wish-granter-probe", "wish-granterX", "granter-1",
                "wish-granter-", "wish-granter-1a", "detach-probe", "perm-fix", ""}) {
            assertFalse(ReapRule.isOurs(other, BASE), other + " must not be reaped");
        }
        assertFalse(ReapRule.isOurs(null, BASE));
        assertFalse(ReapRule.isOurs("wish-granter", null));
    }

    // ── when to stop them ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a finished session is stopped whether or not the game is still up")
    void doneIsAlwaysReaped() {
        assertTrue(ReapRule.shouldStop("done", false));
        assertTrue(ReapRule.shouldStop("done", true));
    }

    @Test
    @DisplayName("BLOCKED plus a dead client is the stranded case — four agents, one for ten days")
    void blockedWithADeadClientIsReaped() {
        // An unclean exit: the orphan guard tells the agent to stop, it stops LOOPING, and a session
        // that has stopped looping is awaiting input — which reads as blocked, not done.
        assertTrue(ReapRule.shouldStop("blocked", false));
    }

    @Test
    @DisplayName("BLOCKED with a LIVE client is left alone — the gate the whole fix rests on")
    void blockedWithALiveClientIsNeverTouched() {
        // Without this, reaping at world load would stop a granter that is simply waiting for a child
        // to make a wish. That is a far worse failure than leaving a dead session registered.
        assertFalse(ReapRule.shouldStop("blocked", true));
    }

    @Test
    @DisplayName("no other state is ever reaped, however it looks")
    void unknownStatesAreLeftAlone() {
        for (String state : new String[]{"working", "stopped", "failed", "starting", "", null}) {
            assertFalse(ReapRule.shouldStop(state, false), state + " must not be reaped");
            assertFalse(ReapRule.shouldStop(state, true), state + " must not be reaped");
        }
    }

    // ── finding the client behind a session ───────────────────────────────────

    @Test
    @DisplayName("the port is read from the session directory — it IS the client's identity")
    void portIsReadFromTheCwd() {
        assertEquals(OptionalInt.of(50223),
                ReapRule.portOf("/Users/player/.moddingfromamod/session-50223"));
        assertEquals(OptionalInt.of(25585),
                ReapRule.portOf("/Users/player/.moddingfromamod/session-25585"));
    }

    @Test
    @DisplayName("anything that is not plainly a port yields nothing, so the client counts as ALIVE")
    void ambiguousPathsYieldNoPort() {
        // Every one of these makes clientAlive() answer true, which leaves the session alone. The
        // failure modes are not symmetric: an untidy list is cheap, killing a live granter is not.
        for (String cwd : new String[]{
                "/Users/player/.moddingfromamod", "/tmp/session-", "/tmp/session-abc",
                "/tmp/session-50223-old", "/tmp/session-99999999", "/tmp/session-0", null}) {
            assertEquals(OptionalInt.empty(), ReapRule.portOf(cwd), "unexpected port from " + cwd);
        }
    }

    @Test
    @DisplayName("the four agents actually stranded on this machine would all be swept")
    void theRealStrandedAgentsWouldBeReaped() {
        // From `claude agents --json`, 2026-08-21. Ports 25585 and 50223 were both verified closed.
        String[][] stranded = {
                {"wish-granter", "blocked", "/Users/player/.moddingfromamod/session-25585"},
                {"wish-granter-1", "blocked", "/Users/player/.moddingfromamod/session-50223"},
                {"wish-granter-2", "blocked", "/Users/player/.moddingfromamod/session-50223"},
                {"wish-granter-3", "blocked", "/Users/player/.moddingfromamod/session-50223"},
        };
        for (String[] a : stranded) {
            assertTrue(ReapRule.isOurs(a[0], BASE), a[0] + " should be recognised as ours");
            assertTrue(ReapRule.portOf(a[2]).isPresent(), a[2] + " should yield a port");
            assertTrue(ReapRule.shouldStop(a[1], false), a[0] + " should be reaped");
        }
    }

    @Test
    @DisplayName("the three LIVE granters on this machine would all survive")
    void theRealLiveAgentsAreSpared() {
        // Same listing: session-61028 was verified LISTENING, and its granters read as working.
        for (String name : new String[]{"wish-granter-1", "wish-granter-2", "wish-granter-3"}) {
            assertTrue(ReapRule.isOurs(name, BASE));
            assertFalse(ReapRule.shouldStop("working", true), name + " is mid-wish");
            assertFalse(ReapRule.shouldStop("blocked", true), name + " is waiting on a live game");
        }
    }
}
