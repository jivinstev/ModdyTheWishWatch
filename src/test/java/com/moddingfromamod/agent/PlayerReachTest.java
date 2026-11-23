package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * another player is a REFERENCE (a place to go), never a SUBJECT — as an allowlist.
 * The decided table, row by row, for {@code run_command} (the wisher by name) and for a datapack or
 * button (only {@code @s} is self).
 */
class PlayerReachTest {

    private static final PlayerReach.Who ALICE = PlayerReach.Who.wisher("Alice", List.of("Bob"));
    private static final PlayerReach.Who PRESSER = PlayerReach.Who.presser(List.of("Alice", "Bob"));

    private static PlayerReach.Kind run(String c) {
        return PlayerReach.check(c, ALICE).kind();
    }

    private static PlayerReach.Kind pack(String c) {
        return PlayerReach.check(c, PRESSER).kind();
    }

    @Test
    void takeMeToBobIsAVisit() {
        assertEquals(PlayerReach.Kind.VISIT, run("tp @s Bob"));
        assertEquals(PlayerReach.Kind.VISIT, run("tp Alice Bob"));
        assertEquals(PlayerReach.Kind.VISIT, run("teleport alice bob"));
        assertEquals(PlayerReach.Kind.VISIT, run("/tp @a[name=Alice] Bob"));
        assertEquals(PlayerReach.Kind.VISIT, run("execute at Bob run tp @s ~ ~ ~"));
        assertEquals(PlayerReach.Kind.VISIT, run("execute as @s at Bob run teleport @s ~ ~ ~ ~ ~"));
        assertEquals("Bob", PlayerReach.check("tp @s Bob", ALICE).other());
    }

    /** an earlier issue's orb, verbatim: the presser goes to the other child. Legal on a world whose reach is open. */
    @Test
    void theOrbsShapeIsAVisitForThePresser() {
        PlayerReach.Verdict v = PlayerReach.check("execute at SomePlayer run tp @s ~ ~ ~",
                PlayerReach.Who.presser(List.of("SomePlayer")));
        assertEquals(PlayerReach.Kind.VISIT, v.kind());
        assertEquals("SomePlayer", v.other());
        // …and the same with a name nobody online has: a function runs later, for whoever presses it
        assertEquals(PlayerReach.Kind.VISIT, pack("execute at Somebody run tp @s ~ ~ ~"));
        assertEquals(PlayerReach.Kind.VISIT, pack("tp @s Somebody"));
    }

    @Test
    void conditionsThatReadOnlySelfMayGuardAVisit() {
        assertEquals(PlayerReach.Kind.VISIT,
                pack("execute if score @s cooldown matches 0 at Bob run tp @s ~ ~ ~"));
        assertEquals(PlayerReach.Kind.VISIT,
                pack("execute if entity @s[tag=ready] unless block ~ ~-1 ~ air at Bob run tp @s ~ ~ ~"));
        assertEquals(PlayerReach.Kind.REFUSED, pack("execute if entity Bob at Bob run tp @s ~ ~ ~"));
    }

    @Test
    void bringBobToMeIsRefused() {
        assertEquals(PlayerReach.Kind.REFUSED, run("tp Bob Alice"));
        assertEquals(PlayerReach.Kind.REFUSED, run("tp Bob @s"));
        assertEquals(PlayerReach.Kind.REFUSED, run("execute as Bob run tp @s Alice"));
        assertEquals(PlayerReach.Kind.REFUSED, run("tp Bob ~ ~10 ~"));
        assertEquals(PlayerReach.Kind.REFUSED, pack("tp Bob @s"));
    }

    @Test
    void anythingAtOrAroundBobIsRefused() {
        assertEquals(PlayerReach.Kind.REFUSED, run("execute at Bob run summon tnt ~ ~ ~"));
        assertEquals(PlayerReach.Kind.REFUSED, run("execute at Bob run setblock ~ ~ ~ lava"));
        assertEquals(PlayerReach.Kind.REFUSED, run("execute positioned as Bob run tp @s ~ ~ ~"));
        assertEquals(PlayerReach.Kind.REFUSED, run("execute at Bob run tp @s ~ ~5 ~"));
        assertEquals(PlayerReach.Kind.REFUSED, run("execute at Bob run tp @s ~ ~ ~ facing entity Bob"));
        assertEquals(PlayerReach.Kind.REFUSED, pack("execute at Bob run summon lightning_bolt"));
    }

    @Test
    void doingThingsToBobIsRefused() {
        assertEquals(PlayerReach.Kind.REFUSED, run("give Bob minecraft:diamond 3"));
        assertEquals(PlayerReach.Kind.REFUSED, run("clear Bob"));
        assertEquals(PlayerReach.Kind.REFUSED, run("effect give @a[name=Bob] minecraft:poison 10"));
        assertEquals(PlayerReach.Kind.REFUSED, run("gamemode spectator Bob"));
        assertEquals(PlayerReach.Kind.REFUSED, pack("give Somebody minecraft:diamond"));
        assertEquals(PlayerReach.Kind.REFUSED, pack("item replace entity Somebody weapon.mainhand with stick"));
    }

    /**
     * an earlier issue's second bug: "the nearest OTHER player" was narrowed into the wisher herself and ran as
     * a no-op reported ok. It names another player, so it is a visit or a refusal — never rewritten.
     */
    @Test
    void anExclusionIsAnotherPlayerNeverTheWisher() {
        assertEquals(PlayerReach.Kind.VISIT, run("tp @s @p[name=!Alice]"));
        assertEquals(PlayerReach.Kind.VISIT, run("tp @s @a[name=!Alice,limit=1,sort=nearest]"));
        assertEquals(PlayerReach.Kind.VISIT, run("execute at @p[distance=1..] run tp @s ~ ~ ~"));
        assertEquals(PlayerReach.Kind.REFUSED, run("effect give @a[name=!Alice] minecraft:glowing 10"));
        assertEquals(PlayerReach.Kind.REFUSED, run("execute at @r[distance=0.5..] run summon tnt"));
        assertEquals(PlayerReach.Kind.REFUSED, pack("effect give @a[name=!Alice] minecraft:glowing 10"));
    }

    /**
     * Shapes from the agent's REAL datapacks (CbScriptCorpusTest) that name nobody: a creature
     * facing the nearest player, an ability hitting whoever stands near the creature, a creature by
     * its custom name. Each was refused by a first cut of this rule.
     */
    @Test
    void creatureAbilitiesFromTheCorpusAreNotThisRulesBusiness() {
        assertEquals(PlayerReach.Kind.NONE, pack("tp @s ~ ~ ~ facing entity @p eyes"));
        assertEquals(PlayerReach.Kind.NONE, pack("execute at @s facing entity @p feet run tp @s ~ ~ ~ facing entity @p feet"));
        assertEquals(PlayerReach.Kind.NONE, pack("execute as @a[distance=4..8] run function grumbnar_smash:mark2"));
        assertEquals(PlayerReach.Kind.NONE, pack("execute as @e[tag=wishmob_dinko] at @s if entity "
                + "@e[tag=wishmob_spikor,distance=6..60] run function dinko_eggs:chase"));
        assertEquals(PlayerReach.Kind.NONE, pack("execute as @e[name=\"SPIKOR King of the Kroaks\"] run tag @s add bwf"));
        assertEquals(PlayerReach.Kind.NONE, pack("execute at @p rotated ~ 0 run tp @e[type=moddingfromamod:wish_mob,"
                + "limit=1,sort=nearest] ^0 ^0 ^9 facing entity @p"));
    }

    @Test
    void whatNamesNobodyIsNotThisRulesBusiness() {
        assertEquals(PlayerReach.Kind.NONE, run("time set day"));
        assertEquals(PlayerReach.Kind.NONE, run("give @s minecraft:diamond"));
        assertEquals(PlayerReach.Kind.NONE, run("give Alice minecraft:diamond"));
        assertEquals(PlayerReach.Kind.NONE, run("say hi Bob"));
        assertEquals(PlayerReach.Kind.NONE, run("tellraw @s {\"text\":\"Bob says hi\"}"));
        assertEquals(PlayerReach.Kind.NONE, run("effect give @a minecraft:speed 10"));   // narrow's
        assertEquals(PlayerReach.Kind.NONE, run("kill @e[type=zombie,distance=1..]"));
        // the effect catalogue's shapes: a sound for everyone near, a held-item loop, a fake player
        assertEquals(PlayerReach.Kind.NONE, pack("playsound minecraft:block.note_block.flute master @a ~ ~ ~ 1 1"));
        assertEquals(PlayerReach.Kind.NONE, pack("execute as @p[distance=..24] run function ns:give"));
        assertEquals(PlayerReach.Kind.NONE, pack("scoreboard players set Global timer 0"));
        assertEquals(PlayerReach.Kind.NONE, pack("execute as @e[tag=x] at @s run tp @s ~ ~1 ~"));
        assertEquals(PlayerReach.Kind.NONE, pack("tag @s add mfam_locate_player.Bob"));
        assertEquals(PlayerReach.Kind.NONE, pack("tp @s 10 64 -3"));
    }

    @Test
    void theModeDecidesAVisitAndNothingElse() {
        PlayerReach.Verdict visit = PlayerReach.check("tp @s Bob", ALICE);
        PlayerReach.Verdict refused = PlayerReach.check("tp Bob @s", ALICE);
        assertTrue(PlayerReach.allows(visit, PlayerReach.Mode.OPEN));
        assertFalse(PlayerReach.allows(visit, PlayerReach.Mode.OFF));
        assertFalse(PlayerReach.allows(refused, PlayerReach.Mode.OPEN));
        assertTrue(PlayerReach.allows(PlayerReach.Verdict.NONE, PlayerReach.Mode.OFF));
    }

    @Test
    void theRefusalNamesWhatIsAllowedAndWhereVisitingWorks() {
        String subject = PlayerReach.refusal(PlayerReach.check("give Bob dirt", ALICE), "the Visiting World");
        assertTrue(subject.contains("tp @s Bob"), subject);
        assertTrue(subject.contains("never build something else"), subject);
        String off = PlayerReach.refusal(PlayerReach.check("tp @s Bob", ALICE), "the Visiting World");
        assertTrue(off.contains("the Visiting World"), off);
    }
}
