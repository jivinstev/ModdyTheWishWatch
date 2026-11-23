package com.moddingfromamod.item;

import java.util.List;
import java.util.Map;

/**
 * <b>What a wished item DOES, drawn on its icon.</b>
 *
 * <p>An item's icon is its library sprite dyed his colour — a wand is a purple wand whether it
 * throws lightning or heals. On 1.21.4 the icon is COMPOSED: the sprite, and over it one overlay
 * from a fixed library (sparks, flame, frost, hearts…) chosen by what the item does. The overlays
 * ship once, in the join-time item pack and the jar, so choosing one is a string on the stack and
 * never a new picture: zero reloads per wish, by construction.
 *
 * <p>The vocabulary is the closed set ({@code OVERLAYS} in {@code tools/parts-coverage.py},
 * which is what the synthetic wish corpus is labelled with); {@code IconOverlaysTest} fails if the
 * two disagree, and {@code tools/gen-overlay-sprites.py} refuses to draw a set that differs.
 *
 * <p>The overlay is DERIVED, never asked for: the agent already names the effect, and the effect
 * is what the overlay depicts. A second field the agent had to fill would be a second place for
 * the icon and the behaviour to disagree. Pure, so every rule here is a unit test.
 */
public final class IconOverlays {

    /** Every overlay the library draws, in the order the pack lists them. {@code none} is absence. */
    public static final List<String> NAMES = List.of(
            "boom", "bubbles", "coins", "flame", "frost", "glow", "hearts", "leaves", "moon", "music",
            "rain", "rainbow", "shadow", "shield", "slime", "smoke", "sparkle", "sparks", "stars", "sun",
            "swirl", "wind");

    /**
     * <b>Effect id → overlay.</b> Only effects with an unmistakable picture have one; {@code hurt}
     * and {@code place} draw no overlay, because a wrong hint is worse than none — and since the vanilla icon bases
     * no wish is ever PLAIN either: every wish icon wears the sparkle backing whatever it does. Every key is a catalogue effect id, which {@code IconOverlaysTest} checks
     * against {@code items/catalogue.json}.
     */
    static final Map<String, String> BY_EFFECT = Map.ofEntries(
            Map.entry("lightning", "sparks"),
            Map.entry("turn_into", "swirl"),
            Map.entry("status", "glow"),
            Map.entry("ignite", "flame"),
            Map.entry("freeze", "frost"),
            Map.entry("heal", "hearts"),
            Map.entry("light", "glow"),
            Map.entry("teleport", "swirl"),
            Map.entry("fly", "wind"),
            Map.entry("launch", "wind"),
            Map.entry("speed", "wind"),
            Map.entry("jump", "wind"),
            Map.entry("grow", "leaves"),
            Map.entry("rich", "coins"),
            Map.entry("protect", "shield"),
            Map.entry("explode", "boom"),
            Map.entry("summon", "sparkle"),
            Map.entry("aggro", "shadow"),
            // Steam rising off food and drinks — the one picture food has that is not magic.
            Map.entry("food", "smoke"));

    private IconOverlays() {
    }

    /**
     * The overlay for an item that does these effects, or {@code null} for none. With several
     * effects the FIRST that has a picture wins — the first effect is the one the item is named
     * after ({@code MakeItemTool}'s feature id), so it is the one he asked for.
     */
    public static String forEffects(List<String> effects) {
        if (effects == null) {
            return null;
        }
        for (String e : effects) {
            String o = e == null ? null : BY_EFFECT.get(e);
            if (o != null) {
                return o;
            }
        }
        return null;
    }

    /** True for a name the library draws. */
    public static boolean known(String overlay) {
        return overlay != null && NAMES.contains(overlay);
    }
}
