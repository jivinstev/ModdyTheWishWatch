package com.moddingfromamod.marshal;

import java.util.Locale;

/**
 * How a seven-year-old actually types a name, normalised — and how far off it may be before we stop
 * guessing.
 *
 * <p><b>"Spikro" is not a semantics problem.</b> It is edit distance 2 from "Spikor", which is a
 * string question, answerable in Java in microseconds with a unit test per case. A mod repo's
 * {@code /food} already does exactly this kind of normalisation — "hot dog", "hot_dog", "hotdog" and
 * "Hot Dog" all resolve — and the same treatment belongs at rung 0 of the resolution ladder.
 *
 * <p>Doing it here also matters for cost: the warm resolver above only ever sees the genuinely
 * semantic cases, so its traffic stays small.
 */
public final class KidTyping {

    private KidTyping() {
    }

    /**
     * Lowercase, letters and digits only, single-spaced. {@code "King Kroak's!"} → {@code "king
     * kroaks"}.
     *
     * <p>An apostrophe is <b>dropped</b> rather than treated as a break. Splitting on it leaves a
     * stray one-letter word — "king kroak s" — which then fails to match "king kroaks" and, worse,
     * silently changes the word count every distance calculation depends on. Possessives are how a
     * child writes a name he owns, so this is the common case, not an edge one.
     */
    public static String normalise(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        s = plain(s);
        boolean space = false;
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '\'' || c == '\u2019') {
                continue;   // "kroak's" is one word
            }
            if (Character.isLetterOrDigit(c)) {
                if (space && sb.length() > 0) {
                    sb.append(' ');
                }
                space = false;
                sb.append(c);
            } else {
                space = true;
            }
        }
        return sb.toString();
    }

    /**
     * A display name with its Minecraft formatting codes removed.
     *
     * <p><b>A colour code is TWO characters, and dropping only the section sign is worse than
     * dropping neither.</b> {@code normalise} keeps letters and digits, so a bare {@code §} falls
     * away on its own and the code's digit survives GLUED TO THE NAME: an agent that names its boss
     * {@code "§6SPIKOR, King of the Kroaks"} — one really did — is filed under {@code "6spikor king
     * of the kroaks"}, and the child typing "spikor" is then matching against a word that starts
     * with a digit. It survives on luck: one edit for {@code §6}, two for {@code §l§6}, and the
     * ladder's NAMED rung stops finding his own creature by its own name.
     *
     * <p>{@link com.moddingfromamod.entity.WishMobTags} learned this first and fixed it locally.
     * The lesson belongs where every name is compared, which is here.
     */
    public static String plain(String s) {
        return s == null ? "" : s.replaceAll("\u00a7.", "");
    }

    /** The same thing with the spaces removed too, so "bigbun" matches "big bun". */
    public static String squash(String s) {
        return normalise(s).replace(" ", "");
    }

    /**
     * How many single-character edits separate two strings, giving up once past {@code max}.
     *
     * <p>Bounded on purpose: an unbounded distance over a long wish sentence is both slow and
     * useless, since anything that far away is not a misspelling.
     *
     * <p><b>Transpositions cost ONE edit, not two</b> (Damerau, not plain Levenshtein). This is the
     * difference between working and not working on the exact cases the design names: {@code Spikro}
     * and {@code Sipkor} are both swapped-letter typos, which plain Levenshtein scores as 2 — over
     * budget for a six-letter name, so both would have failed to resolve. Swapping two letters is
     * the single most common way a child mistypes a name he can spell perfectly well out loud.
     */
    public static int distance(String a, String b, int max) {
        if (a == null || b == null) {
            return max + 1;
        }
        if (Math.abs(a.length() - b.length()) > max) {
            return max + 1;
        }
        int n = a.length();
        int m = b.length();
        int[] prevPrev = new int[m + 1];
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            int best = cur[0];
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                if (i > 1 && j > 1
                        && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, prevPrev[j - 2] + 1);   // the two letters were swapped
                }
                cur[j] = v;
                best = Math.min(best, v);
            }
            if (best > max) {
                return max + 1;   // this row is already too far; nothing below it can recover
            }
            int[] swap = prevPrev;
            prevPrev = prev;
            prev = cur;
            cur = swap;
        }
        return prev[m];
    }

    /**
     * How wrong a name may be and still count as the same name.
     *
     * <p>Scaled to length, because a fixed budget is wrong at both ends: two edits on a four-letter
     * word is a different word, and one edit on "tyrannosaurus" is obviously the same one. Capped, so
     * a long name never becomes a wildcard.
     */
    public static int budgetFor(String name) {
        int n = name == null ? 0 : name.length();
        if (n <= 3) {
            return 0;    // "cat" and "bat" are different animals
        }
        if (n <= 6) {
            return 1;
        }
        return Math.min(3, 1 + n / 6);
    }

    /** True when {@code typed} is close enough to {@code name} to be the same name. */
    public static boolean nearlySame(String typed, String name) {
        String a = squash(typed);
        String b = squash(name);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        int budget = budgetFor(b);
        return budget > 0 && distance(a, b, budget) <= budget;
    }
}
