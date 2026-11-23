package com.moddingfromamod.item.delivery;

import com.moddingfromamod.item.WishItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * <b>Is this a WISHED thing?</b> — the one question the Wish Bag asks of anything put into it
 *, answered in exactly one place.
 *
 * <p>A made item says so itself: everything {@code make_item}, a starter or a library entry mints
 * carries a {@code Wish…} key in {@code custom_data} ({@link WishItem#WISH_FEATURE},
 * {@link WishItem#ON_USE}, {@link WishItem#CARRIER}, …), and a creature's egg and every other mod
 * item is in our namespace. Moddy's own watch and the bag itself are Moddy's, never "wished": the
 * bag refuses them like anything else that is not his wished thing.
 *
 * <h2>⚠ The owner's open question, and the one line that answers it</h2>
 *
 * <p>A wish also hands out PLAIN vanilla items (the oasis wish's 32 melon slices). Two choices
 *: <b>(a)</b> tag them with a {@code custom_data} marker as they are handed over, so they
 * can go back in the bag (but no longer stack with his ordinary melon slices), or <b>(b)</b> leave
 * them untagged — they can come out of the bag, never go back in. <b>(b) is what ships</b> until the
 * owner says otherwise. Switching to (a) is {@link #TAG_PLAIN_GIFTS} = {@code true}: the delivery
 * service then stamps {@link #MARK} on every plain stack it hands over ({@link #markIfPlain}), and
 * this predicate already accepts the mark.
 */
public final class WishedItems {

    /** Choice (a) if true; (b), the shipped default, if false. The ONE line to change. */
    public static final boolean TAG_PLAIN_GIFTS = false;

    /** The marker choice (a) stamps on a plain vanilla stack a wish handed over. */
    public static final String MARK = "WishGift";

    /** Marks that mean "Moddy's own", never "his wished thing". */
    private static final String BAG = "WishBag";

    private WishedItems() {
    }

    /** True for a thing he wished for — and only such a thing may go into the Wish Bag. */
    public static boolean isWished(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            CompoundTag tag = data.copyTag();
            if (tag.contains(WishItem.WATCH) || tag.contains(BAG)) {
                return false;                  // Moddy's own, pinned elsewhere
            }
            for (String key : tag.keySet()) {
                if (key.startsWith("Wish")) {
                    return true;               // WishItem, WishOnUse, WishCarrier, WishGift, …
                }
            }
        }
        // A creature's egg and every other mod item: only ever made by a wish.
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && com.moddingfromamod.ModdingFromAModMod.MODID.equals(id.getNamespace());
    }

    /** Choice (a): stamp {@link #MARK} on a plain stack being handed over. A no-op under (b). */
    public static void markIfPlain(ItemStack stack) {
        if (!TAG_PLAIN_GIFTS || stack == null || stack.isEmpty() || isWished(stack)) {
            return;
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(MARK, true));
    }
}
