package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.config.ModdySettings;
import com.moddingfromamod.config.SettingsOverride;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** the mod prunes the local logs that hold players' words after the privacy notice's period. */
class LogRetentionTest {

    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

    @TempDir
    Path home;

    @AfterEach
    void putThePluginsBack() {
        ModdySettings.overridesForTest(null);
    }

    private static String at(int daysAgo, String what) {
        return "{\"at\":\"" + NOW.minus(Duration.ofDays(daysAgo)) + "\",\"wish\":\"" + what + "\"}\n";
    }

    private Path write(String name, String body) throws Exception {
        Path f = home.resolve(name);
        Files.writeString(f, body, StandardCharsets.UTF_8);
        return f;
    }

    /** What a server's settings override (a plug-in) registers when it prunes its own logs: logs.prune off. */
    private static final SettingsOverride HOUSE = new SettingsOverride() {
        @Override
        public Set<String> keys() {
            return Set.of(LogRetention.KEY);
        }

        @Override
        public Optional<String> value(String key) {
            return LogRetention.KEY.equals(key) ? Optional.of("off") : Optional.empty();
        }
    };

    @Test
    void thePeriodIsReadFromTheJarAndIsThePrivacyNoticesNumber() {
        assertNotNull(LogRetention.readDays(), "retention.json must be in the jar and readable");
        // retention.json carries the privacy notice's logsDays; this pins the jar's copy.
        assertEquals(180, LogRetention.days());
    }

    @Test
    void aRecordOlderThanThePeriodIsPrunedOnANonHouseInstall() throws Exception {
        ModdySettings.overridesForTest(List.of());
        Path f = write("agent-actions.jsonl", at(400, "oldest") + at(181, "old") + at(179, "kept") + at(1, "new"));

        List<LogRetention.Pruned> out = LogRetention.runPass(home, NOW);

        assertEquals(at(179, "kept") + at(1, "new"), Files.readString(f));
        assertEquals(2, out.stream().filter(p -> p.file().equals("agent-actions.jsonl"))
                .findFirst().orElseThrow().records());
    }

    @Test
    void nothingIsPrunedWhenRunningAsAHouse() throws Exception {
        ModdySettings.overridesForTest(List.of(HOUSE));
        String body = at(400, "oldest") + at(1, "new");
        Path f = write("agent-actions.jsonl", body);
        Path rotated = write("agent-actions.jsonl.1", at(400, "rotated"));
        Files.setLastModifiedTime(rotated, FileTime.from(NOW.minus(Duration.ofDays(400))));

        assertFalse(LogRetention.enabled());
        assertTrue(LogRetention.runPass(home, NOW).isEmpty());
        assertEquals(body, Files.readString(f));
        assertTrue(Files.exists(rotated));
    }

    @Test
    void everyFileThatHoldsWordsIsPrunedAndEachWayOfDatingARecordIsRead() throws Exception {
        long oldMs = NOW.minus(Duration.ofDays(200)).toEpochMilli();
        long newMs = NOW.minus(Duration.ofDays(2)).toEpochMilli();
        for (String name : LogRetention.FILES) {
            write(name, at(200, "old") + at(2, "new"));
        }
        // cbscript-compiles dates by time_ms, one-off-wishes by an epoch-ms "at".
        write("cbscript-compiles.jsonl", "{\"time_ms\":" + oldMs + "}\n{\"time_ms\":" + newMs + "}\n");
        write("one-off-wishes.jsonl", "{\"at\":" + oldMs + "}\n{\"at\":" + newMs + "}\n");

        LogRetention.pruneAll(home, NOW, 180);

        for (String name : LogRetention.FILES) {
            List<String> lines = Files.readAllLines(home.resolve(name));
            assertEquals(1, lines.size(), name);
            assertEquals(NOW.minus(Duration.ofDays(2)), LogRetention.dateOf(lines.get(0)), name);
        }
    }

    @Test
    void aFileWithNothingOldIsNotRewritten() throws Exception {
        Path f = write("grown-up-notes.jsonl", at(10, "a") + at(5, "b"));
        FileTime before = FileTime.from(NOW.minus(Duration.ofDays(3)));
        Files.setLastModifiedTime(f, before);

        assertEquals(0, LogRetention.pruneFile(f, NOW.minus(Duration.ofDays(180))));
        assertEquals(before, Files.getLastModifiedTime(f));
    }

    @Test
    void anUndatedLineAmongRecentOnesIsKeptAndAnUnfinishedLastLineIsNeverJudged() throws Exception {
        Path f = write("agent-actions.jsonl", at(300, "old") + "not json\n" + at(3, "new") + "{\"at\":\"" 
                + NOW.minus(Duration.ofDays(500)) + "\"");   // no newline: still being written
        LogRetention.pruneFile(f, NOW.minus(Duration.ofDays(180)));
        assertEquals("not json\n" + at(3, "new") + "{\"at\":\"" + NOW.minus(Duration.ofDays(500)) + "\"",
                Files.readString(f));
    }

    @Test
    void aFileThatIsAllOldIsEmptiedAndOldRotationsGo() throws Exception {
        Path f = write("player-reports.jsonl", at(300, "a") + at(200, "b"));
        Path oldRot = write("player-reports.jsonl.2026-01-01", "x\n");
        Files.setLastModifiedTime(oldRot, FileTime.from(NOW.minus(Duration.ofDays(181))));
        Path newRot = write("player-reports.jsonl.2026-11-01", "y\n");
        Files.setLastModifiedTime(newRot, FileTime.from(NOW.minus(Duration.ofDays(17))));

        LogRetention.pruneAll(home, NOW, 180);

        assertEquals("", Files.readString(f));
        assertFalse(Files.exists(oldRot));
        assertTrue(Files.exists(newRot));
    }

    @Test
    void dateOfReadsTheThreeShapesAndNothingElse() {
        Instant t = Instant.parse("2026-01-02T03:04:05Z");
        assertEquals(t, LogRetention.dateOf("{\"at\":\"2026-01-02T03:04:05Z\"}"));
        assertEquals(t, LogRetention.dateOf("{\"at\":" + t.toEpochMilli() + "}"));
        assertEquals(t, LogRetention.dateOf("{\"time_ms\":" + t.toEpochMilli() + "}"));
        assertNull(LogRetention.dateOf("{\"at\":\"yesterday\"}"));
        assertNull(LogRetention.dateOf("[1,2]"));
        assertNull(LogRetention.dateOf(""));
    }
}
