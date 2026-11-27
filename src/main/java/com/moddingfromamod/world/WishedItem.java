package com.moddingfromamod.world;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * <b>Did a player's wish make this item stack, and whose wish was it?</b> The item twin of {@link Wished}: the one
 * documented way for a world to tell a stack a wish made from any other item, whatever handed it over (a Builder or
 * Apprentice make, "Make another one", a hotbar fire, "Give me one", the Magician's {@code make_item}). Read this
 * rather than the component: its shape may change.
 *
 * <p>The core stamps it as the stack is handed over ({@code WishDelivery.give}): the wish's id and the UUID of the
 * player whose wish it was, in the stack's {@code minecraft:custom_data} (a component every stock client accepts),
 * under {@link #KEY}. NEVER the player's words, nor any name he typed: ids only.
 *
 * <p><b>A plain vanilla gift is not stamped</b> (a wish's 32 melon slices): a mark on it would stop it stacking with
 * his own melon slices, which the owner decided against ({@code WishedItems.TAG_PLAIN_GIFTS}, false). So this answers
 * "not wish-made" for a plain gift; only an item a wish MADE (it already carries the wish's own data and never stacks
 * with a vanilla one) is stamped. If that switch is ever turned on, plain gifts are stamped too.
 */
public final class WishedItem {

    /**
     * What a "Give me one" stamps as the wish: no new wish made it, so it names the kept thing it was given again
     * ({@code again:<its key>}), with the player it was given to as the maker.
     */
    public static final String AGAIN = "again:";

    /** The {@code custom_data} key the core stamps. Read {@link #madeBy}, never this. */
    public static final String KEY = "ModdyMadeBy";

    private WishedItem() {
    }

    /** Who made it: the wish's id, and the UUID of the player whose wish it was. */
    public record Made(String wishId, UUID maker) {
    }

    /** The wish that made {@code stack}, or empty for anything else (a plain gift, a found item, another mod's). */
    public static Optional<Made> madeBy(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return Optional.empty();
        }
        CompoundTag tag = data.copyTag();
        CompoundTag mark = tag.getCompoundOrEmpty(KEY);
        return of(mark.getStringOr("wish", ""), mark.getStringOr("maker", ""));
    }

    /** The decision on the two stamped strings alone: both present, the maker a UUID. Pure. */
    public static Optional<Made> of(String wishId, String maker) {
        if (wishId == null || wishId.isBlank() || maker == null || maker.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Made(wishId, UUID.fromString(maker)));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    /** Stamp {@code stack} as made by wish {@code wishId} of {@code maker}'s (the core, at hand-over). */
    public static void stamp(ItemStack stack, String wishId, UUID maker) {
        if (stack == null || stack.isEmpty() || wishId == null || wishId.isBlank() || maker == null) {
            return;
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag mark = new CompoundTag();
            mark.putString("wish", wishId);
            mark.putString("maker", maker.toString());
            tag.put(KEY, mark);
        });
    }
}
