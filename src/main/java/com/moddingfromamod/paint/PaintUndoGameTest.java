package com.moddingfromamod.paint;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.MadeKind;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.testing.GateShards;

/**
 * <b>Gate B for putting a picture back.</b>
 *
 * <p>"Stop" is universal and "undo" is best-effort, and the whole point of {@code UndoContract} is
 * that we only offer the second when it is true. Painting claims to be reversible, so these are the
 * cases that claim has to survive — the two a child actually produces, which need <i>different</i>
 * reversals and cannot be told apart from the world after the fact.
 */
@GameTestHolder(GateShards.CI)
public class PaintUndoGameTest {

    /**
     * A picture painted where there was no panel: the inverse is to take the panel away.
     *
     * <p>Leaving a blank white cube behind would be a wish that was "undone" and still visibly
     * there, which is the failure the button exists to avoid.
     */
    @GameTest(template = "empty_test")
    public static void undoingAFreshPaintingTakesThePanelAway(GameTestHelper helper) {
        BlockPos at = new BlockPos(1, 2, 1);
        ServerLevel level = helper.getLevel();
        BlockPos world = helper.absolutePos(at);

        helper.setBlock(at, ModPaint.PAINT_PANEL.get().defaultBlockState());
        helper.assertTrue(PaintPanels.paint(level, world, "fresh",
                PaintPanels.demoFace(), 2, 1).ok(), Component.literal("painting failed"));
        PaintRegistry.put(level.getServer(), new PaintRecord("fresh",
                level.dimension().identifier().toString(), world.getX(), world.getY(), world.getZ(),
                "fresh", true, "", PaintRecipe.blank(), 1, 1));

        boolean undone = com.moddingfromamod.wish.WishUndo.undoOne(
                level.getServer(), MadeKind.PAINTING, "fresh");
        helper.assertTrue(undone, Component.literal("undo reported that it did nothing"));
        helper.assertBlockNotPresent(ModPaint.PAINT_PANEL.get(), at);
        helper.assertTrue(PaintRegistry.get("fresh") == null,
                Component.literal("the record survived its own undo, so undoing twice would report success twice"));
        helper.succeed();
    }

    /**
     * A repaint of a panel that was already painted: the inverse is <b>the picture that was on it</b>.
     *
     * <p>This is the case nothing else in the mod could reverse. The block entity holds only the
     * CURRENT picture, so without {@code PaintRecord} keeping the previous recipe an undo could stop
     * the wish and never put it back — and "no, the other one" is the commonest thing a child says.
     */
    @GameTest(template = "empty_test")
    public static void undoingARepaintRestoresThePictureThatWasThere(GameTestHelper helper) {
        BlockPos at = new BlockPos(1, 2, 1);
        ServerLevel level = helper.getLevel();
        BlockPos world = helper.absolutePos(at);
        helper.setBlock(at, ModPaint.PAINT_PANEL.get().defaultBlockState());

        // The picture he had, and liked.
        helper.assertTrue(PaintPanels.paint(level, world, "first",
                PaintPanels.demoFace(), 3, 2).ok(), Component.literal("the first painting failed"));
        PaintPanelBlockEntity be = (PaintPanelBlockEntity) helper.getBlockEntity(at, net.minecraft.world.level.block.entity.BlockEntity.class);
        PaintRecipe wanted = be.recipe();
        int wantedWide = be.widthBlocks();

        // The one that replaced it — recorded the way the tool records it.
        PaintRecord record = new PaintRecord("second",
                level.dimension().identifier().toString(), world.getX(), world.getY(), world.getZ(),
                "second", false, "first", wanted, wantedWide, be.heightBlocks());
        helper.assertTrue(PaintPanels.paint(level, world, "second",
                "{\"size\":64,\"rects\":[{\"x\":0,\"y\":0,\"w\":64,\"h\":64,\"colour\":\"blue\"}]}",
                0, 0).ok(), Component.literal("the repaint failed"));
        PaintRegistry.put(level.getServer(), record);
        helper.assertTrue("second".equals(be.artKey()), Component.literal("the repaint did not take"));

        helper.assertTrue(com.moddingfromamod.wish.WishUndo.undoOne(
                level.getServer(), MadeKind.PAINTING, "second"), Component.literal("undo reported it did nothing"));

        helper.assertTrue(be.artKey().equals("first"),
                Component.literal("the panel shows '" + be.artKey() + "' rather than the picture that was there"));
        helper.assertTrue(be.recipe().rects().equals(wanted.rects()),
                Component.literal("the shapes were not restored: " + be.recipe().rects().size() + " of "
                        + wanted.rects().size()));
        helper.assertTrue(be.widthBlocks() == wantedWide,
                Component.literal("undo restored the picture at the wrong size"));
        // And the IMAGE is back on disk, not merely the key — a key pointing at an image the
        // repaint overwrote would draw the picture we just undid.
        helper.assertTrue(java.nio.file.Files.isRegularFile(
                        com.moddingfromamod.runtime.ModelRepository.textureFile("first")),
                Component.literal("the restored picture has a key but no image"));
        helper.succeed();
    }

    /** Undo says NO rather than yes when the panel is gone — a mined wall, or a train that left. */
    @GameTest(template = "empty_test")
    public static void undoIsHonestWhenThePanelHasGone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos world = helper.absolutePos(new BlockPos(1, 2, 1));
        PaintRegistry.put(level.getServer(), new PaintRecord("vanished",
                level.dimension().identifier().toString(), world.getX(), world.getY(), world.getZ(),
                "vanished", true, "", PaintRecipe.blank(), 1, 1));
        // Nothing is there — the block was never placed.
        helper.assertTrue(!com.moddingfromamod.wish.WishUndo.undoOne(
                        level.getServer(), MadeKind.PAINTING, "vanished"),
                Component.literal("undo claimed to reverse a picture that is not in the world"));
        helper.succeed();
    }

    /** The record survives the trip through its file, which is what makes undo work next session. */
    @GameTest(template = "empty_test")
    public static void theRegistrySurvivesBeingWrittenAndReadBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PaintRecipe before = PaintPanels.demoFace();
        PaintRegistry.put(level.getServer(), new PaintRecord("saved", "minecraft:overworld",
                7, 8, 9, "saved", false, "older", before, 3, 2));

        // READ THE DISK, DO NOT WIPE THE REGISTRY — same seam and same reason as
        // TrainGameTest.aDesignedTrainSurvivesAReload. clear() empties a map every
        // concurrently-running test is reading, and "clear() did not clear" was an implementation
        // detail asserted on the way to the claim that actually matters.
        PaintRecord back = PaintRegistry.savedOnDisk(level.getServer(), "saved");
        helper.assertTrue(back != null, Component.literal("the record did not come back from disk"));
        helper.assertTrue(back.previousRecipe().rects().equals(before.rects()),
                Component.literal("the previous picture did not survive the file — an undo next session would have "
                        + "a key and no shapes"));
        helper.assertTrue(back.x() == 7 && back.y() == 8 && back.z() == 9
                        && back.previousWidth() == 3 && back.previousHeight() == 2,
                Component.literal("the record came back changed: " + back));
        helper.succeed();
    }

    /** Both tools are really offered to the agent — a tool nobody is told about is not a feature. */
    @GameTest(template = "empty_test")
    public static void theAgentIsOfferedBothPaintingTools(GameTestHelper helper) {
        var tools = com.moddingfromamod.agent.WishAgent.buildTools();
        helper.assertTrue(tools.byName("paint_surface") != null,
                Component.literal("paint_surface is not in the tool registry, so no wish can ever paint anything"));
        helper.assertTrue(tools.byName("remove_painting") != null,
                Component.literal("remove_painting is not registered, yet UndoContract names it"));
        helper.succeed();
    }
}
