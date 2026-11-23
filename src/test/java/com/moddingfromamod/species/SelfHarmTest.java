package com.moddingfromamod.species;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.moddingfromamod.datapack.EffectCatalogue;
import com.moddingfromamod.runtime.Abilities;
import com.moddingfromamod.species.SelfHarm.Hazard;

import org.junit.jupiter.api.Test;

/** what a creature's own ability can do to it, and the immunity that stops it. */
class SelfHarmTest {

    /** The datapack from the production wish, in shape: fire placed at the creature's own feet. */
    static final String SPIKOR_FIRE = """
            dir "spikor_fire"
            desc "Spikor breathes fire"
            clock tick
                as @e[tag=wishmob_spikor] at @s:
                    /particle minecraft:flame ^ ^1 ^1 0.2 0.2 0.2 0.02 20
                    /setblock ~ ~ ~ minecraft:fire keep
            """;

    @Test
    void everyHazardHasSignsAndAnImmunityOfItsOwn() {
        Set<Species.Power> seen = EnumSet.noneOf(Species.Power.class);
        for (Hazard h : Hazard.values()) {
            assertNotNull(SelfHarm.SIGNS.get(h), h + " has no signs, so it could never be found");
            assertFalse(SelfHarm.SIGNS.get(h).isEmpty(), h + " has no signs");
            assertTrue(seen.add(SelfHarm.immunity(h)), h + " shares an immunity with another hazard");
        }
    }

    @Test
    void theProductionFireBreathMakesSpikorFireproof() {
        assertTrue(SelfHarm.hazardsIn(SPIKOR_FIRE).contains(Hazard.FIRE));
        assertEquals(Set.of("wishmob_spikor"), SelfHarm.creatureTags(SPIKOR_FIRE));
        assertEquals(Set.of(Species.Power.FIREPROOF),
                SelfHarm.immunitiesFor(SelfHarm.hazardsIn(SPIKOR_FIRE)));
    }

    @Test
    void eachHazardIsFoundByItsOwnSigns() {
        Map<Hazard, String> lines = Map.of(
                Hazard.FIRE, "/setblock ~ ~ ~ minecraft:lava",
                Hazard.EXPLOSION, "/summon minecraft:tnt ~ ~ ~ {fuse:0}",
                Hazard.POISON, "/effect give @e[distance=..4] minecraft:poison 5 1",
                Hazard.WITHER, "/effect give @e[distance=..4] minecraft:wither 5 1",
                Hazard.LIGHTNING, "/summon minecraft:lightning_bolt ~ ~ ~",
                Hazard.FREEZE, "/setblock ~ ~ ~ minecraft:powder_snow",
                Hazard.FALL, "/effect give @s minecraft:levitation 2 3");
        for (var e : lines.entrySet()) {
            assertTrue(SelfHarm.hazardsIn(e.getValue()).contains(e.getKey()),
                    e.getKey() + " not found in: " + e.getValue());
        }
    }

    @Test
    void harmlessThingsAreNotHazards() {
        assertTrue(SelfHarm.hazardsIn("/effect give @s minecraft:fire_resistance 10 0").isEmpty(),
                "fire RESISTANCE is not fire");
        assertTrue(SelfHarm.hazardsIn("/particle minecraft:heart ~ ~1 ~ 0.3 0.3 0.3 0 5\n"
                + "/effect give @a minecraft:speed 5 1").isEmpty());
        assertTrue(SelfHarm.hazardsIn("").isEmpty());
        assertTrue(SelfHarm.hazardsIn(null).isEmpty());
    }

    @Test
    void onlyTheCreaturesTheDatapackPointsAtAreNamed() {
        String src = "as @e[tag=wishmob_dragon,tag=!wishmob_knight] at @s: /summon tnt";
        assertEquals(Set.of("wishmob_dragon"), SelfHarm.creatureTags(src),
                "a negated tag is the datapack pointing AWAY from that creature");
        assertTrue(SelfHarm.creatureTags("/effect give @a minecraft:poison 5").isEmpty());
    }

    @Test
    void noBuiltInAbilityHurtsItsOwnCreature() {
        for (Abilities.Kind k : Abilities.Kind.values()) {
            assertTrue(SelfHarm.hazardsOf(k).isEmpty(), k + ": see SelfHarm.hazardsOf for why");
        }
    }

    // ── every shipped ready-made ability ─────────────────────────────────────────────────────

    /** A harmless value for each kind of parameter the catalogue has. */
    private static String benign(String param) {
        return switch (param) {
            case "COMMAND" -> "say hello";
            case "EFFECT" -> "minecraft:speed";
            case "BLOCK" -> "minecraft:stone";
            case "PARTICLE" -> "minecraft:heart";
            case "SOUND" -> "minecraft:block.note_block.harp";
            case "MOB" -> "minecraft:sheep";
            case "ALLY_TAG" -> "wishmob_friend";
            case "TRIGGER" -> "jump";
            case "DIRECTION" -> "forward";
            case "FEEL" -> "boost";
            case "TARGET" -> "self";
            case "SOFT_LANDING" -> "true";
            case "VICTIM_SELECTOR" -> "@s";
            case "KEY" -> "k";
            case "MOVE", "LANDING" -> "";
            default -> "2";
        };
    }

    /** A hazardous value for each parameter that can carry one, and the hazard it carries. */
    private static final Map<String, Map.Entry<String, Hazard>> HAZARDOUS = Map.of(
            "COMMAND", Map.entry("damage @s 4 minecraft:in_fire", Hazard.FIRE),
            "EFFECT", Map.entry("minecraft:poison", Hazard.POISON),
            "BLOCK", Map.entry("minecraft:lava", Hazard.FIRE),
            "PARTICLE", Map.entry("minecraft:flame", Hazard.FIRE));

    @Test
    void everyReadyMadeAbilityIsReadWhateverItIsFilledWith() {
        EffectCatalogue cat = EffectCatalogue.load();
        int checked = 0;
        for (EffectCatalogue.Effect e : cat.all()) {
            // Every parameter it takes, the defaulted ones too (EFFECT, PARTICLE, BLOCK carry most
            // of the hazards, and all three have defaults).
            // And the ones the TOOL derives (PERIOD_TICKS, BREATH_TICKS, …), or the template does not
            // render at all.
            Set<String> every = new java.util.TreeSet<>(e.allParams());
            every.addAll(e.defaults().keySet());
            Map<String, String> args = new HashMap<>();
            for (String p : every) {
                args.put(p, benign(p));
            }
            args.put("TAG", "wishmob_subject");
            // No skipping: an ability that cannot be rendered here is an ability this test would
            // silently leave uncovered.
            String plain = cat.render(e.id(), args);
            boolean canCarryHarm = HAZARDOUS.keySet().stream().anyMatch(every::contains)
                    || !SelfHarm.hazardsIn(plain).isEmpty();
            // An ability that can carry harm must name its creature, or it could never be
            // protected. colour_cycle is the one that cannot: it recolours an ordinary mob and
            // takes nothing that burns, blasts or poisons.
            if (canCarryHarm) {
                assertTrue(SelfHarm.creatureTags(plain).contains("wishmob_subject"),
                        e.id() + ": the rendered ability does not name its creature, so it could "
                                + "never be protected");
            }
            for (var h : HAZARDOUS.entrySet()) {
                if (!every.contains(h.getKey())) {
                    continue;
                }
                Map<String, String> bad = new HashMap<>(args);
                bad.put(h.getKey(), h.getValue().getKey());
                String src = cat.render(e.id(), bad);
                assertTrue(SelfHarm.hazardsIn(src).contains(h.getValue().getValue()),
                        e.id() + " filled with " + h.getKey() + "=" + h.getValue().getKey()
                                + " was not read as " + h.getValue().getValue());
                checked++;
            }
        }
        // 15 fillings over all 12 abilities on 2026-09-26: EFFECT ×2, COMMAND ×9, PARTICLE ×3,
        // BLOCK ×1.
        assertTrue(checked >= 15, "the catalogue sweep checked only " + checked + " fillings");
    }

    @Test
    void theNoteSaysWhatWasAddedAndThatTheAgentNeedNotDoIt() {
        String n = SelfHarm.note("spikor", Set.of(Species.Power.FIREPROOF));
        assertTrue(n.contains("fireproof") && n.contains("spikor") && n.contains("do not need"));
        assertEquals("", SelfHarm.note("spikor", Set.of()));
        assertEquals(List.of(), List.of());
    }
}
