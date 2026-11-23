package com.moddingfromamod.readymade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import com.moddingfromamod.datapack.EffectCatalogue;
import com.moddingfromamod.datapack.ItemCatalogue;
import com.moddingfromamod.runtime.Abilities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** the index is generated from what ships, so it cannot offer what the tools refuse. */
class ReadyMadeSourcesTest {

    private static Set<String> ids(ReadyMade.Kind k) {
        Set<String> out = new LinkedHashSet<>();
        new ReadyMadeIndex(ReadyMadeSources.shipped()).of(EnumSet.of(k)).forEach(e -> out.add(e.id()));
        return out;
    }

    @Test
    @DisplayName("every catalogue effect and item effect is offered, and nothing else of those kinds")
    void catalogues() {
        Set<String> items = new LinkedHashSet<>();
        ItemCatalogue.load().all().forEach(e -> items.add(e.id()));
        assertEquals(items, ids(ReadyMade.Kind.ITEM));
        Set<String> effects = new LinkedHashSet<>();
        EffectCatalogue.load().all().forEach(e -> effects.add(e.id()));
        if (ReadyMadeSources.ABILITIES_RUN_ON_THE_RIG) {
            for (Abilities.Kind k : Abilities.Kind.values()) {
                effects.add(k.name().toLowerCase(Locale.ROOT));
            }
        }
        assertEquals(effects, ids(ReadyMade.Kind.EFFECT));
    }

    @Test
    @DisplayName("every sculpt ability has a line (a new move cannot ship unoffered)")
    void abilities() {
        Set<String> kinds = new LinkedHashSet<>();
        for (Abilities.Kind k : Abilities.Kind.values()) {
            kinds.add(k.name().toLowerCase(Locale.ROOT));
        }
        assertEquals(kinds, ReadyMadeSources.ABILITIES.keySet());
    }

    @Test
    @DisplayName("a sculpt ability is not offered while it does nothing on the rig (#1677)")
    void deadAbilitiesAreNotOffered() {
        Set<String> c = ids(ReadyMade.Kind.EFFECT);
        for (String a : ReadyMadeSources.ABILITIES.keySet()) {
            assertEquals(ReadyMadeSources.ABILITIES_RUN_ON_THE_RIG, c.contains(a),
                    a + " offered=" + c.contains(a) + " but ABILITIES_RUN_ON_THE_RIG="
                            + ReadyMadeSources.ABILITIES_RUN_ON_THE_RIG);
        }
    }

    @Test
    @DisplayName("the starter creatures are offered, each with the call that starts from it")
    void starters() {
        Set<String> c = ids(ReadyMade.Kind.CREATURE);
        assertTrue(c.contains("pet_dragon") && c.contains("cat"), c.toString());
        new ReadyMadeIndex(ReadyMadeSources.shipped()).of(EnumSet.of(ReadyMade.Kind.CREATURE))
                .forEach(e -> assertTrue(e.use().startsWith("sculpt_creature start_from:\"" + e.id() + "\""), e.use()));
    }

    @Test
    @DisplayName("a starter button is described by its wish, never a bare id")
    void buttonsHaveWords() {
        for (ReadyMade e : ReadyMadeSources.starterButtons()) {
            assertTrue(!e.purpose().equals("button: " + e.id()) && e.purpose().length() > 12, e.purpose());
        }
    }

    @Test
    @DisplayName("reusing one of HIS things names both calls: a NEW one like it, and a change to his")
    void priorUseSpellsOutNewVersusChange() {
        String c = ReadyMadeSources.priorUse("creature", "robo_pup");
        assertTrue(c.contains("a NEW one like it: sculpt_creature id:<a new id> start_from:\"robo_pup\""), c);
        assertTrue(c.contains("his stays as it is") && c.contains("to CHANGE his"), c);
        String i = ReadyMadeSources.priorUse("item", "laser_bow");
        assertTrue(i.contains("start_from:\"laser_bow\" as_new:true") && i.contains("to CHANGE his"), i);
        String b = ReadyMadeSources.priorUse("building", "sky_castle");
        assertTrue(b.contains("build_structure id:<a new id> start_from:\"sky_castle\""), b);
        assertTrue(b.contains("id:\"sky_castle\" start_from:\"sky_castle\""), b);
        assertEquals("inspect it, then change that one", ReadyMadeSources.priorUse("rule", "x"));
    }
}
