package com.moddingfromamod.builder;

import net.minecraft.network.chat.Component;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>What a building stands on, in the running game</b>: the island it gets on the sea,
 * the footing it gets down a slope — both laid by the REAL builder as part of the build, and both
 * taken away again by the same {@link Builder#clearBuild} that {@code remove_build} and an undo
 * use, giving the sea and the hillside back. The rule itself is {@code BuildFootingTest}; the
 * set-down and its glide are {@code WishSpotGameTest}.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class BuildGroundGameTest {

    private static final String BATCH = "buildground";

    private BuildGroundGameTest() {
    }

    static BuildPlan hut(String id) {
        return new BuildPlan(id, "hut", List.of(
                new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, 3, 1, 3, "minecraft:oak_planks", false,
                        BuildShape.Anchor.CORNER, "floor"),
                new BuildShape(BuildShape.Kind.BOX, 0, 1, 0, 3, 3, 3, "minecraft:spruce_planks", true,
                        BuildShape.Anchor.CORNER, "walls")));
    }

    /**
     * <b>A hut wished onto the sea gets an island</b>: sand at sea level under it and a beach round
     * it, down to the seabed — and removing the hut gives the sea back, every block of it water again.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void aBuildOnTheSeaGetsAnIslandAndRemovingItGivesTheSeaBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        // A walled pool: seabed at y+1, water y+2..5 (surface 5), a stone rim so nothing flows out.
        for (int x = 0; x <= 8; x++) {
            for (int z = 0; z <= 8; z++) {
                level.setBlockAndUpdate(o.offset(x, 1, z), Blocks.GRAVEL.defaultBlockState());
                boolean rim = x == 0 || x == 8 || z == 0 || z == 8;
                for (int y = 2; y <= 5; y++) {
                    level.setBlock(o.offset(x, y, z), rim ? Blocks.STONE.defaultBlockState()
                            : Blocks.WATER.defaultBlockState(), 2);
                }
            }
        }
        BlockPos anchor = o.offset(3, 6, 3);          // on the water, as an aim at the sea lands
        BuildPlan plan = hut("gt_island_hut");
        String said = Builder.submit(level, anchor, plan, true, BuildGround.Ground.settle(), null);
        Builder.drainNow(level);
        try {
            helper.assertTrue(said.contains("little island"), Component.literal("the agent was not told about the island: " + said));
            helper.assertTrue(level.getBlockState(anchor).is(Blocks.OAK_PLANKS), Component.literal("the hut's floor is not on the sea"));
            for (int x = 3; x <= 5; x++) {
                for (int z = 3; z <= 5; z++) {
                    helper.assertTrue(level.getBlockState(o.offset(x, 5, z)).is(Blocks.SAND),
                            Component.literal("no island at sea level under the hut at " + x + "," + z));
                    for (int y = 2; y <= 5; y++) {
                        helper.assertTrue(level.getFluidState(o.offset(x, y, z)).isEmpty(),
                                Component.literal("water left under the hut at " + x + "," + y + "," + z + " — the island must reach the seabed"));
                    }
                }
            }
            for (BlockPos beach : List.of(o.offset(1, 5, 4), o.offset(4, 5, 1), o.offset(7, 5, 4), o.offset(4, 5, 7))) {
                helper.assertTrue(level.getBlockState(beach).is(Blocks.SAND), Component.literal("no beach at " + beach.toShortString()));
            }
            helper.assertTrue(level.getBlockState(o.offset(1, 6, 4)).isAir(), Component.literal("the beach rose above sea level"));
            int footing = BuildGround.footingOf(plan.id());
            helper.assertTrue(footing > 0, Component.literal("the island is not recorded as part of the build"));

            int cleared = Builder.clearBuild(level, anchor, plan);
            helper.assertTrue(cleared >= footing, Component.literal("removing the hut cleared " + cleared + " < its island's " + footing));
            for (int x = 1; x <= 7; x++) {
                for (int z = 1; z <= 7; z++) {
                    for (int y = 2; y <= 5; y++) {
                        BlockState s = level.getBlockState(o.offset(x, y, z));
                        helper.assertTrue(s.is(Blocks.WATER), Component.literal("the sea did not come back at " + x + "," + y + ","
                                + z + ": " + s));
                    }
                }
            }
            helper.assertTrue(BuildGround.footingOf(plan.id()) == 0, Component.literal("the island's record outlived the hut"));
            helper.succeed();
        } finally {
            Builder.clearBuild(level, anchor, plan);
        }
    }

    /**
     * <b>A hut on a slope gets a footing of the hillside</b> — dirt under grass, filling only the gap
     * under its downhill side — and nothing at all for one floated on purpose.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void aBuildOnASlopeGetsAFootingAndAFloatedOneDoesNot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        // A grass hillside stepping down east: ground top at y+4, 3, 2 across x = 2, 3, 4.
        for (int x = 0; x <= 8; x++) {
            int top = x <= 2 ? 4 : x == 3 ? 3 : 2;
            for (int z = 0; z <= 8; z++) {
                for (int y = 1; y <= top; y++) {
                    level.setBlockAndUpdate(o.offset(x, y, z), (y == top ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState());
                }
            }
        }
        BlockPos anchor = o.offset(2, 5, 3);
        BuildPlan plan = hut("gt_slope_hut");
        String said = Builder.submit(level, anchor, plan, true, BuildGround.Ground.settle(), null);
        Builder.drainNow(level);
        BuildPlan floatPlan = hut("gt_float_hut");
        BlockPos high = o.offset(2, 9, 6);
        try {
            helper.assertTrue(said.contains("filled them down to the ground"), Component.literal("the agent was not told about the footing: " + said));
            helper.assertTrue(level.getBlockState(o.offset(3, 4, 4)).is(Blocks.DIRT), Component.literal("no footing one block down at x=3"));
            helper.assertTrue(level.getBlockState(o.offset(4, 4, 4)).is(Blocks.DIRT)
                    && level.getBlockState(o.offset(4, 3, 4)).is(Blocks.DIRT), Component.literal("no footing two blocks down at x=4"));
            helper.assertTrue(level.getBlockState(o.offset(5, 3, 4)).isAir(), Component.literal("a footing outside the hut"));
            Builder.clearBuild(level, anchor, plan);
            helper.assertTrue(level.getBlockState(o.offset(4, 4, 4)).isAir() && level.getBlockState(o.offset(4, 3, 4)).isAir(),
                    Component.literal("the footing outlived the hut — the hillside should be as it was"));
            helper.assertTrue(level.getBlockState(o.offset(4, 2, 4)).is(Blocks.GRASS_BLOCK), Component.literal("the hillside itself was touched"));

            String floated = Builder.submit(level, high, floatPlan, true, new BuildGround.Ground(true, true), null);
            Builder.drainNow(level);
            helper.assertTrue(level.getBlockState(high).is(Blocks.OAK_PLANKS), Component.literal("the floated hut is not where it was put"));
            helper.assertTrue(level.getBlockState(high.below()).isAir(), Component.literal("something was filled under a floated build"));
            helper.assertTrue(!floated.contains("set it down"), Component.literal("a floated build was set down: " + floated));
            helper.succeed();
        } finally {
            Builder.clearBuild(level, anchor, plan);
            Builder.clearBuild(level, high, floatPlan);
        }
    }
}
