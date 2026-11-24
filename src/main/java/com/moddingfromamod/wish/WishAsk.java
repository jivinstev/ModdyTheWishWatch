package com.moddingfromamod.wish;

import java.util.Objects;
import java.util.UUID;

/**
 * <b>What a world's {@link WishCharge} is asked about</b>: one value, the same shape at every hook that takes it, so a
 * field added later never changes a hook's signature. Immutable; build one with {@link #of} and the {@code with…}
 * methods, never the canonical constructor (its parameters grow as fields are added).
 *
 * @param player   whose wish it is
 * @param source   where the wish came from; a hotbar proxy's fire and its landing in an inventory are
 *                 {@link WishSource#PRESET}, the source {@code /moddy again} submits with
 * @param recipe   the id the core makes the thing again by: the recipe id {@code /moddy again} takes (a recipe, or
 *                 {@code builder:<make id>} for a kept Builder make, also the one a Builder edit changes), the key
 *                 {@code /moddy give} takes for "Give me one", or {@code ""} when there is none
 * @param magic    what kind of magic grants it
 * @param text     the wish's words as the core holds them (sanitised typed words, a recipe's words, a Builder make's
 *                 {@code Builder: …} line), or {@code ""}
 * @param distress the safety lexicon read these words as a child in distress: such a wish is never refused (the core
 *                 lets it through whatever a charge answers)
 */
public record WishAsk(UUID player, WishSource source, String recipe, Magic magic, String text, boolean distress) {

    /** The kind of magic a wish is granted by. */
    public enum Magic {
        /** A make built from the Builder's parts (also a Builder change on a thing the Magician made). */
        BUILDER,
        /**
         * Typed words answered locally: the Apprentice's page of makes, a make picked from it, or the re-wish reader
         * changing one of his things.
         */
        APPRENTICE,
        /** Typed words the Magician's model will grant. */
        MAGICIAN,
        /**
         * A thing made again from its own recipe, with no model: a recipe button, a cached recipe his words matched, a
         * hotbar proxy, "Make another one", "Give me one".
         */
        REPLAY
    }

    public WishAsk {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(magic, "magic");
        recipe = recipe == null ? "" : recipe;
        text = text == null ? "" : text;
    }

    /**
     * A wish of {@code player}'s from {@code source}, granted by {@code magic}, with no recipe, no words and no distress.
     * The kind of magic is always named by the caller: there is no default to assume.
     */
    public static WishAsk of(UUID player, WishSource source, Magic magic) {
        return new WishAsk(player, source, "", magic, "", false);
    }

    /** A kept thing made again from {@code recipe}: what a hotbar proxy asks about. */
    public static WishAsk replay(UUID player, String recipe) {
        return of(player, WishSource.PRESET, Magic.REPLAY).withRecipe(recipe);
    }

    /**
     * Was the wish's recipe named by what started it (a button, an "again", a hotbar fire, {@code /moddy try}), or is it
     * a Builder or Apprentice make? What {@link WishCharge#refusesAtSubmit}'s {@code recipe} flag has always said, so
     * the default {@link WishCharge#refuses} passes an older charge exactly what it was passed before. False for typed
     * words, also when a cached recipe his words matched will serve them ({@link Magic#REPLAY} from a typed or command
     * source, with that recipe's id).
     */
    public boolean namedRecipe() {
        if (source == WishSource.BUILDER || magic == Magic.BUILDER) {
            return true;
        }
        return magic == Magic.REPLAY && (source == WishSource.PRESET || recipe.isEmpty());
    }

    public WishAsk withRecipe(String recipe) {
        return new WishAsk(player, source, recipe, magic, text, distress);
    }

    public WishAsk withMagic(Magic magic) {
        return new WishAsk(player, source, recipe, magic, text, distress);
    }

    public WishAsk withText(String text) {
        return new WishAsk(player, source, recipe, magic, text, distress);
    }

    public WishAsk withDistress(boolean distress) {
        return new WishAsk(player, source, recipe, magic, text, distress);
    }
}
