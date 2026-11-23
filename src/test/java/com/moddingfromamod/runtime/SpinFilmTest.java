package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/** a spin strip → its frames, the agent's quarter-turn picks, and an animated PNG. */
class SpinFilmTest {

    private static BufferedImage solid(int w, int h, int rgb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, 0xFF000000 | rgb);
            }
        }
        return img;
    }

    private static byte[] strip(int n, int fw, int h) {
        BufferedImage s = new BufferedImage(fw * n, h, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < n; i++) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < fw; x++) {
                    s.setRGB(i * fw + x, y, 0xFF000000 | (i * 10));
                }
            }
        }
        return SpinFilm.png(s);
    }

    @Test
    void aStripSplitsIntoItsFramesInOrder() {
        List<BufferedImage> f = SpinFilm.frames(strip(20, 32, 18), 20);
        assertEquals(20, f.size());
        for (int i = 0; i < 20; i++) {
            assertEquals(32, f.get(i).getWidth());
            assertEquals(18, f.get(i).getHeight());
            assertEquals(i * 10, f.get(i).getRGB(5, 5) & 0xFFFFFF, "frame " + i + " is the " + i + "th slice");
        }
        assertEquals(0, SpinFilm.frames(strip(20, 32, 18), 7).size(), "a count that does not divide is no split");
        assertEquals(0, SpinFilm.frames(new byte[] {1, 2}, 4).size());
        assertEquals(0, SpinFilm.frames(null, 4).size());
    }

    @Test
    void theQuarterTurnPicksAreEvenlyRound() {
        assertEquals(0, SpinFilm.at(0, 20));
        assertEquals(5, SpinFilm.at(0.25, 20));
        assertEquals(10, SpinFilm.at(0.5, 20));
        assertEquals(15, SpinFilm.at(0.75, 20));
        assertEquals(0, SpinFilm.at(1.0, 20), "a full turn is the start again");
        assertEquals(4, SpinFilm.at(0.25, 16));
        assertEquals(-1, SpinFilm.at(0.5, 0));
    }

    @Test
    void anApngIsWellFormedAndShowsFrameOneToAStillViewer() throws Exception {
        List<BufferedImage> frames = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            frames.add(solid(40, 24, 0x110000 * (i + 1)));
        }
        byte[] apng = SpinFilm.apng(frames, 100);
        assertNotNull(apng);
        assertEquals(6, SpinFilm.frameCount(apng));
        BufferedImage first = ImageIO.read(new ByteArrayInputStream(apng));
        assertNotNull(first, "a viewer that ignores APNG still decodes it");
        assertEquals(0x110000, first.getRGB(3, 3) & 0xFFFFFF, "and sees frame one");

        // every chunk's CRC is right, and fcTL/fdAT sequence numbers run 0,1,2… with no gap
        var chunks = SpinFilm.chunks(apng);
        assertEquals("IHDR", chunks.get(0).type());
        assertEquals("IEND", chunks.get(chunks.size() - 1).type());
        int expect = 0;
        int fctl = 0;
        int p = 8;
        for (var c : chunks) {
            int len = c.data().length;
            CRC32 crc = new CRC32();
            crc.update(apng, p + 4, 4 + len);
            int stored = ((apng[p + 8 + len] & 0xFF) << 24) | ((apng[p + 9 + len] & 0xFF) << 16)
                    | ((apng[p + 10 + len] & 0xFF) << 8) | (apng[p + 11 + len] & 0xFF);
            assertEquals((int) crc.getValue(), stored, "CRC of " + c.type());
            if (c.type().equals("fcTL") || c.type().equals("fdAT")) {
                int seq = ((c.data()[0] & 0xFF) << 24) | ((c.data()[1] & 0xFF) << 16)
                        | ((c.data()[2] & 0xFF) << 8) | (c.data()[3] & 0xFF);
                assertEquals(expect++, seq, "sequence numbers are contiguous");
                if (c.type().equals("fcTL")) {
                    fctl++;
                    int delayNum = ((c.data()[20] & 0xFF) << 8) | (c.data()[21] & 0xFF);
                    int delayDen = ((c.data()[22] & 0xFF) << 8) | (c.data()[23] & 0xFF);
                    assertEquals(100, delayNum);
                    assertEquals(1000, delayDen, "a delay in milliseconds");
                }
            }
            p += 12 + len;
        }
        assertEquals(6, fctl, "one frame control per frame");
        int actl = 0;
        for (var c : chunks) {
            if (c.type().equals("acTL")) {
                actl++;
            }
            assertTrue(!c.type().equals("IDAT") || actl == 1, "acTL comes before the image data");
        }
    }

    @Test
    void tooFewOrMismatchedFramesAreNoAnimation() {
        assertNull(SpinFilm.apng(List.of(solid(4, 4, 1)), 100));
        assertNull(SpinFilm.apng(List.of(solid(4, 4, 1), solid(5, 4, 1)), 100));
        assertNull(SpinFilm.apng(null, 100));
        assertEquals(0, SpinFilm.frameCount(SpinFilm.png(solid(4, 4, 1))), "a still has no acTL");
    }
}
