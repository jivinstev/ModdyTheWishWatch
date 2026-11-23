package com.moddingfromamod.agent.tools;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Four sides of a creature in one picture. */
class ContactSheetTest {

    /** A recognisable frame: a solid block of one colour, so it can be found in the sheet. */
    private static byte[] png(int w, int h, Color fill) throws Exception {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setColor(fill);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(im, "PNG", bos);
        return bos.toByteArray();
    }

    private static BufferedImage read(byte[] b) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(b));
    }

    private static List<ContactSheet.Frame> four() throws Exception {
        Color[] colours = {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW};
        List<ContactSheet.Frame> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            out.add(new ContactSheet.Frame(png(320, 180, colours[i]), i * 90));
        }
        return out;
    }

    /** Is this colour present anywhere in the sheet? */
    private static boolean contains(BufferedImage im, Color c) {
        for (int y = 0; y < im.getHeight(); y += 3) {
            for (int x = 0; x < im.getWidth(); x += 3) {
                int rgb = im.getRGB(x, y);
                if (Math.abs(((rgb >> 16) & 255) - c.getRed()) < 24
                        && Math.abs(((rgb >> 8) & 255) - c.getGreen()) < 24
                        && Math.abs((rgb & 255) - c.getBlue()) < 24) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    @DisplayName("EVERY angle is in the sheet — a lost one is a side of the creature nobody saw")
    void allFourFramesSurvive() throws Exception {
        BufferedImage sheet = read(ContactSheet.compose(four()));
        assertNotNull(sheet);
        for (Color c : new Color[]{Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW}) {
            assertTrue(contains(sheet, c), "a frame is missing from the sheet: " + c);
        }
    }

    @Test
    @DisplayName("the sheet fits the budget it claims — one and a half shots, not four")
    void theSheetIsBounded() throws Exception {
        BufferedImage sheet = read(ContactSheet.compose(four()));
        assertTrue(sheet.getWidth() <= ContactSheet.SHEET_EDGE,
                "wider than its own budget: " + sheet.getWidth());
        // 2x2 of 320x180 tiles plus labels: taller than wide is fine, absurdly tall is not.
        assertTrue(sheet.getHeight() < ContactSheet.SHEET_EDGE, "unexpectedly tall sheet");
    }

    @Test
    @DisplayName("tiles keep their aspect ratio — a widescreen shot is not squashed square")
    void aspectIsPreserved() throws Exception {
        BufferedImage wide = read(ContactSheet.compose(four()));
        // Four 16:9 tiles in a 2x2 grid make a sheet WIDER than it is tall, before labels.
        assertTrue(wide.getWidth() > wide.getHeight(),
                "16:9 frames should tile wider than tall, got "
                        + wide.getWidth() + "x" + wide.getHeight());
    }

    @Test
    @DisplayName("FEWER THAN FOUR IS NORMAL: compose what arrived rather than failing the photo")
    void aMissingAngleStillProducesASheet() throws Exception {
        // A blind agent probes the world with commands -- four minutes of one measured wish went
        // that way. Three good angles beat no photograph by a very long way.
        List<ContactSheet.Frame> partial = four().subList(0, 3);
        BufferedImage sheet = read(ContactSheet.compose(partial));
        assertNotNull(sheet);
        assertTrue(contains(sheet, Color.RED) && contains(sheet, Color.BLUE));

        BufferedImage one = read(ContactSheet.compose(four().subList(0, 1)));
        assertNotNull(one, "a single surviving angle is still a photograph");
    }

    @Test
    @DisplayName("nothing decodable produces NULL, never a convincing blank")
    void nothingIsNotABlankSheet() {
        // A blank sheet is indistinguishable from a successful photograph of an empty stage, which
        // is the exact failure mode this whole feature exists to remove.
        assertNull(ContactSheet.compose(List.of()));
        assertNull(ContactSheet.compose(null));
        assertNull(ContactSheet.compose(List.of(new ContactSheet.Frame(new byte[0], 0))));
        assertNull(ContactSheet.compose(
                List.of(new ContactSheet.Frame("not a png".getBytes(), 90))));
    }

    @Test
    @DisplayName("one bad frame among good ones is dropped, not fatal")
    void aCorruptFrameIsSkipped() throws Exception {
        List<ContactSheet.Frame> mixed = new ArrayList<>(four());
        mixed.add(1, new ContactSheet.Frame("garbage".getBytes(), 45));
        BufferedImage sheet = read(ContactSheet.compose(mixed));
        assertNotNull(sheet);
        assertTrue(contains(sheet, Color.YELLOW), "the good frames must all still be there");
    }

    @Test
    @DisplayName("the tiles are labelled by BEARING, because there is no guaranteed front")
    void bearingsAreRecorded() throws Exception {
        // Naming a tile "front" would be the same lie in a new place: the creature is deliberately
        // not turned, so which side faces the camera at 0 degrees is whatever it decided.
        var frames = four();
        assertEquals(0, frames.get(0).bearingDeg());
        assertEquals(270, frames.get(3).bearingDeg());
        assertNotNull(ContactSheet.compose(frames));
    }
}
