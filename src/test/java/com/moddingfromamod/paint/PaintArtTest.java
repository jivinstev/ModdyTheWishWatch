package com.moddingfromamod.paint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.Test;

/**
 * <b>Gate A for the painter, and for the calibration card the client gate photographs.</b>
 *
 * <p>The card is the instrument {@code PaintPanelClientTest} measures orientation with, so if the
 * card itself is painted wrong every verdict it produces is confidently backwards — a mirrored card
 * would report a correct renderer as mirrored, and nothing downstream could tell the difference.
 * <b>Calibrate the instrument before trusting a reading it gives.</b> That is the "a negative from an
 * instrument that was never pointed at anything is not evidence" rule, applied one step earlier.
 *
 * <p>This runs the REAL writer — {@link CreatureFiles#writeTexture}, the same call that paints every
 * creature's skin — into a temp file and reads the pixels back, so it also pins the two properties
 * of that writer a second copy would drift on: rectangles are painted <b>in order</b>, and anything
 * unpainted stays <b>transparent</b> rather than black.
 */
class PaintArtTest {

    private static final int SIZE = 64;
    private static final int RED = 0xFF2020;
    private static final int GREEN = 0x20FF20;
    private static final int YELLOW = 0xFFFF20;
    private static final int BLUE = 0x2040FF;

    @Test
    void theCalibrationCardHasItsFourColoursInTheFourCornersOfTheImage() throws Exception {
        BufferedImage img = paint(PaintArt.testCard(SIZE, RED, GREEN, YELLOW, BLUE));

        // Quarter-way into each quadrant: what the client gate samples, in image coordinates where
        // (0,0) is the TOP-LEFT pixel -- the same convention PanelGeometry maps u,v = 0,0 to.
        assertEquals(RED, rgb(img, SIZE / 4, SIZE / 4), "top-left of the PNG is not the first colour");
        assertEquals(GREEN, rgb(img, 3 * SIZE / 4, SIZE / 4), "top-right");
        assertEquals(BLUE, rgb(img, SIZE / 4, 3 * SIZE / 4), "bottom-left");
        assertEquals(YELLOW, rgb(img, 3 * SIZE / 4, 3 * SIZE / 4), "bottom-right");
    }

    @Test
    void theCardsFourColoursAreTellableApart() throws Exception {
        // The client gate classifies a sampled pixel by NEAREST colour, so two quadrants that are
        // merely similar would make a flip undetectable while every assertion still passed.
        int[] palette = {RED, GREEN, YELLOW, BLUE};
        for (int i = 0; i < palette.length; i++) {
            for (int j = i + 1; j < palette.length; j++) {
                assertTrue(distance(palette[i], palette[j]) > 10000,
                        "two card colours are too close to tell apart: "
                                + Integer.toHexString(palette[i]) + " vs "
                                + Integer.toHexString(palette[j]));
            }
        }
    }

    @Test
    void theCardHasAFrameSoAMisplacedShotIsObvious() throws Exception {
        BufferedImage img = paint(PaintArt.testCard(SIZE, RED, GREEN, YELLOW, BLUE));
        // A frame that is not black would be indistinguishable from a quadrant at the edges, which
        // is exactly where a slightly-off camera samples.
        assertEquals(0x000000, rgb(img, 0, 0), "no frame at the corner");
        assertEquals(0x000000, rgb(img, SIZE - 1, SIZE - 1), "no frame at the far corner");
        assertEquals(0x000000, rgb(img, SIZE / 2, 0), "no frame along the top edge");
    }

    @Test
    void everyPixelOfTheCardIsOpaque() throws Exception {
        // A transparent pixel photographs as whatever is behind it, which on a panel is the white
        // block -- a sample landing there would read as "nothing is painted" on a good renderer.
        BufferedImage img = paint(PaintArt.testCard(SIZE, RED, GREEN, YELLOW, BLUE));
        for (int y = 0; y < SIZE; y += 3) {
            for (int x = 0; x < SIZE; x += 3) {
                assertEquals(0xFF, (img.getRGB(x, y) >>> 24) & 0xFF,
                        "transparent pixel at " + x + "," + y);
            }
        }
    }

    @Test
    void rectanglesArePaintedInTheOrderTheyAreGiven() throws Exception {
        // This is the whole reason a pattern can sit on top of a base colour. A writer that
        // painted in any other order would still produce a plausible image.
        BufferedImage img = paint(List.of(
                new CreatureSculpt.PaintRect(0, 0, SIZE, SIZE, RED),
                new CreatureSculpt.PaintRect(0, 0, SIZE / 2, SIZE / 2, GREEN)));
        assertEquals(GREEN, rgb(img, 1, 1), "the second rectangle did not land on top");
        assertEquals(RED, rgb(img, SIZE - 1, SIZE - 1), "the first rectangle was lost");
    }

    @Test
    void whatIsNotPaintedStaysTransparentRatherThanBlack() throws Exception {
        BufferedImage img = paint(List.of(
                new CreatureSculpt.PaintRect(0, 0, 4, 4, RED)));
        assertEquals(0, (img.getRGB(SIZE - 1, SIZE - 1) >>> 24) & 0xFF,
                "unpainted space came out opaque, so it would bleed into whatever it sits beside");
    }

    @Test
    void aRectangleOffTheEdgeIsClippedRatherThanThrowing() throws Exception {
        // These numbers come from an agent acting on a child's description.
        BufferedImage img = paint(List.of(
                new CreatureSculpt.PaintRect(-10, -10, SIZE * 4, SIZE * 4, BLUE),
                new CreatureSculpt.PaintRect(SIZE + 5, SIZE + 5, 20, 20, RED)));
        assertEquals(BLUE, rgb(img, 0, 0));
        assertEquals(BLUE, rgb(img, SIZE - 1, SIZE - 1));
    }

    @Test
    void anEmptyOrNullRecipeStillProducesAnImage() throws Exception {
        // A picture nobody described must be a blank one, not a crash on the way to the child.
        assertEquals(SIZE, paint(List.of()).getWidth());
        assertEquals(SIZE, paint(null).getWidth());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private static BufferedImage paint(List<CreatureSculpt.PaintRect> rects) throws Exception {
        Path dir = Files.createTempDirectory("paintart");
        Path file = dir.resolve("texture.png");
        CreatureFiles.writeTexture(file, SIZE, rects);
        assertTrue(Files.isRegularFile(file), "the painter wrote no file");
        BufferedImage img = ImageIO.read(file.toFile());
        Files.deleteIfExists(file);
        Files.deleteIfExists(dir);
        return img;
    }

    private static int rgb(BufferedImage img, int x, int y) {
        return img.getRGB(x, y) & 0xFFFFFF;
    }

    private static long distance(int a, int b) {
        long dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        long dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        long db = (a & 0xFF) - (b & 0xFF);
        return dr * dr + dg * dg + db * db;
    }
}
