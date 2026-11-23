package com.moddingfromamod.selector;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>The positive tags a selector requires</b>, read from its text — pure.
 *
 * <p>Every top-level {@code tag=X} (not {@code tag=!X}, not the empty {@code tag=}, which means
 * "no tags at all") is a condition every match must meet, because selector options are ANDed. So
 * the {@link TagIndex} may answer from the entities carrying any one of them, and vanilla's own
 * predicate still runs over that answer — an extra condition is never dropped, only the walk over
 * entities that cannot match.
 *
 * <p>Read from the text rather than by hooking vanilla's option parser, because that lives in a
 * numbered synthetic lambda ({@code EntitySelectorOptions.lambda$bootStrap$49} on 1.21.8) that a
 * Minecraft update renames. Vanilla reads a tag with {@code readUnquotedString}, so a positive tag
 * is a non-empty run of {@code [0-9A-Za-z_\-.+]}; anything else here is "not indexable" and the
 * selector is answered by vanilla, never guessed at. Options inside {@code {}} or {@code []}
 * ({@code nbt=}, {@code scores=}, {@code advancements=}) and inside quotes are skipped, since a
 * {@code tag=} there is not a selector option.
 */
public final class SelectorTags {

    private SelectorTags() {
    }

    /**
     * @param selector the selector's full text, e.g. {@code @e[tag=a,tag=!b,limit=1]}
     * @return its required positive tags, in order; empty when there are none or the text is
     *         anything this does not fully understand
     */
    public static List<String> positive(String selector) {
        if (selector == null) {
            return List.of();
        }
        String s = selector.strip();
        int open = s.indexOf('[');
        if (!s.startsWith("@") || open < 0 || !s.endsWith("]")) {
            return List.of();
        }
        String body = s.substring(open + 1, s.length() - 1);
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        char quote = 0;
        int start = 0;
        for (int i = 0; i <= body.length(); i++) {
            char c = i < body.length() ? body.charAt(i) : ',';
            if (quoted) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quoted = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quoted = true;
                quote = c;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth < 0) {
                    return List.of();
                }
            } else if (c == ',' && depth == 0) {
                String opt = body.substring(start, i);
                start = i + 1;
                int eq = opt.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                if (!opt.substring(0, eq).strip().equals("tag")) {
                    continue;
                }
                String v = opt.substring(eq + 1).strip();
                if (v.isEmpty() || v.startsWith("!")) {
                    continue;
                }
                if (!unquoted(v)) {
                    return List.of();   // something vanilla would not have read as one tag: do not index
                }
                out.add(v);
            }
        }
        if (quoted || depth != 0) {
            return List.of();
        }
        return List.copyOf(out);
    }

    /** {@code StringReader.isAllowedInUnquotedString}, for every character. */
    static boolean unquoted(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
                    || c == '_' || c == '-' || c == '.' || c == '+';
            if (!ok) {
                return false;
            }
        }
        return !v.isEmpty();
    }
}
