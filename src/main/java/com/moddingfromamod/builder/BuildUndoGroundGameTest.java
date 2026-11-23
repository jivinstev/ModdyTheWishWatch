package com.moddingfromamod.builder;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>Taking a building down gives back what it was built on</b>. A walkthrough's
 * farm plot went up on the player's cobblestone generator and cleared its lava; an undo gave back air. Through the
 * real {@link Builder#submit} and {@link Builder#clearBuild} (what {@code remove_build} and a building's undo run).
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class BuildUndoGroundGameTest {

    private static final String BATCH = "buildUndoGround";

    private BuildUndoGroundGameTest() {
    }

    private static BuildPlan box(String id, int w, int h, int d) {
        return BuildPlan.fromJson(JsonParser.parseString("{\"id\":\"" + id + "\",\"name\":\"Box\",\"shapes\":[{\"kind\":\"box\","
                + "\"dx\":0,\"dy\":0,\"dz\":0,\"width\":" + w + ",\"height\":" + h + ",\"depth\":" + d
                + ",\"block\":\"minecraft:oak_planks\"}]}").getAsJsonObject());
    }

    private static void set(ServerLevel level, BlockPos p, Block b) {
        level.setBlockAndUpdate(p, b.defaultBlockState());
    }

    /**
     * A working cobblestone generator (water, a gap, a gap, lava; a hole beside the water) with a plank box built over
     * its lava: the lava is gone. Taken down: the lava SOURCE is back, the grass beside it is back, a cell that was air
     * is air, and the generator makes cobblestone again when its cobblestone is mined.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 1200)
    public static void aBuildingTakenDownGivesBackTheGeneratorItCovered(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(new BlockPos(1, 2, 3));   // the generator's row runs east from here
        // The basin: a floor, walls, the hole beside the water.
        for (int x = -1; x <= 4; x++) {
            for (int z = -1; z <= 1; z++) {
                set(level, o.offset(x, -1, z), Blocks.STONE);
                set(level, o.offset(x, -2, z), Blocks.STONE);
                if (z != 0 || x == -1 || x == 4) {
                    set(level, o.offset(x, 0, z), Blocks.STONE);
                }
            }
        }
        set(level, o.offset(1, -1, 0), Blocks.AIR);     // the hole beside the water
        set(level, o.offset(0, 0, 0), Blocks.WATER);
        set(level, o.offset(3, 0, 0), Blocks.LAVA);
        BlockPos gap = o.offset(2, 0, 0);               // where the cobblestone forms
        BlockPos lava = o.offset(3, 0, 0);
        BlockPos grass = o.offset(5, -1, 0);
        set(level, grass, Blocks.GRASS_BLOCK);
        BuildPlan plan = box("gt_undo_ground_box", 3, 2, 1);
        BlockPos anchor = o.offset(3, 0, 0);            // the box covers the lava, the basin wall and the grass column
        int[] phase = {0};
        helper.succeedWhen(() -> {
            if (phase[0] == 0) {
                helper.assertTrue(level.getBlockState(gap).is(Blocks.COBBLESTONE),
                        Component.literal("control: the generator has not made cobblestone yet (" + level.getBlockState(gap) + ")"));
                String r = Builder.submit(level, anchor, plan, false);
                helper.assertTrue(r.startsWith("Building"), Component.literal("refused: " + r));
                Builder.drainNow(level);
                helper.assertTrue(level.getBlockState(lava).is(Blocks.OAK_PLANKS),
                        Component.literal("control: the box did not cover the lava"));
                helper.assertTrue(BuildGroundJournal.size(level, plan.id()) > 0,
                        Component.literal("the building recorded none of the ground it replaced"));
                Builder.clearBuild(level, anchor, plan);
                level.setBlockAndUpdate(gap, Blocks.AIR.defaultBlockState());   // he mines the cobblestone
                phase[0] = 1;
            }
            BlockState l = level.getBlockState(lava);
            helper.assertTrue(l.getFluidState().is(FluidTags.LAVA) && l.getFluidState().isSource(),
                    Component.literal("the lava source did not come back: " + l));
            helper.assertTrue(level.getBlockState(o.offset(4, 0, 0)).is(Blocks.STONE),
                    Component.literal("the basin wall did not come back: " + level.getBlockState(o.offset(4, 0, 0))));
            helper.assertTrue(level.getBlockState(o.offset(5, 0, 0)).isAir(),
                    Component.literal("a cell that was air is not air: " + level.getBlockState(o.offset(5, 0, 0))));
            helper.assertTrue(level.getBlockState(gap).is(Blocks.COBBLESTONE),
                    Component.literal("the generator has not made cobblestone again yet"));
        });
    }

    /** A block he put in place of one of the building's after it went up is left as he left it; the rest comes back. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void aBlockHeChangedSinceIsLeftAsHeLeftIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos anchor = helper.absolutePos(new BlockPos(2, 2, 2));
        for (int x = 0; x < 3; x++) {
            set(level, anchor.offset(x, 0, 0), Blocks.GRASS_BLOCK);
        }
        BuildPlan plan = box("gt_undo_ground_mine", 3, 1, 1);
        String r = Builder.submit(level, anchor, plan, false);
        helper.assertTrue(r.startsWith("Building"), Component.literal("refused: " + r));
        Builder.drainNow(level);
        set(level, anchor.offset(1, 0, 0), Blocks.DIAMOND_BLOCK);    // his, since
        Builder.clearBuild(level, anchor, plan);
        helper.assertTrue(level.getBlockState(anchor).is(Blocks.GRASS_BLOCK)
                        && level.getBlockState(anchor.offset(2, 0, 0)).is(Blocks.GRASS_BLOCK),
                Component.literal("the grass under the building did not come back"));
        helper.assertTrue(level.getBlockState(anchor.offset(1, 0, 0)).is(Blocks.DIAMOND_BLOCK),
                Component.literal("his block was taken: " + level.getBlockState(anchor.offset(1, 0, 0))));
        helper.assertTrue(BuildGroundJournal.size(level, plan.id()) == 0,
                Component.literal("the building's ground was not forgotten once it came down"));
        for (int x = 0; x < 3; x++) {
            set(level, anchor.offset(x, 0, 0), Blocks.AIR);
        }
        helper.succeed();
    }
}
