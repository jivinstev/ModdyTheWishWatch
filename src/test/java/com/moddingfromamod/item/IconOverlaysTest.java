package com.moddingfromamod.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.moddingfromamod.compat.ModelData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The overlay a wished item wears, and the library it is drawn from.</b>
 *
 * <p>Three things have to agree for an overlay to draw: the NAME the stack carries
 * ({@link IconOverlays}), the closed vocabulary the corpus is labelled with (the parts
 * coverage tool's {@code OVERLAYS}, checked by {@code IconOverlayVocabularyTest}), and the SPRITE the pack ships. A name with no sprite is a stack that draws the
 * magenta missing model over his sword, and nothing errors — so each pair is checked here.
 */
class IconOverlaysTest {

    @Test
    @DisplayName("the overlay names are sorted")
    void theNamesAreSorted() {
        assertEquals(new TreeSet<>(IconOverlays.NAMES).stream().toList(), IconOverlays.NAMES,
                "NAMES is sorted, because the carrier model lists the jar's cases sorted "
                        + "and NativeItemModelsTest compares the two in order");
    }

    @Test
    @DisplayName("every overlay has its sprite and its model in the jar")
    void everyOverlayIsDrawn() {
        for (String o : IconOverlays.NAMES) {
            Path png = Path.of("src/main/resources/assets/moddingfromamod/textures/item/overlay/" + o + ".png");
            Path model = Path.of("src/main/resources/assets/moddingfromamod/models/item/overlay/" + o + ".json");
            assertTrue(Files.exists(png), png + " — run tools/gen-overlay-sprites.py");
            assertTrue(Files.exists(model), model + " — run tools/gen-overlay-sprites.py");
        }
    }

    @Test
    @DisplayName("every effect it maps is a real catalogue effect, onto a real overlay")
    void everyMappingIsReal() throws IOException {
        List<String> ids = new ArrayList<>();
        for (JsonElement e : JsonParser.parseString(Files.readString(
                Path.of("src/main/resources/items/catalogue.json"))).getAsJsonObject()
                .getAsJsonArray("effects")) {
            ids.add(e.getAsJsonObject().get("id").getAsString());
        }
        for (var e : IconOverlays.BY_EFFECT.entrySet()) {
            assertTrue(ids.contains(e.getKey()), "'" + e.getKey() + "' is not a catalogue effect, so "
                    + "no item will ever wear " + e.getValue());
            assertTrue(IconOverlays.known(e.getValue()), e.getValue());
        }
    }

    @Test
    @DisplayName("the first picturable effect wins; nothing picturable is no overlay at all")
    void theFirstPicturableEffectWins() {
        assertEquals("sparks", IconOverlays.forEffects(List.of("lightning")));
        assertEquals("flame", IconOverlays.forEffects(List.of("hurt", "ignite")),
                "a fire sword is a HURT that also ignites: the flame is the picture");
        assertEquals("frost", IconOverlays.forEffects(List.of("freeze", "ignite")));
        assertEquals("smoke", IconOverlays.forEffects(List.of("food")),
                "food steams — sparkles on a slushie would read as magic, not as food");
        assertNull(IconOverlays.forEffects(List.of("hurt")),
                "a plain weapon has no overlay — the backing still makes it a wish");
        assertNull(IconOverlays.forEffects(List.of()));
        assertNull(IconOverlays.forEffects(null));
    }

    @Test
    @DisplayName("the stamp carries the overlay beside the number on 1.21.4, and is unchanged without one")
    void theStampCarriesTheOverlay() {
        int wand = IconSprites.bindings().stream().filter(s -> s.name().equals("wand"))
                .findFirst().orElseThrow().customModelData();
        assertEquals(ModelData.components(wand), ModelData.components(wand, null));
        assertEquals(ModelData.components(wand), ModelData.components(wand, "not_an_overlay"),
                "an unknown name must never reach a stack: the composite has no case for it");
        String with = ModelData.components(wand, "sparks");
        if (ModelData.COMPOSED_ICONS) {
            assertTrue(with.contains("custom_model_data={floats:[" + wand + "f],strings:['sparks']}"), with);
            assertTrue(with.contains("item_model='"), with);
            assertTrue(!with.contains("\""), "a give line travels inside double quotes: " + with);
        } else {
            assertEquals(ModelData.components(wand), with,
                    "1.21.1's component is one int — there is nowhere to put the overlay");
        }
    }

    @Test
    @DisplayName("every overlay and both backing layers ANIMATE — a strip with an mcmeta, and frames that differ")
    void everyOverlayAnimates() throws IOException {
        List<String> sprites = new ArrayList<>();
        for (String o : IconOverlays.NAMES) {
            sprites.add("overlay/" + o);
        }
        for (String fx : com.moddingfromamod.pack.VanillaItemPack.BACKING) {
            sprites.add("fx/" + fx);
        }
        for (String sprite : sprites) {
            Path png = Path.of("src/main/resources/assets/moddingfromamod/textures/item/" + sprite + ".png");
            Path meta = Path.of(png + ".mcmeta");
            assertTrue(Files.exists(png), png + " — run tools/gen-overlay-sprites.py");
            assertTrue(Files.exists(meta), meta + " is missing, so the whole strip is drawn squashed "
                    + "into one square instead of playing");
            java.awt.image.BufferedImage im = javax.imageio.ImageIO.read(png.toFile());
            int w = im.getWidth();
            assertEquals(0, im.getHeight() % w, sprite + " is not a vertical strip of square frames");
            int frames = im.getHeight() / w;
            assertTrue(frames >= 2, sprite + " has one frame — it does not animate");
            java.util.Set<String> distinct = new java.util.HashSet<>();
            for (int f = 0; f < frames; f++) {
                int[] px = im.getRGB(0, f * w, w, w, null, 0, w);
                distinct.add(java.util.Arrays.toString(px));
            }
            assertTrue(distinct.size() >= 2, sprite + ": every frame is identical — it animates nothing");
            // CUTOUT ONLY. A translucent pixel is drawn AFTER every solid one on a newer client
            // (26.2, photographed): the soft backing glow covered the very item it sits behind.
            for (int y = 0; y < im.getHeight(); y++) {
                for (int x = 0; x < w; x++) {
                    int alpha = (im.getRGB(x, y) >>> 24) & 0xFF;
                    assertTrue(alpha == 0 || alpha == 255, sprite + " has a translucent pixel at "
                            + x + "," + y + " (alpha " + alpha + ") — import it with --dither");
                }
            }
            assertTrue(Files.readString(meta).contains("\"animation\""), meta.toString());
        }
    }

    @Test
    @DisplayName("every vanilla base names a real icon-library sprite and a vanilla item model")
    void everyVanillaBaseIsReal() {
        var bases = com.moddingfromamod.pack.IconBases.all();
        assertTrue(bases.size() >= 15, "the map lost most of its bases: " + bases.keySet());
        for (var e : bases.entrySet()) {
            assertTrue(IconSprites.byName(e.getKey()) != null,
                    e.getKey() + " is not an icon-library sprite, so this base draws nothing");
            assertTrue(e.getValue().matches("minecraft:item/[a-z0-9_]+"), e.getValue()
                    + " must be a vanilla ITEM model — a block model or builtin renderer "
                    + "draws nothing inside a composite");
        }
        assertNull(com.moddingfromamod.pack.IconBases.forIcon("egg"),
                "the egg stays ours: the panel colours it per creature, and a vanilla egg is white");
    }
}
