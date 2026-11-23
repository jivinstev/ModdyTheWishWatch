package com.moddingfromamod.reference;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Gate 2 and Gate 0′ of {@code docs/REFERENCE_PALETTE_PLAN.md}.</b>
 *
 * <p>Two things have to hold. The Java histogram must agree with {@code tools/image-palette.py} on
 * the same pixels, or the two instruments quietly answer different questions. And the ESTIMATOR
 * must be the median of several renders, because Gate 0 failed 0/4 on single images and passed 4/4
 * on the aggregate.
 */
class ReferencePaletteTest {

    private static BufferedImage fixture() throws Exception {
        try (InputStream in = ReferencePaletteTest.class
                .getResourceAsStream("/palette/fixture.png")) {
            assertNotNull(in, "the parity fixture is missing from test resources");
            return ImageIO.read(in);
        }
    }

    @Test
    @DisplayName("GATE 2: the Java histogram matches the Python within 0.5 points, bucket by bucket")
    void theTwoRuntimesAgree() throws Exception {
        JsonObject want;
        try (InputStream in = ReferencePaletteTest.class
                .getResourceAsStream("/palette/fixture-expected.json")) {
            assertNotNull(in, "the expected-values file is missing");
            want = JsonParser.parseReader(new java.io.InputStreamReader(in)).getAsJsonObject();
        }
        Map<String, Double> got = ReferencePalette.of(fixture());

        assertEquals(want.get("opaque_fraction").getAsDouble(),
                got.get(ReferencePalette.OPAQUE_FRACTION), 0.005,
                "the transparent rows are being counted, or not counted, differently");

        JsonObject wp = want.getAsJsonObject("palette");
        for (String bucket : wp.keySet()) {
            assertEquals(wp.get(bucket).getAsDouble(), got.getOrDefault(bucket, 0.0), 0.5,
                    "bucket '" + bucket + "' disagrees with tools/image-palette.py. The number's "
                            + "only value is that it is subtractable from a built creature's "
                            + "palette; in different buckets it answers a different question.");
        }
        // ...and nothing EXTRA. A bucket Java invents is the same failure in reverse.
        for (String bucket : got.keySet()) {
            if (!bucket.equals(ReferencePalette.OPAQUE_FRACTION)) {
                assertTrue(wp.has(bucket), "Java reports a bucket the Python does not: " + bucket);
            }
        }
    }

    private static Map<String, Double> img(double opaque, Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put(ReferencePalette.OPAQUE_FRACTION, opaque);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        }
        return m;
    }

    @Test
    @DisplayName("GATE 0: one render is not a reference — fewer than MIN_IMAGES answers nothing")
    void oneImageIsNotAReference() {
        // Measured: per-image agreement on the top colour was 50-67% across 24 real renders.
        // Below three there is no leave-one-out to run and a single kart owns the answer.
        var one = List.of(img(0.5, "green", 60.0));
        assertTrue(ReferencePalette.across(one).isEmpty(), "answered from a single image");
        assertTrue(ReferencePalette.across(List.of(img(0.5, "green", 60.0),
                img(0.5, "green", 55.0))).isEmpty(), "answered from two images");
        assertEquals(3, ReferencePalette.MIN_IMAGES);
    }

    @Test
    @DisplayName("REAL CASE: the character in a kart is an outlier the MEDIAN ignores")
    void theMedianSurvivesOneBadRender() {
        // Dinko's real numbers. Five renders; the Plumo Kart 8 artwork reads blue 36 / cyan 26
        // because it is mostly kart. A MEAN would put blue near the top; the median must not.
        var dinko = List.of(
                img(0.38, "blue", 36.0, "cyan", 26.0, "green", 16.0),   // in a kart
                img(0.46, "black", 39.0, "green", 19.0, "white", 16.0), // in a football kit
                img(0.54, "green", 37.0, "purple/pink", 20.0, "red", 13.0),
                img(0.52, "green", 34.0, "tan/orange", 20.0, "red", 13.0),
                img(0.56, "green", 60.0, "white", 14.0, "tan/orange", 14.0));
        var r = ReferencePalette.across(dinko);

        assertFalse(r.isEmpty());
        assertEquals("green", r.colours().get(0).colour(),
                "the kart and the football kit outvoted the actual dinosaur");
        assertTrue(r.topStable(), "the leading colour did not survive leave-one-out");
        assertEquals(5, r.images());
    }

    @Test
    @DisplayName("an absent colour counts as a real ZERO, not as a missing sample")
    void absentMeansZero() {
        // Taking the median only of the images that HAVE a colour makes one appearance in six look
        // as strong as six -- which is exactly how a kart's blue would win.
        var imgs = List.of(
                img(0.5, "green", 50.0),
                img(0.5, "green", 50.0),
                img(0.5, "green", 50.0),
                img(0.5, "blue", 90.0));
        var r = ReferencePalette.across(imgs);
        assertEquals("green", r.colours().get(0).colour(),
                "one blue image out of four beat three green ones");
    }

    @Test
    @DisplayName("an image with NO transparency is refused — its background is being counted")
    void noAlphaMeansNoSubjectMask() {
        // This one rule caught every junk image in the Gate 0 corpus (a manual scan, a video
        // thumbnail, an opaque-background render) with no filename heuristics at all.
        assertFalse(ReferencePalette.usable(img(1.0, "white", 70.0)));
        assertTrue(ReferencePalette.usable(img(0.52, "green", 40.0)));
        // Three good renders plus one opaque one still answers -- from the three.
        var r = ReferencePalette.across(List.of(
                img(0.5, "green", 40.0), img(0.5, "green", 44.0), img(0.5, "green", 38.0),
                img(1.0, "white", 80.0)));
        assertEquals(3, r.images(), "the maskless image was counted");
        assertEquals("green", r.colours().get(0).colour());
    }

    @Test
    @DisplayName("the reading reports how much it depends on any ONE picture, and caps confidence")
    void itSaysHowMuchToTrustIt() {
        // Tight agreement -> may be trusted.
        var tight = ReferencePalette.across(List.of(
                img(0.5, "green", 40.0), img(0.5, "green", 42.0),
                img(0.5, "green", 41.0), img(0.5, "green", 39.0)));
        assertTrue(tight.topStable());
        assertEquals(ReferenceSpec.Confidence.HIGH, tight.confidenceCeiling());

        // Renders that cannot agree which colour leads must come back LOW, never confidently
        // wrong -- a wrongly-shaped confident answer is an anchor, which is how Omega Lad built
        // worse than no reference at all.
        var split = ReferencePalette.across(List.of(
                img(0.5, "green", 60.0), img(0.5, "green", 55.0),
                img(0.5, "blue", 70.0), img(0.5, "blue", 65.0),
                img(0.5, "red", 80.0)));
        assertTrue(split.confidenceCeiling().compareTo(ReferenceSpec.Confidence.HIGH) < 0,
                "renders that disagree still reported HIGH confidence");
        assertEquals(ReferenceSpec.Confidence.LOW, ReferencePalette.empty().confidenceCeiling());
    }

    @Test
    @DisplayName("REAL BUG: an unstable reading is not allowed to overwrite the model's palette")
    void anUnstableReadingIsNotTrustworthy() {
        // Measured over six held-out characters: every STABLE reading was right (3/3) and every
        // UNSTABLE one was wrong (0/2). Barrel Ape's page's biggest images are a movie poster
        // and a group shot, and the count came back "red 9%" -- against a model that would have
        // said brown. Replacing recall with a count is only warranted while the count is the more
        // reliable of the two.
        var unstable = ReferencePalette.across(List.of(
                img(0.47, "tan/orange", 48.0, "red", 9.0, "black", 29.0),
                img(0.50, "tan/orange", 40.0, "red", 12.0, "black", 20.0),
                img(0.69, "red", 74.0, "tan/orange", 6.0, "black", 5.0),   // a movie poster
                img(0.43, "red", 60.0, "tan/orange", 5.0, "black", 4.0))); // another poster
        assertFalse(unstable.isEmpty(), "it still has an answer");
        assertFalse(unstable.trustworthy(), "an answer whose renders disagree was called reliable");
        assertFalse(unstable.topStable());

        var stable = ReferencePalette.across(List.of(
                img(0.5, "green", 40.0), img(0.5, "green", 44.0), img(0.5, "green", 38.0)));
        assertTrue(stable.trustworthy());
        assertFalse(ReferencePalette.empty().trustworthy(), "nothing at all is not trustworthy");
    }

    @Test
    @DisplayName("what comes out is already inside ReferenceSpec's vocabulary and caps")
    void theOutputCrossesTheBoundaryCleanly() {
        List<Map<String, Double>> imgs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            imgs.add(img(0.5, "green", 30.0, "red", 20.0, "blue", 18.0, "white", 12.0,
                    "black", 9.0, "grey", 6.0, "yellow", 3.0, "cyan", 1.0));
        }
        var r = ReferencePalette.across(imgs);
        assertTrue(r.colours().size() <= ReferenceSpec.MAX_COLOURS);
        for (var c : r.colours()) {
            assertTrue(ReferenceSpec.COLOURS.contains(c.colour()),
                    c.colour() + " is outside the vocabulary and would be dropped at the boundary");
            assertTrue(c.percent() >= 0 && c.percent() <= 100);
        }
        // Sorted biggest-first, because the agent reads it as a palette.
        for (int i = 1; i < r.colours().size(); i++) {
            assertTrue(r.colours().get(i - 1).percent() >= r.colours().get(i).percent());
        }
        // A 1% trace is texture, not palette.
        assertFalse(r.colours().stream().anyMatch(c -> "cyan".equals(c.colour())));
    }

    // ---- the character with only one picture in the world -------------------------------------

    /** One render: the named colour leading, the rest spread so nothing else can lead. */
    private static java.util.Map<String, Double> img(String colour, double pct) {
        return java.util.Map.of(colour, pct, "grey", (100 - pct) / 2, "white", (100 - pct) / 2,
                ReferencePalette.OPAQUE_FRACTION, 0.6);
    }

    @Test
    @DisplayName("one render is measured when the wiki HAS only one, and capped at LOW")
    void aSoleRenderIsMeasuredAndSaysSo() {
        // MIN_IMAGES exists because one render is one POSE -- Dinko in a kart reads as blue. That
        // is an argument about sampling several drawings badly, and it does not apply when there
        // is only one drawing. Measured: Omega Lad's wiki enumerates exactly one image against 29
        // for Spikor, so refusing at n<3 there is not caution, it is declining to look at the only
        // evidence that exists -- and the model's remembered palette for him missed the character's
        // signature colour outright (red 19% counted, absent from the guess).
        var one = List.of(img("red", 60));
        assertTrue(ReferencePalette.across(one, List.of()).isEmpty(),
                "without the flag, one render is still refused");

        var r = ReferencePalette.across(one, List.of(), true);
        assertFalse(r.isEmpty(), "the only artwork in existence was not measured");
        assertTrue(r.sole(), "a sole reading must announce itself");
        assertTrue(r.trustworthy(), "a sole reading is usable; it is its STABILITY that is unknown");
        assertEquals(ReferenceSpec.Confidence.LOW, r.confidenceCeiling(),
                "one drawing cannot say whether a second would have agreed");
        assertEquals("red", r.colours().get(0).colour());
        assertEquals(0, r.topSwingPct(),
                "leave-one-out is undefined at n=1 and must not be reported as a small swing");
    }

    @Test
    @DisplayName("the sole path is for a character with one drawing, NOT for a broken download")
    void aSoleReadingIsNotAFallbackForFailedFetches() {
        // The distinction that keeps this honest. Six URLs of which five failed to download is a
        // broken fetch, and measuring the survivor would be exactly the confident-wrong-number
        // this class refuses everywhere else. The caller passes the flag only when the wiki's own
        // image API offered no more, which is a fact it can know.
        var three = List.of(img("red", 60), img("red", 55), img("red", 65));
        assertFalse(ReferencePalette.across(three, List.of(), true).sole(),
                "three usable renders is the ordinary path and must not be marked sole");
    }

    @Test
    @DisplayName("bands come out of a sole render too")
    void bandsAreMeasurableFromOneRender() {
        // A render has an upper, a middle and a lower whatever N is; the floor here was the same
        // median-of-N argument and takes the same exception. Without this, a sole-artwork
        // character got colours and NO regions, which is the half-answer that reads as a bug.
        java.util.Map<String, Double> whole = java.util.Map.of("red", 20.0, "green", 20.0);
        var bands = List.of(java.util.Map.of(
                ReferenceSpec.Region.UPPER, java.util.Map.of("red", 40.0),
                ReferenceSpec.Region.LOWER, java.util.Map.of("green", 40.0)));
        assertTrue(ReferencePalette.regionsAcross(bands, whole).isEmpty(),
                "at the default floor one image gives no bands");
        var got = ReferencePalette.regionsAcross(bands, whole, 1);
        assertEquals(2, got.size(), "both bands should be reported from the one render");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("#1219 r2: leader survival is the share of drop-one subsets that keep the leader")
    void leaderSurvival() {
        java.util.Map<String, Double> white = java.util.Map.of(OPAQUE_KEY, 0.5, "white", 60.0,
                "grey", 30.0);
        java.util.Map<String, Double> grey = java.util.Map.of(OPAQUE_KEY, 0.5, "grey", 60.0,
                "white", 30.0);
        // Unanimous renders: every subset keeps the leader.
        org.junit.jupiter.api.Assertions.assertEquals(1.0, ReferencePalette.leaderSurvival(
                java.util.List.of(white, white, white)), 1e-9);
        // 3 white, 2 grey: dropping a white leaves the two colours tied at the median, so the
        // answer is a share in [0, 1] -- a bound, because a tie is broken by bucket order.
        double s = ReferencePalette.leaderSurvival(
                java.util.List.of(white, white, white, grey, grey));
        org.junit.jupiter.api.Assertions.assertTrue(s >= 0 && s <= 1, String.valueOf(s));
        // Too few usable renders to ask the question at all.
        org.junit.jupiter.api.Assertions.assertEquals(0.0, ReferencePalette.leaderSurvival(
                java.util.List.of(white, grey)), 1e-9);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("#1219 r2: a majority-trusted reading is trusted but never HIGH")
    void majorityTrustedIsNeverHigh() {
        var r = new ReferencePalette.Reading(java.util.List.of(
                new ReferenceSpec.ColourShare("white", 50)), 5, 4, false, java.util.List.of());
        org.junit.jupiter.api.Assertions.assertFalse(r.trustworthy());
        var m = ReferencePalette.majorityTrusted(r);
        org.junit.jupiter.api.Assertions.assertTrue(m.trustworthy());
        org.junit.jupiter.api.Assertions.assertNotEquals(ReferenceSpec.Confidence.HIGH,
                m.confidenceCeiling(), "the renders DID disagree; that must stay visible");
    }

    private static final String OPAQUE_KEY = ReferencePalette.OPAQUE_FRACTION;
}
