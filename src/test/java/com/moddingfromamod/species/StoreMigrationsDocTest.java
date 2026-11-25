package com.moddingfromamod.species;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * <b>docs/STORE_MIGRATIONS.md cannot drift</b>: every species record version this build writes (2 up to
 * {@link Species#RECORD_VERSION}) has its row in the register, and every row names a migration class (a backticked
 * {@code package/Class}) that exists in the source.
 */
class StoreMigrationsDocTest {

    private static final Path DOC = Path.of("docs", "STORE_MIGRATIONS.md");

    private static List<String> rows() throws Exception {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(DOC)) {
            if (line.startsWith("| species (record) |")) {
                out.add(line);
            }
        }
        return out;
    }

    @Test
    void everyRecordVersionHasItsRow() throws Exception {
        List<String> rows = rows();
        for (int v = 2; v <= Species.RECORD_VERSION; v++) {
            String want = "| species (record) | v" + v + " |";
            assertTrue(rows.stream().anyMatch(r -> r.startsWith(want)),
                    "docs/STORE_MIGRATIONS.md has no row for species record v" + v + ": a migration with no row is undeclared");
        }
        for (String r : rows) {
            Matcher m = Pattern.compile("^\\| species \\(record\\) \\| v(\\d+) \\|").matcher(r);
            assertTrue(m.find() && Integer.parseInt(m.group(1)) <= Species.RECORD_VERSION,
                    "a row for a record version this build does not write: " + r);
        }
    }

    @Test
    void everyRowNamesAMigrationClassThatExists() throws Exception {
        for (String r : rows()) {
            Matcher m = Pattern.compile("`([a-z]+/[A-Z][A-Za-z]+)`").matcher(r);
            boolean named = false;
            while (m.find()) {
                Path src = Path.of("src/main/java/com/moddingfromamod/" + m.group(1) + ".java");
                if (Files.isRegularFile(src)) {
                    named = true;
                }
            }
            assertTrue(named, "a register row names no migration class that exists: " + r);
        }
    }
}
