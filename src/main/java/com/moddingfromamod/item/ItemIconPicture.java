package com.moddingfromamod.item;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import javax.imageio.ImageIO;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>An item wish's picture, drawn from its own icon — no camera</b>.
 *
 * <p>An item has nothing in the world to frame, so its photograph was a first-person shot through
 * a camera client: a VM resumed and lingered for one picture of a hand. The thing a child actually
 * sees in his inventory is the icon, and every piece of it is on the server already — the model's
 * layers and sprites ship in the jar, and the stack says which icon, which dye, which accent and
 * which overlay. So it is drawn here the way the client draws it: layers in order, the tinted ones
 * multiplied by their colour ({@code minecraft:dye} / {@code custom_model_data.colors}), the
 * overlay last and untinted, an animated strip's first frame, scaled up with nearest-neighbour so
 * the pixel art stays pixel art.
 *
 * <p>Pure: textures come through a loader (the classpath in the game, a map in a test).
 */
public final class ItemIconPicture {

    /** Side of the picture: 16 px art × 16, readable on a phone, ~a few KB as a PNG. */
    public static final int SIZE = 256;

    /** One layer: its texture id ({@code moddingfromamod:item/wish/cone}) and a tint, or null. */
    public record Layer(String texture, Integer tintRgb) { }

    private ItemIconPicture() {
    }

    /** The {@code layerN} textures of an item model JSON, in drawing order. Empty if it has none. */
    public static List<String> layers(String modelJson) {
        List<String> out = new ArrayList<>();
        if (modelJson == null) {
            return out;
        }
        try {
            JsonObject root = JsonParser.parseString(modelJson).getAsJsonObject();
            JsonObject tex = root.has("textures") ? root.getAsJsonObject("textures") : null;
            for (int i = 0; tex != null && tex.has("layer" + i); i++) {
                out.add(tex.get("layer" + i).getAsString());
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return out;
    }

    /** {@code ns:item/x} → the jar path of its PNG ({@code /assets/ns/textures/item/x.png}). */
    public static String texturePath(String texture) {
        String ns = "minecraft";
        String path = texture;
        int colon = texture.indexOf(':');
        if (colon >= 0) {
            ns = texture.substring(0, colon);
            path = texture.substring(colon + 1);
        }
        return "/assets/" + ns + "/textures/" + path + ".png";
    }

    /**
     * Draw the layers. A layer whose texture will not load is skipped; none at all is null — never
     * an empty square posing as an icon.
     */
    public static byte[] render(List<Layer> layers, Function<String, byte[]> load) {
        if (layers == null || layers.isEmpty()) {
            return null;
        }
        BufferedImage out = null;
        int drawn = 0;
        for (Layer layer : layers) {
            BufferedImage src = read(load.apply(texturePath(layer.texture())));
            if (src == null) {
                continue;
            }
            int w = src.getWidth();
            int h = Math.min(src.getHeight(), w);     // an animated strip: its first square frame
            if (out == null) {
                out = new BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB);
            }
            int ow = out.getWidth();
            for (int y = 0; y < Math.min(h, ow); y++) {
                for (int x = 0; x < Math.min(w, ow); x++) {
                    int p = src.getRGB(x, y);
                    int a = p >>> 24;
                    if (a == 0) {
                        continue;
                    }
                    if (layer.tintRgb() != null) {
                        p = tint(p, layer.tintRgb());
                    }
                    out.setRGB(x, y, over(out.getRGB(x, y), p));
                }
            }
            drawn++;
        }
        if (out == null || drawn == 0) {
            return null;
        }
        BufferedImage big = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        int scale = Math.max(1, SIZE / out.getWidth());
        int off = (SIZE - out.getWidth() * scale) / 2;
        for (int y = 0; y < out.getHeight(); y++) {
            for (int x = 0; x < out.getWidth(); x++) {
                int p = out.getRGB(x, y);
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        big.setRGB(off + x * scale + dx, off + y * scale + dy, p);
                    }
                }
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(big, "png", bytes);
        } catch (IOException e) {
            return null;
        }
        return bytes.toByteArray();
    }

    /** Minecraft's tint: each channel multiplied by the tint's. Alpha unchanged. */
    static int tint(int argb, int rgb) {
        int a = argb >>> 24;
        int r = ((argb >> 16) & 0xFF) * ((rgb >> 16) & 0xFF) / 255;
        int g = ((argb >> 8) & 0xFF) * ((rgb >> 8) & 0xFF) / 255;
        int b = (argb & 0xFF) * (rgb & 0xFF) / 255;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Source-over, straight alpha. */
    static int over(int dst, int src) {
        int sa = src >>> 24;
        if (sa == 255) {
            return src;
        }
        int da = dst >>> 24;
        int oa = sa + da * (255 - sa) / 255;
        if (oa == 0) {
            return 0;
        }
        int[] s = {(src >> 16) & 0xFF, (src >> 8) & 0xFF, src & 0xFF};
        int[] d = {(dst >> 16) & 0xFF, (dst >> 8) & 0xFF, dst & 0xFF};
        int[] o = new int[3];
        for (int i = 0; i < 3; i++) {
            o[i] = (s[i] * sa + d[i] * da * (255 - sa) / 255) / oa;
        }
        return (oa << 24) | (o[0] << 16) | (o[1] << 8) | o[2];
    }

    private static BufferedImage read(byte[] png) {
        if (png == null) {
            return null;
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException e) {
            return null;
        }
    }
}
