package com.moddingfromamod.reference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptCritique;

/**
 * <b>Did the creature actually come out looking like the real thing?</b>
 *
 * <p>The reference is spent on <b>evaluation</b> rather than generation, and that is the whole
 * point of the redesign. Three reasons it belongs here rather than in front of the build:
 *
 * <ul>
 *   <li><b>Nothing blocks.</b> A search takes 26-112 seconds and the agent asks within seconds of
 *       the wish, so there was never enough overlap to hide it. Judging afterwards removes the race
 *       entirely — the child waits for nothing.</li>
 *   <li><b>This is where the reference is proven to help.</b> As a generation input it helped 2 of 3
 *       creatures by human judgement, which is not evidence. As an evaluation input it fixes the
 *       vision judge's single worst failure: it preferred a generic man in a suit over a sourced
 *       Omega Lad <i>because it had no reference</i>, and supplying one moved the verdict.</li>
 *   <li><b>It answers a question nothing else here can.</b> Every objective metric is saturated and
 *       the judge shares the builder's ignorance. A sourced palette is the first outside fact in
 *       the loop.</li>
 * </ul>
 *
 * <p>Deliberately <b>arithmetic, not a model call</b>. {@link ReferenceSpec} reports colours in the
 * same buckets {@link SculptCritique} does, so the comparison is a subtraction — instant, free,
 * deterministic, and impossible to bias. A vision pass can be layered on later for shape; colour is
 * where the recorded failures actually were.
 *
 * <p>Pure and Minecraft-free, so it is unit-tested without a game.
 */
public record ReferenceVerdict(Grade grade, double distance, List<String> misses,
                               String childFriendly, String followUpWish) {

    /**
     * How close the build came.
     *
     * <p>Three levels, not a score, because the only decision this drives is what to say to a
     * seven-year-old next — and "0.34" is not something to say to a seven-year-old.
     */
    public enum Grade { ABOUT_RIGHT, PARTLY_WRONG, COMPLETELY_WRONG, UNKNOWN }

    /** Total absolute palette difference above which the creature is simply not the character. */
    public static final double COMPLETELY_WRONG_ABOVE = 0.90;
    /** Below this it reads as the character; small colour drift is not worth telling a child about. */
    public static final double ABOUT_RIGHT_BELOW = 0.45;

    public static ReferenceVerdict unknown() {
        return new ReferenceVerdict(Grade.UNKNOWN, 0, List.of(),
                "I couldn't find a picture to check against, so this one is my best guess.", "");
    }

    /**
     * Compare what was built against what the reference says.
     *
     * @param parts     the finished creature
     * @param reference the sourced description; an empty one yields {@link Grade#UNKNOWN}
     * @param name      the character, for the sentence shown to the child
     */
    public static ReferenceVerdict of(List<CreatureSculpt.Part> parts, ReferenceSpec reference,
                                      String name) {
        return of(parts, reference, name, "");
    }

    /**
     * As above, but told <b>what the child actually asked for</b> — and how sure the reference is.
     *
     * <h2>Why both, and what happened without them</h2>
     * Steve invented a creature called a Snorble and asked for "bright orange with green stripes and
     * one big purple eye". {@code look_up_character} answered — for a thing that does not exist —
     * with <i>confidence LOW</i> and a palette of white 58%, purple 18%, black 14%, cyan 10%. This
     * class then discarded the confidence and told the agent:
     *
     * <blockquote>I checked a real picture of Snorble and I got this one wrong (not enough white,
     * not enough purple, too much orange-brown, too much green).</blockquote>
     *
     * <b>There is no real picture of Snorble.</b> The agent, having no reason to disbelieve an
     * authoritative-sounding tool, complied: two of three cross-worker runs repainted the creature
     * to white 55% / purple 17% / black 18% / cyan 10% — within a few points of the invented
     * reference. It was not forgetting the child's words; it was obeying a phantom.
     *
     * <p>So two rules, and the second matters even at HIGH confidence:
     *
     * <ol>
     *   <li><b>Confidence governs how assertively this speaks.</b> LOW behaves exactly like NONE,
     *       which already says the right thing ("build from the child's own words, and do not imply
     *       an official version exists"). MEDIUM may raise a doubt but never returns a verdict or a
     *       follow-up wish — measured across 139 lookups, MEDIUM is usually the RIGHT character
     *       under a misspelt name ("Tralalelo Tralalala" vs the HIGH "Tralalero Tralala"), so
     *       filtering it out would throw away good references over a seven-year-old's spelling.
     *       HIGH is unchanged: that is Spikor and Zippy, and the check earns its place there.</li>
     *   <li><b>A colour the child named himself is never contradicted.</b> He is the reference. If
     *       he said "bright orange", "too much orange-brown" is not a defect report, it is the tool
     *       arguing with the customer.</li>
     * </ol>
     *
     * @param childWords the wish in the child's own words, or "" if unknown
     */
    public static ReferenceVerdict of(List<CreatureSculpt.Part> parts, ReferenceSpec reference,
                                      String name, String childWords) {
        if (parts == null || parts.isEmpty() || reference == null || reference.colours().isEmpty()) {
            return unknown();
        }
        // A guess is not a reference. LOW joins NONE rather than pretending to be a photograph.
        // THE COLOUR AXIS, because this grades a PALETTE. Whether the researcher was sure it
        // found the right character is a different question from whether the colours are precise,
        // and grading a counted palette against the researcher's subject-confidence was mixing
        // them.
        ReferenceSpec.Confidence confidence = reference.colourConfidence();
        if (confidence == ReferenceSpec.Confidence.LOW
                || confidence == ReferenceSpec.Confidence.NONE) {
            return unknown();
        }
        Map<String, Double> built = SculptCritique.palette(parts);

        double distance = 0;
        List<String> misses = new ArrayList<>();
        for (var c : reference.colours()) {
            double want = c.percent() / 100.0;
            double got = built.getOrDefault(c.colour(), 0.0);
            distance += Math.abs(want - got);
            // Only mention a colour that is genuinely missing or genuinely swamping the creature.
            // A child does not care that green is 38% instead of 44%.
            if (childNamed(childWords, c.colour())) {
                continue;   // he asked for it; the reference does not get a vote
            }
            if (want >= 0.15 && got < want / 3) {
                misses.add("not enough " + plain(c.colour()));
            } else if (got >= 0.25 && want < 0.08) {
                misses.add("too much " + plain(c.colour()));
            }
        }
        // Colours the creature has a lot of that the reference does not mention at all.
        for (var e : built.entrySet()) {
            boolean known = reference.colours().stream()
                    .anyMatch(c -> c.colour().equals(e.getKey()));
            if (!known && e.getValue() >= 0.25 && !childNamed(childWords, e.getKey())) {
                misses.add("too much " + plain(e.getKey()));
            }
        }

        Grade g = distance >= COMPLETELY_WRONG_ABOVE ? Grade.COMPLETELY_WRONG
                : distance <= ABOUT_RIGHT_BELOW ? Grade.ABOUT_RIGHT : Grade.PARTLY_WRONG;

        // MEDIUM may wonder aloud; it may not deliver a verdict, and it may not seed a "fix it"
        // wish. A doubt the agent can weigh is useful; an instruction it will obey is not.
        boolean sure = confidence == ReferenceSpec.Confidence.HIGH;
        return new ReferenceVerdict(g, distance, List.copyOf(misses),
                sure ? sentence(g, name, misses) : unsure(g, name, misses),
                sure ? followUp(g, name, misses) : "");
    }

    /**
     * Colours the child asked for by name, so the reference cannot argue with him.
     *
     * <p>Bucket names are not words a child says — he writes "orange", not "tan/orange" — so each
     * bucket carries the plain words that mean it.
     */
    static boolean childNamed(String childWords, String bucket) {
        if (childWords == null || childWords.isBlank() || bucket == null) {
            return false;
        }
        String said = childWords.toLowerCase(java.util.Locale.ROOT);
        for (String word : switch (bucket) {
            case "tan/orange" -> new String[] {"orange", "tan", "ginger", "amber"};
            case "purple/pink" -> new String[] {"purple", "pink", "violet", "magenta"};
            case "grey" -> new String[] {"grey", "gray", "silver"};
            default -> new String[] {bucket};
        }) {
            if (said.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** A doubt, not a verdict — for a reference that might be of a different character entirely. */
    private static String unsure(Grade g, String name, List<String> misses) {
        if (g == Grade.ABOUT_RIGHT || misses.isEmpty()) {
            return "";
        }
        return "I might be thinking of a different character, but the picture I found for " + name
                + " has different colours (" + String.join(", ", misses) + "). Trust the child's "
                + "own words over this.";
    }

    /** Bucket names are for reports; a child gets ordinary words. */
    private static String plain(String bucket) {
        return switch (bucket) {
            case "tan/orange" -> "orange-brown";
            case "purple/pink" -> "purple";
            default -> bucket;
        };
    }

    /**
     * The child-facing sentence — and it says what the researcher ACTUALLY did.
     *
     * <p>It used to open <i>"I checked a real picture of X"</i>, on every grade, and that was false.
     * {@code ReferenceResearcher} launches with {@code --allowed-tools WebSearch,WebFetch}: two
     * TEXT tools. No image is ever fetched, and the cached artifact carries wiki URLs and zero
     * images. The colour buckets are read out of prose and the model's own recall.
     *
     * <p>It reached the child, too — that exact sentence opened <b>24 of 24</b>
     * {@code sculpt_creature} results in the action log.
     *
     * <p>It is the same claim the Snorble failure above turned on, one layer out. That fix stopped
     * LOW confidence from speaking at all; it left the WORDING asserting a picture that does not
     * exist, to an agent with no way to disbelieve it. "Looked up" is true, and no weaker for the
     * agent's purposes — what carries the weight is the colour comparison, not how it was obtained.
     *
     * <p>If a vision pass is ever added, this is the sentence that becomes true and should be
     * changed back — deliberately, and not before.
     */
    private static String sentence(Grade g, String name, List<String> misses) {
        String what = misses.isEmpty() ? "" : " (" + String.join(", ", misses) + ")";
        return switch (g) {
            case ABOUT_RIGHT -> "I looked up what " + name + " really looks like — this looks right!";
            case PARTLY_WRONG -> "I looked up what " + name + " really looks like. Close, but the "
                    + "colours are a bit off" + what + ".";
            case COMPLETELY_WRONG -> "I looked up what " + name + " really looks like and I got this "
                    + "one wrong" + what + ". Want me to try again?";
            default -> "";
        };
    }

    /**
     * The next wish, in the child's own words, ready to run.
     *
     * <p>Empty when the creature is about right — offering to fix something that is fine teaches a
     * child that nothing is ever finished.
     */
    private static String followUp(Grade g, String name, List<String> misses) {
        if (g == Grade.ABOUT_RIGHT || g == Grade.UNKNOWN || misses.isEmpty()) {
            return "";
        }
        return "fix " + name + " — " + String.join(" and ", misses);
    }

    public boolean worthMentioning() {
        return grade == Grade.PARTLY_WRONG || grade == Grade.COMPLETELY_WRONG;
    }
}
