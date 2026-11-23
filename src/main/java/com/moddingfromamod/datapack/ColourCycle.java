package com.moddingfromamod.datapack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>Recolour an ordinary mob — the part a "rainbow axolotl" was missing</b> (E1).
 *
 * <p>Measured twice, on both rigs: asked for <i>"a rainbow axolotl that glows in the dark"</i>, the
 * agent summoned a vanilla axolotl with a glow and lost the rainbow, because its only other choice
 * was sculpting a whole new creature. A vanilla mob that has colour VARIANTS already carries its
 * palette; stepping through it is a rainbow with no model, no pack and no reload. This class knows,
 * per mob, which saved field holds its colour and the order of its variants that reads as a
 * rainbow; the {@code colour_cycle} template steps through them on a clock.
 *
 * <p>Minecraft-free. The field names are 1.21.1's saved-data keys, and {@code ColourCycleGameTest}
 * proves each one on a real mob — a wrong key is a {@code data modify} that fails silently.
 */
public final class ColourCycle {

    /** A mob's colour: the saved field, and its values in rainbow order, already SNBT-literal. */
    public record Palette(String field, List<String> values) {
    }

    private static final Map<String, Palette> PALETTES = new LinkedHashMap<>();

    static {
        // Wool dye ids, red → pink round the wheel. Sheep and shulkers share them.
        List<String> dyes = List.of("14b", "1b", "4b", "5b", "13b", "9b", "3b", "11b", "10b", "2b",
                "6b");
        put("axolotl", "Variant", "0", "2", "3", "4");             // pink, gold, cyan, blue
        put("sheep", "Color", dyes);
        put("shulker", "Color", dyes);
        put("parrot", "Variant", "0", "3", "2", "1");               // red, yellow-blue, green, blue
        put("rabbit", "RabbitType", "4", "0", "1", "3", "5", "2");  // gold … black
        put("llama", "Variant", "0", "2", "1", "3");
        put("trader_llama", "Variant", "0", "2", "1", "3");
        put("horse", "Variant", "1", "2", "3", "6", "4", "5", "0");
        put("mooshroom", "Type", "\"red\"", "\"brown\"");
        put("fox", "Type", "\"red\"", "\"snow\"");
        put("frog", "variant", "\"minecraft:warm\"", "\"minecraft:temperate\"",
                "\"minecraft:cold\"");
        put("cat", "variant", "\"minecraft:red\"", "\"minecraft:calico\"",
                "\"minecraft:siamese\"", "\"minecraft:white\"", "\"minecraft:british_shorthair\"",
                "\"minecraft:all_black\"");
    }

    private static void put(String mob, String field, String... values) {
        put(mob, field, List.of(values));
    }

    private static void put(String mob, String field, List<String> values) {
        PALETTES.put(mob, new Palette(field, values));
    }

    private ColourCycle() {
    }

    /** Every mob this can recolour, in a stable order — the words the agent is offered. */
    public static List<String> mobs() {
        return List.copyOf(PALETTES.keySet());
    }

    /** The palette for a child's word for a mob ("axolotls", "minecraft:sheep"), or null. */
    public static Palette palette(String mob) {
        return PALETTES.get(normalise(mob));
    }

    static String normalise(String mob) {
        if (mob == null) {
            return "";
        }
        String m = mob.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (m.startsWith("minecraft:")) {
            m = m.substring("minecraft:".length());
        }
        if (PALETTES.containsKey(m)) {
            return m;
        }
        if (m.endsWith("es") && PALETTES.containsKey(m.substring(0, m.length() - 2))) {
            return m.substring(0, m.length() - 2);
        }
        if (m.endsWith("s") && PALETTES.containsKey(m.substring(0, m.length() - 1))) {
            return m.substring(0, m.length() - 1);
        }
        return m;
    }

    /** The scoreboard holder for one creature's clock: short, and safe in a command. */
    public static String keyFor(String tag) {
        String k = tag == null ? "" : tag.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return k.length() > 28 ? k.substring(0, 28) : k;
    }

    /**
     * The commands for one step of the cycle, one per colour, each gated on the clock having
     * reached its period and the hue counter reading that colour. Indented and joined so they drop
     * straight into the template's {@code clock tick} block.
     */
    public static String steps(String tag, Palette p, int periodTicks) {
        String k = keyFor(tag);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < p.values().size(); i++) {
            lines.add("/execute if score #t_" + k + " mfam_hue matches " + periodTicks + ".. if score #h_"
                    + k + " mfam_hue matches " + i + " as @e[tag=" + tag + "] run data modify "
                    + "entity @s " + p.field() + " set value " + p.values().get(i));
        }
        return String.join("\n    ", lines);
    }
}
