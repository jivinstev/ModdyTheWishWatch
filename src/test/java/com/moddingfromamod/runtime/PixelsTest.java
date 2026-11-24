package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PixelsTest {

    static BufferedImage of(int w, int h, int type, int... argb) {
        BufferedImage img = new BufferedImage(w, h, type);
        img.setRGB(0, 0, w, h, argb, 0, w);
        return img;
    }

    @Test
    @DisplayName("a copy at its own size is exact, and clipped at the edges")
    void copyIsExactAndClipped() {
        BufferedImage src = of(2, 2, BufferedImage.TYPE_INT_RGB, 0x112233, 0x445566, 0x778899, 0xaabbcc);
        BufferedImage dst = new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB);
        Pixels.draw(dst, src, -1, 1);
        assertEquals(0xff445566, dst.getRGB(0, 1));
        assertEquals(0xffaabbcc, dst.getRGB(0, 2));
        assertEquals(0xff000000, dst.getRGB(1, 1), "nothing drawn outside the source");
        Pixels.draw(dst, src, 5, 5);   // wholly outside: nothing, no exception
    }

    @Test
    @DisplayName("halving averages each 2x2 block, and an even scale is symmetric")
    void halving() {
        BufferedImage src = of(2, 2, BufferedImage.TYPE_INT_RGB, 0x000000, 0xc8c8c8, 0xc8c8c8, 0x000000);
        BufferedImage one = Pixels.scaled(src, 1, 1, BufferedImage.TYPE_INT_RGB);
        assertEquals(0xff646464, one.getRGB(0, 0));
    }

    @Test
    @DisplayName("enlarging is bilinear between pixel centres, with the edges clamped")
    void enlarging() {
        BufferedImage src = of(2, 1, BufferedImage.TYPE_INT_RGB, 0x000000, 0xffffff);
        BufferedImage four = Pixels.scaled(src, 4, 1, BufferedImage.TYPE_INT_RGB);
        assertEquals(0x00, four.getRGB(0, 0) & 0xff, "clamped at the left edge");
        assertEquals(0x40, four.getRGB(1, 0) & 0xff);
        assertEquals(0xbf, four.getRGB(2, 0) & 0xff);
        assertEquals(0xff, four.getRGB(3, 0) & 0xff, "clamped at the right edge");
    }

    @Test
    @DisplayName("a transparent neighbour does not darken a picture (premultiplied), and alpha draws source-over")
    void alpha() {
        BufferedImage src = of(2, 1, BufferedImage.TYPE_INT_ARGB, 0xffff0000, 0x00000000);
        BufferedImage half = Pixels.scaled(src, 1, 1, BufferedImage.TYPE_INT_ARGB);
        assertEquals(0x80ff0000, half.getRGB(0, 0), "half as opaque, still pure red");
        BufferedImage bg = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Pixels.fill(bg, 0, 0, 1, 1, 0x0000ff);
        Pixels.draw(bg, of(1, 1, BufferedImage.TYPE_INT_ARGB, 0x80ff0000), 0, 0);
        assertEquals(0xff80007f, bg.getRGB(0, 0), "red half over blue");
    }

    @Test
    @DisplayName("fill and label: the label's pixels are lit, in its colour, above the baseline")
    void fillAndLabel() {
        BufferedImage img = new BufferedImage(40, 12, BufferedImage.TYPE_INT_RGB);
        Pixels.fill(img, 0, 0, 40, 12, 0x141618);
        assertEquals(0xff141618, img.getRGB(39, 11));
        Pixels.label(img, "90°", 1, 9, 0x9aa4a8);
        int lit = 0;
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 40; x++) {
                if (img.getRGB(x, y) == 0xff9aa4a8) {
                    lit++;
                    assertTrue(y >= 2 && y < 9, "inside the 7-pixel ascent: y=" + y);
                }
            }
        }
        assertTrue(lit > 20, "three glyphs drawn: " + lit);
    }
}
