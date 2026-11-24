package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** AWT is made headless unless someone already chose (a choice either way is kept). */
class HeadlessAwtTest {

    private String before;

    @BeforeEach
    void remember() {
        before = System.getProperty(HeadlessAwt.PROPERTY);
    }

    @AfterEach
    void restore() {
        if (before == null) {
            System.clearProperty(HeadlessAwt.PROPERTY);
        } else {
            System.setProperty(HeadlessAwt.PROPERTY, before);
        }
    }

    @Test
    void unsetBecomesHeadless() {
        System.clearProperty(HeadlessAwt.PROPERTY);
        assertTrue(HeadlessAwt.ensure());
        assertEquals("true", System.getProperty(HeadlessAwt.PROPERTY));
    }

    @Test
    void anExplicitChoiceIsKept() {
        System.setProperty(HeadlessAwt.PROPERTY, "false");
        assertFalse(HeadlessAwt.ensure());
        assertEquals("false", System.getProperty(HeadlessAwt.PROPERTY));
    }
}
