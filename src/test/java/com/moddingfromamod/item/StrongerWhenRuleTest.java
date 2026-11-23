package com.moddingfromamod.item;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.datapack.ItemProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** every condition, the refusals, and the rendered custom_data. */
class StrongerWhenRuleTest {

    private static final StrongerWhenRule.World NOON = new StrongerWhenRule.World(false, false, false, 64, false, false);

    private static boolean holds(String when, StrongerWhenRule.World w) {
        return new StrongerWhenRule.Setting(when, "attack_damage", 6, 0).holds(w);
    }

    @Test
    void eachConditionHoldsExactlyWhenItShould() {
        assertFalse(holds("night", NOON));
        assertTrue(holds("night", new StrongerWhenRule.World(true, false, false, 64, false, false)));
        assertTrue(holds("day", NOON));
        assertTrue(holds("thunder", new StrongerWhenRule.World(false, true, true, 64, false, false)));
        assertFalse(holds("thunder", new StrongerWhenRule.World(false, false, true, 64, false, false)));
        assertTrue(holds("rain", new StrongerWhenRule.World(false, false, true, 64, false, false)));
        assertTrue(holds("below_y", new StrongerWhenRule.World(false, false, false, -1, false, false)));
        assertFalse(holds("below_y", NOON));
        assertTrue(holds("in_nether", new StrongerWhenRule.World(false, false, false, 64, true, false)));
        assertFalse(holds("night", new StrongerWhenRule.World(true, false, false, 64, true, false)),
                "the Nether has no night");
        assertTrue(holds("underwater", new StrongerWhenRule.World(false, false, false, 64, false, true)));
    }

    @Test
    void anythingOutsideTheListsIsRefusedByName() {
        assertNotNull(StrongerWhenRule.refusal("full_moon", "attack_damage", 6));
        assertNotNull(StrongerWhenRule.refusal("night", "luck", 6));
        assertNotNull(StrongerWhenRule.refusal("night", "attack_damage", 99));
        assertNotNull(StrongerWhenRule.refusal("night", "mining_speed", 11));
        assertNull(StrongerWhenRule.refusal("Night", "attack_damage", 20));
    }

    @Test
    void thePropertyRendersFromOurTablesNeverTheAgentsText() {
        JsonObject props = JsonParser.parseString(
                "{\"stronger_when\":{\"when\":\" NIGHT\",\"stat\":\"attack_damage\",\"amount\":\"6\"}}")
                .getAsJsonObject();
        assertTrue(ItemProperties.refusals(props).isEmpty(), ItemProperties.refusals(props).toString());
        assertEquals(",WishStrongerWhen:{when:\"night\",stat:\"attack_damage\",amount:6,y:0}",
                ItemProperties.dataEntries(props));
        assertEquals("", ItemProperties.render(props), "no component: it rides custom_data");
        JsonObject bad = JsonParser.parseString(
                "{\"stronger_when\":{\"when\":\"night\\\"}]\",\"stat\":\"attack_damage\",\"amount\":6}}")
                .getAsJsonObject();
        assertFalse(ItemProperties.refusals(bad).isEmpty());
        assertEquals("", ItemProperties.dataEntries(bad), "an injected word renders nothing");
    }
}
