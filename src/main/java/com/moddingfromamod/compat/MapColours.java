package com.moddingfromamod.compat;

import net.minecraft.util.ARGB;
import net.minecraft.world.level.material.MapColor;

/**
 * <b>A map colour, packed the way the MAP TEXTURE wants it (ABGR)</b> (1.21.4 half).
 *
 * <p>1.21.1's {@code calculateRGBColor} returned {@code 0xFF000000 | b<<16 | g<<8 | r}, i.e. ABGR,
 * whatever its name says. 1.21.2 renamed it {@code calculateARGBColor} AND made it genuinely ARGB
 * ({@code ARGB.scaleRGB(ARGB.opaque(col), modifier)}), so a bare rename double-swaps every caller that
 * compensated for the old packing -- MapArtPalette does, which is how a red banner once rendered blue.
 * This answers the old value on both targets (the migrator's section V34, arriving at 1.21.2).
 */
public final class MapColours {
    private MapColours() {
    }

    public static int abgr(MapColor colour, MapColor.Brightness brightness) {
        return ARGB.toABGR(colour.calculateARGBColor(brightness));
    }
}
