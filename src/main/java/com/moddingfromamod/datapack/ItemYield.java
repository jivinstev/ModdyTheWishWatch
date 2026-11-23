package com.moddingfromamod.datapack;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;

/**
 * <b>YIELD — what using it LEAVES BEHIND.</b>
 *
 * <p>The wish is <i>"a pizza you can cut into 8 slices"</i>, and until now the engine could give him
 * a pizza or give him slices and never the one turning into the other. Every other axis describes
 * the thing he is handed; this one is the only place an item takes something away, which is why it
 * carries more refusals than the rest put together.
 *
 * <p><b>What the thing left behind is.</b> Always a {@code plain} item — a slice does not itself cut
 * into anything — which keeps the axis flat instead of recursive and lets it reuse the give builder
 * unchanged. Name, icon, colour and count, all required: a yielded thing he cannot recognise in his
 * inventory is indistinguishable from the pizza having simply vanished.
 *
 * <p><b>Why it is refused on three of the five shapes.</b> YIELD means <i>he used it and it turned
 * into something</i>, so it needs a moment of use to hang off: {@code thrown} is already gone when
 * it lands, {@code placed} became a block and its function runs on a clock — a yield there would
 * fire every two seconds, forever — and {@code plain} has no {@code act} at all. Rendering it
 * anyway would compile perfectly and produce an item that quietly duplicates slices.
 */
public final class ItemYield {

    private ItemYield() {
    }

    /** The shapes with a single moment of use for a yield to hang off. */
    public static final List<String> SHAPES = List.of("held", "on_hit");

    private static final int MAX_COUNT = 64;

    /**
     * Everything wrong with this {@code yield}, in the agent's terms, or empty.
     *
     * @param hasVariants whether the wish also asks for a family — see the refusal for why not
     */
    public static List<String> refusals(JsonObject y, String shape, boolean hasVariants) {
        List<String> bad = new ArrayList<>();
        if (y == null) {
            return bad;
        }
        if (!SHAPES.contains(shape)) {
            bad.add("a `yield` needs a moment where he USES the thing, and shape '" + shape
                    + "' has none — a thrown one is already gone when it lands, a placed one runs on"
                    + " a clock and would yield again every two seconds, and a plain one is never"
                    + " used at all. Use " + String.join(" or ", SHAPES) + ", or drop the yield.");
        }
        if (hasVariants) {
            // The clear is by the item's behaviour tag, which every member of a family shares, so
            // using one member would consume whichever member came to hand. Refused rather than
            // silently imprecise: "a set of things that each turn into something else" is more than
            // one wish, and answering it wrongly is worse than saying so.
            bad.add("a `yield` and a set of `variants` cannot be combined: the members of a family "
                    + "share one behaviour, so using one of them would take away whichever one "
                    + "happened to be nearest in his bag. Ask him which single thing turns into "
                    + "something else.");
        }
        for (String field : List.of("name", "icon", "colour")) {
            if (!y.has(field) || y.get(field).getAsString().isBlank()) {
                bad.add("yield needs a " + field + " — what he ends up holding has to be something "
                        + "he can recognise, or the thing he used just disappeared.");
            }
        }
        if (y.has("icon") && !ItemCatalogue.ICONS.containsKey(y.get("icon").getAsString())) {
            bad.add("yield asks for icon '" + y.get("icon").getAsString() + "', which does not "
                    + "exist. The ones that do: " + String.join(", ", ItemCatalogue.ICONS.keySet()));
        }
        Integer count = count(y);
        if (count == null || count < 1 || count > MAX_COUNT) {
            bad.add("yield needs a count, 1 to " + MAX_COUNT + " — how many of them he gets. "
                    + "\"cut into 8 slices\" is 8.");
        }
        return bad;
    }

    /** How many of the yielded thing he gets, or null when it was not a whole number. */
    public static Integer count(JsonObject y) {
        if (y == null || !y.has("count") || !y.get("count").isJsonPrimitive()) {
            return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(y.get("count").getAsString().trim()));
        } catch (NumberFormatException bad) {
            return null;
        }
    }

    /**
     * The {@code clear} argument that takes away THIS item and no other wish item.
     *
     * <p>Matched on the behaviour tag in {@code custom_data} rather than on the name: the tag names
     * the feature, so it is unique to this wish, while a name is a text component whose exact JSON
     * a predicate would have to reproduce byte for byte.
     *
     * <p>⚠ The {@code ~} is a PARTIAL match, and it has to be. An {@code =} would demand the whole
     * {@code custom_data} object be equal, which fails the moment anything else is written into it.
     */
    public static String clearPredicate(String shape, String feature, String shapeData,
                                        String baseItem) {
        if (!SHAPES.contains(shape)) {
            throw new IllegalStateException("no yield on shape " + shape);
        }
        // NAMES THE SAME ITEM THE GIVE DID. A predicate that says wish_item while the give handed
        // out a golden axe matches nothing, so the axe is never consumed and he keeps both it and
        // whatever it turned into -- silently, because a clear that removes nothing is not an error.
        return ItemBase.id(baseItem) + "[minecraft:custom_data~{" + shapeData + "}]";
    }
}
