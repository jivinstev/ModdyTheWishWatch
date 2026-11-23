package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * the one rule — a service client is shown only to other service clients — written
 * out as a table, (viewer kind × subject kind) for every surface a child could meet a camera on.
 */
class ServiceClientRuleTest {

    private static final UUID CHILD = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID CHILD2 = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CAMERA = UUID.fromString("00000000-0000-0000-0000-0000000000ca");
    private static final UUID CAMERA2 = UUID.fromString("00000000-0000-0000-0000-0000000000cb");
    private static final Set<UUID> SERVICE = Set.of(CAMERA, CAMERA2);

    @Test
    @DisplayName("every surface, every (viewer, subject): only a service client sees a service client")
    void theTable() {
        for (ServiceClientRule.Surface s : ServiceClientRule.Surface.values()) {
            assertTrue(ServiceClientRule.shown(s, false, false), s + ": a child sees a child");
            assertFalse(ServiceClientRule.shown(s, false, true), s + ": a child does NOT see a camera");
            assertTrue(ServiceClientRule.shown(s, true, true), s + ": a camera sees a camera (#510)");
            assertTrue(ServiceClientRule.shown(s, true, false), s + ": a camera sees a child (it photographs them)");
            assertFalse(ServiceClientRule.shownToPublic(s, true), s + ": the public never sees a camera");
            assertTrue(ServiceClientRule.shownToPublic(s, false), s + ": the public sees a child");
        }
    }

    @Test
    @DisplayName("the tab list a child gets leaves the cameras out; a camera's keeps everyone")
    void tabList() {
        List<UUID> online = List.of(CHILD, CAMERA, CHILD2, CAMERA2);
        assertEquals(List.of(CHILD, CHILD2), ServiceClientRule.visible(false, online, SERVICE));
        assertEquals(online, ServiceClientRule.visible(true, online, SERVICE));
        assertEquals(List.of(), ServiceClientRule.visible(false, List.of(CAMERA), SERVICE),
                "the camera joining: nothing to tell a child");
        assertEquals(online, ServiceClientRule.visible(false, online, Set.of()), "no camera registered: unchanged");
    }

    @Test
    @DisplayName("the server list and 'is anybody on' count children only")
    void counts() {
        assertEquals(0, ServiceClientRule.publicCount(List.of(CAMERA, CAMERA2), SERVICE),
                "an empty server with two cameras reads 0, not 2");
        assertEquals(2, ServiceClientRule.publicCount(List.of(CHILD, CAMERA, CHILD2), SERVICE));
        assertEquals(1, ServiceClientRule.publicCount(List.of(CHILD), Set.of()));
        assertEquals(0, ServiceClientRule.publicCount(null, SERVICE));
    }

    @Test
    @DisplayName("a broadcast line about a camera is not sent; one about a child is")
    void lines() {
        assertTrue(ServiceClientRule.aboutServiceClient(List.of(CAMERA), SERVICE), "Moddy-Camera joined the game");
        assertFalse(ServiceClientRule.aboutServiceClient(List.of(CHILD), SERVICE), "Alice joined the game");
        assertTrue(ServiceClientRule.aboutServiceClient(List.of(CHILD, CAMERA), SERVICE),
                "a line naming both is about the camera too");
        assertFalse(ServiceClientRule.aboutServiceClient(List.of(), SERVICE), "a line naming nobody");
        assertFalse(ServiceClientRule.aboutServiceClient(List.of(CAMERA), Set.of()), "no camera registered");
    }
}
