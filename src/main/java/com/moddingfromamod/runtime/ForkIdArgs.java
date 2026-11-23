package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the arguments of a replay command whose first word is an artifact id that may be a FORK
 *. A child's copy of somebody else's creature, building or picture is {@code <id>~<his
 * short uuid>} ({@code ArtifactOwner.FORK}), and brigadier's {@code StringArgumentType.word()}
 * refuses {@code ~} — so {@code wishmob spawn dragon~1a2b3c4d}, the stored form of the button his
 * wish taught, failed to parse and the button did nothing. fixed {@code wishbuild} by taking
 * the rest of the line; a command with more after the id cannot do that and still read its tail,
 * so the command takes the rest of the line ({@code greedyString}) and this splits it.
 *
 * <p>Pure, so the grammar is unit-tested ({@code ForkIdArgsTest}); the command only executes the
 * result. A word is any run of non-whitespace — exactly what {@code word()} would have read, plus
 * {@code ~} and anything else a store spells an id with.
 */
public final class ForkIdArgs {

    private ForkIdArgs() {
    }

    private static final Pattern WS = Pattern.compile("\\s+");

    /** The words of {@code rest}, split on whitespace; none for a blank line. */
    public static List<String> words(String rest) {
        String t = rest == null ? "" : rest.trim();
        return t.isEmpty() ? List.of() : List.of(WS.split(t));
    }

    /** One id and nothing after it; {@code error} is non-null when that is not what was typed. */
    public record One(String id, String error) {
    }

    public static One one(String rest, String what) {
        List<String> w = words(rest);
        if (w.isEmpty()) {
            return new One("", "Which " + what + "?");
        }
        if (w.size() > 1) {
            return new One("", "Expected one " + what + " id, got '" + rest.trim() + "'.");
        }
        return new One(w.get(0), null);
    }

    /** The first word, and the line after it (trimmed) — for {@code paint <key> <picture json>}. */
    public record Head(String id, String tail) {
    }

    public static Head head(String rest) {
        String t = rest == null ? "" : rest.trim();
        Matcher m = WS.matcher(t);
        if (!m.find()) {
            return new Head(t, "");
        }
        return new Head(t.substring(0, m.start()), t.substring(m.end()).trim());
    }

    /**
     * {@code <key> [count] [at <build>:<spot>]} — {@code wishmob spawn}'s grammar.
     * {@code at} is {@code ""} when absent, as the command has always passed it.
     */
    public record Spawn(String key, int count, String at, String error) {
    }

    private static final Pattern WORD = Pattern.compile("\\S+");

    public static Spawn spawn(String rest, int maxCount) {
        String t = rest == null ? "" : rest.trim();
        List<String> w = words(t);
        if (w.isEmpty()) {
            return new Spawn("", 0, "", "Which creature?");
        }
        String key = w.get(0);
        int count = 1;
        int i = 1;
        if (i < w.size() && w.get(i).matches("-?\\d+")) {
            long n;
            try {
                n = Long.parseLong(w.get(i));
            } catch (NumberFormatException e) {
                n = Long.MAX_VALUE;
            }
            if (n < 1 || n > maxCount) {
                return new Spawn(key, 0, "", "How many must be 1 to " + maxCount + ", not "
                        + w.get(i) + ".");
            }
            count = (int) n;
            i++;
        }
        if (i == w.size()) {
            return new Spawn(key, count, "", null);
        }
        if (!w.get(i).equals("at")) {
            return new Spawn(key, count, "", "Expected a number or 'at <building>:<spot>' after '"
                    + key + "', got '" + w.get(i) + "'.");
        }
        // Everything after the `at` word (word i, not an `at` inside the key), as the old greedy
        // `spot` argument read it.
        int from = -1;
        int words = 0;
        Matcher wm = WORD.matcher(t);
        while (wm.find()) {
            if (words == i) {
                from = wm.end();
                break;
            }
            words++;
        }
        String at = from < 0 ? "" : t.substring(from).trim();
        if (at.isEmpty()) {
            return new Spawn(key, count, "", "Where? 'at <building>:<spot>'.");
        }
        return new Spawn(key, count, at, null);
    }

    /** {@code <key> <part> <colour> [tint|fill]} — {@code wishmob paint}'s grammar. */
    public record Paint(String key, String part, String colour, String error) {
    }

    public static Paint paint(String rest) {
        List<String> w = words(rest);
        if (w.size() < 3) {
            return new Paint("", "", "", "Usage: wishmob paint <creature> <part> <colour>.");
        }
        if (w.size() > 4 || (w.size() == 4 && !w.get(3).equals("tint") && !w.get(3).equals("fill"))) {
            return new Paint("", "", "", "Unexpected '" + String.join(" ", w.subList(3, w.size()))
                    + "' after the colour.");
        }
        return new Paint(w.get(0), w.get(1), w.get(2), null);
    }

    /**
     * Which word of a half-typed line the cursor is in, and where that word starts — so a greedy
     * argument can still suggest per word. {@code index} counts from 0; a trailing space means
     * the cursor has moved on to the next, empty, word.
     */
    public record Cursor(int index, int start, List<String> before) {
    }

    public static Cursor cursor(String typed) {
        String t = typed == null ? "" : typed;
        List<String> before = new ArrayList<>();
        Matcher wm = WORD.matcher(t);
        int lastStart = 0;
        int lastEnd = -1;
        while (wm.find()) {
            before.add(wm.group());
            lastStart = wm.start();
            lastEnd = wm.end();
        }
        if (before.isEmpty()) {
            return new Cursor(0, t.length(), List.of());
        }
        if (lastEnd < t.length()) {
            // a space after the last word: a new, empty word starts at the end
            return new Cursor(before.size(), t.length(), List.copyOf(before));
        }
        before.remove(before.size() - 1);   // the word being typed
        return new Cursor(before.size(), lastStart, List.copyOf(before));
    }
}
