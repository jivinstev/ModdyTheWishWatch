package com.moddingfromamod.item;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ItemStackedOnOtherEvent;

import java.util.Set;

/**
 * <b>A bag for one kind of thing.</b> A real bundle carrying {@code holds_only}
 * (an item tag from {@code ItemProperties.HOLDS_ONLY}) refuses anything outside that tag, in both
 * directions a bundle is filled: a thing clicked ONTO the bag, and the bag clicked onto a thing.
 * Taking things out is never stopped. Everything else is the real bundle's own — its tooltip, its
 * weight, its "one stack's worth".
 *
 * <p>The event fires before either of the bundle's own overrides, and cancelling it leaves the
 * cursor and the slot as they were, so a refused stone stays on his cursor. It is not fired on the
 * server for the creative inventory, which is the client's alone — a survival bag is the wish.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class OneKindBag {

    /** E1's label: the backpack row names {@code inventory_filter} (syn-5-034). */
    public static final Set<String> PARTS = Set.of("inventory_filter");

    private OneKindBag() {
    }

    @SubscribeEvent
    public static void onStacked(ItemStackedOnOtherEvent event) {
        if (refuses(event.getCarriedItem(), event.getStackedOnItem())
                || refuses(event.getStackedOnItem(), event.getCarriedItem())) {
            event.setCanceled(true);
        }
    }

    /** Would this bag refuse to take this thing? A thing it does not govern is never refused. */
    public static boolean refuses(ItemStack bag, ItemStack thing) {
        if (bag.isEmpty() || thing.isEmpty()) {
            return false;
        }
        String tag = WishItem.holdsOnly(bag);
        if (tag.isEmpty()) {
            return false;
        }
        for (String one : tag.split(",")) {
            if (thing.is(TagKey.create(Registries.ITEM, ResourceLocation.parse(one.trim())))) {
                return false;
            }
        }
        return true;
    }
}
