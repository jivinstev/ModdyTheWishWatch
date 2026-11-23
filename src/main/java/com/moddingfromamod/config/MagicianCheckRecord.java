package com.moddingfromamod.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * <b>Which picks last passed the Magician check</b>: Magician turns on only for picks that passed, and a
 * change to any pick asks for the check again. Kept beside the host's settings in
 * {@code <moddy home>/magician-check.json} (per machine, never the world folder); memory only in a test run.
 */
public final class MagicianCheckRecord {

    private static final MagicianCheckRecord SHARED = new MagicianCheckRecord(null);

    private final Path fixedFile;
    private String passed = "";
    private String summary = "";
    private boolean loaded;

    public MagicianCheckRecord(Path file) {
        this.fixedFile = file;
    }

    public static MagicianCheckRecord shared() {
        return SHARED;
    }

    private Path file() {
        if (fixedFile != null) {
            return fixedFile;
        }
        if (com.moddingfromamod.RunContext.isTestRun()) {
            return null;
        }
        return com.moddingfromamod.runtime.RuntimeConfig.home().resolve("magician-check.json");
    }

    /** Did these picks pass? */
    public synchronized boolean passedFor(MagicianPicks picks) {
        load();
        return picks.complete() && passed.equals(picks.fingerprint());
    }

    /** What the last check said (empty before one ran). */
    public synchronized String summary() {
        load();
        return summary;
    }

    public synchronized void record(MagicianPicks picks, boolean pass, String line) {
        load();
        passed = pass ? picks.fingerprint() : "";
        summary = line == null ? "" : line;
        Path f = file();
        if (f == null) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("passed", passed);
        o.addProperty("summary", summary);
        try {
            Files.createDirectories(f.toAbsolutePath().getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(com.moddingfromamod.ModdingFromAModMod.MODID)
                    .warn("magician: could not save {} ({})", f, e.toString());
        }
    }

    public synchronized void clearForTest() {
        passed = "";
        summary = "";
        loaded = true;
    }

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path f = file();
        if (f == null || !Files.isRegularFile(f)) {
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            passed = o.has("passed") ? o.get("passed").getAsString() : "";
            summary = o.has("summary") ? o.get("summary").getAsString() : "";
        } catch (IOException | RuntimeException e) {
            passed = "";
        }
    }
}
