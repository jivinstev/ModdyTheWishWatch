package com.moddingfromamod.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

/**
 * The process recorder {@code NoPythonGameTest} relies on reads a JFR dump that holds other events too. A dump holds
 * every event of its chunks, other recordings' among them. The recorder once read {@code command} from all of them
 * and threw "Attempt to get unknown field" on one Windows JDK build, then passed on a re-run of the same tree.
 */
class ProcessStartsTest {

    @Name("moddingfromamod.test.Other")
    @Label("Another recording's event")
    static final class OtherEvent extends Event {
        @Label("Note")
        String note;
    }

    /** The java this test runs on: a program every platform has, unlike {@code true}. */
    private static String java() {
        return ProcessHandle.current().info().command().orElseThrow();
    }

    @Test
    void aDumpWithOtherEventTypesIsReadAndOnlyProcessStartsCount() throws Exception {
        try (Recording other = new Recording()) {
            other.enable(OtherEvent.class);
            other.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO);
            other.start();
            List<String> started;
            try (ProcessStarts watch = new ProcessStarts()) {
                OtherEvent e = new OtherEvent();
                e.note = "not a process start";
                e.commit();
                Thread.sleep(5);
                new ProcessBuilder(java(), "-version").redirectErrorStream(true).start().waitFor();
                started = watch.stop();
            }
            other.stop();
            assertTrue(started.stream().anyMatch(c -> ProcessStarts.startsProgram(c, "java")),
                    "the recorder did not see the process it started: " + started);
            assertFalse(started.stream().anyMatch(c -> c.contains("not a process start")),
                    "another event was read as a process start: " + started);
        }
    }

    @Test
    void commandsSkipsEveryEventThatIsNotAProcessStart() throws Exception {
        Path f = Files.createTempFile("mixed", ".jfr");
        try (Recording r = new Recording()) {
            r.enable(OtherEvent.class);
            r.enable(ProcessStarts.EVENT).withThreshold(Duration.ZERO);
            r.start();
            OtherEvent e = new OtherEvent();
            e.note = "x";
            e.commit();
            new ProcessBuilder(java(), "-version").redirectErrorStream(true).start().waitFor();
            r.stop();
            r.dump(f);
            List<RecordedEvent> all = RecordingFile.readAllEvents(f);
            assertTrue(all.stream().anyMatch(x -> !x.hasField("command")),
                    "the dump should hold an event without a command field, or this checks nothing");
            List<String> commands = ProcessStarts.commands(all);
            assertEquals(1, commands.stream().filter(c -> ProcessStarts.startsProgram(c, "java")).count(), commands.toString());
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void theControlProgramIsMatchedHoweverThePlatformSpellsIt() {
        for (String c : List.of("true", "/usr/bin/true", "/bin/true --x", "C:\\Git\\usr\\bin\\true.exe",
                "\"C:\\Program Files\\Git\\usr\\bin\\true.exe\" a", "TRUE.EXE")) {
            assertTrue(ProcessStarts.startsProgram(c, "true"), c);
        }
        for (String c : List.of("untrue", "/usr/bin/true-ish", "python3 true", "C:\\true\\python.exe")) {
            assertFalse(ProcessStarts.startsProgram(c, "true"), c);
        }
    }
}
