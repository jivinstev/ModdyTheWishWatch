package com.moddingfromamod.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

/**
 * Records every process this JVM starts (JFR's {@code jdk.ProcessStart}: one event per start, from the JDK's own
 * process launch, so even a program that exits at once is seen; polling the child processes misses those). The mod
 * starts processes only through {@code ProcessBuilder}, which this covers.
 *
 * <p>A recording's dump holds every event of the chunks it spans, so other recordings' events (another type, without a
 * {@code command} field) can be in it. Only {@code jdk.ProcessStart} events that have the field are read. Reading the
 * field from every event worked or threw depending on what else the JVM was recording: the Windows platform smoke
 * failed that way on one Temurin 21 build and passed on a re-run of the same tree (round of 2026-11-26).
 * {@code ProcessStartsTest} (Gate A) records with another event type on and checks that the dump is still read.
 */
public final class ProcessStarts implements AutoCloseable {
    static final String EVENT = "jdk.ProcessStart";

    private final Recording recording = new Recording();

    public ProcessStarts() {
        recording.enable(EVENT).withThreshold(Duration.ZERO);
        recording.start();
    }

    /** Stops the recording and returns the command line of every process start it saw, in order. */
    public List<String> stop() throws IOException {
        recording.stop();
        Path f = Files.createTempFile("process-starts", ".jfr");
        try {
            recording.dump(f);
            return commands(RecordingFile.readAllEvents(f));
        } finally {
            Files.deleteIfExists(f);
        }
    }

    /** The command lines of the {@code jdk.ProcessStart} events among {@code events}; every other event is skipped. */
    static List<String> commands(List<RecordedEvent> events) {
        List<String> out = new ArrayList<>();
        for (RecordedEvent e : events) {
            if (EVENT.equals(e.getEventType().getName()) && e.hasField("command")) {
                out.add(String.valueOf(e.getString("command")));
            }
        }
        return out;
    }

    /**
     * Is {@code command} a start of the program {@code name}, however the platform spells it: the bare name, a path to
     * it, with or without a quote or a {@code .exe}, followed by its arguments or not ({@code true}, {@code /usr/bin/true},
     * {@code C:\tools\true.exe}).
     */
    public static boolean startsProgram(String command, String name) {
        String c = command.strip();
        if (c.startsWith("\"")) {
            int end = c.indexOf('"', 1);
            c = end > 0 ? c.substring(1, end) : c.substring(1);
        } else {
            int space = c.indexOf(' ');
            if (space > 0) {
                c = c.substring(0, space);
            }
        }
        String base = c.substring(Math.max(c.lastIndexOf('/'), c.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT);
        if (base.endsWith(".exe")) {
            base = base.substring(0, base.length() - 4);
        }
        return base.equals(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public void close() {
        recording.close();
    }
}
