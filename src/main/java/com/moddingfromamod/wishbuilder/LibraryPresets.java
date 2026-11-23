package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The ready-mades the Builder starts from, read out of the jar's starter library — the same
 * files {@code StarterLibrary} unpacks for the wish path, so a preset the Builder offers is one the
 * worker can reach too (principle 2). Minecraft-free.
 */
public final class LibraryPresets implements BuilderLowering.Presets {

    /** Which stored file is a ready-made's arguments, by its library directory. */
    static final Map<String, String> FILE = Map.of(
            "species", "sculpt.json",
            "buildings", "build.json",
            "pictures", "painting.json",
            "events", "event.json",
            "items", "item.json",
            "outputs", "item.json");

    public static final LibraryPresets SHIPPED = new LibraryPresets();

    /** An item preset's icon ({@code items/<id>/icon.json}: {@code sprite}, {@code tint}), or null. */
    @Override
    public JsonObject icon(String preset) {
        if (preset == null || !(preset.startsWith("items/") || preset.startsWith("outputs/")) || preset.contains("..")) {
            return null;
        }
        JsonObject j = json("starter-library/" + preset + "/icon.json");
        return j != null ? j : json("builder-library/" + preset + "/icon.json");
    }

    /** A creature ready-made's species file ({@code species/cat}, {@code bodies/truck}): its name, life, powers; else null. */
    public JsonObject species(String preset) {
        if (preset == null || preset.contains("..")) {
            return null;
        }
        return preset.startsWith("species/") ? json("starter-library/" + preset + "/species.json")
                : preset.startsWith("bodies/")
                ? json("builder-bodies/species/" + preset.substring("bodies/".length()) + "/species.json") : null;
    }

    /** The words a ready-made is called by: its stored name or title, else its id in words. */
    public String label(String preset) {
        JsonObject j = species(preset);
        if (j == null) {
            j = read(preset);
        }
        for (String k : new String[] {"name", "title"}) {
            if (j != null && j.has(k) && j.get(k).isJsonPrimitive()) {
                String v = j.get(k).getAsString().replaceFirst("^Your ", "").strip();
                if (!v.isEmpty() && !v.contains("_")) {
                    return v.endsWith("!") ? cap(v.replaceAll("!+$", "").toLowerCase(java.util.Locale.ROOT)) : v;
                }
            }
        }
        String id = preset == null ? "" : preset.substring(preset.indexOf('/') + 1);
        return cap(id.replace('_', ' '));
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Override
    public JsonObject read(String preset) {
        if (preset == null || preset.indexOf('/') < 1 || preset.contains("..")) {
            return null;
        }
        if (preset.startsWith("bodies/")) {
            return json("builder-bodies/species/" + preset.substring("bodies/".length()) + "/sculpt.json");
        }
        String file = FILE.get(preset.substring(0, preset.indexOf('/')));
        if (file == null) {
            return null;
        }
        JsonObject j = json("starter-library/" + preset + "/" + file);
        // …else the Builder's own library: presets only the Builder offers, until B6.
        return j != null ? j : json("builder-library/" + preset + "/" + file);
    }

    /**
     * the jar's files never change while it runs, so each is read and parsed ONCE. Measured: a random walk
     * through the Builder's menus spent three quarters of its time re-reading and re-parsing these files on every page
     * (a page greys what each body cannot take, by reading each body). Every caller gets its own copy, so one that
     * changes what it was given changes nobody else's.
     */
    private static final Map<String, java.util.Optional<JsonObject>> PARSED = new java.util.concurrent.ConcurrentHashMap<>();

    private static JsonObject json(String path) {
        JsonObject j = PARSED.computeIfAbsent(path, p -> java.util.Optional.ofNullable(parse(p))).orElse(null);
        return j == null ? null : j.deepCopy();
    }

    private static JsonObject parse(String path) {
        try (InputStream in = LibraryPresets.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null
                    : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }
}
