package com.moddingfromamod.reference;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * <b>A subject mask for a picture that has none (step 3).</b>
 *
 * <p>{@link ReferencePalette} uses the alpha channel as the subject mask, which suits a wiki's
 * cut-out renders and rejects every photograph and painting — so a real animal or a myth, whose
 * pictures are exactly those, was never measured at all. This estimates the background from the
 * BORDER and removes it, so an opaque picture of a subject on a plain backdrop (a museum photo, a
 * studio shot, a drawing on white) carries a mask like a render does.
 *
 * <p>Deliberately conservative, because a wrong mask is a wrong palette and a wrong palette is
 * worse than none:
 * <ul>
 *   <li>the border must be mostly ONE colour ({@link #MIN_UNIFORM}), or there is no backdrop to
 *       remove — a photo in a forest is refused, not guessed at;</li>
 *   <li>the backdrop is removed by FLOOD FILL from the edge, never by colour alone, so a white
 *       belly inside a black outline on a white page survives;</li>
 *   <li>what is left must be a plausible subject — neither a speck nor the whole frame.</li>
 * </ul>
 *
 * <p>Pure and Minecraft-free; unit-tested on synthetic pictures.
 */
public final class BackgroundMask {

    /** Share of the border that must match the backdrop colour. Below it there is no backdrop. */
    static final double MIN_UNIFORM = 0.6;

    /** RGB distance within which a pixel counts as the backdrop. */
    static final int TOLERANCE = 48;

    /** What must remain once the backdrop is gone, as a share of the frame. */
    static final double MIN_SUBJECT = 0.05;
    static final double MAX_SUBJECT = 0.95;

    /** The masked copy is at most this on its long edge — what the histogram samples anyway. */
    static final int WORK_EDGE = 512;

    private BackgroundMask() {
    }

    /** Whether the picture already carries a real mask (some clearly transparent pixels). */
    static boolean hasAlphaMask(BufferedImage img) {
        if (!img.getColorModel().hasAlpha()) {
            return false;
        }
        int stride = Math.max(1, Math.max(img.getWidth(), img.getHeight()) / WORK_EDGE);
        long total = 0;
        long clear = 0;
        for (int y = 0; y < img.getHeight(); y += stride) {
            for (int x = 0; x < img.getWidth(); x += stride) {
                total++;
                clear += ((img.getRGB(x, y) >>> 24) & 0xFF) < 128 ? 1 : 0;
            }
        }
        return total > 0 && (double) clear / total >= 1 - ReferencePalette.MAX_OPAQUE_FRACTION;
    }

    /**
     * The picture with its backdrop made transparent; the picture itself when it already has a
     * mask; or null when no backdrop can be told from the subject (refused, never guessed).
     */
    public static BufferedImage apply(BufferedImage img) {
        if (img == null || img.getWidth() < 8 || img.getHeight() < 8) {
            return null;
        }
        if (hasAlphaMask(img)) {
            return img;
        }
        int stride = Math.max(1, (int) Math.ceil(
                Math.max(img.getWidth(), img.getHeight()) / (double) WORK_EDGE));
        int w = img.getWidth() / stride;
        int h = img.getHeight() / stride;
        int[] rgb = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                rgb[y * w + x] = img.getRGB(x * stride, y * stride) & 0xFFFFFF;
            }
        }
        int ring = Math.max(1, Math.min(w, h) / 50);
        int bg = backdrop(rgb, w, h, ring);
        if (bg < 0) {
            return null;
        }
        boolean[] gone = new boolean[w * h];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean edge = x < ring || y < ring || x >= w - ring || y >= h - ring;
                int i = y * w + x;
                if (edge && near(rgb[i], bg)) {
                    gone[i] = true;
                    queue.add(i);
                }
            }
        }
        while (!queue.isEmpty()) {
            int i = queue.poll();
            int x = i % w;
            int y = i / w;
            int[] next = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1,
                y < h - 1 ? i + w : -1};
            for (int n : next) {
                if (n >= 0 && !gone[n] && near(rgb[n], bg)) {
                    gone[n] = true;
                    queue.add(n);
                }
            }
        }
        long kept = 0;
        for (boolean g : gone) {
            kept += g ? 0 : 1;
        }
        double subject = (double) kept / (w * h);
        if (subject < MIN_SUBJECT || subject > MAX_SUBJECT) {
            return null;
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < rgb.length; i++) {
            out.setRGB(i % w, i / w, gone[i] ? 0 : (0xFF000000 | rgb[i]));
        }
        return out;
    }

    /**
     * The backdrop colour (mean of the border's modal colour), or -1 when the border is not
     * mostly one colour.
     */
    static int backdrop(int[] rgb, int w, int h, int ring) {
        Map<Integer, Integer> bins = new HashMap<>();
        int border = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x < ring || y < ring || x >= w - ring || y >= h - ring) {
                    bins.merge(bin(rgb[y * w + x]), 1, Integer::sum);
                    border++;
                }
            }
        }
        int mode = bins.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(-1);
        if (mode < 0) {
            return -1;
        }
        long r = 0;
        long g = 0;
        long b = 0;
        int n = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((x < ring || y < ring || x >= w - ring || y >= h - ring)
                        && bin(rgb[y * w + x]) == mode) {
                    int c = rgb[y * w + x];
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                    n++;
                }
            }
        }
        int mean = (int) ((r / n) << 16 | (g / n) << 8 | (b / n));
        int matching = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((x < ring || y < ring || x >= w - ring || y >= h - ring)
                        && near(rgb[y * w + x], mean)) {
                    matching++;
                }
            }
        }
        return (double) matching / border >= MIN_UNIFORM ? mean : -1;
    }

    private static int bin(int c) {
        return ((c >> 20) & 0xF) << 8 | ((c >> 12) & 0xF) << 4 | ((c >> 4) & 0xF);
    }

    static boolean near(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return dr * dr + dg * dg + db * db <= TOLERANCE * TOLERANCE;
    }
}
