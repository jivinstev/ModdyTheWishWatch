package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * <b>What a wished ITEM is and does, in his words</b> (2026-10-12) — the item card's four vitals
 * ({@link WishCardContract}'s item row: ✋ what it is, 💥 what it does, 🎨 colour, 🔁 made) and its
 * numbers, read off the item's own design rather than left as dashes.
 *
 * <p>Pure, so the watch card, the account page (which is sent the card the watch builds) and the
 * agent's own "tell him this" line all say the same thing about one item: one function, three
 * readers. The words come from the effect catalogue's {@code child_when}; the numbers from the
 * design's own parameters, so a cookie that SHRINKS you is never described as one that makes you
 * huge.
 */
public final class ItemVitals {

    private ItemVitals() {
    }

    /** "✋ boots", "✋ food — 8 of them". What kind of thing it is, from its shape. */
    public static String whatItIs(String shape, String baseItem, int count) {
        String noun = noun(shape, baseItem);
        return count > 1 ? noun + " — " + count + " of them" : noun;
    }

    static String noun(String shape, String baseItem) {
        String base = baseItem == null ? "" : baseItem.toLowerCase(Locale.ROOT);
        return switch (shape == null ? "" : shape) {
            case "held" -> "a thing you hold and use";
            case "thrown", "on_throw_land" -> "a thing you throw";
            case "placed" -> "a thing you put down";
            case "machine" -> "a machine";
            case "magic_block" -> "a magic block";
            case "on_hit" -> "a weapon";
            case "arrow" -> "an arrow";
            case "on_shot" -> "a bow";
            case "on_consume" -> base.contains("potion") || base.contains("bottle")
                    || base.contains("milk") || base.contains("stew") ? "a drink" : "food";
            case "on_block" -> "a shield";
            case "on_use_done" -> "a thing you use";
            case "on_mine" -> "a tool for digging";
            case "plain" -> "a keepsake";
            case "worn_head" -> "a hat";
            case "worn_chest" -> "something you wear";
            case "worn_legs" -> "trousers";
            case "worn_feet" -> "boots";
            default -> "a thing you can hold";
        };
    }

    /**
     * What one effect does, as he would be told it. The catalogue's line, except where the design's
     * own numbers turn it round: a {@code grow} below normal size makes you TINY.
     */
    public static String childWhen(String effect, String catalogueLine, Map<String, String> params) {
        if ("grow".equals(effect)) {
            Double scale = number(params == null ? null : params.get("SCALE"));
            if (scale != null && scale < 1) {
                return "You get TINY — and after a while you go back to normal.";
            }
        }
        return catalogueLine == null ? "" : catalogueLine;
    }

    /** "💥"'s text: every effect's line, in order; empty for an item that does nothing. */
    public static String whatItDoes(List<String> effects, Map<String, String> params,
                                    Function<String, String> catalogueLine) {
        List<String> lines = new ArrayList<>();
        for (String e : effects == null ? List.<String>of() : effects) {
            String line = childWhen(e, catalogueLine.apply(e), params);
            if (!line.isBlank() && !lines.contains(line)) {
                lines.add(line);
            }
        }
        return String.join(" ", lines);
    }

    /**
     * Its NUMBERS, one short phrase each, in a fixed order — what a child would want to compare:
     * "5 hearts of damage", "lasts 30 seconds", "size ×0.3". A parameter with no phrase (a yes/no
     * switch, a design's name) is left out rather than shown raw.
     */
    public static List<String> stats(Map<String, String> params) {
        List<String> out = new ArrayList<>();
        if (params == null || params.isEmpty()) {
            return out;
        }
        for (String key : ORDER) {
            String v = params.get(key);
            if (v == null || v.isBlank()) {
                continue;
            }
            String phrase = phrase(key, v.trim());
            if (phrase != null && !phrase.isBlank()) {
                out.add(phrase);
            }
        }
        return out;
    }

    /** The parameters that have a phrase, in the order a card lists them. */
    static final List<String> ORDER = List.of("HEARTS", "MOB", "COUNT", "AS", "EFFECT", "BONUS",
            "LEVEL", "SCALE", "SECONDS", "STRIKES", "SIZE", "POWER", "STRENGTH", "REACH", "RADIUS",
            "RATE", "ITEM", "INPUT", "INGREDIENTS", "OUTPUT", "BLOCK", "FROM", "TO", "PLACE", "KIND",
            "FIGHTS", "GLOW_SECONDS", "PRESENTS");

    static String phrase(String key, String v) {
        Double n = number(v);
        return switch (key) {
            case "HEARTS" -> n == null ? null : count(n, "heart") + " of damage";
            case "MOB" -> "makes " + pretty(v);
            case "COUNT" -> n == null ? null : fmt(n) + " at a time";
            case "AS" -> "plain".equalsIgnoreCase(v) ? null : pretty(v);
            case "EFFECT" -> pretty(v);
            case "BONUS" -> pretty(v) + " boost";
            case "LEVEL" -> n == null ? null : "level " + fmt(n + 1);
            case "SCALE" -> n == null ? null : "size ×" + fmt(n);
            case "SECONDS" -> n == null ? null : "lasts " + count(n, "second");
            case "STRIKES" -> n == null ? null : count(n, "bolt");
            case "SIZE" -> n == null ? null : "blast size " + fmt(n);
            case "POWER", "STRENGTH" -> v.toLowerCase(Locale.ROOT);
            case "REACH" -> n == null ? null : "pulls from " + count(n, "block") + " away";
            case "RADIUS" -> n == null ? null : n == 0 ? "one block" : count(n, "block") + " around";
            case "RATE" -> n == null ? null : "climbs at speed " + fmt(n);
            case "ITEM" -> "gives " + pretty(v);
            case "INPUT", "INGREDIENTS" -> "takes " + pretty(v);
            case "OUTPUT" -> "makes " + pretty(v);
            case "BLOCK" -> pretty(v);
            case "FROM" -> "changes " + pretty(v);
            case "TO" -> "into " + pretty(v);   //: turn_into's TO
            case "PLACE" -> "finds " + pretty(v);
            case "KIND" -> "any".equalsIgnoreCase(v) ? "all your pets" : "your " + pretty(v) + "s";
            case "FIGHTS" -> "me".equalsIgnoreCase(v) ? "they fight you" : "they fight monsters";
            case "GLOW_SECONDS" -> n == null || n <= 0 ? null : "creatures glow for " + count(n, "second");
            case "PRESENTS" -> "presents: " + pretty(v);
            default -> null;
        };
    }

    /** "🎨"'s text: a colour word for a colour, or null when the design names none. */
    public static String colour(String colourWordOrHex, Function<Integer, String> nameOf) {
        if (colourWordOrHex == null || colourWordOrHex.isBlank()) {
            return null;
        }
        String c = colourWordOrHex.trim().toLowerCase(Locale.ROOT);
        String hex = c.startsWith("#") ? c.substring(1) : null;
        if (hex != null && hex.matches("[0-9a-f]{6}")) {
            return nameOf.apply(Integer.parseInt(hex, 16)).replace('_', ' ');
        }
        return c.replace('_', ' ');
    }

    /** "🔁"'s text. */
    public static String made(int times) {
        return times <= 1 ? "made once" : "made " + times + " times";
    }

    /**
     * An id or a list of ids as words: {@code minecraft:zombie} → "zombie",
     * {@code minecraft:ice, minecraft:sugar} → "ice, sugar". A count suffix ({@code diamond 3})
     * survives; at most three are named.
     */
    static String pretty(String v) {
        List<String> words = new ArrayList<>();
        String[] all = v.split(",");
        for (String raw : all) {
            String s = raw.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (words.size() == 3) {
                words.add("…");
                break;
            }
            s = s.replaceFirst("^#", "");
            int colon = s.indexOf(':');
            if (colon >= 0) {
                s = s.substring(colon + 1);
            }
            words.add(s.replace('_', ' ').toLowerCase(Locale.ROOT));
        }
        return String.join(", ", words);
    }

    private static Double number(String v) {
        if (v == null) {
            return null;
        }
        try {
            double d = Double.parseDouble(v.trim());
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String fmt(double n) {
        return n == Math.rint(n) ? String.valueOf((long) n) : String.valueOf(n);
    }

    private static String count(double n, String unit) {
        if (unit.isEmpty()) {
            return fmt(n);
        }
        return fmt(n) + " " + unit + (n == 1 ? "" : "s");
    }
}
