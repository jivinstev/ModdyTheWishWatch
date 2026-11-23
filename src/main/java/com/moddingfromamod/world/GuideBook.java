package com.moddingfromamod.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>The guide book's pages, checked and filled</b> — pure, so a page that would run off
 * the bottom of a book page is a Gate A failure ({@code GuideBookTest}) rather than something a child
 * finds by turning to it.
 *
 * <p><b>How big a page is.</b> The codec allows {@code WrittenBookContent.PAGE_LENGTH} (32767)
 * characters, but a book page SHOWS 14 lines of a 114-pixel-wide column (vanilla
 * {@code BookViewScreen}); the default font's letters are 6 pixels with their gap and the narrow ones
 * less, so {@link #WIDTH} = 19 characters a line is the conservative estimate — a page that fits here
 * fits on screen. Anything past line 14 is not shown at all, which is why this is a check and not a
 * style rule.
 *
 * <p><b>Numbers come from the rules in force.</b> A page that says how the wish bank works names no
 * number of its own: it says {@code {bank_start}}, {@code {bank_refill_minutes}}, {@code {bank_cap}} and
 * {@code {bank_price}}, and {@link #fill} puts in what {@code isle.rules.json} says when the book is
 * given — so a rules edit cannot make the book wrong (the same rule the intro keeps).
 */
public final class GuideBook {

    /** The lines a book page shows. */
    public static final int LINES = 14;
    /** A conservative estimate of the characters on one line of a book page. */
    public static final int WIDTH = 19;
    /** {@code WrittenBookContent.TITLE_MAX_LENGTH}: the longest title a written book carries. */
    public static final int TITLE_MAX = 32;
    /** Pages a guide may have — a guide a child reads, not a manual. */
    public static final int MAX_PAGES = 16;

    private GuideBook() {
    }

    /**
     * {@code page} as the lines a book would show: {@code \n} breaks a line, words wrap at
     * {@link #WIDTH}, a word longer than a line is cut. Pure.
     */
    public static List<String> wrap(String page) {
        List<String> out = new ArrayList<>();
        for (String para : (page == null ? "" : page).split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                if (word.isEmpty()) {
                    continue;
                }
                while (word.length() > WIDTH) {
                    if (line.length() > 0) {
                        out.add(line.toString());
                        line.setLength(0);
                    }
                    out.add(word.substring(0, WIDTH));
                    word = word.substring(WIDTH);
                }
                if (line.length() > 0 && line.length() + 1 + word.length() > WIDTH) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                if (line.length() > 0) {
                    line.append(' ');
                }
                line.append(word);
            }
            out.add(line.toString());
        }
        return out;
    }

    /** Every reason this book would not read well, as sentences. Empty = sound. Pure. */
    public static List<String> problems(WorldGuide.Book book) {
        List<String> out = new ArrayList<>();
        if (book == null || book.pages().isEmpty()) {
            return out;
        }
        if (book.title().isEmpty() || book.title().length() > TITLE_MAX) {
            out.add("the title is 1–" + TITLE_MAX + " characters: '" + (book == null ? "" : book.title()) + "'");
        }
        if (book.author().isEmpty()) {
            out.add("the book names no author");
        }
        if (book.pages().size() > MAX_PAGES) {
            out.add("the book has " + book.pages().size() + " pages; at most " + MAX_PAGES);
        }
        for (int i = 0; i < book.pages().size(); i++) {
            String p = book.pages().get(i);
            if (p.isBlank()) {
                out.add("page " + (i + 1) + " is empty");
                continue;
            }
            int lines = wrap(p).size();
            if (lines > LINES) {
                out.add("page " + (i + 1) + " is " + lines + " lines; a book page shows " + LINES);
            }
        }
        return out;
    }

    /** {@code text} with each {@code {key}} replaced by its value; unknown keys are left as they are. Pure. */
    public static String fill(String text, Map<String, String> values) {
        String out = text == null ? "" : text;
        if (values != null) {
            for (Map.Entry<String, String> e : values.entrySet()) {
                out = out.replace("{" + e.getKey() + "}", e.getValue());
            }
        }
        return out;
    }

    /** True when a placeholder survived {@link #fill} — a page that would show "{bank_cap}" to a child. */
    public static boolean unfilled(String text) {
        return text != null && text.matches("(?s).*\\{[a-z_]+}.*");
    }

    /**
     * The placeholder values for a wish bank of these numbers ({@code WishBank.Params}), or an empty
     * map for a world with no bank. Pure.
     */
    public static Map<String, String> bankValues(boolean bank, int start, int refillMinutes, int cap, int price) {
        if (!bank) {
            return Map.of();
        }
        return Map.of("bank_start", Integer.toString(start),
                "bank_refill_minutes", Integer.toString(refillMinutes),
                "bank_cap", Integer.toString(cap),
                "bank_price", Integer.toString(price));
    }

    /** The pages as given: filled, and any page that would still show a placeholder dropped. Pure. */
    public static List<String> pages(WorldGuide.Book book, Map<String, String> values) {
        List<String> out = new ArrayList<>();
        for (String p : book == null ? List.<String>of() : book.pages()) {
            String f = fill(p, values);
            if (!unfilled(f) && !f.isBlank()) {
                out.add(f);
            }
        }
        return out;
    }
}
