package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class PartPaintTest {

    private static CreatureSculpt.Part part(String name, int colour) {
        return new CreatureSculpt.Part(name, "", new float[3], new float[] {0, 0, 0},
                new float[] {4, 4, 4}, colour);
    }

    private static final List<CreatureSculpt.Part> DRAGON = List.of(
            part("body", 0x00AA00), part("head", 0x00AA00), part("wingL", 0x008800),
            part("wingR", 0x008800), part("tail", 0x00AA00));

    @Test
    void aWordPaintsEveryPartItNamesAndNothingElse() {
        var r = PartPaint.paint(DRAGON, "wing", 0x8000FF);
        assertTrue(r.ok(), r.error());
        assertEquals(List.of("wingL", "wingR"), r.painted());
        assertEquals(0x8000FF, r.parts().get(2).colour());
        assertEquals(0x8000FF, r.parts().get(3).colour());
        assertEquals(0x00AA00, r.parts().get(0).colour(), "the body was not asked for");
        assertEquals(0x00AA00, r.parts().get(4).colour());
    }

    @Test
    void anExactNameWinsOverALooseOne() {
        var parts = List.of(part("arm", 1), part("armor", 2));
        assertEquals(List.of(0), PartPaint.match(parts, "arm"));
    }

    @Test
    void aBandIsRefusedByNameWithThePartsItCouldPaint() {
        var r = PartPaint.paint(DRAGON, "belt", 0xFF0000);
        assertFalse(r.ok());
        assertTrue(r.error().contains("whole parts") && r.error().contains("wingL"), r.error());
        assertTrue(PartPaint.paint(DRAGON, "belt:body", 0xFF0000).error().contains("belt"));
    }

    @Test
    void aBeltPartIsPaintedLikeAnyOther() {
        var parts = List.of(part("body", 1), part("belt_front", 2));
        var r = PartPaint.paint(parts, "belt", 0x00FF00);
        assertTrue(r.ok());
        assertEquals(List.of("belt_front"), r.painted());
    }

    @Test
    void anUnknownPartNamesTheOnesThereAre() {
        var r = PartPaint.paint(DRAGON, "horn", 0xFF0000);
        assertFalse(r.ok());
        assertTrue(r.error().contains("head") && r.error().contains("tail"), r.error());
    }

    @Test
    void theShapeAndThePatternAreKept() {
        var striped = new CreatureSculpt.Part("body", "", new float[] {1, 2, 3},
                new float[] {0, 0, 0}, new float[] {8, 8, 8}, 0x111111,
                new float[] {0, 10, 0}, 0.5f, "stripes", 0x222222);
        var r = PartPaint.paint(List.of(striped), "body", 0x333333);
        var p = r.parts().get(0);
        assertEquals(0x333333, p.colour());
        assertEquals("stripes", p.pattern());
        assertEquals(0x222222, p.patternColour());
        assertEquals(0.5f, p.inflate());
        assertEquals(10f, p.rotation()[1]);
    }
}
