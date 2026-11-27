package com.moddingfromamod.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every place a runtime id or name becomes a file name, held to Windows' rules (store/SafeFileName): found by the
 * platform smoke (an item's {@code id:<name>}) and an audit of every such site. Each test names its site.
 */
class WindowsFileNameSitesTest {

    @Test
    @DisplayName("a datapack id: never a device name, and a ':' is refused, not thrown")
    void datapackIds() {
        assertTrue(com.moddingfromamod.datapack.CbScriptCompiler.validId("ice_bomb"));
        for (String bad : List.of("con", "nul", "aux", "prn", "com1", "lpt9", "my:pack", "Ice", "", "a.b")) {
            assertFalse(com.moddingfromamod.datapack.CbScriptCompiler.validId(bad), bad);
        }
        assertFalse(com.moddingfromamod.datapack.CbScriptCompiler.validId(null));
    }

    @Test
    @DisplayName("a creature's key is never a device name; a fork key keeps its ~")
    void creatureKeys() {
        assertEquals("con_", com.moddingfromamod.runtime.ModelRepository.sanitizeKey("con"));
        assertEquals("aux_", com.moddingfromamod.runtime.ModelRepository.sanitizeKey("AUX"));
        assertEquals("cone", com.moddingfromamod.runtime.ModelRepository.sanitizeKey("cone"));
        assertEquals("pet_dragon", com.moddingfromamod.runtime.ModelRepository.sanitizeKey("pet dragon"));
        for (String k : List.of("con", "nul", "com1", "lpt1", "id:zap", "x".repeat(300))) {
            SafeFileNameTest.assertWindowsSafe(com.moddingfromamod.runtime.ModelRepository.sanitizeKey(k));
            SafeFileNameTest.assertWindowsSafe(com.moddingfromamod.runtime.ModelRepository.sanitizeKey(k) + ".undo-123");
        }
    }
}
