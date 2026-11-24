package com.moddingfromamod.paint;

import net.minecraft.network.chat.Component;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b> — a library picture hangs again, with no agent, wherever the child stands.</b>
 *
 * <p>The starter creeper face is replayed through the same method {@code /wishpaint} runs, for a
 * real {@link ServerPlayer} standing on an empty plot: it must stand its own board up and hang all
 * nine map tiles on it. Counting FILLED MAPS in frames rather than trusting the result — a frame
 * with nothing behind it survives until the next support check and then drops, so the result
 * alone would pass a picture that is gone five seconds later.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class PictureReplayGameTest {

    @GameTest(template = "empty_test", timeoutTicks = 300)
    public static void theStarterPictureHangsOnItsOwnBoard(GameTestHelper helper) {
        ServerPlayer p = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper);
        BlockPos stand = helper.absolutePos(new BlockPos(2, 2, 2));
        // Facing south (yaw 0), so the board stands a few blocks to +z, inside the box below.
        p.snapTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0f, 0f);
        String err = PictureCommand.hang(p, "starter_creeper_face");
        // Gone before anything can fail: a mock left online makes every "nobody is here" test in
        // the run refuse to start (MockPlayerNetwork's own warning, and exactly what happened).
        com.moddingfromamod.testing.MockPlayerNetwork.remove(p);
        helper.assertTrue(err == null, Component.literal("the starter picture did not hang: " + err));
        // Past MapArtPanels' support check (every 100 ticks), so a picture hung on air is gone.
        helper.runAfterDelay(120, () -> {
            List<ItemFrame> frames = helper.getLevel().getEntitiesOfClass(ItemFrame.class,
                    new AABB(net.minecraft.world.phys.Vec3.atCenterOf(stand.offset(-4, -2, 2)), net.minecraft.world.phys.Vec3.atCenterOf(stand.offset(5, 6, 9))), f -> f.getItem().is(Items.FILLED_MAP));
            helper.assertTrue(frames.size() == 9, Component.literal("a 3x3 creeper face is nine map tiles; found "
                    + frames.size()));
            helper.succeed();
        });
    }

    @GameTest(template = "empty_test", timeoutTicks = 60)
    public static void anUnknownPictureIsRefusedByName(GameTestHelper helper) {
        ServerPlayer p = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper);
        String err = PictureCommand.hang(p, "no_such_picture");
        com.moddingfromamod.testing.MockPlayerNetwork.remove(p);
        helper.assertTrue(err != null && err.contains("no_such_picture"),
                Component.literal("an unknown picture must be refused by name, never hung blank: " + err));
        helper.succeed();
    }

    /**
     * <b>Undoing a hung picture gives the world back.</b> {@code /wishpaint} stands a
     * dark-oak board up behind the picture and clears what was in front; undo used to take the
     * frames down and leave the board standing, with what it cleared gone for good. Here glass
     * stands where the board goes up, and one plank is changed by the child afterwards: undo must
     * put the glass back, take the untouched planks away, and leave the child's block as he left it.
     */
    @GameTest(template = "empty_test", batch = "pictureBoardUndo", timeoutTicks = 300)
    public static void undoingAHungPictureTakesItsBoardDownAndGivesBackWhatItCleared(
            GameTestHelper helper) {
        var level = helper.getLevel();
        ServerPlayer p = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gt_board_undo");
        BlockPos stand = helper.absolutePos(new BlockPos(2, 2, 2));
        p.snapTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0f, 0f);
        // Glass across the whole stretch the board can go up in, so whatever cells it takes, some
        // of them had something in them first.
        for (BlockPos q : BlockPos.betweenClosed(stand.offset(-4, 0, 3), stand.offset(4, 4, 8))) {
            level.setBlockAndUpdate(q, net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState());
        }
        String err = PictureCommand.hang(p, "heart");
        com.moddingfromamod.testing.MockPlayerNetwork.remove(p);
        helper.assertTrue(err == null, Component.literal("the library picture did not hang: " + err));
        PaintRecord rec = PaintRegistry.get("heart");
        helper.assertTrue(rec != null && !rec.board().isEmpty(),
                Component.literal("hanging a picture changed blocks and wrote none of them down"));
        long cleared = rec.board().stream().filter(c -> c.put().equals("minecraft:air")).count();
        helper.assertTrue(cleared > 0, Component.literal("no glass was in the picture's way — the fixture is wrong"));
        PaintRecord.BoardCell theChilds = rec.board().stream()
                .filter(c -> c.put().equals("minecraft:dark_oak_planks")).findFirst().orElse(null);
        BlockPos his = theChilds == null ? null : new BlockPos(theChilds.x(), theChilds.y(), theChilds.z());
        if (his != null) {
            level.setBlockAndUpdate(his, net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState());
        }
        helper.assertTrue(com.moddingfromamod.wish.WishUndo.undoOne(level.getServer(), "painting", "heart"),
                Component.literal("undoing the hung picture reported nothing undone"));
        for (PaintRecord.BoardCell c : rec.board()) {
            BlockPos q = new BlockPos(c.x(), c.y(), c.z());
            var now = level.getBlockState(q);
            if (q.equals(his)) {
                helper.assertTrue(now.is(net.minecraft.world.level.block.Blocks.GOLD_BLOCK),
                        Component.literal("undo replaced a block the child changed after the picture went up"));
            } else {
                String nowSpec = net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(now);
                helper.assertTrue(nowSpec.equals(c.was()), Component.literal("the board was not taken down at "
                        + q.toShortString() + ": it is " + nowSpec + ", it was " + c.was()));
            }
        }
        helper.assertTrue(PaintRegistry.get("heart") == null, Component.literal("the placement outlived its undo"));
        helper.succeed();
    }

    /**
     * <b>The pictures we can show off all work (the contract's C2).</b> Every picture the
     * library ships, read from the jar's own index rather than listed here, is hung through
     * {@code /wishpaint}'s method, survives the frame support check, and comes down again under
     * undo with nothing left behind. These are the pictures the website can advertise: a shipped
     * artifact that replays by command, not a button every child has to carry.
     */
    @GameTest(template = "empty_test", batch = "pictureShowcase", timeoutTicks = 2400)
    public static void everyLibraryPictureHangsAndComesDownAgain(GameTestHelper helper) {
        List<String> ids = new java.util.ArrayList<>();
        try (var in = PictureReplayGameTest.class.getResourceAsStream("/starter-library/index.txt")) {
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .split("\n")) {
                String t = line.trim();
                if (t.startsWith("pictures/") && t.endsWith("/painting.json")) {
                    ids.add(t.substring("pictures/".length(), t.length() - "/painting.json".length()));
                }
            }
        } catch (Exception e) {
            helper.fail(Component.literal("could not read the starter library's index: " + e));
            return;
        }
        helper.assertTrue(ids.size() >= 3, Component.literal("the library ships " + ids.size() + " picture(s); the "
                + "contract's C2 needs at least three that work: " + ids));
        showcase(helper, ids, 0);
    }

    private static void showcase(GameTestHelper helper, List<String> ids, int i) {
        if (i >= ids.size()) {
            helper.succeed();
            return;
        }
        String id = ids.get(i);
        var level = helper.getLevel();
        ServerPlayer p = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gt_showcase");
        BlockPos stand = helper.absolutePos(new BlockPos(2, 2, 2));
        p.snapTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0f, 0f);
        String err = PictureCommand.hang(p, id);
        com.moddingfromamod.testing.MockPlayerNetwork.remove(p);
        helper.assertTrue(err == null, Component.literal("library picture '" + id + "' did not hang: " + err));
        AABB near = new AABB(net.minecraft.world.phys.Vec3.atCenterOf(stand.offset(-20, -2, 1)), net.minecraft.world.phys.Vec3.atCenterOf(stand.offset(20, 20, 12)));
        // Past MapArtPanels' support check (every 100 ticks), so a picture hung on air is gone.
        helper.runAfterDelay(120, () -> {
            long frames = level.getEntitiesOfClass(ItemFrame.class, near,
                    f -> f.getItem().is(Items.FILLED_MAP)).size();
            helper.assertTrue(frames > 0, Component.literal("library picture '" + id + "' left no maps on the wall"));
            helper.assertTrue(com.moddingfromamod.wish.WishUndo.undoOne(level.getServer(),
                    "painting", id), Component.literal("undoing library picture '" + id + "' reported nothing undone"));
            long left = level.getEntitiesOfClass(ItemFrame.class, near,
                    f -> f.getItem().is(Items.FILLED_MAP)).size();
            helper.assertTrue(left == 0, Component.literal("library picture '" + id + "' left " + left
                    + " of its " + frames + " maps behind after undo"));
            System.out.println("PICTURE SHOWCASE " + id + ": " + frames + " map tile(s), hung and undone");
            showcase(helper, ids, i + 1);
        });
    }
}
