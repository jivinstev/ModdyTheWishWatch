package com.moddingfromamod.wish;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ActionLog;
import com.moddingfromamod.runtime.RuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A wish writes exactly one {@code settled} record, at the one place its status moves</b>.
 * {@code tools/wish-cost.py} records a wish's cost only once it has settled, because a
 * spend record is written once and never revised — so a wish that never says it settled is a
 * wish whose cost is never recorded, and one that says so twice is two outcomes for one bill.
 */
class WishBookSettledTest {

    private String homeBefore;
    private Path dir;

    @BeforeEach
    void ownHome(@TempDir Path dir) {
        this.dir = dir;
        homeBefore = System.getProperty(ModdingFromAModMod.MODID + ".home");
        System.setProperty(ModdingFromAModMod.MODID + ".home", dir.toString());
        RuntimeConfig.invalidate();
        ActionLog.resetForTest();
    }

    @AfterEach
    void restore() {
        if (homeBefore == null) {
            System.clearProperty(ModdingFromAModMod.MODID + ".home");
        } else {
            System.setProperty(ModdingFromAModMod.MODID + ".home", homeBefore);
        }
        RuntimeConfig.invalidate();
        ActionLog.resetForTest();
    }

    private List<String> settled() throws Exception {
        Path log = ActionLog.path();
        if (!Files.exists(log)) {
            return List.of();
        }
        return Files.readAllLines(log, StandardCharsets.UTF_8).stream()
                .filter(l -> l.contains("\"kind\":\"settled\"")).toList();
    }

    @Test
    void onlyTheFirstFinalStatusWritesOne() throws Exception {
        WishBook book = WishBook.forTest(new WishJournal(dir.resolve("journal.jsonl")));
        UUID kid = UUID.randomUUID();
        Wish w = book.add(Wish.create("a dragon", WishSource.TYPED, System.currentTimeMillis(),
                kid, "Steve", "minecraft:overworld", 0, 64, 0));

        book.complete(w.id(), WishStatus.RUNNING, "");
        assertEquals(0, settled().size(), "RUNNING is not an outcome");

        book.complete(w.id(), WishStatus.DONE, "made it");
        book.complete(w.id(), WishStatus.DONE, "made it again");
        List<String> lines = settled();
        assertEquals(1, lines.size(), "one wish, one outcome: " + lines);
        String line = lines.get(0);
        assertTrue(line.contains("\"wishId\":\"" + w.id().toString().substring(0, 8) + "\""), line);
        assertTrue(line.contains("\"playerId\":\"" + kid + "\""), line);
        assertTrue(line.contains("\"status\":\"DONE\""), line);
        assertTrue(!line.contains("Steve"), "the child's NAME reached the cost record: " + line);
    }
}
