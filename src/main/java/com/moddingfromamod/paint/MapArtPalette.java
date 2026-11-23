package com.moddingfromamod.paint;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.level.material.MapColor;

// ── TRANSITIONAL — art spike. Not production. See docs/VANILLA_ART_SPIKE_RESULTS.md

/**
 * <b>The map palette, read off the LIVE {@link MapColor} table — and the one line that unswaps it.</b>
 *
 * <p>Two jobs, and the class exists to make sure the second one happens in exactly one place.
 *
 * <h2>1. It is read, never transcribed</h2>
 *
 * <p>Every base colour at every brightness, asked of the game at runtime. A hardcoded table is a
 * second source of truth that goes stale silently the moment Minecraft adds a colour, and this repo
 * has already paid for a transcription once ({@code IconTintClientTest} hand-wrote an NBT key, both
 * stacks read as empty, and the probe reported that a working feature did not work). Battle of
 * Lord's precedent reads the live table for the same reason.
 *
 * <h2>2. 🔴 It unswaps ABGR &rarr; RGB, and that is the whole point of the file</h2>
 *
 * <p>{@code MapColor.calculateRGBColor} <b>does not return RGB.</b> Confirmed by disassembling this
 * exact Minecraft rather than by memory — the method ends
 * {@code 0xFF000000 | blue << 16 | green << 8 | red}, i.e. <b>ABGR</b>, which is the byte order the
 * map TEXTURE wants. Compare it against an {@code 0xRRGGBB} pixel and red and blue trade places.
 *
 * <p>A mod repo shipped exactly that: the LEGO Store's <b>red</b> banner rendered <b>blue</b>,
 * with every gate green, because <i>a map full of the wrong colours is still a map full of
 * colours</i>. No assertion that counts pixels, checks for non-emptiness or compares two arms for
 * equality can catch it — both arms would be equally wrong. Only a photograph did.
 *
 * <p>So the swap lives here, once, next to the call that needs it, and {@link MapArt} — which does
 * all the arithmetic — never sees a {@code MapColor} at all and takes plain {@code 0xRRGGBB}. The
 * guard is {@code MapArtTest.aKnownColourSurvivesTheRoundTrip} plus the photographic
 * {@code RED} swatch in {@code VanillaArtAbClientTest}, because a unit test can only prove the
 * arithmetic is self-consistent — it takes a real rendered pixel to prove it is <i>right</i>.
 */
public final class MapArtPalette {

    private static volatile MapArt.Palette cached;

    private MapArtPalette() {
    }

    /**
     * The colour a map colour actually shows as, {@code 0xRRGGBB}.
     *
     * <p>🔴 <b>The unswap. Do not "simplify" this to {@code & 0xFFFFFF}.</b> See the class note:
     * the value coming in is ABGR. Public so a gate can assert this exact function against a real
     * rendered pixel rather than against a copy of it.
     */
    public static int rgb(MapColor colour, MapColor.Brightness brightness) {
        int abgr = com.moddingfromamod.compat.MapColours.abgr(colour, brightness);
        return ((abgr & 0xFF) << 16) | (abgr & 0xFF00) | ((abgr >> 16) & 0xFF);
    }

    /**
     * Every colour a map can show, built once from the live table.
     *
     * <p>{@link MapColor#NONE} is excluded deliberately — it is "draw nothing", not a colour, and
     * leaving it in would let the nearest-colour search choose transparency for a pixel somebody
     * painted. Vanilla's own id 0 is that entry, which is why {@link MapArt#TRANSPARENT} is 0 and
     * why an unpainted pixel never reaches the search at all.
     */
    public static MapArt.Palette get() {
        MapArt.Palette p = cached;
        if (p != null) {
            return p;
        }
        List<Integer> rgb = new ArrayList<>();
        List<Byte> ids = new ArrayList<>();
        // Walk the whole id space rather than a remembered count: the table's size is the game's to
        // decide, and a hardcoded bound is the transcription this class exists to avoid.
        for (int base = 1; base < 64; base++) {
            MapColor colour = MapColor.byId(base);
            if (colour == null || colour == MapColor.NONE) {
                continue;
            }
            for (MapColor.Brightness brightness : MapColor.Brightness.values()) {
                rgb.add(rgb(colour, brightness));
                ids.add(colour.getPackedId(brightness));
            }
        }
        int[] r = new int[rgb.size()];
        byte[] i = new byte[ids.size()];
        for (int k = 0; k < r.length; k++) {
            r[k] = rgb.get(k);
            i[k] = ids.get(k);
        }
        p = new MapArt.Palette(r, i);
        cached = p;
        return p;
    }
}
