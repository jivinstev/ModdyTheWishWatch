package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Gate A for the security boundary. Once an AI agent can propose commands, this class is what stands
 * between a wish and the server, so it's tested against a hostile corpus rather than happy paths.
 */
class CommandGuardTest {

    @Test
    void allowsTheFunCommands() {
        for (String cmd : new String[]{
                "summon minecraft:chicken ~ ~10 ~",
                "give @p minecraft:diamond 5",
                "effect give @p minecraft:speed 30 2",
                "weather clear",
                "time set day",
                "particle minecraft:heart ~ ~1 ~ 1 1 1 0 20",
                "playsound minecraft:entity.generic.explode master @p",
                "setblock ~ ~ ~ minecraft:gold_block"}) {
            assertTrue(CommandGuard.isAllowed(cmd), "should allow: " + cmd);
        }
    }

    @Test
    void refusesServerAndAccountControl() {
        // The whole nasty corpus must be denied — not "most of it".
        for (String cmd : new String[]{
                "stop", "op @a", "deop @a", "ban Steve", "kick @a", "whitelist off",
                "save-off", "publish", "debug start", "perf start", "datapack disable \"file/x\"",
                "reload", "setidletimeout 0", "forceload add ~ ~", "worldborder set 1", "seed"}) {
            assertFalse(CommandGuard.isAllowed(cmd), "should REFUSE: " + cmd);
        }
    }

    @Test
    void recursesThroughExecuteRun() {
        // The classic bypass: the first token is the innocuous `execute`, the payload hides after `run`.
        assertFalse(CommandGuard.isAllowed("execute as @a run stop"),
                "execute ... run stop must be refused");
        assertFalse(CommandGuard.isAllowed("execute at @p run op @a"));
        assertFalse(CommandGuard.isAllowed(
                "execute as @a at @s run execute as @e run datapack disable \"x\""),
                "nested execute chains must still be refused");
        // ...while a legitimate execute still works.
        assertTrue(CommandGuard.isAllowed("execute at @p run summon minecraft:pig ~ ~ ~"));
        assertTrue(CommandGuard.isAllowed("execute as @a at @s run particle minecraft:flame ~ ~ ~ 0 0 0 0 5"));
    }

    @Test
    void refusesSmuggledSecondCommands() {
        // A newline could turn one "command" into two.
        assertFalse(CommandGuard.isAllowed("say hi\nstop"));
        assertFalse(CommandGuard.isAllowed("say hi\r\nop @a"));
        // A leading slash isn't what performPrefixedCommand expects, and hints at unsanitized input.
        assertFalse(CommandGuard.isAllowed("/summon minecraft:pig"));
    }

    @Test
    void refusesJunkAndUnknownCommands() {
        assertFalse(CommandGuard.isAllowed(null));
        assertFalse(CommandGuard.isAllowed(""));
        assertFalse(CommandGuard.isAllowed("   "));
        assertFalse(CommandGuard.isAllowed("definitelynotarealcommand foo"));
    }

    @Test
    void doesNotRecurseForever() {
        // A pathological chain must terminate (and be refused), not blow the stack.
        StringBuilder sb = new StringBuilder("execute as @a run ".repeat(200));
        sb.append("summon minecraft:pig ~ ~ ~");
        assertFalse(CommandGuard.isAllowed(sb.toString()),
                "beyond the depth limit we refuse rather than recurse");
    }

    /**
     * {@code wishmob} is narrowed to {@code spawn} (the audit found this enforced and driven by
     * nothing). The other branches reload, repaint and export models — nothing a wish template has
     * any business doing — and the narrowing must hold through {@code execute … run} too.
     */
    @Test
    void wishmobIsNarrowedToSpawn() {
        assertTrue(CommandGuard.isAllowed("wishmob spawn spikor"), "replaying a saved creature");
        assertTrue(CommandGuard.isAllowed("execute at @p run wishmob spawn spikor"));
        for (String cmd : new String[]{"wishmob reload", "wishmob persist spikor",
                "wishmob paint spikor", "wishmob", "execute at @p run wishmob reload"}) {
            assertFalse(CommandGuard.isAllowed(cmd), "must refuse: " + cmd);
        }
    }

    /**
     * E4: a rule or an item may STAGE a kept event design and nothing else. Defining a design,
     * scheduling one or undoing another's are the agent's tools, never something a command does.
     */
    @Test
    void moddyeventIsNarrowedToStage() {
        assertTrue(CommandGuard.isAllowed("moddyevent stage fort_blast"));
        assertTrue(CommandGuard.isAllowed("execute as @e[tag=boss] at @s run moddyevent stage fort_blast"));
        for (String cmd : new String[]{"moddyevent define {}", "moddyevent every 1 quake",
                "moddyevent undo fort_blast_1", "moddyevent stop quake", "moddyevent list", "moddyevent",
                "execute at @p run moddyevent define {}"}) {
            assertFalse(CommandGuard.isAllowed(cmd), "must refuse: " + cmd);
        }
    }

    /** "make it daytime forever" is one command, and only the two rules that wish needs. */
    @Test
    void allowsStoppingTheSunAndTheRainAndNoOtherRule() {
        assertTrue(CommandGuard.isAllowed("gamerule doDaylightCycle false"));
        assertTrue(CommandGuard.isAllowed("gamerule doDaylightCycle true"));
        assertTrue(CommandGuard.isAllowed("gamerule doWeatherCycle false"));
        assertTrue(CommandGuard.isAllowed("minecraft:gamerule doDaylightCycle false"));
        assertTrue(CommandGuard.isAllowed("execute as @p run gamerule doDaylightCycle false"));

        assertFalse(CommandGuard.isAllowed("gamerule keepInventory true"));
        assertFalse(CommandGuard.isAllowed("gamerule mobGriefing false"));
        assertFalse(CommandGuard.isAllowed("gamerule sendCommandFeedback false"));
        assertFalse(CommandGuard.isAllowed("gamerule randomTickSpeed 1000"));
        assertFalse(CommandGuard.isAllowed("gamerule doDaylightCycle"),
                "a bare query is not a wish");
        assertFalse(CommandGuard.isAllowed("gamerule doDaylightCycle 0"));
        assertFalse(CommandGuard.isAllowed("gamerule doDaylightCycle false extra"));
    }

    @Test
    void wishspotRunsOnlyWhatAWishCouldRunAnyway() {
        // a command on a building's named spot — its tail is checked like execute's.
        assertTrue(CommandGuard.isAllowed("wishspot castle:roof run summon minecraft:ender_dragon ~ ~ ~ {NoAI:1b}"));
        assertTrue(CommandGuard.isAllowed("wishspot shop:counter run setblock ~ ~ ~ minecraft:chest"));
        assertFalse(CommandGuard.isAllowed("wishspot castle:roof run stop"), "the tail is guarded");
        assertFalse(CommandGuard.isAllowed("wishspot castle:roof"), "a spot with nothing to run");
        assertFalse(CommandGuard.isAllowed("wishspot castle:roof run wishspot castle:roof run "
                + "wishspot a:b run wishspot a:b run wishspot a:b run wishspot a:b run wishspot a:b run "
                + "wishspot a:b run wishspot a:b run say hi"), "bounded like execute");
    }

    // ──: the reviewed rows ────────────────────────────────────────────────────────────────

    @Test
    void theCommandsReviewedOpenAreAllowed() {
        for (String cmd : new String[]{
                "fillbiome ~-8 ~-4 ~-8 ~8 ~4 ~8 minecraft:snowy_plains",
                "rotate @e[tag=statue,limit=1] facing entity @p",
                "clear @s minecraft:dirt",
                "recipe give @s *",
                "advancement grant @s everything",
                "spectate @e[tag=my_dragon,limit=1] @s",
                "bossbar add dragon_hp \"Dragon\"",
                "team add red",
                "team modify red color red",
                "team join red @e[tag=red_side]",
                "schedule function wish_meteor:start 300s",
                "trigger vote set 1",
                "random roll 1..6",
                "list",
                "spreadplayers ~ ~ 50 5000 false @s",
                "execute at @p run team join red @s"}) {
            assertTrue(CommandGuard.isAllowed(cmd), "should allow: " + cmd);
        }
    }

    @Test
    void theirLimitsHold() {
        // Revoking his real progress is not undoable; our own triggers (machine handles) are.
        assertFalse(CommandGuard.isAllowed("advancement revoke @s everything"));
        assertFalse(CommandGuard.isAllowed("advancement revoke @s only minecraft:story/mine_stone"));
        assertTrue(CommandGuard.isAllowed("advancement revoke @s only wish_slushie:pulled_the_handle"));
        // A spread across the whole world loads chunks at its edge.
        assertFalse(CommandGuard.isAllowed("spreadplayers 0 0 10 29999984 false @s"));
        assertFalse(CommandGuard.isAllowed("spreadplayers 0 0 10 far false @s"));
        assertTrue(CommandGuard.isAllowed("spreadplayers 0 0 10 " + CommandGuard.MAX_SPREAD + " false @s"));
    }

    @Test
    void theCommandsReviewedClosedStayClosedWithAReason() {
        for (String cmd : new String[]{"tick freeze", "tick rate 5", "defaultgamemode creative",
                "transfer play.example.com", "pardon-ip 1.2.3.4", "msg Bob hi", "tell Bob hi", "me waves",
                "teammsg hi", "moddyop drain", "neoforge tps", "wish a dragon", "execute as @s run tick freeze",
                // D2: the shared world's difficulty and spawn, and the game mode.
                "gamemode creative @s", "difficulty peaceful", "setworldspawn ~ ~ ~"}) {
            assertFalse(CommandGuard.isAllowed(cmd), "should REFUSE: " + cmd);
            String why = CommandGuard.whyRefused(cmd);
            assertTrue(why != null && why.contains("never"), "a refusal says why: " + cmd + " -> " + why);
        }
    }

    /**
     * {@code locate} is ANSWERED by run_command, never dispatched — vanilla's search runs on the
     * server thread. So the guard refuses it everywhere (a template, a rule, a replay, an execute
     * tail) and only run_command's own top-level shape is recognised as one to answer.
     */
    @Test
    void locateIsAnsweredNeverRun() {
        assertTrue(CommandGuard.answeredNotRun("locate biome minecraft:plains"));
        assertTrue(CommandGuard.answeredNotRun("locate structure #minecraft:village"));
        assertTrue(CommandGuard.answeredNotRun("minecraft:locate biome #minecraft:is_jungle"));
        assertFalse(CommandGuard.isAllowed("locate biome minecraft:plains"), "never dispatched");
        assertFalse(CommandGuard.answeredNotRun("locate poi minecraft:home"), "no wish needs a poi search");
        assertFalse(CommandGuard.answeredNotRun("locate biome"), "it needs an id");
        assertFalse(CommandGuard.answeredNotRun("execute at @p run locate biome minecraft:plains"));
        assertFalse(CommandGuard.isAllowed("execute at @p run locate biome minecraft:plains"),
                "an execute tail would reach vanilla's search");
        assertFalse(CommandGuard.answeredNotRun("summon minecraft:pig"));
    }

    @Test
    void everyRowSaysWhyAndNoRootIsListedTwice() {
        // Two rows for one root throw at class load, so reaching here proves there are none.
        assertTrue(CommandGuard.rules().size() > 80, "the table covers the whole command set");
        for (CommandGuard.Rule r : CommandGuard.rules().values()) {
            assertFalse(r.why().isBlank(), "/" + r.root() + " has no reason");
            if (r.tier() == CommandGuard.Tier.LIMITED) {
                assertFalse(r.limit() == CommandGuard.Limit.NONE, "/" + r.root() + " is LIMITED by nothing");
            } else {
                assertTrue(r.limit() == CommandGuard.Limit.NONE, "/" + r.root() + " has a limit but no LIMITED tier");
            }
            if (r.tier() == CommandGuard.Tier.DENY) {
                assertFalse(CommandGuard.isAllowed(r.root()), "a DENY row ran: " + r.root());
            }
        }
    }

    @Test
    void theTextSweepReadsItsVerbsOffTheTable() {
        assertTrue(CommandGuard.textVerbs().containsAll(java.util.Set.of("say", "tellraw", "title", "bossbar", "team")));
        for (String v : CommandGuard.textVerbs()) {
            assertTrue(CommandGuard.allowedRoots().contains(v), "text from a refused command is never shown: " + v);
        }
    }
}
