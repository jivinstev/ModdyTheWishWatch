package com.moddingfromamod.wishbuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>The name he gives in a typed wish</b>: "a pet wolf called sir fluffy". Deterministic
 * and narrow: "called X", "named X", "whose name is X", "call it X", "name it X", up to the end of the wish or a joining
 * word, 1–3 words, letters only, at most 24 characters. Anything else is not a name, and the wish is read as it was.
 *
 * <p>The name is taken OUT of the words before the Apprentice reads them, so "sir fluffy" is never read as a fluffy
 * pick. What is done with it is the caller's (ApprenticeService): a Wish Magician's make takes it after the input
 * lexicon and the text sweep the agent's own names pass; a Crafter's never does (nothing a Crafter types is kept,
 * docs/OPEN_DOOR_PLAN.md §7.3), and he is told a Wish Magician can. Pure: {@code ApprenticeNameTest}.
 */
public final class ApprenticeName {

    private ApprenticeName() {
    }

    public static final int MOST_WORDS = 3;
    public static final int MOST_CHARS = 24;

    /** A name found, and the words without it (what the Apprentice then reads). */
    public record Named(String name, String rest) {
    }

    /** The cues, as word sequences; the name starts right after one. */
    static final List<List<String>> CUES = List.of(List.of("whose", "name", "is"), List.of("call", "it"),
            List.of("name", "it"), List.of("called"), List.of("named"));
    /** A word that ends a name: the wish goes on ("called rex that breathes fire"). */
    static final Set<String> ENDS = Set.of("with", "that", "and", "but", "which", "so", "plus", "also", "in", "make",
            "made", "from", "who", "it", "to", "please", "pls", "on", "for", "at", "the");

    /** The name in {@code words}, or null. */
    public static Named parse(String words) {
        if (words == null) {
            return null;
        }
        String[] raw = words.strip().split("\\s+");
        List<String> toks = new ArrayList<>();
        for (String t : raw) {
            if (!t.isEmpty()) {
                toks.add(t);
            }
        }
        for (int i = 0; i < toks.size(); i++) {
            for (List<String> cue : CUES) {
                if (i + cue.size() > toks.size() || !matches(toks, i, cue)) {
                    continue;
                }
                int start = i + cue.size();
                int end = start;
                while (end < toks.size() && end - start < MOST_WORDS + 1
                        && !ENDS.contains(clean(toks.get(end)).toLowerCase(Locale.ROOT))) {
                    end++;
                }
                if (end == start || end - start > MOST_WORDS) {
                    return null;   // nothing after the cue, or longer than a name: not a name at all
                }
                List<String> name = new ArrayList<>();
                for (int j = start; j < end; j++) {
                    String w = clean(toks.get(j));
                    if (w.isEmpty() || !w.chars().allMatch(Character::isLetter)) {
                        return null;
                    }
                    name.add(w);
                }
                String joined = String.join(" ", name);
                if (joined.length() > MOST_CHARS) {
                    return null;
                }
                List<String> rest = new ArrayList<>(toks.subList(0, i));
                rest.addAll(toks.subList(end, toks.size()));
                return new Named(title(name), String.join(" ", rest).strip());
            }
        }
        return null;
    }

    private static boolean matches(List<String> toks, int at, List<String> cue) {
        for (int k = 0; k < cue.size(); k++) {
            if (!clean(toks.get(at + k)).toLowerCase(Locale.ROOT).equals(cue.get(k))) {
                return false;
            }
        }
        return true;
    }

    /** A word without the punctuation round it ("fluffy!" → "fluffy", "'rex'" → "rex"). */
    static String clean(String w) {
        return w.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
    }

    /** "sir fluffy" → "Sir Fluffy". */
    static String title(List<String> words) {
        List<String> out = new ArrayList<>();
        for (String w : words) {
            String l = w.toLowerCase(Locale.ROOT);
            out.add(Character.toUpperCase(l.charAt(0)) + l.substring(1));
        }
        return String.join(" ", out);
    }
}
