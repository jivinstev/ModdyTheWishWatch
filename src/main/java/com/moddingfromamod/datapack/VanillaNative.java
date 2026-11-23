package com.moddingfromamod.datapack;

import java.util.List;
import java.util.Set;

/**
 * <b>Wishes the game ALREADY grants, given the right real item.</b>
 *
 * <p>Group C of the item audit is thirty wishes with no trigger at all — "comes back", "never
 * breaks", "makes animals follow me". About half turned out to be vanilla already: an enchantment, a
 * component, or a thing the item simply does. Most of those were reachable before this (the
 * {@code enchantments} property), and E1 already counts them. These are the ones that were
 * not, each the vanilla answer to a behaviour the pre-registration named:
 *
 * <ul>
 *   <li><b>lure_animals</b> — a golden carrot is in vanilla's {@code horse_tempt_items} and
 *       {@code rabbit_food}: horses, donkeys, mules and rabbits follow whoever holds one;</li>
 *   <li><b>returning_projectile</b> — a trident with Loyalty flies back to the one who threw it;</li>
 *   <li><b>frost_walk</b> — Frost Walker freezes the water under his feet, and it works on BOOTS.</li>
 * </ul>
 *
 * <p>Each is built by {@code make_item} as it is: the real item, and the enchantment where there is
 * one — no new behaviour, which is the point. What the child gets is sometimes a different THING from
 * the one he named (boots for "a wand", a trident for "a boomerang"), so each way carries the words
 * to tell him. {@code VanillaNativeGameTest} checks every fact here against the running game, on both
 * targets, so the table cannot say something the game does not do.
 *
 * <p>Minecraft-free: ids and words.
 */
public final class VanillaNative {

    /**
     * One way: the E1 part it grants, the real item, the enchantment on it (or null), what to tell
     * him, and the vanilla fact that makes it true (checked by the GameTest).
     */
    public record Way(String part, String baseItem, String enchantment, String tellHim, String fact) {
    }

    public static final List<Way> WAYS = List.of(
            new Way("lure_animals", "minecraft:golden_carrot", null,
                    "horses, donkeys and rabbits follow you while you hold it",
                    "tag:minecraft:horse_tempt_items,minecraft:rabbit_food"),
            new Way("returning_projectile", "minecraft:trident", "loyalty",
                    "throw it and it flies back to you",
                    "enchantment:minecraft:loyalty"),
            new Way("frost_walk", "minecraft:diamond_boots", "frost_walker",
                    "they are BOOTS: walk onto water and it freezes under your feet",
                    "enchantment:minecraft:frost_walker"));

    /** E1's labels (tools/parts-coverage.py reads this literal): the behaviours vanilla grants. */
    public static final Set<String> PARTS = Set.of("lure_animals", "returning_projectile", "frost_walk");

    private VanillaNative() {
    }

    /** The lines make_item's description carries, generated so the words match the table. */
    public static String guidance() {
        StringBuilder out = new StringBuilder("\nALREADY IN THE GAME — build these as the real thing, "
                + "no effect needed, and tell him what he got:");
        for (Way w : WAYS) {
            // `shape plain`, said outright: measured, the frost boots came back as worn_feet with
            // no effect, which make_item refuses — a real item does its own thing in ANY shape, and
            // plain is the one that asks for no effect.
            out.append("\n  • ").append(w.part().replace('_', ' ')).append(" → shape plain, base_item ")
                    .append(w.baseItem());
            if (w.enchantment() != null) {
                out.append(" with enchantments {\"").append(w.enchantment()).append("\": ")
                        .append(ItemProperties.ENCHANTMENTS.get(w.enchantment())).append('}');
            }
            out.append(" (\"").append(w.tellHim()).append("\")");
        }
        return out.toString();
    }
}
