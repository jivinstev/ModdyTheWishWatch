package com.moddingfromamod.compat;

/**
 * <b>Can a wished thing be WORN on this Minecraft?</b> (1.21.4 half.)
 *
 * <p>Yes: 1.21.2 added the {@code minecraft:equippable} component, so any stack — our carrier
 * included — can name the slot it goes in and the equipment asset drawn on the body, and
 * {@code minecraft:glider} makes any chest piece an elytra. The 1.21.1 half answers no, and the worn
 * shapes are then REFUSED by name (never silently built as something held), because 1.21.1 has no
 * way to put a thing made from scratch on the body at all.
 */
public final class Wearables {
    private Wearables() {
    }

    public static final boolean SUPPORTED = true;

    /**
     * The armour slot a real item already goes in ({@code head}, {@code chest}, {@code legs},
     * {@code feet}, {@code body}…), or null when it goes in none or when there is no game to ask.
     */
    public static String slotOf(String itemId) {
        try {
            var key = net.minecraft.resources.Identifier.tryParse(itemId);
            if (key == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(key)) {
                return null;
            }
            var eq = new net.minecraft.world.item.ItemStack(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(key))
                    .get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
            return eq == null ? null : eq.slot().getSerializedName();
        } catch (Throwable outsideTheGame) {
            // Same reason as MakeItemTool.ownModifiers: a unit test has no registries, and a
            // bootstrap failure there is an Error, not an Exception.
            return null;
        }
    }

    /**
     * What this stack says about being worn, as {@code "<slot> <asset>"} (the asset is
     * {@code none} when nothing is drawn on the body), or null when it cannot be worn at all.
     * For gates, which must compile on 1.21.1 where the component does not exist.
     */
    public static String describe(net.minecraft.world.item.ItemStack stack) {
        var eq = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
        if (eq == null) {
            return null;
        }
        return eq.slot().getSerializedName() + " "
                + eq.assetId().map(k -> k.identifier().toString()).orElse("none");
    }

    /** Does this stack glide like an elytra? */
    public static boolean glides(net.minecraft.world.item.ItemStack stack) {
        return stack.has(net.minecraft.core.component.DataComponents.GLIDER);
    }
}
