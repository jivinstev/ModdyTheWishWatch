package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/** a rig atom's move carries its rotation. */
class RigMoveRotationTest {

    @Test
    void theStaleYawAlwaysDiffersByAtLeastOneUnit() {
        // ServerEntity sends the rotation when |packed - lastSent| >= 1, compared as ints of the two bytes
        for (int b = -128; b <= 127; b++) {
            byte packed = (byte) b;
            assertTrue(Math.abs(packed - RigMoveRotation.staleYaw(packed)) >= 1, "packed " + b);
        }
    }

    @Test
    void onlyAMoveCounts() {
        assertTrue(RigMoveRotation.moved(1e-4));
        assertFalse(RigMoveRotation.moved(0), "a still rig sends nothing extra");
        assertFalse(RigMoveRotation.moved(1e-6));
    }

    @Test
    void onlyRigAtomsAreRigAtoms() {
        assertTrue(RigMoveRotation.isRigAtom(Set.of(DisplayRig.RIG_OWNER_PREFIX + "abc")));
        assertFalse(RigMoveRotation.isRigAtom(Set.of("bar")));
        assertTrue(RigMoveRotation.ON, "on by default (the Bedrock extension fix, #2694, has landed)");
    }
}
