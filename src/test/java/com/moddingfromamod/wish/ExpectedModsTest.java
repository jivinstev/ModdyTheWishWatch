package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Did this run boot what it meant to? —. */
class ExpectedModsTest {

    private static final List<String> BOOTED = List.of("minecraft", "neoforge", "create");

    @Test
    @DisplayName("unset means inert — a real session must never see this")
    void unsetIsInert() {
        assertTrue(ExpectedMods.parse(null).isEmpty());
        assertTrue(ExpectedMods.parse("   ").isEmpty());
        assertEquals("", ExpectedMods.qualifier(Set.of(), BOOTED));
        assertEquals("", ExpectedMods.qualifier(null, BOOTED));
    }

    @Test
    @DisplayName("a complete world says nothing")
    void completeIsSilent() {
        // P6, do not cry wolf: a qualifier that fires on the normal case is one the agent learns
        // to skip, and then the real finding is skipped with it.
        assertEquals("", ExpectedMods.qualifier(Set.of("create"), BOOTED));
        assertEquals("", ExpectedMods.qualifier(Set.of("CREATE", " create "), BOOTED));
    }

    @Test
    @DisplayName("THE McTRAIN: the mod that shaped the design was not booted")
    void theMissingModIsNamed() {
        String q = ExpectedMods.qualifier(Set.of("create", "othermod"), BOOTED);
        assertTrue(q.contains("othermod"), q);
        assertTrue(q.contains("INCOMPLETE"), q);
        // It has to CONTRADICT the sentence it follows. "No mod provides it" and "the mod that
        // provides it did not boot" call for opposite actions.
        assertTrue(q.contains("lookalike is NOT the right answer"), q);
        assertTrue(q.contains("cannot be compared"), q);
        assertFalse(q.contains("create"), "create booted; naming it would be a false alarm: " + q);
    }

    @Test
    @DisplayName("absent() is order-preserving and case-insensitive on both sides")
    void absentIsTidy() {
        assertEquals(List.of("othermod", "cataclysm"),
                ExpectedMods.absent(List.of("othermod", "Create", "cataclysm"),
                        List.of("CREATE")));
        assertEquals(List.of(), ExpectedMods.absent(null, BOOTED));
        assertEquals(List.of("a"), ExpectedMods.absent(List.of("a"), null));
    }

    @Test
    @DisplayName("the property parses the shape the launcher writes")
    void parsesLauncherShape() {
        assertEquals(Set.of("create", "othermod"),
                ExpectedMods.parse("create,othermod"));
        assertEquals(Set.of("create", "othermod"),
                ExpectedMods.parse(" Create , OtherMod , "));
        assertEquals(Set.of("create"), ExpectedMods.parse("create,create"));
    }

    @Test
    @DisplayName("one missing mod reads as one, not as a plural")
    void wordingScalesWithCount() {
        assertTrue(ExpectedMods.qualifier(Set.of("othermod"), BOOTED).contains("that mod"));
        assertTrue(ExpectedMods.qualifier(Set.of("othermod", "cataclysm"), BOOTED)
                .contains("those mods"));
    }
}
