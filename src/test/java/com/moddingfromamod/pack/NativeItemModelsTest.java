package com.moddingfromamod.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.compat.ModelData;
import com.moddingfromamod.item.IconSprites;
import com.moddingfromamod.vanilla.AtomLibrary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A stack names its own picture, and the name draws what the number drew.</b>
 *
 * <p>Three providers have to agree for a stack to look the same by name as by number: the pushed
 * pack (a stock client), the jar (a modded client), and the carrier's range_dispatch table (every
 * stack minted before this). Each is asserted here by RESOLUTION — what model a given stack reaches
 * — rather than by counting files, because a definition that points at a neighbour's model is a
 * file that exists and draws the wrong thing.
 */
class NativeItemModelsTest {

    private static final Path JAR = Path.of("src/main/resources");

    private static Map<String, String> pack() {
        return VanillaItemPack.textFiles(new VanillaItemPack.Spec(
                WishPackWriter.BASE_ITEM,
                WishPackWriter.icons("on"), WishPackWriter.atoms("on")));
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    /** 1.21.4's RangeSelectItemModel: the largest threshold at or below the value. */
    private static JsonObject rangeDispatch(JsonObject definition, float value) {
        JsonObject model = definition.getAsJsonObject("model");
        JsonObject hit = model.getAsJsonObject("fallback");
        JsonArray entries = model.getAsJsonArray("entries");
        for (int i = 0; i < entries.size(); i++) {
            JsonObject e = entries.get(i).getAsJsonObject();
            if (e.get("threshold").getAsFloat() <= value) {
                hit = e.getAsJsonObject("model");
            }
        }
        return hit;
    }

    /**
     * An ICON's definition is a composite whose FIRST layer is what the number draws and
     * whose second is the overlay select; anything else is its own base. The base is what has to
     * agree with the number table — the overlay layer draws nothing on a stack with no string.
     */
    private static JsonObject baseOf(JsonObject model) {
        if (!"minecraft:composite".equals(model.get("type").getAsString())) {
            return model;
        }
        JsonArray layers = model.getAsJsonArray("models");
        assertEquals(IconNodes.PICTURE + 2, layers.size(), model.toString());
        JsonObject select = layers.get(layers.size() - 1).getAsJsonObject();
        assertEquals("minecraft:select", select.get("type").getAsString());
        assertEquals("minecraft:empty", select.getAsJsonObject("fallback").get("type").getAsString(),
                "a stack with no overlay string must draw NOTHING over its icon");
        return IconNodes.picture(model);
    }

    /** The overlay names an icon's composite selects on, in order. */
    private static List<String> overlayCases(JsonObject model, String ns, java.util.function.Predicate<String> exists) {
        List<String> out = new ArrayList<>();
        JsonArray models = model.getAsJsonArray("models");
        JsonArray cases = models.get(models.size() - 1).getAsJsonObject().getAsJsonArray("cases");
        for (int i = 0; i < cases.size(); i++) {
            JsonObject c = cases.get(i).getAsJsonObject();
            String name = c.get("when").getAsString();
            String m = c.getAsJsonObject("model").get("model").getAsString();
            assertEquals(ns + ":item/overlay/" + name, m);
            assertTrue(exists.test(m), "overlay " + name + " names " + m + ", which does not exist");
            out.add(name);
        }
        return out;
    }

    @Test
    @DisplayName("every ICON is composed with the whole overlay library, in the pack and the jar alike")
    void everyIconIsComposed() throws Exception {
        Map<String, String> files = pack();
        int icons = 0;
        for (NativeItemModels.Binding b : NativeItemModels.all()) {
            JsonObject packDef = json(files.get(NativeItemModels.definitionPath(b.name()))).getAsJsonObject("model");
            JsonObject jarDef = json(Files.readString(JAR.resolve(NativeItemModels.definitionPath(b.name())),
                    StandardCharsets.UTF_8)).getAsJsonObject("model");
            JsonObject jarBase = baseOf(jarDef);
            boolean icon = "minecraft:composite".equals(jarDef.get("type").getAsString());
            assertEquals(icon, "minecraft:composite".equals(packDef.get("type").getAsString()),
                    b.name() + ": icons are composed and nothing else is (an atom cube never wears an overlay)");
            assertEquals(icon, "minecraft:composite".equals(jarDef.get("type").getAsString()),
                    b.name() + " is composed in one provider and not the other. Run tools/gen-carrier-model.py");
            if (!icon) {
                continue;
            }
            icons++;
            assertEquals(com.moddingfromamod.item.IconOverlays.NAMES,
                    overlayCases(packDef, VanillaItemPack.NAMESPACE,
                            m -> files.containsKey(VanillaItemPack.extraModelPath(m.substring(m.indexOf(':') + 6)))));
            assertEquals(com.moddingfromamod.item.IconOverlays.NAMES,
                    overlayCases(jarDef, "moddingfromamod",
                            m -> Files.exists(JAR.resolve("assets/moddingfromamod/models/item/"
                                    + m.substring(m.indexOf(':') + 6) + ".json"))));
        }
        long wishSprites = WishPackWriter.icons("on").stream()
                .filter(i -> i.layers().get(0).startsWith("wish/")).count();
        assertEquals(wishSprites, icons, "every icon a wish may pick is composed, and only those");
        assertTrue(icons > 0);
    }

    private static String definitionPathOf(String itemModelId) {
        String[] ns = itemModelId.split(":", 2);
        return "assets/" + ns[0] + "/items/" + ns[1] + ".json";
    }

    @Test
    @DisplayName("every number the carrier table binds has a native twin — icons, Moddy and every atom")
    void everyBindingHasANativeId() {
        List<Integer> numbers = new ArrayList<>();
        for (IconSprites.Sprite s : IconSprites.bindings()) {
            numbers.add(s.customModelData());
        }
        for (AtomLibrary.Entry e : AtomLibrary.all()) {
            numbers.add(e.customModelData());
        }
        assertEquals(numbers.size(), NativeItemModels.all().size());
        for (int n : numbers) {
            String id = NativeItemModels.forNumber(n);
            assertNotNull(id, "custom_model_data " + n + " is bound on the carrier and has no item_model");
            assertTrue(id.startsWith(NativeItemModels.NAMESPACE + ":" + NativeItemModels.DIR + "/"), id);
        }
        assertNull(NativeItemModels.forNumber(0), "0 is a stack with no number, never a binding");
        assertNull(NativeItemModels.forNumber(500), "an unbound number must not be given a picture");
    }

    @Test
    @DisplayName("the PUSHED pack: each native definition draws exactly what range_dispatch drew for that number")
    void thePackAgreesWithItself() {
        Map<String, String> files = pack();
        JsonObject carrier = json(files.get(
                VanillaItemPack.itemDefinitionPath(WishPackWriter.BASE_ITEM)));
        for (NativeItemModels.Binding b : NativeItemModels.all()) {
            String path = definitionPathOf(NativeItemModels.itemModelId(b.name()));
            assertEquals(NativeItemModels.definitionPath(b.name()), path);
            String def = files.get(path);
            assertNotNull(def, "the pushed pack has no " + path + ", so a stock 1.21.4 client draws a "
                    + "stack naming it as the magenta missing model");
            // ONE node serves both tables, so the whole node — backing, picture and
            // overlay — is what must agree, not just the picture.
            baseOf(json(def).getAsJsonObject("model"));
            assertEquals(rangeDispatch(carrier, b.customModelData()),
                    json(def).getAsJsonObject("model"),
                    b.name() + " (" + b.customModelData() + ") draws differently by name than by number");
        }
    }

    @Test
    @DisplayName("the JAR: its native definitions and its carrier range_dispatch both draw each binding's own model")
    void theJarDrawsEachBindingsModel() throws Exception {
        // the bindings are the runtime's table (IconSprites + AtomLibrary), not the jar's
        // pre-1.21.4 overrides model, which is gone.
        Map<Integer, String> bound = new java.util.LinkedHashMap<>();
        for (IconSprites.Sprite s : IconSprites.bindings()) {
            bound.put(s.customModelData(), "moddingfromamod:item/" + s.model());
        }
        for (AtomLibrary.Entry e : AtomLibrary.all()) {
            bound.put(e.customModelData(), "moddingfromamod:item/" + e.modelName());
        }
        JsonObject carrier = json(Files.readString(
                JAR.resolve("assets/minecraft/items/leather_horse_armor.json"), StandardCharsets.UTF_8));
        assertEquals(NativeItemModels.all().size(), bound.size());
        assertEquals(bound.size(), carrier.getAsJsonObject("model").getAsJsonArray("entries").size(),
                "the jar binds a different set of numbers than NativeItemModels. Run tools/gen-carrier-model.py");
        for (Map.Entry<Integer, String> o : bound.entrySet()) {
            int n = o.getKey();
            String model = o.getValue();
            String id = NativeItemModels.forNumber(n);
            assertNotNull(id, "the jar binds " + n + " and NativeItemModels does not");
            Path def = JAR.resolve(definitionPathOf(id));
            assertTrue(Files.exists(def), "the jar has no " + def + ". Run tools/gen-carrier-model.py");
            assertEquals(IconNodes.modernFor(model), baseOf(json(Files.readString(def, StandardCharsets.UTF_8))
                    .getAsJsonObject("model")).get("model").getAsString(),
                    "a modded client draws " + id + " as a different model than the number " + n);
            assertEquals(IconNodes.modernFor(model), IconNodes.pictureModel(rangeDispatch(carrier, n)),
                    "an OLD stack at " + n + " on a modded 1.21.4 client reaches the wrong model");
        }
    }

    @Test
    @DisplayName("native ids never collide with the mod's own items/<id>.json defaults")
    void nativeIdsStayOutOfTheModsOwnItemIds() {
        for (NativeItemModels.Binding b : NativeItemModels.all()) {
            assertFalse(NativeItemModels.itemModelId(b.name()).startsWith("moddingfromamod:"),
                    "a native id in the mod's namespace can become one of the mod's own items' defaults");
        }
    }

    // ── the text choke point ──────────────────────────────────────────────────────────────────

    private static String dialect(String text) {
        return ModelData.nameTheModel(ModelData.correctSpelling(text, new ArrayList<>()), new ArrayList<>());
    }

    @Test
    @DisplayName("a give line gains its item_model on 1.21.4; the 1.21.4 spelling is dropped, loudly, on 1.21.1")
    void aGiveLineNamesItsModel() {
        int cup = IconSprites.numberFor("cup");
        String give = "give @a[tag=kid] minecraft:leather_horse_armor[minecraft:custom_model_data="
                + ModelData.snbt(cup) + ",minecraft:dyed_color={rgb:255},"
                + "minecraft:custom_name='{\"text\":\"Hot [Coffee]\"}'] 1";
        String out = dialect(give);
        if (ModelData.NATIVE_ITEM_MODEL) {
            assertTrue(out.contains("minecraft:item_model='wishpack:icon/cup'"), out);
            assertEquals(out, dialect(out), "the rewrite must be idempotent — a stored line replays");
        } else {
            assertEquals(give, out, "1.21.1 has no item_model, and nothing to add");
            List<String> notes = new ArrayList<>();
            String native_ = "give @s leather_horse_armor[minecraft:custom_model_data=3,"
                    + "minecraft:item_model='wishpack:icon/cup'] 1";
            String back = ModelData.nameTheModel(native_, notes);
            assertFalse(back.contains("item_model"), back);
            assertFalse(notes.isEmpty(), "a dropped component must be NOTED, never silent");
        }
    }

    @Test
    @DisplayName("a predicate is never touched — it would stop matching every stack a child already holds")
    void aPredicateIsLeftAlone() {
        int cup = IconSprites.numberFor("cup");
        String clear = "clear @s minecraft:leather_horse_armor[minecraft:custom_model_data="
                + ModelData.snbt(cup) + "] 1";
        assertEquals(clear, dialect(clear));
        String test = "execute if items entity @s weapon.mainhand leather_horse_armor[custom_model_data="
                + ModelData.snbt(cup) + "] run say hi";
        assertEquals(test, dialect(test));
    }

    @Test
    @DisplayName("a give that already names a model, or an unbound number, is left exactly as written")
    void itNeverSecondGuesses() {
        String own = "give @s leather_horse_armor[custom_model_data=" + ModelData.snbt(3)
                + ",item_model='minecraft:diamond'] 1";
        if (ModelData.NATIVE_ITEM_MODEL) {
            assertEquals(own, dialect(own));
            String unbound = "give @s leather_horse_armor[custom_model_data=" + ModelData.snbt(500) + "] 1";
            assertEquals(unbound, dialect(unbound));
        }
    }

    @Test
    @DisplayName("the stamp as text carries both components on 1.21.4 and only the number on 1.21.1")
    void theTextStamp() {
        String c = ModelData.components(IconSprites.numberFor("wand"));
        assertTrue(c.startsWith("minecraft:custom_model_data=" + ModelData.snbt(IconSprites.numberFor("wand"))), c);
        assertEquals(ModelData.NATIVE_ITEM_MODEL, c.contains("item_model='wishpack:icon/wand'"), c);
    }
}
