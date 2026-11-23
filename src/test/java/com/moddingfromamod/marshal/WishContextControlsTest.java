package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The two switches the wish-context experiment cannot run without.
 *
 * <p>Same job as {@link WishSlotsTest} one level up: if the control does not really move, every
 * finding after it is unfalsifiable. "Arm A beat arm B" means nothing unless the flag genuinely put
 * the run in arm A.
 */
class WishContextControlsTest {

    @AfterEach
    void reset() {
        WishAffinity.override(null);
        WishContextMode.override(null);
        System.clearProperty(WishAffinity.PROPERTY);
        System.clearProperty(WishContextMode.PROPERTY);
    }

    // ================= defaults =================

    @Test
    void outOfTheBoxNothingIsForcedAndNothingIsInjected() {
        // Phase 1 is instrumentation. A control that changed what a child sees before the thing it
        // controls had been measured would make its own baseline unmeasurable.
        assertEquals(WishAffinity.OFF, WishAffinity.current());
        assertEquals(WishContextMode.OFF, WishContextMode.current());
        assertFalse(WishAffinity.current().forcing());
        assertFalse(WishContextMode.current().injects());
    }

    // ================= the property really moves them =================

    @Test
    void thePropertyMovesTheAffinity() {
        System.setProperty(WishAffinity.PROPERTY, "same");
        assertEquals(WishAffinity.SAME, WishAffinity.current());
        assertTrue(WishAffinity.current().forcing());

        System.setProperty(WishAffinity.PROPERTY, "DIFFERENT");
        assertEquals(WishAffinity.DIFFERENT, WishAffinity.current(), "case must not matter");
    }

    @Test
    void thePropertyMovesTheContextMode() {
        System.setProperty(WishContextMode.PROPERTY, "on");
        assertEquals(WishContextMode.ON, WishContextMode.current());
        assertTrue(WishContextMode.current().injects());

        System.setProperty(WishContextMode.PROPERTY, " shuffled ");
        assertEquals(WishContextMode.SHUFFLED, WishContextMode.current(), "whitespace must not matter");
        assertTrue(WishContextMode.current().injects(),
                "the control arm pays the same token cost as the treatment — that is its whole job");
    }

    // ================= read per call, not cached =================

    /**
     * The property is read on EVERY call, like {@link WishSlots}. Cached, one arm would need one
     * client boot, and at ~117 s a wish that is the difference between an experiment and an evening.
     */
    @Test
    void bothAreReadOnEveryCallSoATestCanFlipThemMidRun() {
        assertEquals(WishAffinity.OFF, WishAffinity.current());
        System.setProperty(WishAffinity.PROPERTY, "same");
        assertEquals(WishAffinity.SAME, WishAffinity.current(), "no cached value may survive here");
        System.setProperty(WishAffinity.PROPERTY, "different");
        assertEquals(WishAffinity.DIFFERENT, WishAffinity.current());

        assertEquals(WishContextMode.OFF, WishContextMode.current());
        System.setProperty(WishContextMode.PROPERTY, "on");
        assertEquals(WishContextMode.ON, WishContextMode.current());
    }

    @Test
    void anOverrideWinsAndClearsCleanly() {
        System.setProperty(WishAffinity.PROPERTY, "same");
        WishAffinity.override(WishAffinity.DIFFERENT);
        assertEquals(WishAffinity.DIFFERENT, WishAffinity.current(), "the override wins");
        WishAffinity.override(null);
        assertEquals(WishAffinity.SAME, WishAffinity.current(), "and clearing it restores the property");

        WishContextMode.override(WishContextMode.SHUFFLED);
        assertEquals(WishContextMode.SHUFFLED, WishContextMode.current());
        WishContextMode.override(null);
        assertEquals(WishContextMode.OFF, WishContextMode.current());
    }

    // ================= a typo may never invent an arm =================

    /**
     * A misread flag falls back to the DEFAULT, never to a forcing mode or a treatment.
     *
     * <p>The failure this prevents is silent: a typo that turned forcing on would produce an arm
     * nobody asked for, labelled with whatever the operator believed they had set — which is exactly
     * the "distinguishable only by the operator's memory" defect the controls exist to remove.
     */
    @Test
    void aTypoFallsBackToTheDefaultRatherThanInventingAnArm() {
        System.setProperty(WishAffinity.PROPERTY, "smae");
        assertEquals(WishAffinity.OFF, WishAffinity.current());
        System.setProperty(WishAffinity.PROPERTY, "");
        assertEquals(WishAffinity.OFF, WishAffinity.current());

        System.setProperty(WishContextMode.PROPERTY, "yes");
        assertEquals(WishContextMode.OFF, WishContextMode.current());
        assertFalse(WishContextMode.current().injects(),
                "a typo must never silently switch the treatment on");
    }

    // ================= labels, because the log and the scorer share them =================

    @Test
    void everyModeHasTheLowerCaseLabelTheLogWrites() {
        assertEquals("off", WishAffinity.OFF.label());
        assertEquals("same", WishAffinity.SAME.label());
        assertEquals("different", WishAffinity.DIFFERENT.label());
        assertEquals("off", WishContextMode.OFF.label());
        assertEquals("on", WishContextMode.ON.label());
        assertEquals("shuffled", WishContextMode.SHUFFLED.label());
        // Round-trip, because the scorer reads these strings back out of the log to bucket arms.
        for (WishAffinity m : WishAffinity.values()) {
            assertEquals(m, WishAffinity.parse(m.label()));
        }
        for (WishContextMode m : WishContextMode.values()) {
            assertEquals(m, WishContextMode.parse(m.label()));
        }
    }
}
