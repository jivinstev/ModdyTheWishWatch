package com.moddingfromamod.datapack;

import com.moddingfromamod.item.RealItemHint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>The side door: a datapack that GIVES an item skips every item rule.</b>
 *
 * <p>{@code make_item} refuses a made carrier named after a real item with its own action
 * ({@link RealItemHint}). A pack can write the same item by hand — {@code give @s
 * moddingfromamod:wish_item[custom_name='"Storm Trident"',…]}, or a carrot on a stick dressed as a
 * pearl — and nothing looked. Measured in an earlier pass: Opus handed out 40 of 332 items that way
 * and Muse 18 of 104, and 16 of those were exactly the thing make_item would have refused (a
 * trident that cannot be thrown, a horn that cannot be blown, an apple that cannot be eaten).
 *
 * <p>Pure: it reads text — CBScript source or a compiled {@code .mcfunction}, since a raw
 * {@code give} is the same line in both — and says which gives are a made carrier standing in for
 * a real item. The fixture is {@code corpus/regressions/1068-datapack-item-gives.json}.
 */
public final class DatapackItemGives {

    /** One give that should have been the real item. */
    public record Finding(String name, String given, RealItemHint.Real real) {
    }

    private static final Pattern GIVE = Pattern.compile("(?:^|[\\s/])give\\s+(\\S+)\\s+([a-z0-9_.\\-]+:)?([a-z0-9_./\\-]+)");
    private static final Pattern MODEL = Pattern.compile("item_model\\s*=\\s*['\"]([a-z0-9_.\\-]+:?[a-z0-9_./\\-]*)['\"]");
    private static final Pattern NAME = Pattern.compile("(?:custom_name|item_name)\\s*=\\s*");
    private static final Pattern TEXT = Pattern.compile("\\\\?\"text\\\\?\"\\s*:\\s*\\\\?\"((?:[^\"\\\\]|\\\\[^\"])*)\\\\?\"");
    private static final Pattern QUOTED = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern TELEPORTS = Pattern.compile("(?:^|[\\s/])(?:tp|teleport)\\s");
    private static final Pattern PEARL = Pattern.compile("\\bpearls?\\b");

    private DatapackItemGives() {
    }

    /** Is this item id one of ours or vanilla's made carriers (a thing that is only clicked)? */
    static boolean madeCarrier(String namespace, String path) {
        String ns = namespace == null ? "minecraft" : namespace.substring(0, namespace.length() - 1);
        if ("moddingfromamod".equals(ns)) {
            return path.equals("wish_item");
        }
        return "minecraft".equals(ns) && (path.equals("carrot_on_a_stick")
                || path.equals("warped_fungus_on_a_stick") || path.endsWith("horse_armor"));
    }

    /** Every give in {@code text} that hands out a made carrier where a real item was meant. */
    public static List<Finding> scan(String text) {
        Map<String, Finding> out = new LinkedHashMap<>();
        if (text == null) {
            return List.of();
        }
        boolean teleports = TELEPORTS.matcher(text).find();
        Matcher m = GIVE.matcher(text);
        while (m.find()) {
            if (isEffectGive(text, m.start())) {
                continue;
            }
            if (!madeCarrier(m.group(2), m.group(3))) {
                continue;
            }
            String components = componentsAt(text, m.end());
            String name = nameIn(components);
            Optional<RealItemHint.Real> real = RealItemHint.forName(name);
            Matcher model = MODEL.matcher(components);
            if (real.isEmpty() && model.find()) {
                real = RealItemHint.forModel(model.group(1));
            }
            if (real.isEmpty() && teleports && name != null
                    && PEARL.matcher(name.toLowerCase(Locale.ROOT)).find()) {
                // "a pearl that takes me home": a pearl that TELEPORTS is an ender pearl. "pearl"
                // alone is not in RealItemHint (a pearl necklace), so it needs the pack's own tp.
                real = RealItemHint.forName("ender pearl");
            }
            String given = (m.group(2) == null ? "minecraft:" : m.group(2)) + m.group(3);
            real.ifPresent(r -> out.putIfAbsent(name + "|" + given,
                    new Finding(name == null ? given : name, given, r)));
        }
        return new ArrayList<>(out.values());
    }

    private static boolean isEffectGive(String text, int at) {
        int lineStart = text.lastIndexOf('\n', at) + 1;
        String before = text.substring(lineStart, Math.min(text.length(), at + 1)).trim();
        return before.endsWith("effect") || before.matches(".*\\beffect\\s*$");
    }

    /** The {@code [ … ]} right after the item id, bracket- and quote-aware; "" when none. */
    static String componentsAt(String text, int at) {
        if (at >= text.length() || text.charAt(at) != '[') {
            return "";
        }
        int depth = 0;
        char quote = 0;
        for (int i = at; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(at, i + 1);
                }
            } else if (c == '\n') {
                break;
            }
        }
        return text.substring(at);
    }

    /** The shown name — a custom_name or item_name, JSON or a bare quoted string. */
    static String nameIn(String components) {
        Matcher n = NAME.matcher(components);
        if (!n.find()) {
            return null;
        }
        String rest = components.substring(n.end());
        Matcher t = TEXT.matcher(rest);
        if (t.find() && t.start() < 8) {
            return t.group(1);
        }
        Matcher q = QUOTED.matcher(rest);
        return q.find() && q.start() < 4 ? q.group(1) : null;
    }

    /** What the agent is told: the make_item path, which keeps every item rule. */
    public static String refusal(List<Finding> found) {
        StringBuilder sb = new StringBuilder("This pack GIVES ");
        for (int i = 0; i < found.size(); i++) {
            Finding f = found.get(i);
            sb.append(i == 0 ? "" : (i == found.size() - 1 ? " and " : ", "))
                    .append('\'').append(f.name()).append("' as a ").append(f.given());
        }
        RealItemHint.Real r = found.get(0).real();
        sb.append(". That is a made item that is only CALLED a ").append(r.word())
                .append(": a real ").append(r.word()).append(' ').append(r.keeps())
                .append(", and this one can only be clicked. Hand it out with make_item instead, "
                        + "built ON the real thing (base_item \"").append(r.baseItem())
                .append("\") with the shape its own use triggers — make_item keeps every item rule "
                        + "a hand-written give skips — and keep this pack for the rest of the wish. "
                        + "Only if he wants something that must look like a ").append(r.word())
                .append(" and must NOT work like one, call author_datapack again as it is.");
        return sb.toString();
    }
}
