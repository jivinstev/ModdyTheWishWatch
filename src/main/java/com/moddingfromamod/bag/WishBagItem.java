package com.moddingfromamod.bag;

import com.moddingfromamod.datapack.VanillaCarrier;
import com.moddingfromamod.item.WishItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemLore;

/**
 * <b>The Wish Bag as something a stock client can hold and right-click</b> — pinned
 * in hotbar slot 2 beside Moddy.
 *
 * <p>Minted exactly as the watch is ({@code VanillaWatchItem.stack}), because every reason the
 * watch is built the way it is applies to the bag word for word: it rides the vanilla carrier with
 * its own number ({@link VanillaCarrier#WISH_BAG_MODEL_DATA}) so a client with no mod draws it from
 * the pushed pack; it is dyed WHITE because a tint repaints finished art; its "+3 Armor" is
 * neutralised; it stops the click at the main hand so a right-click cannot also use his offhand;
 * and it carries {@link WishItem#CARRIER} (what the right-click bridge gates on) plus its own mark,
 * {@link #MARK}, stamped here and read nowhere but {@link #isBag}.
 *
 * <p>The bag holds NOTHING itself — its contents are the server's ({@link WishBagService}), so the
 * item can be re-minted every tick by the pin without losing a thing, and a stock client never
 * sees a {@code bundle_contents} it could empty onto the floor.
 */
public final class WishBagItem {

    /** The mark that says "this carrier is the Wish Bag". */
    public static final String MARK = "WishBag";

    private WishBagItem() {
    }

    /** A bag, minted as a carrier. */
    public static ItemStack stack() {
        ItemStack s = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getValue(net.minecraft.resources.ResourceLocation.parse(VanillaCarrier.ITEM_ID)));
        com.moddingfromamod.compat.ModelData.stamp(s, VanillaCarrier.WISH_BAG_MODEL_DATA);
        com.moddingfromamod.compat.ItemComponents.dyeHidden(s, 0xFFFFFF);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(WishBagWords.NAME)
                .setStyle(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE).withItalic(false)));
        // the last line is the rule, there every time he hovers it.
        s.set(DataComponents.LORE, new ItemLore(WishBagWords.LORE.stream()
                .map(line -> (Component) Component.literal(line)
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false)))
                .toList()));
        s.set(DataComponents.ATTRIBUTE_MODIFIERS,
                com.moddingfromamod.wish.VanillaWatchItem.inertArmour("wish_bag_inert"));
        com.moddingfromamod.compat.ItemComponents.hideLine(s, DataComponents.ATTRIBUTE_MODIFIERS);
        com.moddingfromamod.compat.Consumables.stopsTheClick(s);
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(WishItem.CARRIER, true);
        tag.putBoolean(MARK, true);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    /** True if this stack is a bag we minted. Cheap for the common case: asked of 41 slots a tick. */
    public static boolean isBag(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(MARK) && data.copyTag().getBooleanOr(MARK, false);
    }
}
