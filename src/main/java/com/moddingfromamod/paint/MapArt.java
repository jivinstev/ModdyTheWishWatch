package com.moddingfromamod.paint;

import java.util.List;

import com.moddingfromamod.runtime.CreatureSculpt;

// ── TRANSITIONAL — art spike. Not production. See docs/VANILLA_ART_SPIKE_RESULTS.md

/**
 * <b>A picture, turned into the bytes a Minecraft map stores.</b>
 *
 * <p>Pure — no Minecraft — so the quantisation, the tiling and (above all) the colour arithmetic
 * are unit-testable without a game. That is not a stylistic preference here: the failure mode this
 * class exists to avoid produces a <i>complete, plausible, wrong-coloured picture</i>, and the only
 * cheap way to catch that is an assertion on a known colour, which needs a pure function to assert
 * against.
 *
 * <h2>🔴 The trap this class is shaped around</h2>
 *
 * <p>{@code MapColor.calculateRGBColor} returns <b>ABGR, not ARGB</b>. Read out of 1.21.1's own
 * bytecode rather than remembered — the tail of that method is
 * {@code 0xFF000000 | blue << 16 | green << 8 | red}. A mod repo compared it against an ARGB
 * pixel and the LEGO Store's <b>red</b> shopfront banner rendered <b>blue</b>, with every gate
 * green, <i>because a map full of the wrong colours is still a map full of colours</i>. Only the
 * photograph showed it.
 *
 * <p>So this class never sees {@code MapColor}. It takes a palette as plain {@code 0xRRGGBB} ints
 * and it is {@link MapArtPalette}'s single job to have unswapped them. The one place the byte order
 * can be got wrong is therefore one line, in a Minecraft-facing class, with a unit test that paints
 * pure red and demands red back ({@code MapArtTest.aKnownColourSurvivesTheRoundTrip}) and a
 * photographic gate that does the same thing through the real game.
 *
 * <h2>Transparency</h2>
 *
 * <p>Map colour <b>0</b> is {@code MapColor.NONE}, which vanilla draws as nothing at all. That maps
 * exactly onto {@code paint_surface}'s own contract — "whatever you do not paint is see-through, so
 * the blocks behind show past the picture" — so an unpainted texel becomes a transparent map pixel
 * and the promise the agent was given survives the change of view. It also means the nearest-colour
 * search must never be asked about a transparent pixel: {@code NONE} is excluded from the palette,
 * and a fully-transparent input short-circuits to 0 before any distance is computed. Without that
 * short-circuit, unpainted space would quantise to whichever real colour is nearest to black and a
 * picture would arrive on a slab — the exact defect the tool description was rewritten to stop.
 */
public final class MapArt {

    /** Below this alpha a pixel is treated as unpainted. Anything the painter writes is 255. */
    public static final int ALPHA_CUTOFF = 128;

    /** What vanilla draws as nothing. {@code MapColor.NONE}'s packed id is 0. */
    public static final byte TRANSPARENT = 0;

    private MapArt() {
    }

    /**
     * A map palette as this class wants it: matched colours as plain {@code 0xRRGGBB}, and the byte
     * vanilla stores for each.
     *
     * <p>Two parallel arrays rather than a map because the nearest-colour search is the inner loop
     * over every pixel of every tile, and it is the only thing in this route that is not free.
     */
    public record Palette(int[] rgb, byte[] id) {

        public Palette {
            if (rgb == null || id == null || rgb.length != id.length) {
                throw new IllegalArgumentException("a palette needs one id per colour");
            }
        }

        public int size() {
            return rgb.length;
        }
    }

    /**
     * Paint a recipe and quantise it, in one go, at the resolution the span deserves.
     *
     * <p>Goes through {@link CreatureSculpt#rasterise} — the same painter the mod renderer's PNG is
     * written by — so the two arms differ by the quantisation and by nothing else.
     */
    public static byte[][] tilesOf(PaintRecipe recipe, int wide, int tall, Palette palette) {
        PaintRecipe r = recipe == null ? PaintRecipe.blank() : recipe;
        int cols = VanillaArt.tiles(wide);
        int rows = VanillaArt.tiles(tall);
        // The recipe's own coordinates are in ITS OWN pixel space (the agent wrote x/y/w/h against
        // recipe.size()), so the rects are scaled onto the map canvas rather than the canvas being
        // resized around them. Getting this backwards draws the picture in the top-left corner of a
        // much larger canvas -- a "postage stamp on the side of a carriage", which is the failure
        // the schema's own DRAW IT BIG paragraph exists to prevent.
        int width = cols * VanillaArt.TILE;
        int height = rows * VanillaArt.TILE;
        int[] argb = rasteriseScaled(r, width, height);
        return split(argb, width, height, cols, rows, palette);
    }

    /**
     * Rasterise a recipe onto a non-square canvas of a different size from the one it was written
     * for.
     *
     * <p>{@link CreatureSculpt#rasterise} paints a SQUARE at the recipe's own size, which is right
     * for a creature skin and for the mod renderer's texture. A map wall is {@code cols}&times;
     * {@code rows} tiles and is square only by accident, so the rectangles are scaled here — as
     * rectangles, before painting, so the ordering rule ("later rectangles go on top") still holds
     * and no pixel is ever interpolated. A resampled bitmap would blur a hard-edged shape picture
     * and then the palette would be blamed for the softness.
     */
    static int[] rasteriseScaled(PaintRecipe recipe, int width, int height) {
        int src = Math.max(1, recipe.size());
        int[] px = new int[width * height];
        for (CreatureSculpt.PaintRect r : recipe.rects()) {
            if (r == null) {
                continue;
            }
            int argb = 0xFF000000 | (r.colour() & 0xFFFFFF);
            int x0 = scale(r.x(), src, width);
            int x1 = scale(r.x() + r.width(), src, width);
            int y0 = scale(r.y(), src, height);
            int y1 = scale(r.y() + r.height(), src, height);
            for (int y = Math.max(0, y0); y < Math.min(height, y1); y++) {
                for (int x = Math.max(0, x0); x < Math.min(width, x1); x++) {
                    px[y * width + x] = argb;
                }
            }
        }
        return px;
    }

    private static int scale(int v, int from, int to) {
        return (int) Math.round((double) v * to / from);
    }

    /**
     * Cut a rasterised image into map-sized tiles, quantising as it goes.
     *
     * <p>Tiles are returned in <b>reading order</b> — row 0 is the TOP of the picture, column 0 the
     * left as a viewer standing in front of it sees it. That is {@link PanelGeometry}'s convention
     * ("(0,0) is the picture's top-left as a viewer sees it") restated for tiles rather than texels,
     * deliberately, so a mural hung by this route and one drawn by the panel renderer cannot come
     * out mirrored relative to each other. Upside-down and mirrored both photograph perfectly.
     */
    static byte[][] split(int[] argb, int width, int height, int cols, int rows, Palette palette) {
        byte[][] tiles = new byte[cols * rows][VanillaArt.TILE * VanillaArt.TILE];
        // ONE PALETTE SEARCH PER COLOUR, NOT PER PIXEL (C16). A picture is solid
        // rectangles, so a 16x16 board is 4.2M pixels in at most MAX_RECTS + 1 colours, and
        // searching the whole palette for each pixel measured 1910 ms inside one server tick.
        // `nearest` is a pure function of the pixel, so the memo cannot change a single byte.
        java.util.HashMap<Integer, Byte> memo = new java.util.HashMap<>();
        int lastIn = 0;
        byte lastOut = nearest(0, palette);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                byte[] tile = tiles[row * cols + col];
                for (int y = 0; y < VanillaArt.TILE; y++) {
                    int sy = row * VanillaArt.TILE + y;
                    for (int x = 0; x < VanillaArt.TILE; x++) {
                        int sx = col * VanillaArt.TILE + x;
                        int p = (sy < height && sx < width) ? argb[sy * width + sx] : 0;
                        if (p != lastIn) {
                            Byte q = memo.get(p);
                            if (q == null) {
                                q = nearest(p, palette);
                                memo.put(p, q);
                            }
                            lastIn = p;
                            lastOut = q;
                        }
                        tile[y * VanillaArt.TILE + x] = lastOut;
                    }
                }
            }
        }
        return tiles;
    }

    /**
     * The nearest palette entry to one ARGB pixel, or {@link #TRANSPARENT} for an unpainted one.
     *
     * <p>Plain squared RGB distance. Deliberately not a perceptual metric: the palette is measured
     * against the SAME metric in {@code MapArtTest}, so a fancier one would move both the choice and
     * the score together and report an improvement that nobody can see. If the measured per-colour
     * error turns out to matter, that is a finding to act on with evidence, not a knob to pre-turn.
     */
    public static byte nearest(int argb, Palette palette) {
        if (((argb >>> 24) & 0xFF) < ALPHA_CUTOFF) {
            return TRANSPARENT;      // see the class note: NEVER quantise an unpainted pixel
        }
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int best = 0;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < palette.rgb().length; i++) {
            int p = palette.rgb()[i];
            long dr = ((p >> 16) & 0xFF) - r;
            long dg = ((p >> 8) & 0xFF) - g;
            long db = (p & 0xFF) - b;
            long d = dr * dr + dg * dg + db * db;
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return palette.id()[best];
    }

    /**
     * The colour a map byte actually renders as, {@code 0xRRGGBB} — the inverse of
     * {@link #nearest}, for measurement.
     *
     * <p>This is what makes the round trip assertable: paint red, quantise, look the byte back up,
     * and demand red. Returns -1 for the transparent byte, which is not a colour and must not be
     * averaged into a delta as if it were black.
     */
    public static int renders(byte id, Palette palette) {
        if (id == TRANSPARENT) {
            return -1;
        }
        for (int i = 0; i < palette.id().length; i++) {
            if (palette.id()[i] == id) {
                return palette.rgb()[i];
            }
        }
        return -1;
    }

    /** Straight-line RGB distance between two {@code 0xRRGGBB} colours. */
    public static double distance(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return Math.sqrt((double) dr * dr + (double) dg * dg + (double) db * db);
    }

    /** Every distinct colour a recipe paints, in the order first used. */
    public static List<Integer> coloursOf(PaintRecipe recipe) {
        java.util.LinkedHashSet<Integer> seen = new java.util.LinkedHashSet<>();
        for (CreatureSculpt.PaintRect r : recipe.rects()) {
            if (r != null) {
                seen.add(r.colour() & 0xFFFFFF);
            }
        }
        return List.copyOf(seen);
    }
}
