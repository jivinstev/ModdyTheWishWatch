package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `kill` is allowed ONLY against something a wish tagged —.
 *
 * <p>The asymmetry this fixes: `summon` was allowed and `kill` was not, so the agent could add to
 * the child's world but never take back. Every failed attempt was permanent. Observed: it summoned
 * a zombie, could not configure it, could not remove it, and tried twice more — the child asked for
 * one and got three, two of them unconfigured, in a wish that reported success.
 *
 * <p><b>The refusing half matters more than the allowing half.</b> A widened allowlist that let the
 * agent empty the world would be a far worse bug than the one being fixed, so most of this file is
 * about what stays refused.
 */
class KillIsTagScopedTest {

    @Test
    @DisplayName("it can clean up its own tagged mistake")
    void taggedIsAllowed() {
        assertTrue(CommandGuard.isAllowed("kill @e[tag=speedzombie]"));
        assertTrue(CommandGuard.isAllowed("kill @e[tag=speedzombie,limit=1]"));
        assertTrue(CommandGuard.isAllowed("kill @e[type=zombie,tag=wishmob_x]"),
                "a tag plus a type is still scoped by the tag");
        assertTrue(CommandGuard.isAllowed("execute at @p run kill @e[tag=wishmob_x]"),
                "the rule must survive being wrapped in execute, or it is trivially bypassed");
    }

    @Test
    @DisplayName("it can NEVER empty the world")
    void unscopedIsRefused() {
        for (String forbidden : new String[] {
                "kill @e",                       // everything, everywhere
                "kill @a",                       // every player
                "kill @p",                       // the child
                "kill @s",
                "kill Dev",                      // the child, by name
                "kill",                          // the child, implicitly
                "kill @e[type=minecraft:cow]",   // his animals — not the agent's to remove
                "kill @e[distance=..10]",
                "kill @e[limit=100]"}) {
            assertFalse(CommandGuard.isAllowed(forbidden), "ALLOWED and must not be: " + forbidden);
        }
    }

    @Test
    @DisplayName("a NEGATIVE tag filter is the opposite of a scope, and is refused")
    void negatedTagIsNotAScope() {
        // `tag=!x` means "everything EXCEPT x" — it contains the characters `tag=` and is the exact
        // inverse of naming your own work. A substring check would have waved this through.
        assertFalse(CommandGuard.isAllowed("kill @e[tag=!keepme]"));
        assertFalse(CommandGuard.isAllowed("kill @e[tag=!wishmob_x,limit=99]"));
        assertFalse(CommandGuard.isAllowed("kill @e[tag=]"), "an empty tag scopes nothing");
    }

    @Test
    @DisplayName("nothing else on the allowlist gained or lost anything")
    void theRestOfThePolicyIsUnchanged() {
        assertTrue(CommandGuard.isAllowed("summon minecraft:zombie ~ ~ ~"));
        assertTrue(CommandGuard.isAllowed("give @p minecraft:diamond 1"));
        assertTrue(CommandGuard.isAllowed("effect give @s minecraft:speed infinite 3 true"));
        // Still forbidden, and unaffected by adding a tag-scoping rule.
        assertFalse(CommandGuard.isAllowed("stop"));
        assertFalse(CommandGuard.isAllowed("op @p"));
        assertFalse(CommandGuard.isAllowed("datapack disable \"file/x.zip\""));
    }
}
