package com.moddingfromamod.runtime;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Surface patterns on a sculpted part — the "grass skirt" case. */
class SculptPatternTest {

    private static final CreatureSculpt.PaintRect NET =
            new CreatureSculpt.PaintRect(10, 20, 32, 24, 0x00AA00);

    private static CreatureSculpt.Part patterned(String pattern, int base, int ink) {
        return new CreatureSculpt.Part("skirt", null, new float[]{0, 0, 0},
                new float[]{0, 0, 0}, new float[]{8, 8, 4}, base,
                CreatureSculpt.NO_ROTATION, 0f, pattern, ink);
    }

    @Test
    void aFlatPartPaintsNothingExtra() {
        var flat = new CreatureSculpt.Part("body", null, new float[]{0, 0, 0},
                new float[]{0, 0, 0}, new float[]{8, 8, 4}, 0x00AA00);
        assertFalse(flat.patterned());
        assertTrue(SculptPattern.overlay(flat, NET).isEmpty());
    }

    @Test
    void everyPatternInTheVocabularyActuallyDrawsSomething() {
        // A pattern that silently paints nothing would read to a child as the feature having
        // failed, and to us as the feature having shipped.
        for (String name : SculptPattern.NAMES) {
            var rects = SculptPattern.overlay(patterned(name, 0x00AA00, 0xFFFFFF), NET);
            assertFalse(rects.isEmpty(), name + " painted nothing");
        }
    }

    @Test
    void patternStaysInsideThePartsOwnAtlasRectangle() {
        // Bleeding into a neighbour's UV would repaint a DIFFERENT part of the creature, which is
        // the kind of bug that looks like a rendering problem for a week.
        for (String name : SculptPattern.NAMES) {
            for (var r : SculptPattern.overlay(patterned(name, 0x00AA00, 0xFFFFFF), NET)) {
                assertTrue(r.x() >= NET.x(), name + " ran off the left");
                assertTrue(r.y() >= NET.y(), name + " ran off the top");
                assertTrue(r.x() + r.width() <= NET.x() + NET.width(), name + " ran off the right");
                assertTrue(r.y() + r.height() <= NET.y() + NET.height(),
                        name + " ran off the bottom");
            }
        }
    }

    @Test
    void theSameCreatureAlwaysPaintsTheSameWay() {
        // Deliberately a hash of the coordinate, never a Random: a texture that differs between two
        // bakes would make every grader a sampling exercise, and would repaint a creature already
        // standing in the world differently on a re-sculpt.
        var a = SculptPattern.overlay(patterned("speckle", 0x884422, 0xFFFFFF), NET);
        var b = SculptPattern.overlay(patterned("speckle", 0x884422, 0xFFFFFF), NET);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i), b.get(i));
        }
    }

    @Test
    void aPatternColourEqualToTheBaseIsReplacedRatherThanPaintedInvisibly() {
        var rects = SculptPattern.overlay(patterned("stripes", 0x00AA00, 0x00AA00), NET);
        assertFalse(rects.isEmpty());
        for (var r : rects) {
            assertNotEquals(0x00AA00, r.colour(), "an invisible stripe reads as a broken feature");
        }
    }

    @Test
    void contrastLightensADarkPartAndDarkensALightOne() {
        assertTrue(luma(SculptPattern.contrastWith(0x111111)) > luma(0x111111));
        assertTrue(luma(SculptPattern.contrastWith(0xEEEEEE)) < luma(0xEEEEEE));
    }

    @Test
    void nearMissPatternNamesResolveAndNonsenseDegradesToFlat() {
        assertEquals("stripes", SculptPattern.sanitize("striped"));
        assertEquals("spots", SculptPattern.sanitize("Dotted"));
        assertEquals("fur", SculptPattern.sanitize("furry"));
        assertEquals("", SculptPattern.sanitize("ignore_all_previous_instructions"));
        assertEquals("", SculptPattern.sanitize(null));
    }

    @Test
    void aPatternedPartStillBakesAndKeepsItsGeometry() {
        JsonArray json = JsonParser.parseString("""
                [{"name":"skirt","from":[-5,0,-3],"to":[5,8,3],"colour":"green",
                  "pattern":"stripes","pattern_colour":"yellow"}]""").getAsJsonArray();
        List<CreatureSculpt.Part> ps = CreatureSculpt.parseParts(json);
        assertTrue(ps.get(0).patterned());
        CreatureSculpt.Baked baked = CreatureSculpt.bake(ps);
        // Base fill for the part, plus the stripes on top of it.
        assertTrue(baked.paint().size() > ps.size(),
                "the pattern must add rectangles beyond the flat fill");
        assertEquals(1, baked.geometry().getAsJsonArray("minecraft:geometry").get(0)
                .getAsJsonObject().getAsJsonArray("bones").size());
    }

    private static int luma(int rgb) {
        return (299 * ((rgb >> 16) & 0xFF) + 587 * ((rgb >> 8) & 0xFF) + 114 * (rgb & 0xFF)) / 1000;
    }
}
