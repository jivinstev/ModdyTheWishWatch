package com.moddingfromamod.wish;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>{@code /attribute} changes ONE entity</b>: its target argument is a single-entity selector, so
 * {@code attribute @e[type=zombie] minecraft:follow_range base set 0} is refused by the parser —
 * "Only one entity is allowed, but the provided selector allows more than one". In a datapack that
 * refusal happens AT PACK LOAD and takes the whole function with it: the pack reports loaded and
 * the rule (a "zombies are friendly by day" clock, in the archived run that found this) never
 * exists. Straight from {@code run_command} it is an error the agent has to read and fix by hand.
 *
 * <p>CORRECTED, NOT REFUSED, for {@link SoundIds}' reason: the many-entity form is never valid,
 * what the author meant is not in doubt, and the rewrite is mechanical —
 * {@code execute as <selector> run attribute @s …}. A selector that is already one entity
 * ({@code @s}, {@code @p}, {@code @r}, {@code @n}, a name, anything with {@code limit=1}) is left
 * byte for byte. Only an {@code attribute} at a command's head is touched: the start of a line
 * (optionally after {@code /} or a CBScript indent) or right after an {@code execute … run}.
 * A macro ({@code $(…)}) selector is left alone. Pure: no Minecraft.
 */
public final class AttributeSelectors {

    private AttributeSelectors() {
    }

    /** {@code attribute} where a command starts: a line's head (after any indent, {@code $} or {@code /}), or after {@code run}. */
    private static final Pattern HEAD = Pattern.compile(
            "(?im)(^[ \\t]*\\$?/?|\\brun[ \\t]+)attribute[ \\t]+(?=@[aeprsn])");

    private static final Pattern LIMIT = Pattern.compile("(?i)\\blimit\\s*=\\s*(\\d+)");

    /** The text with every many-entity {@code attribute} rewritten; each rewrite is added to {@code notes}. */
    public static String correct(String text, List<String> notes) {
        if (text == null || !text.toLowerCase(Locale.ROOT).contains("attribute")) {
            return text;
        }
        Matcher m = HEAD.matcher(text);
        StringBuilder out = new StringBuilder();
        int copied = 0;
        int from = 0;
        while (from <= text.length() && m.find(from)) {
            int selStart = m.end();
            int selEnd = selectorEnd(text, selStart);
            if (selEnd < 0) {
                from = m.end();
                continue;
            }
            String selector = text.substring(selStart, selEnd);
            if (allowsMany(selector)) {
                out.append(text, copied, m.start())
                        .append(m.group(1)).append("execute as ").append(selector)
                        .append(" run attribute @s");
                copied = selEnd;
                String note = "the many-entity 'attribute " + selector + "' to 'execute as " + selector
                        + " run attribute @s' (attribute changes ONE entity at a time)";
                if (!notes.contains(note)) {
                    notes.add(note);
                }
            }
            from = selEnd;
        }
        if (copied == 0) {
            return text;
        }
        return out.append(text, copied, text.length()).toString();
    }

    /**
     * The index just past the selector starting at {@code at} ({@code @e} or {@code @e[...]}, brackets,
     * braces and quotes balanced), or -1 when it is not one this rule understands.
     */
    static int selectorEnd(String text, int at) {
        if (at + 1 >= text.length() || text.charAt(at) != '@') {
            return -1;
        }
        int i = at + 2;
        if (i < text.length() && Character.isLetterOrDigit(text.charAt(i))) {
            return -1;                                   // "@everyone" — not a selector
        }
        if (i >= text.length() || text.charAt(i) != '[') {
            return i;
        }
        int depth = 0;
        char quote = 0;
        for (; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            } else if (c == '\n') {
                return -1;
            }
        }
        return -1;
    }

    /** Can this selector name more than one entity? (@a/@e without limit=1, or any limit above one.) */
    static boolean allowsMany(String selector) {
        if (selector.contains("$(")) {
            return false;
        }
        char kind = Character.toLowerCase(selector.charAt(1));
        Matcher lim = LIMIT.matcher(selector);
        if (lim.find()) {
            try {
                return Integer.parseInt(lim.group(1)) > 1;
            } catch (NumberFormatException e) {
                return true;
            }
        }
        return kind == 'a' || kind == 'e';
    }
}
