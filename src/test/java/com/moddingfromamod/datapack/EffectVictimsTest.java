package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Who a creature's effect lands on (chunk 2): the words, the default that never catches the
 * child, and the catalogue wiring every victim-picking template through the one rule.
 */
class EffectVictimsTest {

    @Test
    void theDefaultNeverCatchesAPlayer() {
        String d = EffectVictims.clause(null);
        assertTrue(d.contains("type=!minecraft:player"), d);
        assertEquals(d, EffectVictims.clause(""));
        assertEquals(d, EffectVictims.clause("mobs"));
    }

    @Test
    void eachWordSaysWhoItMeans() {
        assertTrue(EffectVictims.clause("players").contains("type=minecraft:player"));
        assertTrue(EffectVictims.clause("me").contains("type=minecraft:player"));
        assertTrue(EffectVictims.clause("enemies").contains("type=" + EffectVictims.MONSTERS_TAG));
        assertTrue(EffectVictims.clause("Hostile").contains("type=" + EffectVictims.MONSTERS_TAG));
        assertTrue(EffectVictims.clause("animals").contains("type=" + EffectVictims.ANIMALS_TAG));
        String all = EffectVictims.clause("everyone");
        assertFalse(all.contains("player"), "everyone includes players: " + all);
        assertTrue(all.contains("type=!minecraft:item"), all);
        assertEquals(",type=minecraft:zombie,tag=!" + EffectVictims.PROXY_TAG,
                EffectVictims.clause("minecraft:zombie"));
    }

    @Test
    void anUnknownWordIsRefusedNotGuessed() {
        assertNull(EffectVictims.clause("bunnies"),
                "a plain creature name is the tool's to resolve against the registry");
        assertFalse(EffectVictims.isWord("zombies"));
        assertTrue(EffectVictims.isWord("monsters"));
    }

    @Test
    void aPositiveTypeNeverSitsBesideAnotherType() {
        // The selector parser refuses `type=x` next to any other `type=` argument, so a positive word
        // must carry no exclusions — the effect would fail to parse and do nothing, silently.
        for (String w : new String[]{"players", "monsters", "animals", "minecraft:cow"}) {
            String c = EffectVictims.clause(w);
            assertEquals(1, c.split("type=", -1).length - 1, w + " -> " + c);
        }
    }

    @Test
    void neverItsOwnHitBoxes() {
        for (String w : new String[]{"everyone", "mobs", "players", "monsters", "minecraft:cow"}) {
            assertTrue(EffectVictims.clause(w).contains("tag=!" + EffectVictims.PROXY_TAG), w);
        }
    }

    @Test
    void everyTemplateThatPicksVictimsTakesTarget() {
        EffectCatalogue cat = EffectCatalogue.load();
        int wired = 0;
        for (EffectCatalogue.Effect e : cat.all()) {
            if (!e.derived().contains("VICTIMS")) {
                continue;
            }
            wired++;
            assertTrue(e.params().contains("TARGET"), e.id() + " derives VICTIMS but offers no TARGET");
            String src = cat.render(e.id(), required(e));
            assertTrue(src.contains("type=!minecraft:player"),
                    e.id() + " with no TARGET must leave players out:\n" + src);
            String forHim = cat.render(e.id(), with(required(e), "TARGET", "players"));
            assertTrue(forHim.contains("type=minecraft:player"), e.id() + ": " + forHim);
        }
        assertEquals(6, wired, "on_hit, on_land, on_step, breath_cone, inflict_nearby and lingering_zone "
                + "pick their own victims; impulse builds its selector itself");
        assertThrows(IllegalArgumentException.class,
                () -> cat.render("on_hit", with(required(cat.get("on_hit")), "TARGET", "bunnies")));
    }

    @Test
    void theCataloguesDefaultIsTheRulesDefault() {
        // The default lives in two places — the catalogue's TARGET default (what render() uses) and
        // EffectVictims.DEFAULT (when no default is declared) — so they are pinned together. A
        // control that changed only one of them once "passed" with the child unprotected.
        for (EffectCatalogue.Effect e : EffectCatalogue.load().all()) {
            if (e.params().contains("TARGET")) {
                assertEquals(EffectVictims.DEFAULT, e.defaults().get("TARGET"), e.id());
            }
        }
    }

    /** The parameters an entry needs with no default, filled with something harmless. */
    private static Map<String, String> required(EffectCatalogue.Effect e) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("TAG", "gt");
        if (e.params().contains("COMMAND")) {
            m.put("COMMAND", "effect give @s minecraft:slowness 1 0");
        }
        for (String d : e.derived()) {
            if (!d.equals("VICTIMS")) {
                m.put(d, "20");   // a derived tick count the tool would fill
            }
        }
        return m;
    }

    private static Map<String, String> with(Map<String, String> m, String k, String v) {
        Map<String, String> out = new java.util.LinkedHashMap<>(m);
        out.put(k, v);
        return out;
    }
}
