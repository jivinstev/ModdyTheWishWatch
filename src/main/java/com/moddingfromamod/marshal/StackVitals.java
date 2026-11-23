package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * <b>What a held thing is and does, read off the STACK itself</b> — for an item card whose
 * design says nothing, or that has no design at all.
 *
 * <p>The gap: the item card ({@link ItemVitals}) was built only from the item's {@code make_item}
 * design. A vanilla pickaxe handed over with Efficiency V, Fortune III and unbreakable has no design
 * — or a {@code plain} one that says "a keepsake" — while everything a child would want to know is on
 * the stack: the base item, its enchantments, its attack damage, unbreakable, its dye. So the card
 * said "—" four times about the best pickaxe he had ever been given.
 *
 * <p>Pure: the Minecraft side reads the stack into {@link Facts} and this turns them into words, in
 * the same vocabulary {@link ItemVitals} uses ("5 hearts of damage").
 */
public final class StackVitals {

    private StackVitals() {
    }

    /**
     * What the stack carries, as plain values.
     *
     * @param baseItem     the item id ({@code minecraft:netherite_pickaxe}); blank when unknown
     * @param carrier      true when the base item is only our CARRIER (a horse armour wearing the
     *                     wish's artwork) — then the base item says nothing about the thing
     * @param enchantments enchantment id to level, in the stack's order
     * @param unbreakable  the stack never wears out
     * @param attackDamage the damage one hit does, in Minecraft's points (2 points = 1 heart), or
     *                     null when the item is not a weapon or tool
     * @param dyedRgb      the dyed colour as 0xRRGGBB, or null
     */
    public record Facts(String baseItem, boolean carrier, Map<String, Integer> enchantments,
                        boolean unbreakable, Double attackDamage, Integer dyedRgb) {

        public Facts {
            baseItem = baseItem == null ? "" : baseItem;
            enchantments = enchantments == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(enchantments));
        }

        /** Whether the base item names the thing — a real pickaxe, not our carrier. */
        public boolean baseIsTheThing() {
            return !carrier && !baseItem.isBlank() && !baseItem.startsWith("moddingfromamod:");
        }
    }

    /** "a netherite pickaxe", "an iron sword" — or null when the base item says nothing. */
    public static String whatItIs(Facts f) {
        if (f == null || !f.baseIsTheThing()) {
            return null;
        }
        String words = readable(f.baseItem());
        return words.isEmpty() ? null : com.moddingfromamod.wishbuilder.English.withArticle(words, 1);   //
    }

    /**
     * "💥"'s text from the stack: one phrase per enchantment, then "never breaks". Empty when the
     * stack carries nothing worth saying.
     */
    public static String whatItDoes(Facts f) {
        if (f == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        f.enchantments().forEach((id, level) -> {
            String p = enchantment(id, level == null ? 1 : level);
            if (!p.isBlank() && !parts.contains(p)) {
                parts.add(p);
            }
        });
        if (f.unbreakable()) {
            parts.add("never breaks");
        }
        if (parts.isEmpty()) {
            return "";
        }
        String joined = String.join(", ", parts);
        return Character.toUpperCase(joined.charAt(0)) + joined.substring(1) + "!";
    }

    /** Its numbers: "7 hearts of damage" for a weapon or tool — {@link ItemVitals}' phrase. */
    public static List<String> stats(Facts f) {
        List<String> out = new ArrayList<>();
        if (f != null && f.attackDamage() != null && f.attackDamage() > 1.0) {
            out.addAll(ItemVitals.stats(Map.of("HEARTS", fmt(f.attackDamage() / 2.0))));
        }
        return out;
    }

    /** "🎨"'s text from a dye, or null when it is not dyed. */
    public static String colour(Facts f, Function<Integer, String> nameOf) {
        if (f == null || f.dyedRgb() == null) {
            return null;
        }
        return ItemVitals.colour(String.format("#%06x", f.dyedRgb() & 0xFFFFFF), nameOf);
    }

    /**
     * One enchantment in a child's words, its level after it when that matters
     * ("mines super fast (level 5)"). An enchantment this table does not know — another mod's, or a
     * new one — is its own name and level ("Zap Power level 2") rather than nothing.
     */
    public static String enchantment(String id, int level) {
        if (id == null || id.isBlank()) {
            return "";
        }
        String key = id.trim().toLowerCase(Locale.ROOT);
        String path = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
        String phrase = key.startsWith("minecraft:") || !key.contains(":") ? PHRASES.get(path) : null;
        if (phrase == null) {
            String name = title(path);
            return level > 1 ? name + " level " + level : name;
        }
        return level > 1 && !SINGLE_LEVEL.contains(path) ? phrase + " (level " + level + ")"
                : phrase;
    }

    /** Vanilla's enchantments in child words. Every one is a promise the game keeps. */
    static final Map<String, String> PHRASES = Map.ofEntries(
            Map.entry("efficiency", "mines super fast"),
            Map.entry("fortune", "more drops from ores"),
            Map.entry("silk_touch", "picks up blocks whole"),
            Map.entry("unbreaking", "lasts much longer"),
            Map.entry("mending", "fixes itself with XP"),
            Map.entry("sharpness", "hits extra hard"),
            Map.entry("smite", "extra strong against zombies and skeletons"),
            Map.entry("bane_of_arthropods", "extra strong against spiders"),
            Map.entry("knockback", "knocks things back"),
            Map.entry("fire_aspect", "sets things on fire"),
            Map.entry("looting", "more drops from mobs"),
            Map.entry("sweeping_edge", "sweeps through crowds"),
            Map.entry("power", "shoots extra hard"),
            Map.entry("punch", "arrows knock things back"),
            Map.entry("flame", "shoots fire arrows"),
            Map.entry("infinity", "never runs out of arrows"),
            Map.entry("multishot", "shoots three at once"),
            Map.entry("quick_charge", "loads fast"),
            Map.entry("piercing", "arrows go right through"),
            Map.entry("loyalty", "comes back when thrown"),
            Map.entry("impaling", "extra strong against sea creatures"),
            Map.entry("riptide", "zooms you through water"),
            Map.entry("channeling", "calls down lightning"),
            Map.entry("protection", "protects you"),
            Map.entry("fire_protection", "protects you from fire"),
            Map.entry("blast_protection", "protects you from explosions"),
            Map.entry("projectile_protection", "protects you from arrows"),
            Map.entry("feather_falling", "softer landings"),
            Map.entry("respiration", "breathe longer underwater"),
            Map.entry("aqua_affinity", "mines fast underwater"),
            Map.entry("thorns", "stings whatever hits you"),
            Map.entry("depth_strider", "swims fast"),
            Map.entry("frost_walker", "freezes water to walk on"),
            Map.entry("soul_speed", "fast on soul sand"),
            Map.entry("swift_sneak", "sneaks fast"),
            Map.entry("lure", "fish bite sooner"),
            Map.entry("luck_of_the_sea", "better fishing"),
            Map.entry("density", "hits harder the farther you fall"),
            Map.entry("breach", "gets through armour"),
            Map.entry("wind_burst", "bounces you up"),
            Map.entry("vanishing_curse", "vanishes if you lose it"),
            Map.entry("binding_curse", "can't be taken off"));

    /** Enchantments with one level, where "(level 1)" would be noise and a higher one is odd. */
    static final java.util.Set<String> SINGLE_LEVEL = java.util.Set.of("silk_touch", "mending",
            "infinity", "multishot", "channeling", "flame", "aqua_affinity", "vanishing_curse",
            "binding_curse");

    /** {@code minecraft:netherite_pickaxe} → "netherite pickaxe". */
    static String readable(String id) {
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return path.replace('_', ' ').trim().toLowerCase(Locale.ROOT);
    }

    private static String title(String path) {
        StringBuilder b = new StringBuilder();
        for (String w : path.split("_")) {
            if (w.isEmpty()) {
                continue;
            }
            b.append(b.length() == 0 ? "" : " ")
                    .append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    private static String fmt(double n) {
        double half = Math.round(n * 2) / 2.0;
        return half == Math.rint(half) ? String.valueOf((long) half) : String.valueOf(half);
    }
}
