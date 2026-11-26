package com.moddingfromamod.item;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;

/**
 * <b>#173: a wished food is eaten on a FULL hunger bar.</b> A heal egg is a cookie carrying
 * {@code WishConsumeFunction}; vanilla refuses to start eating a cookie at food 20, so the wish
 * did nothing for a child who was hurt but not hungry. Each case goes through the real use path
 * ({@code ServerPlayerGameMode.useItem}, which fires the right-click event before {@code Item.use}),
 * with a plain cookie as the control that proves the refusal is visible to this test at all.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class AlwaysEdibleGameTest {

    private static final String BATCH = "alwaysedible";

    /** A stack exactly as an old wish's give line mints it: the base's own food, the trigger tag. */
    private static ItemStack wishedCookie() {
        ItemStack s = new ItemStack(Items.COOKIE);
        CompoundTag tag = new CompoundTag();
        tag.putString(WishItem.CONSUME_FN, "healing_egg_heal:act");
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    private static ServerPlayer fullPlayer(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        p.setGameMode(GameType.SURVIVAL);
        p.getFoodData().setFoodLevel(20);
        return p;
    }

    /** Right-click it for real: does he start eating? */
    private static boolean startsEating(ServerPlayer p, ItemStack stack) {
        p.stopUsingItem();
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        p.gameMode.useItem(p, p.level(), stack, InteractionHand.MAIN_HAND);
        boolean eating = p.isUsingItem();
        p.stopUsingItem();
        return eating;
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void aWishedCookieIsEatenOnAFullStomachAndAPlainOneIsNot(GameTestHelper helper) {
        ServerPlayer p = fullPlayer(helper);
        helper.assertTrue(!startsEating(p, new ItemStack(Items.COOKIE)),
                Component.literal("control: a plain cookie was started at food 20, so this test cannot see the refusal"));
        ItemStack wished = wishedCookie();
        helper.assertTrue(startsEating(p, wished),
                Component.literal("a wished cookie (on_consume) could not be started at food 20"));
        FoodProperties food = wished.get(DataComponents.FOOD);
        FoodProperties plain = Items.COOKIE.components().get(DataComponents.FOOD);
        helper.assertTrue(food != null && food.canAlwaysEat()
                        && food.nutrition() == plain.nutrition() && food.saturation() == plain.saturation(),
                Component.literal("the food changed beyond can_always_eat: " + food + " vs " + plain));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void anOldWishedFoodInHisInventoryIsFixedOnceAndRealFoodIsLeftAlone(GameTestHelper helper) {
        ServerPlayer p = fullPlayer(helper);
        p.getInventory().clearContent();
        p.getInventory().setItem(3, wishedCookie());
        p.getInventory().setItem(4, new ItemStack(Items.BREAD, 5));
        p.getInventory().setItem(5, new ItemStack(Items.POTION));
        helper.assertValueEqual(AlwaysEdible.sweep(p), 1, Component.literal("stacks fixed by the first sweep"));
        helper.assertValueEqual(AlwaysEdible.sweep(p), 0, Component.literal("stacks fixed by a second sweep (must be idempotent)"));
        helper.assertTrue(p.getInventory().getItem(3).get(DataComponents.FOOD).canAlwaysEat(),
                Component.literal("the old wished cookie is still refused on a full stomach"));
        helper.assertTrue(!p.getInventory().getItem(4).get(DataComponents.FOOD).canAlwaysEat(),
                Component.literal("real bread with no wish on it was changed"));
        helper.assertTrue(!p.getInventory().getItem(5).has(DataComponents.FOOD),
                Component.literal("a potion was given a food"));
        helper.succeed();
    }
}
