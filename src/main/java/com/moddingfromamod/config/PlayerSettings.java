package com.moddingfromamod.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * <b>The host's own settings</b>: what a single-player owner (or the LAN host, D18) chose for their game,
 * in {@code <moddy home>/settings.json} ({@code {"magician.enabled": "on", …}}). Never an API key (keys come only from
 * environment variables, D5). In a test run it lives in memory only, so a gate never reads or writes the machine's
 * own choices.
 */
public final class PlayerSettings {

    private static final PlayerSettings SHARED = new PlayerSettings(null);

    private final Path fixedFile;
    private final Map<String, String> values = new LinkedHashMap<>();
    private boolean loaded;

    /** {@code file} null: the game's file under the Moddy home, or memory only in a test run. */
    public PlayerSettings(Path file) {
        this.fixedFile = file;
    }

    public static PlayerSettings shared() {
        return SHARED;
    }

    private Path file() {
        if (fixedFile != null) {
            return fixedFile;
        }
        if (com.moddingfromamod.RunContext.isTestRun()) {
            return null;
        }
        return com.moddingfromamod.runtime.RuntimeConfig.home().resolve("settings.json");
    }

    public synchronized Optional<String> get(String key) {
        load();
        return Optional.ofNullable(values.get(key));
    }

    public synchronized void set(String key, String value) {
        load();
        values.put(key, value);
        Path f = file();
        if (f == null) {
            return;
        }
        JsonObject o = new JsonObject();
        values.forEach(o::addProperty);
        try {
            Files.createDirectories(f.toAbsolutePath().getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(com.moddingfromamod.ModdingFromAModMod.MODID)
                    .warn("settings: could not save {} ({}); it holds until the game closes", f, e.toString());
        }
    }

    /** For tests: forget everything (memory only; the file is not touched). */
    public synchronized void clearForTest() {
        values.clear();
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
            JsonElement el = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            if (el.isJsonObject()) {
                for (var e : el.getAsJsonObject().entrySet()) {
                    if (e.getValue().isJsonPrimitive() && SettingsCatalog.find(e.getKey())
                            .map(st -> st.playerChangeable() && st.accepts(e.getValue().getAsString())).orElse(false)) {
                        values.put(e.getKey(), e.getValue().getAsString());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(com.moddingfromamod.ModdingFromAModMod.MODID)
                    .warn("settings: could not read {} ({}); using the defaults", f, e.toString());
        }
    }
}
