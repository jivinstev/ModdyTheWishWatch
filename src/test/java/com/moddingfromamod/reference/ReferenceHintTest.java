package com.moddingfromamod.reference;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceHintTest {

    @Test
    @DisplayName("REAL BUG: candidates must not run across clauses or keep leading verbs")
    void realWishesFromTheCorpus() {
        // Actually produced in a live run, and all three were fetched and cached under keys the
        // agent could never ask for: "Spikormake", "Fun Mealsmake Tralalelo Tralalala",
        // "Make SPIKOR".
        var a = ReferenceHint.candidates("Make SPIKOR, king of the Kroaks! He can breathe FIRE!");
        assertTrue(a.contains("SPIKOR"), a.toString());
        assertFalse(a.stream().anyMatch(c -> c.toLowerCase().startsWith("make")), a.toString());

        var b = ReferenceHint.candidates(
                "make chucko mcburger with Fun Meals! make Tralalelo Tralalala the shark");
        assertFalse(b.stream().anyMatch(c -> c.contains("Mealsmake")), b.toString());
    }

    @Test
    @DisplayName("an invented creature's own name is still a candidate")
    void namedByTheChild() {
        var c = ReferenceHint.candidates("invent me a creature called Zizzle, a spiky orange lizard");
        assertTrue(c.stream().anyMatch(x -> x.contains("Zizzle")), c.toString());
    }

    @Test
    @DisplayName("a wish naming nothing produces nothing")
    void noCandidates() {
        assertTrue(ReferenceHint.candidates("make me a cool monster please").isEmpty());
        assertTrue(ReferenceHint.candidates("").isEmpty());
        assertTrue(ReferenceHint.candidates(null).isEmpty());
    }

    @Test
    @DisplayName("GUARD: the creature lesson must not deny a capability the agent has")
    void lessonMatchesTheToolset() throws Exception {
        // The failure this encodes: the lesson said "you have no web access" long after
        // look_up_character shipped, and adoption fell from 3 of 7 named creatures to 0 of 7. A
        // confident stale instruction is obeyed exactly as readily as a correct one.
        String lesson = Files.readString(
                Path.of("src/main/resources/lessons/creature.md"));
        for (String line : lesson.split("\n")) {
            if (line.contains("no web access") || line.contains("cannot check")) {
                assertTrue(line.contains("used to") || line.contains("once")
                                || line.startsWith("(") || line.contains("became false"),
                        "the lesson states as fact something the toolset contradicts: " + line);
            }
        }
        assertTrue(lesson.contains("look_up_character"),
                "the lesson should tell the agent the lookup exists");
    }
}
