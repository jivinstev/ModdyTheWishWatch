package com.moddingfromamod.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** a polished stack glints for a moment and is then exactly what it was. */
class PolishGlintTest {

    @Test
    void whateverTheOverrideWasComesBack() {
        for (Boolean was : new Boolean[] {null, Boolean.TRUE, Boolean.FALSE}) {
            assertEquals(was, PolishGlint.restore(PolishGlint.was(was)), "round trip of " + was);
        }
    }

    @Test
    void anUnknownRecordRemovesTheOverrideRatherThanLeavingAGlint() {
        assertNull(PolishGlint.restore(""));
        assertNull(PolishGlint.restore(null));
    }

    @Test
    void itRunsItsTimeAndNoLonger() {
        long until = 100 + PolishGlint.TICKS;
        assertFalse(PolishGlint.expired(until, 100));
        assertFalse(PolishGlint.expired(until, until - 1));
        assertTrue(PolishGlint.expired(until, until));
        assertTrue(PolishGlint.expired(until, Long.MAX_VALUE), "a login takes any glint off");
    }
}
