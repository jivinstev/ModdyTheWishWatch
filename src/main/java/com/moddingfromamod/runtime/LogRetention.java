package com.moddingfromamod.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.moddingfromamod.ModdingFromAModMod;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The local logs that can hold a player's words are kept {@link #days()} days, then pruned</b>.
 *
 * <p>The mod appends what players typed to files under its home ({@link RuntimeConfig#home()}): every wish and tool
 * call ({@code agent-actions.jsonl}, which also carries the safety records), the datapack sources the agent compiled
 * ({@code cbscript-compiles.jsonl}), the wishes it could not save ({@code one-off-wishes.jsonl}) and
 * {@code /moddy report}s ({@code player-reports.jsonl}). {@code library-gaps.jsonl} and {@code grown-up-notes.jsonl}
 * are no longer written by the mod but are pruned with the rest, because an older install may still hold one. Files that hold no words ({@code world-rules.jsonl}, {@code camera-waits.jsonl},
 * {@code knobs-audit.jsonl}: closed vocabularies, ids and timings) are left alone.
 *
 * <p><b>The period is the privacy notice's</b>, copied into the jar as {@code retention.json}. There is no switch to
 * keep logs longer, and none to turn pruning off, except the one a dedicated server with its own pruning uses:
 *
 * <p><b>A server that rotates and harvests these files on its own schedule</b> has its settings override (a
 * plug-in) set {@link #KEY} to {@code off}, so nothing is pruned twice. {@link #KEY} is not in the settings catalogue, so neither a player nor a server's
 * configuration file can set it: only an override can (see {@code docs/public-repo/SERVER_CONFIG.md}).
 *
 * <p><b>How a file is pruned.</b> These logs are append-only, so the old records are a run at the head: the pruner
 * reads from the top until the first record dated inside the window and drops everything up to the last record
 * dated outside it (an undated line between the two is kept). The rest is copied verbatim to a temporary file, any
 * record appended meanwhile is copied after it, and the temporary file replaces the log in one move. A record is
 * dated by its {@code at} (an ISO instant or epoch milliseconds) or {@code time_ms}. Rotated copies a server left
 * beside a log ({@code agent-actions.jsonl.*}) are deleted once they were last written before the window.
 *
 * <p><b>When.</b> {@link #start()} runs a pass on its own daemon thread as the mod starts and then once a day. Not on
 * a test run ({@code RunContext}): a gate never rewrites the machine's own logs.
 *
 * <p>⚠ <b>Corpora mined from these files</b> must be mined inside the window: on a machine that prunes, a record older
 * than the window is gone.
 */
public final class LogRetention {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** The setting a server's settings override turns {@code off}. Not in the catalogue: only an override sets it. */
    public static final String KEY = "logs.prune";

    /** The files pruned, under the mod home. The same list an external rotation must cover (plus reports). */
    public static final List<String> FILES = List.of(
            "agent-actions.jsonl",
            "cbscript-compiles.jsonl",
            "one-off-wishes.jsonl",
            "library-gaps.jsonl",
            "grown-up-notes.jsonl",
            "player-reports.jsonl");

    /** The jar's copy of the privacy notice's period. */
    public static final String RESOURCE = "/retention.json";

    /** Used only if {@link #RESOURCE} cannot be read; {@code LogRetentionTest} fails a jar where that happens. */
    static final int FALLBACK_DAYS = 180;

    private static final Duration DAY = Duration.ofDays(1);
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private LogRetention() {
    }

    /** What a pass did to one file. */
    public record Pruned(String file, int records, int rotations) {
    }

    // ── the period ─────────────────────────────────────────────────────────────────────────────────────

    /** The retention period in days, from {@link #RESOURCE}. */
    public static int days() {
        Integer d = readDays();
        return d != null ? d : FALLBACK_DAYS;
    }

    /** The resource's {@code logsDays}, or null when it is missing or unreadable. */
    static Integer readDays() {
        try (InputStream in = LogRetention.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return null;
            }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                int d = o.get("logsDays").getAsInt();
                return d > 0 ? d : null;
            }
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    // ── whether to prune here ──────────────────────────────────────────────────────────────────────────

    /** Off only when an override (a plug-in) sets {@link #KEY} to {@code off}; a player or a server file cannot. */
    public static boolean enabled() {
        return !"off".equals(com.moddingfromamod.config.ModdySettings.get(KEY).orElse("on"));
    }

    // ── the schedule ───────────────────────────────────────────────────────────────────────────────────

    /** Start the daily pass (the first one now), once per JVM, unless this is a test run. */
    public static void start() {
        if (com.moddingfromamod.RunContext.isTestRun() || !STARTED.compareAndSet(false, true)) {
            return;
        }
        daily("moddy-log-retention", DAY, () -> runPass(RuntimeConfig.home(), Instant.now()));
    }

    /** One pass: prune {@code home}'s logs at {@link #days()}, or nothing at all where an override turned it off. */
    public static List<Pruned> runPass(Path home, Instant now) {
        if (!enabled()) {
            return List.of();
        }
        return pruneAll(home, now, days());
    }

    /**
     * Run {@code pass} now and then every {@code period}, on a daemon thread, until the JVM exits. A plain thread with
     * its own catch, not a scheduled executor: a task that throws in an executor dies silently (CLAUDE.md), and a
     * pass that throws here is logged and the next one still runs.
     */
    public static Thread daily(String name, Duration period, Runnable pass) {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    pass.run();
                } catch (RuntimeException e) {
                    LOGGER.warn("log retention: a pass failed ({}); the next one runs in {}", e.toString(), period);
                }
                try {
                    Thread.sleep(period.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ── pruning ────────────────────────────────────────────────────────────────────────────────────────

    /** Prune every file in {@link #FILES} under {@code home}: records older than {@code days} before {@code now}. */
    public static List<Pruned> pruneAll(Path home, Instant now, int days) {
        Instant cutoff = now.minus(Duration.ofDays(days));
        List<Pruned> out = new ArrayList<>();
        for (String name : FILES) {
            Path f = home.resolve(name);
            int records = 0;
            int rotations = 0;
            try {
                records = pruneFile(f, cutoff);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("log retention: could not prune {} ({})", f, e.toString());
            }
            try {
                rotations = pruneRotations(home, name, cutoff);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("log retention: could not prune the rotations of {} ({})", f, e.toString());
            }
            if (records > 0 || rotations > 0) {
                LOGGER.info("log retention: {} — dropped {} record(s) and {} rotated file(s) older than {} days",
                        name, records, rotations, days);
            }
            out.add(new Pruned(name, records, rotations));
        }
        return out;
    }

    /**
     * Drop the records at the head of {@code f} dated before {@code cutoff}. Returns how many lines were dropped;
     * 0 (and the file untouched) when there is nothing to drop or no file.
     */
    public static int pruneFile(Path f, Instant cutoff) throws IOException {
        if (!Files.isRegularFile(f)) {
            return 0;
        }
        long cut = 0;      // the byte offset just after the last old dated line seen before a recent one
        int cutLines = 0;
        int lines = 0;
        long offset = 0;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(f), 1 << 16)) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int b;
            boolean eof = false;
            while (!eof) {
                line.reset();
                while ((b = in.read()) != -1 && b != '\n') {
                    line.write(b);
                }
                eof = b == -1;
                if (eof && line.size() == 0) {
                    break;
                }
                offset += line.size() + (eof ? 0 : 1);
                if (eof) {
                    break;  // a last line with no newline may be a record still being written: never judge it
                }
                lines++;
                Instant at = dateOf(line.toString(StandardCharsets.UTF_8));
                if (at == null) {
                    continue;
                }
                if (!at.isBefore(cutoff)) {
                    break;  // the first record inside the window: everything after it stays
                }
                cut = offset;
                cutLines = lines;
            }
        }
        if (cut == 0) {
            return 0;
        }
        Path tmp = f.resolveSibling("." + f.getFileName() + ".prune-tmp");
        try {
            long copied;
            try (FileChannel src = FileChannel.open(f, StandardOpenOption.READ);
                 OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                copied = copyFrom(src, cut, out);
            }
            // The catch-up (anything appended while the bulk was copied) and the move happen under ActionLog's lock,
            // so its appends (the action log) cannot land between them. The other writers
            // append rarely; a record one of them writes in that instant is the one loss this accepts.
            synchronized (com.moddingfromamod.agent.ActionLog.class) {
                try (FileChannel src = FileChannel.open(f, StandardOpenOption.READ);
                     OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.APPEND)) {
                    copyFrom(src, copied, out);
                }
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return cutLines;
    }

    /** Copy {@code src} from {@code from} to its current end into {@code out}; returns the new position. */
    private static long copyFrom(FileChannel src, long from, OutputStream out) throws IOException {
        long size = src.size();
        long pos = from;
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(1 << 16);
        while (pos < size) {
            buf.clear();
            int n = src.read(buf, pos);
            if (n <= 0) {
                break;
            }
            out.write(buf.array(), 0, n);
            pos += n;
        }
        return pos;
    }

    /** Delete {@code <name>.*} beside the log (a server's rotations) last written before {@code cutoff}. */
    static int pruneRotations(Path home, String name, Instant cutoff) throws IOException {
        if (!Files.isDirectory(home)) {
            return 0;
        }
        int n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(home, name + ".*")) {
            for (Path p : ds) {
                if (Files.isRegularFile(p) && Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
                    Files.delete(p);
                    n++;
                }
            }
        }
        return n;
    }

    /** When a record was written: its {@code at} (ISO instant or epoch ms) or {@code time_ms}; null if neither. */
    static Instant dateOf(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            JsonElement e = JsonParser.parseString(line);
            if (!e.isJsonObject()) {
                return null;
            }
            JsonObject o = e.getAsJsonObject();
            Instant at = instant(o.get("at"));
            return at != null ? at : instant(o.get("time_ms"));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Instant instant(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        try {
            if (p.isNumber()) {
                return Instant.ofEpochMilli(p.getAsLong());
            }
            if (p.isString()) {
                return Instant.parse(p.getAsString());
            }
        } catch (RuntimeException ex) {
            return null;
        }
        return null;
    }
}
