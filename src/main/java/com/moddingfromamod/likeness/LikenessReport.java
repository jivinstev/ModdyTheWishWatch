package com.moddingfromamod.likeness;

import java.util.List;

import com.moddingfromamod.reference.ReferenceSpec;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptKits;

/**
 * <b>What survived calibration against the human.</b>
 *
 * <h2>The surviving set, and what it cost to find</h2>
 *
 * <p>Five candidate graders were built and scored against <b>nine recorded human votes</b>
 * ({@code calibration/human-votes-002-fidelity.json}, kept private because each vote is keyed with a child's own words). Ties and UNKNOWNs count as <i>no answer</i>,
 * never as a miss, so the denominators differ:
 *
 * <table border="1">
 *   <caption>Agreement with the human</caption>
 *   <tr><td>G2 palette (earth-mover)</td><td><b>6/7</b></td><td>kept — the only one that works</td></tr>
 *   <tr><td>G3 feature recall</td><td>3/7</td><td>kept, but NOT as a ranker — see below</td></tr>
 *   <tr><td>G1 silhouette IoU</td><td><b>1/7</b></td><td><b>deleted</b></td></tr>
 *   <tr><td>G6 coherence</td><td>1/6</td><td><b>deleted</b></td></tr>
 * </table>
 *
 * <p><b>Why two were deleted rather than tuned.</b> Both were <i>anti</i>-correlated with the human,
 * not merely noisy, and the reason is structural rather than a matter of thresholds: every metric
 * that failed rewards <b>conformity</b> — similarity to a reference box model, one dominant body
 * mass, regular rotation axes, absence of prefab parts — while the human rewards <b>elaboration
 * done well</b>. A measure of that shape can only ever punish the thing he keeps choosing. G1 in
 * particular compared against a reference built from <i>axis-aligned</i> boxes, so any tilt could
 * only lose points, and it never once agreed with him across seven comparisons.
 *
 * <p>They are deleted rather than left in place because <b>a grader that exists will be used</b>,
 * and an anti-correlated one is worse than none — this project's own rule. Recoverable at
 * {@code bfa1331} if a fixed reference or a photograph-based silhouette makes the idea
 * work; the nine votes remain, so any future candidate can be scored offline and free without
 * spending the human's time again.
 *
 * <h2>Why G3 stays despite scoring at chance</h2>
 *
 * <p>3/7 means it does not predict which creature he prefers — but it was never a preference
 * grader. It answers a <i>completeness</i> question ("the reference says Spikor has spikes; does
 * this one?") whose answer is a checkable fact rather than a taste. It is reported and must
 * <b>not</b> be ranked on.
 *
 * <h2>And no single number, ever</h2>
 *
 * <p>The vision judge's three buckets averaged to something that read as "56%" and hid the only
 * useful fact in the measurement. These stay separate for the same reason, and because each is
 * individually gameable in a different direction.
 *
 * @param palette   G2, colour — the surviving instrument
 * @param features  G3, presence — reported, not ranked
 * @param parts     how many boxes the creature is
 * @param kitParts  how many came from a part-kit — the adoption number. Reported as a
 *                  <b>guardrail</b>, never as a score: kit share is not monotonic (0.54 was
 *                  rejected, 0.38 preferred, and 0.00 was <i>not</i> preferred over 0.38), which is
 *                  why the real control is the {@code MAX_KIT_SHARE} cap in code and not a metric.
 */
public record LikenessReport(PaletteEmd.Score palette,
                             FeatureRecall.Score features,
                             int parts,
                             int kitParts) {

    /** Score one creature against its sourced reference. Any grader may report UNKNOWN. */
    public static LikenessReport of(List<CreatureSculpt.Part> parts, ReferenceSpec reference) {
        List<CreatureSculpt.Part> safe = parts == null ? List.of() : parts;
        return new LikenessReport(
                PaletteEmd.of(safe, reference),
                FeatureRecall.of(safe, reference),
                safe.size(),
                SculptKits.usedBy(safe));
    }

    /** Did either grader have a reference to work from? */
    public boolean scored() {
        return palette.scored() || features.scored();
    }

    /** One tab-separated row, for a table a human reads column by column. */
    public String row(String id) {
        return String.join("\t", id,
                String.valueOf(parts),
                String.valueOf(kitParts),
                palette.scored() ? String.format("%.3f", palette.distance()) : "-",
                features.scored() ? String.format("%.3f", features.recall()) : "-",
                palette.band(), features.band());
    }

    /** The header matching {@link #row}. */
    public static String header() {
        return String.join("\t", "creature", "parts", "kit_parts",
                "G2_palette", "G3_recall", "G2_band", "G3_band");
    }
}
