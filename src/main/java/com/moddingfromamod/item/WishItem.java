package com.moddingfromamod.item;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * <b>One registered item that any "thing" wish can wear and place.</b>
 *
 * <p>The same shape as {@link WishEggItem}: a single registered item whose identity is data on the
 * stack, so unlimited wishes cost one registry entry. It exists because the placeable-things spike
 * found the two limits a datapack cannot cross, and they turn out to be the same limit:
 *
 * <ul>
 *   <li><b>The icon.</b> A datapack can only tint what vanilla already tints, and the item it must
 *       use for placement — an armour stand — is not on that list. So a datapack-only placeable is
 *       stuck looking like an armour stand. See docs/ITEM_ICONS.md.</li>
 *   <li><b>The creative menu.</b> {@code CREATIVE_MODE_TAB} is a built-in registry and appears
 *       nowhere in {@code RegistryDataLoader}, so no datapack item can ever be searchable.</li>
 * </ul>
 *
 * <p>This crosses both at once, and gives up nothing the datapack was relying on. Right-clicking a
 * block face drops the <b>same</b> invisible, tagged, player-facing marker that {@code ArmorStandItem}
 * would have — the anchor every placeable datapack is built around — so an existing artifact swaps to
 * it by changing which item it hands out and nothing else.
 *
 * <p><b>The wish supplies both halves as plain data on the stack:</b>
 * {@code minecraft:custom_model_data} picks the artwork from the shipped library, and
 * {@code minecraft:custom_data} carries {@code {WishPlaceTag: "..."}} — the tag to stamp on the
 * anchor, which is what the datapack then watches for.
 */
public class WishItem extends com.moddingfromamod.compat.UseItem {

    /** The stack key naming the tag to stamp on whatever this item puts down. */
    public static final String PLACE_TAG = "WishPlaceTag";
    /** A datapack function to run when the item is used. This is what makes it a THING THAT DOES. */
    public static final String ON_USE = "WishOnUse";
    /** A tag to stamp on a thrown projectile, for the whole throwable-item wish class. */
    public static final String THROW_TAG = "WishThrowTag";
    /** A function to run AT THE POINT OF IMPACT when a thrown thing lands. */
    public static final String HIT_FN = "WishHitFunction";

    /**
     * The function to run when the holder HITS something with this — the melee counterpart of
     * {@link #HIT_FN}, which is a thrown item's impact.
     *
     * <p>Deliberately its own key rather than reusing {@code WishHitFunction}: a wish can be both
     * ("throw it, and also swing it"), and one key would make the two indistinguishable to
     * {@code WishItemCatalogue.keyOf}, which identifies an item BY its behaviour tag.
     */
    public static final String ON_HIT = "WishOnHit";

    /**
     * WHERE a worn thing goes — {@code head}, {@code chest}, {@code legs} or {@code feet}
     *. Not a function to run: the item's own datapack clock watches that armour slot. It is
     * here so a stack says what KIND of thing it is, the way the other four tags do.
     */
    public static final String WORN = "WishWorn";

    /** A function to run where an ARROW this bow (crossbow, trident) fired lands. */
    public static final String ARROW_FN = "WishArrowFunction";
    /** The function a weapon runs the moment it shoots, as the shooter (on_shot). */
    public static final String SHOT_FN = "WishShotFunction";
    /** The function a food or drink runs when he has FINISHED it, as him (on_consume). */
    public static final String CONSUME_FN = "WishConsumeFunction";
    /** The function a shield runs each time it BLOCKS a hit, as the one holding it (on_block). */
    public static final String BLOCK_FN = "WishBlockFunction";
    /** Where a real throwable LANDS, as the thrower (on_throw_land). */
    public static final String LAND_FN = "WishLandFunction";
    /** When a long use (a horn, a brush, a spyglass) is DONE, as him (on_use_done). */
    public static final String USE_DONE_FN = "WishUseDoneFunction";

    /** A function to run at each block he BREAKS with this in his hand. */
    public static final String ON_MINE = "WishOnMine";

    /**
     * WHICH made item this is — the {@code make_item} feature id, stamped on every item the engine
     * makes and on nothing else.
     *
     * <p>Added. Every item made from scratch shares one registry id
     * ({@code moddingfromamod:wish_item}); its name, icon and colour are components. So until this
     * existed there was <b>no way to say "his Hot Coffee" as opposed to "a wish item"</b> — which is
     * why {@code convert}'s INPUT could not name one (it would have eaten his wand), why
     * {@code rich} could only hand over a blank, and why {@code WishItemCatalogue.keyOf} said in its
     * own comment that a plain keepsake has <i>"nothing stable to key on, so an icon change on one
     * of these still makes a second row"</i>.
     *
     * <p><b>Additive and backward-compatible on purpose.</b> An item made before this carries no
     * such tag, and every path still works on it — it simply cannot be named by feature, exactly as
     * before. Nothing in a child's existing world breaks.
     */
    public static final String WISH_FEATURE = "WishItem";


    public WishItem(Properties properties) {
        super(properties);
    }

    /** Build a stack: which icon to wear, and which tag to stamp on what it places. */
    public static ItemStack of(Item item, int iconId, String placeTag) {
        ItemStack stack = new ItemStack(item);
        com.moddingfromamod.compat.ModelData.stamp(stack, iconId);
        CompoundTag tag = new CompoundTag();
        tag.putString(PLACE_TAG, placeTag == null ? "" : placeTag);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    /** A stack that RUNS something when used — the "item with an effect" shape. */
    public static ItemStack doing(Item item, int iconId, String functionId, int tint) {
        ItemStack stack = new ItemStack(item);
        com.moddingfromamod.compat.ModelData.stamp(stack, iconId);
        com.moddingfromamod.compat.ItemComponents.dyeHidden(stack, tint);
        CompoundTag tag = new CompoundTag();
        tag.putString(ON_USE, functionId == null ? "" : functionId);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    /** A stack that is THROWN, tagging its projectile so a datapack can act on it. */
    public static ItemStack thrown(Item item, int iconId, String tag, int tint) {
        ItemStack stack = new ItemStack(item);
        com.moddingfromamod.compat.ModelData.stamp(stack, iconId);
        com.moddingfromamod.compat.ItemComponents.dyeHidden(stack, tint);
        CompoundTag t = new CompoundTag();
        t.putString(THROW_TAG, tag == null ? "" : tag);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        return stack;
    }

    /**
     * <b>Notice a wished thing, so it stays findable after the child loses it.</b>
     *
     * <p>Recording happens here rather than where the item is handed out because there is no single
     * such place: a wish hands one over from a datapack's {@code /give}, written as CBScript text by
     * the agent, and the mod never sees that call. Whatever route it took, it ends up in an inventory
     * and ticks — so this catches every route, including routes nobody has written yet.
     *
     * <p><b>Checked once a second, not every tick.</b> A thing's identity includes what it DOES
     * ({@link WishItemCatalogue#keyOf}), which costs a string to read exactly. Hashing it instead
     * would be cheaper and can collide, and a collision here does not throw — it silently drops an
     * item from the creative menu, which is the exact disappearance this whole feature exists to
     * prevent. So the cost is paid, twenty times less often. A wished thing becoming searchable up to
     * a second after it lands is not a difference anybody can perceive.
     */
    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity,
            net.minecraft.world.entity.EquipmentSlot slot) {
        super.inventoryTick(stack, level, entity, slot);
        if (!(level instanceof ServerLevel serverLevel) || !(entity instanceof Player)) {
            return;
        }
        if (level.getGameTime() % 20L != 0L) {
            return;
        }
        if (WishItemCatalogue.knows(stack)) {
            return;
        }
        // filed under the child whose bag it is in (or its design's owner), never under
        // "the wish in flight", which from a tick is a guess — another child's, or nobody's.
        if (WishItemCatalogue.remember(serverLevel.getServer(), stack,
                entity.getStringUUID())) {
            // Guarded broadcast: sendToAllPlayers THROWS for a player with no channel (a GameTest
            // mock, or a vanilla client) instead of skipping them. Same change as the carrier
            // sweep's, deliberately — the two writers of this catalogue are one feature, and
            // fixing one of a pair is how the two paths drift.
            com.moddingfromamod.net.ModdedClient.broadcastWishItems(serverLevel.getServer());
        }
    }

    /** The tag this stack stamps on what it places, or empty if it was never given one. */
    public static String placeTag(ItemStack stack) {
        return key(stack, PLACE_TAG);
    }


    public static String onUse(ItemStack stack) {
        return key(stack, ON_USE);
    }

    public static String throwTag(ItemStack stack) {
        return key(stack, THROW_TAG);
    }

    /** The function this stack runs when its holder hits something, or empty. */
    public static String onHit(ItemStack stack) {
        return key(stack, ON_HIT);
    }

    /** Which made item this is — the feature id — or empty for anything the engine did not make. */
    public static String wishFeature(ItemStack stack) {
        return key(stack, WISH_FEATURE);
    }

    private static String key(ItemStack stack, String k) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? "" : data.copyTag().getStringOr(k, "");
    }

    /**
     * <b>Right-click in the air: throw it, or run its function.</b>
     *
     * <p>This is the half that makes the two big unserved wish classes reachable, and it removes a
     * pile of machinery rather than adding it. A datapack-only item has to detect its own use through
     * advancement triggers — which do not fire for an inert item at all, and which never learn WHERE
     * the click happened (see docs/SPIKE_PLACEABLE_MECHANISM.md). Once the mod owns the item it can
     * simply call the function, as the player, at the player. No trigger, no predicate, no raycast.
     */
    @Override
    protected Outcome onUse(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        return WishItemActions.use(level, player, hand, stack) ? Outcome.SUCCESS : Outcome.PASS;
    }

    /**
     * Place the anchor — {@link WishItemActions#useOn}, shared with the vanilla-carrier bridge.
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        return WishItemActions.useOn(context);
    }

    /** The function a thrown one runs at the point of impact, or empty. */
    /**
     * <b>The mark that says "this vanilla item is OUR carrier standing in for the wish item".</b>
     *
     * <p>Without it the carrier route has a hole that no case-by-case gate can close. A wish the
     * child built ON {@code minecraft:leather_horse_armor} ("magic horse armour") and our own
     * substitution produce stacks that are <b>byte-identical in shape</b>: same item, same
     * behaviour tags, same components. Anything asking the STACK "are you a carrier" gets the same
     * answer for both, so the bridge would run its abilities on his horse armour — while the same
     * wish built on a golden axe would not, purely because of which vanilla item we happened to
     * pick. That is not a rule anybody could predict.
     *
     * <p>Two earlier bugs were this same confusion caught one call site at a time (the neutralised
     * armour, the borrowed icon). Both were fixed by asking about the BASE the caller passed, which
     * works where the base is in scope — and nothing at interaction time has it. So the intent is
     * written down at give time instead, once, and every later question is answered from the stack.
     */
    // TRANSITIONAL — carrier spike. Not production. See docs/VANILLA_CARRIER_TRANSITIONAL.md
    // SEAM: the mark only means anything while both paths exist.
    public static final String CARRIER = "WishCarrier";

    // TRANSITIONAL — watch spike. Not production. See docs/VANILLA_WATCH_SPIKE_RESULTS.md
    // SEAM: which carrier is Moddy rather than a wished thing. Stamped at mint time by
    // VanillaWatchItem, for the reason CARRIER is: a child can wish for a magic horse armour and
    // no component tells his apart from ours, so intent has to be recorded when it is known.
    public static final String WATCH = "WishWatch";

    /** True if this stack was minted as our carrier — see {@link #CARRIER}. */
    public static boolean isOurCarrier(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBooleanOr(CARRIER, false);
    }

    public static String hitFunction(ItemStack stack) {
        return key(stack, HIT_FN);
    }

    public static String landFunction(ItemStack stack) {
        return stack == null ? "" : key(stack, LAND_FN);
    }

    public static String useDoneFunction(ItemStack stack) {
        return stack == null ? "" : key(stack, USE_DONE_FN);
    }

    public static String blockFunction(ItemStack stack) {
        return stack == null ? "" : key(stack, BLOCK_FN);
    }

    public static String consumeFunction(ItemStack stack) {
        return stack == null ? "" : key(stack, CONSUME_FN);
    }

    public static String shotFunction(ItemStack stack) {
        return stack == null ? "" : key(stack, SHOT_FN);
    }

    public static String arrowFunction(ItemStack stack) {
        return stack == null ? "" : key(stack, ARROW_FN);
    }

    public static String onMine(ItemStack stack) {
        return stack == null ? "" : key(stack, ON_MINE);
    }

    /** What this bow shoots instead of its arrow, as an entity id, or empty. */
    public static String projectile(ItemStack stack) {
        return stack == null ? "" : key(stack, com.moddingfromamod.datapack.ItemProperties.PROJECTILE_DATA);
    }

    /** Whether this bucket stays full after a pour or a drink. */
    public static boolean neverEmpty(ItemStack stack) {
        CustomData data = stack == null ? null : stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBooleanOr(com.moddingfromamod.datapack.ItemProperties.NEVER_EMPTY_DATA, false);
    }

    /** The item tag this bag takes and nothing else, without the {@code #}, or empty. */
    public static String holdsOnly(ItemStack stack) {
        return stack == null ? "" : key(stack, com.moddingfromamod.datapack.ItemProperties.HOLDS_ONLY_DATA);
    }

    /** When this item gets stronger, or null when it does not. */
    public static StrongerWhenRule.Setting strongerWhen(ItemStack stack) {
        CustomData data = stack == null ? null : stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        net.minecraft.nbt.CompoundTag all = data.copyTag();
        if (!all.contains(com.moddingfromamod.datapack.ItemProperties.STRONGER_WHEN_DATA)) {
            return null;
        }
        net.minecraft.nbt.CompoundTag t = all.getCompoundOrEmpty(com.moddingfromamod.datapack.ItemProperties.STRONGER_WHEN_DATA);
        String when = t.getStringOr("when", "");
        String stat = t.getStringOr("stat", "");
        int amount = t.getIntOr("amount", 0);
        return StrongerWhenRule.refusal(when, stat, amount) == null
                ? new StrongerWhenRule.Setting(when, stat, amount, t.getIntOr("y", 0)) : null;
    }
}
