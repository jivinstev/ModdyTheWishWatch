package com.moddingfromamod.pack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The mod ships nothing copied out of Minecraft</b> (the licence check; docs/public-repo/THIRD_PARTY_NOTICES.md:
 * "Moddy contains no Minecraft code or textures"). Read against the client jar of the pinned Minecraft, its
 * {@code assets/} and {@code data/} alike. A shipped file under {@code src/main/resources} is a COPY when:
 * <ol>
 *   <li>it is byte for byte a vanilla file (a texture, a sound, a model…), bigger than {@value #TRIVIAL} bytes;</li>
 *   <li>a JSON tree inside it equals a WHOLE vanilla JSON file of more than two values (a model with its elements,
 *   a recipe, a whole item definition file);</li>
 *   <li>a JSON tree inside it equals a vanilla item definition's {@code model} node that is not a REFERENCE;</li>
 *   <li>it embeds more than {@value #TABLE} vanilla item-definition nodes that are references but more than a bare
 *   name (more than two values): a table reproducing vanilla's definitions is a copy even when each entry is small.</li>
 * </ol>
 * <b>A REFERENCE</b> is a tree that holds no Minecraft content, only names: at most {@value #REFERENCE_VALUES} values,
 * at most {@value #REFERENCE_DEPTH} levels deep, and every leaf a resource id or keyword ({@code minecraft:dye},
 * {@code minecraft:item/cake}, {@code gui}), a number or a boolean. So the carrier's fallback
 * ({@code {"type":"minecraft:model","model":"minecraft:item/leather_horse_armor","tints":[{"type":"minecraft:dye",
 * "default":-6265536}]}}: an id, a tint type and a colour) is a reference, and so is potion's node of the same shape;
 * chest's node is not (a {@code select} on the date, with a {@code "MM-dd"} pattern), and neither is a model's geometry
 * (elements, faces, uv: dozens of values). A bare name ({@code {"type":"minecraft:model","model":"…"}}, two
 * values) never counts, so the hotbar badge's rule can write a thousand of them.
 *
 * <p>Skipped BY NAME where the client jar is not cached (a fresh checkout before the first build).
 */
class NoVanillaCopyTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    /** A file this small is a fact (an empty list, a one-line flag), not a copy. */
    static final int TRIVIAL = 80;

    /** A tree with at most this many values is a bare name; it never counts. */
    static final int BARE = 2;

    /** A reference holds at most this many values… */
    static final int REFERENCE_VALUES = 6;

    /** …and is at most this deep (object → tints array → tint object is 3). */
    static final int REFERENCE_DEPTH = 3;

    /** More reference-sized vanilla definitions than this in one file is a table of them: a copy. */
    static final int TABLE = 3;

    /** A leaf a reference may hold: a resource id, a tag, or a keyword. */
    private static final Pattern NAME = Pattern.compile("#?[a-z0-9_.\\-]+(:[a-z0-9_.\\-/]+)?");

    /** What vanilla ships, read once from the client jar. */
    record Index(Map<String, String> bytes, Map<String, String> files, Map<String, String> definitions) {
    }

    private static Index index;

    static Index index() throws Exception {
        if (index != null) {
            return index;
        }
        Path jar = HotbarBadgeTest.clientJar();
        Assumptions.assumeTrue(Files.exists(jar),
                "SKIPPED: no Minecraft client jar at " + jar + ", so there is nothing to compare against");
        Map<String, String> bytes = new HashMap<>();
        Map<String, String> files = new HashMap<>();
        Map<String, String> definitions = new HashMap<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            var entries = z.entries();
            while (entries.hasMoreElements()) {
                var e = entries.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !(n.startsWith("assets/") || n.startsWith("data/"))) {
                    continue;
                }
                byte[] b = z.getInputStream(e).readAllBytes();
                bytes.putIfAbsent(sha1(b), n);
                if (n.endsWith(".json") || n.endsWith(".mcmeta")) {
                    JsonElement root = parse(b);
                    if (root != null) {
                        if (values(root) > BARE) {
                            files.putIfAbsent(canonical(root), n);
                        }
                        if (n.startsWith("assets/minecraft/items/") && root.isJsonObject()
                                && root.getAsJsonObject().has("model")) {
                            JsonElement model = root.getAsJsonObject().get("model");
                            if (values(model) > BARE) {
                                definitions.putIfAbsent(canonical(model), n + "#model");
                            }
                        }
                    }
                }
            }
        }
        assertTrue(bytes.size() > 3000, "only " + bytes.size() + " vanilla files read");
        index = new Index(bytes, files, definitions);
        return index;
    }

    @Test
    @DisplayName("no shipped file is a vanilla asset or data file, and no shipped JSON embeds one")
    void nothingIsCopied() throws Exception {
        Index idx = index();
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(RESOURCES)) {
            for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                found.addAll(scan(p.toString(), Files.readAllBytes(p), idx));
            }
        }
        assertTrue(found.isEmpty(), found.size() + " copied out of Minecraft (first 20):\n  "
                + String.join("\n  ", found.subList(0, Math.min(20, found.size()))));
    }

    /** What is copied in one file: an empty list when nothing is. */
    static List<String> scan(String name, byte[] b, Index idx) throws Exception {
        List<String> found = new ArrayList<>();
        JsonElement json = name.endsWith(".json") || name.endsWith(".mcmeta") ? parse(b) : null;
        String same = idx.bytes().get(sha1(b));
        if (same != null && b.length > TRIVIAL && (json == null || values(json) > BARE)) {
            found.add(name + " IS " + same);
            return found;
        }
        if (json != null) {
            List<String> references = new ArrayList<>();
            walk(json, "$", name, idx, found, references);
            if (references.size() > TABLE) {
                found.add(name + " embeds " + references.size() + " vanilla item definitions, a table of them: "
                        + String.join(", ", references.subList(0, Math.min(5, references.size()))) + "…");
            }
        }
        return found;
    }

    private static void walk(JsonElement e, String at, String file, Index idx, List<String> found,
                             List<String> references) {
        if ((e.isJsonObject() || e.isJsonArray()) && values(e) > BARE) {
            String c = canonical(e);
            String whole = idx.files().get(c);
            if (whole != null) {
                found.add(file + " " + at + " IS the whole of " + whole);
                return;
            }
            String def = idx.definitions().get(c);
            if (def != null) {
                if (reference(e)) {
                    references.add(at + " = " + def);
                } else {
                    found.add(file + " " + at + " embeds " + def);
                }
                return;
            }
        }
        if (e.isJsonObject()) {
            for (var kv : e.getAsJsonObject().entrySet()) {
                walk(kv.getValue(), at + "." + kv.getKey(), file, idx, found, references);
            }
        } else if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) {
                walk(a.get(i), at + "[" + i + "]", file, idx, found, references);
            }
        }
    }

    /** A tree that only names things: small, shallow, and every leaf an id, a keyword, a number or a boolean. */
    static boolean reference(JsonElement e) {
        return values(e) <= REFERENCE_VALUES && depth(e) <= REFERENCE_DEPTH && namesOnly(e);
    }

    private static boolean namesOnly(JsonElement e) {
        if (e.isJsonObject()) {
            for (var kv : e.getAsJsonObject().entrySet()) {
                if (!namesOnly(kv.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                if (!namesOnly(x)) {
                    return false;
                }
            }
            return true;
        }
        if (e.isJsonNull()) {
            return true;
        }
        var p = e.getAsJsonPrimitive();
        return p.isNumber() || p.isBoolean() || NAME.matcher(p.getAsString()).matches();
    }

    static int values(JsonElement e) {
        if (e.isJsonObject()) {
            int n = 0;
            for (var kv : e.getAsJsonObject().entrySet()) {
                n += values(kv.getValue());
            }
            return n;
        }
        if (e.isJsonArray()) {
            int n = 0;
            for (JsonElement x : e.getAsJsonArray()) {
                n += values(x);
            }
            return n;
        }
        return 1;
    }

    private static int depth(JsonElement e) {
        int d = 0;
        if (e.isJsonObject()) {
            for (var kv : e.getAsJsonObject().entrySet()) {
                d = Math.max(d, depth(kv.getValue()));
            }
            return d + 1;
        }
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                d = Math.max(d, depth(x));
            }
            return d + 1;
        }
        return 0;
    }

    /** JSON with sorted keys and no whitespace, so a reformatted copy is still a copy. */
    static String canonical(JsonElement e) {
        if (e.isJsonObject()) {
            TreeMap<String, JsonElement> sorted = new TreeMap<>();
            for (var kv : e.getAsJsonObject().entrySet()) {
                sorted.put(kv.getKey(), kv.getValue());
            }
            StringBuilder sb = new StringBuilder("{");
            for (var kv : sorted.entrySet()) {
                sb.append(sb.length() > 1 ? "," : "").append('"').append(kv.getKey()).append("\":")
                  .append(canonical(kv.getValue()));
            }
            return sb.append('}').toString();
        }
        if (e.isJsonArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (JsonElement x : e.getAsJsonArray()) {
                sb.append(sb.length() > 1 ? "," : "").append(canonical(x));
            }
            return sb.append(']').toString();
        }
        return e.toString();
    }

    private static JsonElement parse(byte[] b) {
        try {
            return JsonParser.parseString(new String(b, StandardCharsets.UTF_8));
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    private static String sha1(byte[] b) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-1").digest(b)) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    // ── both sides of the rule, on real vanilla data ─────────────────────────────────────────────────

    private static JsonElement vanilla(String path) throws Exception {
        try (ZipFile z = new ZipFile(HotbarBadgeTest.clientJar().toFile())) {
            return parse(z.getInputStream(z.getEntry(path)).readAllBytes());
        }
    }

    private static List<String> scanJson(String name, JsonElement root) throws Exception {
        return scan(name, root.toString().getBytes(StandardCharsets.UTF_8), index());
    }

    @Test
    @DisplayName("PASSES: the carrier's fallback (an id, a tint type, a colour) is a reference, as shipped")
    void theCarrierFallbackIsAReference() throws Exception {
        Path carrier = RESOURCES.resolve("assets/minecraft/items/leather_horse_armor.json");
        JsonElement fallback = parse(Files.readAllBytes(carrier)).getAsJsonObject().getAsJsonObject("model").get("fallback");
        assertTrue(index().definitions().containsKey(canonical(fallback)),
                "the fallback no longer equals vanilla's leather armour node, so this case tests nothing");
        assertTrue(reference(fallback), fallback.toString());
        assertTrue(scan(carrier.toString(), Files.readAllBytes(carrier), index()).isEmpty());
    }

    @Test
    @DisplayName("PASSES: potion's definition node alone (an id, a tint type, a colour), the same shape as the carrier's")
    void aSmallTintedNodeAloneIsAReference() throws Exception {
        JsonElement potion = vanilla("assets/minecraft/items/potion.json").getAsJsonObject().get("model");
        JsonObject one = new JsonObject();
        one.add("minecraft:potion", potion);
        assertTrue(reference(potion), potion.toString());
        assertTrue(scanJson("one.json", one).isEmpty());
    }

    @Test
    @DisplayName("FAILS, said plainly: chest's node is NOT only names; it is a select on the date (a Christmas chest)")
    void aChestNodeAloneIsACopy() throws Exception {
        JsonElement chest = vanilla("assets/minecraft/items/chest.json").getAsJsonObject().get("model");
        assertFalse(reference(chest), chest.toString());
        JsonObject one = new JsonObject();
        one.add("minecraft:chest", chest);
        assertFalse(scanJson("one.json", one).isEmpty(), "chest's node pasted into a file of ours was not found");
    }

    @Test
    @DisplayName("FAILS: a vanilla model with its elements (geometry), embedded in a file of ours")
    void aModelWithElementsIsACopy() throws Exception {
        JsonElement cube = vanilla("assets/minecraft/models/block/cube.json");
        assertTrue(cube.getAsJsonObject().has("elements"));
        JsonObject ours = new JsonObject();
        ours.add("model", cube);
        assertFalse(scanJson("ours.json", ours).isEmpty(), "a vanilla model's geometry was not found");
    }

    @Test
    @DisplayName("FAILS: one whole vanilla item-definition file, embedded")
    void aWholeDefinitionFileIsACopy() throws Exception {
        JsonElement potion = vanilla("assets/minecraft/items/potion.json");
        JsonObject ours = new JsonObject();
        ours.add("minecraft:potion", potion);
        assertFalse(scanJson("ours.json", ours).isEmpty(), "a whole vanilla definition file was not found");
    }

    @Test
    @DisplayName("FAILS: a table of vanilla definitions, even of small ones (what this branch removed)")
    void aTableOfDefinitionsIsACopy() throws Exception {
        JsonObject table = new JsonObject();
        // four reference-sized definitions, each of which passes alone
        for (String id : List.of("potion", "splash_potion", "lingering_potion", "tipped_arrow")) {
            table.add("minecraft:" + id, vanilla("assets/minecraft/items/" + id + ".json").getAsJsonObject().get("model"));
        }
        List<String> found = scanJson("items/hotbar-vanilla.json", table);
        for (String id : List.of("potion", "splash_potion", "lingering_potion", "tipped_arrow")) {
            assertTrue(reference(table.get("minecraft:" + id)), id + " is not reference-sized, so this case tests the wrong rule");
        }
        assertFalse(found.isEmpty(), "a table of four small vanilla definitions was not found");
    }

    @Test
    @DisplayName("FAILS: a big definition (a select over display contexts) even alone")
    void aBigDefinitionIsACopy() throws Exception {
        JsonElement bundle = vanilla("assets/minecraft/items/bundle.json").getAsJsonObject().get("model");
        assertFalse(reference(bundle), bundle.toString());
        JsonObject ours = new JsonObject();
        ours.add("minecraft:bundle", bundle);
        assertFalse(scanJson("ours.json", ours).isEmpty());
    }

    @Test
    @DisplayName("FAILS: a vanilla file byte for byte (a texture)")
    void aFileByteForByteIsACopy() throws Exception {
        byte[] png;
        try (ZipFile z = new ZipFile(HotbarBadgeTest.clientJar().toFile())) {
            png = z.getInputStream(z.getEntry("assets/minecraft/textures/item/cake.png")).readAllBytes();
        }
        assertFalse(scan("cake.png", png, index()).isEmpty());
    }
}
