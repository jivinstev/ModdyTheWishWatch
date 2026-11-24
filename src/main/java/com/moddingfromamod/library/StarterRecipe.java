package com.moddingfromamod.library;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>How a starter datapack was made, kept beside it</b> ({@code docs/STARTER_LIBRARY.md}). Minecraft-free.
 *
 * <p>A starter pack is compiled output frozen into the jar:
 * {@code starter-library/datapacks/<id>/<id>.zip}. Without the call that made it, a compiler or Minecraft
 * change can only be patched inside the zip. So every pack keeps a {@code recipe.json} next to it:
 *
 * <pre>
 *   {
 *     "recipe": "starter-pack", "v": 1,
 *     "id": "periodic_trex",
 *     "calls": [ {"tool": "apply_effect", "args": {"effect": "periodic", "tag": "trex", "params": {...}}} ],
 *     "compiler": "&lt;the 40-hex commit in tools/cbscript.SOURCE it was compiled with&gt;",
 *     "date": "2026-11-24",
 *     "note": "optional: anything a rebuild should know"
 *   }
 * </pre>
 *
 * <p>The calls are replayed, in order, through the real tools by {@code StarterRegenGameTest}; the last one
 * must make the pack {@code id}. {@code StarterRecipeTest} (Gate A) refuses a starter pack without one.
 */
public final class StarterRecipe {

    /** The {@code recipe} field: says what the file is. */
    public static final String KIND = "starter-pack";
    /** The newest version read; a store refuses one it cannot read. */
    public static final int VERSION = 1;
    /** The file beside each pack. */
    public static final String FILE = "recipe.json";
    /** The tools a recipe may replay: the two that make a starter pack. */
    public static final List<String> TOOLS = List.of("apply_effect", "make_item");

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** One tool call, exactly as it is replayed. */
    public record Call(String tool, JsonObject args) {
    }

    /** A read recipe. */
    public record Recipe(String id, List<Call> calls, String compiler, String date, String note) {
    }

    private StarterRecipe() {
    }

    /**
     * Read and check one recipe. Throws {@link IllegalArgumentException} naming what is wrong rather than
     * returning something half-read: a rebuild from half a recipe is a different pack.
     */
    public static Recipe parse(String json) {
        JsonObject o;
        try {
            o = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a JSON object: " + e.getMessage());
        }
        if (!KIND.equals(str(o, "recipe"))) {
            throw new IllegalArgumentException("\"recipe\" must be \"" + KIND + "\", not " + o.get("recipe"));
        }
        if (!o.has("v") || !o.get("v").isJsonPrimitive() || o.get("v").getAsInt() != VERSION) {
            throw new IllegalArgumentException("version " + o.get("v") + " is not one this build reads (" + VERSION + ")");
        }
        String id = str(o, "id");
        if (id == null || !id.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("\"id\" must be the pack id, lower case: " + o.get("id"));
        }
        String compiler = str(o, "compiler");
        if (compiler == null || !COMMIT.matcher(compiler).matches()) {
            throw new IllegalArgumentException("\"compiler\" must be the full 40-hex commit of the bundled compiler: "
                    + o.get("compiler"));
        }
        String date = str(o, "date");
        if (date == null || !DATE.matcher(date).matches()) {
            throw new IllegalArgumentException("\"date\" must be YYYY-MM-DD: " + o.get("date"));
        }
        if (!o.has("calls") || !o.get("calls").isJsonArray() || o.getAsJsonArray("calls").isEmpty()) {
            throw new IllegalArgumentException("\"calls\" must list at least one tool call");
        }
        List<Call> calls = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("calls")) {
            if (!e.isJsonObject()) {
                throw new IllegalArgumentException("a call must be an object: " + e);
            }
            JsonObject c = e.getAsJsonObject();
            String tool = str(c, "tool");
            if (!TOOLS.contains(tool)) {
                throw new IllegalArgumentException("a call's \"tool\" must be one of " + TOOLS + ": " + c.get("tool"));
            }
            if (!c.has("args") || !c.get("args").isJsonObject()) {
                throw new IllegalArgumentException("a call needs its full \"args\" object: " + c);
            }
            calls.add(new Call(tool, c.getAsJsonObject("args").deepCopy()));
        }
        return new Recipe(id, List.copyOf(calls), compiler, date, str(o, "note"));
    }

    /**
     * The recipe a starter pack should carry: its calls, the compiler commit and the date. The step that adds a
     * starter writes this at the moment it copies the zip.
     */
    public static JsonObject write(String id, List<Call> calls, String compiler, String date, String note) {
        JsonObject o = new JsonObject();
        o.addProperty("recipe", KIND);
        o.addProperty("v", VERSION);
        o.addProperty("id", id);
        JsonArray arr = new JsonArray();
        for (Call c : calls) {
            JsonObject call = new JsonObject();
            call.addProperty("tool", c.tool());
            call.add("args", c.args().deepCopy());
            arr.add(call);
        }
        o.add("calls", arr);
        o.addProperty("compiler", compiler);
        o.addProperty("date", date);
        if (note != null && !note.isBlank()) {
            o.addProperty("note", note);
        }
        // Parsed back so a writer can never produce a recipe the reader refuses.
        parse(o.toString());
        return o;
    }

    /**
     * Every pack directory under {@code datapacksDir} that has no readable recipe, with why. Empty when every
     * pack has one whose {@code id} is its directory's.
     */
    public static List<String> problems(Path datapacksDir) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(datapacksDir)) {
            return out;
        }
        try (Stream<Path> dirs = Files.list(datapacksDir)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                String id = dir.getFileName().toString();
                Path f = dir.resolve(FILE);
                if (!Files.isRegularFile(f)) {
                    out.add(id + ": no " + FILE + " beside " + id + ".zip");
                    continue;
                }
                try {
                    Recipe r = parse(Files.readString(f, StandardCharsets.UTF_8));
                    if (!r.id().equals(id)) {
                        out.add(id + ": its recipe says it makes '" + r.id() + "'");
                    }
                } catch (IllegalArgumentException e) {
                    out.add(id + ": " + FILE + " " + e.getMessage());
                }
            }
        }
        return out;
    }

    /**
     * The {@code make_item} call a starter item's design is: {@code item.json}'s fields, its effects joined, its
     * icon, and {@code from_scratch}. An item recipe's call must say exactly this (plus {@code start_from} for one
     * rebuilt under its own id), so the recipe and the design cannot drift apart.
     */
    public static JsonObject makeItemCall(JsonObject item, JsonObject icon) {
        JsonObject call = new JsonObject();
        for (String k : new String[] {"name", "base_item", "shape", "count"}) {
            if (item.has(k) && !item.get(k).isJsonNull()) {
                call.add(k, item.get(k));
            }
        }
        List<String> fx = new ArrayList<>();
        item.getAsJsonArray("effects").forEach(e -> fx.add(e.getAsString()));
        call.addProperty("effect", String.join("+", fx));
        if (item.has("params")) {
            call.add("params", item.get("params"));
        }
        for (String k : new String[] {"properties", "parts", "variants"}) {
            JsonElement v = item.get(k);
            boolean empty = v == null || v.isJsonNull()
                    || v.isJsonArray() && v.getAsJsonArray().isEmpty()
                    || v.isJsonObject() && v.getAsJsonObject().isEmpty();
            if (!empty) {
                call.add(k, v);
            }
        }
        if (icon != null) {
            call.addProperty("icon", icon.get("sprite").getAsString());
            call.addProperty("colour", icon.get("tint").getAsString());
        }
        call.addProperty("from_scratch", true);
        return call.deepCopy();
    }

    /**
     * Where two packs' functions and function tags differ, one line per file, comparing what Minecraft runs.
     * {@code pack.mcmeta} is the version stamp (its pack format follows the compiler and the game) and is
     * reported separately by {@link #stampDiffers}, never here.
     */
    public static List<String> functionDiff(byte[] shipped, byte[] rebuilt) throws IOException {
        Map<String, String> a = functions(shipped);
        Map<String, String> b = functions(rebuilt);
        List<String> out = new ArrayList<>();
        java.util.TreeSet<String> names = new java.util.TreeSet<>(a.keySet());
        names.addAll(b.keySet());
        for (String n : names) {
            String x = a.get(n);
            String y = b.get(n);
            if (x == null) {
                out.add(n + ": only in the rebuild");
            } else if (y == null) {
                out.add(n + ": only in the shipped pack");
            } else if (!x.equals(y)) {
                out.add(n + ": differs");
            }
        }
        return out;
    }

    /** True when the two packs' {@code pack.mcmeta} differ (the compiler-version stamp). */
    public static boolean stampDiffers(byte[] shipped, byte[] rebuilt) throws IOException {
        return !java.util.Objects.equals(entries(shipped).get("pack.mcmeta"), entries(rebuilt).get("pack.mcmeta"));
    }

    private static Map<String, String> functions(byte[] zip) throws IOException {
        Map<String, String> out = new TreeMap<>();
        entries(zip).forEach((name, text) -> {
            if (name.startsWith("data/") && (name.endsWith(".mcfunction") || name.endsWith(".json"))) {
                out.put(name, text.replace("\r\n", "\n").strip());
            }
        });
        return out;
    }

    private static Map<String, String> entries(byte[] zip) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (!e.isDirectory()) {
                    out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }
}
