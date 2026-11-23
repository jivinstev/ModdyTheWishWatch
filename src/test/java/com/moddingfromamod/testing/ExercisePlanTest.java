package com.moddingfromamod.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** the behaviour exercise's script, without a server. */
class ExercisePlanTest {

    @Test
    void phasesRunInOrderAndEnd() {
        assertEquals("settle", ExercisePlan.at(0).name());
        assertEquals("idle", ExercisePlan.at(40).name());
        assertEquals(0, ExercisePlan.into(40));
        int last = ExercisePlan.totalTicks() - 1;
        assertEquals("death", ExercisePlan.at(last).name(), "the death must come last: it ends the creature");
        assertNull(ExercisePlan.at(ExercisePlan.totalTicks()));
        assertEquals(-1, ExercisePlan.into(ExercisePlan.totalTicks()));
    }

    @Test
    void everyJudgedPhaseIsLongEnoughToSeeACooldownFire() {
        // The slowest ability cooldown a creature ships with is 300 ticks (summon), but most are
        // 40-120 and an effect's timer is the agent's choice; a phase shorter than 100 ticks would
        // read "never did it" for a creature that simply had not yet. The death phase only needs
        // the tick it dies on and a few after.
        for (ExercisePlan.Phase p : ExercisePlan.PHASES) {
            if (!p.name().equals("settle") && !p.name().equals("death")) {
                assertTrue(p.ticks() >= 100, p + " is too short to judge");
            }
        }
    }

    @Test
    void jumpsArePressedOnlyInJumpingPhases() {
        ExercisePlan.Phase riderJump = ExercisePlan.PHASES.stream().filter(p -> p.name().equals("rider_jump"))
                .findFirst().orElseThrow();
        ExercisePlan.Phase idle = ExercisePlan.at(40);
        int n = 0;
        for (int t = 0; t < riderJump.ticks(); t++) {
            n += ExercisePlan.jumpNow(riderJump, t) ? 1 : 0;
            assertFalse(ExercisePlan.jumpNow(idle, t));
        }
        assertEquals(ExercisePlan.JUMPS_AT.length, n);
    }

    @Test
    void theWalkGoesOutAndComesBackPastTheMonsters() {
        assertEquals(ExercisePlan.WALK_TO, ExercisePlan.walkZ(0, 240));
        assertEquals(0.0, ExercisePlan.walkZ(200, 240));
        // the path passes within reach of every near monster
        for (double[] e : ExercisePlan.ENEMIES) {
            assertTrue(Math.abs(e[0]) <= 3.5 && e[1] >= 0 && e[1] <= ExercisePlan.WALK_TO, "off the path: " + e[0]);
        }
    }
}
