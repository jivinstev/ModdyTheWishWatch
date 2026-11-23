package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * an earlier issue's second bug, in {@code WatchSnapshot.onServerTick}: ONE signature shared by every player,
 * and a {@code return} where the loop meant {@code continue}. With two children on, an unchanged
 * watch for the first stopped the check for everyone after him, and the shared signature made each
 * child's watch "changed" whenever the other's differed.
 */
class WatchSignaturesTest {

    private static final UUID STEVE = UUID.nameUUIDFromBytes("steve".getBytes());
    private static final UUID ALEX = UUID.nameUUIDFromBytes("alex".getBytes());

    @Test
    void eachChildsWatchIsJudgedOnItsOwn() {
        WatchSignatures s = new WatchSignatures();
        assertTrue(s.changed(STEVE, "a"), "first look is a change");
        assertTrue(s.changed(ALEX, "b"), "Alex's first look is a change whatever Steve's is");
        assertFalse(s.changed(STEVE, "a"), "Steve's unchanged watch is not resent");
        assertFalse(s.changed(ALEX, "b"), "and neither is Alex's — a shared signature resent both");
        assertTrue(s.changed(ALEX, "c"), "Alex's watch changed");
        assertFalse(s.changed(STEVE, "a"), "Steve's did not");
    }

    @Test
    void aJoinSendsAFreshWatch() {
        WatchSignatures s = new WatchSignatures();
        s.changed(STEVE, "a");
        s.forget(STEVE);
        assertTrue(s.changed(STEVE, "a"), "after a re-join the same watch is sent again");
    }
}
