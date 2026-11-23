package com.moddingfromamod.runtime;

import java.util.Locale;

/**
 * What colour is this creature's spawn egg?
 *
 * <p>Vanilla spawn eggs are drawn from one two-layer template tinted per mob — a base colour and a
 * spot colour. Since every wished-for creature shares a <b>single</b> registered egg item (registries
 * freeze at load, so there can't be one item per creature), the tint has to be computed per stack
 * instead of baked in. This works that out.
 *
 * <p>A creature that was never given a colour still needs a <i>distinctive</i> egg — a shelf of
 * identical white eggs is useless to a child hunting for his dragon. So an uncoloured species derives
 * a stable colour from its id: the same creature always gets the same egg, and different creatures
 * almost always differ.
 *
 * <p>Pure, so the "no two eggs look the same" property is unit-tested rather than hoped for.
 */
public final class EggColours {

    private EggColours() {
    }

    /** White means "no tint" on a species, which would be a blank-looking egg. */
    public static final int NO_TINT = 0xFFFFFF;

    /**
     * The egg's base (layer 0) colour.
     *
     * @param tint      the species tint, or {@link #NO_TINT}
     * @param speciesId used to derive a colour when there's no tint
     */
    public static int base(int tint, String speciesId) {
        if ((tint & 0xFFFFFF) != NO_TINT) {
            return tint & 0xFFFFFF;
        }
        return derive(speciesId);
    }

    /**
     * The egg's spot (layer 1) colour: a brighter version of the base, so the speckles read against
     * it the way vanilla's do.
     */
    public static int spots(int tint, String speciesId) {
        return lighten(base(tint, speciesId), 0.45f);
    }

    /**
     * Make a colour <b>opaque</b> for use as an item tint.
     *
     * <p>Minecraft multiplies the item's quads by this colour <em>including its alpha channel</em>, so
     * a bare {@code 0xRRGGBB} (alpha {@code 0x00}) renders a completely invisible item. That shipped:
     * the egg was in the inventory, correctly named, correctly tinted, with a baked 75-quad model —
     * and the hotbar slot looked empty. Vanilla's own spawn-egg tints come back as {@code 0xFF……}.
     */
    public static int opaque(int rgb) {
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }

    /** A stable, well-spread colour for an id. Same id → same colour, always. */
    static int derive(String speciesId) {
        String key = speciesId == null ? "" : speciesId.toLowerCase(Locale.ROOT);
        // A cheap string hash, then spread it around the hue circle. Saturation and value are held
        // high so every derived egg is vivid rather than muddy.
        int h = 0;
        for (int i = 0; i < key.length(); i++) {
            h = h * 31 + key.charAt(i);
        }
        float hue = ((h % 360) + 360) % 360 / 360f;
        return hsvToRgb(hue, 0.75f, 0.95f);
    }

    private static int lighten(int rgb, float amount) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        r = Math.min(255, Math.round(r + (255 - r) * amount));
        g = Math.min(255, Math.round(g + (255 - g) * amount));
        b = Math.min(255, Math.round(b + (255 - b) * amount));
        return (r << 16) | (g << 8) | b;
    }

    static int hsvToRgb(float h, float s, float v) {
        int i = (int) Math.floor(h * 6);
        float f = h * 6 - i;
        float p = v * (1 - s);
        float q = v * (1 - f * s);
        float t = v * (1 - (1 - f) * s);
        float r;
        float g;
        float b;
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }
}
