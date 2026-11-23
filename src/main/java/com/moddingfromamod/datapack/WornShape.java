package com.moddingfromamod.datapack;

import java.util.List;
import java.util.Locale;

/**
 * <b>A thing he WEARS: {@code worn_head}, {@code worn_chest}, {@code worn_legs},
 * {@code worn_feet}.</b>
 *
 * <p>Every other shape fires when he DOES something — a right-click, a throw, a swing. A worn thing
 * fires while it is ON him: {@code items/shape_worn.cbs} runs the effect the moment he puts it on and
 * again each time it would wear off, for as long as it stays in that armour slot (the quiet tick),
 * and nothing at all once he takes it off.
 *
 * <p><b>Four flat names rather than {@code worn(slot)} with a second field</b>, because a shape is a
 * single stored string everywhere it travels — the item design ({@code ItemSpecStore}), a
 * refinement ({@code start_from}), a library button — and a slot that lived in a new field would have
 * been a stored-shape change for every one of them. The name is {@code worn_<slot>} and the slot is
 * read off it, here and nowhere else.
 *
 * <h2>What it looks like on him</h2>
 *
 * <ul>
 *   <li><b>built on a real item</b> ({@code base_item}, e.g. {@code minecraft:golden_boots} or
 *       {@code minecraft:elytra}): that item's own look, armour and icon — the vanilla item IS the
 *       picture, on the body and in the hand, and an elytra glides;</li>
 *   <li><b>made from scratch</b>: the carrier, told where it goes with {@code minecraft:equippable}
 *       and drawn on the body as <b>leather dyed his colour</b> ({@link #LEATHER_ASSET}), with the
 *       matching vanilla leather piece as its icon, and leather's own armour points for that slot.</li>
 * </ul>
 *
 * <p>Minecraft-free, like {@link ItemBase}: which slot, which asset and which armour value is a
 * string decision, testable without a game.
 */
public final class WornShape {

    private WornShape() {
    }

    /** The prefix every worn shape shares. */
    public static final String PREFIX = "worn_";

    /** The four slots, in the order a child reads a body top to bottom. */
    public static final List<String> SLOTS = List.of("head", "chest", "legs", "feet");

    /** The four shape names. */
    public static final List<String> SHAPES =
            SLOTS.stream().map(s -> PREFIX + s).toList();

    /** Vanilla's dyeable leather equipment asset — the look of a worn thing made from scratch. */
    public static final String LEATHER_ASSET = "minecraft:leather";

    /** Is this one of the worn shapes? */
    public static boolean isWorn(String shape) {
        return shape != null && SHAPES.contains(shape);
    }

    /** The slot a worn shape names: {@code worn_feet} → {@code feet}. */
    public static String slot(String shape) {
        if (!isWorn(shape)) {
            throw new IllegalArgumentException("'" + shape + "' is not a worn shape; have " + SHAPES);
        }
        return shape.substring(PREFIX.length());
    }

    /**
     * The vanilla leather piece for a slot — the icon a from-scratch worn thing wears in the hand,
     * tinted his colour by its own item definition's {@code dye} source.
     */
    public static String leatherPiece(String slot) {
        return "minecraft:leather_" + switch (slot) {
            case "head" -> "helmet";
            case "chest" -> "chestplate";
            case "legs" -> "leggings";
            case "feet" -> "boots";
            default -> throw new IllegalArgumentException("no slot '" + slot + "'");
        };
    }

    /** Leather's own armour points for a slot (vanilla: 1 / 3 / 2 / 1). */
    public static int leatherArmour(String slot) {
        return switch (slot) {
            case "head", "feet" -> 1;
            case "chest" -> 3;
            case "legs" -> 2;
            default -> throw new IllegalArgumentException("no slot '" + slot + "'");
        };
    }

    /**
     * The components that make a FROM-SCRATCH carrier a worn thing, as give-line text with no
     * trailing comma: where it goes, what is drawn on the body, and what it is drawn as in the hand.
     * (Its stack size, 1, is written where every carrier's is — MakeItemTool.itemArg.)
     * The attribute modifiers are rendered separately, because a give line may carry that component
     * only once and a property ({@code attack_damage}) may already be writing it.
     */
    public static String scratchComponents(String slot) {
        return scratchEquippable(slot) + ",minecraft:item_model=\"" + leatherPiece(slot) + "\"";
    }

    /**
     * Where it goes and what is drawn on the BODY, with no hand model: a worn thing whose icon is a drawn
     * library picture (a wizard hat, a crown) is drawn as that picture in the hand, and as leather on him.
     */
    public static String scratchEquippable(String slot) {
        return "minecraft:equippable={slot:\"" + slot + "\",asset_id:\"" + LEATHER_ASSET + "\","
                + "equip_sound:\"minecraft:item.armor.equip_leather\"}";
    }

    /**
     * Leather's armour for this slot as one {@code attribute_modifiers} entry, id'd as ours so
     * nothing else collides with it. Written INSTEAD of the carrier's own +3 on the body slot,
     * which a present component already suppresses.
     */
    public static String scratchArmourModifier(String slot, String armourAttributeId) {
        return "{type:\"" + armourAttributeId + "\",id:\"moddingfromamod:wish_worn_armor_"
                + slot.toLowerCase(Locale.ROOT) + "\",amount:" + leatherArmour(slot)
                + ",operation:\"add_value\",slot:\"" + slot + "\"}";
    }
}
