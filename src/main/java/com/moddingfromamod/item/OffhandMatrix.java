package com.moddingfromamod.item;

import com.moddingfromamod.ModdingFromAModMod;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;

/**
 * <b>The offhand click matrix: every kind of wished thing a child can hold, crossed
 * with what he might have in his other hand.</b> See {@code docs/OFFHAND_CLICK_MATRIX.md}.
 *
 * <p>A stock client decides by itself whether a right-click used the main hand — only a SUCCESS
 * from the main-hand item's own vanilla {@code use} stops {@code Minecraft.startUseItem} going on to
 * the offhand. So one click on a wished thing can also throw a snowball, empty a backpack, raise a
 * shield, start eating, or place a torch. The rows here are the kinds of wished item the code can
 * actually make, and each is built from the PRODUCTION give line ({@code MakeItemTool.itemArg}, parsed
 * with the game's own {@code ItemParser}), so a row tests the thing a child is handed rather than a
 * hand-assembled likeness of it. Shared by {@code OffhandClickMatrixGameTest} (both packets of a
 * click, through the real server path) and {@code lite/WatchPinStage} (the same cells, on a stock
 * client, filmed).
 */
public final class OffhandMatrix {

    private OffhandMatrix() {
    }

    /** The main hand's ability: a gold block under him, a bell, sparkles ({@code offhand_probe}). */
    public static final String PROBE_FN = ModdingFromAModMod.MODID + ":offhand_probe";

    /** The tag a PLACED row stamps on its anchor. */
    public static final String PLACE_TAG = "offhand_placed_here";

    /** The tag a THROWN row's projectile carries. */
    public static final String THROW_TAG = "offhand_thrown_fly";

    /**
     * The kinds of wished item. {@code letter} is the row in the design's order; a row two kinds
     * share one mechanism with is named in the doc rather than repeated here (a wished FOOD made
     * from scratch is a {@link #HELD} carrier whose function feeds him; {@code machine} and
     * {@code magic_block} are {@link #PLACED}'s anchor; {@code on_hit} is {@link #KEEPSAKE} to a
     * right-click).
     */
    public enum Row {
        /** (a) no ability: a keepsake ({@code plain}) on the carrier. */
        KEEPSAKE("a"),
        /** (b) an ability on right-click in the air ({@code held}) on the carrier. */
        HELD("b"),
        /** (b') the same wish built on a REAL item with no use of its own (a stick). */
        HELD_ON_STICK("b2"),
        /** (c) an ability on right-click at a block ({@code placed}; machine, magic block). */
        PLACED("c"),
        /** (d) already edible: a wish on real bread, run when he finishes it ({@code on_consume}). */
        REAL_FOOD("d"),
        /** (e) wearable, made from scratch: the carrier told it goes on his head. */
        WORN("e"),
        /** (f) thrown, on the carrier. */
        THROWN("f"),
        /** (g) a wished bag: a REAL bundle, holding things. */
        BAG("g"),
        /** (h) Moddy — the fixed reference (an earlier issue's stamp). */
        MODDY("h");

        public final String letter;

        Row(String letter) {
            this.letter = letter;
        }

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** What is in his offhand. */
    public enum Col {
        EMPTY, SNOWBALL, BUNDLE, SHIELD, BREAD, TORCH;

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    // ── the rule: one click, one hand ────────────────────────────────────────────────────────
    // Shared by the GameTest and the film stage, so a film cell and a GameTest cell are judged by
    // one definition. "block" = the click aimed at a block; otherwise at open air.

    /** Does a stock client stop at the main hand by itself (its own use of the main hand succeeds)? */
    public static boolean expectClientStops(Row r) {
        return switch (r) {
            // Real bread starts being eaten, a real bundle starts emptying, a worn thing is put on:
            // each is a SUCCESS on the client. Moddy carries an earlier issue's stamp where it exists.
            case REAL_FOOD, WORN, BAG -> true;
            case MODDY -> com.moddingfromamod.compat.Consumables.STAMPS;
            default -> false;
        };
    }

    /** What the main hand does on the click. */
    public static String expectMain(Row r, boolean block) {
        return switch (r) {
            case KEEPSAKE -> "-";
            case HELD, HELD_ON_STICK -> "ability";
            case PLACED -> block ? "placed" : "-";
            case REAL_FOOD -> "eating";
            case WORN -> "equipped";
            case THROWN -> "thrown";
            case BAG -> "emptying";
            case MODDY -> "panel";
        };
    }

    /** Did the server HANDLE the main hand's click: its own ability ran? */
    public static boolean handled(Row r, boolean block) {
        return switch (r) {
            case HELD, HELD_ON_STICK, THROWN, MODDY -> true;
            case PLACED -> block;
            default -> false;
        };
    }

    /** What the offhand does in a click nothing stops (an empty main hand's click). */
    public static String nativeOff(Col c, boolean block) {
        return switch (c) {
            case EMPTY -> "-";
            case SNOWBALL -> "thrown";
            case BUNDLE -> "emptying";
            case SHIELD -> "raised";
            case BREAD -> "eating";
            case TORCH -> block ? "placed" : "-";
        };
    }

    /** THE RULE: what the offhand does on a click with this row in the main hand. */
    public static String expectOff(Row r, Col c, boolean block) {
        if (expectClientStops(r) || handled(r, block)) {
            return "-";
        }
        return nativeOff(c, block);
    }

    /** What TODAY (before the backstop) did: the offhand ignored the main hand entirely. */
    public static String todayOff(Row r, Col c, boolean block) {
        return expectClientStops(r) ? "-" : nativeOff(c, block);
    }

    /** A filled backpack: a real bundle holding dirt and cobblestone (8 things). */
    public static ItemStack backpack() {
        ItemStack bag = new ItemStack(Items.BUNDLE);
        bag.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(
                new ItemStack(Items.DIRT, 5), new ItemStack(Items.COBBLESTONE, 3))));
        return bag;
    }

    /** How many things a bundle holds. */
    public static int inside(ItemStack bag) {
        BundleContents c = bag.get(DataComponents.BUNDLE_CONTENTS);
        int n = 0;
        if (c != null) {
            for (ItemStack s : c.items()) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** The offhand for a column, or null where this Minecraft has no such item (a bundle, 1.21.1). */
    public static ItemStack offhand(Col c) {
        return switch (c) {
            case EMPTY -> ItemStack.EMPTY;
            case SNOWBALL -> new ItemStack(Items.SNOWBALL, 8);
            case BUNDLE -> com.moddingfromamod.compat.Bundles.SUPPORTED ? backpack() : null;
            case SHIELD -> new ItemStack(Items.SHIELD);
            case BREAD -> new ItemStack(Items.BREAD, 5);
            case TORCH -> new ItemStack(Items.TORCH, 16);
        };
    }

    /**
     * The main hand for a row, built from the production give line, or null where this Minecraft
     * cannot make one (a bundle on 1.21.1). The carrier route must be on ({@code VanillaCarrier}).
     */
    public static ItemStack stack(Row r, HolderLookup.Provider lookup) {
        String probe = "\"" + PROBE_FN + "\"";
        return switch (r) {
            case KEEPSAKE -> give(lookup, "Lucky Pebble", null, "plain", feature(r));
            case HELD -> give(lookup, "Magic Wand", null, "held",
                    feature(r) + "," + WishItem.ON_USE + ":" + probe);
            case HELD_ON_STICK -> give(lookup, "Magic Stick", "minecraft:stick", "held",
                    feature(r) + "," + WishItem.ON_USE + ":" + probe);
            case PLACED -> give(lookup, "Magic Tent", null, "placed",
                    feature(r) + "," + WishItem.PLACE_TAG + ":\"" + PLACE_TAG + "\"");
            case REAL_FOOD -> give(lookup, "Magic Bread", "minecraft:bread", "on_consume",
                    feature(r) + "," + WishItem.CONSUME_FN + ":" + probe);
            case WORN -> give(lookup, "Magic Hat", null, "worn_head",
                    feature(r) + "," + WishItem.WORN + ":\"head\"");
            case THROWN -> give(lookup, "Magic Ball", null, "thrown",
                    feature(r) + "," + WishItem.THROW_TAG + ":\"" + THROW_TAG + "\","
                            + WishItem.HIT_FN + ":" + probe);
            case BAG -> {
                if (!com.moddingfromamod.compat.Bundles.SUPPORTED) {
                    yield null;
                }
                ItemStack bag = give(lookup, "Flower Bag", "minecraft:bundle", "plain", feature(r));
                if (bag != null) {
                    bag.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(
                            new ItemStack(Items.POPPY, 4))));
                }
                yield bag;
            }
            case MODDY -> com.moddingfromamod.wish.VanillaWatchItem.stack();
        };
    }

    private static String feature(Row r) {
        return WishItem.WISH_FEATURE + ":\"offhand_" + r.key() + "\"";
    }

    private static ItemStack give(HolderLookup.Provider lookup, String name, String base, String shape,
            String data) {
        String arg = com.moddingfromamod.agent.tools.MakeItemTool.itemArg(
                new com.moddingfromamod.agent.tools.MakeItemTool.Variant(name, 0x3AB3DA),
                3, "matrix", null, base, data, shape);
        try {
            var parsed = new net.minecraft.commands.arguments.item.ItemParser(lookup)
                    .parse(new com.mojang.brigadier.StringReader(arg));
            return new ItemStack(parsed.item(), 1, parsed.components());
        } catch (Exception e) {
            return null;
        }
    }
}
