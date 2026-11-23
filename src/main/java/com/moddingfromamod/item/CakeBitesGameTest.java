package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.datapack.ItemProperties;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b> part 2: a fork that eats a cake faster, on the real game.</b> The real
 * right-click event on a real cake: a fork carrying {@code cake_bites} 4 takes 4 bites in one click
 * (and fills him by 8); a 7-bite fork finishes a whole cake; a full child takes none; a plain stick
 * is left to vanilla. Red on {@code main}: the property does not exist.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class CakeBitesGameTest {

    private static ItemStack fork(int n) {
        ItemStack s = new ItemStack(Items.STICK);
        CompoundTag t = new CompoundTag();
        t.putInt(ItemProperties.CAKE_BITES_DATA, n);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        return s;
    }

    private static boolean click(GameTestHelper helper, ServerPlayer p, BlockPos cake) {
        var ev = new PlayerInteractEvent.RightClickBlock(p, InteractionHand.MAIN_HAND, cake,
                new BlockHitResult(Vec3.atCenterOf(cake), Direction.UP, cake, false));
        NeoForge.EVENT_BUS.post(ev);
        return ev.isCanceled();
    }

    @GameTest(template = "empty_test", batch = "cakebites", timeoutTicks = 100)
    public static void aForkTakesManyBitesInOneClick(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        p.setGameMode(GameType.SURVIVAL);
        BlockPos cake = helper.absolutePos(new BlockPos(2, 2, 2));
        var level = helper.getLevel();

        level.setBlockAndUpdate(cake, Blocks.CAKE.defaultBlockState());
        p.getFoodData().setFoodLevel(2);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        helper.assertTrue(!click(helper, p, cake) && level.getBlockState(cake).getValue(CakeBlock.BITES) == 0,
                Component.literal("a plain stick ate the cake"));

        p.setItemInHand(InteractionHand.MAIN_HAND, fork(4));
        helper.assertTrue(click(helper, p, cake), Component.literal("the fork's click was not handled"));
        helper.assertTrue(level.getBlockState(cake).getValue(CakeBlock.BITES) == 4,
                Component.literal("one click took " + level.getBlockState(cake).getValue(CakeBlock.BITES) + " bites, not 4"));
        helper.assertTrue(p.getFoodData().getFoodLevel() == 10, Component.literal("he was filled to " + p.getFoodData().getFoodLevel() + ", not 10"));

        level.setBlockAndUpdate(cake, Blocks.CAKE.defaultBlockState());
        p.getFoodData().setFoodLevel(0);
        p.setItemInHand(InteractionHand.MAIN_HAND, fork(7));
        click(helper, p, cake);
        helper.assertTrue(level.getBlockState(cake).isAir(), Component.literal("a 7-bite fork did not finish a whole cake"));

        level.setBlockAndUpdate(cake, Blocks.CAKE.defaultBlockState());
        p.getFoodData().setFoodLevel(20);
        click(helper, p, cake);
        helper.assertTrue(level.getBlockState(cake).getValue(CakeBlock.BITES) == 0, Component.literal("a full child ate cake"));
        helper.succeed();
    }

    /** Where it RUNS: make_item takes it on a made fork. */
    @GameTest(template = "empty_test", batch = "cakebites", timeoutTicks = 100)
    public static void makeItemTakesACakeFork(GameTestHelper helper) {
        JsonObject props = new JsonObject();
        props.addProperty(ItemProperties.CAKE_BITES_KEY, 4);
        JsonObject o = new JsonObject();
        o.addProperty("name", "Cake Fork " + helper.getLevel().getGameTime());
        o.addProperty("icon", "wand");
        o.addProperty("colour", "#C0C0C0");
        o.addProperty("shape", "plain");
        o.addProperty("count", 1);
        o.add("properties", props);
        var r = new com.moddingfromamod.agent.tools.MakeItemTool().call(o);
        helper.assertTrue(!r.isError(), Component.literal("make_item refused a cake fork: " + r.text()));
        helper.succeed();
    }
}
