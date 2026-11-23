package com.moddingfromamod.agent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.datapack.ItemCatalogue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Who the thing you hit turns on.</b>
 *
 * <p>{@code aggro} is the effect behind <i>"a pearl that makes a noob fight a mob"</i>, and the
 * whole of its content is one choice: does the victim come after <b>him</b>, or go for <b>another
 * creature</b>. Those are opposite toys — one is a chase, one is a brawl you stand back and watch —
 * so {@code FIGHTS} has no default. A default here is a coin flip wearing a decision's clothes.
 *
 * <h2>Why the value is looked up rather than written</h2>
 * The chosen filter is spliced <b>inside a selector's brackets</b>. A stray comma there would not
 * fail loudly — it would become another selector argument, silently changing who gets attacked.
 * That is why {@link MakeItemTool} keeps a closed table, exactly as {@code SUMMON_AS} does, and why
 * this test asserts on the rendered selector rather than only on the refusal.
 */
class AggroFightsTest {

    private static ItemCatalogue.Entry aggro() {
        return ItemCatalogue.load().all().stream()
                .filter(e -> e.id().equals("aggro")).findFirst().orElseThrow();
    }

    private static Map<String, String> args(String fights) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("FIGHTS", fights);
        return m;
    }

    private static String derive(Map<String, String> m) {
        return MakeItemTool.derive(aggro(), "thrown", m);
    }

    @Test
    @DisplayName("'me' and 'mob' are the two answers, and they render differently")
    void bothAnswersRender() {
        Map<String, String> me = args("me");
        assertNull(derive(me), "'me' should be accepted");
        assertEquals("type=minecraft:player", me.get("AGGRESSOR"),
                "'fight ME' must pick the player and nothing else");

        Map<String, String> mob = args("mob");
        assertNull(derive(mob), "'mob' should be accepted");
        String filter = mob.get("AGGRESSOR");
        assertNotNull(filter);
        assertTrue(filter.contains("type=!minecraft:player"),
                "'fight a mob' must not pick the child: " + filter);
        assertTrue(filter.contains("type=!minecraft:item"),
                "a dropped item is not somebody to fight: " + filter);
        assertTrue(filter.contains("type=!minecraft:armor_stand"),
                "the placed shape's own marker is an armour stand — attacking it is attacking the "
                        + "item you just put down: " + filter);
    }

    /** The two build opposite toys, so the wrong one is worse than a refusal. */
    @Test
    @DisplayName("FIGHTS has no default and refuses anything not in the table")
    void nothingIsGuessed() {
        for (String bad : new String[]{null, "", "  ", "everyone", "player", "the zombie", "yes"}) {
            Map<String, String> m = args(bad);
            String err = derive(m);
            assertNotNull(err, "'" + bad + "' was accepted, so something was guessed");
            assertTrue(err.contains("me") && err.contains("mob"),
                    "a refusal must name the two answers: " + err);
            assertNull(m.get("AGGRESSOR"), "a refused call must render nothing");
        }
    }

    /**
     * The injection guard, and the reason it is asserted on the VALUE rather than on the input.
     *
     * <p>Filtering the agent's text would be a filter; this is a lookup, so nothing the agent wrote
     * can reach the selector at all. The test that proves it is not "does a bad string get
     * rejected" — it is "is the rendered value one of the two we wrote".
     */
    @Test
    @DisplayName("nothing the agent types reaches the selector")
    void theValueIsOursNotTheirs() {
        Map<String, String> m = args("mob,nbt={Invulnerable:1b}]  say pwned");
        assertNotNull(derive(m), "an injected value must be refused");

        for (String ok : new String[]{"me", "MOB", " Mob "}) {
            Map<String, String> good = args(ok);
            assertNull(derive(good), "'" + ok + "' should be accepted");
            assertTrue(good.get("AGGRESSOR").equals("type=minecraft:player")
                            || good.get("AGGRESSOR").startsWith("type=!minecraft:item"),
                    "the rendered filter is not one of ours: " + good.get("AGGRESSOR"));
        }
    }

    /** FIGHTS is consumed, or it would be written into the function as a stray placeholder. */
    @Test
    @DisplayName("FIGHTS is spent on AGGRESSOR and does not survive into the template")
    void theRawWordIsConsumed() {
        Map<String, String> m = args("mob");
        assertNull(derive(m));
        assertFalse(m.containsKey("FIGHTS"), "FIGHTS must be removed once it has been rendered");
    }

    /** Playbook 4f: the ask carries the child's own words, so the agent can match on them. */
    @Test
    @DisplayName("the agent is told the choice exists, in the child's words")
    void theAgentIsToldAboutIt() {
        String ask = aggro().paramAsks().get("FIGHTS");
        assertTrue(ask.toLowerCase().contains("fight me")
                        || ask.toLowerCase().contains("chase me"),
                "the ask must carry the child's own words, per playbook 4f: " + ask);
        String d = new MakeItemTool().description();
        assertTrue(d.contains("aggro"), "the agent is never told the aggro effect exists");
    }

    /**
     * <b>The bound a Gate B run had to find.</b>
     *
     * <p>{@code sort=nearest} with no {@code distance} searches the whole loaded world. The damage
     * is then attributed to a creature that may be hundreds of blocks away and cannot walk over, so
     * the fight never starts — and nothing about that is visible: the pearl lands, the particles
     * play, and the child is told it worked. In the test world it picked a creature belonging to a
     * different test.
     *
     * <p>Pinned here rather than left to the GameTest because the GameTest can only catch it when
     * something else happens to be standing nearby, which is luck.
     */
    @Test
    @DisplayName("the search for somebody to fight is bounded")
    void itCannotPickACreatureAcrossTheWorld() {
        String template = com.moddingfromamod.datapack.ItemCatalogue.source("aggro.cbs");
        assertNotNull(template, "aggro.cbs is not on the classpath");
        String damage = template.lines().map(String::strip)
                .filter(l -> l.startsWith("/") && l.contains(" damage "))
                .findFirst().orElseThrow();
        assertTrue(damage.contains("distance=.."),
                "the aggressor is chosen with an unbounded sort=nearest, so it can be a creature "
                        + "on the far side of the world: " + damage.strip());
    }

    /**
     * <b>The second thing a Gate B run had to find.</b>
     *
     * <p>{@code TARGET} is a SET — the thrown shape's is everything within three blocks — so an
     * effect that tags it and then excludes the tag has just removed the whole huddle from the
     * running, including the creature the child meant it to fight. A pearl thrown into a group then
     * reached outside the group for an aggressor, or found nobody and did nothing at all.
     *
     * <p>The fix is to narrow to the one nearest and drop the set tag, and this pins that shape:
     * whatever the damage line excludes must NOT be the tag that was put on the whole set.
     */
    @Test
    @DisplayName("it fights ONE creature, and the rest stay eligible to fight it")
    void theWholeHuddleIsNotWrittenOff() {
        String template = com.moddingfromamod.datapack.ItemCatalogue.source("aggro.cbs");
        assertNotNull(template);
        List<String> lines = template.lines().map(String::strip)
                .filter(l -> l.startsWith("/")).toList();

        String broad = lines.stream().filter(l -> l.contains("add mfam_aggro_noob"))
                .findFirst().orElseThrow();
        assertTrue(broad.contains("${TARGET}"), "the set tag is not applied to TARGET: " + broad);

        assertTrue(lines.stream().anyMatch(l ->
                        l.contains("add mfam_aggro_one") && l.contains("limit=1")
                                && l.contains("sort=nearest")),
                "the set is never narrowed to one creature, so a pearl thrown into a group has no "
                        + "single victim");

        String damage = lines.stream().filter(l -> l.contains(" damage ")).findFirst().orElseThrow();
        assertTrue(damage.contains("tag=!mfam_aggro_one"),
                "the aggressor must be chosen by excluding the ONE victim: " + damage);
        assertFalse(damage.contains("tag=!mfam_aggro_noob"),
                "excluding the whole set makes every creature near the victim ineligible to fight "
                        + "it — which is usually the only creature there is: " + damage);

        // The two `remove` lines are the same text, so index-of-a-value cannot tell them apart —
        // walk the list instead. The order is the whole mechanism: narrow FIRST, then release.
        int narrowed = -1;
        int releasedAfter = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (narrowed < 0 && lines.get(i).contains("add mfam_aggro_one")) {
                narrowed = i;
            } else if (narrowed >= 0 && lines.get(i).contains("remove mfam_aggro_noob")) {
                releasedAfter = i;
                break;
            }
        }
        assertTrue(narrowed >= 0, "nothing narrows the set to one victim");
        assertTrue(releasedAfter > narrowed,
                "the set tag is never released after the one victim is picked out of it, so every "
                        + "creature near the victim stays ineligible to fight it");
    }
}
