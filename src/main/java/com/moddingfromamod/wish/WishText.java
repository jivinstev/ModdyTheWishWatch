package com.moddingfromamod.wish;

import java.text.Normalizer;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cleaning and reading the raw text of a wish. Pure (no Minecraft), so every nasty input case is
 * unit-testable in milliseconds.
 *
 * <p>The text arrives from a GUI text box typed by a 7-year-old and is later handed to a command
 * builder and, eventually, to an AI agent — so it is <b>untrusted</b>. Everything here is about
 * making it boring and bounded before anything else touches it.
 */
public final class WishText {

    /** Matches the client-side EditBox cap; re-applied server-side because clients can lie. */
    public static final int MAX_LENGTH = 256;

    /**
     * How long a seeded follow-up suggestion may be.
     *
     * <p>Much shorter than {@link #MAX_LENGTH} on purpose, and for pixels rather than safety: the
     * wish box is 260px wide, which shows roughly 50 characters. A suggestion the child has to
     * scroll to read is one he won't read — and the entire point of seeding it is that he takes it
     * in at a glance and presses Wish.
     */
    public static final int SUGGESTION_MAX = 50;

    /**
     * <b>How much a wish typed on the SHIPPED route can be: the wish box's own cap</b> (D2).
     *
     * <p>The watch's "Wish for something new" opens a vanilla dialog whose text box carries
     * {@code max_length} = this ({@link WishCompose}). A stock client stops the next keystroke there, and
     * the box's value rides back as {@code /moddy wishbox <words>} — so the cap is what still fits under
     * {@link #MAX_LENGTH} with that prefix in front (15 + 240 = 255). It replaced the borrowed anvil's
     * 50, the stock client's rename cap, which most real typed wishes (50 of 84) were longer than.
     */
    public static final int BOX_MAX = 240;

    /**
     * What a wish WRITTEN DOWN somewhere must fit in, which is two characters less.
     *
     * <p>A wish exactly at the cap that ends on a space loses it to the submit-time trim — measured on
     * the anvil, 57 characters typed and 49 recorded. Anything sitting on the boundary is one
     * word-edit from being silently cut, so a test constant, a corpus prompt or a preset gets a budget
     * rather than the cap.
     */
    public static final int BOX_BUDGET = 238;

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}\\p{Cf}]");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");
    private static final Pattern HAS_LETTER_OR_DIGIT = Pattern.compile("[\\p{L}\\p{N}]");
    private static final Pattern FIRST_NUMBER = Pattern.compile("\\b(\\d{1,6})\\b");

    private WishText() {
    }

    /**
     * Clean a suggested follow-up wish and cut it to something that fits the box.
     *
     * <p>Cuts on a WORD boundary. Chopping mid-word gives a child "make Spikor's fire ev", which
     * reads as broken rather than as an offer — and this text is going to be presented to him as
     * his own next wish.
     */
    public static String trimSuggestion(String raw) {
        String s = sanitize(raw);
        if (s.length() <= SUGGESTION_MAX) {
            return s;
        }
        String cut = s.substring(0, SUGGESTION_MAX);
        int lastSpace = cut.lastIndexOf(' ');
        // Only fall back to a hard cut if the first "word" is itself longer than the whole budget.
        return (lastSpace > SUGGESTION_MAX / 2 ? cut.substring(0, lastSpace) : cut).trim();
    }

    /**
     * Normalise a raw wish into something safe to store, match and display: strip control characters
     * (including newlines — a newline could smuggle a second command into a template), NFKC-normalise
     * so lookalike Unicode can't dodge keyword matching, collapse whitespace runs, trim, and clamp
     * to {@link #MAX_LENGTH}.
     *
     * @return the cleaned text, or empty string if there's nothing meaningful left
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        s = CONTROL_CHARS.matcher(s).replaceAll(" ");
        s = WHITESPACE_RUN.matcher(s).replaceAll(" ").trim();
        if (s.length() > MAX_LENGTH) {
            s = s.substring(0, MAX_LENGTH).trim();
        }
        // All punctuation/emoji with no letters or digits carries no intent — treat as empty.
        if (!HAS_LETTER_OR_DIGIT.matcher(s).find()) {
            return "";
        }
        return s;
    }

    /**
     * What joins a wish to the thing the child re-aimed it at.
     *
     * <p>Readable to the agent on its own, without the note beside it: the wake text shows the wish
     * and nothing else on the redirect path, so the words have to carry the meaning.
     */
    public static final String REAIM_JOINER = " — aimed at: ";

    /**
     * <b>The child's wish, re-aimed</b> — his original words plus what he actually meant
     * ({@code docs/CHILD_SAFETY_PLAN.md} §12.7, decision 7).
     *
     * <p>{@code /moddy redirect <free text>} used to write the new words into the wish's NOTE and
     * leave {@code wish.text()} alone, which meant the dequeue grade — memoised per text and run on
     * {@code wish.text()} — never saw them: the one free text in the system that nothing but the
     * deterministic floor had read. Making the re-aim part of the TEXT closes that for free, because
     * a new string is a new memo key and therefore a new grade, and it closes the derived-text leak
     * with it: everything the agent is handed is now inside the string that was graded.
     *
     * <p><b>Both halves are kept</b>, and that is not politeness. The original is the wish
     * ("make it bigger"); the re-aim is which thing ("the stone tower"). A text holding only the
     * re-aim would be a wish for a tower, which is not what he asked for — and a text holding only
     * the original is what the bug was.
     *
     * <h2>Two properties this has to have, and both are asserted</h2>
     *
     * <ul>
     *   <li><b>Sanitize-stable.</b> {@code sanitize(reaimed(a, b)).equals(reaimed(a, b))}. The
     *       grader sanitises what it is given, so anything this returns that sanitising would
     *       change is a string the agent sees and the grade did not — the leak class again, one
     *       level down. This is why the result is clamped here rather than left for
     *       {@link #MAX_LENGTH} to cut later.</li>
     *   <li><b>The RE-AIM survives, the original is what gets cut.</b> Over the cap, the original
     *       is trimmed on a word boundary and the whole re-aim is kept. Cutting the tail would be
     *       the natural reading of {@code sanitize}, and it would silently drop exactly the words
     *       nothing has graded yet — a safety layer must never be the thing that got truncated.</li>
     * </ul>
     *
     * <p>Re-aiming twice appends twice ("… — aimed at: A — aimed at: B"), which is ugly and honest:
     * the newest aim is last, all of it is graded, and the cap trims from the old end, so the thing
     * he just said is the thing that is kept.
     *
     * @param original  the wish as it stands, or the empty string
     * @param reaimedAt what he says it was aimed at; blank leaves the wish alone
     */
    public static String reaimed(String original, String reaimedAt) {
        String aim = sanitize(reaimedAt);
        String was = sanitize(original);
        if (aim.isEmpty()) {
            return was;
        }
        String tail = REAIM_JOINER + aim;
        int room = MAX_LENGTH - tail.length();
        if (was.isEmpty() || room <= 0) {
            // Nothing to keep, or the re-aim alone fills the budget. Either way the newest words
            // are the ones that survive, which is the rule above rather than an edge case.
            return aim;
        }
        if (was.length() > room) {
            was = cutToWord(was, room);
        }
        return sanitize(was + tail);
    }

    /**
     * Cut to at most {@code max} characters on a word boundary.
     *
     * <p>The {@link #trimSuggestion} rule, for the same reason: "make the stone tow" reads as
     * broken rather than as shortened, and here the agent has to act on it.
     */
    private static String cutToWord(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        String cut = s.substring(0, max);
        int lastSpace = cut.lastIndexOf(' ');
        return (lastSpace > max / 2 ? cut.substring(0, lastSpace) : cut).trim();
    }

    /**
     * <b>Why this wish cannot be typed into the wish box, or null when it can.</b>
     *
     * <p>Pure, so the client-test driver refuses on the same rule the box enforces. Measures the
     * SANITIZED length, because that is what is submitted and what the trim sees.
     */
    public static String tooLongForBox(String raw) {
        String s = sanitize(raw);
        if (s.length() <= BOX_MAX) {
            return null;
        }
        return "this wish is " + s.length() + " characters and the wish box caps at " + BOX_MAX
                + " — a stock client would type only \"" + s.substring(0, BOX_MAX).trim()
                + "\" and the rest would never be sent. Shorten it to " + BOX_BUDGET
                + " or fewer (WishText.BOX_BUDGET).";
    }

    /**
     * <b>The wish box's words, as he typed them.</b> A vanilla dialog puts a text box's value into its
     * command with {@code StringTag.escapeWithoutQuotes}: {@code "}, {@code '} and {@code \} get a
     * backslash and a control character (a newline from Enter in the several-line box) becomes
     * {@code \n}, {@code \t}, {@code \xHH}… So "Spikor's castle" arrives as {@code Spikor\'s castle}.
     * This undoes it, turning every control character into a space ({@link #sanitize} would anyway).
     * Text with no backslash — Geyser's form, a typed command — comes back unchanged.
     */
    public static String fromDialog(String raw) {
        if (raw == null || raw.indexOf('\\') < 0) {
            return raw == null ? "" : raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) {
                out.append(c);
                continue;
            }
            char n = raw.charAt(++i);
            switch (n) {
                case '"', '\'', '\\' -> out.append(n);
                case 'n', 't', 'r', 'b', 'f' -> out.append(' ');
                case 'x' -> {
                    if (i + 2 < raw.length() && raw.substring(i + 1, i + 3).matches("[0-9A-Fa-f]{2}")) {
                        i += 2;
                        out.append(' ');
                    } else {
                        out.append('\\').append(n);
                    }
                }
                default -> out.append('\\').append(n);
            }
        }
        return out.toString();
    }

    /** True when a sanitized wish has any usable content. */
    public static boolean isUsable(String sanitized) {
        return sanitized != null && !sanitized.isEmpty();
    }

    /**
     * Key used to spot the same wish being submitted repeatedly (button mashing). Case- and
     * punctuation-insensitive so "MAKE IT RAIN CHICKENS!!!" and "make it rain chickens" collapse.
     */
    public static String dedupeKey(String sanitized) {
        if (sanitized == null) {
            return "";
        }
        String k = sanitized.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", "");
        return WHITESPACE_RUN.matcher(k).replaceAll(" ").trim();
    }

    /**
     * The first plain number in the wish ("spawn 20 zombies" → 20), used to fill a template's
     * {@code {count}}. Absent when the kid didn't ask for a specific number.
     */
    public static OptionalInt firstNumber(String sanitized) {
        if (sanitized == null) {
            return OptionalInt.empty();
        }
        Matcher m = FIRST_NUMBER.matcher(sanitized);
        if (!m.find()) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(m.group(1)));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    /** Lower-cased form used for keyword matching. */
    public static String matchable(String sanitized) {
        return sanitized == null ? "" : sanitized.toLowerCase(Locale.ROOT);
    }
}
