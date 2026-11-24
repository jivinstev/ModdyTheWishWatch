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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: a tool that breaks more than one block, on the real game.</b> He breaks ONE block the
 * way the game does it ({@code destroyBlock}, the same path as a real swing); the shape takes the
 * rest — a 3×3 face, a whole vein and nothing beside it, a hole — every block dropping its own item
 * and wearing the tool once, and never past the cap.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class MineAreaGameTest {

    private static ItemStack with(Item base, String shape) throws Exception {
        JsonObject props = new JsonObject();
        props.addProperty(ItemProperties.MINE_AREA_KEY, shape);
        String entries = ItemProperties.dataEntries(props);
        ItemStack s = new ItemStack(base);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(TagParser.parseCompoundFully("{" + entries.substring(1) + "}")));
        return s;
    }

    private static ServerPlayer miner(GameTestHelper helper, Item tool, String shape, BlockPos stand,
                                      float yaw, float pitch) throws Exception {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        p.setGameMode(GameType.SURVIVAL);
        BlockPos a = helper.absolutePos(stand);
        p.snapTo(a.getX() + 0.5, a.getY(), a.getZ() + 0.5, yaw, pitch);
        p.setItemInHand(InteractionHand.MAIN_HAND, with(tool, shape));
        return p;
    }

    private static int count(GameTestHelper helper, BlockPos from, BlockPos to, Block b) {
        int n = 0;
        for (BlockPos q : BlockPos.betweenClosed(helper.absolutePos(from), helper.absolutePos(to))) {
            n += helper.getLevel().getBlockState(q).is(b) ? 1 : 0;
        }
        return n;
    }

    private static int dropped(GameTestHelper helper, Item item) {
        int n = 0;
        for (var e : helper.getLevel().getEntities(EntityTypes.ITEM,
                new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(16), x -> x.getItem().is(item))) {
            n += e.getItem().getCount();
        }
        return n;
    }

    @GameTest(template = "empty_test", batch = "minearea", timeoutTicks = 100)
    public static void aHammerBreaksTheThreeByThreeFaceHeHitAndWearsOncePerBlock(GameTestHelper helper) throws Exception {
        // A stone wall in the x-y plane at z=5; he stands at z=2 looking south (+z) at its middle.
        for (int x = 1; x <= 5; x++) {
            for (int y = 1; y <= 5; y++) {
                helper.setBlock(new BlockPos(x, y, 5), Blocks.STONE);
            }
        }
        ServerPlayer p = miner(helper, Items.IRON_PICKAXE, "3x3", new BlockPos(3, 2, 2), 0f, 0f);
        p.gameMode.destroyBlock(helper.absolutePos(new BlockPos(3, 3, 5)));
        MineArea.finish(p);
        helper.assertTrue(count(helper, new BlockPos(2, 2, 5), new BlockPos(4, 4, 5), Blocks.STONE) == 0,
                Component.literal("the 3x3 face he hit is gone"));
        helper.assertTrue(count(helper, new BlockPos(1, 1, 5), new BlockPos(5, 5, 5), Blocks.STONE) == 16,
                Component.literal("and nothing outside it"));
        helper.assertTrue(dropped(helper, Items.COBBLESTONE) == 9, Component.literal("each block dropped its own: " + dropped(helper, Items.COBBLESTONE)));
        helper.assertTrue(p.getMainHandItem().getDamageValue() == 9,
                Component.literal("the tool wore once per block: " + p.getMainHandItem().getDamageValue()));
        MockPlayerNetwork.remove(p);
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "minearea", timeoutTicks = 100)
    public static void aVeinGoesWholeAndOnlyTheVeinWithinTheCap(GameTestHelper helper) throws Exception {
        // An iron vein of five, touching a stone block that must stay; and a huge vein of coal that
        // must stop at the cap.
        BlockPos[] vein = {new BlockPos(2, 2, 5), new BlockPos(3, 2, 5), new BlockPos(3, 3, 5),
                new BlockPos(4, 3, 6), new BlockPos(4, 4, 6)};
        for (BlockPos v : vein) {
            helper.setBlock(v, Blocks.IRON_ORE);
        }
        helper.setBlock(new BlockPos(2, 3, 5), Blocks.STONE);
        ServerPlayer p = miner(helper, Items.STONE_PICKAXE, "vein", new BlockPos(2, 2, 2), 0f, 0f);
        p.gameMode.destroyBlock(helper.absolutePos(vein[0]));
        MineArea.finish(p);
        helper.assertTrue(count(helper, new BlockPos(1, 1, 4), new BlockPos(5, 5, 7), Blocks.IRON_ORE) == 0,
                Component.literal("the whole vein went"));
        helper.assertBlockPresent(Blocks.STONE, new BlockPos(2, 3, 5));
        helper.assertTrue(dropped(helper, Items.RAW_IRON) == 5, Component.literal("five raw iron: " + dropped(helper, Items.RAW_IRON)));

        for (int x = 0; x <= 7; x++) {
            for (int y = 1; y <= 7; y++) {
                for (int z = 8; z <= 9; z++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.COAL_ORE);
                }
            }
        }
        p.setItemInHand(InteractionHand.MAIN_HAND, with(Items.DIAMOND_PICKAXE, "vein"));
        p.gameMode.destroyBlock(helper.absolutePos(new BlockPos(3, 3, 8)));
        MineArea.finish(p);
        int gone = 8 * 7 * 2 - count(helper, new BlockPos(0, 1, 8), new BlockPos(7, 7, 9), Blocks.COAL_ORE);
        helper.assertTrue(gone == 1 + MineShape.MAX_EXTRA, Component.literal("a huge vein stops at the cap: " + gone));
        MockPlayerNetwork.remove(p);
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "minearea", timeoutTicks = 100)
    public static void aShovelDigsAHoleAndAToolThatCannotTakeABlockLeavesIt(GameTestHelper helper) throws Exception {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.DIRT);
                }
            }
        }
        helper.setBlock(new BlockPos(4, 3, 3), Blocks.OBSIDIAN);   // a shovel cannot take this
        helper.setBlock(new BlockPos(2, 3, 3), Blocks.CHEST);      // nor anything that holds things
        ServerPlayer p = miner(helper, Items.IRON_SHOVEL, "hole", new BlockPos(3, 5, 3), 0f, 90f);
        p.gameMode.destroyBlock(helper.absolutePos(new BlockPos(3, 4, 3)));
        MineArea.finish(p);
        int dug = 7 * 7 * 4 - count(helper, new BlockPos(0, 1, 0), new BlockPos(6, 4, 6), Blocks.DIRT);
        helper.assertTrue(dug >= 15, Component.literal("a hole was dug: " + dug + " blocks"));
        helper.assertBlockPresent(Blocks.OBSIDIAN, new BlockPos(4, 3, 3));
        helper.assertBlockPresent(Blocks.CHEST, new BlockPos(2, 3, 3));
        MockPlayerNetwork.remove(p);
        helper.succeed();
    }
}
