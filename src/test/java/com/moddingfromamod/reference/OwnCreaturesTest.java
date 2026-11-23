package com.moddingfromamod.reference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** a name a child's own wished creature carries is his, not a character to look up. */
class OwnCreaturesTest {

    @Test
    void theCreaturesNameOrItsFirstWordsMatch() {
        List<String> names = List.of("Scorch the Dragon", "Mr Wiggles");
        assertTrue(OwnCreatures.matches("Scorch", names), "the live case: the Apprentice's Scorch the Dragon");
        assertTrue(OwnCreatures.matches("scorch the dragon", names));
        assertTrue(OwnCreatures.matches("Mr. Wiggles", names), "punctuation is not a word");
        assertTrue(OwnCreatures.matches("SCORCH", names));
    }

    @Test
    void aWordInsideAnotherNameDoesNotMatch() {
        List<String> names = List.of("Spikor's Helper", "Big Spikor", "Scorchy");
        assertFalse(OwnCreatures.matches("Spikor", names), "a starter's name inside a child's creature's name");
        assertFalse(OwnCreatures.matches("Scorch", names), "a different word");
        assertFalse(OwnCreatures.matches("Scorch the Dragon King", List.of("Scorch the Dragon")),
                "a longer name than the creature's");
    }

    @Test
    void nothingMatchesNothing() {
        assertFalse(OwnCreatures.matches("", List.of("Scorch")));
        assertFalse(OwnCreatures.matches(null, List.of("Scorch")));
        assertFalse(OwnCreatures.matches("Scorch", null));
        assertFalse(OwnCreatures.matches("Scorch", List.of("", "  ")));
    }
}
