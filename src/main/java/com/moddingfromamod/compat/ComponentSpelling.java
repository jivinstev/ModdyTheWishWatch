package com.moddingfromamod.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>The 1.21.5 spellings of item components and text in commands</b>, corrected where
 * {@code wish/CommandDialect} corrects everything else: an agent's {@code run_command}, a CBScript program
 * before it compiles, and every STORED datapack on its way into a world ({@code datapack/DatapackDialect}).
 * That last one is why this lives here and not only in the compiler: a world's packs and the starter
 * library were compiled for 1.21.4, and 1.21.8 refuses some of their lines at load.
 *
 * <ul>
 *   <li>{@code dyed_color={rgb:N}} → {@code dyed_color=N} — refused at load ("Malformed
 *       'minecraft:dyed_color'"), and a refused line drops its whole function.</li>
 *   <li>{@code enchantments={levels:{…}}} (and {@code stored_enchantments}) → {@code enchantments={…}} —
 *       refused the same way.</li>
 *   <li>{@code attribute_modifiers={modifiers:[…]}} → the bare list; a {@code show_in_tooltip:false} becomes
 *       {@code tooltip_display={hidden_components:[…]}}.</li>
 *   <li>Text is SNBT, not JSON in a string: {@code custom_name='{"text":"X"}'}, {@code CustomName:'…'},
 *       {@code lore=['…']}, a sign's {@code messages:['…']} and a book's {@code pages} are ACCEPTED in the old
 *       form and show the braces literally (measured: a machine's sign read
 *       {@code {"text":"ice + sugar","color":"dark_gray"}}). A JSON object or string is valid SNBT, so the
 *       same text goes in unquoted.</li>
 * </ul>
 * Pure; mirrors cbscript's {@code component_syntax.py}. Already-new text is returned unchanged.
 */
public final class ComponentSpelling {

    private ComponentSpelling() {
    }

    private static final Pattern DYED = Pattern.compile(
            "((?:minecraft:)?dyed_color)=\\{\\s*rgb\\s*:\\s*(-?\\d+)\\s*(?:,\\s*show_in_tooltip\\s*:\\s*\\w+\\s*)?}");
    private static final Pattern ONE_TEXT = Pattern.compile(
            "((?:minecraft:)?(?:custom_name|item_name)=|\\b(?:CustomName|custom_name)\\s*:\\s*)'((?:[^'\\\\]|\\\\.)*)'");
    private static final Pattern TEXT_LIST = Pattern.compile(
            "((?:minecraft:)?(?:lore)=|\\b(?:messages|pages|Lore)\\s*:\\s*)\\[");
    private static final Pattern WRAPPED = Pattern.compile(
            "((?:minecraft:)?(?:attribute_modifiers|enchantments|stored_enchantments))=\\{");

    public static String correct(String text, List<String> notes) {
        if (text == null || (text.indexOf('[') < 0 && text.indexOf('{') < 0)) {
            return text;
        }
        String out = text;
        Matcher d = DYED.matcher(out);
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        while (d.find()) {
            any = true;
            d.appendReplacement(sb, Matcher.quoteReplacement(d.group(1) + "=" + d.group(2)));
        }
        if (any) {
            d.appendTail(sb);
            out = sb.toString();
            notes.add("dyed_color={rgb:N} is the pre-1.21.5 spelling; wrote dyed_color=N");
        }
        out = oneText(out, notes);
        out = textLists(out, notes);
        out = wrapped(out, notes);
        return out;
    }

    private static String unquote(String body) {
        return body.replace("\\'", "'").replace("\\\\", "\\");
    }

    private static boolean jsonText(String s) {
        String t = s.strip();
        return t.startsWith("{") || t.startsWith("\"") || t.startsWith("[");
    }

    private static String oneText(String in, List<String> notes) {
        Matcher m = ONE_TEXT.matcher(in);
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        while (m.find()) {
            String body = unquote(m.group(2));
            if (!jsonText(body)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            any = true;
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + body));
        }
        m.appendTail(sb);
        if (any) {
            notes.add("a name in quotes is plain text from 1.21.5; wrote the JSON text unquoted");
        }
        return sb.toString();
    }

    /** The index just past the bracket matching {@code s.charAt(i)}, respecting quoted strings; -1 if none. */
    static int match(String s, int i, char open, char close) {
        int depth = 0;
        char quote = 0;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == open) {
                depth++;
            } else if (c == close && --depth == 0) {
                return i + 1;
            }
        }
        return -1;
    }

    private static final Pattern QUOTED = Pattern.compile("'((?:[^'\\\\]|\\\\.)*)'");

    private static String textLists(String in, List<String> notes) {
        StringBuilder out = new StringBuilder();
        int at = 0;
        Matcher m = TEXT_LIST.matcher(in);
        boolean any = false;
        while (m.find(at)) {
            int open = m.end() - 1;
            int end = match(in, open, '[', ']');
            if (end < 0) {
                break;
            }
            String body = in.substring(open + 1, end - 1);
            List<String> items = new ArrayList<>();
            Matcher q = QUOTED.matcher(body);
            while (q.find()) {
                items.add(unquote(q.group(1)));
            }
            String rest = QUOTED.matcher(body).replaceAll("").replace(",", "").strip();
            if (!items.isEmpty() && rest.isEmpty() && items.stream().allMatch(ComponentSpelling::jsonText)) {
                out.append(in, at, open).append('[').append(String.join(",", items)).append(']');
                any = true;
            } else {
                out.append(in, at, end);
            }
            at = end;
        }
        out.append(in.substring(at));
        if (any) {
            notes.add("text lines in quotes are plain text from 1.21.5; wrote the JSON text unquoted");
        }
        return out.toString();
    }

    private static final Pattern INNER = Pattern.compile("\\s*(modifiers|levels)\\s*:\\s*([\\[{])");
    private static final Pattern HIDE_TAIL = Pattern.compile(",?\\s*show_in_tooltip\\s*:\\s*(\\w+)\\s*");

    private static String wrapped(String in, List<String> notes) {
        StringBuilder out = new StringBuilder();
        int at = 0;
        Matcher m = WRAPPED.matcher(in);
        boolean any = false;
        while (m.find(at)) {
            int open = m.end() - 1;
            int end = match(in, open, '{', '}');
            if (end < 0) {
                break;
            }
            String inner = in.substring(open + 1, end - 1);
            Matcher im = INNER.matcher(inner);
            boolean modifiers = m.group(1).endsWith("attribute_modifiers");
            if (!im.lookingAt() || modifiers != im.group(1).equals("modifiers")) {
                out.append(in, at, m.end());
                at = m.end();
                continue;
            }
            int vOpen = im.end() - 1;
            char o = inner.charAt(vOpen);
            int vEnd = match(inner, vOpen, o, o == '[' ? ']' : '}');
            String tail = vEnd < 0 ? "x" : inner.substring(vEnd).strip();
            Matcher hm = HIDE_TAIL.matcher(tail);
            if (vEnd < 0 || !(tail.isEmpty() || hm.matches())) {
                out.append(in, at, end);
                at = end;
                continue;
            }
            boolean hide = !tail.isEmpty() && (hm.group(1).equals("false") || hm.group(1).startsWith("0"));
            String component = m.group(1).startsWith("minecraft:") ? m.group(1) : "minecraft:" + m.group(1);
            out.append(in, at, m.start()).append(m.group(1)).append('=').append(inner, vOpen, vEnd);
            if (hide) {
                out.append(",minecraft:tooltip_display={hidden_components:[\"").append(component).append("\"]}");
            }
            any = true;
            at = end;
        }
        out.append(in.substring(at));
        if (any) {
            notes.add("attribute_modifiers/enchantments lost their {modifiers:…}/{levels:…} wrapper in 1.21.5");
        }
        return out.toString();
    }
}
