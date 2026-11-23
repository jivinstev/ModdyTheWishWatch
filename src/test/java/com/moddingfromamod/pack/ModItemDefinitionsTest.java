package com.moddingfromamod.pack;

import com.moddingfromamod.testing.SourceRoots;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Every item the mod registers has a 1.21.4 definition in the jar.</b>
 *
 * <p>1.21.4 draws every item through {@code assets/<ns>/items/<id>.json}, including one registered in
 * Java. Nothing wrote one for ours, so a client that HAS the mod on 1.21.4 logged
 * {@code No model loaded for default item ID moddingfromamod:wish_watch} and drew Moddy's watch (and the
 * wish egg, the paint panel, the legacy wish item) as the magenta missing cube. It was photographed in
 * Its Gate D runs as hotbar slot 1. A stock child never holds these ids, since he gets the vanilla
 * carrier, so no stock-client gate could see it; and the jar had no 1.21.4 half at all because its
 * generator assumed a modded client was "by construction on 1.21.1", which T11 made false.
 *
 * <p>The ids are READ from the registration source, the same way {@code tools/gen-carrier-model.py}
 * reads them, so a new item cannot ship without a definition: this fails by name.
 */
class ModItemDefinitionsTest {

    private static final Path JAVA = Path.of("src/main/java/com/moddingfromamod");
    private static final Path ITEMS = Path.of("src/main/resources/assets/moddingfromamod/items");
    private static final Path MODELS = Path.of("src/main/resources/assets/moddingfromamod/models");

    private static final Pattern REGISTER_ITEM = Pattern.compile("registerItem\\(\\s*\"([a-z0-9_]+)\"");
    private static final Pattern BLOCK_ITEM =
            Pattern.compile("registerSimpleBlockItem\\(\\s*([A-Za-z_\"0-9]+)");
    private static final Pattern CONST =
            Pattern.compile("static final String ([A-Z_]+)\\s*=\\s*\"([a-z0-9_]+)\"");

    static TreeSet<String> registeredItemIds() throws IOException {
        TreeSet<String> ids = new TreeSet<>();
        List<Path> files;
        try (Stream<Path> s = SourceRoots.walk(JAVA)) {
            files = s.filter(p -> p.toString().endsWith(".java")).toList();
        }
        for (Path f : files) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            Matcher m = REGISTER_ITEM.matcher(src);
            while (m.find()) {
                ids.add(m.group(1));
            }
            Map<String, String> consts = new java.util.HashMap<>();
            Matcher c = CONST.matcher(src);
            while (c.find()) {
                consts.put(c.group(1), c.group(2));
            }
            Matcher b = BLOCK_ITEM.matcher(src);
            while (b.find()) {
                String tok = b.group(1);
                ids.add(tok.startsWith("\"") ? tok.replace("\"", "") : consts.getOrDefault(tok, tok));
            }
        }
        return ids;
    }

    @Test
    @DisplayName("the registration scan finds the items we know exist (it can fire)")
    void theScanFindsTheKnownItems() throws IOException {
        TreeSet<String> ids = registeredItemIds();
        assertTrue(ids.containsAll(List.of("wish_watch", "wish_egg", "wish_item", "paint_panel")),
                "the scan missed a registered item, so the test below would pass over it: " + ids);
    }

    @Test
    @DisplayName("every registered mod item has items/<id>.json naming a model the jar ships")
    void everyItemHasADefinition() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String id : registeredItemIds()) {
            Path def = ITEMS.resolve(id + ".json");
            if (!Files.exists(def)) {
                missing.add(id + ": no " + def + " (1.21.4 draws it as the magenta missing cube)");
                continue;
            }
            JsonObject model = JsonParser.parseString(Files.readString(def, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("model");
            // A range_dispatch keeps its base model in `fallback`; a plain definition names it.
            JsonObject base = model.has("fallback") ? model.getAsJsonObject("fallback") : model;
            String ref = base.get("model").getAsString();
            String path = ref.substring(ref.indexOf(':') + 1);
            if (!ref.startsWith("moddingfromamod:") || !Files.exists(MODELS.resolve(path + ".json"))) {
                missing.add(id + ": its definition names " + ref + ", which the jar does not ship");
            }
        }
        assertTrue(missing.isEmpty(), "run tools/gen-carrier-model.py:\n  " + String.join("\n  ", missing));
    }

    @Test
    @DisplayName("the watch is not tinted: Moddy never renders undyed-leather brown")
    void theWatchIsUntinted() throws IOException {
        String watch = Files.readString(ITEMS.resolve("wish_watch.json"), StandardCharsets.UTF_8);
        assertFalse(watch.contains("minecraft:dye"),
                "a dye tint on the watch multiplies its face by the carrier's brown default");
    }
}
