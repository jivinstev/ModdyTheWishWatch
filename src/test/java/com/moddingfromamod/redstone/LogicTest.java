package com.moddingfromamod.redstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The logic bridge's conditions, tick by tick. */
class LogicTest {

    static Logic.Seen seen(long tick) {
        return new Logic.Seen(Set.of(), Set.of(), 0, List.of(), false, 0, tick);
    }

    static Logic.Rule rule(String kind, List<String> who, String item, int count, List<Integer> code, int period,
                           int onFor) {
        return new Logic.Rule(kind, who, item, count, code, period, onFor, 4);
    }

    @Test
    void onlyTheNamedPlayerOpensIt() {
        Logic.Rule r = rule("player_near", List.of("Ezra"), null, 0, null, 0, 0);
        Logic.Memory m = new Logic.Memory();
        assertFalse(Logic.step(r, new Logic.Seen(Set.of("Sister"), Set.of(), 0, List.of(), false, 0, 1), m));
        assertTrue(Logic.step(r, new Logic.Seen(Set.of("Sister", "ezra"), Set.of(), 0, List.of(), false, 0, 2), m));
        assertFalse(Logic.step(r, seen(3), m));
    }

    @Test
    void monstersButNotCowsOrPlayers() {
        Logic.Rule r = rule("mob_near", List.of("monster"), null, 0, null, 0, 0);
        Logic.Memory m = new Logic.Memory();
        assertFalse(Logic.step(r, new Logic.Seen(Set.of("Ezra"), Set.of("minecraft:cow"), 0, List.of(), false, 0, 1), m));
        assertTrue(Logic.step(r, new Logic.Seen(Set.of(), Set.of("minecraft:zombie"), 0, List.of(), false, 0, 2), m));
        Logic.Rule creeper = rule("mob_near", List.of("creeper"), null, 0, null, 0, 0);
        assertFalse(Logic.step(creeper, new Logic.Seen(Set.of(), Set.of("minecraft:zombie"), 0, List.of(), false, 0, 3), m));
        assertTrue(Logic.step(creeper, new Logic.Seen(Set.of(), Set.of("minecraft:creeper"), 0, List.of(), false, 0, 4), m));
    }

    @Test
    void payingTakesTheCoinAndHoldsThePower() {
        Logic.Rule r = rule("paid", null, "minecraft:emerald", 1, null, 0, 40);
        Logic.Memory m = new Logic.Memory();
        assertFalse(Logic.step(r, seen(10), m));
        assertEquals(0, m.take);
        assertTrue(Logic.step(r, new Logic.Seen(Set.of(), Set.of(), 3, List.of(), false, 0, 11), m));
        assertEquals(1, m.take);
        assertTrue(Logic.step(r, seen(40), m));       // still on: held for 40 ticks
        assertEquals(0, m.take);                      // and takes nothing more while held
        assertFalse(Logic.step(r, seen(51), m));
    }

    @Test
    void theRightCodeOpensAndAWrongPressStartsAgain() {
        Logic.Rule r = rule("code", null, null, 0, List.of(2, 0, 1), 0, 60);
        Logic.Memory m = new Logic.Memory();
        long t = 0;
        assertFalse(Logic.step(r, press(++t, 2), m));
        assertFalse(Logic.step(r, press(++t, 1), m));        // wrong: starts again
        assertFalse(Logic.step(r, press(++t, 0), m));
        assertFalse(Logic.step(r, press(++t, 2), m));        // a new try begins with 2
        assertFalse(Logic.step(r, press(++t, 0), m));
        assertTrue(Logic.step(r, press(++t, 1), m));         // 2, 0, 1
        assertTrue(Logic.step(r, seen(t + 30), m));
        assertFalse(Logic.step(r, seen(t + 61), m));
    }

    static Logic.Seen press(long tick, int b) {
        return new Logic.Seen(Set.of(), Set.of(), 0, List.of(b), false, 0, tick);
    }

    @Test
    void everyFewSecondsIsOnForItsShareOfThePeriod() {
        Logic.Rule r = rule("every", null, null, 0, null, 100, 20);
        Logic.Memory m = new Logic.Memory();
        int on = 0;
        for (long t = 0; t < 1000; t++) {
            if (Logic.step(r, seen(t), m)) {
                on++;
            }
        }
        assertEquals(200, on);
    }

    @Test
    void aTargetHitHoldsTheDoorOpenForAWhile() {
        Logic.Rule r = rule("target_hit", null, null, 0, null, 0, 60);
        Logic.Memory m = new Logic.Memory();
        assertFalse(Logic.step(r, seen(1), m));
        assertTrue(Logic.step(r, new Logic.Seen(Set.of(), Set.of(), 0, List.of(), false, 9, 2), m));
        assertTrue(Logic.step(r, seen(50), m));
        assertFalse(Logic.step(r, seen(63), m));
    }

    @Test
    void nightAndDay() {
        Logic.Memory m = new Logic.Memory();
        Logic.Seen night = new Logic.Seen(Set.of(), Set.of(), 0, List.of(), true, 0, 1);
        assertTrue(Logic.step(rule("night", null, null, 0, null, 0, 0), night, m));
        assertFalse(Logic.step(rule("day", null, null, 0, null, 0, 0), night, m));
    }
}
