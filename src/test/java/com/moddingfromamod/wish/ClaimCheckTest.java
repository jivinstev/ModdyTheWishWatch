package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** a claim's `check` is a closed, bounded type — nothing else gets through. */
class ClaimCheckTest {

    private static ClaimCheck parse(String json) {
        return ClaimCheck.parse(JsonParser.parseString(json));
    }

    @Test
    @DisplayName("no check is null: a claim nothing checks, told as 'I tried'")
    void absentIsNull() {
        assertNull(ClaimCheck.parse(null));
        assertNull(ClaimCheck.parse(com.google.gson.JsonNull.INSTANCE));
    }

    @Test
    @DisplayName("the pickleball check parses with its defaults and bounds")
    void pickleball() {
        ClaimCheck c = parse("{\"kind\":\"creature_count\",\"what\":\"pickleball_player\",\"min\":2,\"radius\":8}");
        assertEquals(ClaimCheck.Kind.CREATURE_COUNT, c.kind());
        assertEquals(2, c.min());
        assertEquals(8, c.radius());
        assertEquals(ClaimCheck.Near.WISH, c.near());
        assertTrue(c.watched());
        ClaimCheck f = parse("{\"kind\":\"follows\",\"what\":\"spikor\"}");
        assertEquals(1, f.min());
        assertEquals(ClaimCheck.DEFAULT_RADIUS, f.radius());
        assertEquals(ClaimCheck.Near.CHILD, parse("{\"kind\":\"block-present\",\"what\":\"gold_block\",\"near\":\"player\"}").near());
    }

    @Test
    @DisplayName("an unknown kind, a missing or unsafe `what`, or an out-of-range number is refused by name")
    void refusals() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> parse("{\"kind\":\"run_command\",\"what\":\"x\"}")).getMessage().contains("creature_count"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"kind\":\"follows\"}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"kind\":\"follows\",\"what\":\"@e[type=cow]\"}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"kind\":\"creature_count\",\"what\":\"cow\",\"min\":0}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"kind\":\"creature_count\",\"what\":\"cow\",\"radius\":500}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"kind\":\"creature_count\",\"what\":\"cow\",\"near\":\"moon\"}"));
        assertThrows(IllegalArgumentException.class, () -> parse("\"creature_count\""));
    }
}
