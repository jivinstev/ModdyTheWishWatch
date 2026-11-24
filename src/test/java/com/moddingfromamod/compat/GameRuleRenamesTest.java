package com.moddingfromamod.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GameRuleRenamesTest {

    @Test
    void theCompilersBoilerplateFromAnOldPackTakesTheNewId() {
        assertEquals("gamerule max_command_sequence_length 1000000000",
                GameRuleRenames.correctLine("gamerule maxCommandChainLength 1000000000", "1.21.11"));
        assertEquals("/gamerule max_command_sequence_length 1000000000",
                GameRuleRenames.correctLine("/gamerule maxCommandChainLength 1000000000", "1.21.11"));
    }

    @Test
    void onAnOlderMinecraftNothingChanges() {
        String line = "gamerule maxCommandChainLength 1000000000";
        assertSame(line, GameRuleRenames.correctLine(line, "1.21.10"));
        assertSame(line, GameRuleRenames.correctLine(line, "1.21.8"));
    }

    @Test
    void afterRunNamespacedAndInAWholeFunction() {
        assertEquals("execute as @a run minecraft:gamerule advance_time false",
                GameRuleRenames.correctLine("execute as @a run minecraft:gamerule doDaylightCycle false", "1.21.11"));
        assertEquals("say hi\ngamerule keep_inventory true\n",
                GameRuleRenames.correct("say hi\ngamerule keepInventory true\n", "1.21.11"));
    }

    @Test
    void anInvertedRuleFlipsItsValueAndAQueryIsOnlyRenamed() {
        assertEquals("gamerule raids false", GameRuleRenames.correctLine("gamerule disableRaids true", "1.21.11"));
        assertEquals("gamerule elytra_movement_check true",
                GameRuleRenames.correctLine("gamerule disableElytraMovementCheck false", "1.21.11"));
        assertEquals("gamerule raids", GameRuleRenames.correctLine("gamerule disableRaids", "1.21.11"));
    }

    @Test
    void doFireTickBecomesTheSpreadRadiusAsMojangsFixDoes() {
        assertEquals("gamerule fire_spread_radius_around_player 0",
                GameRuleRenames.correctLine("gamerule doFireTick false", "1.21.11"));
        assertEquals("gamerule fire_spread_radius_around_player 128",
                GameRuleRenames.correctLine("gamerule doFireTick true", "1.21.11"));
    }

    @Test
    void integersPassThroughAndANewIdOrOtherTextIsUntouched() {
        assertEquals("gamerule random_tick_speed 0", GameRuleRenames.correctLine("gamerule randomTickSpeed 0", "1.21.11"));
        for (String keep : new String[] {"gamerule advance_time false", "say gamerule doDaylightCycle false",
                "tellraw @a \"gamerule keepInventory\"", "scoreboard players set #gamerule x 1",
                "gamerule allowFireTicksAwayFromPlayer true"}) {
            assertSame(keep, GameRuleRenames.correctLine(keep, "1.21.11"), keep);
        }
    }

    @Test
    void theTableHasOneRowPerOldNameAndOnlySnakeCaseTargets() {
        Set<String> from = new HashSet<>();
        for (GameRuleRenames.Row r : GameRuleRenames.ROWS) {
            assertTrue(from.add(r.from()), "two rows for " + r.from());
            assertTrue(r.to().matches("[a-z_]+"), r.to());
        }
        assertTrue(from.contains("maxCommandChainLength") && from.contains("doDaylightCycle"));
    }
}
