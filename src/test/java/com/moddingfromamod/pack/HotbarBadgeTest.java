package com.moddingfromamod.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The Moddy badge on a hotbar wish reaches BOTH providers, and only a proxy draws it.</b>
 *
 * <p>A stock client reads the pushed pack ({@link HotbarBadge} writes it), a modded one the jar
 * ({@code tools/gen-hotbar-badge.py} and {@code tools/gen-carrier-model.py} write it). A badge in one and not the
 * other is a wish that is marked on one client and looks like the thing itself on the other.
 */
class HotbarBadgeTest {

    private static final Path JAR = Path.of("src/main/resources");

    private static Map<String, String> pack() {
        return VanillaItemPack.textFiles(new VanillaItemPack.Spec(WishPackWriter.BASE_ITEM,
                WishPackWriter.icons("on"), WishPackWriter.atoms("on")));
    }

    private static JsonElement json(String s) {
        return JsonParser.parseString(s);
    }

    private static String jar(String path) throws Exception {
        return Files.readString(JAR.resolve(path), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the vanilla-picture definition is the same in the pack and the jar, and draws every vanilla item")
    void theVanillaDefinitionAgrees() throws Exception {
        String packDef = pack().get(HotbarBadge.DEFINITION_PATH);
        assertNotNull(packDef, "the pushed pack has no " + HotbarBadge.DEFINITION_PATH);
        assertEquals(json(packDef.replace(VanillaItemPack.NAMESPACE + ":item/hotbar/", "moddingfromamod:item/hotbar/")),
                json(jar(HotbarBadge.DEFINITION_PATH)),
                "the jar's hotbar definition differs from the pack's. Run tools/gen-hotbar-badge.py");
        assertTrue(HotbarBadge.vanillaCount() > 1000, "only " + HotbarBadge.vanillaCount() + " vanilla pictures by rule");
        for (String id : List.of("minecraft:cake", "minecraft:stone", "minecraft:oak_fence", "minecraft:oak_door")) {
            assertTrue(HotbarBadge.drawsVanilla(id), id + " is not drawn by rule");
        }
        for (String id : List.of("minecraft:potion", "minecraft:chest", "minecraft:grass_block")) {
            assertFalse(HotbarBadge.drawsVanilla(id), id + " is drawn by rule, but its definition is tinted or special");
            assertTrue(HotbarBadge.exceptions().contains(id), id + " is not on the exceptions list");
        }
        assertFalse(HotbarBadge.drawsVanilla(HotbarBadge.ITEM_MODEL), "the hotbar definition must not draw itself");
        JsonObject select = select(json(packDef).getAsJsonObject());
        assertEquals(HotbarBadge.VANILLA_INDEX, select.get("index").getAsInt());
        assertEquals(HotbarBadge.vanillaCount(), select.getAsJsonArray("cases").size());
    }

    private static JsonObject select(JsonObject definition) {
        JsonObject found = null;
        for (JsonElement m : definition.getAsJsonObject("model").getAsJsonArray("models")) {
            if ("minecraft:select".equals(m.getAsJsonObject().get("type").getAsString())) {
                found = m.getAsJsonObject();
            }
        }
        assertNotNull(found);
        return found;
    }

    @Test
    @DisplayName("every vanilla entry is the RULE's: a bare model reference it names, nothing copied")
    void everyEntryIsTheRule() throws Exception {
        for (JsonElement c : select(json(jar(HotbarBadge.DEFINITION_PATH)).getAsJsonObject()).getAsJsonArray("cases")) {
            String id = c.getAsJsonObject().get("when").getAsString();
            JsonObject m = c.getAsJsonObject().getAsJsonObject("model");
            assertEquals(java.util.Set.of("type", "model"), m.keySet(), id + ": an entry is a bare reference");
            String model = m.get("model").getAsString();
            boolean block = model.startsWith("minecraft:block/") || HotbarBadge.flatBlocks().contains(id);
            assertEquals(HotbarBadge.ruleModel(id, block, HotbarBadge.flatBlocks().contains(id)), model, id);
            assertFalse(HotbarBadge.exceptions().contains(id), id + " is an exception and drawn anyway");
        }
    }

    /** The client jar of the pinned Minecraft, as the build writes it (build.gradle's additionalMinecraftArtifacts); READ only, never committed. */
    static Path clientJar() {
        String override = System.getenv("MODDY_CLIENT_JAR");   // another cache, or a path that is not there (the skip)
        return Path.of(override != null && !override.isBlank() ? override
                : "build/minecraft/client-1.21.8.jar");
    }

    @Test
    @DisplayName("the flat list and the exceptions are fresh for the pinned Minecraft (skipped by name without its client jar)")
    void theIdListsAreFresh() throws Exception {
        Path jar = clientJar();
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(jar),
                "SKIPPED: no Minecraft client jar at " + jar + " (a fresh checkout before the first build); the rule "
                        + "cannot be measured against the real definitions here");
        java.util.Set<String> flat = new java.util.TreeSet<>();
        java.util.Set<String> skip = new java.util.TreeSet<>();
        java.util.Set<String> drawn = new java.util.TreeSet<>();
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar.toFile())) {
            java.util.Set<String> names = new java.util.HashSet<>();
            z.stream().forEach(e -> names.add(e.getName()));
            for (String n : new java.util.TreeSet<>(names)) {
                if (!n.startsWith("assets/minecraft/items/") || !n.endsWith(".json")) {
                    continue;
                }
                String path = n.substring("assets/minecraft/items/".length(), n.length() - 5);
                String id = "minecraft:" + path;
                JsonObject model = json(new String(z.getInputStream(z.getEntry(n)).readAllBytes(),
                        StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("model");
                boolean block = names.contains("assets/minecraft/blockstates/" + path + ".json");
                if (model.equals(ref(HotbarBadge.ruleModel(id, block, false)))) {
                    drawn.add(id);
                } else if (block && model.equals(ref(HotbarBadge.ruleModel(id, true, true)))) {
                    flat.add(id);
                    drawn.add(id);
                } else {
                    skip.add(id);
                }
            }
        }
        assertEquals(flat, new java.util.TreeSet<>(HotbarBadge.flatBlocks()),
                "the flat list is stale for this Minecraft. Run tools/gen-hotbar-badge.py");
        assertEquals(skip, new java.util.TreeSet<>(HotbarBadge.exceptions()),
                "the exceptions are stale for this Minecraft: a picture the rule gets wrong draws the missing-model "
                        + "checkerboard. Run tools/gen-hotbar-badge.py");
        java.util.Set<String> committed = new java.util.TreeSet<>();
        for (JsonElement c : select(json(jar(HotbarBadge.DEFINITION_PATH)).getAsJsonObject()).getAsJsonArray("cases")) {
            committed.add(c.getAsJsonObject().get("when").getAsString());
        }
        assertEquals(drawn, committed, "the hotbar definition is stale. Run tools/gen-hotbar-badge.py");
    }

    private static JsonObject ref(String model) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "minecraft:model");
        o.addProperty("model", model);
        return o;
    }

    @Test
    @DisplayName("the badge's sprites and models ship in the jar and the pack, for the look the table was made for")
    void theBadgeShips() throws Exception {
        assertFalse(HotbarBadge.sprites().isEmpty());
        Map<String, String> files = pack();
        for (String sprite : List.of(HotbarBadge.BACK, HotbarBadge.FRONT)) {
            boolean used = HotbarBadge.sprites().contains(sprite);
            Path png = JAR.resolve("assets/moddingfromamod/textures/item/" + sprite + ".png");
            assertEquals(used, Files.exists(png), sprite + ": the jar's sprites are not this look's ("
                    + HotbarBadge.look() + "). Run tools/gen-hotbar-badge.py");
            if (!used) {
                continue;
            }
            assertTrue(Files.exists(Path.of(png + ".mcmeta")), sprite + " animates like the watch: its .mcmeta");
            assertEquals(json(HotbarBadge.model("moddingfromamod", sprite)),
                    json(jar("assets/moddingfromamod/models/item/" + sprite + ".json")));
            assertEquals(json(HotbarBadge.model(VanillaItemPack.NAMESPACE, sprite)),
                    json(files.get(VanillaItemPack.extraModelPath(sprite))));
        }
    }

    @Test
    @DisplayName("the pushed pack carries the badge's texture")
    void thePackCarriesTheTexture() throws Exception {
        Path dir = Files.createTempDirectory("hotbar-badge-pack");
        WishPackWriter.write("on", dir);
        for (String sprite : HotbarBadge.sprites()) {
            Path p = dir.resolve(VanillaItemPack.texturePath(sprite));
            assertTrue(Files.exists(p), "the pack has no " + p);
            assertTrue(Files.exists(Path.of(p + ".mcmeta")), "the pack's " + sprite + " has no .mcmeta: it would "
                    + "draw as the whole frame strip squashed into one slot");
        }
    }

    @Test
    @DisplayName("every composed icon draws the badge only when flags[0] is set, in the pack and the jar alike")
    void everyIconIsFlagged() throws Exception {
        Map<String, String> files = pack();
        int composed = 0;
        for (NativeItemModels.Binding b : NativeItemModels.all()) {
            JsonObject packDef = json(files.get(NativeItemModels.definitionPath(b.name()))).getAsJsonObject()
                    .getAsJsonObject("model");
            JsonObject jarDef = json(jar(NativeItemModels.definitionPath(b.name()))).getAsJsonObject()
                    .getAsJsonObject("model");
            List<String> packBadges = badges(packDef);
            List<String> jarBadges = badges(jarDef);
            boolean icon = "minecraft:composite".equals(packDef.get("type").getAsString());
            List<String> want = new ArrayList<>();
            if (icon) {
                composed++;
                for (String s : HotbarBadge.sprites()) {
                    want.add(s);
                }
            }
            assertEquals(want, packBadges.stream().map(m -> m.substring(m.indexOf(":item/") + 6)).toList(),
                    b.name() + " (pack)");
            assertEquals(want, jarBadges.stream().map(m -> m.substring(m.indexOf(":item/") + 6)).toList(),
                    b.name() + " (jar). Run tools/gen-carrier-model.py");
        }
        assertTrue(composed > 100, "only " + composed + " composed icons");
    }

    /** The badge models an icon's composite draws under the flag, in order. */
    private static List<String> badges(JsonObject model) {
        List<String> out = new ArrayList<>();
        if (!"minecraft:composite".equals(model.get("type").getAsString())) {
            return out;
        }
        for (JsonElement e : model.getAsJsonArray("models")) {
            JsonObject m = e.getAsJsonObject();
            if (!"minecraft:condition".equals(m.get("type").getAsString())) {
                continue;
            }
            assertEquals("minecraft:custom_model_data", m.get("property").getAsString());
            assertEquals(HotbarBadge.FLAG_INDEX, m.get("index").getAsInt());
            assertEquals("minecraft:empty", m.getAsJsonObject("on_false").get("type").getAsString(),
                    "an icon in the history (no flag) must draw nothing extra");
            out.add(m.getAsJsonObject("on_true").get("model").getAsString());
        }
        return out;
    }
}
