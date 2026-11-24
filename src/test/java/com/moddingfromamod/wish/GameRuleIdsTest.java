package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The pre-1.21.11 game-rule ids are rewritten to 1.21.11's snake_case ones (ported from the 26.2 fork). */
class GameRuleIdsTest {

    private static String fix(String cmd) {
        return GameRuleIds.correct(cmd, new ArrayList<>());
    }

    @Test
    void theDaylightWishIsRenamed() {
        List<String> notes = new ArrayList<>();
        assertEquals("gamerule advance_time false", GameRuleIds.correct("gamerule doDaylightCycle false", notes));
        assertEquals(1, notes.size(), notes.toString());
        assertEquals("/gamerule advance_weather false", fix("/gamerule doWeatherCycle false"));
        assertEquals("execute as @s run gamerule keep_inventory true",
                fix("execute as @s run gamerule keepInventory true"));
    }

    @Test
    void anInvertedRuleFlipsItsValue() {
        assertEquals("gamerule raids false", fix("gamerule disableRaids true"));
        assertEquals("gamerule raids true", fix("gamerule disableRaids false"));
        assertEquals("gamerule raids", fix("gamerule disableRaids"), "a query is left alone");
    }

    @Test
    void newAndUnknownIdsAreLeftAlone() {
        assertEquals("gamerule advance_time false", fix("gamerule advance_time false"));
        assertEquals("gamerule doFireTick false", fix("gamerule doFireTick false"),
                "an integer since 1.21.11, not a rename: the game refuses it, which is the truth");
        assertEquals("say keepInventory is fun", fix("say keepInventory is fun"));
        assertEquals("gamerule max_command_sequence_length 65536",
                fix("gamerule maxCommandChainLength 65536"));
    }

    @Test
    void rulesAddedAfter1214AreRenamedToo() {
        assertEquals("gamerule spawn_monsters false", fix("gamerule spawnMonsters false"));
        assertEquals("gamerule command_blocks_work true", fix("gamerule commandBlocksEnabled true"));
    }

    @Test
    void itRunsInsideTheDialectChain() {
        assertTrue(CommandDialect.correct("gamerule doDaylightCycle false", id -> true).command()
                .contains("advance_time"));
    }
}
