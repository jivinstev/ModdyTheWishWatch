package com.moddingfromamod.wish;

import java.util.Objects;
import java.util.UUID;

/**
 * <b>What a world's {@link WishCharge} is asked about</b>: one value, the same shape at every hook that takes it, so a
 * field added later never changes a hook's signature. Immutable; build one with {@link #of} and the {@code with…}
 * methods, never the canonical constructor (its parameters grow as fields are added).
 *
 * @param player whose wish it is
 * @param source where the wish came from; a hotbar proxy's fire and its landing in an inventory are
 *               {@link WishSource#PRESET}, the source {@code /moddy again} submits with
 * @param recipe the recipe id {@code /moddy again} takes, or {@code ""} when there is none
 * @param magic  what kind of magic grants it
 */
public record WishAsk(UUID player, WishSource source, String recipe, Magic magic) {

    /** The kind of magic a wish is granted by. */
    public enum Magic {
        /** A make built from the Builder's parts. */
        BUILDER,
        /** A make the Apprentice picked for typed words. */
        APPRENTICE,
        /** A wish the Magician's model grants. */
        MAGICIAN,
        /** A kept thing made again from its own recipe (a hotbar proxy, "Make another one"). */
        REPLAY
    }

    public WishAsk {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(magic, "magic");
        recipe = recipe == null ? "" : recipe;
    }

    /** A wish of {@code player}'s from {@code source}: no recipe, granted by the Magician until said otherwise. */
    public static WishAsk of(UUID player, WishSource source) {
        return new WishAsk(player, source, "", Magic.MAGICIAN);
    }

    /** A kept thing made again from {@code recipe}: what a hotbar proxy asks about. */
    public static WishAsk replay(UUID player, String recipe) {
        return of(player, WishSource.PRESET).withRecipe(recipe).withMagic(Magic.REPLAY);
    }

    public WishAsk withRecipe(String recipe) {
        return new WishAsk(player, source, recipe, magic);
    }

    public WishAsk withMagic(Magic magic) {
        return new WishAsk(player, source, recipe, magic);
    }
}
