package com.moddingfromamod.likeness;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.reference.ReferenceSpec;
import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The surviving likeness graders.
 *
 * <p>G1 (silhouette IoU) and G6 (coherence) were deleted after scoring 1/7 and 1/6 against nine
 * human votes — anti-correlated rather than noisy, because both rewarded conformity while the human
 * rewards elaboration. Recoverable at {@code bfa1331}; see {@link LikenessReport}.
 *
 * <p>These tests are deliberately about <b>direction and safety</b>, not about hitting particular
 * numbers. The project has already been burnt once by thresholds calibrated against hand-made
 * fixtures — they caught 0 of 14 real creatures on the first live run, because the fixtures were
 * built to the thresholds. So nothing here asserts "Spikor scores 0.62"; it asserts that a closer
 * shape scores higher than a further one, which is the only property an optimisation loop needs and
 * the only one a fixture can honestly establish.
 */
class LikenessGraderTest {

    private static CreatureSculpt.Part box(String name, float x0, float y0, float z0,
                                           float x1, float y1, float z1, int colour) {
        return new CreatureSculpt.Part(name, null, new float[]{(x0 + x1) / 2, y0, (z0 + z1) / 2},
                new float[]{x0, y0, z0}, new float[]{x1, y1, z1}, colour);
    }

    // ===================== G2 =====================

    @Test
    void theRightPaletteScoresNearZeroDistance() {
        var sculpt = List.of(box("body", -8, 0, -4, 8, 24, 4, 0x3CA53C));
        var spec = new ReferenceSpec(List.of(new ReferenceSpec.ColourShare("green", 100)),
                List.of("body"), ReferenceSpec.Size.MEDIUM, ReferenceSpec.Confidence.HIGH,
                List.of());
        assertTrue(PaletteEmd.of(sculpt, spec).distance() < 0.05);
    }

    /**
     * The single reason G2 exists beside the existing subtraction: a near miss and an opposite are
     * not the same mistake, and per-colour absolute difference cannot tell them apart.
     */
    @Test
    void aNearMissCostsLessThanAnOpposite() {
        var spec = new ReferenceSpec(List.of(new ReferenceSpec.ColourShare("green", 100)),
                List.of("body"), ReferenceSpec.Size.MEDIUM, ReferenceSpec.Confidence.HIGH,
                List.of());
        double near = PaletteEmd.of(
                List.of(box("body", -8, 0, -4, 8, 24, 4, 0x35B5C4)), spec).distance();   // cyan
        double far = PaletteEmd.of(
                List.of(box("body", -8, 0, -4, 8, 24, 4, 0xA845B8)), spec).distance();   // magenta
        assertTrue(near < far,
                "green->cyan (" + near + ") should cost less than green->magenta (" + far + ")");
    }

    @Test
    void theEarthMoverIsExactOnAKnownTransport() {
        // Two units of mass, each with exactly one place to go: the answer is the sum of the two
        // edge costs and nothing else. A greedy solver gets this right; the test is here so a later
        // "optimisation" of emd() cannot quietly stop being exact.
        double[][] cost = {{0, 5, 9}, {5, 0, 1}, {9, 1, 0}};
        double moved = PaletteEmd.emd(new double[]{0.5, 0.5, 0}, new double[]{0, 0, 1.0}, cost);
        assertEquals(0.5 * 9 + 0.5 * 1, moved, 1e-9);
    }

    @Test
    void paletteDistanceStaysInRange() {
        var spec = new ReferenceSpec(List.of(new ReferenceSpec.ColourShare("white", 100)),
                List.of("body"), ReferenceSpec.Size.MEDIUM, ReferenceSpec.Confidence.HIGH,
                List.of());
        double d = PaletteEmd.of(List.of(box("body", 0, 0, 0, 4, 4, 4, 0x1E1E1E)), spec).distance();
        assertTrue(d >= 0 && d <= 1, "out of range: " + d);
    }

    // ===================== G3 =====================

    @Test
    void featureRecallNoticesTheMissingSpikes() {
        var spec = new ReferenceSpec(List.of(new ReferenceSpec.ColourShare("green", 100)),
                List.of("body", "head", "shell", "spike", "horn"),
                ReferenceSpec.Size.LARGE, ReferenceSpec.Confidence.HIGH, List.of());
        var noSpikes = List.of(
                box("body", -8, 0, -4, 8, 20, 4, 0x00AA00),
                box("head", -5, 20, -4, 5, 30, 4, 0x00AA00),
                box("shell", -9, 4, -8, 9, 18, -4, 0x00AA00));
        var withSpikes = new ArrayList<>(noSpikes);
        withSpikes.add(box("spike_row0", -1, 18, -8, 1, 24, -6, 0xFFFFFF));
        withSpikes.add(box("horn0", -4, 30, -2, -2, 34, 0, 0xFFFFFF));

        var before = FeatureRecall.of(noSpikes, spec);
        var after = FeatureRecall.of(withSpikes, spec);
        assertTrue(before.missing().contains("spike"));
        assertTrue(after.missing().isEmpty(), "still missing: " + after.missing());
        assertTrue(after.recall() > before.recall());
    }

    @Test
    void structuralWordsDoNotInflateEveryScore() {
        // Every creature has a body and a head; counting them would push every score toward a
        // common floor and flatten exactly the differences this is supposed to expose.
        var spec = new ReferenceSpec(List.of(new ReferenceSpec.ColourShare("green", 100)),
                List.of("body", "head", "arm", "leg", "spike"),
                ReferenceSpec.Size.LARGE, ReferenceSpec.Confidence.HIGH, List.of());
        var plain = List.of(
                box("body", -8, 0, -4, 8, 20, 4, 0x00AA00),
                box("head", -5, 20, -4, 5, 30, 4, 0x00AA00),
                box("armL", -10, 8, -2, -8, 18, 2, 0x00AA00),
                box("legL", -6, 0, -2, -2, 8, 2, 0x00AA00));
        // Only "spike" is informative, and it is absent — so recall is 0, not 4/5.
        assertEquals(0.0, FeatureRecall.of(plain, spec).recall(), 1e-9);
    }

    @Test
    void bonesFromAKitAndBonesFromAHandSculptBothMatch() {
        // A grader that only recognised one naming style would make the kit-adoption number lie in
        // the flattering direction.
        assertTrue(FeatureRecall.matches("spike_row0", "spike"));
        assertTrue(FeatureRecall.matches("backSpike2", "spike"));
        assertTrue(FeatureRecall.matches("mane", "hair"));
        assertFalse(FeatureRecall.matches("body", "spike"));
    }

    // ===================== the bundle =====================

    @Test
    void theReportKeepsTheGradersSeparate() {
        var spec = new ReferenceSpec(List.of(new ReferenceSpec.ColourShare("green", 100)),
                List.of("body", "spike"), ReferenceSpec.Size.MEDIUM,
                ReferenceSpec.Confidence.HIGH, List.of());
        var report = LikenessReport.of(
                List.of(box("body", -8, 0, -4, 8, 24, 4, 0x3CA53C)), spec);
        assertTrue(report.scored());
        // Three separate columns, and a row that carries all of them rather than a single score.
        String row = report.row("test");
        assertEquals(LikenessReport.header().split("\t").length, row.split("\t").length);
    }

    @Test
    void everyGraderSurvivesAnEmptyCreature() {
        var report = LikenessReport.of(List.of(), null);
        assertFalse(report.scored());
        assertEquals(0, report.parts());
    }
}
