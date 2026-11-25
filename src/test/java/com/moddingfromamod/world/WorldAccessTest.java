package com.moddingfromamod.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #82: each case of a world's access settings, the one line it logs, and that what the core does is unchanged. */
class WorldAccessTest {

    private static final String OPEN = "{\"crafters\": {\"open\": true}}";
    private static final String SHUT = "{\"crafters\": {\"open\": false}}";

    @Test
    @DisplayName("single player / no world id: quiet")
    void noWorldIsQuiet() {
        WorldAccess a = WorldAccess.classify("", () -> {
            throw new AssertionError("no manifest is read where no world expects one");
        }, false);
        assertEquals(WorldAccess.State.QUIET, a.state());
        assertFalse(a.warns());
        assertEquals(WorldAccess.State.QUIET, WorldAccess.classify(null, null, true).state());
    }

    @Test
    @DisplayName("a manifest with no crafters block: quiet")
    void noCraftersBlockIsQuiet() {
        WorldAccess a = WorldAccess.classify("isle", () -> "{\"name\": \"Isle\"}", false);
        assertEquals(WorldAccess.State.QUIET, a.state());
        assertFalse(a.warns());
    }

    @Test
    @DisplayName("read and consistent: no line")
    void readIsQuiet() {
        assertEquals(WorldAccess.State.READ, WorldAccess.classify("isle", () -> SHUT, false).state());
        WorldAccess open = WorldAccess.classify("isle", () -> OPEN, true);
        assertEquals(WorldAccess.State.READ, open.state());
        assertFalse(open.warns());
    }

    @Test
    @DisplayName("missing where a world expects it: one line naming the file and the default")
    void missingWarns() {
        WorldAccess a = WorldAccess.classify("isle", () -> null, true);
        assertEquals(WorldAccess.State.MISSING, a.state());
        assertTrue(a.line().contains("worlds/isle.json") && a.line().contains("not found")
                && a.line().contains("default") && a.line().contains("Nothing is refused"), a.line());
    }

    @Test
    @DisplayName("unreadable: one line, and the reader's exception is not thrown on")
    void unreadableWarns() {
        WorldAccess a = WorldAccess.classify("isle", () -> {
            throw new IllegalStateException("disk");
        }, true);
        assertEquals(WorldAccess.State.UNREADABLE, a.state());
        assertTrue(a.line().contains("worlds/isle.json") && a.line().contains("could not be read")
                && a.line().contains("default"), a.line());
    }

    @Test
    @DisplayName("malformed (bad JSON, or a crafters block that is not an object): one line")
    void malformedWarns() {
        for (String bad : new String[] {"{not json", "[1, 2]", "{\"crafters\": true}"}) {
            WorldAccess a = WorldAccess.classify("isle", () -> bad, true);
            assertEquals(WorldAccess.State.MALFORMED, a.state(), bad);
            assertTrue(a.line().contains("malformed") && a.line().contains("default"), a.line());
        }
    }

    @Test
    @DisplayName("the door open while the server's allow list is off: one line saying what to turn on")
    void openDoorWithoutAllowListWarns() {
        WorldAccess a = WorldAccess.classify("isle", () -> OPEN, false);
        assertEquals(WorldAccess.State.ALLOW_LIST_OFF, a.state());
        assertTrue(a.line().contains("white-list") && a.line().contains("Nothing is refused"), a.line());
    }

    @Test
    @DisplayName("behaviour is unchanged: what the core reads from each manifest is what it read before")
    void behaviourUnchanged() {
        // the default for anything unread or malformed is the door shut, as it always was
        assertEquals(WorldCrafters.NONE, WorldCrafters.parse("{not json"));
        assertEquals(WorldCrafters.NONE, WorldCrafters.parse("{\"crafters\": true}"));
        assertEquals(WorldCrafters.NONE, WorldCrafters.forId("no-such-world-82"));
        // and a warned open door is still open: the warning changes nothing
        WorldAccess.classify("isle", () -> OPEN, false);
        assertTrue(WorldCrafters.parse(OPEN).open());
    }

    @Test
    @DisplayName("the plug-in reader answers quiet before the server starts, then what was published")
    void readerPublishes() {
        WorldAccess was = WorldAccess.current();
        try {
            WorldAccess.publish(null);
            assertEquals(WorldAccess.UNKNOWN, WorldAccess.current());
            WorldAccess missing = WorldAccess.classify("isle", () -> null, true);
            WorldAccess.publish(missing);
            assertEquals(missing, WorldAccess.current());
        } finally {
            WorldAccess.publish(was);
        }
    }
}
