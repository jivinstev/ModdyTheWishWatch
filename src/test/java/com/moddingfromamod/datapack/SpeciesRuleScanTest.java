package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Gate A for reading a standing species rule out of a CBScript program.
 *
 * <p>Two consumers depend on this being right, and they fail differently. The <b>collision check</b>
 * refuses a second rule about a species, so a false positive costs a redirect the agent did not need
 * and a false negative leaves two clocks racing over one selector forever. The <b>report</b> tells
 * the next wish what is already true in his world.
 */
class SpeciesRuleScanTest {

    private static final String BIG_ZOMBIES = """
            clock tick
                as @e[type=zombie, tag=!my_rule] at @s
                    /tag @s add my_rule
                    /attribute @s minecraft:scale base set 2.0
                end
            end
            """;

    @Test
    void itReadsTheSpeciesOutOfATickRule() {
        assertEquals(Set.of("zombie"), SpeciesRuleScan.standingSpeciesIn(BIG_ZOMBIES));
    }

    @Test
    void aNamespaceInARawCommandIsStillTheSameSpecies() {
        // CBScript's own parser rejects a namespace inside a selector, but a raw /execute in the
        // program may carry one — and a scan that missed those would report "no conflict" on a
        // program that has one.
        String s = """
                clock tick
                    /execute as @e[type=minecraft:zombie] run attribute @s minecraft:scale base set 3
                end
                """;
        assertEquals(Set.of("zombie"), SpeciesRuleScan.standingSpeciesIn(s));
    }

    @Test
    void anExclusionIsNotASubject() {
        // "anything but a player" is how half of all programs are written. Reading type=! as a
        // subject would make almost every program collide with almost every other one — and a
        // check that fires constantly gets switched off, and then it protects nothing.
        String s = """
                clock tick
                    as @e[type=!player, type=!item] at @s
                        /damage @s 1
                    end
                end
                """;
        assertTrue(SpeciesRuleScan.standingSpeciesIn(s).isEmpty());
    }

    @Test
    void aOneOffThatMerelyMENTIONSAMobIsNotAStandingRule() {
        // The distinction the collision check turns on. A give-me-a-zombie-egg pack mentions
        // zombies and rules nothing; refusing a later "make zombies bigger" because of it would be
        // the check firing on the wrong thing entirely.
        String s = """
                function give()
                    /summon zombie ~ ~ ~
                    /execute as @e[type=zombie] run attribute @s minecraft:scale base set 2
                end
                """;
        assertTrue(SpeciesRuleScan.standingSpeciesIn(s).isEmpty(),
                "nothing here runs again, so nothing here rules a species");
        assertFalse(SpeciesRuleScan.speciesIn(s).isEmpty(),
                "…though it certainly mentions one");
    }

    @Test
    void itSaysWhatTheRuleDoesInTheChildsWordsRatherThanTheAttributes() {
        var rules = SpeciesRuleScan.rules(BIG_ZOMBIES);
        assertEquals(1, rules.size());
        assertEquals("zombie", rules.get(0).species());
        assertTrue(rules.get(0).describe().startsWith("every zombie:"), rules.get(0).describe());
        assertTrue(rules.get(0).describe().contains("size"), rules.get(0).describe());
    }

    @Test
    void aRuleOverSeveralKindsDoesNotCLAIMWhichChangeBelongsToWhich() {
        // Pairing a change to a species needs the block structure, and a wrong pairing reads
        // exactly as authoritatively as a right one. An honest list beats a confident guess.
        String s = """
                clock tick
                    as @e[type=zombie] at @s
                        /attribute @s minecraft:scale base set 2.0
                    end
                    as @e[type=creeper] at @s
                        /effect give @s minecraft:speed 10 1
                    end
                end
                """;
        var rules = SpeciesRuleScan.rules(s);
        assertEquals(2, rules.size());
        assertTrue(rules.get(0).describe().contains("not read here"), rules.get(0).describe());
    }

    @Test
    void aRuleThatDoesSomethingUnrecognisedStillReportsAsARule() {
        // The list of aspects is a convenience, not a gate. A program doing something we have no
        // word for is still a standing rule and must still collide.
        String s = """
                clock tick
                    as @e[type=creeper] at @s
                        /somebrandnewcommand @s wobble
                    end
                end
                """;
        assertEquals(Set.of("creeper"), SpeciesRuleScan.standingSpeciesIn(s));
        assertEquals(1, SpeciesRuleScan.rules(s).size());
        assertTrue(SpeciesRuleScan.rules(s).get(0).does().contains("every tick"));
    }

    @Test
    void rubbishInputIsNotAnExplosion() {
        assertTrue(SpeciesRuleScan.standingSpeciesIn(null).isEmpty());
        assertTrue(SpeciesRuleScan.standingSpeciesIn("").isEmpty());
        assertTrue(SpeciesRuleScan.rules("clock tick\nend").isEmpty());
        assertTrue(SpeciesRuleScan.speciesIn("type=").isEmpty());
    }

    @Test
    void theRecipeWeHandTheAgentIsReadAsAStandingRuleAboutTheSpeciesItNames() {
        // Closes the loop: modify_mob refuses a category wish and prints this exact program, so if
        // the scan could not read its own advice back, the collision check would never fire on the
        // rules we ourselves told the agent to write.
        for (String species : new String[] {"zombie", "creeper", "wither_skeleton"}) {
            String recipe = com.moddingfromamod.agent.tools.ModifyMobTool.categoryRuleRecipe(species);
            assertEquals(Set.of(species), SpeciesRuleScan.standingSpeciesIn(recipe), recipe);
        }
    }
}
