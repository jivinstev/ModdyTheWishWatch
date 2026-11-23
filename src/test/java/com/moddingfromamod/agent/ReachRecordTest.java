package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The {@code reach} record — the one row per time a wish was stopped from reaching a child who did
 * not make it — has the shape the wish statistics count. A renamed field here must move in their
 * reader too.
 */
class ReachRecordTest {

    @Test
    void theFieldsTheStatsCount() {
        var o = ActionLog.reachRecord("run_command", "narrowed", PersonSelectors.WHY_EVERYONE,
                "@a → @a[name=Alice]", "effect give @a minecraft:glowing 30", 2);
        assertEquals("reach", o.get("kind").getAsString());
        assertEquals("run_command", o.get("tool").getAsString());
        assertEquals("narrowed", o.get("action").getAsString());
        assertEquals("reaches_everyone", o.get("why").getAsString());
        assertEquals(2, o.get("others").getAsInt());
        assertTrue(o.has("at") && o.has("test"));
        assertEquals("effect give @a minecraft:glowing 30", o.get("command").getAsString());
    }

    @Test
    void aPersonToolRefusalCarriesNoCommand() {
        var o = ActionLog.reachRecord("give_items", "refused", "owner_left", "", null, -1);
        assertFalse(o.has("command"));
        assertEquals(0, o.get("others").getAsInt(), "never negative");
    }
}
