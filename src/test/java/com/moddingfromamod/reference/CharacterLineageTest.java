package com.moddingfromamod.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** a Wish Builder make asks nobody who its creature is; everything else is unchanged. */
class CharacterLineageTest {

    private static final String TEXT = "lineage test: make sparky";

    @AfterEach
    void clear() {
        CharacterLineage.clear();
        CharacterResolver.clear();
    }

    @Test
    void aBuilderMakeLooksNothingUpNotEvenARememberedCharacter() {
        CharacterResolver.putForTest(TEXT, Optional.of(new CharacterResolver.Resolved("Sparky", SubjectKind.CREATURE)));
        CharacterLineage.remember("lineage_sp", "Zorbo");
        assertEquals(Optional.empty(), CharacterLineage.resolve(TEXT, "lineage_sp", true));
        assertEquals(Optional.empty(), CharacterLineage.resolve("", "lineage_sp", true));
    }

    @Test
    void outsideAMakeTheWishWinsThenTheCreatureIsRemembered() {
        CharacterResolver.putForTest(TEXT, Optional.of(new CharacterResolver.Resolved("Sparky", SubjectKind.CREATURE)));
        CharacterLineage.remember("lineage_sp", "Zorbo");
        assertEquals(Optional.of("Sparky"), CharacterLineage.resolve(TEXT, "lineage_sp", false));
        assertEquals(Optional.of("Zorbo"), CharacterLineage.resolve("", "lineage_sp", false));
    }
}
