package com.moddingfromamod.item;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>"Show me where the treasure is", the pure half.</b> Which ores, found how far,
 * and how often the beeper beeps. A closed ORE word → block tags (vanilla's own {@code *_ores} block
 * tags, the same on both targets), and two outputs: GLOW (the ores light up through walls) and BEEP
 * (a note that comes faster and higher as he gets closer).
 */
public final class RevealRule {

    public static final List<String> HOW = List.of("glow", "beep");
    public static final Map<String, List<String>> ORES = new LinkedHashMap<>();

    static {
        ORES.put("diamond", List.of("minecraft:diamond_ores"));
        ORES.put("gold", List.of("minecraft:gold_ores"));
        ORES.put("iron", List.of("minecraft:iron_ores"));
        ORES.put("emerald", List.of("minecraft:emerald_ores"));
        ORES.put("coal", List.of("minecraft:coal_ores"));
        ORES.put("copper", List.of("minecraft:copper_ores"));
        ORES.put("redstone", List.of("minecraft:redstone_ores"));
        ORES.put("lapis", List.of("minecraft:lapis_ores"));
        ORES.put("any", List.of("c:ores"));
    }

    /** How far he can sense, in blocks each way: a 17-block cube is 4,913 blocks, cheap twice a second. */
    public static final int RADIUS = 8;
    /** How many ores glow at once, nearest first: enough to see a vein, never a light show. */
    public static final int MAX_GLOW = 12;

    private RevealRule() {
    }

    /** Ticks between beeps at this distance: every 4 ticks when touching it, every 40 at the edge. */
    public static int beepInterval(double distance) {
        return (int) Math.max(4, Math.min(40, Math.round(distance * 4)));
    }

    /** Pitch of a beep: 2.0 on top of it, 0.5 at the edge. */
    public static float beepPitch(double distance) {
        double t = Math.max(0, Math.min(1, distance / (RADIUS * Math.sqrt(3))));
        return (float) (2.0 - 1.5 * t);
    }

    public static String ore(String word) {
        if (word == null) {
            return null;
        }
        String w = word.trim().toLowerCase(java.util.Locale.ROOT).replace("_ore", "").replace("diamonds", "diamond")
                .replace("ores", "any");
        if (w.endsWith("s") && ORES.containsKey(w.substring(0, w.length() - 1))) {
            w = w.substring(0, w.length() - 1);
        }
        return ORES.containsKey(w) ? w : null;
    }
}
