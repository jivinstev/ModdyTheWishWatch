package com.moddingfromamod.reference;

import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptCritique;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * <b>Is the MAIN colour right?</b> (idea 2: level 2 of the generator ladder.) Pure.
 *
 * <p>Measured 's A/B (Opus at low effort): a stand-in for a named character changes its
 * colours on purpose, to count as "original": a lavender Puffo, a sky-blue Sparkit, an
 * orange-and-teal Plumo. {@link ReferenceVerdict} already compared the build with the reference,
 * but it spoke to the CHILD ("Want me to try again?") and called pink "purple", so the agent
 * shipped the lavender Puffo with the warning in hand. This speaks to the AGENT, before the reveal,
 * about the one thing a child recognises first: the colour the character mostly is.
 *
 * <p>It never argues with the child: a colour he named in his own words is his.
 */
public final class MainColourCheck {

    /** The reference's main colour must be at least this share to be the character's colour. */
    static final double MAIN_AT_LEAST = 0.25;
    /** The build keeps the main colour when it holds at least this fraction of the reference's share. */
    static final double KEPT_FRACTION = 0.5;

    private MainColourCheck() {
    }

    /** Colour shares of a picture by painted area, in the same buckets a sculpt uses. */
    public static Map<String, Double> pictureShares(int size, List<CreatureSculpt.PaintRect> rects) {
        Map<String, Double> acc = new LinkedHashMap<>();
        if (rects == null || rects.isEmpty() || size <= 0) {
            return acc;
        }
        int[] px = CreatureSculpt.rasterise(size, rects);
        int painted = 0;
        for (int argb : px) {
            if ((argb >>> 24) == 0) {
                continue;
            }
            painted++;
            acc.merge(SculptCritique.bucket(argb & 0xFFFFFF), 1.0, Double::sum);
        }
        if (painted > 0) {
            for (var e : acc.entrySet()) {
                e.setValue(e.getValue() / painted);
            }
        }
        return acc;
    }

    /**
     * The sentence for the agent, or empty.
     *
     * @param built      the build's colour shares (bucket -> 0..1)
     * @param reference  the sourced reference; a LOW or NONE colour confidence says nothing
     * @param name       the character
     * @param childWords the child's own wish, whose named colours always win
     * @param redo       how to fix it, e.g. "re-sculpt the same id" or "repaint the same id"
     */
    public static Optional<String> forAgent(Map<String, Double> built, ReferenceSpec reference, String name,
                                            String childWords, String redo) {
        if (built == null || built.isEmpty() || reference == null || reference.colours().isEmpty()) {
            return Optional.empty();
        }
        var confidence = reference.colourConfidence();
        if (confidence == ReferenceSpec.Confidence.LOW || confidence == ReferenceSpec.Confidence.NONE) {
            return Optional.empty();
        }
        ReferenceSpec.ColourShare main = reference.colours().get(0);
        for (var c : reference.colours()) {
            if (c.percent() > main.percent()) {
                main = c;
            }
        }
        double want = main.percent() / 100.0;
        if (want < MAIN_AT_LEAST) {
            return Optional.empty();
        }
        String top = null;
        double topShare = -1;
        for (var e : built.entrySet()) {
            if (e.getValue() > topShare) {
                top = e.getKey();
                topShare = e.getValue();
            }
        }
        double got = built.getOrDefault(main.colour(), 0.0);
        if (got >= want * KEPT_FRACTION || main.colour().equals(top)) {
            return Optional.empty();
        }
        if (ReferenceVerdict.childNamed(childWords, top) || ReferenceVerdict.childNamed(childWords, main.colour())) {
            return Optional.empty();   // he asked for a colour; his words win
        }
        return Optional.of(String.format(Locale.ROOT,
                "⚠ MAIN COLOUR WRONG: fix this before you show him. The real %s is mostly %s (about %d%%); "
                        + "you made it mostly %s (%d%%), with %s at only %d%%. A child knows a character by its "
                        + "colours first, so even an original stand-in of your own design keeps them, in their "
                        + "proportions: change the face, logos and name instead, never the colours. %s with %s "
                        + "as the main colour.",
                name, words(main.colour()), main.percent(), words(top), Math.round(topShare * 100),
                words(main.colour()), Math.round(got * 100), redo, words(main.colour())));
    }

    /** Bucket names in words that cannot be misread ("purple/pink" is pink as often as purple). */
    static String words(String bucket) {
        return switch (bucket == null ? "" : bucket) {
            case "tan/orange" -> "tan or orange";
            case "purple/pink" -> "pink or purple";
            default -> bucket;
        };
    }
}
