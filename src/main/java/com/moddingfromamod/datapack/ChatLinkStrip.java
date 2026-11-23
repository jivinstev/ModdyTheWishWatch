package com.moddingfromamod.datapack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes every clickable link out of a datapack's source and keeps its words. Pure.
 *
 * <p>A {@code clickEvent} / {@code click_event} in a {@code tellraw}, a title, a sign or a book runs AS THE
 * PLAYER WHO CLICKS IT, and a child is permission 0 ({@code WishChatGameTest} pins that he has only
 * {@code /wish} and {@code /moddy}). Every link the agent ever wrote ran {@code /function} or
 * {@code /summon}, which need permission 2, so the link was dead for him on Java and a pop-up button that
 * fails the same way on a Bedrock phone. The owner (2026-11-10/11): no wish behaviour that does not
 * function. So the click goes and the text stays; a label that only made sense as a link
 * ({@code "[Click for Fireballs]"}) becomes its words ({@code "Fireballs"}).
 *
 * <p>Here, on the source, because every compile passes {@link CbScriptCompiler#compile} (the wish path,
 * the item catalogue, the effect templates and every replayed template), so old packs and other phrasings
 * are covered as well as what the lesson now teaches. Both JSON ({@code "clickEvent":{…}}) and the
 * 1.21.5+ SNBT spelling ({@code click_event:{…}}) are matched, the value is skipped with string-aware
 * brace matching (a {@code /summon} inside it may carry NBT braces), and one neighbouring comma goes with
 * it so the component stays well-formed.
 */
public final class ChatLinkStrip {

    private ChatLinkStrip() {}

    /** The stripped source and how many links were taken out. */
    public record Result(String source, int removed) {}

    private static final Pattern KEY = Pattern.compile("\"?(?:clickEvent|click_event)\"?\\s*:\\s*");
    /** A label that only made sense as a link: "[Click for X]", "[Click to X]", "[Click here for X]", "Click here to X". */
    private static final Pattern LABEL = Pattern.compile(
            "\\[?\\s*[Cc]lick(?:\\s+here)?\\s+(?:for|to)\\s+([^\\]\"]+?)\\s*\\]?(?=\")");

    public static Result strip(String source) {
        if (source == null || source.isEmpty()) return new Result(source, 0);
        String[] lines = source.split("\n", -1);   // -1 keeps a trailing newline exact
        int removed = 0;
        for (int i = 0; i < lines.length; i++) {
            Result r = stripLine(lines[i]);
            lines[i] = r.source();
            removed += r.removed();
        }
        return removed == 0 ? new Result(source, 0) : new Result(String.join("\n", lines), removed);
    }

    private static Result stripLine(String line) {
        int removed = 0;
        String s = line;
        Matcher m = KEY.matcher(s);
        while (m.find()) {
            int keyStart = m.start();
            int valueStart = m.end();
            int valueEnd = valueEnd(s, valueStart);
            if (valueEnd < 0) break;   // unbalanced: leave the line alone rather than guess
            int from = keyStart;
            int to = valueEnd;
            // take one comma with it: the one before the key, else the one after the value
            int before = skipBack(s, keyStart - 1);
            if (before >= 0 && s.charAt(before) == ',') {
                from = before;
            } else {
                int after = skipFwd(s, valueEnd);
                if (after < s.length() && s.charAt(after) == ',') to = after + 1;
            }
            s = s.substring(0, from) + s.substring(to);
            removed++;
            m = KEY.matcher(s);
        }
        if (removed > 0) s = LABEL.matcher(s).replaceAll(r -> Matcher.quoteReplacement(capital(r.group(1))));
        return new Result(s, removed);
    }

    /** The index just past the JSON/SNBT value starting at {@code i}, or -1 if it does not close on this line. */
    private static int valueEnd(String s, int i) {
        if (i >= s.length()) return -1;
        char c = s.charAt(i);
        if (c != '{' && c != '[') {   // a scalar: up to the next , } or ]
            int j = i;
            boolean inStr = false;
            while (j < s.length()) {
                char d = s.charAt(j);
                if (inStr) { if (d == '\\') j++; else if (d == '"') inStr = false; }
                else if (d == '"') inStr = true;
                else if (d == ',' || d == '}' || d == ']') return j;
                j++;
            }
            return -1;
        }
        int depth = 0;
        boolean inStr = false;
        char quote = 0;
        for (int j = i; j < s.length(); j++) {
            char d = s.charAt(j);
            if (inStr) {
                if (d == '\\') j++;
                else if (d == quote) inStr = false;
            } else if (d == '"' || d == '\'') {
                inStr = true;
                quote = d;
            } else if (d == '{' || d == '[') {
                depth++;
            } else if (d == '}' || d == ']') {
                if (--depth == 0) return j + 1;
            }
        }
        return -1;
    }

    private static int skipBack(String s, int i) {
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) i--;
        return i;
    }

    private static int skipFwd(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static String capital(String w) {
        String t = w.strip();
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }
}
