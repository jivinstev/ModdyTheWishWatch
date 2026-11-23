package com.moddingfromamod.item;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/** An item wish's picture is its icon, drawn on the server — no camera. */
class ItemIconPictureTest {

    private static byte[] png(int w, int h, int argb) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, argb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] b) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(b));
    }

    @Test
    void theModelsLayersAreReadInOrder() {
        String json = "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer1\":\"m:item/b\","
                + "\"layer0\":\"m:item/a\",\"layer2\":\"m:item/c\"}}";
        assertEquals(List.of("m:item/a", "m:item/b", "m:item/c"), ItemIconPicture.layers(json));
        assertEquals(List.of(), ItemIconPicture.layers("{\"textures\":{}}"));
        assertEquals(List.of(), ItemIconPicture.layers("not json"));
        assertEquals(List.of(), ItemIconPicture.layers(null));
        assertEquals("/assets/m/textures/item/wish/cone.png", ItemIconPicture.texturePath("m:item/wish/cone"));
        assertEquals("/assets/minecraft/textures/item/stick.png", ItemIconPicture.texturePath("item/stick"));
    }

    /** A grey sprite dyed is the client's multiply, channel by channel. */
    @Test
    void aTintMultipliesEachChannel() {
        assertEquals(0xFF800000, ItemIconPicture.tint(0xFF808080, 0xFF0000) & 0xFFFFFFFF);
        assertEquals(0xFFFF8000, ItemIconPicture.tint(0xFFFFFFFF, 0xFF8000));
        assertEquals(0x80000000, ItemIconPicture.tint(0x80FFFFFF, 0x000000), "alpha untouched");
    }

    @Test
    void layersStackInOrderAndTheTintedOneTakesItsColour() throws Exception {
        Map<String, byte[]> tex = new HashMap<>();
        tex.put("/assets/m/textures/item/base.png", png(16, 16, 0xFFFFFFFF));   // white: takes the dye
        BufferedImage dot = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        dot.setRGB(0, 0, 0xFF00FF00);                                           // one green pixel on top
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(dot, "png", b);
        tex.put("/assets/m/textures/item/detail.png", b.toByteArray());
        byte[] out = ItemIconPicture.render(List.of(new ItemIconPicture.Layer("m:item/base", 0x3366CC),
                new ItemIconPicture.Layer("m:item/detail", null)), tex::get);
        assertNotNull(out);
        BufferedImage img = decode(out);
        assertEquals(ItemIconPicture.SIZE, img.getWidth());
        assertEquals(ItemIconPicture.SIZE, img.getHeight());
        int scale = ItemIconPicture.SIZE / 16;
        assertEquals(0xFF3366CC, img.getRGB(scale * 5, scale * 5), "the dyed body");
        assertEquals(0xFF00FF00, img.getRGB(1, 1), "the untinted layer drawn OVER it, upscaled");
        assertEquals(0xFF00FF00, img.getRGB(scale - 1, scale - 1), "nearest-neighbour: a whole block");
        assertEquals(0xFF3366CC, img.getRGB(scale, scale - 1), "and not a blur into the next pixel");
    }

    @Test
    void anAnimatedStripDrawsItsFirstFrame() throws Exception {
        BufferedImage strip = new BufferedImage(16, 48, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < 16; x++) {
                strip.setRGB(x, y, y < 16 ? 0xFFFF0000 : 0xFF0000FF);
            }
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(strip, "png", b);
        byte[] out = ItemIconPicture.render(List.of(new ItemIconPicture.Layer("m:item/s", null)),
                p -> b.toByteArray());
        BufferedImage img = decode(out);
        assertEquals(ItemIconPicture.SIZE, img.getHeight(), "square, not a tall strip");
        assertEquals(0xFFFF0000, img.getRGB(10, ItemIconPicture.SIZE - 1), "frame one only");
    }

    @Test
    void aMissingTextureIsSkippedAndNoTexturesIsNoPicture() throws Exception {
        byte[] body = png(16, 16, 0xFFFFFFFF);
        byte[] one = ItemIconPicture.render(List.of(new ItemIconPicture.Layer("m:item/gone", null),
                new ItemIconPicture.Layer("m:item/body", null)),
                p -> p.endsWith("body.png") ? body : null);
        assertNotNull(one, "a missing overlay never costs the icon");
        assertNull(ItemIconPicture.render(List.of(new ItemIconPicture.Layer("m:item/gone", null)), p -> null),
                "never an empty square posing as an icon");
        assertNull(ItemIconPicture.render(List.of(), p -> body));
        assertNull(ItemIconPicture.render(List.of(new ItemIconPicture.Layer("m:item/x", null)),
                p -> new byte[] {1, 2, 3}), "an unreadable PNG is skipped too");
    }

    @Test
    void translucentPixelsBlendOverWhatIsBelow() {
        int blended = ItemIconPicture.over(0xFF0000FF, 0x80FF0000);
        assertEquals(0xFF, blended >>> 24);
        int r = (blended >> 16) & 0xFF;
        int bl = blended & 0xFF;
        assertEquals(true, r > 100 && bl > 100, Integer.toHexString(blended));
        assertEquals(0xFF123456, ItemIconPicture.over(0xFF000000, 0xFF123456), "opaque replaces");
        assertEquals(0, ItemIconPicture.over(0, 0), "nothing over nothing");
    }

    @Test
    void theSamePixelsGiveTheSameBytes() throws Exception {
        byte[] body = png(16, 16, 0xFFAAAAAA);
        var layers = List.of(new ItemIconPicture.Layer("m:item/b", 0x22AA22));
        assertArrayEquals(ItemIconPicture.render(layers, p -> body), ItemIconPicture.render(layers, p -> body));
    }
}
