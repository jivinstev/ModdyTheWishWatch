package com.moddingfromamod.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * <b>A world's rules, re-read live whenever the file changes</b> (the "reread" class:
 * published, then picked up with no restart). Minecraft-free, so the three promises are unit tests
 * ({@code WorldRulesReloaderTest}) rather than hopes:
 * <ol>
 *   <li><b>an edit changes behaviour with no restart</b> — {@link #poll()} notices a new mtime or
 *       size, parses, and swaps {@link #current()} atomically;</li>
 *   <li><b>an invalid file keeps the previous rules and alerts the operator</b> — the parse error goes
 *       to the alert sink as ONE {@code MODDY-ALERT world rules invalid: …} line per bad file (an
 *       operator's log watcher can alert on it), never one per poll;</li>
 *   <li><b>a file that disappears keeps what was loaded</b> — a missing file mid-run is a delivery
 *       hiccup, not an instruction to allow everything. Only a boot with no file uses the
 *       fallback, which on a bases world is {@link WorldRules#CLOSED}.</li>
 * </ol>
 * Polling rather than a {@code WatchService}: one {@code stat} every few seconds costs nothing, and
 * a watch service on a file replaced by an atomic rename (how {@code world-rules.py pull} writes it)
 * watches an inode that no longer exists.
 */
public final class WorldRulesReloader {

    private final Path file;
    private final String expectWorld;
    private final WorldRules fallback;
    private final Consumer<String> alert;
    private final Consumer<String> info;

    private volatile WorldRules current;
    private volatile boolean fromFile;
    private long seenMtime = Long.MIN_VALUE;
    private long seenSize = -1;
    private String badSha = "";
    private volatile String lastError = "";

    /**
     * @param file        the rules file ({@code <gameDir>/moddingfromamod/world-rules.json})
     * @param expectWorld this server's world id, or null/empty when it is not known
     * @param fallback    the rules until a good file is read
     * @param alert       where a {@code MODDY-ALERT} line goes (the server log, at WARN)
     * @param info        where a routine line goes (a reload that worked)
     */
    public WorldRulesReloader(Path file, String expectWorld, WorldRules fallback,
                              Consumer<String> alert, Consumer<String> info) {
        this.file = file;
        this.expectWorld = expectWorld == null ? "" : expectWorld;
        this.fallback = fallback == null ? WorldRules.ALLOW_ALL : fallback;
        this.current = this.fallback;
        this.alert = alert == null ? s -> { } : alert;
        this.info = info == null ? s -> { } : info;
    }

    /** What a poll did. */
    public enum Outcome { UNCHANGED, LOADED, REFUSED, MISSING }

    /** Look at the file once; reload it if it changed. Never throws. */
    public synchronized Outcome poll() {
        long mtime;
        long size;
        try {
            if (!Files.isRegularFile(file)) {
                return Outcome.MISSING;
            }
            mtime = Files.getLastModifiedTime(file).toMillis();
            size = Files.size(file);
        } catch (IOException | RuntimeException e) {
            return Outcome.MISSING;
        }
        if (mtime == seenMtime && size == seenSize) {
            return Outcome.UNCHANGED;
        }
        seenMtime = mtime;
        seenSize = size;
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return refuse(List.of("could not be read: " + e.getMessage()), "unreadable-" + mtime);
        }
        String sha = WorldRules.sha1(text);
        if (fromFile && sha.equals(current.sha())) {
            badSha = "";                            // touched, or a bad edit put back
            lastError = "";
            return Outcome.UNCHANGED;
        }
        WorldRules.Parsed p = WorldRules.parse(text, expectWorld);
        if (!p.ok()) {
            return refuse(p.errors(), sha);
        }
        current = p.rules();
        fromFile = true;
        badSha = "";
        lastError = "";
        info.accept("world rules loaded from " + file + ": " + current + " (sha " + sha.substring(0, 8) + ")");
        return Outcome.LOADED;
    }

    private Outcome refuse(List<String> errors, String sha) {
        lastError = String.join("; ", errors);
        if (!sha.equals(badSha)) {
            badSha = sha;
            String first = errors.isEmpty() ? "?" : errors.get(0);
            alert.accept(alertLine(file, first, errors.size() - 1,
                    fromFile ? current.sha().substring(0, Math.min(8, current.sha().length())) : "none"));
        }
        return Outcome.REFUSED;
    }

    /** The one alert line — its prefix is what a log watcher alerts on. */
    static String alertLine(Path file, String first, int more, String keeping) {
        return "MODDY-ALERT world rules invalid: " + file.getFileName() + ": " + first
                + (more > 0 ? " (+" + more + " more)" : "")
                + " — keeping the previous rules (" + keeping + ")";
    }

    /** The rules in force. */
    public WorldRules current() {
        return current;
    }

    /** True once a file has been read successfully (false = the fallback is in force). */
    public boolean fromFile() {
        return fromFile;
    }

    /** Why the last read was refused, or empty. */
    public String lastError() {
        return lastError;
    }

    public Path file() {
        return file;
    }
}
