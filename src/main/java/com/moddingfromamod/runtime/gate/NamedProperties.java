package com.moddingfromamod.runtime.gate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptCritique;

/**
 * <b>Did the creature get the things the child actually said?</b>
 *
 * <p>The one grader that needs no reference and no taste. "A spiky orange lizard with
 * <b>three tails</b>" is ambiguous about almost everything — how spiky, which orange, what kind of
 * lizard — and completely unambiguous about the number three. Those stated facts are checkable, and
 * everything unstated is the agent's latitude and is deliberately not scored.
 *
 * <p>This matters more than the reference-based scores, for two reasons found by measurement rather
 * than argument. It applies to <b>every</b> creature, including the invented ones that make up most
 * of the corpus and have no reference at all. And it is the only creature grader that has never had
 * to be retracted — both reference-based findings in this project turned out to rest on numbers that
 * were invented rather than sourced.
 *
 * <p>Pure and Minecraft-free, so it is unit-tested without a game.
 */
public final class NamedProperties {

    private NamedProperties() {
    }

    /** Number words a seven-year-old uses, plus digits. */
    private static final Map<String, Integer> NUMBERS = Map.ofEntries(
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
            Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8),
            Map.entry("1", 1), Map.entry("2", 2), Map.entry("3", 3), Map.entry("4", 4),
            Map.entry("5", 5), Map.entry("6", 6), Map.entry("7", 7), Map.entry("8", 8));

    /** Body parts a child names and counts. Singular keys; plurals handled by prefix match. */
    private static final List<String> COUNTABLE = List.of(
            "tail", "wing", "eye", "head", "arm", "leg", "horn", "ear", "claw", "tooth", "tusk",
            "spike", "finger", "antenna");

    /** Colour words mapped to the buckets {@link SculptCritique} reports. */
    private static final Map<String, String> COLOURS = Map.ofEntries(
            Map.entry("red", "red"), Map.entry("green", "green"), Map.entry("blue", "blue"),
            Map.entry("yellow", "yellow"), Map.entry("orange", "tan/orange"),
            Map.entry("purple", "purple/pink"), Map.entry("pink", "purple/pink"),
            Map.entry("white", "white"), Map.entry("black", "black"), Map.entry("grey", "grey"),
            Map.entry("gray", "grey"), Map.entry("tan", "tan/orange"),
            Map.entry("brown", "tan/orange"), Map.entry("cyan", "cyan"));

    /** One stated fact, and whether the creature has it. */
    public record Claim(String stated, boolean satisfied, String detail) {
    }

    /** The whole verdict for one creature. */
    public record Report(List<Claim> claims) {

        public int satisfied() {
            return (int) claims.stream().filter(Claim::satisfied).count();
        }

        public int total() {
            return claims.size();
        }

        /** Nothing checkable was said — a legitimate outcome, not a pass and not a failure. */
        public boolean isEmpty() {
            return claims.isEmpty();
        }

        public List<String> unmet() {
            return claims.stream().filter(c -> !c.satisfied())
                    .map(c -> c.stated() + " — " + c.detail()).toList();
        }

        public String describe() {
            if (isEmpty()) {
                return "the wish stated nothing countable or coloured, so there is nothing to check "
                        + "against — judge this one by eye";
            }
            StringBuilder sb = new StringBuilder(satisfied() + "/" + total()
                    + " of the things the child actually said:\n");
            for (Claim c : claims) {
                sb.append("    ").append(c.satisfied() ? "OK  " : "MISS").append(' ')
                  .append(c.stated()).append(" — ").append(c.detail()).append('\n');
            }
            return sb.toString();
        }
    }

    /**
     * Grade a sculpt against the wish that asked for it.
     *
     * <p>Only three kinds of claim are extracted, because only these three are unambiguous enough to
     * score: a <b>counted body part</b> ("three tails"), a <b>colour</b> ("purple"), and a
     * <b>size</b> ("big", "tiny"). Adjectives like "spiky" or "fluffy" are deliberately skipped —
     * there is no honest threshold for spikiness, and inventing one is how the reference-based
     * scores went wrong.
     */
    public static Report grade(String wish, List<CreatureSculpt.Part> parts) {
        return grade(wish, parts, 1.0);
    }

    /**
     * @param scale the species' size multiplier — <b>load-bearing</b>. Ignoring it reported two
     *              perfectly good baby creatures as "not small": a 26-pixel model at scale 0.6 is
     *              under a block tall, and the grader was reading the 26 and calling it big.
     */
    public static Report grade(String wish, List<CreatureSculpt.Part> parts, double scale) {
        List<Claim> claims = new ArrayList<>();
        if (wish == null || parts == null || parts.isEmpty()) {
            return new Report(List.of());
        }
        String text = wish.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ");
        String[] words = text.split("\\s+");
        List<String> names = parts.stream()
                .map(p -> p.name().toLowerCase(Locale.ROOT)).toList();

        // ── counted body parts: "three tails", "4 wings", "one big eye"
        for (int i = 0; i < words.length; i++) {
            Integer n = NUMBERS.get(words[i]);
            if (n == null) {
                continue;
            }
            // the noun may be a word or two later ("one BIG eye")
            for (int j = i + 1; j < Math.min(i + 4, words.length); j++) {
                String noun = singular(words[j]);
                if (!COUNTABLE.contains(noun)) {
                    continue;
                }
                List<String> matching = names.stream().filter(nm -> nm.contains(noun)).toList();
                long have = matching.size();
                long groups = matching.stream().map(NamedProperties::segmentBase).distinct().count();
                // "three tails" is NOT "at least three parts with tail in the name". A real sculpt
                // built tailC1/tailC2/tailCtip + tailL* + tailR* — three tails of three segments,
                // exactly right — and `have >= n` passed it for the wrong reason: two tails of
                // three segments would also have passed. Either the parts count matches, or the
                // SEGMENT GROUPS do.
                boolean ok = have == n || groups == n;
                claims.add(new Claim(n + " " + noun + (n > 1 ? "s" : ""), ok,
                        have == groups
                                ? "the sculpt has " + have + " '" + noun + "' part(s)"
                                : "the sculpt has " + have + " '" + noun + "' part(s) in "
                                        + groups + " group(s)"));
                break;
            }
        }

        // ── colours: present as a real share of the body, not a speck
        Map<String, Double> palette = SculptCritique.palette(parts);
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (String w : words) {
            String bucket = COLOURS.get(w);
            if (bucket == null || seen.containsKey(w)) {
                continue;
            }
            seen.put(w, true);
            double share = palette.getOrDefault(bucket, 0.0);
            claims.add(new Claim("it is " + w, share >= 0.15,
                    String.format("%s is %.0f%% of the body", bucket, share * 100)));
        }

        // ── size: only the unmistakable words, and only against the pixel height
        int height = (int) Math.round(height(parts) * (scale <= 0 ? 1.0 : scale));
        if (containsAny(words, "giant", "ginormous", "ginormus", "huge", "enormous")) {
            claims.add(new Claim("it is huge", height >= 32,
                    height + " pixels tall (32 = two blocks)"));
        } else if (containsAny(words, "big", "large")) {
            claims.add(new Claim("it is big", height >= 24,
                    height + " pixels tall (16 = one block)"));
        }
        if (containsAny(words, "tiny", "teeny", "little", "baby", "small")) {
            claims.add(new Claim("it is small", height <= 20,
                    height + " pixels tall (16 = one block)"));
        }
        return new Report(List.copyOf(claims));
    }

    private static boolean containsAny(String[] words, String... any) {
        for (String w : words) {
            for (String a : any) {
                if (w.equals(a)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Strip a segment index off a part name, so {@code tailC1}, {@code tailC2} and
     * {@code tailCtip} all collapse to one tail.
     */
    private static String segmentBase(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[_\\-]", "");
        s = s.replaceAll("(tip|base|mid|end|top|bot|bottom)$", "");
        s = s.replaceAll("\\d+$", "");
        s = s.replaceAll("(tip|base|mid|end|top|bot|bottom)$", "");
        return s;
    }

    private static String singular(String w) {
        if (w.equals("teeth")) {
            return "tooth";
        }
        if (w.endsWith("s") && w.length() > 3) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    private static int height(List<CreatureSculpt.Part> parts) {
        float lo = Float.MAX_VALUE;
        float hi = -Float.MAX_VALUE;
        for (CreatureSculpt.Part p : parts) {
            lo = Math.min(lo, Math.min(p.from()[1], p.to()[1]));
            hi = Math.max(hi, Math.max(p.from()[1], p.to()[1]));
        }
        return Math.round(hi - lo);
    }
}
