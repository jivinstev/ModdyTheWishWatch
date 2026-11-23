package com.moddingfromamod.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Ember-only prompt forks: a Muse call asks with {@code X.ember.md}, the Haiku fallback with
 * the original, so tuning for Ember never moves the grader whose floors were measured on Haiku.
 */
class MusePromptsTest {

    @Test
    void aRegisteredForkIsWhatMuseIsAskedWith() {
        MusePrompts.register("BASE prompt for a test", "EMBER prompt for a test");
        assertEquals("EMBER prompt for a test", MusePrompts.forMuse("BASE prompt for a test"));
        assertEquals("another prompt", MusePrompts.forMuse("another prompt"), "no fork: the prompt itself");
        MusePrompts.register("BASE2", "  ");
        assertEquals("BASE2", MusePrompts.forMuse("BASE2"), "a blank fork is ignored");
    }

    @Test
    void theShippedForksLoadBesideTheirOriginals() {
        // Both start as the original's copy; a tuning round edits only the .ember.md (docs/EMBER_GRADERS.md).
        for (String path : new String[] {"/safety/input-grader-prompt.md", "/readymade/nudge-prompt.md"}) {
            String base = MusePrompts.load(MusePromptsTest.class, path);
            String fork = MusePrompts.load(MusePromptsTest.class, path.replace(".md", ".ember.md"));
            assertEquals(false, base.isEmpty(), path);
            assertEquals(fork, MusePrompts.forMuse(base), path + " has its Ember fork registered");
        }
    }
}
