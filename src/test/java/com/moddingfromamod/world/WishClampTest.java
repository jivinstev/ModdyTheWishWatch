package com.moddingfromamod.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 *, Gate A: "short boosts, modest gifts" as code — both sides. What is under the caps runs unchanged; what
 * is over is shortened or shrunk (never refused while anything is left), what cannot be counted is refused with
 * the way that can; his creatures and mobs are never touched; and a world with no clamp rule clamps nothing.
 */
class WishClampTest {

    private static final WishClamp.Limits L = new WishClamp.Limits(300, 1, 3, 5);

    private static WishClamp.Clamped run(String c, WishClamp.Ledger ledger) {
        return WishClamp.command(c, L, ledger, id -> id.contains("ender_pearl") ? 16 : 64);
    }

    private static WishClamp.Clamped run(String c) {
        return run(c, new WishClamp.Ledger());
    }

    @Test
    void noClampRuleClampsNothing() {
        assertNull(WishClamp.of(null));
        assertNull(WishClamp.of(WorldRules.ALLOW_ALL));
    }

    @Test
    void aClampRuleIsCheckedLikeEveryOther() {
        String head = "{\"store\":\"world-rules\",\"v\":1,\"world\":\"closed\",\"rules\":[";
        assertFalse(WorldRules.parse(head + "{\"id\":\"c\",\"type\":\"clamp\",\"params\":{\"powerSeconds\":0,"
                + "\"stacksPerKind\":1,\"kindsPerWish\":3,\"xpLevels\":5}}]}", "closed").ok(), "0 s is not a cap");
        assertFalse(WorldRules.parse(head + "{\"id\":\"c\",\"type\":\"clamp\",\"params\":{\"powerSeconds\":300,"
                + "\"stacksPerKind\":1,\"kindsPerWish\":3,\"xpLevels\":5,\"gems\":2}}]}", "closed").ok(), "an unknown cap");
        String one = "{\"id\":\"c%d\",\"type\":\"clamp\",\"params\":{\"powerSeconds\":300,\"stacksPerKind\":1,"
                + "\"kindsPerWish\":3,\"xpLevels\":5}}";
        assertFalse(WorldRules.parse(head + String.format(one, 1) + "," + String.format(one, 2) + "]}", "closed").ok(),
                "a world has one clamp");
    }

    // ── what SHOULD still work, unchanged ────────────────────────────────────────────────────────────────

    @Test
    void underTheCapsEverythingRunsAsWritten() {
        for (String c : new String[]{"effect give @s minecraft:speed 30 1", "effect give @s speed",
                "give @s oak_log 10", "xp add @s 3 levels", "setblock ~ ~ ~ stone", "summon pig ~ ~ ~",
                "effect clear @s", "attribute @s minecraft:scale get"}) {
            var r = run(c);
            assertFalse(r.refused(), c);
            assertEquals(c, r.command(), "unchanged: " + c);
            assertNull(r.words(), c);
        }
    }

    @Test
    void hisCreaturesAndMobsAreNeverClamped() {
        for (String c : new String[]{"effect give @e[tag=his_pet] minecraft:glowing infinite",
                "effect give @e[type=zombie,distance=..10] slowness 999999 2",
                "attribute @e[tag=his_pet,limit=1] minecraft:scale base set 3",
                "execute as @e[tag=his_pet] run effect give @s glowing infinite"}) {
            var r = run(c);
            assertFalse(r.refused(), c);
            assertEquals(c, r.command(), "his creature is not clamped: " + c);
        }
    }

    // ── what must be CLAMPED or REFUSED ──────────────────────────────────────────────────────────────────

    @Test
    void aForeverOrOverlongEffectOnHimBecomesTheCap() {
        assertEquals("effect give @s minecraft:speed 300 1 true", run("effect give @s minecraft:speed infinite 1 true").command());
        assertEquals("effect give @s speed 300", run("effect give @s speed 100000").command());
        assertEquals("effect give @p resistance 300 4", run("effect give @p resistance infinite 4").command());
        assertEquals("execute as @s at @s run effect give @s speed 300", run("execute as @s at @s run effect give @s speed infinite").command());
        assertEquals("execute as @a run effect give @s speed 300", run("execute as @a run effect give @s speed infinite").command(),
                "execute as a player makes @s a player");
        assertNotNull(run("effect give @s speed infinite").words());
    }

    @Test
    void aPermanentAttributeOnHimIsRefusedWithTheFix() {
        var r = run("attribute @s minecraft:max_health base set 100");
        assertTrue(r.refused() && r.refusal().contains("modify_player with seconds"), String.valueOf(r.refusal()));
        assertTrue(run("attribute @p scale base reset").refused());
    }

    @Test
    void aBigGiveIsOneStackAndTenGivesAddUpToNoMore() {
        assertEquals("give @s diamond 64", run("give @s diamond 640").command());
        assertEquals("give @s minecraft:ender_pearl 16", run("give @s minecraft:ender_pearl 99").command(), "a stack is the item's own");
        WishClamp.Ledger l = new WishClamp.Ledger();
        int total = 0;
        for (int i = 0; i < 10; i++) {
            var r = run("give @s oak_log 10", l);
            if (!r.refused()) {
                String[] p = r.command().split(" ");
                total += Integer.parseInt(p[p.length - 1]);
            }
        }
        assertEquals(64, total, "ten gives of 10 in one wish total one stack");
        assertTrue(run("give @s minecraft:oak_log 1", l).refused(), "…a different spelling is the same kind, and it is spent");
    }

    @Test
    void aWishGivesAtMostThreeKinds() {
        WishClamp.Ledger l = new WishClamp.Ledger();
        assertFalse(run("give @s apple 1", l).refused());
        assertFalse(run("give @s bread 1", l).refused());
        assertFalse(run("item replace entity @s weapon.mainhand with stone_sword 1", l).refused());
        var fourth = run("give @s diamond 1", l);
        assertTrue(fourth.refused() && fourth.refusal().contains("3 kinds"), String.valueOf(fourth.refusal()));
        assertFalse(run("give @s apple 2", l).refused(), "more of a kind it already gave is fine, to a stack");
    }

    @Test
    void xpIsAtMostFiveLevelsAWish() {
        WishClamp.Ledger l = new WishClamp.Ledger();
        assertEquals("xp add @s 5 levels", run("xp add @s 1000 levels", l).command());
        assertTrue(run("experience add @s 1 levels", l).refused(), "…and nothing more in the same wish");
        assertEquals("xp add @s 5 levels", run("xp add @s 100000 points").command(), "points count as the levels they make");
        assertEquals(3, WishClamp.levelsFromPoints(27));
        assertEquals(5, WishClamp.levelsFromPoints(55));
    }

    @Test
    void whatCannotBeCountedIsRefusedWithTheWayThatCan() {
        var loot = run("loot give @s loot minecraft:chests/end_city_treasure");
        assertTrue(loot.refused() && loot.refusal().contains("give_items"), String.valueOf(loot.refusal()));
        assertTrue(run("loot replace entity @s hotbar.0 loot minecraft:chests/simple_dungeon").refused());
        var item = run("summon item ~ ~ ~ {Item:{id:\"minecraft:diamond\",count:64}}");
        assertTrue(item.refused() && item.refusal().contains("give_items"), String.valueOf(item.refusal()));
        assertTrue(run("summon minecraft:experience_orb ~ ~ ~ {Value:10000}").refused());
        assertFalse(run("loot spawn ~ ~ ~ loot minecraft:entities/zombie").refused(), "loot on the ground near him is not a gift to him");
    }

    @Test
    void theWordsAreAChildsWords() {
        assertEquals("On this island powers are short boosts, so this one lasts 5 minutes.", WishClamp.powerWords(L));
        assertEquals("30 seconds", WishClamp.duration(30));
    }
}
