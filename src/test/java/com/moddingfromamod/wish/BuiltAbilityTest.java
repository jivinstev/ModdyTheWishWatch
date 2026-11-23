package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The rule this class exists for: <b>a right build described wrongly is worse than a wrong build
 * described honestly</b>, because the child cannot tell a wrong sentence from a broken toy.
 */
class BuiltAbilityTest {

    @BeforeEach
    void reset() {
        BuiltAbility.clear();
    }

    @Test
    void nothingBuiltMeansNothingSaid() {
        // NOT a friendly default. A child who is handed an instruction, follows it, and sees
        // nothing happen concludes the toy is broken -- which is exactly the failure being fixed,
        // reintroduced by a fallback sentence.
        assertEquals("", BuiltAbility.describe());
    }

    @Test
    void theSentenceComesFromWhatWasBuilt() {
        BuiltAbility.record("on_hit", "It happens when the spider HITS something.", "spider");
        assertTrue(BuiltAbility.describe().contains("HITS"));
        assertFalse(BuiltAbility.describe().toLowerCase().contains("close"),
                "the spider-bite episode built on_hit and told the child to stand close; the "
                        + "sentence must follow the artifact, not the intent");
    }

    @Test
    void twoAbilitiesSharingATriggerAreStatedOnce() {
        // Slowness AND nausea from one wish is a real case; saying the same sentence twice reads
        // as a stutter to a child who is being read this out loud.
        BuiltAbility.record("proximity", "It happens the WHOLE TIME you are close to the queen.", "queen");
        BuiltAbility.record("proximity", "It happens the WHOLE TIME you are close to the queen.", "queen");
        assertEquals(1, BuiltAbility.describe().split("It happens", -1).length - 1);
    }

    @Test
    void twoGenuinelyDifferentTriggersAreBothStated() {
        BuiltAbility.record("on_hit", "It happens when the wolf HITS something.", "wolf");
        BuiltAbility.record("periodic", "It happens over and over, all by itself.", "wolf");
        String s = BuiltAbility.describe();
        assertTrue(s.contains("HITS") && s.contains("over and over"),
                "both are true of the build, and the child needs both: " + s);
    }

    @Test
    void aBlankSentenceIsNotRecorded() {
        BuiltAbility.record("on_hit", "  ", "wolf");
        assertEquals("", BuiltAbility.describe());
        assertTrue(BuiltAbility.current().isEmpty());
    }

    @Test
    void clearingStopsASentenceOutlivingItsWish() {
        BuiltAbility.record("on_hit", "It happens when the wolf HITS something.", "wolf");
        BuiltAbility.clear();
        assertEquals("", BuiltAbility.describe(),
                "a sentence surviving into the NEXT wish would describe an ability that wish never "
                        + "built -- the same lie, one wish later");
    }
}
