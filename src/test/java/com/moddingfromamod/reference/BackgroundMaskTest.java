package com.moddingfromamod.reference;

import java.awt.image.BufferedImage;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** step 3: a subject mask for a picture with no alpha. */
class BackgroundMaskTest {

    private static BufferedImage opaque(int w, int h, int fill) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, fill);
            }
        }
        return img;
    }

    private static void rect(BufferedImage img, int x0, int y0, int x1, int y1, int c) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                img.setRGB(x, y, c);
            }
        }
    }

    @Test
    @DisplayName("a subject on a plain backdrop is measured as the subject, not the backdrop")
    void aPlainBackdropIsRemoved() {
        BufferedImage img = opaque(300, 300, 0xFFFFFF);
        rect(img, 100, 100, 200, 200, 0xE03030);
        // Unmasked, the palette would be the white page: the case that rejected every photo.
        assertTrue(!ReferencePalette.usable(ReferencePalette.of(img)));
        var masked = ReferencePalette.of(BackgroundMask.apply(img));
        assertTrue(ReferencePalette.usable(masked));
        assertEquals(100.0, masked.get("red"), 0.01);
    }

    @Test
    @DisplayName("FLOOD FILL, not colour: a white belly inside an outline survives a white page")
    void enclosedBackdropColourIsKept() {
        BufferedImage img = opaque(300, 300, 0xFFFFFF);
        rect(img, 80, 80, 220, 220, 0x101010);      // the outline
        rect(img, 100, 100, 200, 200, 0xFFFFFF);    // the white inside it
        var p = ReferencePalette.of(BackgroundMask.apply(img));
        assertTrue(p.getOrDefault("white", 0.0) > 40, "the enclosed white was removed: " + p);
        assertTrue(p.getOrDefault("black", 0.0) > 40, p.toString());
    }

    @Test
    @DisplayName("no backdrop to find — a busy photo — is REFUSED, never guessed at")
    void aBusyBorderIsRefused() {
        BufferedImage img = opaque(200, 200, 0);
        Random r = new Random(7);
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 200; x++) {
                img.setRGB(x, y, r.nextInt(0xFFFFFF));
            }
        }
        assertNull(BackgroundMask.apply(img));
    }

    @Test
    @DisplayName("a speck, or a frame that is all backdrop, is not a subject")
    void implausibleSubjectsAreRefused() {
        BufferedImage speck = opaque(300, 300, 0xFFFFFF);
        rect(speck, 148, 148, 152, 152, 0xE03030);
        assertNull(BackgroundMask.apply(speck));
        BufferedImage frame = opaque(300, 300, 0x2060E0);
        rect(frame, 0, 0, 300, 4, 0xFFFFFF);         // a thin white strip: no subject, all "bg"
        assertNull(BackgroundMask.apply(frame));
    }

    @Test
    @DisplayName("a render that already has alpha is returned UNTOUCHED")
    void anAlphaRenderIsLeftAlone() {
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        rect(img, 50, 50, 150, 150, 0xFF30A030);
        assertSame(img, BackgroundMask.apply(img));
        assertNotNull(BackgroundMask.apply(img));
    }

    @Test
    @DisplayName("the working copy is capped, so a 4000 px photo costs what a 512 px one does")
    void theWorkingCopyIsSmall() {
        BufferedImage img = opaque(2000, 1000, 0xFFFFFF);
        rect(img, 600, 300, 1400, 700, 0x30A030);
        BufferedImage out = BackgroundMask.apply(img);
        assertTrue(out.getWidth() <= BackgroundMask.WORK_EDGE, String.valueOf(out.getWidth()));
    }
}
