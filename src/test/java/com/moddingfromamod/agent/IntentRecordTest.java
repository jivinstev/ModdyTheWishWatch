package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The {@code intent} record (2026-10-10) has the shape {@code tools/pool-economics.py} reads;
 * its Python twin is {@code tools/test-pool-economics.py}. Rename a field here, move that fixture too.
 */
class IntentRecordTest {

    @Test
    void aRunUpIsRecorded() {
        var o = ActionLog.intentRecord("81dbb518", "p1",
                new com.moddingfromamod.marshal.WishIntent.Taken("watch", 23_000, "watch,anvil,key"), false);
        assertEquals("intent", o.get("kind").getAsString());
        assertEquals("81dbb518", o.get("wishId").getAsString());
        assertEquals("watch", o.get("first").getAsString());
        assertEquals(23_000, o.get("leadMs").getAsLong());
        assertEquals("watch,anvil,key", o.get("kinds").getAsString());
        assertFalse(o.get("bootWait").getAsBoolean());
        assertTrue(o.has("at") && o.has("test"));
    }

    @Test
    void noRunUpIsNoneAndNoLead() {
        var o = ActionLog.intentRecord("81dbb518", "p1", null, true);
        assertEquals("none", o.get("first").getAsString());
        assertFalse(o.has("leadMs"), "no lead is never 0");
        assertTrue(o.get("bootWait").getAsBoolean());
    }
}
