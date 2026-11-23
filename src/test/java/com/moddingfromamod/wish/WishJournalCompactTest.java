package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Journal compaction keeps the newest LINES byte for byte. It used to re-serialise every
 * parsed wish, which dropped any field a newer Moddy had added and any line this build could not
 * parse: the one place the append-only journal rewrote history it could not read.
 */
class WishJournalCompactTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("a newer line keeps its unknown fields and an unparseable line survives compaction")
    void compactionKeepsRawLines() throws Exception {
        Path f = tmp.resolve("journal.jsonl");
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 2_100; i++) {
            lines.add("{\"id\":\"00000000-0000-0000-0000-" + String.format("%012d", i)
                    + "\",\"text\":\"wish " + i + "\"}");
        }
        String newer = "{\"id\":\"00000000-0000-0000-0000-999999999999\",\"text\":\"from a newer "
                + "Moddy\",\"aFieldThisBuildHasNeverHeardOf\":{\"kept\":true}}";
        String strange = "this line is not JSON at all";
        lines.add(newer);
        lines.add(strange);
        Files.write(f, lines);

        new WishJournal(f).compactIfLarge();

        List<String> after = Files.readAllLines(f);
        assertEquals(500, after.size());
        assertEquals(lines.subList(lines.size() - 500, lines.size()), after,
                "the newest 500 lines, byte for byte — nothing re-serialised, nothing dropped");
    }

    @Test
    @DisplayName("below the threshold nothing is rewritten")
    void smallJournalIsLeftAlone() throws Exception {
        Path f = tmp.resolve("journal.jsonl");
        Files.writeString(f, "{\"id\":\"a\"}\n");
        new WishJournal(f).compactIfLarge();
        assertEquals("{\"id\":\"a\"}\n", Files.readString(f));
    }
}
