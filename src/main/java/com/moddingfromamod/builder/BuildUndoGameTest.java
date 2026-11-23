package com.moddingfromamod.builder;

import net.minecraft.network.chat.Component;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.MadeKind;
import com.moddingfromamod.wish.WishUndo;

import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Undoing a refinement puts the building back one step — it does not delete the castle.
 *
 * <p>Written against the real registry and the real builder, because the defect was never in the
 * geometry: {@code undoBuild} cleared the CURRENT merged plan, so undoing <i>"put a dome on it"</i>
 * took the castle and the dome together, and there was no previous version anywhere to go back to.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class BuildUndoGameTest {

    private static BuildShape box(String name, int dx, int dy, int dz, int w, int h, int d,
                                  String block) {
        return new BuildShape(BuildShape.Kind.BOX, dx, dy, dz, w, h, d, block, false,
                BuildShape.Anchor.CORNER, name);
    }

    private static BuildPlan keep(String id) {
        return new BuildPlan(id, "Keep", List.of(box("keep", 0, 0, 0, 3, 3, 3,
                "minecraft:stone")));
    }

    private static BuildPlan keepWithDome(String id) {
        return new BuildPlan(id, "Keep", List.of(box("keep", 0, 0, 0, 3, 3, 3, "minecraft:stone"),
                box("dome", 0, 3, 0, 3, 2, 3, "minecraft:gold_block")));
    }

    /** Finish whatever the builder queued, rather than waiting a tick budget out. */
    private static void finishBuilding(net.minecraft.server.level.ServerLevel level) {
        for (int i = 0; i < 64 && Builder.isBusy(); i++) {
            Builder.drainNow(level);
        }
    }

    /**
     * Assert on a block in ABSOLUTE space.
     *
     * <p>Not {@code helper.assertBlockPresent(block, helper.relativePos(abs))}: a test structure
     * carries a ROTATION, so relativePos is not a clean inverse of absolutePos and the round trip
     * lands somewhere else — the first cut of this test failed with
     * {@code Expected Stone, got Air ... (relative: -2,2,-2)} on a castle that was standing
     * exactly where it was put. The builder works in absolute coordinates from the anchor, so the
     * assertion should too.
     */
    private static void assertBlock(GameTestHelper helper,
                                    net.minecraft.server.level.ServerLevel level, BlockPos abs,
                                    net.minecraft.world.level.block.Block want, String why) {
        var got = level.getBlockState(abs).getBlock();
        helper.assertTrue(got == want,
                Component.literal(why + " — expected " + want.getName().getString() + " at " + abs + ", got "
                        + got.getName().getString()));
    }

    @GameTest(template = "empty_test", timeoutTicks = 400, batch = "buildundo")
    public void undoingARefinementLeavesTheBuildingStanding(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        String id = "undo_keep_" + Long.toHexString(at.asLong());

        BuildRegistry.put(server, keep(id));
        BuildRegistry.rememberAnchor(id, at.asLong(), level.dimension());
        Builder.submit(level, at, keep(id));
        finishBuilding(level);

        // The refinement: same id, a dome on top. This is the call that used to make undo lethal.
        BuildRegistry.put(server, keepWithDome(id));
        Builder.submit(level, at, keepWithDome(id));
        finishBuilding(level);
        helper.assertTrue(BuildRegistry.historyDepth(id) == 1,
                Component.literal("the version being replaced was not kept, so there is nothing to go back TO"));

        helper.assertTrue(WishUndo.undoOne(server, MadeKind.BUILD, id), Component.literal("undo reported failure"));
        finishBuilding(level);

        // 1. The castle is still there.
        assertBlock(helper, level, at.offset(1, 1, 1),
                net.minecraft.world.level.block.Blocks.STONE,
                "undo took the castle away with the dome, which is #155 itself");
        // 2. The dome is not.
        assertBlock(helper, level, at.offset(1, 4, 1),
                net.minecraft.world.level.block.Blocks.AIR, "the dome was not reverted");
        // 3. THE OLDER VERSION IS WHAT PERSISTS -- so the next refinement inherits the reverted
        //    castle rather than the undone one. Fixing the world and leaving the record ahead of
        //    it is an earlier issue's R3 bug in a new place.
        BuildPlan now = BuildRegistry.get(id);
        helper.assertTrue(now != null && now.shapes().size() == 1,
                Component.literal("the record still has the dome in it: " + (now == null ? "null" : now.shapes())));
        helper.succeed();
    }

    @GameTest(template = "empty_test", timeoutTicks = 400, batch = "buildundo")
    public void undoingACreationStillRemovesIt(GameTestHelper helper) {
        // The behaviour that was always right and must not regress: with no previous version,
        // undoing a creation means removing it.
        var level = helper.getLevel();
        var server = level.getServer();
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 5));
        String id = "undo_once_" + Long.toHexString(at.asLong());

        BuildRegistry.put(server, keep(id));
        BuildRegistry.rememberAnchor(id, at.asLong(), level.dimension());
        Builder.submit(level, at, keep(id));
        finishBuilding(level);
        helper.assertTrue(BuildRegistry.historyDepth(id) == 0, Component.literal("a creation has no previous version"));

        helper.assertTrue(WishUndo.undoOne(server, MadeKind.BUILD, id), Component.literal("undo reported failure"));
        finishBuilding(level);
        assertBlock(helper, level, at.offset(1, 1, 1),
                net.minecraft.world.level.block.Blocks.AIR,
                "undoing a creation must still remove it");
        helper.succeed();
    }

    @GameTest(template = "empty_test", timeoutTicks = 600, batch = "buildundo")
    public void twoRefinementsWalkBackInOrder(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 9));
        String id = "undo_twice_" + Long.toHexString(at.asLong());

        BuildPlan v1 = keep(id);
        BuildPlan v2 = keepWithDome(id);
        BuildPlan v3 = new BuildPlan(id, "Keep", List.of(
                box("keep", 0, 0, 0, 3, 3, 3, "minecraft:stone"),
                box("dome", 0, 3, 0, 3, 2, 3, "minecraft:gold_block"),
                box("flag", 1, 5, 1, 1, 2, 1, "minecraft:diamond_block")));

        BuildRegistry.rememberAnchor(id, at.asLong(), level.dimension());
        for (BuildPlan v : List.of(v1, v2, v3)) {
            BuildRegistry.put(server, v);
            Builder.submit(level, at, v);
            finishBuilding(level);
        }
        helper.assertTrue(BuildRegistry.historyDepth(id) == 2,
                Component.literal("expected two older versions, got " + BuildRegistry.historyDepth(id)));

        WishUndo.undoOne(server, MadeKind.BUILD, id);
        finishBuilding(level);
        helper.assertTrue(BuildRegistry.get(id).shapes().size() == 2,
                Component.literal("one undo should land on the dome version"));

        WishUndo.undoOne(server, MadeKind.BUILD, id);
        finishBuilding(level);
        helper.assertTrue(BuildRegistry.get(id).shapes().size() == 1,
                Component.literal("two undos should land on the bare keep"));
        assertBlock(helper, level, at.offset(1, 4, 1),
                net.minecraft.world.level.block.Blocks.AIR,
                "two undos should have taken the dome off as well");
        assertBlock(helper, level, at.offset(1, 1, 1),
                net.minecraft.world.level.block.Blocks.STONE,
                "and left the keep standing");
        helper.succeed();
    }
}
