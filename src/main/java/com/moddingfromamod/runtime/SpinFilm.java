package com.moddingfromamod.runtime;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

/**
 * <b>What a spin strip becomes</b> — Minecraft-free.
 *
 * <ul>
 *   <li>{@link #frames}: the strip cut back into its frames (the client writes them side by side).</li>
 *   <li>{@link #at}: the frame nearest a fraction of the turn — the agent's four-side sheet is the
 *       frames a quarter turn apart, so it keeps the judgement four stills used to give.</li>
 *   <li>{@link #apng}: an ANIMATED PNG. Every pipe a wish's photographs travel — {@code WishShots},
 *       a server's own media tools — accepts {@code .png} and
 *       nothing else, so the clip is a PNG that plays: the same file name rules, the same shipping,
 *       and a browser animates it in a plain {@code <img>}. A GIF or an MP4 would have needed each
 *       of those pipes widened, and a dedicated server may have no ffmpeg.</li>
 * </ul>
 */
public final class SpinFilm {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private SpinFilm() {
    }

    /** A horizontal strip of {@code n} equal frames, split. Empty when it cannot be read or split. */
    public static List<BufferedImage> frames(byte[] stripPng, int n) {
        List<BufferedImage> out = new ArrayList<>();
        BufferedImage strip = read(stripPng);
        if (strip == null || n < 1 || strip.getWidth() % n != 0) {
            return out;
        }
        int fw = strip.getWidth() / n;
        for (int i = 0; i < n; i++) {
            BufferedImage f = new BufferedImage(fw, strip.getHeight(), BufferedImage.TYPE_INT_RGB);   // no alpha: a quarter smaller
            Pixels.draw(f, strip, -i * fw, 0);   // no Java2D: it froze the game window on macOS (Pixels)
            out.add(f);
        }
        return out;
    }

    /** The frame index a fraction {@code t} ∈ [0,1) of the way round, of {@code n} frames. */
    public static int at(double t, int n) {
        if (n <= 0) {
            return -1;
        }
        double u = ((t % 1.0) + 1.0) % 1.0;
        return Math.min(n - 1, (int) Math.round(u * n) % n);
    }

    /** A frame scaled to {@code width} (aspect kept), smoothly — the filed animation is the small copy. */
    public static BufferedImage scaled(BufferedImage f, int width) {
        if (f == null || width <= 0 || f.getWidth() <= width) {
            return f;
        }
        int h = Math.max(1, (int) Math.round(f.getHeight() * (width / (double) f.getWidth())));
        return Pixels.scaled(f, width, h, BufferedImage.TYPE_INT_RGB);
    }

    /** One frame as a plain PNG. */
    public static byte[] png(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * An animated PNG of these frames, looping forever, {@code gapMs} each. Null for fewer than two
     * frames or frames of different sizes. Viewers that do not know APNG show frame one.
     */
    public static byte[] apng(List<BufferedImage> frames, int gapMs) {
        if (frames == null || frames.size() < 2) {
            return null;
        }
        int w = frames.get(0).getWidth();
        int h = frames.get(0).getHeight();
        for (BufferedImage f : frames) {
            if (f.getWidth() != w || f.getHeight() != h) {
                return null;
            }
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.write(SIGNATURE);
            int seq = 0;
            for (int i = 0; i < frames.size(); i++) {
                List<Chunk> chunks = chunks(png(frames.get(i)));
                if (i == 0) {
                    Chunk ihdr = chunks.get(0);
                    writeChunk(out, "IHDR", ihdr.data());
                    ByteArrayOutputStream ac = new ByteArrayOutputStream();
                    DataOutputStream a = new DataOutputStream(ac);
                    a.writeInt(frames.size());
                    a.writeInt(0);                           // loop forever
                    writeChunk(out, "acTL", ac.toByteArray());
                }
                writeChunk(out, "fcTL", fctl(seq++, w, h, gapMs));
                for (Chunk c : chunks) {
                    if (!"IDAT".equals(c.type())) {
                        continue;
                    }
                    if (i == 0) {
                        writeChunk(out, "IDAT", c.data());
                    } else {
                        ByteArrayOutputStream fd = new ByteArrayOutputStream();
                        DataOutputStream d = new DataOutputStream(fd);
                        d.writeInt(seq++);
                        d.write(c.data());
                        writeChunk(out, "fdAT", fd.toByteArray());
                    }
                }
            }
            writeChunk(out, "IEND", new byte[0]);
            return bytes.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** How many frames an APNG says it has ({@code acTL}), or 0 for a still PNG. For the tests. */
    public static int frameCount(byte[] png) {
        for (Chunk c : chunks(png)) {
            if ("acTL".equals(c.type()) && c.data().length >= 4) {
                return ((c.data()[0] & 0xFF) << 24) | ((c.data()[1] & 0xFF) << 16)
                        | ((c.data()[2] & 0xFF) << 8) | (c.data()[3] & 0xFF);
            }
        }
        return 0;
    }

    private static byte[] fctl(int seq, int w, int h, int gapMs) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        d.writeInt(seq);
        d.writeInt(w);
        d.writeInt(h);
        d.writeInt(0);                                       // x offset
        d.writeInt(0);                                       // y offset
        d.writeShort(Math.max(1, Math.min(65535, gapMs)));   // delay numerator
        d.writeShort(1000);                                  // delay denominator: milliseconds
        d.writeByte(0);                                      // dispose: none
        d.writeByte(0);                                      // blend: source
        return b.toByteArray();
    }

    record Chunk(String type, byte[] data) { }

    static List<Chunk> chunks(byte[] png) {
        List<Chunk> out = new ArrayList<>();
        if (png == null || png.length < 8) {
            return out;
        }
        int p = 8;
        while (p + 8 <= png.length) {
            int len = ((png[p] & 0xFF) << 24) | ((png[p + 1] & 0xFF) << 16) | ((png[p + 2] & 0xFF) << 8)
                    | (png[p + 3] & 0xFF);
            if (len < 0 || p + 12 + len > png.length) {
                break;
            }
            String type = new String(png, p + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            byte[] data = new byte[len];
            System.arraycopy(png, p + 8, data, 0, len);
            out.add(new Chunk(type, data));
            p += 12 + len;
        }
        return out;
    }

    private static void writeChunk(DataOutputStream out, String type, byte[] data) throws IOException {
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.writeInt(data.length);
        out.write(t);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.writeInt((int) crc.getValue());
    }

    private static BufferedImage read(byte[] png) {
        if (png == null) {
            return null;
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException e) {
            return null;
        }
    }
}
