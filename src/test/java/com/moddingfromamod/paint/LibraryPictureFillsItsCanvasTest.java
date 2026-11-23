package com.moddingfromamod.paint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A library picture fills its canvas</b> (owner grading, 2026-11-10: the diamond and the smiley face were "crowded
 * to the top left of the block"). Both were drawn in 16 of a 32-pixel canvas, so they hung as a quarter-size
 * drawing in one corner of a 3x3 frame. Nothing checked it: the file is valid and every rect is in range.
 *
 * <p>The rule is the drawing's extent, not its area: a star or a rainbow leaves corners empty on purpose, but
 * nothing a child picks from the library should leave more than {@link #MIN_SPAN} of the canvas unused across or down.
 */
class LibraryPictureFillsItsCanvasTest {

    private static final Path PICTURES = Path.of("src/main/resources/builder-library/pictures");

    /** The smallest share of the canvas a picture's drawing spans in each direction. */
    static final double MIN_SPAN = 0.6;

    @Test
    @DisplayName("every library picture's drawing spans most of its canvas, across and down")
    void everyPictureFillsItsCanvas() throws IOException {
        Map<String, String> bad = new TreeMap<>();
        int seen = 0;
        try (Stream<Path> dirs = Files.list(PICTURES)) {
            for (Path d : dirs.sorted().toList()) {
                Path f = d.resolve(LibraryPicture.FILE);
                if (!Files.exists(f)) {
                    continue;
                }
                seen++;
                PaintRecipe r = LibraryPicture.parse(Files.readString(f)).recipe();
                int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
                for (CreatureSculpt.PaintRect p : r.rects()) {
                    x0 = Math.min(x0, p.x());
                    y0 = Math.min(y0, p.y());
                    x1 = Math.max(x1, p.x() + p.width());
                    y1 = Math.max(y1, p.y() + p.height());
                }
                double across = (x1 - x0) / (double) r.size(), down = (y1 - y0) / (double) r.size();
                if (across < MIN_SPAN || down < MIN_SPAN) {
                    bad.put(d.getFileName().toString(), String.format(java.util.Locale.ROOT,
                            "spans %.0f%% across and %.0f%% down a %d canvas", across * 100, down * 100, r.size()));
                }
            }
        }
        assertTrue(seen >= 20, "the library pictures did not load: " + seen);
        assertEquals(Map.of(), bad, "a library picture sits in a corner of its canvas");
    }
}
