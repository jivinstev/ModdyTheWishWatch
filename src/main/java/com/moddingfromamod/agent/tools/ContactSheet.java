package com.moddingfromamod.agent.tools;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * <b>Four sides of a creature, in one picture.</b>
 *
 * <h2>Why a sheet rather than four images</h2>
 *
 * <p>{@code ToolResult} carries one image, and widening it to a list would be a protocol change for
 * every tool in order to serve one. A sheet is also how a person actually judges a model — all four
 * sides at once, compared against each other, rather than four separate judgements made in
 * sequence. And it is honest about cost: one 768px sheet is roughly one and a half 512px shots, not
 * four.
 *
 * <p>Each tile is labelled with its <b>camera bearing</b>, not "front" or "side". With the subject
 * untouched there is no longer any such thing as a guaranteed front — that is the whole point of
 * orbiting — so a tile claiming to be the front would be the same lie in a new place.
 *
 * <h2>Fewer than four is a normal outcome</h2>
 *
 * <p>A shot can fail to arrive: the window is minimised, a screenshot is still being written, the
 * game hitched. Composing from what did arrive and <b>saying how many</b> is much better than
 * failing the whole photograph — the agent is then blind, and a blind agent probes the world with
 * commands, which is where four minutes of one measured wish went.
 *
 * <p>Pure: {@code java.awt} only, no Minecraft, so the layout is unit-tested.
 */
public final class ContactSheet {

    private ContactSheet() {
    }

    /** Long edge of the finished sheet. Each tile is about half this. */
    public static final int SHEET_EDGE = 768;

    /** Gutter and label strip, in pixels of the finished sheet. */
    private static final int GAP = 6;
    private static final int LABEL_H = 16;

    /** One tile: the PNG bytes and the bearing the camera was at. */
    public record Frame(byte[] png, int bearingDeg) {
    }

    /**
     * Tile the frames into one PNG, left-to-right then top-to-bottom.
     *
     * @return the sheet, or {@code null} if nothing decodable was supplied — never a blank image,
     *         which would look exactly like a successful photograph of an empty stage
     */
    public static byte[] compose(List<Frame> frames) {
        List<BufferedImage> imgs = new ArrayList<>();
        List<Integer> bearings = new ArrayList<>();
        for (Frame f : frames == null ? List.<Frame>of() : frames) {
            BufferedImage im = decode(f.png());
            if (im != null) {
                imgs.add(im);
                bearings.add(f.bearingDeg());
            }
        }
        if (imgs.isEmpty()) {
            return null;
        }
        int cols = imgs.size() == 1 ? 1 : 2;
        int rows = (imgs.size() + cols - 1) / cols;

        // Tiles keep the source aspect ratio, so a widescreen shot is not squashed into a square.
        double aspect = (double) imgs.get(0).getWidth() / Math.max(1, imgs.get(0).getHeight());
        int tileW = (SHEET_EDGE - GAP * (cols + 1)) / cols;
        int tileH = (int) Math.round(tileW / Math.max(0.1, aspect));

        int w = cols * tileW + GAP * (cols + 1);
        int h = rows * (tileH + LABEL_H) + GAP * (rows + 1);
        BufferedImage sheet = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x14, 0x16, 0x18));
        g.fillRect(0, 0, w, h);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));

        for (int i = 0; i < imgs.size(); i++) {
            int cx = GAP + (i % cols) * (tileW + GAP);
            int cy = GAP + (i / cols) * (tileH + LABEL_H + GAP);
            g.drawImage(imgs.get(i), cx, cy, tileW, tileH, null);
            g.setColor(new Color(0x9a, 0xa4, 0xa8));
            g.drawString(bearings.get(i) + "°", cx + 2, cy + tileH + LABEL_H - 4);
        }
        g.dispose();

        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(sheet, "PNG", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static BufferedImage decode(byte[] png) {
        if (png == null || png.length == 0) {
            return null;
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (Exception e) {
            return null;
        }
    }
}
