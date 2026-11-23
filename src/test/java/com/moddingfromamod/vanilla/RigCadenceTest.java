package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/** a rig atom's move goes out on the rig's beat, the same ticks for every atom, with the pose. */
class RigCadenceTest {

    private static final int N = RigCadence.DEFAULT_MOVE_INTERVAL;

    @Test
    void everyEaseIsTheGapPlusOneTick() {
        // a move every N ticks eased over N + 1, so a late packet does not stop the body;
        // and every pose the same way, so no channel is left with a gap-long ease (the death film's fall froze for it)
        assertEquals(1, RigCadence.EASE_SLACK);
        assertEquals(RigCadence.DEFAULT_MOVE_INTERVAL + 1, DisplayRig.easeTicks(null));
        assertEquals(RigAnimator.DEFAULT_INTERVAL, RigCadence.DEFAULT_MOVE_INTERVAL, "one move per pose write");
        assertEquals(DisplayRig.easeTicks(null), RigAnimator.ease(RigAnimator.DEFAULT_INTERVAL), "the move and the pose ease alike");
        assertEquals(2, RigAnimator.ease(1), "a pose written every tick (the talk) still has its tick of slack");
        assertEquals(0, RigAnimator.ease(0), "a snap stays a snap");
        assertEquals(5, DisplayRig.easeTicks("5"), "the measuring arm");
    }

    @Test
    void aLookChangeReArmsOnlyARunningEase() {
        // a pose sent at tracker tick 100 with a 4-tick ease ends at 104
        long end = 100 + 4;
        assertEquals(3, RigCadence.remaining(end, 101));
        assertEquals(1, RigCadence.remaining(end, 103));
        assertEquals(0, RigCadence.remaining(end, 104), "finished: nothing to keep");
        assertEquals(0, RigCadence.remaining(end, 140));
        assertEquals(0, RigCadence.remaining(Long.MIN_VALUE, 5), "never posed");
    }

    @Test
    void theSlackArmIsTheOldEase() {
        assertEquals(0, RigCadence.slack("0"));
        assertEquals(1, RigCadence.slack(null));
        assertEquals(1, RigCadence.slack("x"));
    }

    @Test
    void movesGoOutAThirdOfTheTicksOnOneBeat() {
        int sent = 0;
        for (long t = 1000; t < 1000 + 60; t++) {
            if (RigCadence.send(t, false, N) == RigCadence.Send.ALL) {
                sent++;
                assertTrue(RigCadence.onBeat(t, N));
            }
        }
        assertEquals(60 / N, sent, "a third of the ticks");
    }

    @Test
    void theBeatIsTheTrackerTickThatCarriesThePose() {
        // The rig writes at game time t (ServerTickEvent.Post); the tracker sends it at t + 1.
        for (long t = 0; t < 30; t++) {
            if (RigAnimator.writesOn(t, RigAnimator.DEFAULT_INTERVAL)) {
                assertTrue(RigCadence.onBeat(t + RigCadence.TRACKER_LAG, N), "the pose written at " + t + " goes with a move");
            }
        }
    }

    /**
     * The bug found: a posed atom (dirty data) used to send its MOVE off the beat too, so posed parts moved on a
     * different schedule from unposed ones and were drawn ahead of the body. Dirty data off the beat is data only.
     */
    @Test
    void dirtyDataNeverCarriesAMoveOffTheBeat() {
        for (long t = 0; t < 30; t++) {
            RigCadence.Send dirty = RigCadence.send(t, true, N);
            RigCadence.Send clean = RigCadence.send(t, false, N);
            boolean dirtyMoves = dirty == RigCadence.Send.ALL;
            boolean cleanMoves = clean == RigCadence.Send.ALL;
            assertEquals(cleanMoves, dirtyMoves, "a posed and an unposed atom move on the same ticks (t=" + t + ")");
            if (!cleanMoves) {
                assertEquals(RigCadence.Send.DATA_ONLY, dirty, "changed data still goes out at once");
                assertEquals(RigCadence.Send.HOLD, clean);
            }
        }
    }

    @Test
    void theBeatIsTheWorldsSoEveryAtomSharesIt() {
        assertTrue(RigCadence.onBeat(-2, 3), "negative game time is still on the same beat (floorMod)");
        assertFalse(RigCadence.onBeat(-3, 3));
    }

    @Test
    void intervalOneIsVanilla() {
        for (long t = 0; t < 10; t++) {
            assertEquals(RigCadence.Send.ALL, RigCadence.send(t, false, 1));
            assertEquals(RigCadence.Send.ALL, RigCadence.send(t, true, 1));
        }
        assertEquals(1, RigCadence.interval("1"));
        assertEquals(3, RigCadence.interval(null));
        assertEquals(3, RigCadence.interval("x"));
        assertEquals(20, RigCadence.interval("99"));
    }

    @Test
    void onlyRigAtomsAreRigAtoms() {
        assertTrue(RigCadence.isRigAtom(Set.of(DisplayRig.RIG_OWNER_PREFIX + "abc", DisplayRig.RIG_INDEX_PREFIX + "3")));
        assertFalse(RigCadence.isRigAtom(Set.of("bar", "moddy_rig_leaving")));
        assertFalse(RigCadence.isRigAtom(Set.of()));
    }
}
