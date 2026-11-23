package com.moddingfromamod.reference;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.runtime.SculptCritique;

/**
 * <b>What colours a character is ACTUALLY made of, counted rather than recalled.</b>
 *
 * <p>This is the one reference question a wiki page cannot answer. Prose says "green shell, tan
 * skin"; it never says which of the two you mostly see, and that proportion is what
 * {@code sculpt_creature}'s {@code palette} wants — the field with 100% adoption.
 *
 * <p>The measured stakes, on the best-documented character in the corpus: a text-only lookup put
 * Spikor at <b>green 40% / tan 35%</b>. Six official renders put him at <b>tan 40%</b> with green
 * outside the top four. The creature built to the text number, and every gate stayed green, because
 * {@link ReferenceVerdict} computes {@code |reference − built|} — it can only ever ask "did you
 * build what you were told?", never "was what you were told right?" Nothing in the loop had looked
 * at the character.
 *
 * <h2>A histogram, not a model</h2>
 *
 * <p>Every opaque pixel is bucketed by {@link SculptCritique#bucket(int)} — the same call a BUILT
 * creature is scored with, so the two are directly subtractable. No image is ever shown to a model.
 * That is both the cheap part (measured 0.19s and zero tokens against 74.5s and 122,005 tokens for
 * a vision model on the same four renders) and the safe part: an image is the highest-bandwidth
 * arbitrary payload there is, and nothing here reads one as text.
 *
 * <h2>THE ESTIMATOR IS THE MEDIAN OF N, AND THAT IS NOT A DETAIL</h2>
 *
 * <p>A single render is not a reference — Gate 0 failed 0/4 proving it. One image of a character
 * may be the character in a kart (Dinko reads <i>blue</i>), in a football kit (Dinko reads
 * <i>black</i>), or breathing fire (Spikor reads half red and yellow). Per-image agreement on the
 * top colour was 50–67%. The <b>median across ≥3 renders</b> was correct on all four characters and
 * did not move when any single image was dropped.
 *
 * <p>So this refuses to answer from fewer than {@link #MIN_IMAGES}, and reports how much the answer
 * depends on any one picture. That reliability figure is not decoration: a character whose renders
 * disagree must come back LOW rather than confidently wrong, because a confidently-shaped wrong
 * answer is an anchor rather than a weak signal — which is exactly how Omega Lad built worse than
 * no reference at all.
 *
 * <p>Pure and Minecraft-free apart from the shared bucket rule, so all of it is unit-tested.
 */
public final class ReferencePalette {

    /**
     * Below three renders, leave-one-out says nothing and a single kart owns the answer.
     * Measured: Gate 0, {@code corpus/palette-gate0/}.
     */
    public static final int MIN_IMAGES = 3;

    /**
     * <b>Transparency IS the subject mask.</b> An image with no alpha has its background counted,
     * so a character on white reads as a white character. This one rule caught every junk image in
     * the Gate 0 corpus — a manual scan, a video thumbnail and an opaque-background render — with
     * no filename heuristics at all.
     */
    public static final double MAX_OPAQUE_FRACTION = 0.995;

    /** Colours quieter than this are texture, not palette, and clutter a 12-part brief. */
    private static final double REPORTABLE = 1.5;

    private ReferencePalette() {
    }

    /**
     * <b>Where on the character a colour is, coarsely.</b>
     *
     * <p>A single "tan/orange 57%" is true of Spikor and does not tell the builder that the shell
     * is green and on his BACK. Three bands do, and they cost one extra pass over pixels already in
     * memory.
     *
     * <p>Named by position rather than by anatomy on purpose. Calling the top band "head" is an
     * interpretation, and it is wrong for a fish, a snake, a car and half the brainrot menagerie —
     * the measurement knows where the pixels are and nothing whatever about what they depict.
     */

    /**
     * The subject's own bounding box, from the alpha channel — not the image's.
     *
     * <p>Renders are padded differently, and splitting the IMAGE into thirds would put a
     * generously-margined character's head in the top band on one render and in the middle on the
     * next. Splitting the SUBJECT makes the bands mean the same thing across renders, which is what
     * lets them be combined at all.
     *
     * @return {@code {minY, maxY}} of the opaque rows, or null when nothing is opaque
     */
    static int[] subjectRows(BufferedImage img, int stride) {
        int lo = -1;
        int hi = -1;
        for (int y = 0; y < img.getHeight(); y += stride) {
            boolean any = false;
            for (int x = 0; x < img.getWidth() && !any; x += stride) {
                any = ((img.getRGB(x, y) >>> 24) & 0xFF) >= 128;
            }
            if (any) {
                lo = lo < 0 ? y : lo;
                hi = y;
            }
        }
        return lo < 0 ? null : new int[]{lo, hi};
    }

    /** Composition of each third of the SUBJECT, top to bottom. */
    public static Map<ReferenceSpec.Region, Map<String, Double>> byRegion(BufferedImage img) {
        if (img == null) {
            return Map.of();
        }
        int stride = stride(img);
        int[] rows = subjectRows(img, stride);
        if (rows == null || rows[1] - rows[0] < 3) {
            return Map.of();
        }
        double band = (rows[1] - rows[0] + 1) / 3.0;
        Map<ReferenceSpec.Region, Map<String, Long>> counts = new LinkedHashMap<>();
        Map<ReferenceSpec.Region, Long> kept = new LinkedHashMap<>();
        for (ReferenceSpec.Region r : ReferenceSpec.Region.values()) {
            counts.put(r, new LinkedHashMap<>());
            kept.put(r, 0L);
        }
        for (int y = rows[0]; y <= rows[1]; y += stride) {
            int i = (int) Math.min(2, (y - rows[0]) / band);
            ReferenceSpec.Region r = ReferenceSpec.Region.values()[i];
            for (int x = 0; x < img.getWidth(); x += stride) {
                int argb = img.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) < 128) {
                    continue;
                }
                kept.merge(r, 1L, Long::sum);
                counts.get(r).merge(SculptCritique.bucket(argb & 0xFFFFFF), 1L, Long::sum);
            }
        }
        Map<ReferenceSpec.Region, Map<String, Double>> out = new LinkedHashMap<>();
        for (ReferenceSpec.Region r : ReferenceSpec.Region.values()) {
            long k = kept.get(r);
            if (k == 0) {
                continue;
            }
            Map<String, Double> pct = new LinkedHashMap<>();
            for (var e : counts.get(r).entrySet()) {
                pct.put(e.getKey(), 100.0 * e.getValue() / k);
            }
            out.put(r, pct);
        }
        return out;
    }

    /** Shared so a whole-image count and a per-band count sample exactly the same pixels. */
    private static int stride(BufferedImage img) {
        return Math.max(1, (int) Math.ceil(Math.max(img.getWidth(), img.getHeight()) / 512.0));
    }

    /** How far above its whole-body share a colour must sit to count as belonging to a band. */
    private static final double CONCENTRATED_BY = 6.0;

    /**
     * <b>What is CONCENTRATED in each band — not what leads it.</b>
     *
     * <p>The leader is the wrong statistic and the first cut proved it: Spikor came back
     * {@code upper tan/orange, middle tan/orange, lower tan/orange}, three identical rows carrying
     * no information, because tan skin leads everywhere on him. What a builder needs is the
     * opposite question — which colour is <i>unusually present here</i> — and that is a
     * subtraction: the band's share minus the same colour's share over the whole body. It puts the
     * red mane at the top and the green shell in the middle, which is the thing a single "57%
     * tan/orange" can never say.
     *
     * @param whole the whole-body medians, which is what each band is measured AGAINST
     */
    public static List<ReferenceSpec.RegionColour> regionsAcross(
            List<Map<ReferenceSpec.Region, Map<String, Double>>> perImage,
            Map<String, Double> whole) {
        return regionsAcross(perImage, whole, MIN_IMAGES);
    }

    /**
     * With an explicit floor, so a character whose wiki holds only one drawing gets bands from it.
     *
     * <p>The bands are no less measurable in one image than the whole body is — a render has an
     * upper, a middle and a lower whatever N is. The floor is the same median-of-N argument and
     * takes the same exception; see {@link #across(List, List, boolean)}.
     */
    public static List<ReferenceSpec.RegionColour> regionsAcross(
            List<Map<ReferenceSpec.Region, Map<String, Double>>> perImage,
            Map<String, Double> whole, int minImages) {
        List<Map<ReferenceSpec.Region, Map<String, Double>>> use = perImage == null ? List.of()
                : perImage;
        if (use.size() < minImages) {
            return List.of();
        }
        List<ReferenceSpec.RegionColour> out = new ArrayList<>();
        for (ReferenceSpec.Region r : ReferenceSpec.Region.values()) {
            List<Map<String, Double>> band = new ArrayList<>();
            for (var m : use) {
                if (m.containsKey(r)) {
                    band.add(m.get(r));
                }
            }
            if (band.size() < minImages) {
                continue;
            }
            Map<String, Double> med = median(band);
            List<Map.Entry<String, Double>> ranked = new ArrayList<>(med.entrySet());
            // Sorted by EXCESS over the whole body, so a colour that is merely everywhere never
            // wins a band.
            ranked.sort((a, b) -> Double.compare(
                    b.getValue() - whole.getOrDefault(b.getKey(), 0.0),
                    a.getValue() - whole.getOrDefault(a.getKey(), 0.0)));
            int added = 0;
            for (var e : ranked) {
                if (added >= 2) {
                    break;
                }
                double excess = e.getValue() - whole.getOrDefault(e.getKey(), 0.0);
                if (excess < CONCENTRATED_BY || e.getValue() < REPORTABLE) {
                    break;
                }
                out.add(new ReferenceSpec.RegionColour(r, e.getKey(),
                        (int) Math.round(e.getValue())));
                added++;
            }
        }
        return List.copyOf(out);
    }

    /** One image's composition, as bucket → percent of its OPAQUE pixels. Empty if fully masked. */
    public static Map<String, Double> of(BufferedImage img) {
        if (img == null) {
            return Map.of();
        }
        // A histogram does not need four megapixels, and the cost is linear in them. Sampling a
        // stride is not the same as resizing: it takes ORIGINAL pixels, so it can never invent a
        // blended colour along an edge that belongs to neither side of it.
        int stride = stride(img);
        Map<String, Long> counts = new LinkedHashMap<>();
        long kept = 0;
        long total = 0;
        for (int y = 0; y < img.getHeight(); y += stride) {
            for (int x = 0; x < img.getWidth(); x += stride) {
                total++;
                int argb = img.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) < 128) {
                    continue;
                }
                kept++;
                counts.merge(SculptCritique.bucket(argb & 0xFFFFFF), 1L, Long::sum);
            }
        }
        if (kept == 0) {
            return Map.of();
        }
        Map<String, Double> pct = new LinkedHashMap<>();
        pct.put(OPAQUE_FRACTION, total == 0 ? 0 : (double) kept / total);
        for (var e : counts.entrySet()) {
            pct.put(e.getKey(), 100.0 * e.getValue() / kept);
        }
        return pct;
    }

    /**
     * Smuggled into the same map so a caller cannot read a composition without also being handed
     * the number that says whether to trust it. A separate accessor would be forgotten exactly
     * once.
     */
    public static final String OPAQUE_FRACTION = "#opaque";

    /** Whether one image can contribute at all: it must carry a real subject mask. */
    public static boolean usable(Map<String, Double> one) {
        return one != null && !one.isEmpty()
                && one.getOrDefault(OPAQUE_FRACTION, 1.0) <= MAX_OPAQUE_FRACTION;
    }

    /**
     * The answer, and how much it depends on any one picture.
     *
     * @param colours     biggest first, already in {@link ReferenceSpec}'s vocabulary
     * @param images      how many renders actually contributed
     * @param topSwingPct how far the leading colour's share moved across leave-one-out. Small is
     *                    trustworthy; large means the renders disagree and the caller should say so
     * @param topStable   whether the LEADING colour survived dropping any single image. False is
     *                    the loud failure — the character's own renders cannot agree on what colour
     *                    it mostly is
     */
    public record Reading(List<ReferenceSpec.ColourShare> colours, int images,
                          double topSwingPct, boolean topStable,
                          List<ReferenceSpec.RegionColour> regions, boolean sole) {

        /** The ordinary case: several renders, so stability is a question that can be asked. */
        public Reading(List<ReferenceSpec.ColourShare> colours, int images, double topSwingPct,
                       boolean topStable, List<ReferenceSpec.RegionColour> regions) {
            this(colours, images, topSwingPct, topStable, regions, false);
        }

        public boolean isEmpty() {
            return colours.isEmpty();
        }

        /**
         * <b>Whether this may replace a remembered palette.</b>
         *
         * <p>Not the same question as "is it empty". A reading whose renders cannot agree which
         * colour leads is an answer, and it is a WORSE answer than the model's — measured over six
         * held-out characters, every stable reading was right (3/3) and every unstable one was
         * wrong (0/2). The warrant for overriding recall with a count is that the count is more
         * reliable; when it says it is not, the warrant is gone.
         */
        public boolean trustworthy() {
            return !isEmpty() && (topStable || sole);
        }

        /**
         * What to tell the researcher's caller. Deliberately conservative: this may lower a
         * confidence but never raise one, because the pixels know what the character LOOKS like and
         * nothing about whether it is the right character.
         */
        public ReferenceSpec.Confidence confidenceCeiling() {
            // A SOLE render is measured, and its stability is unknowable rather than bad. It gets
            // the floor, never the ceiling: the number is real, and one drawing cannot tell you
            // whether a second would have agreed.
            if (isEmpty() || sole || !topStable) {
                return ReferenceSpec.Confidence.LOW;
            }
            return topSwingPct <= 15 ? ReferenceSpec.Confidence.HIGH
                    : ReferenceSpec.Confidence.MEDIUM;
        }
    }

    public static Reading empty() {
        return new Reading(List.of(), 0, 0, false, List.of());
    }

    /**
     * <b>The character with only one picture in the world.</b>
     *
     * <p>{@link #MIN_IMAGES} exists because one render is one POSE — Dinko in a kart reads as
     * blue. That argument is about sampling several drawings badly, and it does not apply when
     * there is only one drawing: measured, Omega Lad's wiki enumerates exactly one image (against
     * 29 for Spikor), so refusing at n&lt;3 there is not caution, it is declining to look at the
     * only evidence that exists.
     *
     * <p>What it is NOT: a fallback for a download that failed. The caller must pass
     * {@code allowSole} only when the wiki itself offered no more — six URLs of which five failed
     * is a broken fetch, and measuring the survivor would be exactly the confident-wrong-number
     * this class refuses elsewhere.
     *
     * <p>The reading is marked {@link Reading#sole}, which caps its colour confidence at LOW. The
     * proportions are real; whether a second drawing would have agreed is unknowable, and saying
     * so is the honest form of the answer.
     */
    public static Reading across(List<Map<String, Double>> perImage,
                                 List<Map<ReferenceSpec.Region, Map<String, Double>>> perRegion,
                                 boolean allowSole) {
        Reading full = across(perImage, perRegion);
        if (!full.isEmpty() || !allowSole) {
            return full;
        }
        List<Map<String, Double>> use = new ArrayList<>();
        for (Map<String, Double> m : perImage == null ? List.<Map<String, Double>>of() : perImage) {
            if (usable(m)) {
                use.add(m);
            }
        }
        if (use.isEmpty() || use.size() >= MIN_IMAGES) {
            return full;
        }
        Map<String, Double> med = median(use);
        List<ReferenceSpec.RegionColour> bands = regionsAcross(perRegion, med, use.size());
        if (leader(med) == null) {
            return empty();
        }
        return new Reading(rank(med), use.size(), 0, false, bands, true);
    }

    /**
     * Combine several renders of one character. The MEDIAN per bucket, not the mean: one image of
     * the character in a vehicle is an outlier the median ignores and the mean does not.
     */
    public static Reading across(List<Map<String, Double>> perImage) {
        return across(perImage, List.of());
    }

    /** With the per-band views alongside, so both estimators see the same set of renders. */
    public static Reading across(List<Map<String, Double>> perImage,
                                 List<Map<ReferenceSpec.Region, Map<String, Double>>> perRegion) {
        List<Map<String, Double>> use = new ArrayList<>();
        for (Map<String, Double> m : perImage == null ? List.<Map<String, Double>>of() : perImage) {
            if (usable(m)) {
                use.add(m);
            }
        }
        if (use.size() < MIN_IMAGES) {
            return empty();
        }
        Map<String, Double> med = median(use);
        // Bands are measured against the whole body, so this must come after it.
        List<ReferenceSpec.RegionColour> bands = regionsAcross(perRegion, med);
        String top = leader(med);
        if (top == null) {
            return empty();
        }
        // LEAVE ONE OUT. The question is not "how noisy are the images" but "would this answer
        // survive me not having found one of them", which is the situation every real lookup is in.
        boolean stable = true;
        double lo = med.get(top);
        double hi = lo;
        for (int i = 0; i < use.size(); i++) {
            List<Map<String, Double>> sub = new ArrayList<>(use);
            sub.remove(i);
            Map<String, Double> m = median(sub);
            stable &= top.equals(leader(m));
            double v = m.getOrDefault(top, 0.0);
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }

        return new Reading(rank(med), use.size(), hi - lo, stable, bands);
    }

    /**
     * The share of leave-one-out subsets whose leader is the full median's leader (round
     * 2). {@link #across} requires 1.0; this is what a looser rule is measured against.
     */
    public static double leaderSurvival(List<Map<String, Double>> perImage) {
        List<Map<String, Double>> use = new ArrayList<>();
        for (Map<String, Double> m : perImage == null ? List.<Map<String, Double>>of() : perImage) {
            if (usable(m)) {
                use.add(m);
            }
        }
        if (use.size() < MIN_IMAGES) {
            return 0;
        }
        String top = leader(median(use));
        if (top == null) {
            return 0;
        }
        int kept = 0;
        for (int i = 0; i < use.size(); i++) {
            List<Map<String, Double>> sub = new ArrayList<>(use);
            sub.remove(i);
            kept += top.equals(leader(median(sub))) ? 1 : 0;
        }
        return (double) kept / use.size();
    }

    /**
     * The same reading, trusted by a majority rather than unanimously. Its swing is floored above
     * the HIGH band so its confidence can never read HIGH: the renders did disagree.
     */
    public static Reading majorityTrusted(Reading r) {
        return new Reading(r.colours(), r.images(), Math.max(r.topSwingPct(), 16), true,
                r.regions());
    }

    /** Biggest first, capped, and anything under {@link #REPORTABLE} dropped. */
    private static List<ReferenceSpec.ColourShare> rank(Map<String, Double> med) {
        List<ReferenceSpec.ColourShare> colours = new ArrayList<>();
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(med.entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        for (var e : ranked) {
            if (colours.size() >= ReferenceSpec.MAX_COLOURS || e.getValue() < REPORTABLE) {
                break;
            }
            // Rounded here rather than at the boundary so what is reported is what was measured.
            colours.add(new ReferenceSpec.ColourShare(e.getKey(), (int) Math.round(e.getValue())));
        }
        return List.copyOf(colours);
    }

    /** Median share per bucket. An image where a colour is absent contributes a real zero. */
    private static Map<String, Double> median(List<Map<String, Double>> imgs) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String b : ReferenceSpec.COLOURS) {
            List<Double> vals = new ArrayList<>(imgs.size());
            for (Map<String, Double> m : imgs) {
                // ABSENT MUST COUNT AS ZERO. Taking the median only of the images that HAVE a
                // colour makes a colour in one render out of six look as strong as one in all six.
                vals.add(m.getOrDefault(b, 0.0));
            }
            Collections.sort(vals);
            int n = vals.size();
            out.put(b, n % 2 == 1 ? vals.get(n / 2)
                    : (vals.get(n / 2 - 1) + vals.get(n / 2)) / 2.0);
        }
        return out;
    }

    private static String leader(Map<String, Double> m) {
        String best = null;
        double v = 0;
        for (var e : m.entrySet()) {
            if (e.getValue() > v) {
                v = e.getValue();
                best = e.getKey();
            }
        }
        return v >= REPORTABLE ? best : null;
    }
}
