package com.moddingfromamod.likeness;

import com.moddingfromamod.runtime.SculptCritique;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <b>A reference palette tool must bucket a colour exactly as this codebase does.</b> The hue boundaries, driven
 * through the real Java; {@code ImagePalettePythonTest} holds the Python copy to them where the tool lives.
 *
 * <p>That tool measures what a character REALLY looks like, by counting the pixels of its official
 * artwork — the one reference question a wiki page cannot answer, because prose says "green shell,
 * tan skin" and never says which of the two you mostly see. Its whole value is that its answer can
 * be SUBTRACTED from a built creature's palette, and that only holds while both use the same
 * buckets.
 *
 * <p>It is a copy of {@link SculptCritique#bucket(int)} in another language, so it can drift, and
 * drift here is the failure this repo keeps recording: an instrument that answers a slightly
 * different question, plausibly, with a number someone then compares against something else. The
 * measured stakes — a text-only lookup put one character at green 40% / tan 35%; the pixels of two
 * official renders put him at green 5% / tan 68%, and the repo's own earlier resemblance analysis
 * independently said 64% skin. A shifted boundary would hide a gap that size.
 *
 * <p>So this drives every hue boundary through the REAL Java and asserts the Python source states
 * the same numbers. Reading the file as text is deliberate and is the only way to check a rule that
 * lives in another runtime, exactly as {@code SourceRuleSweepTest} does.
 */
class ImagePaletteParityTest {

    /** Every boundary in the Java, as {degrees, expected bucket}. */
    private static final Object[][] HUES = {
        {0, "red"}, {17, "red"}, {19, "tan/orange"}, {44, "tan/orange"},
        {46, "yellow"}, {69, "yellow"}, {71, "green"}, {169, "green"},
        {171, "cyan"}, {199, "cyan"}, {201, "blue"}, {259, "blue"},
        {261, "purple/pink"}, {344, "purple/pink"}, {346, "red"},
    };

    @Test
    @DisplayName("the Java buckets a hue where the Python says it does")
    void theJavaSideIsWhatTheTestVectorsClaim() {
        for (Object[] row : HUES) {
            int deg = (Integer) row[0];
            int rgb = java.awt.Color.HSBtoRGB(deg / 360.0f, 1.0f, 1.0f) & 0xFFFFFF;
            assertEquals(row[1], SculptCritique.bucket(rgb), "hue " + deg + " degrees");
        }
        // The two gates that run BEFORE hue, in order. A dark saturated red is black; an
        // unsaturated colour is white or grey whatever its hue. Get the order wrong and every
        // shadowed pixel becomes whatever faint hue it carries.
        assertEquals("black", SculptCritique.bucket(0x320000), "a very dark red is black");
        assertEquals("white", SculptCritique.bucket(0xC8CDD2), "bright and desaturated is white");
        assertEquals("grey", SculptCritique.bucket(0x64676A), "dim and desaturated is grey");
    }

}
