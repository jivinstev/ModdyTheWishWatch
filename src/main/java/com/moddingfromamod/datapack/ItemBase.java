package com.moddingfromamod.datapack;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * <b>WHICH ITEM a wish is built on.</b>
 *
 * <p>Every axis on {@code make_item} — the trigger, the effects, the properties, the parts, the
 * yield — used to apply to exactly one item, because {@code gives()} wrote
 * {@code moddingfromamod:wish_item} into the give line and nothing could change it. That is a
 * capability shaped like a special case: <i>"make my diamond sword unbreakable"</i> and
 * <i>"give the piglin a gold axe that never brakes"</i> are ordinary item wishes about items this
 * engine did not mint, and none of the machinery reached them.
 *
 * <p><b>So the base item is an axis of its own, and the default is what it always was.</b> Name any
 * item and the whole apparatus applies to it: the child's name, the lore, the behaviour tag that
 * makes a right-click run a function, the static properties. Name nothing and it is a wish item, so
 * every wish ever granted takes exactly the path it always did.
 *
 * <p><b>Two components are wish-item-only and are dropped for anything else.</b>
 * {@code custom_model_data} selects out of OUR icon library and means nothing on a golden axe, and
 * {@code dyed_color} tints a greyscale sprite that a real item does not have. Writing them anyway
 * would be harmless and misleading — the tool would report a colour the child cannot see.
 *
 * <p><b>Why {@code unbreakable} needed this.</b> The component is inert on {@code wish_item},
 * which has no durability, and it was written up as a fork between adding a {@code max_damage}
 * component and handing out a real item. That was a false dilemma: it is one property that works
 * wherever there is durability to protect, and what was actually missing was the ability to say
 * WHICH item — which is this.
 */
public final class ItemBase {

    private ItemBase() {
    }

    /** The default, and what every wish before this axis was built on. */
    public static final String WISH_ITEM = "moddingfromamod:wish_item";

    /**
     * A registry id and nothing else — the same discipline as {@link ItemRecipient}. This string is
     * spliced in front of a component list, so a bracket or a space in it would end the item and
     * start something arbitrary.
     */
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** Whether this names an item made from scratch rather than one the child named. */
    public static boolean isWishItem(String baseItem) {
        return baseItem == null || baseItem.isBlank() || WISH_ITEM.equals(baseItem);
    }

    /**
     * <b>Whether the icon-and-tint apparatus applies</b> — which is a different question from
     * {@link #isWishItem}, and telling them apart is the whole of issue A1.
     *
     * <p>{@code isWishItem} asks <i>"is this our registered item"</i>. What {@code itemArg} needs to
     * know is <i>"does this base wear our icon library"</i>, and a {@link VanillaCarrier} is a
     * <b>vanilla</b> item that does. The two questions agreed for as long as there was only one
     * item that wore our icons, exactly the way {@code TrainFraming}'s axis was right for as long
     * as a train could only run east.
     *
     * <p><b>Narrower than it first looked, and worth being exact about.</b> On the ordinary path
     * {@code base_item} is absent, so {@code isWishItem(null)} is already true and the icon is
     * written — the substitution happens later, in {@link #id}. What this fixes is the case where
     * the carrier is named EXPLICITLY as a base item, and, more importantly, it is what makes the
     * obvious alternative implementation safe: had the carrier been introduced by passing its id as
     * {@code base_item}, every wished item would have silently lost its icon and its colour.
     */
    // TRANSITIONAL — carrier spike. Not production. See docs/VANILLA_CARRIER_TRANSITIONAL.md
    // SEAM: already reduced to isWishItem after A1 was retracted; kept for the NAME.
    public static boolean wearsOurIcons(String baseItem) {
        // ⚠ THIS USED TO CARRY `|| (VanillaCarrier.enabled() && isCarrierId(baseItem))` AND THAT
        // CLAUSE WAS BOTH DEAD AND HARMFUL. It was added for A1, whose stated failure — "every
        // wished item on the Lite path renders as a plain leather horse armour" — was never real:
        // MakeItemTool.itemArg is handed the RAW base and resolves the id itself one line later,
        // so our own substitution arrives here as `null` and `isWishItem` already answers true.
        //
        // The clause could therefore only fire for a base the CHILD named — `base_item` takes any
        // item id — and then it put our icon and our colour on his deliberate "magic horse
        // armour". Same root as the inert-attribute bug in MakeItemTool: a predicate that cannot
        // tell "we substituted the carrier" from "he asked for that item".
        //
        // Kept as a named method rather than inlined because the NAME is the question worth
        // asking at the call site; it simply has one honest answer.
        return isWishItem(baseItem);
    }

    /**
     * The id to write into the give line — and the <b>one</b> place a wished thing becomes a
     * vanilla carrier.
     *
     * <p>Every give line and every match predicate on the {@code make_item} path already resolves
     * through here, so swapping the id here swaps it consistently: an item and the predicate that
     * finds it again cannot disagree. Doing it in the agent's prompt instead would have meant
     * recalibrating the wish prompt, which the spike is not allowed to do — see
     * {@link VanillaCarrier}.
     *
     * <p>A base item the child NAMED is never substituted. <i>"Make my diamond sword
     * unbreakable"</i> is about his sword; handing back a horse armour would be worse than anything
     * this route is trying to fix.
     */
    // TRANSITIONAL — carrier spike. Not production. See docs/VANILLA_CARRIER_TRANSITIONAL.md
    // SEAM: exists only while two paths coexist. One path wins -> inline to WISH_ITEM.
    public static String id(String baseItem) {
        return isWishItem(baseItem) ? VanillaCarrier.itemId() : baseItem;
    }

    /**
     * Everything wrong with this base item, in the agent's terms, or empty.
     *
     * <p><b>Two checks, and only one of them can be made off-game.</b> The SHAPE of the id is pure.
     * Whether the item actually exists needs the live registry, and whether a component combination
     * is legal needs the item's own default components — a golden axe carries {@code max_damage} by
     * default, so asking for a stack of 64 of them is <i>"Item cannot be both damageable and
     * stackable"</i> and the give fails silently. Both of those are asked in
     * {@code ItemPropertiesGameTest}, where a real registry exists.
     */
    public static List<String> refusals(String baseItem) {
        return refusals(baseItem, RegistryExists.ALWAYS);
    }

    /**
     * The same, with the live registry consulted — the second of the two checks this class's own
     * doc has always named, and which nothing on the {@code make_item} path ever asked.
     *
     * <p>A base item that does not exist is the worst of the three failures here, because the whole
     * wish is built on it: the give line names a thing the game has never heard of, {@code /give}
     * says nothing, and the child is handed an item that does not arrive at all.
     */
    public static List<String> refusals(String baseItem, RegistryExists registry) {
        List<String> bad = new ArrayList<>();
        if (isWishItem(baseItem)) {
            return bad;
        }
        if (!ITEM_ID.matcher(baseItem).matches()) {
            bad.add("base_item must be a full item id like minecraft:golden_axe, all lowercase. '"
                    + baseItem + "' is not one. Leave it out entirely for an item made from "
                    + "scratch, which is what nearly every wish wants.");
            return bad;
        }
        // A MADE ITEM CANNOT BE A BASE. base_item exists to build ON something real; the wish item
        // is what everything we make already IS, and naming a feature id here would be asking to
        // build one of ours on another of ours — which is what `start_from` is for.
        if (ItemRef.parse(baseItem).kind() == ItemRef.Kind.WISHED) {
            bad.add("base_item names '" + baseItem + "', which is something YOU made rather than a "
                    + "real item from the game. base_item is for building on a real thing — "
                    + "minecraft:golden_axe, or an item from a mod. To change something you already "
                    + "made, pass `start_from` instead and send only the fields that change.");
            return bad;
        }
        if (com.moddingfromamod.wish.ForbiddenBlocks.isForbiddenId(baseItem)) {
            bad.add("base_item names '" + baseItem + "'. "
                    + com.moddingfromamod.wish.ForbiddenBlocks.refusal(baseItem));   //
            return bad;
        }
        if (!registry.has(baseItem)) {
            bad.add("base_item names '" + baseItem + "', and there is no such item in the game. "
                    + "Check the spelling and the namespace — an item from a mod is only there if "
                    + "that mod is loaded. Nothing reports a miss: the give line names a thing the "
                    + "game has never heard of and he is handed nothing at all.");
        }
        return bad;
    }
}
