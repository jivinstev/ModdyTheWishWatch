package com.moddingfromamod.agent.tools;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.runtime.ClearView;
import com.moddingfromamod.testing.GateShards;
import com.moddingfromamod.testing.TestPlots;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Does the camera's line-of-sight check work against a REAL world?</b>
 *
 * <p>{@code ClearViewTest} drives the geometry through a fake {@link ClearView.Sight}, which is
 * what makes the rule cheap to test — and leaves the ADAPTER, the half that actually asks
 * Minecraft, unexercised. That is the split {@code CLAUDE.md} warns about in as many words:
 * <i>a rule tested only where it is DEFINED is not tested where it RUNS.</i> an earlier issue's melter feed
 * gate was green for months while hand-dropping past the tool the agent really calls.
 *
 * <p>So this builds an actual wall between an actual camera and an actual subject, and asks
 * {@code PhotographTool.sight} the same two questions the wish card asks it.
 */
@GameTestHolder(GateShards.DEFAULT)
public class ClearViewGameTest {

    private static final TestPlots.Region PLOT = TestPlots.CLEAR_VIEW;
    private static final double EYE = 1.62;

    /** A wall tall and wide enough that a camera cannot simply see round the end of it. */
    private static void wall(GameTestHelper helper, BlockPos at, int half, int height) {
        for (int dz = -half; dz <= half; dz++) {
            for (int dy = 0; dy < height; dy++) {
                helper.getLevel().setBlock(at.offset(0, dy, dz),
                        Blocks.STONE.defaultBlockState(), 2);
            }
        }
    }

    @GameTest(template = "empty_test", timeoutTicks = 400, batch = "clearview")
    public void aWallBetweenTheCameraAndTheSubjectIsSeenAndClimbed(GameTestHelper helper) {
        BlockPos subject = PLOT.at(helper, 0, 0, 0, 0);
        BlockPos wallAt = subject.offset(20, 0, 0);
        wall(helper, wallAt, 8, 12);

        double aimX = subject.getX() + 0.5;
        double aimY = subject.getY() + 2.0;
        double aimZ = subject.getZ() + 0.5;
        double camX = subject.getX() + 40.5;
        double camZ = subject.getZ() + 0.5;
        double feetY = subject.getY();

        var sight = PhotographTool.sight(helper.getLevel(), null);

        // The adapter has to AGREE that the wall is in the way — if it does not, everything below
        // is vacuous and would pass on a broken clip.
        helper.assertTrue(!sight.clear(camX, feetY + EYE, camZ, aimX, aimY, aimZ),
                Component.literal("the sight adapter says a 12-block stone wall standing between the camera and the "
                        + "subject is no obstruction, so it is not asking the world anything"));

        ClearView.Seat seat = ClearView.find(sight, camX, feetY, camZ, 90f,
                aimX, aimY, aimZ, EYE);

        helper.assertTrue(!seat.blind(),
                Component.literal("no seat cleared a 12-block wall in " + ClearView.MAX_RISE + " rises"));
        helper.assertTrue(sight.clear(seat.x(), seat.feetY() + EYE, seat.z(), aimX, aimY, aimZ),
                Component.literal("the seat it settled on still cannot see the subject — the correction moved the "
                        + "camera without solving anything"));
        helper.assertTrue(seat.rose() > 0,
                Component.literal("it claims it never had to climb, past a wall it just agreed was in the way"));
        helper.succeed();
    }

    @GameTest(template = "empty_test", timeoutTicks = 400, batch = "clearview")
    public void openGroundLeavesTheFramingExactlyWhereItWas(GameTestHelper helper) {
        // ⚠ THE OVER-REACH GUARD, and it is the one that matters most. This correction runs on
        // EVERY building and train photograph now. If it moved the camera on clear ground it would
        // silently re-frame every wish card in the game — and every counter would still read clean,
        // which is the exact family of failure is.
        BlockPos subject = PLOT.at(helper, 1, 0, 0, 0);
        double aimX = subject.getX() + 0.5;
        double aimY = subject.getY() + 2.0;
        double aimZ = subject.getZ() + 0.5;
        double camX = subject.getX() + 30.5;
        double feetY = subject.getY() + 6;

        var sight = PhotographTool.sight(helper.getLevel(), null);
        ClearView.Seat seat = ClearView.find(sight, camX, feetY, aimZ, 90f,
                aimX, aimY, aimZ, EYE);

        helper.assertTrue(Math.abs(seat.x() - camX) < 1e-6 && Math.abs(seat.feetY() - feetY) < 1e-6,
                Component.literal("an unobstructed camera was moved from (" + camX + ", " + feetY + ") to ("
                        + seat.x() + ", " + seat.feetY() + "), so every clear shot in the game has "
                        + "just been re-framed"));
        helper.assertTrue(seat.rose() == 0 && !seat.slid() && !seat.blind(),
                Component.literal("an unobstructed camera reported climbing/sliding/blindness"));
        helper.succeed();
    }

    @GameTest(template = "empty_test", timeoutTicks = 400, batch = "clearview")
    public void aCameraBuriedInTerrainIsLiftedOutOfIt(GameTestHelper helper) {
        // A camera inside a block photographs BLACK, which is indistinguishable from the dark
        // interior that interior shots exist to reveal (docs/CAMERA.md).
        BlockPos subject = PLOT.at(helper, 2, 0, 0, 0);
        BlockPos camAt = subject.offset(25, 0, 0);
        for (int dy = 0; dy < 3; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    helper.getLevel().setBlock(camAt.offset(dx, dy, dz),
                            Blocks.DIRT.defaultBlockState(), 2);
                }
            }
        }
        var sight = PhotographTool.sight(helper.getLevel(), null);
        double feetY = camAt.getY();
        helper.assertTrue(sight.solid(camAt.getX() + 0.5, feetY + 0.6, camAt.getZ() + 0.5),
                Component.literal("the adapter cannot tell it is inside dirt"));

        ClearView.Seat seat = ClearView.find(sight, camAt.getX() + 0.5, feetY, camAt.getZ() + 0.5,
                90f, subject.getX() + 0.5, subject.getY() + 2.0, subject.getZ() + 0.5, EYE);
        helper.assertTrue(!sight.solid(seat.x(), seat.feetY() + 0.6, seat.z()),
                Component.literal("the camera is still inside a block at y " + seat.feetY()
                        + ", so this frame is a black rectangle"));
        helper.succeed();
    }
}
