package com.moddingfromamod.runtime;

import java.awt.image.BufferedImage;

/**
 * <b>Moddy's own pixel drawing: scale, draw at an offset, fill, and a small label font.</b> It reads and writes pixel
 * arrays only ({@link BufferedImage#getRGB}/{@link BufferedImage#setRGB}) and never asks AWT for a {@code Graphics}.
 *
 * <p>Why not {@code createGraphics()}: on macOS, the first Java2D drawing in the JVM builds AWT's
 * {@code CGraphicsEnvironment}, which starts the Mac desktop toolkit ({@code LWCToolkit}, {@code initAppkit}) from
 * whatever thread drew. In the game that thread is a wish's background thread while GLFW owns the main thread, and the
 * game's window froze for good (#2822). {@code java.awt.headless=true} does not prevent it: the JDK builds the Mac
 * toolkit first and only then wraps it as headless (and Minecraft's client sets headless itself). So the core does no
 * Java2D drawing at all; {@code NoJava2dTest} (Gate A) fails a {@code createGraphics}, {@code getGraphics} or
 * {@code Graphics2D} anywhere under {@code src/main}.
 *
 * <p>Scaling is bilinear, sampled at pixel centres with the edges clamped, like the Java2D
 * {@code VALUE_INTERPOLATION_BILINEAR} it replaces. Colours are interpolated with premultiplied alpha and drawn over
 * what is already there (source-over), so a transparent edge does not darken a picture.
 */
public final class Pixels {

    private Pixels() {
    }

    /** {@code src} scaled to {@code w}×{@code h} into a new image of {@code type}, over transparent black. */
    public static BufferedImage scaled(BufferedImage src, int w, int h, int type) {
        BufferedImage out = new BufferedImage(Math.max(1, w), Math.max(1, h), type);
        draw(out, src, 0, 0, out.getWidth(), out.getHeight());
        return out;
    }

    /** {@code src} at its own size with its top-left corner at ({@code x},{@code y}) on {@code dst}, clipped. */
    public static void draw(BufferedImage dst, BufferedImage src, int x, int y) {
        draw(dst, src, x, y, src.getWidth(), src.getHeight());
    }

    /**
     * {@code src} scaled to {@code w}×{@code h} with its top-left corner at ({@code x},{@code y}) on {@code dst},
     * clipped to {@code dst}, drawn source-over.
     */
    public static void draw(BufferedImage dst, BufferedImage src, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(dst.getWidth(), x + w), y1 = Math.min(dst.getHeight(), y + h);
        if (x0 >= x1 || y0 >= y1) {
            return;
        }
        int sw = src.getWidth(), sh = src.getHeight();
        int[] s = src.getRGB(0, 0, sw, sh, null, 0, sw);
        int cw = x1 - x0, ch = y1 - y0;
        int[] d = dst.getRGB(x0, y0, cw, ch, null, 0, cw);
        boolean dstAlpha = dst.getColorModel().hasAlpha();
        boolean same = sw == w && sh == h;
        double kx = sw / (double) w, ky = sh / (double) h;
        for (int j = 0; j < ch; j++) {
            int dy = y0 + j - y;
            for (int i = 0; i < cw; i++) {
                int dx = x0 + i - x;
                int a, r, g, b;   // premultiplied
                if (same) {
                    int p = s[dy * sw + dx];
                    a = p >>> 24;
                    r = ((p >> 16) & 0xff) * a / 255;
                    g = ((p >> 8) & 0xff) * a / 255;
                    b = (p & 0xff) * a / 255;
                } else {
                    double fx = (dx + 0.5) * kx - 0.5, fy = (dy + 0.5) * ky - 0.5;
                    int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy);
                    double tx = fx - ix, ty = fy - iy;
                    int xa = clamp(ix, sw), xb = clamp(ix + 1, sw), ya = clamp(iy, sh), yb = clamp(iy + 1, sh);
                    double[] acc = new double[4];
                    add(acc, s[ya * sw + xa], (1 - tx) * (1 - ty));
                    add(acc, s[ya * sw + xb], tx * (1 - ty));
                    add(acc, s[yb * sw + xa], (1 - tx) * ty);
                    add(acc, s[yb * sw + xb], tx * ty);
                    a = round(acc[0]);
                    r = round(acc[1]);
                    g = round(acc[2]);
                    b = round(acc[3]);
                }
                int k = j * cw + i;
                d[k] = over(a, r, g, b, d[k], dstAlpha);
            }
        }
        dst.setRGB(x0, y0, cw, ch, d, 0, cw);
    }

    /** Fill a rectangle of {@code dst} (clipped) with an opaque {@code rgb}. */
    public static void fill(BufferedImage dst, int x, int y, int w, int h, int rgb) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(dst.getWidth(), x + w), y1 = Math.min(dst.getHeight(), y + h);
        if (x0 >= x1 || y0 >= y1) {
            return;
        }
        int[] row = new int[x1 - x0];
        java.util.Arrays.fill(row, 0xff000000 | rgb);
        for (int j = y0; j < y1; j++) {
            dst.setRGB(x0, j, x1 - x0, 1, row, 0, x1 - x0);
        }
    }

    // ---- a label font: digits, minus, degree sign and space, 5 pixels wide and 7 tall -------------------------

    /** Rows of 5 bits, top first, for each character the labels use. */
    private static int[] glyph(char c) {
        return switch (c) {
            case '0' -> new int[]{0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110};
            case '1' -> new int[]{0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110};
            case '2' -> new int[]{0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111};
            case '3' -> new int[]{0b11110, 0b00001, 0b00001, 0b01110, 0b00001, 0b00001, 0b11110};
            case '4' -> new int[]{0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010};
            case '5' -> new int[]{0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110};
            case '6' -> new int[]{0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110};
            case '7' -> new int[]{0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000};
            case '8' -> new int[]{0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110};
            case '9' -> new int[]{0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100};
            case '-' -> new int[]{0, 0, 0, 0b11111, 0, 0, 0};
            case '°' -> new int[]{0b01100, 0b10010, 0b10010, 0b01100, 0, 0, 0};
            default -> new int[]{0, 0, 0, 0, 0, 0, 0};
        };
    }

    /** Height in pixels of {@link #label}'s characters above the baseline. */
    public static final int LABEL_ASCENT = 7;

    /**
     * Write {@code text} (digits, '-', '°', spaces; anything else is a space) in an opaque {@code rgb}, its baseline at
     * {@code baselineY} and its left edge at {@code x}, clipped to {@code dst}.
     */
    public static void label(BufferedImage dst, String text, int x, int baselineY, int rgb) {
        int top = baselineY - LABEL_ASCENT;
        int px = x;
        for (char c : text.toCharArray()) {
            int[] rows = glyph(c);
            for (int r = 0; r < rows.length; r++) {
                for (int col = 0; col < 5; col++) {
                    if ((rows[r] >> (4 - col) & 1) != 0) {
                        int gx = px + col, gy = top + r;
                        if (gx >= 0 && gy >= 0 && gx < dst.getWidth() && gy < dst.getHeight()) {
                            dst.setRGB(gx, gy, 0xff000000 | rgb);
                        }
                    }
                }
            }
            px += 6;
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private static int clamp(int v, int n) {
        return v < 0 ? 0 : (v >= n ? n - 1 : v);
    }

    private static int round(double v) {
        int i = (int) Math.round(v);
        return i < 0 ? 0 : Math.min(255, i);
    }

    /** Add a straight-alpha pixel's premultiplied channels, weighted. */
    private static void add(double[] acc, int p, double wgt) {
        int a = p >>> 24;
        acc[0] += a * wgt;
        acc[1] += ((p >> 16) & 0xff) * a / 255.0 * wgt;
        acc[2] += ((p >> 8) & 0xff) * a / 255.0 * wgt;
        acc[3] += (p & 0xff) * a / 255.0 * wgt;
    }

    /** Premultiplied (a,r,g,b) source-over the straight-alpha pixel {@code d}; straight-alpha out. */
    private static int over(int a, int r, int g, int b, int d, boolean dstAlpha) {
        if (a == 255) {
            return 0xff000000 | (r << 16) | (g << 8) | b;
        }
        int da = dstAlpha ? d >>> 24 : 255;
        double inv = (255 - a) / 255.0;
        double dr = ((d >> 16) & 0xff) * da / 255.0, dg = ((d >> 8) & 0xff) * da / 255.0, db = (d & 0xff) * da / 255.0;
        double oa = a + da * inv;
        double or = r + dr * inv, og = g + dg * inv, ob = b + db * inv;
        if (oa <= 0) {
            return 0;
        }
        if (!dstAlpha) {
            return 0xff000000 | (round(or) << 16) | (round(og) << 8) | round(ob);
        }
        double un = 255.0 / oa;
        return (round(oa) << 24) | (round(or * un) << 16) | (round(og * un) << 8) | round(ob * un);
    }
}
