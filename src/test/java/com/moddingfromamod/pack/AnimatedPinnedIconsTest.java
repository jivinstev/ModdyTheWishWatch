package com.moddingfromamod.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.imageio.ImageIO;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.datapack.VanillaCarrier;
import com.moddingfromamod.vanilla.ItemPackService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * <b>: Moddy and the Wish Bag animate for a STOCK client, on both bindings.</b>
 *
 * <p>Each is a 16-frame vertical strip plus a {@code .png.mcmeta}. Without the sidecar a client does
 * not refuse the texture — it draws all sixteen frames squashed into one slot, a tall smear and no
 * error — so this walks the pack the server PUSHES ({@code ItemPackService.buildPack}) from the
 * carrier's number to the texture, by each route a 1.21.4+ client takes (the 1.21.1 overrides route
 * went step 3 — older clients are refused at join):
 *
 * <ul>
 *   <li><b>1.21.4+, an old stack</b>: the carrier's {@code range_dispatch} entry → a model → its layers;</li>
 *   <li><b>1.21.4+, the stack we mint</b>: its {@code item_model} ({@code NativeItemModels}) → the
 *       pack's own {@code items/icon/<name>.json} → a model → its layers;</li>
 * </ul>
 *
 * <p>and requires that both reach the strip, that the strip is whole frames, and that its
 * {@code .png.mcmeta} rides beside it in the pack with the configured frame time.
 */
class AnimatedPinnedIconsTest {

    private static final String SRC = "src/main/resources/assets/moddingfromamod/textures/item/";

    @ParameterizedTest(name = "{0}")
    @CsvSource({"wish_watch, 1000, 2", "wish_bag, 1002, 3"})
    @DisplayName("the pushed pack carries the animated strip and its .mcmeta, on both bindings")
    void bothBindingsReachTheAnimatedStrip(String sprite, int cmd, int frametime, @TempDir Path tmp)
            throws Exception {
        assertEquals(cmd, "wish_watch".equals(sprite) ? VanillaCarrier.WATCH_MODEL_DATA
                : VanillaCarrier.WISH_BAG_MODEL_DATA, "the numbers this test walks from drifted");
        Path pack = tmp.resolve("items");
        ItemPackService.buildPack(pack);

        Set<String> neu = texturesOf(pack, modelsFromDefinition(
                read(pack.resolve(VanillaItemPack.itemDefinitionPath(WishPackWriter.BASE_ITEM))), cmd));
        String itemModel = NativeItemModels.forNumber(cmd);       // "wishpack:icon/wish_bag"
        assertTrue(itemModel != null, "no native binding for " + cmd);
        String[] im = itemModel.split(":", 2);
        Path nativeDef = pack.resolve("assets/" + im[0] + "/items/" + im[1] + ".json");
        assertTrue(Files.exists(nativeDef), "the pushed pack has no native definition " + nativeDef);
        List<String> nativeModels = new ArrayList<>();
        collectModelIds(read(nativeDef).get("model"), nativeModels);
        Set<String> nat = texturesOf(pack, nativeModels);
        String want = VanillaItemPack.textureId(sprite);
        assertTrue(nat.contains(want), "the item_model route for " + cmd + " reaches " + nat + ", not " + want);
        assertTrue(neu.contains(want), "1.21.4's dispatch for " + cmd + " reaches " + neu + ", not " + want);

        Path png = pack.resolve(VanillaItemPack.texturePath(sprite));
        Path meta = pack.resolve(VanillaItemPack.texturePath(sprite) + ".mcmeta");
        assertTrue(Files.exists(png), "the pack has no " + png);
        assertTrue(Files.exists(meta), sprite + "'s strip is pushed WITHOUT its .png.mcmeta, so a stock "
                + "client draws all sixteen frames squashed into one slot");
        JsonObject anim = read(meta).getAsJsonObject("animation");
        assertEquals(frametime, anim.get("frametime").getAsInt(), "the configured frame time");

        BufferedImage img = ImageIO.read(png.toFile());
        assertEquals(32, img.getWidth(), "a 32-px sprite");
        assertEquals(16 * img.getWidth(), img.getHeight(), "sixteen whole square frames");
        assertTrue(java.util.Arrays.equals(Files.readAllBytes(png), Files.readAllBytes(Path.of(SRC + sprite + ".png"))),
                "the pushed strip is not the mod's own bytes");
    }

    private static List<String> modelsFromDefinition(JsonObject def, int cmd) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : def.getAsJsonObject("model").getAsJsonArray("entries")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("threshold").getAsInt() == cmd) {
                collectModelIds(o.get("model"), out);
            }
        }
        return out;
    }

    /** Every {@code "model": "<id>"} string under a node — a plain model, a composite, a condition. */
    private static void collectModelIds(JsonElement node, List<String> out) {
        if (node.isJsonObject()) {
            for (var kv : node.getAsJsonObject().entrySet()) {
                if (kv.getKey().equals("model") && kv.getValue().isJsonPrimitive()) {
                    out.add(kv.getValue().getAsString());
                } else {
                    collectModelIds(kv.getValue(), out);
                }
            }
        } else if (node.isJsonArray()) {
            node.getAsJsonArray().forEach(n -> collectModelIds(n, out));
        }
    }

    /** The texture ids the named models draw, read from the model files IN THE PACK. */
    private static Set<String> texturesOf(Path pack, List<String> modelIds) throws Exception {
        assertFalse(modelIds.isEmpty(), "no model is bound at that number");
        Set<String> out = new TreeSet<>();
        for (String id : modelIds) {
            String[] ns = id.split(":", 2);
            Path model = pack.resolve("assets/" + ns[0] + "/models/" + ns[1] + ".json");
            assertTrue(Files.exists(model), "the pack binds " + id + " but does not carry " + model);
            JsonObject textures = read(model).getAsJsonObject("textures");
            if (textures != null) {
                textures.entrySet().forEach(t -> out.add(t.getValue().getAsString()));
            }
        }
        return out;
    }

    private static JsonObject read(Path p) throws Exception {
        return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
