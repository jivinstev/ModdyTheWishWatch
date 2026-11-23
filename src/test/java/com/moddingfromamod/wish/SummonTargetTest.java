package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where a summon is going to put something —.
 *
 * <p>The cases are the real command from the issue and the forms around it. The two that matter
 * are the negatives: a {@code ~} summon must be left alone (it lands on the child, in a chunk that
 * is loaded by definition), and anything that is not a summon must not be parsed as one.
 */
class SummonTargetTest {

    @Test
    @DisplayName("the command from the issue, verbatim")
    void theRealCommand() {
        var at = SummonTarget.of(
                "summon minecraft:zombie 60 65 -37 {Tags:[\"speedzombie\"],NoAI:1b}");
        assertTrue(at.isPresent(), "the coordinates in the failing command were not read");
        assertEquals(new SummonTarget.At(60, 65, -37), at.get());
    }

    @Test
    @DisplayName("a leading slash is still a summon")
    void leadingSlash() {
        assertEquals(new SummonTarget.At(1, 2, 3),
                SummonTarget.of("/summon minecraft:pig 1 2 3").orElseThrow());
    }

    @Test
    @DisplayName("relative and local coordinates are left alone")
    void relativeIsNotOurs() {
        // These resolve against the child, who is standing in a loaded chunk, so there is nothing
        // to load. Parsing them would mean loading a chunk we picked by arithmetic.
        for (String c : new String[]{
                "summon minecraft:zombie ~ ~ ~",
                "summon minecraft:zombie ~10 ~ ~-5 {Tags:[\"x\"]}",
                "summon minecraft:zombie ^ ^ ^3"}) {
            assertTrue(SummonTarget.of(c).isEmpty(), c);
        }
    }

    @Test
    @DisplayName("a summon with no coordinates lands on the source and is left alone")
    void noCoordinates() {
        assertTrue(SummonTarget.of("summon minecraft:zombie").isEmpty());
    }

    @Test
    @DisplayName("only a summon — a selector command that mentions one is not a summon")
    void notASummon() {
        for (String c : new String[]{
                null, "", "   ",
                "attribute @e[tag=speedzombie,limit=1] minecraft:movement_speed base set 0.7",
                "say summon minecraft:zombie 1 2 3",
                "data get entity @e[tag=x,limit=1] Tags"}) {
            assertTrue(SummonTarget.of(c).isEmpty(), String.valueOf(c));
        }
    }

    @Test
    @DisplayName("fractional coordinates FLOOR, because -0.5 is in block -1")
    void fractionsFloor() {
        // Truncation puts -0.5 in block 0, which is the wrong chunk at a boundary -- and a chunk
        // boundary is the only place this is ever consulted.
        assertEquals(new SummonTarget.At(-1, 64, -1),
                SummonTarget.of("summon minecraft:zombie -0.5 64.9 -0.1").orElseThrow());
    }

    @Test
    @DisplayName("a malformed coordinate is not guessed at")
    void malformedIsAbsent() {
        assertTrue(SummonTarget.of("summon minecraft:zombie x y z").isEmpty());
    }
}
