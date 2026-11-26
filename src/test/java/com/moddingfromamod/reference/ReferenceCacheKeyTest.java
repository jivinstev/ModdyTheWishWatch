package com.moddingfromamod.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.moddingfromamod.store.SafeFileName;
import com.moddingfromamod.store.SafeFileNameTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The reference cache names a file after a character's name: held to Windows' rules (store/SafeFileName). */
class ReferenceCacheKeyTest {

    @Test
    @DisplayName("a reference cache key: never empty, never a device name, bounded; an ASCII name keeps its key")
    void referenceCacheKeys() {
        assertEquals("spikor_the_great", ReferenceResearcher.key("Spikor the Great"));
        String a = ReferenceResearcher.key("ピカチュウ");
        String b = ReferenceResearcher.key("リザードン");
        assertFalse(a.isEmpty());
        assertNotEquals(a, b, "two names with no ASCII letters no longer share one cache file");
        for (String n : List.of("Con", "nul", "ピカチュウ", "a very long character name ".repeat(12))) {
            SafeFileNameTest.assertWindowsSafe(ReferenceResearcher.key(n) + ".json");
            assertTrue(ReferenceResearcher.key(n).length() <= SafeFileName.MAX, n);
        }
    }
}
