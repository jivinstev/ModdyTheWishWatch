package com.moddingfromamod.agent.tools;

import java.util.Map;

/**
 * <b>{@code spare_players}: a power that never touches a player</b> (WISH_BUILDER_PLAN §6.2). A bow's,
 * trident's or snowball's wish runs where it LANDS, on whatever is near there, and that can be his friend. With
 * the flag, the effect's {@code TARGET} skips every player, and a lightning bolt is summoned only when no player
 * stands within reach of it. Pure: {@code SparePlayersTest}.
 */
public final class SparePlayers {

    /** A bolt's damage reaches about three blocks; four keeps a player well clear of it. */
    static final String NO_PLAYER_NEAR = "execute unless entity @a[distance=..4] run ";

    private SparePlayers() {
    }

    /** Rewrite one effect's rendered arguments in place. */
    public static void apply(Map<String, String> effectArgs) {
        String target = effectArgs.get("TARGET");
        if (target != null) {
            effectArgs.put("TARGET", spare(target));
        }
        String bolts = effectArgs.get("BOLTS");
        if (bolts != null) {
            effectArgs.put("BOLTS", bolts.replace("/summon minecraft:lightning_bolt", "/" + NO_PLAYER_NEAR
                    + "summon minecraft:lightning_bolt"));
        }
    }

    /** A selector that cannot be a player: {@code @s} (him) is left alone only when it is not a player at all. */
    static String spare(String selector) {
        if (selector.contains("type=!minecraft:player") || selector.contains("type=minecraft:player")) {
            return selector;
        }
        if (selector.matches(".*[\\[,]type=[^!].*")) {
            // A POSITIVE type (type=minecraft:zombie, a magic block's monsters-only type=#…) is never a player already,
            // and the server refuses a second type= beside it ("Option 'type' isn't applicable here"), switching off
            // the whole datapack. Found 's GameTest: a freeze block on monsters only was refused.
            return selector;
        }
        if (selector.startsWith("@e[")) {
            return "@e[type=!minecraft:player," + selector.substring(3);
        }
        if (selector.equals("@e")) {
            return "@e[type=!minecraft:player]";
        }
        return selector;   // @s: the effect is on HIM, which is never another player
    }
}
