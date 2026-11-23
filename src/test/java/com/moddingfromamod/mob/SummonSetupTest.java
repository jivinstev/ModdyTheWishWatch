package com.moddingfromamod.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.mob.SummonSetup.Repair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which wished mobs need the spawn setup an NBT summon skipped, and only when nothing set it. */
class SummonSetupTest {

    @Test
    @DisplayName("the three mobs whose behaviour breaks without finalizeSpawn, and no others")
    void whichTypes() {
        assertEquals(Repair.WARDEN_DIG_COOLDOWN, SummonSetup.forType("minecraft:warden"));
        assertEquals(Repair.PHANTOM_ANCHOR, SummonSetup.forType("minecraft:phantom"));
        assertEquals(Repair.TURTLE_HOME, SummonSetup.forType("Minecraft:Turtle"));
        for (String other : new String[] {"minecraft:zombie", "minecraft:piglin", "minecraft:slime",
                "minecraft:fox", "somemod:warden", "", null}) {
            assertNull(SummonSetup.forType(other), String.valueOf(other));
        }
    }

    @Test
    @DisplayName("a value the NBT (or finalizeSpawn) already set is the agent's own and is kept")
    void onlyWhenUnset() {
        assertTrue(SummonSetup.needed(Repair.WARDEN_DIG_COOLDOWN, false, 0, 0, 0));
        assertFalse(SummonSetup.needed(Repair.WARDEN_DIG_COOLDOWN, true, 0, 0, 0));
        assertTrue(SummonSetup.needed(Repair.PHANTOM_ANCHOR, true, 0, 0, 0));
        assertFalse(SummonSetup.needed(Repair.PHANTOM_ANCHOR, true, 10, 70, -4));
        assertTrue(SummonSetup.needed(Repair.TURTLE_HOME, true, 0, 0, 0));
        assertFalse(SummonSetup.needed(Repair.TURTLE_HOME, true, 0, 63, 0));
        assertFalse(SummonSetup.needed(null, false, 0, 0, 0));
    }
}
