package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** which name a rig's plate shows — only one a child GAVE, never the species' own birth name. */
class RigLookNameTest {

    @Test
    void anUnnamedCreatureHasNoPlate() {
        assertNull(DisplayRig.shownNameFor(null, "Charge"));
        assertNull(DisplayRig.shownNameFor("  ", "Charge"));
    }

    @Test
    void theSpeciesOwnNameIsNotAPlate() {
        // every carrier is born wearing its species' name: a plate on every starter would be a new look
        assertNull(DisplayRig.shownNameFor("Charge", "Charge"));
    }

    @Test
    void aNameHeGaveItIsShown() {
        assertEquals("Sparky", DisplayRig.shownNameFor("Sparky", "Charge"));
        assertEquals("Sparky", DisplayRig.shownNameFor("Sparky", null));
    }
}
