package com.moddingfromamod.paint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.Test;

/**
 * <b>Gate A for the vanilla map-art route.</b>
 *
 * <p>The route's failure mode is <b>a complete, plausible, wrong-coloured picture</b>, which is the
 * one thing a photograph is bad at catching and a count-the-pixels assertion cannot catch at all —
 * A mod repo shipped a red logo that rendered blue with every gate green. So the colour
 * arithmetic is pinned here, in a pure test, where "red must come back red" is a named failure
 * rather than something somebody has to notice.
 *
 * <p><b>The palette in these tests is built by hand, not read from {@link MapArtPalette}.</b>
 * Deliberate: a test that took its expectations from the class under test would agree with the
 * ABGR bug and pass. {@code MapArtPaletteGameTest} asserts the live table separately, against a
 * value derived from Minecraft's own {@code col} field rather than from our unswap.
 */
class MapArtTest {

    /**
     * A deliberately tiny, explicit palette: pure red, green, blue, white, black — as
     * {@code 0xRRGGBB}, which is what {@link MapArt} contracts to be given.
     *
     * <p>Ids are arbitrary non-zero bytes. Zero is reserved for transparency and must never appear
     * in a palette; {@code aPaletteNeverOffersTransparencyAsAColour} pins that.
     */
    private static MapArt.Palette basic() {
        return new MapArt.Palette(
                new int[]{0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF, 0x000000},
                new byte[]{10, 20, 30, 40, 50});
    }

    // ===================== 🔴 THE FIFTH GUARD =====================
    //
    // ⚠ READ THIS BEFORE ADDING TO IT. The first draft of this guard was a round trip through ONE
    // palette -- quantise red, look the byte back up, assert red -- and it CANNOT catch the ABGR
    // bug. Both directions consult the same table, so a table that is wrong in one direction is
    // wrong in the other and the two agree perfectly. That is not a curiosity; it is the mechanism
    // by which a mod repo's LEGO logo shipped blue with every gate green, and writing it out
    // reproduced the methodology's own warning that "a test can pin a wrong belief".
    //
    // So the model here separates two things a round trip conflates:
    //   * TRUTH  -- what colour a map byte actually renders as, which only Minecraft knows;
    //   * BELIEF -- what our palette says it renders as, which is where the swap gets in.
    // A guard is only a guard if it compares one against the other.

    /** What each byte in {@link #basic()} really renders as. The TRUTH, independent of the code. */
    private static int truth(byte id) {
        return switch (id) {
            case 10 -> 0xFF0000;   // red
            case 20 -> 0x00FF00;   // green
            case 30 -> 0x0000FF;   // blue
            case 40 -> 0xFFFFFF;
            default -> 0x000000;
        };
    }

    /** A palette believing the truth, as {@link MapArtPalette} does once it has unswapped. */
    private static MapArt.Palette believingTheTruth() {
        return basic();
    }

    /**
     * A palette that believes {@code calculateRGBColor}'s raw output — i.e. ABGR — which is exactly
     * what you get by writing {@code colour.calculateRGBColor(b) & 0xFFFFFF} and calling it RGB.
     */
    private static MapArt.Palette believingAbgr() {
        int[] rgb = new int[basic().rgb().length];
        for (int i = 0; i < rgb.length; i++) {
            rgb[i] = swap(truth(basic().id()[i]));
        }
        return new MapArt.Palette(rgb, basic().id());
    }

    /**
     * <b>Paint pure red, and demand the pixel a player would actually SEE is red.</b>
     *
     * <p>This is the assertion the LEGO logo needed. It reads the chosen byte's <i>true</i> colour
     * rather than the palette's opinion of it, so a swapped palette fails by name instead of
     * producing a full, plausible, wrong-coloured picture that every counting check calls a pass.
     *
     * <p>All three primaries, because a red/blue swap is invisible on white, on black and on every
     * grey — which is most of a typical picture, and most of a typical assertion.
     */
    @Test
    void aKnownColourSurvivesTheRoundTrip() {
        MapArt.Palette p = believingTheTruth();
        assertEquals(0xFF0000, truth(MapArt.nearest(0xFFFF0000, p)),
                "pure RED must be SEEN as red — if this says blue, the ABGR unswap is missing");
        assertEquals(0x00FF00, truth(MapArt.nearest(0xFF00FF00, p)),
                "pure GREEN must be seen as green");
        assertEquals(0x0000FF, truth(MapArt.nearest(0xFF0000FF, p)),
                "pure BLUE must be seen as blue — the other half of the red/blue swap");
    }

    /**
     * The guard above, <b>verified to FAIL</b> (&sect;3.C.11) — with a palette left in ABGR, red is
     * seen as blue.
     *
     * <p>If this test ever goes green, the guard has stopped being able to tell the two apart.
     */
    @Test
    void theColourGuardWouldCatchTheAbgrBug() {
        MapArt.Palette broken = believingAbgr();
        assertEquals(0x0000FF, truth(MapArt.nearest(0xFFFF0000, broken)),
                "with the palette left in ABGR, red MUST be seen as blue — that is the bug, and if "
                        + "it does not reproduce the guard above is not proving anything");
        assertEquals(0xFF0000, truth(MapArt.nearest(0xFF0000FF, broken)),
                "and blue as red");
    }

    /**
     * <b>Why a round trip is NOT enough</b>, pinned so nobody "simplifies" the guard back into one.
     *
     * <p>The same broken palette that gets every colour visibly wrong round-trips <i>perfectly</i>:
     * {@code renders(nearest(red))} returns red, because both halves consult the same wrong table.
     * A guard built that way is green over the exact bug it was written for.
     */
    @Test
    void aRoundTripThroughOneTableCannotCatchAWrongTable() {
        MapArt.Palette broken = believingAbgr();
        assertEquals(0xFF0000, MapArt.renders(MapArt.nearest(0xFFFF0000, broken), broken),
                "the round trip agrees with itself even though the picture is blue — which is why "
                        + "the guard must compare against the TRUTH, and why the photographic "
                        + "swatch in the Gate C run is not redundant with this file");
    }

    private static int swap(int rgb) {
        return ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);
    }

    // ===================== transparency =====================

    /**
     * <b>An unpainted pixel is transparent, never "the nearest colour to black".</b>
     *
     * <p>{@code paint_surface}'s description promises the child that "whatever you do not paint is
     * see-through, so the blocks behind show past the picture", and its schema was rewritten
     * specifically because a backdrop had been getting painted. If the quantiser treated an
     * alpha-0 pixel as a colour, every picture on this route would arrive on a black slab and the
     * route would have silently reintroduced the defect the tool description exists to prevent.
     */
    @Test
    void anUnpaintedPixelIsTransparentRatherThanBlack() {
        MapArt.Palette p = basic();
        assertEquals(MapArt.TRANSPARENT, MapArt.nearest(0x00000000, p),
                "a fully transparent pixel must be map colour 0");
        assertEquals(MapArt.TRANSPARENT, MapArt.nearest(0x00FF0000, p),
                "alpha is what decides, not the colour bytes underneath it");
        assertNotEquals(MapArt.TRANSPARENT, MapArt.nearest(0xFF000000, p),
                "an OPAQUE black pixel is a colour somebody painted and must be kept");
    }

    @Test
    void transparencyIsNotAColourItCanRenderAs() {
        assertEquals(-1, MapArt.renders(MapArt.TRANSPARENT, basic()),
                "the transparent byte has no colour, and returning black here would drag every "
                        + "unpainted pixel into a measured delta as if it were painted");
    }

    // ===================== tiling and orientation =====================

    /**
     * One map per block of span — the arithmetic the whole route rests on.
     *
     * <p>Asserted against {@link PanelGeometry#clampSpan} rather than against a copy of its bounds,
     * so the panel and the map art cannot disagree about how big a picture is.
     */
    @Test
    void aPictureIsOneMapTilePerBlock() {
        assertEquals(1, VanillaArt.tiles(1));
        assertEquals(4, VanillaArt.tiles(4));
        assertEquals(PanelGeometry.MAX_SPAN, VanillaArt.tiles(PanelGeometry.MAX_SPAN + 99),
                "a span past the panel's own cap must clamp the same way the panel does");
        assertEquals(1, VanillaArt.tiles(0), "and so must a nonsense one");
        assertEquals(4 * VanillaArt.TILE, VanillaArt.pixels(4));
    }

    /**
     * <b>Row 0 is the TOP and column 0 is the LEFT</b>, matching {@link PanelGeometry}'s stated
     * convention.
     *
     * <p>Upside-down and mirrored both photograph perfectly, which is why this is a unit test and
     * not something to check by eye. The picture is four differently-coloured quadrants — a
     * symmetrical one could not tell either failure from a pass.
     */
    @Test
    void tilesComeBackInReadingOrder() {
        MapArt.Palette p = basic();
        // A 2x2-block picture: red top-left, green top-right, blue bottom-left, white bottom-right.
        PaintRecipe r = new PaintRecipe(64, List.of(
                new CreatureSculpt.PaintRect(0, 0, 32, 32, 0xFF0000),
                new CreatureSculpt.PaintRect(32, 0, 32, 32, 0x00FF00),
                new CreatureSculpt.PaintRect(0, 32, 32, 32, 0x0000FF),
                new CreatureSculpt.PaintRect(32, 32, 32, 32, 0xFFFFFF)));
        byte[][] tiles = MapArt.tilesOf(r, 2, 2, p);
        assertEquals(4, tiles.length);
        assertEquals(0xFF0000, MapArt.renders(centre(tiles[0]), p), "tile 0 is the TOP-LEFT");
        assertEquals(0x00FF00, MapArt.renders(centre(tiles[1]), p), "tile 1 is the TOP-RIGHT");
        assertEquals(0x0000FF, MapArt.renders(centre(tiles[2]), p), "tile 2 is the BOTTOM-LEFT");
        assertEquals(0xFFFFFF, MapArt.renders(centre(tiles[3]), p), "tile 3 is the BOTTOM-RIGHT");
    }

    private static byte centre(byte[] tile) {
        return tile[(VanillaArt.TILE / 2) * VanillaArt.TILE + VanillaArt.TILE / 2];
    }

    /**
     * <b>A picture fills the wall it is hung on, whatever resolution it was written at.</b>
     *
     * <p>The agent writes rectangles in the recipe's own pixel space, which is 256 by default and
     * square; a 4&times;1 mural is 512&times;128 map pixels. Painting the rects at their literal
     * coordinates would put the whole picture in a 256&sup2; corner of that canvas — "a postage
     * stamp on the side of a carriage", which is the failure the schema's own DRAW IT BIG paragraph
     * was written to prevent, arriving through the back door.
     */
    @Test
    void aPictureIsScaledOntoTheWallRatherThanPastedIntoItsCorner() {
        MapArt.Palette p = basic();
        PaintRecipe full = new PaintRecipe(256, List.of(
                new CreatureSculpt.PaintRect(0, 0, 256, 256, 0xFF0000)));
        byte[][] tiles = MapArt.tilesOf(full, 4, 1, p);
        assertEquals(4, tiles.length);
        for (int i = 0; i < tiles.length; i++) {
            for (int j = 0; j < tiles[i].length; j++) {
                assertNotEquals(MapArt.TRANSPARENT, tiles[i][j],
                        "tile " + i + " has an unpainted pixel, so a full-canvas rectangle did not "
                                + "reach the whole wall");
            }
        }
    }

    /** A picture nobody described paints nothing at all — not a black wall. */
    @Test
    void anEmptyRecipeLeavesTheWallSeeThrough() {
        byte[][] tiles = MapArt.tilesOf(PaintRecipe.blank(), 2, 2, basic());
        for (byte[] tile : tiles) {
            for (byte b : tile) {
                assertEquals(MapArt.TRANSPARENT, b);
            }
        }
    }

    /**
     * Later rectangles go on top — the ordering rule shared with the creature painter.
     *
     * <p>Pinned on THIS route as well as on the shared rasteriser, because the map path scales the
     * rectangles before painting them and an implementation that sorted or de-duplicated them on
     * the way would silently reverse it.
     */
    @Test
    void laterRectanglesGoOnTop() {
        MapArt.Palette p = basic();
        PaintRecipe r = new PaintRecipe(64, List.of(
                new CreatureSculpt.PaintRect(0, 0, 64, 64, 0xFF0000),
                new CreatureSculpt.PaintRect(0, 0, 64, 64, 0x00FF00)));
        assertEquals(0x00FF00, MapArt.renders(centre(MapArt.tilesOf(r, 1, 1, p)[0]), p),
                "the SECOND rectangle must win");
    }

    // ===================== the shared painter =====================

    /**
     * <b>The two arms share one painter</b> (&sect;3.B.8), so an A/B compares a quantisation and
     * never two rasterisers that have drifted.
     *
     * <p>Asserted by painting the same recipe through {@link CreatureSculpt#rasterise} — the
     * function the mod renderer's PNG is written by — and through the map route at the same size,
     * and demanding the map route quantise every pixel to whatever the shared painter produced.
     */
    @Test
    void bothArmsArePaintedByTheSameFunction() {
        MapArt.Palette p = basic();
        PaintRecipe r = new PaintRecipe(VanillaArt.TILE, List.of(
                new CreatureSculpt.PaintRect(0, 0, 60, 60, 0xFF0000),
                new CreatureSculpt.PaintRect(20, 20, 40, 40, 0x0000FF)));
        int[] shared = CreatureSculpt.rasterise(VanillaArt.TILE, r.rects());
        byte[] tile = MapArt.tilesOf(r, 1, 1, p)[0];
        for (int i = 0; i < tile.length; i++) {
            int expected = MapArt.nearest(shared[i], p);
            assertEquals(expected, tile[i],
                    "pixel " + i + " differs from what the shared painter produced, so the map "
                            + "route has grown a second rasteriser");
        }
    }

    @Test
    void theSharedPainterLeavesUnpaintedSpaceTransparent() {
        int[] px = CreatureSculpt.rasterise(8, List.of(
                new CreatureSculpt.PaintRect(0, 0, 4, 4, 0xFF0000)));
        assertEquals(0xFFFF0000, px[0], "a painted pixel is opaque");
        assertEquals(0, px[7], "an unpainted one is fully transparent, not black");
    }

    /** Out-of-bounds rectangles are clipped rather than throwing. */
    @Test
    void theSharedPainterClipsRatherThanThrowing() {
        int[] px = CreatureSculpt.rasterise(8, List.of(
                new CreatureSculpt.PaintRect(-4, -4, 100, 100, 0x00FF00)));
        assertEquals(0xFF00FF00, px[0]);
        assertEquals(64, px.length);
    }

    // ===================== the palette's own contract =====================

    @Test
    void aPaletteNeverOffersTransparencyAsAColour() {
        MapArt.Palette p = basic();
        for (byte id : p.id()) {
            assertNotEquals(MapArt.TRANSPARENT, id,
                    "map colour 0 is 'draw nothing'. Leaving it in the palette lets the search "
                            + "choose transparency for a pixel somebody actually painted.");
        }
    }

    @Test
    void aMalformedPaletteIsRefusedRatherThanSilentlyMisaligned() {
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MapArt.Palette(new int[]{1, 2}, new byte[]{1})).getMessage()
                .contains("one id per colour"),
                "mismatched arrays would pair every colour with the wrong id from that point on, "
                        + "which is a whole wrong-coloured picture and no exception");
    }

    // ===================== measurement helpers used by the delta gate =====================

    @Test
    void distanceIsZeroForAColourAgainstItself() {
        assertEquals(0.0, MapArt.distance(0x123456, 0x123456), 1e-9);
        assertEquals(255.0, MapArt.distance(0x000000, 0xFF0000), 1e-9);
    }

    @Test
    void theColoursOfARecipeAreTheOnesItPaintsInOrder() {
        PaintRecipe r = new PaintRecipe(64, List.of(
                new CreatureSculpt.PaintRect(0, 0, 8, 8, 0xFF0000),
                new CreatureSculpt.PaintRect(0, 0, 8, 8, 0x00FF00),
                new CreatureSculpt.PaintRect(8, 0, 8, 8, 0xFF0000)));
        assertEquals(List.of(0xFF0000, 0x00FF00), MapArt.coloursOf(r));
    }

    // ===================== the switch =====================

    /**
     * <b>The routing decision, both ways</b> — the "testable both ways" criterion path 2 requires,
     * at the cheapest tier it can be asked at.
     *
     * <p>It is one negation of {@link VanillaArt#enabled()} and it is still worth its own name and
     * its own test, because the two are different questions: one is "are we serving vanilla
     * clients", the other is "what draws this wall". A tool that asked the first directly would be
     * the second copy of the switch that &sect;3.B.7 says is how the paths drift.
     */
    @Test
    void aStaticWallGetsAPanelOnlyWhenTheVanillaRouteIsOff() {
        try {
            VanillaArt.force(false);
            assertTrue(PaintPanels.panelOnStaticSurface(),
                    "with the route off a wall must still get a panel");
            VanillaArt.force(true);
            assertFalse(PaintPanels.panelOnStaticSurface(),
                    "with the route on a wall must NOT get a panel, or a modded client sees two "
                            + "pictures a block apart");
        } finally {
            VanillaArt.force(null);
        }
    }

    /**
     * <b>The route is ON, and only a TEST can say otherwise —.</b>
     *
     * <p>This used to pin both directions of a system property, on the reasoning that {@code off}
     * was *"the both-ways gate's only lever and the escape hatch for a world that wants the panel
     * renderer back"*. The first half was true and is now {@link VanillaArt#force}; the second half
     * is what settled against — a panel is <b>invisible to a stock client</b>, so an escape
     * hatch a grown-up can set is a way to make a child's picture disappear.
     *
     * <p>So the property is gone and what is pinned is the pair that survived it: the shipped
     * answer cannot move, and the A/B seam still works. The seam is asserted in BOTH directions
     * because a seam that only answers one way cannot measure an arm.
     */
    @Test
    void theRouteIsOnAndOnlyTheTestSeamCanSayOtherwise() {
        VanillaArt.force(null);
        try {
            assertTrue(VanillaArt.enabled(), "the art route is ON in every shipped run");
            VanillaArt.force(false);
            assertFalse(VanillaArt.enabled(), "the A/B seam must still reach the panel arm");
            VanillaArt.force(true);
            assertTrue(VanillaArt.enabled());
        } finally {
            VanillaArt.force(null);
        }
        assertTrue(VanillaArt.enabled(), "and clearing the seam restores the shipped answer");
    }

    /**
     * {@code split()} now looks each distinct colour up once. It must give exactly the bytes a
     * per-pixel search gives — including transparency and a colour that sits between two entries.
     */
    @Test
    void theColourMemoGivesTheSameBytesAsSearchingEveryPixel() {
        MapArt.Palette pal = basic();
        int w = VanillaArt.TILE * 2;
        int h = VanillaArt.TILE;
        int[] argb = new int[w * h];
        int[] colours = {0, 0xFFFF0000, 0xFF00FF00, 0xFF7F7F10, 0x40FFFFFF, 0xFF123456};
        for (int i = 0; i < argb.length; i++) {
            argb[i] = colours[(i * 7 + i / 13) % colours.length];
        }
        byte[][] tiles = MapArt.split(argb, w, h, 2, 1, pal);
        for (int t = 0; t < 2; t++) {
            for (int y = 0; y < VanillaArt.TILE; y++) {
                for (int x = 0; x < VanillaArt.TILE; x++) {
                    int p = argb[y * w + t * VanillaArt.TILE + x];
                    assertEquals(MapArt.nearest(p, pal), tiles[t][y * VanillaArt.TILE + x],
                            "tile " + t + " pixel " + x + "," + y);
                }
            }
        }
    }
}
