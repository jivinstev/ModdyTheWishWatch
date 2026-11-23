package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gate A for the agent's eyes.
 *
 * <p>The whole read-back retry rests on one contract: a <b>half-written</b> screenshot returns null
 * (so the caller waits and tries again) while a <b>complete</b> one returns bytes. Get that wrong in
 * either direction and the agent either goes blind or hands the model a corrupt image.
 *
 * <p>This matters because it already failed in the worst possible way. On 2026-08-04 all five
 * {@code photograph} calls in a live session reported "the photo was taken but couldn't be read
 * back" while the PNGs sat complete on disk — the code read once, 150ms after the file was
 * *created*, and gave up with 7.5 of its 8 seconds unspent. The agent could not see what it had
 * built, so it spent four minutes probing the world with commands instead.
 */
class PhotographToolTest {

    /** A PNG big enough that a truncation is genuinely a broken file, not an empty one. */
    private static byte[] samplePng() throws Exception {
        BufferedImage img = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 600; x++) {
            for (int y = 0; y < 400; y++) {
                img.setRGB(x, y, (x * 7 + y * 13) | 0x202020);   // noise, so it can't compress away
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", bos);
        return bos.toByteArray();
    }

    @Test
    void aCompleteScreenshotIsReadAndScaledDown(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("shot.png");
        Files.write(file, samplePng());

        byte[] out = PhotographTool.shrink(file);
        assertNotNull(out, "a complete PNG must be readable");
        assertTrue(out.length > 0, "and must produce actual bytes");

        BufferedImage back = ImageIO.read(new java.io.ByteArrayInputStream(out));
        assertNotNull(back, "the returned bytes must themselves be a valid PNG");
        assertTrue(Math.max(back.getWidth(), back.getHeight()) <= 512,
                "must be scaled to the 512px long edge, was "
                        + back.getWidth() + "x" + back.getHeight());
    }

    @Test
    void aHalfWrittenScreenshotReturnsNullSoTheCallerWaits(@TempDir Path dir) throws Exception {
        // Exactly the live failure: the file exists and its timestamp has changed, but the client
        // is still writing it. This must read as "not yet", never as a hard error.
        byte[] full = samplePng();
        Path file = dir.resolve("half.png");
        Files.write(file, java.util.Arrays.copyOf(full, full.length / 3));

        assertNull(PhotographTool.shrink(file), "a truncated PNG must return null, not throw");
    }

    @Test
    void aFileThatFinishesLaterBecomesReadable(@TempDir Path dir) throws Exception {
        // The retry actually paying off: same path, unreadable then readable. This is the behaviour
        // the old read-once code could never reach.
        byte[] full = samplePng();
        Path file = dir.resolve("late.png");

        Files.write(file, java.util.Arrays.copyOf(full, full.length / 3));
        assertNull(PhotographTool.shrink(file), "partial write is not readable");

        Files.write(file, full);
        assertNotNull(PhotographTool.shrink(file), "once complete, the same path must read fine");
    }

    @Test
    void garbageAndEmptyFilesDegradeQuietlyInsteadOfThrowing(@TempDir Path dir) throws Exception {
        Path empty = dir.resolve("empty.png");
        Files.write(empty, new byte[0]);
        assertNull(PhotographTool.shrink(empty), "an empty file is just not-ready-yet");

        Path junk = dir.resolve("junk.png");
        Files.writeString(junk, "this is not a png at all");
        assertNull(PhotographTool.shrink(junk), "junk must not throw out of the poll loop");

        assertNull(PhotographTool.shrink(dir.resolve("nope.png")), "a missing file returns null");
    }
}
