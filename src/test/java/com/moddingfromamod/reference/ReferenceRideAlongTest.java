package com.moddingfromamod.reference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** idea 1: the researcher's reference for a named character rides in with the wish. */
class ReferenceRideAlongTest {

    private static final ReferenceSpec SPARKIT = new ReferenceSpec(
            List.of(new ReferenceSpec.ColourShare("yellow", 75), new ReferenceSpec.ColourShare("black", 8)),
            List.of("ear", "cheek", "tail"), ReferenceSpec.Size.SMALL, ReferenceSpec.Confidence.HIGH,
            List.of("a page"));

    @AfterEach
    void reset() {
        ReferenceRideAlong.clear();
    }

    @Test
    @DisplayName("a named CHARACTER gets the reference as a note, with its colours and parts")
    void aCharacterRidesAlong() {
        String n = ReferenceRideAlong.decide("w1", "paint a picture of Sparkit",
                w -> Optional.of(new CharacterResolver.Resolved("Sparkit", SubjectKind.CHARACTER)),
                (name, kind) -> SPARKIT).orElseThrow();
        assertTrue(n.startsWith("REFERENCE FOR THIS WISH: Sparkit"), n);
        assertTrue(n.contains("even if what you build is your own stand-in"), n);
        assertTrue(n.contains("yellow"), n);
    }

    @Test
    @DisplayName("no note for NONE, a creature, a person, an empty reference, or when the agent asked itself")
    void otherwiseNothing() {
        assertFalse(ReferenceRideAlong.decide("w", "a troll called Grumbnar", w -> Optional.empty(),
                (a, b) -> SPARKIT).isPresent());
        assertFalse(ReferenceRideAlong.decide("w", "a capybara",
                w -> Optional.of(new CharacterResolver.Resolved("Capybara", SubjectKind.CREATURE)),
                (a, b) -> SPARKIT).isPresent());
        assertFalse(ReferenceRideAlong.decide("w", "a picture of Stampy",
                w -> Optional.of(new CharacterResolver.Resolved("Stampy", SubjectKind.PERSON)),
                (a, b) -> SPARKIT).isPresent());
        assertFalse(ReferenceRideAlong.decide("w", "make me Puffo",
                w -> Optional.of(new CharacterResolver.Resolved("Puffo", SubjectKind.CHARACTER)),
                (a, b) -> ReferenceSpec.none()).isPresent());
        ReferenceRideAlong.agentAsked("w2");
        assertFalse(ReferenceRideAlong.decide("w2", "make me Puffo",
                w -> Optional.of(new CharacterResolver.Resolved("Puffo", SubjectKind.CHARACTER)),
                (a, b) -> SPARKIT).isPresent(), "it already has the answer");
    }

    @Test
    @DisplayName("the A/B arm switches it off")
    void offArm() {
        System.setProperty(ReferenceRideAlong.PROPERTY, "off");
        try {
            java.util.concurrent.atomic.AtomicBoolean called = new java.util.concurrent.atomic.AtomicBoolean();
            ReferenceRideAlong.atHandOver("w3", "make me Puffo", (a, b) -> called.set(true));
            assertFalse(called.get());
        } finally {
            System.clearProperty(ReferenceRideAlong.PROPERTY);
        }
    }
}
