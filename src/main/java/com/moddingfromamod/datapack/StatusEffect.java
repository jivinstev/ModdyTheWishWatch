package com.moddingfromamod.datapack;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>"While he holds or wears it": the {@code status} effect.</b>
 *
 * <p>A helmet that lets him breathe underwater, a helmet with night vision, a shield that makes him
 * invisible, a feather that lets him float down slowly: each is ONE vanilla status effect kept on
 * him — by the worn clock while it is on, or for a while after he right-clicks a held one. One
 * effect answers them all, choosing from a closed table of the KIND effects ({@link #EFFECTS}):
 * nothing that hurts, blinds, poisons or lifts him off the ground, because those are things done
 * TO a child.
 *
 * <p><b>Why a worn one is given longer than it is renewed.</b> The worn clock renews an effect just
 * before it runs out, which is right for speed and wrong for night vision: vanilla FLASHES the
 * screen for night vision's last ten seconds, so a helmet renewed at half a second left would
 * flicker every cycle. A worn status is therefore given {@link #WORN_PAD_SECONDS} longer than the
 * clock's renewal, and never drops into that window.
 *
 * <p><b>Why fire resistance answers "see in lava".</b> Since 1.19.3 vanilla thins the fog in lava
 * for a player with fire resistance — it is the game's own lava vision, not a stand-in.
 */
public final class StatusEffect {

    private StatusEffect() {
    }

    /** The effect id in {@code items/catalogue.json}. */
    public static final String EFFECT = "status";

    /** The E1 labels this effect grants ({@code tools/parts-coverage.py} reads this set). */
    public static final Set<String> PARTS = Set.of("status_effect", "lava_vision");

    /** How much longer than its renewal a WORN status lasts: past night vision's 10 s flash. */
    public static final int WORN_PAD_SECONDS = 12;

    /** The longest a held one lasts: ten minutes. */
    public static final int MAX_SECONDS = 600;

    /** The kind effects, by the name the agent sends, each with its highest level (0-based). */
    public static final Map<String, Integer> EFFECTS = new LinkedHashMap<>();

    static {
        Object[][] table = {
                {"water_breathing", 0}, {"night_vision", 0}, {"invisibility", 0},
                {"slow_falling", 0}, {"fire_resistance", 0}, {"dolphins_grace", 0},
                {"conduit_power", 0}, {"speed", 3}, {"jump_boost", 3}, {"strength", 2},
                {"resistance", 2}, {"regeneration", 2}, {"haste", 3}, {"luck", 2},
                {"health_boost", 3}, {"absorption", 3}, {"saturation", 0}, {"glowing", 0}};
        for (Object[] row : table) {
            EFFECTS.put((String) row[0], (Integer) row[1]);
        }
    }

    /** The table's name for what the agent sent (a bare or a minecraft: id), or null. */
    public static String effect(String raw) {
        if (raw == null) {
            return null;
        }
        String e = raw.trim().toLowerCase(Locale.ROOT);
        if (e.startsWith("minecraft:")) {
            e = e.substring("minecraft:".length());
        }
        return EFFECTS.containsKey(e) ? e : null;
    }

    /** How long to GIVE it for: a worn one outlasts its renewal (see the class note). */
    public static int duration(String shape, int seconds) {
        return WornShape.isWorn(shape) ? seconds + WORN_PAD_SECONDS : seconds;
    }
}
