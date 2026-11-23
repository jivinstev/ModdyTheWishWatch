package com.moddingfromamod.paint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A skin becomes the picture the game would draw.</b> Each vanilla rule
 * {@link SkinPicture} copies is driven here by a skin built pixel by pixel, so the rule — not a
 * committed PNG of somebody — is the fixture. Steve and Alex come from the game's own assets.
 */
class SkinPictureTest {

    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;
    private static final int GREEN = 0xFF00FF00;
    private static final int SKIN = 0xFFE0B090;

    /** A 64×H skin filled with one opaque colour. */
    private static BufferedImage skin(int h, int fill) {
        BufferedImage img = new BufferedImage(64, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < 64; x++) {
                img.setRGB(x, y, fill);
            }
        }
        return img;
    }

    private static void fill(BufferedImage img, int x0, int y0, int w, int h, int argb) {
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                img.setRGB(x, y, argb);
            }
        }
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** The colour the recipe paints at a picture pixel, or -1 where nothing is painted. */
    private static int at(PaintRecipe r, int x, int y) {
        int[] px = CreatureSculpt.rasterise(r.size(), r.rects());
        int c = px[y * r.size() + x];
        return (c >>> 24) == 0 ? -1 : c & 0xFFFFFF;
    }

    @Test
    @DisplayName("a modern skin's hat with holes is drawn over the face, and only where it is opaque")
    void theHatLayerOverlaysTheFace() throws IOException {
        BufferedImage img = skin(64, 0x00000000);
        fill(img, 8, 8, 8, 8, SKIN);            // face
        fill(img, 40, 8, 8, 2, RED);            // a red brim across the top two rows of the hat
        PaintRecipe r = SkinPicture.recipe(png(img), false, SkinPicture.View.FACE);
        assertEquals(PaintArt.SIZE, r.size());
        assertEquals(0xFF0000, at(r, 10, 10), "the hat's opaque rows paint over the face");
        assertEquals(SKIN & 0xFFFFFF, at(r, 128, 200), "the face shows through the hat's holes");
        assertEquals(2, r.rects().size(), "one rect for the face below, one for the brim");
    }

    @Test
    @DisplayName("a LEGACY 64x32 skin's hat is ignored when it has no transparent pixel (Notch's rule)")
    void anOpaqueLegacyHatIsIgnored() throws IOException {
        BufferedImage img = skin(32, BLUE);     // the whole legacy sheet opaque, hat region included
        fill(img, 8, 8, 8, 8, SKIN);
        PaintRecipe r = SkinPicture.recipe(png(img), false, SkinPicture.View.FACE);
        assertEquals(SKIN & 0xFFFFFF, at(r, 128, 128), "an opaque legacy hat would be a solid "
                + "helmet over every old skin; vanilla ignores it and so must this");
        assertEquals(1, r.rects().size());
    }

    @Test
    @DisplayName("a LEGACY hat with even one hole is a real hat and is drawn")
    void aLegacyHatWithAHoleIsDrawn() throws IOException {
        BufferedImage img = skin(32, BLUE);
        fill(img, 8, 8, 8, 8, SKIN);
        img.setRGB(63, 31, 0x00000000);         // one hole anywhere in the 32..64 x 0..32 region
        PaintRecipe r = SkinPicture.recipe(png(img), false, SkinPicture.View.FACE);
        assertEquals(0x0000FF, at(r, 128, 128), "the blue hat now covers the face");
    }

    @Test
    @DisplayName("a LEGACY skin's left arm and leg are the right ones mirrored")
    void legacyLeftLimbsAreMirrored() throws IOException {
        BufferedImage img = skin(32, 0xFF808080);
        fill(img, 32, 0, 32, 16, 0x00000000);   // a hat with holes, so it is honoured (and empty)
        fill(img, 44, 20, 1, 12, RED);          // the OUTER column of his right arm's front
        fill(img, 4, 20, 1, 12, GREEN);         // the outer column of his right leg's front
        PaintRecipe r = SkinPicture.recipe(png(img), false, SkinPicture.View.FRONT);
        int sx = PaintArt.SIZE / 16;
        int sy = PaintArt.SIZE / 32;
        // Viewer's left (his right arm): x=0 is the outer column. His left arm is x=12..15, and
        // its outer column is x=15 — the mirror.
        assertEquals(0xFF0000, at(r, 0, 10 * sy));
        assertEquals(0xFF0000, at(r, 15 * sx + 1, 10 * sy), "his left arm is the right arm flipped");
        assertEquals(0x808080, at(r, 12 * sx + 1, 10 * sy));
        assertEquals(0x00FF00, at(r, 4 * sx + 1, 25 * sy));
        assertEquals(0x00FF00, at(r, 11 * sx + 1, 25 * sy), "his left leg is the right leg flipped");
    }

    @Test
    @DisplayName("SLIM arms are 3 pixels, so the outer column of the front view is empty")
    void slimArmsLeaveAGap() throws IOException {
        BufferedImage img = skin(64, 0xFF808080);
        fill(img, 32, 0, 32, 16, 0x00000000);   // hat
        fill(img, 0, 32, 64, 16, 0x00000000);   // second layers
        fill(img, 48, 48, 16, 16, 0x00000000);
        fill(img, 0, 48, 16, 16, 0x00000000);
        PaintRecipe classic = SkinPicture.recipe(png(img), false, SkinPicture.View.FRONT);
        PaintRecipe slim = SkinPicture.recipe(png(img), true, SkinPicture.View.FRONT);
        int sy = PaintArt.SIZE / 32;
        assertEquals(0x808080, at(classic, 1, 12 * sy));
        assertEquals(-1, at(slim, 1, 12 * sy), "a slim arm starts one pixel in from the edge");
        assertEquals(-1, at(slim, PaintArt.SIZE - 2, 12 * sy));
        assertEquals(0x808080, at(slim, PaintArt.SIZE / 16 + 1, 12 * sy));
    }

    @Test
    @DisplayName("a first-layer pixel with alpha is still opaque (vanilla's setNoAlpha)")
    void theFirstLayerIsAlwaysOpaque() throws IOException {
        BufferedImage img = skin(64, 0x00000000);
        fill(img, 8, 8, 8, 8, 0x10E0B090);      // a nearly-invisible face
        PaintRecipe r = SkinPicture.recipe(png(img), false, SkinPicture.View.FACE);
        assertEquals(SKIN & 0xFFFFFF, at(r, 5, 5));
    }

    @Test
    @DisplayName("an overlay pixel below alpha 128 is not drawn; at 128 it is")
    void theOverlayCutoffIs128() throws IOException {
        BufferedImage img = skin(64, 0x00000000);
        fill(img, 8, 8, 8, 8, SKIN);
        img.setRGB(40, 8, 0x7FFF0000);
        img.setRGB(41, 8, 0x80FF0000);
        PaintRecipe r = SkinPicture.recipe(png(img), false, SkinPicture.View.FACE);
        assertEquals(SKIN & 0xFFFFFF, at(r, 5, 5), "alpha 127 is a hole");
        assertEquals(0xFF0000, at(r, 32 + 5, 5), "alpha 128 is drawn");
    }

    @Test
    @DisplayName("anything that is not a 64x64 or 64x32 PNG is refused with a reason")
    void notASkinIsRefused() throws IOException {
        assertThrows(SkinPicture.NotASkin.class,
                () -> SkinPicture.recipe(png(new BufferedImage(128, 128,
                        BufferedImage.TYPE_INT_ARGB)), false, SkinPicture.View.FACE));
        assertThrows(SkinPicture.NotASkin.class,
                () -> SkinPicture.recipe("not a png".getBytes(), false, SkinPicture.View.FACE));
        assertThrows(SkinPicture.NotASkin.class,
                () -> SkinPicture.recipe(new byte[0], false, SkinPicture.View.FACE));
    }

    @Test
    @DisplayName("a view's board keeps its pixels square: face 1:1, front 1:2")
    void theBoardShapeFollowsTheView() {
        assertEquals(3, SkinPicture.View.FACE.tallFor(3));
        assertEquals(6, SkinPicture.View.FRONT.tallFor(3));
        assertEquals(SkinPicture.View.FACE, SkinPicture.View.of(null));
        assertEquals(SkinPicture.View.FACE, SkinPicture.View.of("legs"));
        assertEquals(SkinPicture.View.FRONT, SkinPicture.View.of(" Front "));
    }

    private static byte[] vanilla(String path) throws IOException {
        try (InputStream in = SkinPictureTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " is not on the test classpath — the client assets moved");
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("Steve and Alex, from the game's own textures, paint well under the cap")
    void steveAndAlexPaint() throws IOException {
        byte[] steve = vanilla("/assets/minecraft/textures/entity/player/wide/steve.png");
        byte[] alex = vanilla("/assets/minecraft/textures/entity/player/slim/alex.png");
        for (var view : SkinPicture.View.values()) {
            PaintRecipe s = SkinPicture.recipe(steve, false, view);
            PaintRecipe a = SkinPicture.recipe(alex, true, view);
            assertTrue(s.rects().size() > 10 && s.rects().size() < PaintRecipe.MAX_RECTS,
                    "steve " + view + ": " + s.rects().size());
            assertTrue(a.rects().size() > 10 && a.rects().size() < PaintRecipe.MAX_RECTS,
                    "alex " + view + ": " + a.rects().size());
        }
        // Every pixel of a face is painted: the head's first layer is opaque by rule.
        PaintRecipe face = SkinPicture.recipe(steve, false, SkinPicture.View.FACE);
        int[] px = CreatureSculpt.rasterise(face.size(), face.rects());
        for (int c : px) {
            assertEquals(0xFF, c >>> 24, "a face has no holes");
        }
        // Alex's front leaves the slim-arm columns empty; Steve's does not.
        assertEquals(-1, at(SkinPicture.recipe(alex, true, SkinPicture.View.FRONT), 1, 100));
        assertTrue(at(SkinPicture.recipe(steve, false, SkinPicture.View.FRONT), 1, 100) >= 0);
    }
}
