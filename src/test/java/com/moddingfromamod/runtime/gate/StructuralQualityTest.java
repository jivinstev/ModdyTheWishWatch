package com.moddingfromamod.runtime.gate;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These metrics are a <b>regression detector</b>, not a ranking instrument, and the tests are
 * written to that purpose: they check that real defects are caught and that correct creatures are
 * not marked down. Measured across 19 real creatures the spread is only 0.08, so nothing here
 * should be used to say one competent model is better than another.
 */
class StructuralQualityTest {

    private static final int GREEN = 0x2E8B22;
    private static final int PURPLE = 0x8833CC;
    private static final int TAN = 0xD2A15C;

    private static CreatureSculpt.Part box(String n, int c, float x, float y, float z, float s) {
        return new CreatureSculpt.Part(n, null, new float[] {0, 0, 0},
                new float[] {x, y, z}, new float[] {x + s, y + s, z + s}, c);
    }

    private static List<CreatureSculpt.Part> decentCreature() {
        List<CreatureSculpt.Part> p = new ArrayList<>();
        p.add(box("body", GREEN, -4, 6, -3, 8));
        p.add(box("head", GREEN, -3, 14, -3, 6));
        p.add(box("legL", TAN, -4, 0, -2, 4));
        p.add(box("legR", TAN, 0, 0, -2, 4));
        p.add(box("armL", TAN, -7, 8, -2, 3));
        p.add(box("armR", TAN, 4, 8, -2, 3));
        return p;
    }

    @Test
    @DisplayName("catches a floating part — the least ambiguous structural defect there is")
    void floatingPartIsCaught() {
        List<CreatureSculpt.Part> p = new ArrayList<>(decentCreature());
        p.add(box("wing", GREEN, 40, 40, 40, 4));   // nowhere near the body
        var r = StructuralQuality.measure(p, "a green creature");
        assertTrue(r.get("connected").score() < 1.0, r.describe());
        assertTrue(r.get("connected").note().contains("wing"), r.describe());
    }

    @Test
    @DisplayName("catches a pile of boxes")
    void aPileOfBoxesScoresLow() {
        var r = StructuralQuality.measure(List.of(
                box("body", GREEN, 0, 0, 0, 8),
                box("head", GREEN, 0, 8, 0, 6)), "a green creature");
        assertTrue(r.get("complexity").score() < 0.3, r.describe());
    }

    @Test
    @DisplayName("REAL FALSE POSITIVE: a purple slug asked for one colour and must not be marked down")
    void oneColourWhenOneColourWasAsked() {
        // An invented purple creature ("Glorp, a squishy purple four-eyed slug" here) scored 0.30
        // on palette and was the WORST creature in a 19-creature run -- punished for being exactly what the child asked for.
        List<CreatureSculpt.Part> p = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            p.add(box("blob" + i, PURPLE, -3 + i, i, 0, 5));
        }
        var r = StructuralQuality.measure(p,
                "invent a new creature called Glorp, a squishy purple four-eyed slug");
        assertTrue(r.get("palette").score() >= 0.9,
                "one named colour means one colour is correct: " + r.describe());
    }

    @Test
    @DisplayName("REAL FALSE POSITIVE: a troll with a club is asymmetric on purpose")
    void askedForAsymmetryIsNotAFault() {
        List<CreatureSculpt.Part> p = new ArrayList<>(decentCreature());
        p.add(box("club", TAN, 8, 10, -2, 6));      // one hand only
        var r = StructuralQuality.measure(p, "make a big troll called Grumbnar with a club");
        assertTrue(r.get("symmetry").score() >= 0.9,
                "the child asked for a club, which only goes in one hand: " + r.describe());
    }

    @Test
    @DisplayName("still penalises monochrome when nobody asked for it")
    void monochromeWithoutIntentIsStillPoor() {
        List<CreatureSculpt.Part> p = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            p.add(box("bit" + i, PURPLE, -3 + i, i, 0, 5));
        }
        var r = StructuralQuality.measure(p, "make me a cool monster");
        assertTrue(r.get("palette").score() < 0.5, r.describe());
    }

    @Test
    @DisplayName("cannot be gamed by emitting hundreds of specks")
    void complexitySaturates() {
        List<CreatureSculpt.Part> few = new ArrayList<>(decentCreature());
        for (int i = 0; i < 14; i++) {
            few.add(box("spike" + i, GREEN, -4 + (i % 8), 6 + i / 8, -3, 2));
        }
        List<CreatureSculpt.Part> many = new ArrayList<>(few);
        for (int i = 0; i < 400; i++) {
            many.add(box("speck" + i, GREEN, -4 + (i % 8), 6 + (i % 9), -3, 1));
        }
        double a = StructuralQuality.measure(few, "x").get("complexity").score();
        double b = StructuralQuality.measure(many, "x").get("complexity").score();
        assertTrue(b <= a + 0.001,
                "400 extra specks must not beat a well-built creature (" + a + " vs " + b + ")");
    }
}
