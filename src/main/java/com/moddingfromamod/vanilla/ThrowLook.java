package com.moddingfromamod.vanilla;

import java.util.Locale;

/**
 * <b>What a thrown ball looks like IN FLIGHT, beyond its item sprite</b> (follow-up).
 *
 * <p>A rig throw is a snowball drawn as the item it throws ({@link RigAbilities#throwBall}). Some of
 * those sprites are dark: measured from the 1.21.8 client jar, the fire charge is 45% near-black
 * pixels (mean {@code #5c4229}) and the ender pearl 44% (mean {@code #14584d}), so the fire buddy's
 * fireball read as a black ball. Vanilla's own
 * fireballs use that same sprite and read as fire because they burn and trail flames. A stock
 * client cannot be given a new renderer, so the look is made of what every client already draws:
 * <ul>
 *   <li><b>burning</b>: an entity on fire is drawn wrapped in flames, a thrown ball included (its
 *       fire flag is synced every tick, {@code Entity.baseTick});</li>
 *   <li><b>a trail</b>: particles the server sends along the ball's path.</li>
 * </ul>
 * Chosen by what the item IS (its id's words), so a wish for any fire-ish or ender-ish thing gets
 * the look without a table entry per item. Light sprites (snowball, egg, slime ball) keep their
 * plain look, and coal stays black, because coal is black.
 *
 * <p>Pure: no Minecraft.
 */
public final class ThrowLook {

    private ThrowLook() {
    }

    /** A trail's kind: none, flames, the ender shimmer, or coloured dust ({@link Look#rgb}). */
    public enum Trail { NONE, FLAME, PORTAL, DUST }

    /** How a ball looks in flight: burning or not, its trail, and the dust colour for {@link Trail#DUST}. */
    public record Look(boolean burning, Trail trail, int rgb) {
        public static final Look PLAIN = new Look(false, Trail.NONE, 0);
    }

    private static final String[] FIRE = {"fire", "blaze", "magma", "flame", "lava", "ember", "inferno"};
    private static final String[] ENDER = {"ender", "chorus", "shulker"};

    /**
     * Dark items with a real colour of their own, which a dark sprite hides at a distance: dust in
     * that colour. Measured means where the sprite is dark (redstone {@code #6b0500}); the colour is
     * the thing's own, brightened to read in flight.
     */
    private static final Object[][] DUST = {
            {"redstone", 0xFF2A1A},
            {"glowstone", 0xFFD34A},
            {"glow_ink", 0x6FF5D8},
            {"amethyst", 0xB57BFF},
            {"echo_shard", 0x2EC7C7},
            {"heart_of_the_sea", 0x2E9BD6},
            {"prismarine", 0x7FD8C4},
    };

    /** The look for a thrown item id ({@code minecraft:fire_charge}); plain for none or unknown. */
    public static Look of(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return Look.PLAIN;
        }
        String path = itemId.toLowerCase(Locale.ROOT);
        path = path.contains(":") ? path.substring(path.indexOf(':') + 1) : path;
        if (path.equals("wind_charge")) {
            return Look.PLAIN;   // a CHARGE, not a fire: "charge" must never read as fire
        }
        for (String w : FIRE) {
            if (path.contains(w)) {
                return new Look(true, Trail.FLAME, 0);
            }
        }
        for (String w : ENDER) {
            if (path.contains(w)) {
                return new Look(false, Trail.PORTAL, 0);
            }
        }
        for (Object[] d : DUST) {
            if (path.contains((String) d[0])) {
                return new Look(false, Trail.DUST, (Integer) d[1]);
            }
        }
        return Look.PLAIN;
    }
}
