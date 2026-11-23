package com.moddingfromamod.selector;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>The linter: which {@code @e} selectors in a datapack check every entity in the world,
 * even with the tag index</b> — pure.
 *
 * <p>An {@code @e} is answered cheaply in exactly two ways: it names a positive {@code tag=} (the
 * index answers it from the entities carrying that tag), or it names a position
 * ({@code distance}, {@code x}/{@code y}/{@code z}, {@code dx}/{@code dy}/{@code dz}: vanilla
 * searches only that box). Anything else walks every loaded entity each time it runs — and that
 * INCLUDES {@code type=}, which vanilla checks entity by entity over the whole lookup
 * ({@code EntityLookup.getEntities} → {@code tryCast}, 1.21.8 bytecode), and {@code scores=}. A
 * rigged creature is ~25–60 entities, so in a tick function each one is paid many times over.
 *
 * <p>Advisory, never a refusal: a selector in a function that runs once is fine, and only the pack
 * knows. Template placeholders ({@code tag=${TAG}}) count as a tag. The fix to suggest is always
 * the same: tag what you track when you start tracking it ({@code tag @s add <pack>_on}), select
 * by that tag, and remove it when you are done.
 */
public final class SelectorCost {

    private SelectorCost() {
    }

    private static final Pattern E = Pattern.compile("@e(?![A-Za-z0-9_])");
    private static final Set<String> POSITIONAL = Set.of("distance", "x", "y", "z", "dx", "dy", "dz");

    /** Every distinct {@code @e} selector in {@code text} that is neither tagged nor positioned, in order. */
    public static List<String> costly(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return List.of();
        }
        // a comment line runs nothing (CBScript and mcfunction both start one with '#'); the
        // templates explain @e in prose, and a first cut flagged four of them for it
        StringBuilder code = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            code.append(line.strip().startsWith("#") ? "" : line).append('\n');
        }
        text = code.toString();
        Matcher m = E.matcher(text);
        while (m.find()) {
            int end = m.end();
            String sel;
            List<String[]> opts = new ArrayList<>();
            if (end < text.length() && text.charAt(end) == '[') {
                int close = matching(text, end);
                if (close < 0) {
                    continue;   // not a selector this can read: say nothing rather than guess
                }
                sel = text.substring(m.start(), close + 1);
                for (String o : options(text.substring(end + 1, close))) {
                    int eq = o.indexOf('=');
                    if (eq > 0) {
                        opts.add(new String[]{o.substring(0, eq).strip(), o.substring(eq + 1).strip()});
                    }
                }
            } else {
                sel = "@e";
            }
            boolean cheap = false;
            for (String[] kv : opts) {
                if (POSITIONAL.contains(kv[0])
                        || kv[0].equals("tag") && !kv[1].isEmpty() && !kv[1].startsWith("!")) {
                    cheap = true;
                    break;
                }
            }
            if (!cheap) {
                out.add(sel);
            }
        }
        return List.copyOf(out);
    }

    private static int matching(String s, int open) {
        int depth = 0;
        boolean quoted = false;
        char q = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '\\') {
                    i++;
                } else if (c == q) {
                    quoted = false;
                }
            } else if (c == '"' || c == '\'') {
                quoted = true;
                q = c;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    return c == ']' ? i : -1;
                }
            } else if (c == '\n') {
                return -1;
            }
        }
        return -1;
    }

    private static List<String> options(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        char q = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (quoted) {
                if (c == '\\') {
                    i++;
                } else if (c == q) {
                    quoted = false;
                }
            } else if (c == '"' || c == '\'') {
                quoted = true;
                q = c;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(body.substring(start, i));
                start = i + 1;
            }
        }
        out.add(body.substring(start));
        return out;
    }

    /** The advisory {@code verify_datapack} appends, or "" when there is nothing to say. */
    public static String advice(String source) {
        List<String> c = costly(source);
        if (c.isEmpty()) {
            return "";
        }
        return "\n⏱ CHECKS EVERY ENTITY: " + c + (c.size() == 1 ? " has" : " have")
                + " no tag and no position, so each time it runs Minecraft looks at every entity in the "
                + "world (a creature is dozens of them). Fine in something that runs once; on a clock or "
                + "a tick, select by a TAG instead: a creature is @e[tag=wishmob_<id>], and to track "
                + "anything else, tag it when you start (tag @s add <pack>_on), select by that tag, and "
                + "remove the tag when you are done. type= and scores= alone do not narrow the search.";
    }
}
