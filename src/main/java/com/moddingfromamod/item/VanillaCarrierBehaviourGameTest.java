package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.advancements.criterion.ItemPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

// ── TRANSITIONAL — carrier spike. Not production. See docs/VANILLA_CARRIER_TRANSITIONAL.md
// The datapack-route behaviour matrix. If the route is abandoned this whole file goes; if it ships, the
// MOD ITEM becomes the legacy path instead. Gated.

/**
 * <b>Gate B for the spike's second half: can a wished item still DO anything on a vanilla client?</b>
 *
 * <p>{@code VanillaItemAbClientTest} measures how a wished item <i>looks</i> on a vanilla carrier,
 * and the answer there is near-parity. This measures what it <i>does</i>, which is a completely
 * separate mechanism and — measured here — a much worse answer.
 *
 * <p>Today every behaviour lives in {@link WishItem}, a mod {@code Item} subclass: {@code use}
 * throws a tagged projectile or runs a datapack function, {@code useOn} puts down a tagged anchor,
 * {@code inventoryTick} catalogues it. <b>A vanilla carrier has none of those hooks</b>, so on a
 * vanilla client the behaviour would have to come from a datapack — an advancement trigger that
 * notices the click, plus a function that acts on it.
 *
 * <p>This is a <b>server-side</b> question, so it is measured here rather than through a camera:
 * Gate B can assert that a function ran and that an entity carries a tag, which no photograph can.
 *
 * <h2>What each test establishes, and why the obvious version of it would not</h2>
 *
 * <p>The load-bearing find is {@code anInertCarrierNeverReachesTheVanillaUseTrigger}. Vanilla only
 * fires {@code CriteriaTriggers.ITEM_USED_ON_BLOCK} when the item's own {@code useOn}
 * <b>consumed the action</b> — {@code ServerPlayerGameMode.useItemOn}:
 * {@code if (interactionresult1.consumesAction()) { ...trigger(...) }}. An inert item returns
 * {@code PASS}, so the trigger never fires and there is nothing for a datapack to hang off. Reading
 * a list of available triggers would suggest the opposite: {@code item_used_on_block} exists, takes
 * an item predicate, and reports the block position — everything a placeable wish needs, and it is
 * unreachable for the carrier the icon work chose.
 *
 * <p>The other tests are the positive half — which carriers <i>do</i> open a route, so the spike can
 * say what a vanilla item can be made to do rather than only what it cannot.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class VanillaCarrierBehaviourGameTest {

    /** The carrier the icon A/B chose — see {@code VanillaItemPackClient.BASE_ITEM}. */
    private static ItemStack carrier(int iconId, String key, String value) {
        ItemStack s = new ItemStack(Items.LEATHER_HORSE_ARMOR);
        s.set(DataComponents.CUSTOM_MODEL_DATA, com.moddingfromamod.compat.ModelData.of(iconId));
        CompoundTag tag = new CompoundTag();
        tag.putString(key, value);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    /**
     * <b>The finding.</b> Right-clicking a block with the vanilla carrier does nothing at all, and
     * — the part that matters — vanilla's own trigger does not fire either, because it is gated on
     * the item having consumed the action.
     */
    @GameTest(template = "empty_test")
    public static void anInertCarrierNeverReachesTheVanillaUseTrigger(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos floorRel = new BlockPos(2, 1, 2);
        helper.setBlock(floorRel, Blocks.STONE);
        BlockPos floor = helper.absolutePos(floorRel);

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(floor.getX() + 0.5, floor.getY() + 1, floor.getZ() + 3.5);

        // Arm A: the mod item. Its useOn consumes, which both places the anchor AND is the exact
        // condition vanilla's trigger is gated on.
        ItemStack modItem = WishItem.of(ModItems.WISH_ITEM.get(), 1, "carrier_probe_a");
        player.setItemInHand(InteractionHand.MAIN_HAND, modItem);
        InteractionResult a = modItem.getItem().useOn(context(player, floor));
        helper.assertTrue(a.consumesAction(),
                Component.literal("today's wish_item must consume the action on useOn — if it ever stops, the anchor "
                        + "stops being placed AND vanilla's own trigger stops firing for it too"));

        // Arm B: the vanilla carrier, same components.
        ItemStack vanilla = carrier(1, WishItem.PLACE_TAG, "carrier_probe_b");
        player.setItemInHand(InteractionHand.MAIN_HAND, vanilla);
        InteractionResult b = vanilla.getItem().useOn(context(player, floor));
        helper.assertFalse(b.consumesAction(),
                Component.literal("leather_horse_armor unexpectedly consumed a use-on-block; if that ever becomes "
                        + "true this whole finding changes and the datapack route opens"));

        List<ArmorStand> anchors = level.getEntities(EntityType.ARMOR_STAND,
                new AABB(floor.above()).inflate(2.0),
                e -> e.getTags().contains("carrier_probe_b"));
        helper.assertTrue(anchors.isEmpty(),
                Component.literal("the vanilla carrier placed something, which contradicts it having no useOn"));

        // Say the consequence out loud, so a reader of the gate log gets the finding and not just
        // a green tick: this is WHY the behaviour half of the spike is not near-parity.
        helper.assertTrue(!b.consumesAction(),
                Component.literal("ServerPlayerGameMode.useItemOn only fires ITEM_USED_ON_BLOCK when the item "
                        + "consumed the action, so an inert carrier is invisible to the one "
                        + "advancement trigger that would have reported a placement"));
        helper.succeed();
    }

    /** Right-clicking the air with an inert carrier is likewise nothing, and has no trigger at all. */
    @GameTest(template = "empty_test")
    public static void anInertCarrierDoesNothingInTheAir(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);

        ItemStack vanilla = carrier(9, WishItem.THROW_TAG, "carrier_throw_probe");
        player.setItemInHand(InteractionHand.MAIN_HAND, vanilla);
        vanilla.getItem().use(level, player, InteractionHand.MAIN_HAND);

        helper.assertTrue(level.getEntities(player, new AABB(at).inflate(8.0)).isEmpty(),
                Component.literal("the vanilla carrier threw something, which it has no code to do"));
        helper.assertTrue(vanilla.getUseDuration(player) == 0,
                Component.literal("leather_horse_armor must have no use duration — with one, minecraft:using_item "
                        + "would fire and the air-click WOULD be detectable, which is exactly the "
                        + "property that makes a potion a usable carrier and this one not"));
        helper.succeed();
    }

    /**
     * The positive half: a SPLASH POTION carrier throws natively and carries the wish's data on the
     * thrown entity, which is the shape {@code WishItem.thrown}'s tagged snowball has today.
     *
     * <p>So the throwable wish class does have a vanilla route — with a different carrier from the
     * one the icons want. That is the spike's real D1 answer: not one base item, a small table.
     */
    @GameTest(template = "empty_test")
    public static void aSplashPotionCarrierThrowsAndKeepsTheWishesData(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(4, 3, 4));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);

        ItemStack splash = new ItemStack(Items.SPLASH_POTION);
        splash.set(DataComponents.CUSTOM_MODEL_DATA, com.moddingfromamod.compat.ModelData.of(9));
        CompoundTag tag = new CompoundTag();
        tag.putString(WishItem.THROW_TAG, "splash_probe");
        splash.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        player.setItemInHand(InteractionHand.MAIN_HAND, splash);

        splash.getItem().use(level, player, InteractionHand.MAIN_HAND);

        List<ThrownSplashPotion> thrown = level.getEntities(EntityType.SPLASH_POTION,
                new AABB(at).inflate(8.0), e -> true);
        helper.assertTrue(thrown.size() == 1,
                Component.literal("a splash potion must throw natively, produced " + thrown.size()));
        CustomData carried = thrown.get(0).getItem().get(DataComponents.CUSTOM_DATA);
        helper.assertTrue(carried != null
                        && "splash_probe".equals(carried.copyTag().getStringOr(WishItem.THROW_TAG, "")),
                Component.literal("the thrown entity must carry the wish's own data, or a datapack watching "
                        + "@e[type=potion] cannot tell which wish threw it: " + carried));
        helper.succeed();
    }

    /**
     * And the other half of "a datapack could act on this": can a predicate tell two wished items
     * apart at all?
     *
     * <p>Without this, every route above is moot — a trigger that fires for every wished item alike
     * cannot run the right function. This is the vanilla mechanism a datapack would use
     * ({@code minecraft:custom_data} inside an item predicate), driven directly.
     */
    @GameTest(template = "empty_test")
    public static void anItemPredicateCanTellTwoWishedItemsApart(GameTestHelper helper) {
        ItemStack mine = carrier(3, WishItem.ON_USE, "wish:pour_coffee");
        ItemStack other = carrier(3, WishItem.ON_USE, "wish:launch_rocket");
        ItemStack bare = new ItemStack(Items.LEATHER_HORSE_ARMOR);

        CompoundTag want = new CompoundTag();
        want.putString(WishItem.ON_USE, "wish:pour_coffee");
        ItemPredicate predicate = ItemPredicate.Builder.item()
                .withComponents(net.minecraft.advancements.criterion.DataComponentMatchers.Builder.components()
                        .exact(net.minecraft.core.component.DataComponentExactPredicate.builder()
                                .expect(DataComponents.CUSTOM_DATA, CustomData.of(want))
                                .build())
                        .build())
                .build();

        helper.assertTrue(predicate.test(mine), Component.literal("the predicate must match its own wish"));
        helper.assertFalse(predicate.test(other),
                Component.literal("the predicate matched a DIFFERENT wish — a datapack could not route two wishes "
                        + "to two functions, so every wished item would do the same thing"));
        helper.assertFalse(predicate.test(bare),
                Component.literal("the predicate matched a plain leather horse armour, so any of them in a child's "
                        + "world would start behaving like a wish"));
        helper.succeed();
    }

    /**
     * The carrier the ICON work chose and the carriers the BEHAVIOUR work would need are different
     * items, and that is the decision D1 has to record.
     *
     * <p>Asserted rather than written down, because a later change to either side would otherwise
     * silently invalidate the recommendation.
     */
    @GameTest(template = "empty_test")
    public static void theIconCarrierAndTheBehaviourCarriersAreNotTheSameItem(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        // Drinkable: a use duration is what makes minecraft:using_item / consume_item reachable.
        helper.assertTrue(new ItemStack(Items.POTION).getUseDuration(player) > 0,
                Component.literal("a potion must have a use duration, or the 'item that does something on "
                        + "right-click' wish class has no vanilla route either"));
        // Inert: no duration, no useOn — nothing to hang a trigger on.
        helper.assertTrue(new ItemStack(Items.LEATHER_HORSE_ARMOR).getUseDuration(player) == 0,
                Component.literal("the icon carrier must be inert; if it gains a use action a child right-clicking a "
                        + "wished cup would do something unasked-for"));
        helper.succeed();
    }

    private static UseOnContext context(Player player, BlockPos floor) {
        return new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor,
                        false));
    }
}
