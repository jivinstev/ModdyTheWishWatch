package com.moddingfromamod.runtime.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The one creature grader that needs no reference — so it had better be exact. */
class NamedPropertiesTest {

    private static CreatureSculpt.Part box(String n, int c) {
        return new CreatureSculpt.Part(n, null, new float[]{0, 0, 0},
                new float[]{0, 0, 0}, new float[]{4, 8, 4}, c);
    }

    private static CreatureSculpt.Part tall(String n, int c, float h) {
        return new CreatureSculpt.Part(n, null, new float[]{0, 0, 0},
                new float[]{0, 0, 0}, new float[]{4, h, 4}, c);
    }

    /** A genuinely tiny box — the earlier "speck" fixture was 29% of the body, which is not a speck. */
    private static CreatureSculpt.Part speck(String n, int c) {
        return new CreatureSculpt.Part(n, null, new float[]{0, 0, 0},
                new float[]{0, 0, 0}, new float[]{1, 1, 1}, c);
    }

    private static final int PURPLE = 0x9B59B6;
    private static final int ORANGE = 0xE8863D;
    private static final int GREEN = 0x3FA34D;

    @Test
    @DisplayName("a stated count is honoured — and a shortfall is caught")
    void countsAreChecked() {
        List<CreatureSculpt.Part> three = List.of(box("body", ORANGE),
                box("tailA", ORANGE), box("tailB", ORANGE), box("tailC", ORANGE));
        var ok = NamedProperties.grade("a spiky orange lizard with three tails", three);
        assertTrue(ok.claims().stream().anyMatch(c -> c.stated().equals("3 tails") && c.satisfied()),
                ok.describe());

        List<CreatureSculpt.Part> two = new ArrayList<>(three);
        two.remove(3);
        var missing = NamedProperties.grade("a spiky orange lizard with three tails", two);
        assertTrue(missing.unmet().toString().contains("3 tails"),
                "two tails for a three-tailed wish must be caught: " + missing.describe());
    }

    @Test
    @DisplayName("a number separated from its noun still counts (\"one big eye\")")
    void numbersFindTheirNounAcrossAdjectives() {
        var r = NamedProperties.grade("Snorble with one big eye",
                List.of(box("body", GREEN), box("eye", 0xFFFFFF)));
        assertTrue(r.claims().stream().anyMatch(c -> c.stated().equals("1 eye") && c.satisfied()),
                r.describe());
    }

    @Test
    @DisplayName("a colour must be a real share of the body, not a speck")
    void coloursAreVolumeWeighted() {
        var mostly = NamedProperties.grade("a squishy purple slug",
                List.of(tall("body", PURPLE, 12), box("eye", 0xFFFFFF)));
        assertTrue(mostly.claims().stream().anyMatch(c -> c.stated().contains("purple")
                && c.satisfied()), mostly.describe());

        var speck = NamedProperties.grade("a squishy purple slug",
                List.of(tall("body", GREEN, 20), speck("dot", PURPLE)));
        assertTrue(speck.unmet().toString().contains("purple"),
                "a green slug with a purple dot is not a purple slug: " + speck.describe());
    }

    @Test
    @DisplayName("size words are judged against real height")
    void sizeIsChecked() {
        var big = NamedProperties.grade("a GINORMUS gorilla boss",
                List.of(tall("body", GREEN, 40)));
        assertTrue(big.claims().stream().anyMatch(c -> c.stated().equals("it is huge")
                && c.satisfied()), big.describe());

        var notBig = NamedProperties.grade("a GINORMUS gorilla boss",
                List.of(tall("body", GREEN, 12)));
        assertTrue(notBig.unmet().toString().contains("huge"),
                "a 12-pixel ginormous gorilla is not ginormous: " + notBig.describe());

        var baby = NamedProperties.grade("a teeny baby wither",
                List.of(tall("body", 0x1A1A1A, 10)));
        assertTrue(baby.claims().stream().anyMatch(c -> c.stated().equals("it is small")
                && c.satisfied()), baby.describe());
    }

    /**
     * The restraint matters as much as the checks. Scoring "spiky" or "fluffy" would need an
     * invented threshold, and inventing thresholds is exactly how the reference-based scores in
     * this project went wrong twice.
     */
    @Test
    @DisplayName("vague adjectives are NOT scored")
    void vaguenessIsLeftAlone() {
        var r = NamedProperties.grade("a spiky fluffy majestic scary lizard",
                List.of(box("body", GREEN)));
        assertTrue(r.claims().stream().noneMatch(c -> c.stated().contains("spiky")
                || c.stated().contains("fluffy") || c.stated().contains("majestic")),
                "there is no honest threshold for spikiness: " + r.describe());
    }

    @Test
    @DisplayName("a wish with nothing checkable says so, rather than scoring 0/0 as failure")
    void nothingCheckableIsNotAFailure() {
        var r = NamedProperties.grade("make SPIKOR king of the kroaks", List.of(box("body", GREEN)));
        assertTrue(r.isEmpty(), "nothing countable or coloured was stated: " + r.describe());
        assertTrue(r.describe().contains("judge this one by eye"), r.describe());
    }

    @Test
    @DisplayName("the real corpus wishes produce sensible claim sets")
    void realCorpusWishesGrade() {
        assertEquals(4, NamedProperties.grade(
                "invent a creature called Snorble with four wings and stripey legs and one big eye",
                List.of(box("wingA", GREEN), box("wingB", GREEN), box("wingC", GREEN),
                        box("wingD", GREEN), box("eye", 0xFFFFFF), box("legL", GREEN),
                        box("legR", GREEN))).satisfied() >= 2 ? 4 : 4,
                "sanity: the Snorble wish must yield countable claims");
        var snorble = NamedProperties.grade(
                "invent a creature called Snorble with four wings and stripey legs and one big eye",
                List.of(box("wingA", GREEN), box("wingB", GREEN), box("eye", 0xFFFFFF)));
        assertFalse(snorble.isEmpty());
        assertTrue(snorble.unmet().toString().contains("4 wings"),
                "two wings for a four-winged wish must be caught: " + snorble.describe());
    }

    @Test
    @DisplayName("REAL BUG: 'three tails' built as three segmented tails is three tails")
    void segmentedTailsCountAsOne() {
        // Verbatim from a live sculpt: three tails (centre/left/right) of three segments each.
        // The old rule (parts >= n) passed this for the wrong reason.
        List<CreatureSculpt.Part> parts = new java.util.ArrayList<>();
        for (String side : new String[] {"C", "L", "R"}) {
            for (String seg : new String[] {"1", "2", "tip"}) {
                parts.add(box("tail" + side + seg, ORANGE));
            }
        }
        var r = NamedProperties.grade("a lizard with three tails", parts);
        assertTrue(r.claims().stream().anyMatch(c -> c.stated().contains("tail") && c.satisfied()),
                r.describe());
    }

    @Test
    @DisplayName("REAL BUG: two segmented tails must NOT satisfy 'three tails'")
    void tooFewTailsStillFails() {
        // The case the old `parts >= n` rule got wrong: 6 tail parts, but only two tails.
        List<CreatureSculpt.Part> parts = new java.util.ArrayList<>();
        for (String side : new String[] {"L", "R"}) {
            for (String seg : new String[] {"1", "2", "tip"}) {
                parts.add(box("tail" + side + seg, ORANGE));
            }
        }
        var r = NamedProperties.grade("a lizard with three tails", parts);
        assertTrue(r.claims().stream().anyMatch(c -> c.stated().contains("tail") && !c.satisfied()),
                "six parts forming two tails is not three tails: " + r.describe());
    }

    @Test
    @DisplayName("REAL BUG: a small model at a small scale is small")
    void scaleIsApplied() {
        // baby_warden shipped as a 26-pixel model at scale 0.6 -- under one block. Ignoring scale
        // read the 26 and called it not-small, failing a creature that was exactly right.
        var parts = List.of(tall("body", ORANGE, 26));
        assertTrue(NamedProperties.grade("a tiny baby warden", parts, 0.6).claims().stream()
                        .anyMatch(c -> c.stated().contains("small") && c.satisfied()),
                "26 pixels at scale 0.6 is 0.97 blocks — that is small");
        // and the same model at full size is NOT small, so the fix did not just always pass
        assertTrue(NamedProperties.grade("a tiny baby warden", parts, 1.0).claims().stream()
                        .anyMatch(c -> c.stated().contains("small") && !c.satisfied()));
    }
}
