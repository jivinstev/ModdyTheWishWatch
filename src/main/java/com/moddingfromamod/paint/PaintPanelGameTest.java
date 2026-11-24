package com.moddingfromamod.paint;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.GateShards;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B for the painted panel.</b>
 *
 * <p>Everything a headless server can honestly say about a thing whose whole job is to be looked at:
 * it registers, it places, it remembers its picture across a save, and it refuses a key that could
 * not name a file. (Whether Create would carry it on a train was asked here too, until Create went in
 *.)
 *
 * <p>What it deliberately does NOT claim: that anything was drawn. Rendering is client-only, which
 * is what {@code PaintPanelClientTest} is for.
 */
@GameTestHolder(GateShards.DEFAULT)
public class PaintPanelGameTest {

    private static final Identifier PANEL_ID =
            Identifier.fromNamespaceAndPath(ModdingFromAModMod.MODID, ModPaint.PANEL_ID);

    /**
     * All three registrations landed.
     *
     * <p>The block-entity type is the one worth asserting rather than assuming: an unregistered type
     * does not fail when the block is placed, it fails when the world is <b>saved</b>, because that
     * is the first time anything asks {@code getType()} for an id. A world that works perfectly and
     * then cannot be closed is a much worse bug than one that never starts.
     */
    @GameTest(template = "empty_test")
    public static void theBlockItsItemAndItsBlockEntityAreAllRegistered(GameTestHelper helper) {
        helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(PANEL_ID), Component.literal("no paint_panel block"));
        helper.assertTrue(BuiltInRegistries.ITEM.containsKey(PANEL_ID),
                Component.literal("no paint_panel item — a block you cannot obtain is not a feature"));
        helper.assertTrue(BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(PANEL_ID),
                Component.literal("no paint_panel block entity type — the world would fail on SAVE, not on place"));
        helper.assertTrue(
                BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(PANEL_ID).isValid(
                        ModPaint.PAINT_PANEL.get().defaultBlockState()),
                Component.literal("the block entity type does not claim its own block"));
        helper.succeed();
    }

    /** Placing one gives you a real block entity, facing the way the blockstate says. */
    @GameTest(template = "empty_test")
    public static void aPlacedPanelHasABlockEntityAndAFacing(GameTestHelper helper) {
        BlockPos at = new BlockPos(1, 2, 1);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            helper.setBlock(at, ModPaint.PAINT_PANEL.get().defaultBlockState()
                    .setValue(HorizontalDirectionalBlock.FACING, facing));
            BlockState state = helper.getBlockState(at);
            helper.assertTrue(state.getValue(HorizontalDirectionalBlock.FACING) == facing,
                    Component.literal("the panel did not keep its facing"));
            BlockEntity be = helper.getBlockEntity(at, net.minecraft.world.level.block.entity.BlockEntity.class);
            helper.assertTrue(be instanceof PaintPanelBlockEntity,
                    Component.literal("no PaintPanelBlockEntity at a placed panel: " + be));
        }
        helper.succeed();
    }

    /**
     * The picture survives a save/load round trip — and it is the SAME round trip a train uses.
     *
     * <p>Create rebuilds a contraption's block entities from their saved tags when the carriage
     * assembles ({@code ClientContraption.readBlockEntity}), so a panel that kept its key anywhere
     * other than NBT would ride the train blank while looking perfect standing still.
     */
    @GameTest(template = "empty_test")
    public static void thePictureIsRememberedInNbtAndComesBack(GameTestHelper helper) {
        BlockPos at = new BlockPos(1, 2, 1);
        helper.setBlock(at, ModPaint.PAINT_PANEL.get().defaultBlockState());
        PaintPanelBlockEntity be = (PaintPanelBlockEntity) helper.getBlockEntity(at, net.minecraft.world.level.block.entity.BlockEntity.class);
        be.setArt("spikor_face", 3, 2);

        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        CompoundTag saved = be.saveWithFullMetadata(registries);
        helper.assertTrue(saved.getStringOr(PaintPanelBlockEntity.ART_KEY, "").equals("spikor_face"),
                Component.literal("the key is not in the saved tag: " + saved));

        BlockPos elsewhere = new BlockPos(3, 2, 1);
        helper.setBlock(elsewhere, ModPaint.PAINT_PANEL.get().defaultBlockState());
        PaintPanelBlockEntity reloaded = (PaintPanelBlockEntity) helper.getBlockEntity(elsewhere, net.minecraft.world.level.block.entity.BlockEntity.class);
        com.moddingfromamod.compat.EntityNbt.load(reloaded, saved, registries);
        helper.assertTrue(reloaded.artKey().equals("spikor_face"),
                Component.literal("the picture did not survive the round trip: " + reloaded.artKey()));
        helper.assertTrue(reloaded.widthBlocks() == 3 && reloaded.heightBlocks() == 2,
                Component.literal("the picture's size did not survive: " + reloaded.widthBlocks()
                        + "x" + reloaded.heightBlocks()));

        // The update tag is a different path from the save tag, and it is the one the CLIENT sees.
        // A panel right on the server and blank on the client is the grand-prize item frame again.
        CompoundTag update = reloaded.getUpdateTag(registries);
        helper.assertTrue(update.getStringOr(PaintPanelBlockEntity.ART_KEY, "").equals("spikor_face"),
                Component.literal("the key is not in the update tag, so no client would ever hear about it"));
        helper.succeed();
    }

    /**
     * A key that could not name a file is refused, and damaged data costs the picture rather than
     * the world.
     */
    @GameTest(template = "empty_test")
    public static void anImpossibleKeyLeavesABlankCanvasRatherThanBreaking(GameTestHelper helper) {
        BlockPos at = new BlockPos(1, 2, 1);
        helper.setBlock(at, ModPaint.PAINT_PANEL.get().defaultBlockState());
        PaintPanelBlockEntity be = (PaintPanelBlockEntity) helper.getBlockEntity(at, net.minecraft.world.level.block.entity.BlockEntity.class);

        for (String bad : new String[]{"../../etc/passwd", "Spikor Face", "", "a/b"}) {
            be.setArt(bad, 1, 1);
            helper.assertTrue(be.artKey().isEmpty(),
                    Component.literal("accepted a key that cannot name a file: \"" + bad + "\""));
        }
        // And a tag full of nonsense loads as a blank panel instead of throwing.
        CompoundTag junk = new CompoundTag();
        junk.putString("id", ModdingFromAModMod.MODID + ":" + ModPaint.PANEL_ID);
        junk.putString(PaintPanelBlockEntity.ART_KEY, "NOT A KEY");
        junk.putInt(PaintPanelBlockEntity.WIDTH, 9999);
        junk.putInt(PaintPanelBlockEntity.HEIGHT, -4);
        com.moddingfromamod.compat.EntityNbt.load(be, junk, helper.getLevel().registryAccess());
        helper.assertTrue(be.artKey().isEmpty(), Component.literal("a damaged key was kept: " + be.artKey()));
        helper.assertTrue(be.widthBlocks() == PanelGeometry.MAX_SPAN && be.heightBlocks() == 1,
                Component.literal("a damaged size was kept: " + be.widthBlocks() + "x" + be.heightBlocks()));
        helper.succeed();
    }

    /**
     * <b>The picture itself survives the round trip, not only its name.</b>
     *
     * <p>The key is a pointer into a folder; the recipe is the picture. A panel that kept only the
     * key would look perfect until the block was carried somewhere the folder is not — which for a
     * contraption is every assembly, since Create rebuilds its block entities from their saved tags.
     */
    @GameTest(template = "empty_test")
    public static void thePaintRecipeSurvivesTheRoundTripAndNotJustTheKey(GameTestHelper helper) {
        BlockPos at = new BlockPos(1, 2, 1);
        helper.setBlock(at, ModPaint.PAINT_PANEL.get().defaultBlockState());
        PaintPanelBlockEntity be = (PaintPanelBlockEntity) helper.getBlockEntity(at, net.minecraft.world.level.block.entity.BlockEntity.class);
        PaintRecipe face = PaintPanels.demoFace();
        be.setArt("demo_face", face, 2, 2);

        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        CompoundTag saved = be.saveWithFullMetadata(registries);
        helper.assertTrue(saved.contains(PaintPanelBlockEntity.RECIPE),
                Component.literal("the picture is not in the saved tag at all: " + saved.keySet()));

        BlockPos elsewhere = new BlockPos(3, 2, 1);
        helper.setBlock(elsewhere, ModPaint.PAINT_PANEL.get().defaultBlockState());
        PaintPanelBlockEntity back = (PaintPanelBlockEntity) helper.getBlockEntity(elsewhere, net.minecraft.world.level.block.entity.BlockEntity.class);
        com.moddingfromamod.compat.EntityNbt.load(back, saved, registries);
        helper.assertTrue(back.recipe().rects().equals(face.rects()),
                Component.literal("the shapes changed across the round trip: " + back.recipe().rects().size()
                        + " of " + face.rects().size() + " survived"));
        helper.assertTrue(back.recipe().size() == face.size(),
                Component.literal("the resolution changed across the round trip"));

        // And the UPDATE tag too — that is the copy a client is sent, and a client that never hears
        // the shapes is a client that can only ever draw what happens to be on its disk already.
        helper.assertTrue(back.getUpdateTag(registries).contains(PaintPanelBlockEntity.RECIPE),
                Component.literal("the picture is not in the update tag, so no client would ever be told it"));
        helper.succeed();
    }

    /**
     * Painting really writes an image, and the panel really points at it.
     *
     * <p>Drives {@link PaintPanels#paint}, which is the same call the command and (later) the wish
     * tool make.
     *
     * <p><b>It does NOT check the bake-then-set ORDER, and it used to say it did.</b> Both the file
     * and the key are there at the end whichever way round they happened, so the name was a claim
     * the body could not support — the kind of test that reads as coverage and is not. The order's
     * only observable consequence is client-side: a client told about the panel before the image
     * exists caches the unpainted fallback until the file's mtime moves. That is exactly what Gate
     * C's {@code drawnUnpainted} counter measures, and it is where the check belongs.
     */
    @GameTest(template = "empty_test")
    public static void paintingWritesAnImageAndPointsThePanelAtIt(GameTestHelper helper) {
        BlockPos at = new BlockPos(1, 2, 1);
        helper.setBlock(at, ModPaint.PAINT_PANEL.get().defaultBlockState());

        PaintPanels.Result r = PaintPanels.paint(helper.getLevel(), helper.absolutePos(at),
                "gametest_face", PaintPanels.demoFace(), 2, 1);
        helper.assertTrue(r.ok(), Component.literal("painting failed: " + r.message()));
        helper.assertTrue(java.nio.file.Files.isRegularFile(
                        com.moddingfromamod.runtime.ModelRepository.textureFile("gametest_face")),
                Component.literal("no image was written for the picture"));

        PaintPanelBlockEntity be = (PaintPanelBlockEntity) helper.getBlockEntity(at, net.minecraft.world.level.block.entity.BlockEntity.class);
        helper.assertTrue("gametest_face".equals(be.artKey()), Component.literal("the panel did not take the key"));
        helper.assertTrue(!be.recipe().isEmpty(), Component.literal("the panel did not take the shapes"));
        helper.assertTrue(be.widthBlocks() == 2 && be.heightBlocks() == 1,
                Component.literal("the panel did not take the size"));
        helper.succeed();
    }

    /**
     * Every way of asking for something impossible answers in words, and changes nothing.
     *
     * <p>These all come from an agent acting on a child's description, so each is one sentence away.
     * An exception here would surface to a child as a wish that simply stopped.
     */
    @GameTest(template = "empty_test")
    public static void animpossibleRequestIsAnsweredRatherThanThrown(GameTestHelper helper) {
        BlockPos at = new BlockPos(1, 2, 1);
        helper.setBlock(at, ModPaint.PAINT_PANEL.get().defaultBlockState());
        BlockPos world = helper.absolutePos(at);
        ServerLevel level = helper.getLevel();

        helper.assertTrue(!PaintPanels.paint(level, world, "NOT A KEY",
                PaintPanels.demoFace(), 1, 1).ok(), Component.literal("an impossible key was accepted"));
        helper.assertTrue(!PaintPanels.paint(level, world, "empty",
                PaintRecipe.blank(), 1, 1).ok(), Component.literal("a picture with no shapes was accepted"));
        helper.assertTrue(!PaintPanels.paint(level, world, "bad_json",
                "{not json", 1, 1).ok(), Component.literal("malformed JSON was accepted"));
        helper.assertTrue(!PaintPanels.paint(level, helper.absolutePos(new BlockPos(5, 2, 5)),
                "nowhere", PaintPanels.demoFace(), 1, 1).ok(),
                Component.literal("painting thin air was accepted"));

        // Nothing changed: the panel is still blank.
        PaintPanelBlockEntity be = (PaintPanelBlockEntity) helper.getBlockEntity(at, net.minecraft.world.level.block.entity.BlockEntity.class);
        helper.assertTrue(be.artKey().isEmpty() && be.recipe().isEmpty(),
                Component.literal("a refused request still altered the panel"));

        // And the real JSON path works, so the refusals above are not just "everything fails".
        helper.assertTrue(PaintPanels.paint(level, world, "json_ok",
                        "{\"size\":64,\"rects\":[{\"x\":0,\"y\":0,\"w\":64,\"h\":64,"
                        + "\"colour\":\"red\"}]}", 1, 1).ok(),
                Component.literal("the JSON path refused a perfectly good picture, so this test proves nothing"));
        helper.succeed();
    }

    /** The command exists, with both the demo and the real form. */
    @GameTest(template = "empty_test")
    public static void thePaintCommandIsRegistered(GameTestHelper helper) {
        var root = helper.getLevel().getServer().getCommands().getDispatcher().getRoot();
        helper.assertTrue(root.getChild("paint") != null, Component.literal("/paint is not registered"));
        helper.assertTrue(root.getChild("paint").getChild("demo") != null,
                Component.literal("/paint demo is missing — a person cannot try it without writing JSON"));
        helper.succeed();
    }

    /**
     * <b>Repainting a mural does not shrink it.</b>
     *
     * <p>A real defect, found by a client gate aiming at a three-block picture and reported as a
     * wrong COLOUR: {@code /paint <key> <json>} passed 1x1, so the second painting quietly resized
     * the panel to a single block. The picture was right and the thing it was on was not, and
     * nothing anywhere failed.
     */
    @GameTest(template = "empty_test")
    public static void repaintingAPanelKeepsTheSizeItWasHungAt(GameTestHelper helper) {
        BlockPos at = new BlockPos(1, 2, 1);
        helper.setBlock(at, ModPaint.PAINT_PANEL.get().defaultBlockState());
        BlockPos world = helper.absolutePos(at);
        ServerLevel level = helper.getLevel();

        helper.assertTrue(PaintPanels.paint(level, world, "mural", PaintPanels.demoFace(), 3, 2).ok(),
                Component.literal("the first painting failed"));
        PaintPanelBlockEntity be = (PaintPanelBlockEntity) helper.getBlockEntity(at, net.minecraft.world.level.block.entity.BlockEntity.class);
        helper.assertTrue(be.widthBlocks() == 3 && be.heightBlocks() == 2, Component.literal("the size did not take"));

        // 0,0 — what the command passes. The picture changes; the size must not.
        helper.assertTrue(PaintPanels.paint(level, world, "mural2",
                        "{\"size\":64,\"rects\":[{\"x\":0,\"y\":0,\"w\":64,\"h\":64,"
                        + "\"colour\":\"blue\"}]}", 0, 0).ok(),
                Component.literal("the repaint failed"));
        helper.assertTrue(be.widthBlocks() == 3 && be.heightBlocks() == 2,
                Component.literal("repainting shrank the mural to " + be.widthBlocks() + "x" + be.heightBlocks()));
        helper.assertTrue("mural2".equals(be.artKey()), Component.literal("the repaint did not change the picture"));

        // And an explicit size still resizes, or "keep" would have eaten the ability to change it.
        helper.assertTrue(PaintPanels.paint(level, world, "mural3",
                PaintPanels.demoFace(), 1, 1).ok(), Component.literal("an explicit resize failed"));
        helper.assertTrue(be.widthBlocks() == 1 && be.heightBlocks() == 1,
                Component.literal("an explicit size was ignored"));
        helper.succeed();
    }

    /**
     * <b>The panel draws nothing of its own, and hides nothing behind it.</b>
     *
     * <p>Both halves, in one test, because either alone looks like the other one is broken. A
     * picture with a transparent background used to reveal the panel's own {@code cube_all} model
     * in white concrete — not the carriage. Make the model invisible but leave the block occluding
     * and it is worse: the panel sits directly against the carriage skin, so the skin's face is
     * culled and you see <i>through</i> the train instead of at it.
     *
     * <p>Runtime rather than unit, because both are properties of a real {@code BlockState} in a
     * real level. Neither is a claim about how it LOOKS — only a photograph settles that, which is
     * what {@code PaintPanelClientTest} is for.
     */
    @GameTest(template = "empty_test")
    public static void thePanelIsDrawnOnlyByItsRendererAndNeverHidesTheBlockBehindIt(
            GameTestHelper helper) {
        BlockPos at = new BlockPos(1, 2, 1);
        helper.setBlock(at, ModPaint.PAINT_PANEL.get().defaultBlockState());
        BlockState state = helper.getBlockState(at);

        helper.assertTrue(
                state.getRenderShape() == net.minecraft.world.level.block.RenderShape.INVISIBLE,
                Component.literal("the panel contributes block-model quads again, so a transparent picture shows the "
                        + "panel's own texture rather than what is behind it"));
        helper.assertFalse(state.canOcclude(),
                Component.literal("the panel occludes again, so the carriage face it is pressed against is culled — "
                        + "a transparent picture becomes a hole through the train"));
        helper.succeed();
    }
}
