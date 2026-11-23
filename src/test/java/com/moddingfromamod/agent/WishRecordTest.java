package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The {@code wish} record's {@code templateId} and the {@code promotion} record have the
 * shapes offline reporting tools count; if a field is renamed here, their fixtures must move with it.
 */
class WishRecordTest {

    @Test
    void aServedWishNamesItsRecipe() {
        var o = ActionLog.wishRecord("a pet dragon", "FULFILLING", "PRESET", "abcd1234",
                "11111111-2222-3333-4444-555555555555", "starter_pet_dragon");
        assertEquals("wish", o.get("kind").getAsString());
        assertEquals("abcd1234", o.get("wishId").getAsString());
        assertEquals("PRESET", o.get("source").getAsString());
        assertEquals("FULFILLING", o.get("outcome").getAsString());
        assertEquals("starter_pet_dragon", o.get("templateId").getAsString());
        assertTrue(o.has("at") && o.has("test") && o.has("text"));
    }

    @Test
    void anAgentWishCarriesNoRecipeAtAll() {
        var o = ActionLog.wishRecord("a rainbow shark", "PENDING_ESCALATION", "TYPED", "abcd1234",
                "", "");
        assertFalse(o.has("templateId"), "omitted, not empty");
        assertFalse(o.has("playerId"));
        var legacy = ActionLog.wishRecord("x", "EMPTY", "TYPED", "", "", null);
        assertFalse(legacy.has("templateId"));
    }

    @Test
    void aPromotionSaysWhatBecameWhatAndThroughWhichDoor() {
        var o = ActionLog.promotionRecord("spikor~1f2e3d4c", "spikor", "moddyop", false);
        assertEquals("promotion", o.get("kind").getAsString());
        assertEquals("spikor~1f2e3d4c", o.get("from").getAsString());
        assertEquals("spikor", o.get("id").getAsString());
        assertEquals("moddyop", o.get("via").getAsString());
        assertFalse(o.get("force").getAsBoolean());
        assertTrue(o.has("at") && o.has("test"));
    }

    @Test
    void aGrantedWishSaysWhatItMadeByKindAndKey() {
        var o = ActionLog.madeRecord("abcd1234", java.util.List.of(
                new com.moddingfromamod.marshal.WishRun.Made("creature", "spikor~1f2e3d4c"),
                new com.moddingfromamod.marshal.WishRun.Made("datapack", "fire_breath")));
        assertEquals("made", o.get("kind").getAsString());
        assertEquals("abcd1234", o.get("wishId").getAsString());
        var made = o.getAsJsonArray("made");
        assertEquals(2, made.size());
        assertEquals("creature", made.get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals("spikor~1f2e3d4c", made.get(0).getAsJsonObject().get("id").getAsString());
        assertFalse(o.has("text") || o.has("playerId"), "no words, no player — the wish record has those");
    }

    // ──: a test wish on production says so, and so do its later records ──────────────

    @Test
    void aChildsWishSaysChild() {
        try {
            var o = ActionLog.wishRecord("make me a dragon", "PENDING_ESCALATION", "COMMAND",
                    "abcd1234", "07e1e78c-0cfa-3cca-91c6-3cf484ce277d", "");
            assertEquals("child", o.get("origin").getAsString(),
                    "/wish is COMMAND too, and it is how the child sends a long wish");
            var later = new com.google.gson.JsonObject();
            later.addProperty("kind", "tool_call");
            later.addProperty("wishId", "abcd1234");
            ActionLog.stampOrigin(later);
            assertFalse(later.has("origin"), "a child's later records are left alone");
        } finally {
            com.moddingfromamod.wish.WishOriginTestAccess.reset();
        }
    }

    @Test
    void aCanaryWishAndEveryRecordAfterItAreTest() {
        try (var scope = com.moddingfromamod.wish.WishOrigin.scope(
                com.moddingfromamod.wish.WishOrigin.CANARY)) {
            var o = ActionLog.wishRecord("a lighthouse", "PENDING_ESCALATION", "COMMAND",
                    "c0ffee12", "65c9acfc-ae7e-3e02-b0ac-df045f09af05", "");
            assertEquals("canary", o.get("origin").getAsString());
            assertTrue(o.get("test").getAsBoolean());
        }
        try {
            for (String kind : new String[] {"tool_call", "made", "settled", "said"}) {
                var later = new com.google.gson.JsonObject();
                later.addProperty("kind", kind);
                later.addProperty("test", false);
                // tool calls name the FULL id; the wish record named the short one
                later.addProperty("wishId", "c0ffee12-0000-1111-2222-333333333333");
                ActionLog.stampOrigin(later);
                assertTrue(later.get("test").getAsBoolean(), kind);
                assertEquals("canary", later.get("origin").getAsString(), kind);
            }
        } finally {
            com.moddingfromamod.wish.WishOriginTestAccess.reset();
        }
    }
}
