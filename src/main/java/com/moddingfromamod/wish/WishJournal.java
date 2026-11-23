package com.moddingfromamod.wish;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The on-disk record of every wish, as append-only JSONL (one JSON object per line).
 *
 * <p>Why a journal and not {@code SavedData}: this file is also the <b>change signal</b> for an
 * outside process (the Phase-3 daemon that wakes Claude). JSONL is trivially tailable and a partial
 * write only ever damages the last line — which {@link #readAll()} skips rather than dying on. A
 * crash mid-write must never cost the kid his wish history.
 *
 * <p>Note this is persistence, <em>not</em> a coordination protocol: claiming and status changes are
 * decided in memory on the server thread by {@link WishBook}, and merely recorded here.
 *
 * <p>Lives under the world folder, so wishes travel with the save and don't leak between worlds.
 */
public final class WishJournal {

    private static final Logger LOGGER = LoggerFactory.getLogger("moddingfromamod");

    /** Above this many lines, compaction rewrites the file keeping only the newest entries. */
    private static final int COMPACT_THRESHOLD = 2_000;
    private static final int KEEP_ON_COMPACT = 500;

    private final Path file;

    public WishJournal(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** Append one wish. Best-effort: a journal failure must never break gameplay. */
    public synchronized void append(Wish wish) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, wish.toJson() + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warn("could not append to the wish journal at {}: {}", file, e.toString());
        }
    }

    /**
     * Every wish on disk, oldest first. Malformed lines — including a truncated final line from a
     * crash mid-write — are skipped with a warning rather than aborting the read.
     */
    public synchronized List<Wish> readAll() {
        List<Wish> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        int skipped = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                Wish w = parseLine(trimmed);
                if (w == null) {
                    skipped++;
                } else {
                    out.add(w);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("could not read the wish journal at {}: {}", file, e.toString());
        }
        if (skipped > 0) {
            LOGGER.warn("skipped {} damaged line(s) in the wish journal", skipped);
        }
        return out;
    }

    private static Wish parseLine(String line) {
        try {
            JsonElement el = JsonParser.parseString(line);
            if (el == null || !el.isJsonObject()) {
                return null;
            }
            return Wish.fromJson((JsonObject) el);
        } catch (RuntimeException e) {
            return null; // truncated or corrupt line
        }
    }

    /**
     * Rewrite the file keeping only the newest entries, if it has grown large. Writes to a temp file
     * and moves it into place, so a reader (or the daemon) never observes a half-written journal.
     *
     * <p>⚠ <b>It keeps the newest LINES, byte for byte — never a re-serialisation.</b> This
     * used to parse every line and write {@code Wish.toJson()} back, which is correct only for a
     * line this build fully understands: a line written by a NEWER Moddy lost every field this one
     * does not know, and a line it could not parse at all was dropped — the one place the journal,
     * otherwise append-only, rewrote history it could not read. Copying the raw text keeps both.
     */
    public synchronized void compactIfLarge() {
        List<String> lines = new ArrayList<>();
        try {
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        lines.add(line);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("could not read the wish journal to compact it: {}", e.toString());
            return;
        }
        if (lines.size() < COMPACT_THRESHOLD) {
            return;
        }
        List<String> keep = lines.subList(Math.max(0, lines.size() - KEEP_ON_COMPACT), lines.size());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp-" + System.nanoTime());
        try {
            StringBuilder sb = new StringBuilder();
            for (String line : keep) {
                sb.append(line).append(System.lineSeparator());
            }
            Files.createDirectories(file.getParent());
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            LOGGER.info("compacted the wish journal: {} -> {} lines", lines.size(), keep.size());
        } catch (IOException e) {
            LOGGER.warn("could not compact the wish journal: {}", e.toString());
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
