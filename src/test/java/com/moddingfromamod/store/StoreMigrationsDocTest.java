package com.moddingfromamod.store;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * <b>Every versioned record has its row in {@code docs/STORE_MIGRATIONS.md}, at the version the code writes</b>: a
 * migration a downstream census cannot find, or a row left behind at an old version, fails here (Gate A). A new
 * versioned record adds its entry to {@link #CURRENT} and its row in the same change.
 */
class StoreMigrationsDocTest {

    /** The register's first column (its start) → the version the code writes now. */
    static final Map<String, Integer> CURRENT = new LinkedHashMap<>();

    static {
        CURRENT.put("species (record)", com.moddingfromamod.species.Species.RECORD_VERSION);
        CURRENT.put("mob undo record", com.moddingfromamod.mob.MobState.RECORD_VERSION);
    }

    @Test
    void everyVersionedRecordHasItsRowAtTheVersionItWrites() throws IOException {
        String doc = Files.readString(Path.of("docs/STORE_MIGRATIONS.md"));
        for (var e : CURRENT.entrySet()) {
            boolean found = false;
            for (String line : doc.split("\n")) {
                String[] cells = line.split("\\|");
                if (cells.length > 2 && cells[1].strip().startsWith(e.getKey())
                        && cells[2].strip().equals("v" + e.getValue())) {
                    found = true;
                }
            }
            assertTrue(found, "docs/STORE_MIGRATIONS.md has no row for '" + e.getKey() + "' at v" + e.getValue());
        }
    }

    @Test
    void theMobStoreIsNeverWrittenOverByAnOlderBuild() {
        assertTrue(com.moddingfromamod.mob.MobHandleStore.VERSION >= com.moddingfromamod.mob.MobState.RECORD_VERSION,
                "mobs.json's version moves with its records, so an older build's writeBlocker refuses a newer file");
    }
}
