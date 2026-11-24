package com.moddingfromamod.world;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a run_command line reaches, as the grammar shows it — and what it cannot see. */
class CommandCoordsTest {

    private static final CommandCoords.Origin AT_100 = new CommandCoords.Origin(100, 200, 0, true);

    private static List<CommandCoords.Area> areas(String c) {
        CommandCoords.Result r = CommandCoords.analyse(c, AT_100);
        assertNull(r.refusal(), c + " was refused: " + r.refusal());
        return r.areas();
    }

    private static boolean reaches(String c, double x, double z) {
        return areas(c).stream().anyMatch(a -> Math.abs(a.x() - x) <= a.radius() + 0.01
                && Math.abs(a.z() - z) <= a.radius() + 0.01);
    }

    @Test
    void absoluteAndRelativePositionsResolveAgainstTheOrigin() {
        assertTrue(reaches("setblock 5000 64 -3 stone", 5000, -3));
        assertTrue(reaches("setblock ~3 ~ ~-4 stone", 103, 196));
        assertTrue(reaches("/fill ~ ~ ~ ~10 ~10 ~10 glass", 110, 210), "the leading slash is ignored");
        assertTrue(reaches("clone 1 2 3 4 5 6 7 8 9", 7, 9), "a clone's destination");
        assertTrue(reaches("clone 1 2 3 4 5 6 7 8 9", 1, 3), "and its source — copying a neighbour's build");
        assertTrue(reaches("summon zombie 900 70 900 {CustomName:'\"a b\"'}", 900, 900),
                "NBT with spaces is one token");
    }

    @Test
    void localCoordinatesReachEveryDirection() {
        List<CommandCoords.Area> a = areas("setblock ^ ^ ^40 stone");
        assertTrue(a.stream().anyMatch(x -> x.radius() >= 40), a.toString());
    }

    @Test
    void theOriginItselfIsAPlaceTheCommandTouches() {
        assertTrue(reaches("summon cow", 100, 200), "summon with no position lands at the origin");
        assertTrue(reaches("give @s diamond", 100, 200));
    }

    @Test
    void executeMovesTheOrigin() {
        assertTrue(reaches("execute positioned 4000 64 4000 run summon cow ~ ~ ~", 4000, 4000));
        assertTrue(reaches("execute positioned 4000 64 4000 run say hi", 4000, 4000));
        assertNotNull(CommandCoords.analyse("execute as @e run kill @s", AT_100).refusal(),
                "as every entity, kill yourself — is every entity on every island");
        assertNotNull(CommandCoords.analyse("execute at @e[type=cow] run setblock ~ ~ ~ tnt", AT_100).refusal());
        assertNull(CommandCoords.analyse("execute at @e[type=cow,distance=..10] run setblock ~ ~ ~ tnt", AT_100)
                .refusal());
        assertNotNull(CommandCoords.analyse("execute in minecraft:the_nether run setblock ~ ~ ~ stone", AT_100)
                .refusal(), "another dimension is not an island");
        assertTrue(reaches("execute store result block 3000 64 3000 Items[0].Count int 1 run say x", 3000, 3000),
                "store into a block is a write");
    }

    @Test
    void entitySelectorsMustBeBounded() {
        assertNotNull(CommandCoords.analyse("kill @e[type=item]", AT_100).refusal());
        assertNotNull(CommandCoords.analyse("kill @e[type=cow,distance=3..]", AT_100).refusal(),
                "at-least-3 is not a bound");
        assertTrue(areas("kill @e[type=cow,distance=..16]").stream().anyMatch(a -> a.radius() >= 16));
        assertTrue(reaches("kill @e[x=500,z=500,dx=10,dz=10]", 505, 505));
    }

    @Test
    void particleAndPlaysoundReadOnlyTheirPlace() {
        // a delta of 0.5 0.5 0.5 is a spread, not the place (0.5, 0.5)
        List<CommandCoords.Area> a = areas("particle flame ~ ~1 ~ 0.5 0.5 0.5 0.1 20");
        assertTrue(a.stream().noneMatch(x -> x.x() == 0.5), a.toString());
    }

    @Test
    void worldWideCommandsAreRefused() {
        for (String c : List.of("time set day", "weather clear", "gamerule advance_time false",
                "difficulty hard", "worldborder set 10", "setworldspawn 0 64 0", "function foo:bar",
                "minecraft:time set night")) {
            assertNotNull(CommandCoords.analyse(c, AT_100).refusal(), c);
        }
    }

    /**
     * granting an advancement or unlocking a recipe skips the game every child on the world
     * plays, so it is a {@code gamerules_difficulty} change — refused where that is denied (a world
     * whose goal list is his to earn) and fine where it is not (a world with no rules).
     */
    @Test
    void advancementsAndRecipesAreTheGameRulesOnAWorldThatDeniesThem() {
        for (String c : List.of("advancement grant @s everything", "recipe give @s *",
                "execute as @s run advancement grant @s only minecraft:story/mine_stone")) {
            assertNotNull(CommandCoords.analyse(c, AT_100).refusal(), c);
            assertNull(CommandCoords.analyse(c, AT_100, h -> true).refusal(), "allowed where not denied: " + c);
        }
        assertEquals(WorldEffect.GAMERULES_DIFFICULTY, WorldEffect.ofCommand("advancement"));
        assertEquals(WorldEffect.GAMERULES_DIFFICULTY, WorldEffect.ofCommand("recipe"));
    }

    @Test
    void anUnknownOriginRefusesRelativePlaces() {
        CommandCoords.Origin none = new CommandCoords.Origin(0, 0, 0, false);
        assertNotNull(CommandCoords.analyse("setblock ~ ~ ~ stone", none).refusal());
    }

    @Test
    void theGapsAreRealAndWrittenDown() {
        // NOT covered, on purpose, and said in CommandCoords' class note: a position inside NBT.
        assertTrue(areas("data modify entity @s Pos set value [9000d,64d,9000d]").stream()
                        .noneMatch(a -> a.x() > 1000),
                "only the origin and @s — the NBT position is not read (documented gap)");
    }

    @Test
    void forceloadAndSpreadplayersAreColumns() {
        assertTrue(reaches("forceload add 2000 2000", 2000, 2000));
        assertTrue(areas("spreadplayers 0 0 5 300 false @s").stream().anyMatch(a -> a.radius() >= 300));
    }

    // ──: what every player's screen shows — refused where base reach is on ───────────────

    private static String refusal(String c) {
        return CommandCoords.analyse(c, AT_100).refusal();
    }

    @Test
    void aGlobalDisplaySlotIsRefused() {
        for (String c : new String[]{"scoreboard objectives setdisplay sidebar coins",
                "scoreboard objectives setdisplay list coins", "scoreboard objectives setdisplay below_name hp",
                "scoreboard objectives setdisplay sidebar.team.red coins", "scoreboard objectives setdisplay sidebar",
                "execute as @s run scoreboard objectives setdisplay sidebar coins"}) {
            String why = refusal(c);
            assertNotNull(why, c);
            assertTrue(why.contains("EVERY player's screen"), why);
        }
    }

    @Test
    void aPlayerInATeamIsRefused() {
        for (String c : new String[]{"team join red @s", "team join red @a", "team join red @p", "team join red",
                "team join red OtherKid", "team join red @e[distance=..10]", "team join red @e[type=player,distance=..5]",
                "team join red @e[tag=pet,distance=..10] @a", "execute as @s run team join red @s"}) {
            String why = refusal(c);
            assertNotNull(why, c);
            assertTrue(why.contains("tab list"), c + " → " + why);
        }
    }

    @Test
    void hisOwnGameScoresBarsAndCreatureTeamsStill() {
        for (String c : new String[]{"scoreboard objectives add coins dummy", "scoreboard players set @s coins 5",
                "scoreboard players add @s coins 1", "bossbar add gt:mine \"my dragon\"",
                "bossbar set gt:mine players @s", "team add blue", "team modify blue color blue",
                "team join blue @e[tag=his_wolf,distance=..20]", "team join blue @e[type=wolf,distance=..20]",
                "team leave @e[tag=his_wolf,distance=..20]"}) {
            assertNull(refusal(c), c);
        }
    }
}
