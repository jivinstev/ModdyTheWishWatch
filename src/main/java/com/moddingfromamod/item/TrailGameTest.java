package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.datapack.ItemProperties;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: a trail behind him, on the real game.</b> He walks one block with a fire sword
 * in his hand, then with flower boots on: the block he LEFT has fire (and he is fire resistant), or a
 * flower where soil is under it and nothing where stone is. With nothing carried, he leaves nothing.
 * Red on {@code main}: the property does not exist, so nothing is ever left.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class TrailGameTest {

    private static ItemStack with(Item base, String trail) throws Exception {
        JsonObject props = new JsonObject();
        props.addProperty(ItemProperties.TRAIL_KEY, trail);
        String entries = ItemProperties.dataEntries(props);
        ItemStack s = new ItemStack(base);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(TagParser.parseCompoundFully("{" + entries.substring(1) + "}")));
        return s;
    }

    /** Stand him on (x,2,z) over a floor of `floor`, look once, step to (x+1), look again. */
    private static BlockPos walk(GameTestHelper helper, ServerPlayer p, int x, int z, Block floor) {
        for (int dx = 0; dx <= 1; dx++) {
            helper.setBlock(new BlockPos(x + dx, 1, z), floor);
            helper.setBlock(new BlockPos(x + dx, 2, z), Blocks.AIR);
        }
        BlockPos from = helper.absolutePos(new BlockPos(x, 2, z));
        BlockPos to = helper.absolutePos(new BlockPos(x + 1, 2, z));
        p.snapTo(from.getX() + 0.5, from.getY(), from.getZ() + 0.5, 0, 0);
        Trail.step(p);
        p.snapTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 0, 0);
        Trail.step(p);
        return from;
    }

    @GameTest(template = "empty_test", batch = "trail", timeoutTicks = 100)
    public static void fireAndFlowersAreLeftWhereHeWalked(GameTestHelper helper) throws Exception {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        p.setGameMode(GameType.SURVIVAL);
        var level = helper.getLevel();

        BlockPos none = walk(helper, p, 0, 0, Blocks.STONE);
        helper.assertTrue(level.getBlockState(none).isAir(), Component.literal("he left something with nothing carried"));

        p.setItemInHand(InteractionHand.MAIN_HAND, with(Items.DIAMOND_SWORD, "fire"));
        BlockPos fire = walk(helper, p, 0, 1, Blocks.STONE);
        helper.assertTrue(level.getBlockState(fire).is(BlockTags.FIRE), Component.literal("no fire where he walked: " + level.getBlockState(fire)));
        helper.assertTrue(p.hasEffect(MobEffects.FIRE_RESISTANCE), Component.literal("the fire trail did not keep him fire resistant"));
        level.setBlockAndUpdate(fire, Blocks.AIR.defaultBlockState());

        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.setItemSlot(EquipmentSlot.FEET, with(Items.LEATHER_BOOTS, "flowers"));
        BlockPos flower = walk(helper, p, 0, 2, Blocks.GRASS_BLOCK);
        helper.assertTrue(level.getBlockState(flower).is(BlockTags.SMALL_FLOWERS),
                Component.literal("no flower where he walked on grass: " + level.getBlockState(flower)));
        BlockPos stone = walk(helper, p, 0, 3, Blocks.STONE);
        helper.assertTrue(level.getBlockState(stone).isAir(), Component.literal("a flower was planted on stone: " + level.getBlockState(stone)));
        p.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
        helper.succeed();
    }

    /** Where it RUNS: make_item takes a trail on a real sword, and refuses an unknown one by name. */
    @GameTest(template = "empty_test", batch = "trail", timeoutTicks = 100)
    public static void makeItemTakesAFireTrailAndRefusesIce(GameTestHelper helper) {
        JsonObject props = new JsonObject();
        props.addProperty(ItemProperties.TRAIL_KEY, "fire");
        JsonObject o = new JsonObject();
        o.addProperty("name", "Blaze Blade " + helper.getLevel().getGameTime());
        o.addProperty("icon", "sword");
        o.addProperty("colour", "#E04010");
        o.addProperty("shape", "plain");
        o.addProperty("base_item", "minecraft:diamond_sword");
        o.addProperty("count", 1);
        o.add("properties", props);
        var r = new com.moddingfromamod.agent.tools.MakeItemTool().call(o);
        helper.assertTrue(!r.isError(), Component.literal("make_item refused a fire trail on a real sword: " + r.text()));
        JsonObject ice = o.deepCopy();
        ice.addProperty("name", "Ice Walkers " + helper.getLevel().getGameTime());
        ice.getAsJsonObject("properties").addProperty(ItemProperties.TRAIL_KEY, "ice");
        var no = new com.moddingfromamod.agent.tools.MakeItemTool().call(ice);
        helper.assertTrue(no.isError() && no.text().contains("frost_walker"), Component.literal("an ice trail was not sent to frost_walker: " + no.text()));
        helper.succeed();
    }
}
