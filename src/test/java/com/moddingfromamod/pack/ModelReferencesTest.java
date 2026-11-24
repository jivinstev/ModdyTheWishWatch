package com.moddingfromamod.pack;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Every model, texture and parent the jar's assets name exists, in the jar or in the target's vanilla client.</b>
 *
 * <p>The wish egg's model had {@code minecraft:item/template_spawn_egg} as its parent. Vanilla removed that template
 * when spawn eggs got their own models (1.21.5), so the client logged {@code Missing block model} and drew the egg as
 * the magenta-and-black missing model. Nothing failed: a compile, a headless GameTest and a stock client never load an
 * item model. This walks every file under {@code models/}, {@code items/} and {@code blockstates/}, and checks each
 * reference by name: one in our own namespace against {@code src/main/resources}, one in {@code minecraft:} against
 * the client jar of {@link com.moddingfromamod.compat.Target#MINECRAFT} (the one {@link HotbarBadgeTest#clientJar()}
 * finds). A Minecraft update that removes a model or a texture we lean on fails here by file name.
 */
class ModelReferencesTest {

    private static final String NS = "moddingfromamod";
    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final Path ASSETS = RESOURCES.resolve("assets/" + NS);

    /** A reference found in one of our asset files: what it names, and whether it is a model or a texture. */
    record Ref(String file, String kind, String id) {
        /** The path inside a jar or resources root that must exist for this reference to load. */
        String path() {
            String ns = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
            String p = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            return "model".equals(kind) ? "assets/" + ns + "/models/" + p + ".json"
                    : "assets/" + ns + "/textures/" + p + ".png";
        }

        String namespace() {
            return id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
        }

        @Override
        public String toString() {
            return file + ": " + kind + " " + id + " (no " + path() + ")";
        }
    }

    static List<Ref> references() throws IOException {
        List<Ref> refs = new ArrayList<>();
        for (Path f : jsonFiles(ASSETS.resolve("models"))) {
            JsonObject model = parse(f);
            String name = rel(f);
            if (model.has("parent")) {
                String parent = model.get("parent").getAsString();
                if (!"builtin/generated".equals(parent)) {   // still the root of vanilla's item/generated in 1.21.8
                    refs.add(new Ref(name, "model", parent));
                }
            }
            if (model.has("textures")) {
                for (Map.Entry<String, JsonElement> t : model.getAsJsonObject("textures").entrySet()) {
                    String v = texture(t.getValue());
                    if (v != null && !v.startsWith("#")) {
                        refs.add(new Ref(name, "texture", v));
                    }
                }
            }
        }
        for (Path f : jsonFiles(ASSETS.resolve("items"))) {
            itemModels(parse(f), rel(f), refs);
        }
        for (Path f : jsonFiles(ASSETS.resolve("blockstates"))) {
            blockstateModels(parse(f), rel(f), refs);
        }
        return refs;
    }

    /** A texture entry is a bare id, or (1.21.5+) an object with a {@code sprite}. */
    private static String texture(JsonElement e) {
        if (e.isJsonPrimitive()) {
            return e.getAsString();
        }
        if (e.isJsonObject() && e.getAsJsonObject().has("sprite")) {
            return e.getAsJsonObject().get("sprite").getAsString();
        }
        return null;
    }

    /** Every model a client item definition draws: {@code minecraft:model}'s model and {@code minecraft:special}'s base. */
    private static void itemModels(JsonElement e, String file, List<Ref> out) {
        if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(c -> itemModels(c, file, out));
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            String type = o.has("type") && o.get("type").isJsonPrimitive() ? o.get("type").getAsString() : "";
            if (("minecraft:model".equals(type) || "model".equals(type)) && o.has("model")
                    && o.get("model").isJsonPrimitive()) {
                out.add(new Ref(file, "model", o.get("model").getAsString()));
            }
            if (("minecraft:special".equals(type) || "special".equals(type)) && o.has("base")) {
                out.add(new Ref(file, "model", o.get("base").getAsString()));
            }
            o.entrySet().forEach(c -> itemModels(c.getValue(), file, out));
        }
    }

    /** Every model a blockstate names, in {@code variants} or {@code multipart}, single or weighted. */
    private static void blockstateModels(JsonElement e, String file, List<Ref> out) {
        if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(c -> blockstateModels(c, file, out));
        } else if (e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> c : e.getAsJsonObject().entrySet()) {
                if ("model".equals(c.getKey()) && c.getValue().isJsonPrimitive()) {
                    out.add(new Ref(file, "model", c.getValue().getAsString()));
                } else {
                    blockstateModels(c.getValue(), file, out);
                }
            }
        }
    }

    private static List<Path> jsonFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    private static JsonObject parse(Path f) throws IOException {
        return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String rel(Path f) {
        return RESOURCES.relativize(f).toString().replace('\\', '/');
    }

    @Test
    @DisplayName("every model, texture and parent in our own namespace ships in the jar")
    void ownReferencesExist() throws IOException {
        List<Ref> refs = references();
        assertTrue(refs.size() > 500, "only " + refs.size() + " references found: the walk is not reading the assets");
        Map<String, Ref> missing = new TreeMap<>();
        for (Ref r : refs) {
            if (NS.equals(r.namespace()) && !Files.exists(RESOURCES.resolve(r.path()))) {
                missing.put(r.toString(), r);
            } else if (!NS.equals(r.namespace()) && !"minecraft".equals(r.namespace())) {
                missing.put(r + " -- a namespace that is neither ours nor vanilla's", r);
            }
        }
        assertTrue(missing.isEmpty(), missing.size() + " references the client cannot load (it draws the "
                + "missing model or texture):\n  " + String.join("\n  ", missing.keySet()));
    }

    @Test
    @DisplayName("every minecraft: model, texture and parent we name exists in the target's client jar (skipped by name without it)")
    void vanillaReferencesExist() throws IOException {
        Path jar = HotbarBadgeTest.clientJar();
        Assumptions.assumeTrue(Files.exists(jar),
                "SKIPPED: no Minecraft client jar at " + jar + " (a fresh checkout before the first build); the "
                        + "minecraft: references cannot be checked against the real assets here");
        Set<String> names = new HashSet<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            z.stream().forEach(e -> names.add(e.getName()));
        }
        int checked = 0;
        Map<String, Ref> missing = new TreeMap<>();
        for (Ref r : references()) {
            if (!"minecraft".equals(r.namespace())) {
                continue;
            }
            checked++;
            if (!names.contains(r.path())) {
                missing.put(r.toString(), r);
            }
        }
        assertTrue(checked > 200, "only " + checked + " minecraft: references checked");
        assertTrue(missing.isEmpty(), missing.size() + " minecraft: references that Minecraft "
                + com.moddingfromamod.compat.Target.MINECRAFT + " does not ship (the client logs Missing block model "
                + "or a missing texture and draws the magenta checkerboard):\n  " + String.join("\n  ", missing.keySet()));
    }
}
