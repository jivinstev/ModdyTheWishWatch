package com.moddingfromamod.wish;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sight half: who may be seen, as opposed to who may be acted on. */
class CameraPresenceTest {

    @Test
    @DisplayName("a child is never shown a service client")
    void theCameraIsNotAPersonInHisWorld() {
        assertTrue(CameraPresence.hiddenFrom(false, true),
                "the camera stood two metres from the creature with a name tag for the whole "
                        + "wish, and every gate was delighted because it had joined");
    }

    @Test
    @DisplayName("a service client sees the other service clients")
    void camerasSeeEachOther() {
        assertFalse(CameraPresence.hiddenFrom(true, true),
                "#510 puts more than one camera in a world; hiding them from each other would "
                        + "make a queue impossible to debug from inside");
    }

    @Test
    @DisplayName("children are never hidden from anybody")
    void theChildIsAlwaysVisible() {
        assertFalse(CameraPresence.hiddenFrom(false, false),
                "two children in one world must see each other -- this rule is about the mod's "
                        + "own clients, not about crowding");
        assertFalse(CameraPresence.hiddenFrom(true, false),
                "the camera must see the child: he is in the establishing shot, and #503's "
                        + "subject search reads the world the camera can see");
    }
}
