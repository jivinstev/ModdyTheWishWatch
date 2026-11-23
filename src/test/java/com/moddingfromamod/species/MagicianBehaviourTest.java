package com.moddingfromamod.species;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** how a Magician creature's life is settled — kept, a flier lifted off the ground, or chosen when none was named. */
class MagicianBehaviourTest {

    @Test
    void aNamedLifeThatFitsIsKeptWithNoNote() {
        var o = MagicianBehaviour.settle("pack_hunter", false, Set.of("friendly"), "companion", 1.0, false);
        assertEquals("pack_hunter", o.key());
        assertEquals("", o.note());
        assertFalse(o.fallback() || o.corrected());
    }

    @Test
    void aFlierNamedAGroundLifeGetsAFlyingOne() {
        for (String ground : Set.of("pack_hunter", "grazer", "bruiser", "villager", "climber", "archer", "swimmer")) {
            var o = MagicianBehaviour.settle(ground, false, Set.of("flies", "friendly"), "wild", 1.0, false);
            assertTrue(BehaviourChoice.FLYING.contains(o.key()), ground + " → " + o.key());
            assertTrue(o.corrected());
            assertTrue(o.note().contains("flying life"), o.note());
        }
    }

    @Test
    void aFlierHeRidesBecomesAMountAndABigFierceOneADragon() {
        assertEquals("mount", MagicianBehaviour.settle("pack_hunter", false, Set.of("flies", "rideable"), null, 2.0, false).key());
        assertEquals("dragon", MagicianBehaviour.settle("bruiser", false, Set.of("flies", "hostile"), null, 5.0, false).key());
        assertEquals("buzzer", MagicianBehaviour.settle("grazer", false, Set.of("flies", "friendly"), null, 0.5, false).key());
    }

    @Test
    void flyingLivesAreNeverChanged() {
        for (String fly : BehaviourChoice.FLYING) {
            assertEquals(fly, MagicianBehaviour.settle(fly, false, Set.of("flies"), null, 1.0, false).key());
        }
    }

    @Test
    void noneNamedIsChosenNeverRefused() {
        var flier = MagicianBehaviour.settle(null, false, Set.of("flies", "friendly"), null, 1.0, false);
        assertTrue(BehaviourChoice.FLYING.contains(flier.key()), flier.key());
        assertTrue(flier.fallback());
        assertTrue(flier.note().contains("named no behaves_like"), flier.note());

        assertEquals("swimmer", MagicianBehaviour.settle(null, false, Set.of("friendly"), null, 1.0, true).key());
        assertEquals("mount", MagicianBehaviour.settle(null, false, Set.of("rideable", "friendly"), null, 2.0, false).key());
        var ground = MagicianBehaviour.settle(null, false, Set.of("friendly"), null, 1.0, false);
        assertFalse(ground.key().equals("buzzer") || ground.key().equals("dragon"), ground.key());
    }

    @Test
    void anInheritingSculptThatNamesNoneKeepsItsBase() {
        var o = MagicianBehaviour.settle(null, true, Set.of("friendly"), null, 1.0, false);
        assertNull(o.key());
        assertEquals("", o.note());
    }

    @Test
    void tempersAndSizesReadAsTheBuildersDo() {
        assertEquals("wild", MagicianBehaviour.temper(Set.of("hostile"), ""));
        assertEquals("guard", MagicianBehaviour.temper(Set.of("friendly"), "guardian"));
        assertEquals("shy", MagicianBehaviour.temper(Set.of("skittish"), ""));
        assertEquals("friendly", MagicianBehaviour.temper(Set.of("friendly"), "companion"));
        assertEquals(1, MagicianBehaviour.size(0.5));
        assertEquals(2, MagicianBehaviour.size(null));
        assertEquals(3, MagicianBehaviour.size(2.5));
        assertEquals(4, MagicianBehaviour.size(6.0));
    }

    @Test
    void aSeaCreatureNamedALandLifeSwimsButABoatStaysAVehicle() {
        var fish = MagicianBehaviour.settle("grazer", false, Set.of("friendly"), null, 1.0, true);
        assertEquals("swimmer", fish.key());
        assertTrue(fish.corrected() && fish.note().contains("sea creature"), fish.note());
        assertEquals("vehicle", MagicianBehaviour.settle("vehicle", false, Set.of("rideable"), null, 2.0, true).key());
        assertEquals("swimmer", MagicianBehaviour.settle("swimmer", false, Set.of(), null, 1.0, true).key());
        // a flying fish is a flier first
        assertTrue(BehaviourChoice.FLYING.contains(
                MagicianBehaviour.settle("grazer", false, Set.of("flies"), null, 1.0, true).key()));
    }

    /** "make it fly" through modify_mob, with no life named, lifts the creature's life off the ground. */
    @Test
    void makeItFlySettlesAFlyingLife() {
        assertEquals("buzzer", MagicianBehaviour.afterPowers("pack_hunter", false, Set.of("friendly", "flies"), "companion", 1.0, false));
        assertNull(MagicianBehaviour.afterPowers("mount", false, Set.of("friendly", "flies", "rideable"), null, 2.0, false),
                "a mount he rides is a pegasus: its life already flies");
        assertEquals("dragon", MagicianBehaviour.afterPowers("bruiser", false, Set.of("hostile", "flies"), null, 5.0, false));
        assertEquals("mount", MagicianBehaviour.afterPowers("grazer", false, Set.of("friendly", "flies", "rideable"), null, 1.0, false));
        assertTrue(BehaviourChoice.FLYING.contains(
                MagicianBehaviour.afterPowers(null, false, Set.of("friendly", "flies"), null, 1.0, false)), "no life before: a flying one now");
    }

    @Test
    void aPowerChangeThatIsNotFlyingLeavesTheLifeAlone() {
        assertNull(MagicianBehaviour.afterPowers("pack_hunter", false, Set.of("friendly", "glowing"), null, 1.0, false));
        assertNull(MagicianBehaviour.afterPowers("pack_hunter", true, Set.of("friendly", "flies"), null, 1.0, false), "it flew already");
        assertNull(MagicianBehaviour.afterPowers("dragon", false, Set.of("friendly", "flies"), null, 1.0, false), "its life flies");
    }
}
