package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** decision 3: which commands can reach a player once the child who wished has left. */
class PersonSelectorsTest {

    private static String hit(String command) {
        return PersonSelectors.firstPlayerSelector(command);
    }

    @Test
    void thePlayerSelectorsAlwaysCount() {
        assertEquals("@p", hit("give @p minecraft:diamond 3"));
        assertEquals("@a", hit("effect give @a minecraft:speed 30"));
        assertEquals("@r", hit("tp @r 0 80 0"));
        assertEquals("@a[distance=..10]", hit("title @a[distance=..10] title \"hi\""));
    }

    @Test
    void evenInsideAnExecuteAsAMob() {
        assertEquals("@p",
                hit("execute as @e[type=zombie] at @s run give @p minecraft:emerald"));
    }

    @Test
    void anUnrestrictedEntitySelectorCanBeAPlayer() {
        assertEquals("@e[distance=..5]", hit("kill @e[distance=..5]"));
        assertEquals("@e", hit("effect give @e minecraft:glowing 10"));
        assertEquals("@n", hit("tp @n ~ ~5 ~"));
        assertEquals("@e[type=player]", hit("kill @e[type=player]"));
        assertEquals("@e[type=minecraft:player]", hit("kill @e[type=minecraft:player]"));
        assertEquals("@e[type=!zombie]", hit("kill @e[type=!zombie]"));
    }

    @Test
    void aSelectorPinnedToAMobIsFine() {
        assertNull(hit("kill @e[type=zombie,distance=..20]"));
        assertNull(hit("effect give @n[type=minecraft:cow] minecraft:glowing 10"));
        assertNull(hit("kill @e[type=!player]"));
        assertNull(hit("execute as @e[type=zombie] at @s run summon lightning_bolt ~ ~ ~"));
    }

    @Test
    void commandsThatNameNoPlayerRunAsBefore() {
        assertNull(hit("setblock 10 64 10 minecraft:gold_block"));
        assertNull(hit("summon minecraft:zombie ~ ~ ~ {CustomName:'\"Bob\"'}"));
        assertNull(hit("fill ~-2 ~ ~-2 ~2 ~3 ~2 minecraft:oak_planks hollow"));
        assertNull(hit("data merge entity @s {Glowing:1b}"), "@s is not another child");
        assertNull(hit("say email me at bob@parents.example"), "not a selector");
        assertNull(hit(null));
    }

    // ── option 2: the wisher is here, and so is another child ──────────────────────────────

    private static final List<String> BOB = List.of("Bob");

    private static PersonSelectors.Decision n(String command) {
        return PersonSelectors.narrow(command, "Alice", BOB);
    }

    @Test
    void aloneInTheWorldNothingChangesByteForByte() {
        var d = PersonSelectors.narrow("effect give @a minecraft:glowing 30", "Alice", List.of());
        assertTrue(d.unchanged());
        assertEquals("effect give @a minecraft:glowing 30", d.command());
        assertTrue(PersonSelectors.narrow("give @p diamond", "Alice", List.of("alice")).unchanged(),
                "the wisher listed among the others is still alone");
    }

    @Test
    void everyoneBecomesOnlyTheWisher() {
        assertEquals("effect give @a[name=Alice] minecraft:glowing 30",
                n("effect give @a minecraft:glowing 30").command(), "make everyone glow");
        assertEquals("give @p[name=Alice] minecraft:diamond_sword",
                n("give @p minecraft:diamond_sword").command());
        assertEquals("tp @r[distance=..20,name=Alice] 0 80 0",
                n("tp @r[distance=..20] 0 80 0").command());
        assertEquals("execute as @a[name=Alice] at @s run summon lightning_bolt",
                n("execute as @a at @s run summon lightning_bolt").command());
        var d = n("effect give @a minecraft:speed 30");
        assertEquals(PersonSelectors.WHY_EVERYONE, d.why());
        assertEquals(List.of("@a → @a[name=Alice]"), d.narrowed());
    }

    @Test
    void namingAnotherChildIsRefused() {
        assertEquals("Bob", n("give Bob minecraft:diamond_sword").refused(), "a gift for Bob");
        assertEquals("bob", n("attribute bob minecraft:scale base set 0.3").refused(),
                "make Bob tiny — names are not case-sensitive");
        assertEquals("@a[name=Bob]", n("effect give @a[name=Bob] minecraft:slowness 30").refused());
        assertEquals("@e[name=Bob]", n("kill @e[name=Bob]").refused());
        assertEquals(PersonSelectors.WHY_NAMED, n("tp Bob ~ ~ ~").why());
    }

    @Test
    void anEntitySelectorThatCouldBeAChildIsRefused() {
        var d = n("effect give @e[distance=..10] minecraft:levitation 5");
        assertTrue(d.isRefused());
        assertEquals(PersonSelectors.WHY_ANY_ENTITY, d.why());
    }

    /**
     * a positive tag names something a wish tagged — what a taught button aims at
     * ({@code @e[tag=wishmob_giant_trex,…]}) — so it runs, at grant time and at replay alike. A
     * negated tag is "everything but" and still reaches a child.
     */
    @Test
    void aSelectorPinnedToAWishsTagIsFine() {
        var d = n("effect give @e[tag=wishmob_giant_trex,distance=..48] minecraft:jump_boost infinite 4 true");
        assertFalse(d.isRefused(), "a taught button aimed at his T-rex: " + d.refused());
        assertNull(hit("kill @e[tag=gt_props]"));
        assertTrue(n("effect give @e[tag=!wishmob_giant_trex,distance=..10] minecraft:levitation 5").isRefused());
        assertTrue(n("effect give @e[tag=,distance=..10] minecraft:levitation 5").isRefused(), "an empty tag names nothing");
    }

    @Test
    void theSharedWorldIsUntouched() {
        for (String c : List.of("time set night", "weather rain", "summon minecraft:zombie ~ ~ ~",
                "setblock ~ ~ ~ minecraft:gold_block", "kill @e[type=zombie]",
                "effect give @s minecraft:glowing 30", "give @a[name=Alice] minecraft:apple",
                "summon zombie ~ ~ ~ {CustomName:'\"Bob\"'}", "say hi Bob, look up!",
                "tellraw @s {\"text\":\"Bob is here\"}", "kill @e[name=Rex]")) {
            assertTrue(n(c).unchanged(), c);
        }
    }

    /** an earlier issue's second bug: "everyone but me" was rewritten into "only me" — a silent no-op. */
    @Test
    void anExclusionIsRefusedNeverReversedIntoTheWisher() {
        var d = PersonSelectors.narrow("tp @s @a[name=!Alice,limit=1,sort=nearest]", "Alice", List.of("Bob"));
        assertTrue(d.isRefused(), d.toString());
        assertEquals(PersonSelectors.WHY_EXCLUDES, d.why());
    }
}
