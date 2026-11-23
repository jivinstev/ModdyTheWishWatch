package com.moddingfromamod.event;

/**
 * <b>A title has to fit across his screen</b> (E6).
 *
 * <p>Minecraft draws a title four times the size of chat, so on a small window only about sixteen
 * capital letters fit, and a longer one runs off both edges — filmed: "IT'S RAINING CHICKENS!" came
 * out as "T'S RAINING CHICKENS". The child's words are never refused for it: the big line keeps the
 * whole words that fit, and the rest moves to the smaller line under it. Pure, so it is a unit test.
 */
public final class TitleFit {

    /** Characters of big title that fit on a small (854-wide) window at the default GUI scale. */
    public static final int FITS = 16;

    private TitleFit() {
    }

    /** {big line, what moved to the line under it (maybe empty)}. */
    public static String[] fit(String title) {
        String t = title == null ? "" : title.trim().replaceAll("\\s+", " ");
        if (t.length() <= FITS) {
            return new String[] {t, ""};
        }
        String[] words = t.split(" ");
        StringBuilder big = new StringBuilder();
        int i = 0;
        while (i < words.length && big.length() + (big.isEmpty() ? 0 : 1) + words[i].length() <= FITS) {
            if (!big.isEmpty()) {
                big.append(' ');
            }
            big.append(words[i++]);
        }
        if (big.isEmpty()) {
            // one word longer than the screen: it cannot be split sensibly, so it stays whole
            return new String[] {words[0], String.join(" ", java.util.Arrays.copyOfRange(words, 1, words.length))};
        }
        return new String[] {big.toString(), String.join(" ", java.util.Arrays.copyOfRange(words, i, words.length))};
    }
}
