package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>A mirror the agent can hold up to the creature it just sculpted.</b>
 *
 * <p>The agent knows perfectly well what Spikor looks like. What it has never had is any feedback
 * about <i>what it actually built</i> — so it sculpted a Spikor that is <b>77% green with 7% skin
 * tone</b> across six revisions and had no way to notice, because nothing ever told it.
 *
 * <p>(This javadoc used to continue "real Spikor is mostly tan". That was written from memory and
 * is wrong — his shell and head are green, roughly half of him. The same false claim reached the
 * agent through a tool description and it obeyed it exactly, building a 65%-tan Spikor. Left here
 * deliberately: the error is the point, and a corrected file with no record of the correction
 * invites the next person to re-introduce it.)
 *
 * <p>So this does not contain a reference database and does not judge. It reports plain facts about
 * the geometry — the palette it came out as, the parts nobody can see, how tall it is — and the
 * creature lessons tell the agent to compare those facts against the character it was asked for.
 * Knowledge lives in the model; observation is what was missing.
 *
 * <h2>The rule that generalises</h2>
 *
 * <p>Chucko's white face existed in the model and was entirely enclosed by the hair boxes, so the
 * front view was a featureless cube. The useful rule is not "faces must be visible" — it is:
 *
 * <blockquote><b>A part nobody can see is a part you didn't build.</b></blockquote>
 *
 * <p>Subject-independent, objective, and it applies to every creature including invented ones,
 * which is most of them.
 *
 * <p>Pure and Minecraft-free, so it is unit-tested without a game.
 */
public final class SculptCritique {

    /**
     * How visible a part has to be, and it depends on WHAT THE PART IS.
     *
     * <p>Calibrated against real creatures, and the calibration is the interesting part. A single
     * threshold cannot work: Spikor's torso is 28% visible (limbs and shell cover it — perfectly
     * normal anatomy) while Chucko's rejected head was 20% (buried in hair, and it read as a
     * featureless cube). Eight percentage points apart, opposite verdicts. Any flat number either
     * blocks good torsos or lets buried faces through, which is criterion 4 of the gate rubric
     * failing outright.
     *
     * <p>What separates them is not geometry, it is ROLE. A part called a face exists in order to
     * be looked at; a torso does not. So a face must be mostly visible, and anything else only has
     * to exist somewhere in view.
     */
    /**
     * <b>How much of a face feature has to show, and this number is MEASURED rather than chosen.</b>
     *
     * <p>It was 0.50, which was a guess, and the guess was wrong in kind. A face is built by
     * stacking smaller boxes on bigger ones — a pupil on an eye, an eye and a snout on a head — so
     * a CORRECTLY built face feature is always partly covered by its own detail. Measured across
     * five real Spikors, well-made parts sat at:
     *
     * <pre>
     *   eyeL / eyeR   44-47% visible   (covered by their own pupil and brow)
     *   head          28-44% visible   (covered by its own snout and eyes)
     * </pre>
     *
     * At 0.50 every one of those was reported as buried, on four creatures in five. That is not a
     * tuning error: the threshold was asking a question whose correct answer is "yes, partly".
     *
     * <p>0.15 sits below every correctly-built part observed and still catches the defect this
     * exists for — a face wrapped in hair, which reads as a blank lump and shows almost nothing.
     * Re-derive it against {@code CreatureLintSweepTest} rather than nudging it by feel.
     */
    private static final double FACE_MUST_BE_VISIBLE = 0.15;
    private static final double ANYTHING_ELSE = 0.05;

    /** Parts whose whole purpose is to be seen. Hiding one is always a defect. */
    private static final List<String> FACE_WORDS =
            List.of("face", "eye", "mouth", "snout", "nose", "beak", "smile", "tooth", "teeth");

    private static boolean isAFeature(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String w : FACE_WORDS) {
            if (n.contains(w)) {
                return true;
            }
        }
        // A bare "head" counts: on a sculpt whose face is a separate box the head IS the face.
        return n.equals("head") || n.startsWith("head_");
    }

    /** A colour bucket big enough to be worth naming in the report. */
    private static final double NOTABLE_SHARE = 0.04;

    /**
     * How many occluders to name. Three is enough to act on; the full list on a 59-part creature
     * is a wall of text the agent has to re-read every turn, and this note is already the longest
     * thing in the sculpt answer.
     */
    private static final int MAX_OCCLUDERS_NAMED = 3;

    /** Boxes this close count as touching. Forgives float drift, not a real gap. */
    private static final float TOUCH_EPSILON = 0.01f;

    private SculptCritique() {
    }

    /** What the sculpt actually came out as. */
    public record Report(Map<String, Double> palette, List<String> buried,
                         List<String> floating, int partCount, int heightPixels,
                         List<String> flat, List<String> lopsided, List<String> blankEyes) {

        /** Before the blank-eyes line. */
        public Report(Map<String, Double> palette, List<String> buried,
                      List<String> floating, int partCount, int heightPixels,
                      List<String> flat, List<String> lopsided) {
            this(palette, buried, floating, partCount, heightPixels, flat, lopsided, List.of());
        }

        /** Before the left/right line (2026-10-10). */
        public Report(Map<String, Double> palette, List<String> buried,
                      List<String> floating, int partCount, int heightPixels,
                      List<String> flat) {
            this(palette, buried, floating, partCount, heightPixels, flat, List.of());
        }

        /** Before the flat-surface line (2026-10-10). */
        public Report(Map<String, Double> palette, List<String> buried,
                      List<String> floating, int partCount, int heightPixels) {
            this(palette, buried, floating, partCount, heightPixels, List.of());
        }

        /** Pre-#342 shape, kept so existing call sites and fixtures still compile. */
        public Report(Map<String, Double> palette, List<String> buried,
                      int partCount, int heightPixels) {
            this(palette, buried, List.of(), partCount, heightPixels);
        }

        /** The note appended to the sculpt tool's answer. Empty is impossible — facts always help. */
        public String describe() {
            StringBuilder sb = new StringBuilder("WHAT YOU ACTUALLY BUILT (look at this before "
                    + "showing the child — it is the only feedback you get):\n");
            sb.append("  colours by volume: ");
            sb.append(palette.isEmpty() ? "(none)" : String.join(", ",
                    palette.entrySet().stream()
                            .map(e -> String.format("%s %.0f%%", e.getKey(), e.getValue() * 100))
                            .toList()));
            sb.append('\n');
            sb.append("  parts: ").append(partCount)
              .append(", height: ").append(heightPixels).append(" pixels (16 = one block)\n");
            if (!buried.isEmpty()) {
                sb.append("  ⚠ BURIED — these parts cannot be seen from outside, so as far as the "
                        + "child is concerned you did not build them:\n    - ")
                  .append(String.join("\n    - ", buried))
                  .append("\n    FIRST make whatever covers them smaller or move THAT aside. Only "
                          + "move the buried part itself outward as a last resort — an eye pushed "
                          + "out of its socket bulges, and a bulging eye is a worse creature than "
                          + "a slightly shadowed one. A face wrapped in hair reads as a blank "
                          + "lump.\n");
            }
            if (!flat.isEmpty()) {
                sb.append("  ◻ FLAT — its biggest parts (").append(String.join(", ", flat))
                  .append(") are each one plain colour, so up close it reads as painted blocks. "
                          + "Give the body and head a `pattern` with a `pattern_colour` — fur, "
                          + "scales, spots, stripes, speckle or gradient — the way the ready-made "
                          + "creatures all have one. It costs no extra parts.\n");
            }
            if (!blankEyes.isEmpty()) {
                sb.append("  ⚠ BLANK EYES — ").append(String.join(", ", blankEyes))
                  .append(" are each one solid colour with nothing in front of them, so the face reads "
                          + "empty. Give each eye a small darker pupil standing in front of it (and a "
                          + "one-unit white highlight), the way a cartoon eye reads, unless his words ask "
                          + "for solid or dot eyes.\n");
            }
            if (!lopsided.isEmpty()) {
                sb.append("  ⇔ LOPSIDED — these left/right pairs do not match, so one side looks "
                        + "broken (a wolf with one eye twice the size of the other reads as a "
                        + "wink or an injury):\n    - ")
                  .append(String.join("\n    - ", lopsided))
                  .append("\n    Make the pair the same size and mirror it across the middle "
                          + "(x → −x). Leave it only if the wish ASKED for one side to differ.\n");
            }
            if (!floating.isEmpty()) {
                sb.append("  ⚠ FLOATING — these parts touch nothing else, so they hang in the air "
                        + "beside the creature:\n    - ")
                  .append(String.join("\n    - ", floating))
                  .append("\n    Move them until they meet the part they belong to.\n");
            }
            sb.append("  Compare the colours against what the character REALLY looks like. If the "
                    + "proportions are wrong, the creature will not read as that character no "
                    + "matter how many parts it has.");
            return sb.toString();
        }

        /** True when something is wrong enough that the agent should fix it before revealing. */
        public boolean hasProblems() {
            return !buried.isEmpty() || !floating.isEmpty();
        }
    }

    /** Look at a sculpt and report what it is, not what it should be. */
    public static Report critique(List<CreatureSculpt.Part> parts) {
        return critique(parts, "");
    }

    /**
     * With the words the creature was made from (his wish, and a brief's look), so a face he ASKED
     * to have solid eyes is not told to add pupils.
     */
    public static Report critique(List<CreatureSculpt.Part> parts, String words) {
        if (parts == null || parts.isEmpty()) {
            return new Report(Map.of(), List.of(), 0, 0);
        }
        return new Report(palette(parts), buried(parts), floating(parts), parts.size(),
                heightPixels(parts), flat(parts), lopsided(parts),
                asksForSolidEyes(words) ? List.of() : blankEyes(parts));
    }

    private static final java.util.regex.Pattern EYE = java.util.regex.Pattern.compile("(?i)eye");
    private static final java.util.regex.Pattern NOT_AN_EYE =
            java.util.regex.Pattern.compile("(?i)brow|lid|lash|patch|white|socket|stalk|mask|ring");
    private static final java.util.regex.Pattern EYE_DETAIL =
            java.util.regex.Pattern.compile("(?i)pupil|iris|shine|glint|highlight|catch|spark|glare|dot");
    private static final java.util.regex.Pattern SOLID_EYES = java.util.regex.Pattern.compile(
            "(?i)\\b(solid|dot|button|beady|bead|blank|glowing|plain)(?:[- ]\\w+)?[- ]?eye|eyes? (are|is) (solid|plain|all) ");

    static boolean asksForSolidEyes(String words) {
        return words != null && SOLID_EYES.matcher(words).find();
    }

    /**
     * <b>Eyes with nothing in them</b>: every eye is one solid box, with no pupil, iris or
     * highlight in front of it. Measured on the archive: 5 's 24 replayed creatures (a Link with
     * flat blue eyes, a Zippy with flat green ones), where the character's eyes have a pupil; a face like
     * that reads as blank. A detail is a part named for one, or any smaller box of another colour in
     * front of the eye over its face.
     */
    static List<String> blankEyes(List<CreatureSculpt.Part> parts) {
        List<CreatureSculpt.Part> eyes = new ArrayList<>();
        for (CreatureSculpt.Part p : parts) {
            if (EYE.matcher(p.name()).find() && !NOT_AN_EYE.matcher(p.name()).find()
                    && !EYE_DETAIL.matcher(p.name()).find()) {
                eyes.add(p);
            }
        }
        if (eyes.isEmpty()) {
            return List.of();
        }
        for (CreatureSculpt.Part p : parts) {
            if (EYE_DETAIL.matcher(p.name()).find()) {
                return List.of();
            }
        }
        for (CreatureSculpt.Part e : eyes) {
            // A patterned eye has its own detail; a small or near-black eye is a DOT eye, which reads as an
            // eye (a cat's, Bowtie Kitty's): only a big flat coloured eye reads blank.
            float faceArea = Math.max((e.to()[0] - e.from()[0]) * (e.to()[1] - e.from()[1]),
                    (e.to()[2] - e.from()[2]) * (e.to()[1] - e.from()[1]));
            if (e.patterned() || faceArea < 4 || "black".equals(bucket(e.colour()))) {
                return List.of();
            }
            double ev = vol(e);
            for (CreatureSculpt.Part d : parts) {
                if (d == e || d.colour() == e.colour() || vol(d) >= ev) {
                    continue;
                }
                boolean over = d.from()[0] < e.to()[0] && e.from()[0] < d.to()[0]
                        && d.from()[1] < e.to()[1] && e.from()[1] < d.to()[1];
                boolean front = d.from()[2] <= e.from()[2] || d.to()[2] >= e.to()[2];
                if (over && front) {
                    return List.of();
                }
            }
        }
        return eyes.stream().map(CreatureSculpt.Part::name).toList();
    }

    private static double vol(CreatureSculpt.Part p) {
        return (double) (p.to()[0] - p.from()[0]) * (p.to()[1] - p.from()[1]) * (p.to()[2] - p.from()[2]);
    }

    /** A pair smaller than this may be off-centre on purpose: a catchlight sits on one side of each eye. */
    private static final double LOPSIDED_PLACE_MIN_VOLUME = 4;

    /**
     * <b>Left/right pairs that do not mirror</b>: a different size, or (for anything bigger than a
     * highlight) not placed as each other's reflection across x = 0, the middle every sculpt is
     * built about. Pairs are found by name — {@code eyeL}/{@code eyeR}, {@code legBL}/{@code legBR},
     * {@code left_wing}/{@code right_wing}. Found 2026-10-10: the ready-made wolf had one eye twice
     * the width of the other, and every wolf wish inherited it; nothing in the answer said so.
     */
    static List<String> lopsided(List<CreatureSculpt.Part> parts) {
        if (parts == null) {
            return List.of();
        }
        Map<String, CreatureSculpt.Part[]> pairs = new java.util.LinkedHashMap<>();
        for (CreatureSculpt.Part p : parts) {
            String[] side = side(p.name());
            if (side != null) {
                pairs.computeIfAbsent(side[0], k -> new CreatureSculpt.Part[2])[side[1].equals("L") ? 0 : 1] = p;
            }
        }
        List<String> out = new java.util.ArrayList<>();
        for (CreatureSculpt.Part[] pair : pairs.values()) {
            CreatureSculpt.Part l = pair[0], r = pair[1];
            if (l == null || r == null) {
                continue;
            }
            String sl = size(l), sr = size(r);
            if (!sl.equals(sr)) {
                out.add(l.name() + " is " + sl + " but " + r.name() + " is " + sr);
                continue;
            }
            float cl = (l.from()[0] + l.to()[0]) / 2f, cr = (r.from()[0] + r.to()[0]) / 2f;
            boolean mirrored = Math.abs(cl + cr) < 0.5f
                    && Math.abs((l.from()[1] + l.to()[1]) - (r.from()[1] + r.to()[1])) < 1f
                    && Math.abs((l.from()[2] + l.to()[2]) - (r.from()[2] + r.to()[2])) < 1f;
            if (!mirrored && volumeOf(l) >= LOPSIDED_PLACE_MIN_VOLUME) {
                out.add(l.name() + " and " + r.name() + " are not each other's mirror image "
                        + "(centres at x " + fmt(cl) + " and " + fmt(cr) + ")");
            }
        }
        return out;
    }

    private static final java.util.regex.Pattern SUFFIX_SIDE =
            java.util.regex.Pattern.compile("(.*[a-z0-9_][A-Z]?)([LR])(\\d*)");
    private static final java.util.regex.Pattern WORD_SIDE =
            java.util.regex.Pattern.compile(
                    "(.*?)(?:^|[_ -]|(?<=[a-z])(?=[LR]))((?i:left|right))(?=$|[_ -]|[A-Z0-9])(.*)");

    /** {pair key, "L"|"R"} for a part named as one side of a pair, else null. */
    static String[] side(String name) {
        if (name == null) {
            return null;
        }
        var m = SUFFIX_SIDE.matcher(name);
        if (m.matches()) {
            return new String[] {m.group(1) + "|" + m.group(3), m.group(2)};
        }
        m = WORD_SIDE.matcher(name);
        if (m.matches()) {
            return new String[] {(m.group(1) + "|" + m.group(3)).toLowerCase(java.util.Locale.ROOT),
                    m.group(2).toLowerCase(java.util.Locale.ROOT).startsWith("l") ? "L" : "R"};
        }
        return null;
    }

    private static String size(CreatureSculpt.Part p) {
        return fmt(Math.abs(p.to()[0] - p.from()[0])) + "×" + fmt(Math.abs(p.to()[1] - p.from()[1]))
                + "×" + fmt(Math.abs(p.to()[2] - p.from()[2]));
    }

    private static String fmt(float v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** How many of the biggest parts a FLAT report names. */
    private static final int FLAT_BIGGEST = 3;

    /**
     * <b>The biggest parts, when NO part carries a pattern</b>; empty otherwise, and empty for
     * a sculpt too small to need one. Measured 2026-10-10 over every archived sculpt: patterns on
     * 1.5% of Muse's parts and 2% of Opus's, while every ready-made creature carries one — so the
     * textured creatures a child saw were the ones a wish started from, and a creature sculpted
     * from nothing was flat. Nothing in the answer ever said so.
     */
    static List<String> flat(List<CreatureSculpt.Part> parts) {
        if (parts == null || parts.size() <= FLAT_BIGGEST) {
            return List.of();
        }
        // ANY skin anywhere is enough: the ready-made frog wears its one pattern on a small part,
        // and a check that called a ready-made creature flat would be telling the agent to undo
        // the very thing it holds up as the example.
        for (CreatureSculpt.Part p : parts) {
            if (p.pattern() != null && !p.pattern().isBlank()) {
                return List.of();
            }
        }
        List<CreatureSculpt.Part> biggest = new java.util.ArrayList<>(parts);
        biggest.sort(java.util.Comparator.comparingDouble(SculptCritique::volumeOf).reversed());
        return biggest.subList(0, FLAT_BIGGEST).stream().map(CreatureSculpt.Part::name).toList();
    }

    private static double volumeOf(CreatureSculpt.Part p) {
        return Math.abs((double) (p.to()[0] - p.from()[0]) * (p.to()[1] - p.from()[1])
                * (p.to()[2] - p.from()[2]));
    }

    /**
     * <b>The one colour there is most of</b>, by volume and by exact RGB — what a 16-pixel panel
     * icon can say about a sculpted creature. A sculpted species' own {@code tint} is the
     * white default: its colours live in its parts, so a panel that asked the species would draw
     * every creature the child ever made as the same white egg. Ties go to the first part.
     *
     * @return 0xRRGGBB, or -1 when there are no parts
     */
    public static int dominantColour(List<CreatureSculpt.Part> parts) {
        if (parts == null || parts.isEmpty()) {
            return -1;
        }
        Map<Integer, Double> volume = new LinkedHashMap<>();
        for (CreatureSculpt.Part p : parts) {
            double v = Math.max(1, p.width()) * Math.max(1, p.height()) * Math.max(1, p.depth());
            volume.merge(p.colour() & 0xFFFFFF, v, Double::sum);
        }
        int best = -1;
        double most = -1;
        for (Map.Entry<Integer, Double> e : volume.entrySet()) {
            if (e.getValue() > most) {
                most = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    /** Volume-weighted colour composition, in named buckets, biggest first. */
    public static Map<String, Double> palette(List<CreatureSculpt.Part> parts) {
        Map<String, Double> out = new LinkedHashMap<>();
        composition(parts).entrySet().stream()
                .filter(e -> e.getValue() >= NOTABLE_SHARE)
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /**
     * The same composition with nothing left out. {@link #palette} drops a bucket under
     * {@link #NOTABLE_SHARE} because it is a report a person reads; a CHECK must not, or a colour
     * built at 3.5% is graded as 0% and called vanished (an accent declared at 10% and built
     * at 3.5% was told it had disappeared, and re-sculpted).
     */
    public static Map<String, Double> composition(List<CreatureSculpt.Part> parts) {
        Map<String, Double> acc = new LinkedHashMap<>();
        double total = 0;
        for (CreatureSculpt.Part p : parts) {
            double v = Math.max(1, p.width()) * Math.max(1, p.height()) * Math.max(1, p.depth());
            acc.merge(bucket(p.colour()), v, Double::sum);
            total += v;
        }
        final double t = total == 0 ? 1 : total;
        Map<String, Double> out = new LinkedHashMap<>();
        acc.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> out.put(e.getKey(), e.getValue() / t));
        return out;
    }

    /**
     * Parts that are effectively invisible from every side.
     *
     * <p>Deliberately checks all six directions rather than only the front: a detail hidden behind
     * the head is just as wasted as one hidden inside it, and "you cannot see it from anywhere" is
     * a claim that does not depend on which way the creature happens to be facing.
     */
    public static List<String> buried(List<CreatureSculpt.Part> parts) {
        List<String> out = new ArrayList<>();
        // +1 when the creature's face is at +Z, -1 at -Z, 0 when its anatomy says nothing. With no
        // evidence we keep the old max-over-four behaviour rather than guessing a direction.
        int front = SculptOrientation.frontSign(parts);
        for (CreatureSculpt.Part p : parts) {
            // ONLY THE DIRECTIONS A CHILD LOOKS FROM. Taking the max over all six counted a part
            // visible from BELOW as visible, which is how Chucko's buried face passed: nobody
            // views a mob from underneath. Front and the two sides are what he sees.
            java.util.Map<String, Integer> occluders = new java.util.HashMap<>();
            double best;
            if (isAFeature(p.name()) && front != 0) {
                // ⚠ A FACE IS JUDGED FROM THE FRONT ONLY, and taking the max over four directions
                // was exactly backwards for it: an eye visible only from the LEFT satisfied the
                // rule and is plainly wrong. `best` is the right question for a shell or a tail --
                // seen from anywhere is seen -- and the wrong one for the part whose whole job is
                // to be looked at head-on.
                best = visibleFraction(p, parts, 2, front, occluders);
            } else {
                best = 0;
                best = Math.max(best, visibleFraction(p, parts, 2, -1, occluders));   // front
                best = Math.max(best, visibleFraction(p, parts, 2, 1, occluders));    // back
                best = Math.max(best, visibleFraction(p, parts, 0, -1, occluders));   // left
                best = Math.max(best, visibleFraction(p, parts, 0, 1, occluders));    // right
            }
            double needed = isAFeature(p.name()) ? FACE_MUST_BE_VISIBLE : ANYTHING_ELSE;
            if (best < needed) {
                out.add(p.name() + " (" + p.width() + "x" + p.height() + "x" + p.depth()
                        + ") — only " + Math.round(best * 100) + "% of it can be seen from the "
                        + "front or sides" + coveredBy(occluders)
                        + (isAFeature(p.name())
                                ? ". A face is the one part that HAS to be seen; wrapped in hair "
                                        + "it reads as a blank lump."
                                : ""));
            }
        }
        return out;
    }

    /** A face part nobody can see from the front, and what covers it. */
    public record BuriedFace(String name, java.util.Set<String> occluders) {
    }

    /**
     * The FACE parts {@link #buried} would report, judged from the front, with their occluders
     * named — the structured form, for a caller that fixes them rather than reports them
     * ({@link SculptFaceFront}). Empty when the creature's facing is unknown.
     */
    public static List<BuriedFace> buriedFaces(List<CreatureSculpt.Part> parts) {
        List<BuriedFace> out = new ArrayList<>();
        int front = SculptOrientation.frontSign(parts);
        if (front == 0) {
            return out;
        }
        for (CreatureSculpt.Part p : parts) {
            if (!isAFeature(p.name()) || p.name().equalsIgnoreCase("head")
                    || p.name().toLowerCase(Locale.ROOT).startsWith("head_")) {
                continue;
            }
            java.util.Map<String, Integer> occluders = new java.util.HashMap<>();
            if (visibleFraction(p, parts, 2, front, occluders) < FACE_MUST_BE_VISIBLE) {
                out.add(new BuriedFace(p.name(), new java.util.LinkedHashSet<>(occluders.keySet())));
            }
        }
        return out;
    }

    /** Is this part's whole job to be looked at (an eye, a mouth, a nose)? */
    public static boolean isFaceFeature(String name) {
        return isAFeature(name);
    }

    /**
     * <b>Is {@code o} part of {@code p}?</b> — and if so it cannot bury it.
     *
     * <p>THE FALSE POSITIVE THIS REMOVES IS THE WHOLE REASON THE RULE WAS WRONG. A face is built by
     * stacking smaller features on bigger ones: a pupil sits on an eye, an eye and a snout sit on a
     * head. Counting those as occluders made the sampler report
     *
     * <pre>
     *   head  — 44% ... covered by muzzle, eyeL, eyeR
     *   eyeL  — 47% ... covered by pupilL, browL_in
     * </pre>
     *
     * on <b>correctly built faces</b>, on four of five real creatures. And the damage was not just
     * noise: the remedy said "move them out", so the fix for "your eye is covered by its own pupil"
     * is to push the eye out from under the pupil — which is exactly the bulging-eye look reported
     * from a real creature. A linter whose advice creates the defect is worse than no linter.
     *
     * <p>So an occluder that is the part itself, or hangs off it through {@code parent}, is
     * skipped. Depth-bounded because a malformed parent chain must not hang a live wish.
     */
    static boolean descendsFrom(CreatureSculpt.Part o, CreatureSculpt.Part p,
                                List<CreatureSculpt.Part> all) {
        String want = p.name();
        if (want == null || want.isEmpty()) {
            return false;
        }
        String at = o.parent();
        for (int hops = 0; hops < MAX_PARENT_HOPS && at != null && !at.isEmpty(); hops++) {
            if (want.equals(at)) {
                return true;
            }
            String next = null;
            for (CreatureSculpt.Part q : all) {
                if (at.equals(q.name())) {
                    next = q.parent();
                    break;
                }
            }
            at = next;
        }
        return false;
    }

    /** A creature's bone tree is shallow; this only exists so a cycle cannot spin forever. */
    private static final int MAX_PARENT_HOPS = 16;

    /**
     * <b>The box a part really occupies, rotation included.</b>
     *
     * <p>The sampler used to read {@code from}/{@code to} straight, i.e. the box as if it were
     * never tilted — while <b>53% of the parts on a real Spikor carry a rotation</b>. A tilted
     * spike that genuinely pokes clear of the head was tested as its upright footprint, so the
     * answer was confidently wrong in both directions.
     *
     * <p>This rotates the eight corners about the pivot and returns their axis-aligned bounds. It
     * is an <b>approximation</b> and it errs one way: the AABB of a tilted box is larger than the
     * box, so an occluder can be credited with covering slightly more than it does. That is a
     * smaller error than pretending the rotation is not there, and the direction is stated so the
     * next person does not have to rediscover it. Angles are applied X then Y then Z and are
     * bounded by {@link CreatureSculpt#MAX_ROTATION}.
     */
    static float[] bounds(CreatureSculpt.Part p) {
        float[] f = p.from();
        float[] t = p.to();
        if (!p.rotated()) {
            return new float[]{Math.min(f[0], t[0]), Math.min(f[1], t[1]), Math.min(f[2], t[2]),
                               Math.max(f[0], t[0]), Math.max(f[1], t[1]), Math.max(f[2], t[2])};
        }
        float[] pivot = p.pivot() == null ? new float[]{0, 0, 0} : p.pivot();
        double rx = Math.toRadians(p.rotation()[0]);
        double ry = Math.toRadians(p.rotation()[1]);
        double rz = Math.toRadians(p.rotation()[2]);
        float[] out = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                      -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < 8; i++) {
            double x = ((i & 1) == 0 ? Math.min(f[0], t[0]) : Math.max(f[0], t[0])) - pivot[0];
            double y = ((i & 2) == 0 ? Math.min(f[1], t[1]) : Math.max(f[1], t[1])) - pivot[1];
            double z = ((i & 4) == 0 ? Math.min(f[2], t[2]) : Math.max(f[2], t[2])) - pivot[2];
            double y1 = y * Math.cos(rx) - z * Math.sin(rx);
            double z1 = y * Math.sin(rx) + z * Math.cos(rx);
            double x2 = x * Math.cos(ry) + z1 * Math.sin(ry);
            double z2 = -x * Math.sin(ry) + z1 * Math.cos(ry);
            double x3 = x2 * Math.cos(rz) - y1 * Math.sin(rz);
            double y3 = x2 * Math.sin(rz) + y1 * Math.cos(rz);
            double[] c = {x3 + pivot[0], y3 + pivot[1], z2 + pivot[2]};
            for (int a = 0; a < 3; a++) {
                out[a] = (float) Math.min(out[a], c[a]);
                out[a + 3] = (float) Math.max(out[a + 3], c[a]);
            }
        }
        return out;
    }

    /** The worst offenders, worded so the agent can act: ", covered by hair1, hair2". */
    private static String coveredBy(java.util.Map<String, Integer> occluders) {
        if (occluders.isEmpty()) {
            return "";
        }
        List<String> worst = occluders.entrySet().stream()
                .sorted((x, y) -> Integer.compare(y.getValue(), x.getValue()))
                .limit(MAX_OCCLUDERS_NAMED)
                .map(java.util.Map.Entry::getKey)
                .toList();
        // "and N more" when there are others. Naming the worst three is right -- a list of nine is
        // not actionable -- but a bare three reads as ALL of them, and an agent that moves those
        // three and finds the part still hidden has no reason to look further.
        int rest = occluders.size() - worst.size();
        return ", covered by " + String.join(", ", worst)
                + (rest > 0 ? " and " + rest + " more" : "");
    }

    /**
     * <b>Parts that touch nothing.</b> A creature is one object; a box floating clear of every
     * other box reads as a bug to anyone looking at it — a detached arm, a horn hanging in the air
     * beside the head.
     *
     * <p>Deliberately geometric rather than hierarchical: a part can name a perfectly good
     * {@code parent} and still be nowhere near it, and it is the LOOK that is wrong. Boxes that
     * merely touch count as connected, so a limb sitting flush against a torso is fine.
     *
     * <p>A one-part creature has nothing to be connected to, so it is never flagged.
     */
    public static List<String> floating(List<CreatureSculpt.Part> parts) {
        List<String> out = new ArrayList<>();
        if (parts == null || parts.size() < 2) {
            return out;
        }
        for (CreatureSculpt.Part p : parts) {
            boolean touching = false;
            for (CreatureSculpt.Part o : parts) {
                if (o != p && overlaps(p, o)) {
                    touching = true;
                    break;
                }
            }
            if (!touching) {
                out.add(p.name() + " (" + p.width() + "x" + p.height() + "x" + p.depth()
                        + ") — touches no other part, so it hangs in the air on its own");
            }
        }
        return out;
    }

    /** Do two boxes meet? Touching counts; TOUCH_EPSILON forgives a hair of float drift. */
    private static boolean overlaps(CreatureSculpt.Part a, CreatureSculpt.Part b) {
        for (int axis = 0; axis < 3; axis++) {
            float aLo = Math.min(a.from()[axis], a.to()[axis]) - TOUCH_EPSILON;
            float aHi = Math.max(a.from()[axis], a.to()[axis]) + TOUCH_EPSILON;
            float bLo = Math.min(b.from()[axis], b.to()[axis]);
            float bHi = Math.max(b.from()[axis], b.to()[axis]);
            if (aHi < bLo || bHi < aLo) {
                return false;
            }
        }
        return true;
    }

    /**
     * How much of {@code p}'s face along one axis survives being covered by other parts.
     *
     * <p>Sampled on a coarse grid rather than solved exactly: this runs inside a live wish, and a
     * near-enough answer delivered in time beats a perfect one that costs the child a pause.
     */
    /** Exposed for calibration. */
    public static double visibleFor(CreatureSculpt.Part p, List<CreatureSculpt.Part> all, int axis, int dir) {
        return visibleFraction(p, all, axis, dir);
    }

    private static double visibleFraction(CreatureSculpt.Part p, List<CreatureSculpt.Part> all,
                                          int axis, int dir) {
        return visibleFraction(p, all, axis, dir, null);
    }

    /**
     * As above, but tallying <b>which parts</b> did the blocking into {@code occluders}.
     *
     * <p>Why this exists: the report used to say only <i>"head — only 47% of it can be seen"</i>,
     * which states a problem and no remedy. Measured 2026-08-30, the agent was told exactly that
     * four times on one wish and shipped the creature anyway — it had to infer the occluder from a
     * 59-part list, and it could not. The blocking part is already in hand at the moment the sample
     * is marked blocked, so naming it costs a map increment and turns an observation into an
     * action.
     */
    private static double visibleFraction(CreatureSculpt.Part p, List<CreatureSculpt.Part> all,
                                          int axis, int dir,
                                          java.util.Map<String, Integer> occluders) {
        int a = (axis + 1) % 3;
        int b = (axis + 2) % 3;
        float[] pb = bounds(p);
        float lo0 = pb[a];
        float hi0 = pb[a + 3];
        float lo1 = pb[b];
        float hi1 = pb[b + 3];
        // The outward-facing surface of p along this axis.
        float face = dir < 0 ? pb[axis] : pb[axis + 3];

        int steps = 6;
        int seen = 0;
        int n = 0;
        for (int i = 0; i < steps; i++) {
            for (int j = 0; j < steps; j++) {
                float u = lo0 + (hi0 - lo0) * (i + 0.5f) / steps;
                float v = lo1 + (hi1 - lo1) * (j + 0.5f) / steps;
                n++;
                boolean blocked = false;
                for (CreatureSculpt.Part o : all) {
                    if (o == p || descendsFrom(o, p, all)) {
                        continue;
                    }
                    float[] ob = bounds(o);
                    if (!spans(ob, a, u) || !spans(ob, b, v)) {
                        continue;
                    }
                    // o hides this sample if it reaches further out along the viewing direction
                    // than p's own surface does — i.e. it sits between the viewer and p.
                    float oOut = dir < 0 ? ob[axis] : ob[axis + 3];
                    if (dir < 0 ? oOut < face : oOut > face) {
                        blocked = true;
                        if (occluders != null) {
                            occluders.merge(o.name(), 1, Integer::sum);
                        }
                        break;
                    }
                }
                if (!blocked) {
                    seen++;
                }
            }
        }
        return n == 0 ? 1 : (double) seen / n;
    }

    private static boolean spans(float[] ob, int axis, float at) {
        return at >= ob[axis] && at <= ob[axis + 3];
    }

    private static int heightPixels(List<CreatureSculpt.Part> parts) {
        float lo = Float.MAX_VALUE;
        float hi = -Float.MAX_VALUE;
        for (CreatureSculpt.Part p : parts) {
            lo = Math.min(lo, Math.min(p.from()[1], p.to()[1]));
            hi = Math.max(hi, Math.max(p.from()[1], p.to()[1]));
        }
        return Math.round(hi - lo);
    }

    /** A colour name a person would use, from a packed RGB. */
    public static String bucket(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        float[] hsv = java.awt.Color.RGBtoHSB(r, g, b, null);
        if (hsv[2] < 0.22f) {
            return "black";
        }
        if (hsv[1] < 0.16f) {
            return hsv[2] > 0.75f ? "white" : "grey";
        }
        float h = hsv[0] * 360;
        if (h < 18 || h >= 345) {
            return "red";
        }
        if (h < 45) {
            return "tan/orange";
        }
        if (h < 70) {
            return "yellow";
        }
        if (h < 170) {
            return "green";
        }
        if (h < 200) {
            return "cyan";
        }
        if (h < 260) {
            return "blue";
        }
        return "purple/pink";
    }

    /** Lower-cased helper so the lessons text and the report agree on colour names. */
    public static String describeBuckets(Map<String, Double> palette) {
        return palette.entrySet().stream()
                .map(e -> String.format(Locale.ROOT, "%s %.0f%%", e.getKey(), e.getValue() * 100))
                .reduce((a, b) -> a + ", " + b).orElse("(none)");
    }
}
