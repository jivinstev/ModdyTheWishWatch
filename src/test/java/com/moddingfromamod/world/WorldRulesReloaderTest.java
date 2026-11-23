package com.moddingfromamod.world;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hot reload — an edit changes behaviour with no restart; an invalid file keeps the previous rules and pages once. */
class WorldRulesReloaderTest {

    private static final String DENY_TIME = """
            {"store":"world-rules","v":1,"world":"closed","rules":[
             {"id":"t","type":"deny_world_effect","effects":["time"],"message":{"rule":"a","why":"b"}}]}""";
    private static final String DENY_WEATHER = DENY_TIME.replace("\"time\"", "\"weather\"");

    private int bump = 0;

    private void write(Path f, String text) throws Exception {
        Files.writeString(f, text);
        // a fresh mtime each write, as a real edit or an atomic rename gives
        Files.setLastModifiedTime(f, FileTime.fromMillis(1_000_000_000_000L + (++bump) * 10_000L));
    }

    @Test
    void anEditChangesBehaviourWithNoRestart(@TempDir Path d) throws Exception {
        Path f = d.resolve("world-rules.json");
        List<String> alerts = new ArrayList<>();
        WorldRulesReloader r = new WorldRulesReloader(f, "closed", WorldRules.CLOSED, alerts::add, s -> { });
        assertEquals(WorldRulesReloader.Outcome.MISSING, r.poll());
        assertSame(WorldRules.CLOSED, r.current(), "no file on a bases world: CLOSED, never allow-all");
        assertFalse(r.fromFile());

        write(f, DENY_TIME);
        assertEquals(WorldRulesReloader.Outcome.LOADED, r.poll());
        assertTrue(r.current().denies(WorldEffect.TIME) && !r.current().denies(WorldEffect.WEATHER));
        assertEquals(WorldRulesReloader.Outcome.UNCHANGED, r.poll(), "nothing changed, nothing re-read");

        write(f, DENY_WEATHER);
        assertEquals(WorldRulesReloader.Outcome.LOADED, r.poll());
        assertTrue(r.current().denies(WorldEffect.WEATHER) && !r.current().denies(WorldEffect.TIME),
                "the edit is in force at once");
        assertTrue(alerts.isEmpty());
    }

    @Test
    void anInvalidFileKeepsThePreviousRulesAndPagesOnce(@TempDir Path d) throws Exception {
        Path f = d.resolve("world-rules.json");
        List<String> alerts = new ArrayList<>();
        WorldRulesReloader r = new WorldRulesReloader(f, "closed", WorldRules.CLOSED, alerts::add, s -> { });
        write(f, DENY_TIME);
        r.poll();
        WorldRules good = r.current();

        write(f, DENY_TIME.replace("deny_world_effect", "deny_wrold_effect"));
        assertEquals(WorldRulesReloader.Outcome.REFUSED, r.poll());
        assertSame(good, r.current(), "the previous rules stay in force");
        assertEquals(1, alerts.size());
        assertTrue(alerts.get(0).startsWith("MODDY-ALERT world rules invalid: world-rules.json: "),
                "the watchdog's alarm prefix: " + alerts.get(0));
        assertTrue(alerts.get(0).contains("keeping the previous rules (" + good.sha().substring(0, 8) + ")"));
        assertFalse(r.lastError().isEmpty());

        // polled again (touched, same bad bytes): no second page
        Files.setLastModifiedTime(f, FileTime.fromMillis(1_000_000_000_000L + (++bump) * 10_000L));
        assertEquals(WorldRulesReloader.Outcome.REFUSED, r.poll());
        assertEquals(1, alerts.size(), "one page per bad file, not one per poll");

        // fixed: loads, and the error clears
        write(f, DENY_WEATHER);
        assertEquals(WorldRulesReloader.Outcome.LOADED, r.poll());
        assertTrue(r.lastError().isEmpty());
    }

    @Test
    void aFileForAnotherWorldIsRefused(@TempDir Path d) throws Exception {
        Path f = d.resolve("world-rules.json");
        List<String> alerts = new ArrayList<>();
        WorldRulesReloader r = new WorldRulesReloader(f, "open", WorldRules.ALLOW_ALL, alerts::add, s -> { });
        write(f, DENY_TIME);
        assertEquals(WorldRulesReloader.Outcome.REFUSED, r.poll());
        assertSame(WorldRules.ALLOW_ALL, r.current(), "one world's rules must never govern another");
        assertTrue(alerts.get(0).contains("this file is for 'open'"));
    }

    @Test
    void aFileThatDisappearsKeepsWhatWasLoaded(@TempDir Path d) throws Exception {
        Path f = d.resolve("world-rules.json");
        WorldRulesReloader r = new WorldRulesReloader(f, "closed", WorldRules.CLOSED, s -> { }, s -> { });
        write(f, DENY_WEATHER);
        r.poll();
        Files.delete(f);
        assertEquals(WorldRulesReloader.Outcome.MISSING, r.poll());
        assertTrue(r.current().denies(WorldEffect.WEATHER) && r.fromFile(),
                "a delivery hiccup is not an instruction to allow everything");
    }
}
