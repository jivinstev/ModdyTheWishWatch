package com.moddingfromamod.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.event.catching.CatchRules;
import org.junit.jupiter.api.Test;

/** The feel of what an event does to what it catches (E3). */
class CatchRulesTest {

    @Test
    void aPushNeverCompoundsHoweverOftenItIsApplied() {
        double v = 0;
        for (int i = 0; i < 200; i++) {
            v = CatchRules.carry(v, 1.0);
        }
        assertEquals(CatchRules.MAX_CARRY, v, 1e-9, "a wave holds you at its speed, it does not add up");
        double y = 0;
        for (int i = 0; i < 200; i++) {
            y = CatchRules.vertical(y, 1.0, false);
        }
        assertEquals(CatchRules.MAX_LIFT, y, 1e-9, "a lift every tick is still one lift");
    }

    @Test
    void somethingAlreadyFasterKeepsItsOwnSpeed() {
        assertEquals(2.0, CatchRules.carry(2.0, 1.0), 1e-9);
        assertEquals(-1.5, CatchRules.vertical(-1.5, 1.0, true), 1e-9, "already falling harder than a slam");
    }

    @Test
    void aWaveCarriesItDoesNotLaunch() {
        assertTrue(CatchRules.MAX_LIFT < CatchRules.MAX_CARRY / 2,
                "the lift is far under the push, or a wave is a catapult");
        assertTrue(CatchRules.vertical(0, 1.0, true) < 0, "a falling crest slams you down");
    }

    @Test
    void aQuakeHasAMiddleAndAnEdge() {
        assertEquals(1.0, CatchRules.falloff(0, 20), 1e-9);
        assertEquals(0.0, CatchRules.falloff(20, 20), 1e-9, "nothing at exactly its reach");
        assertTrue(CatchRules.falloff(5, 20) > CatchRules.falloff(15, 20));
        double[] j = CatchRules.shake(1.0, 0.3);
        assertEquals(CatchRules.SHAKE, Math.hypot(j[0], j[2]), 1e-9);
        assertEquals(0.0, Math.hypot(CatchRules.shake(0, 1)[0], CatchRules.shake(0, 1)[2]), 1e-9);
    }

    @Test
    void anEventHeWishedForNeverKillsHim() {
        assertEquals(18.0F, CatchRules.hurt(100, 20, true), 1e-6);
        assertEquals(0.0F, CatchRules.hurt(5, 2, true), 1e-6, "at one heart, nothing more");
        assertEquals(0.0F, CatchRules.hurt(5, 1, true), 1e-6, "and never a negative hurt");
        assertEquals(100.0F, CatchRules.hurt(100, 20, false), 1e-6, "a monster gets the lot");
    }

    @Test
    void fragileThingsBreakSometimesAndSolidOnesNever() {
        assertEquals(0.0, CatchRules.breakChance(CatchRules.Fragile.NONE, 1.0), 1e-9);
        assertTrue(CatchRules.breakChance(CatchRules.Fragile.GLASS, 1.0) < 1.0,
                "not every stone breaks a pane: sometimes, not all the time");
        assertTrue(CatchRules.breakChance(CatchRules.Fragile.CLUTTER, 1.0)
                < CatchRules.breakChance(CatchRules.Fragile.GLASS, 1.0));
        assertEquals(0.0, CatchRules.breakChance(CatchRules.Fragile.GLASS, 0), 1e-9);
    }

    @Test
    void fireOnlyWhereItCannotReachHisBuild() {
        assertTrue(CatchRules.mayLight(true, true, false));
        assertFalse(CatchRules.mayLight(true, true, true), "never beside something that burns");
        assertFalse(CatchRules.mayLight(true, false, false), "never hanging in the air");
        assertFalse(CatchRules.mayLight(false, true, false), "never inside a block");
        assertTrue(CatchRules.burnSeconds(0.01) >= 1 && CatchRules.burnSeconds(1) == CatchRules.MAX_BURN_SECONDS);
    }
}
