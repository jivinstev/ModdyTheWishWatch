package com.moddingfromamod.event;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The staged-events foundation, in a real world</b> (E1): whatever an event writes comes
 * back, a chest is never touched, the water it pours is never left ready to flow, a block the child
 * put in the water survives the undo, and <b>a server that stops mid-event gives the world back</b>
 * when it starts again.
 *
 * <p>The director ticks on the server's own tick, so these tests stage an event and then wait on
 * the world rather than driving the motion by hand.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class EventDirectorGameTest {

    private static final String BATCH = "events";
    private static final AtomicInteger SEQ = new AtomicInteger();

    private static String id(String what) {
        return "test_" + what + "_" + SEQ.incrementAndGet();
    }

    /** A 3x2x3 box of cells in the plot, above its floor. */
    private static List<BlockPos> box(GameTestHelper helper) {
        List<BlockPos> out = new ArrayList<>();
        for (int x = 1; x <= 3; x++) {
            for (int y = 2; y <= 3; y++) {
                for (int z = 1; z <= 3; z++) {
                    out.add(helper.absolutePos(new BlockPos(x, y, z)));
                }
            }
        }
        return out;
    }

    /** A motion that writes {@code state} into every cell on its first tick, then waits {@code hold} ticks. */
    private static EventMotion fill(List<BlockPos> cells, BlockState state, int hold) {
        return (w, age) -> {
            if (age == 0) {
                for (BlockPos p : cells) {
                    w.put(p, state);
                }
            }
            return age >= hold;
        };
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void everyBlockAnEventWritesComesBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> cells = box(helper);
        BlockPos flower = cells.get(0);
        level.setBlock(flower, Blocks.POPPY.defaultBlockState(), 3);
        List<BlockState> before = cells.stream().map(level::getBlockState).toList();

        String id = id("restore");
        EventDirector.start(level, id, "", EventScale.LITTLE, EventGuard.Policy.INTO_OPEN, true,
                fill(cells, Blocks.WATER.defaultBlockState(), 3));
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, id);
        helper.runAfterDelay(1, () -> {
            helper.assertTrue(level.getBlockState(flower).is(Blocks.WATER),
                    Component.literal("the wave should have covered the poppy"));
            helper.assertTrue(EventDirector.get(level.getServer(), id).changed() == cells.size(),
                    Component.literal("every cell should be in the journal"));
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(EventDirector.get(level.getServer(), id) == null,
                    Component.literal("the event should have finished and been forgotten"));
            for (int i = 0; i < cells.size(); i++) {
                helper.assertTrue(level.getBlockState(cells.get(i)) == before.get(i),
                        Component.literal("cell " + i + " should be back to " + before.get(i)
                                + " but is " + level.getBlockState(cells.get(i))));
            }
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void aChestAndABuiltWallAreNeverTouched(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chest = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos bricks = helper.absolutePos(new BlockPos(2, 2, 1));
        BlockPos dirt = helper.absolutePos(new BlockPos(3, 2, 1));
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ((ChestBlockEntity) level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 5));
        level.setBlock(bricks, Blocks.STONE_BRICKS.defaultBlockState(), 3);
        level.setBlock(dirt, Blocks.DIRT.defaultBlockState(), 3);

        String id = id("guard");
        List<Boolean> wrote = new ArrayList<>();
        EventDirector.start(level, id, "", EventScale.LITTLE, EventGuard.Policy.TERRAIN, false,
                (w, age) -> {
                    wrote.add(w.put(chest, Blocks.AIR.defaultBlockState()));
                    wrote.add(w.put(bricks, Blocks.AIR.defaultBlockState()));
                    wrote.add(w.put(dirt, Blocks.AIR.defaultBlockState()));
                    return true;
                });
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, id);
        helper.succeedWhen(() -> {
            helper.assertTrue(wrote.size() == 3, Component.literal("the motion should have run"));
            helper.assertTrue(!wrote.get(0) && !wrote.get(1) && wrote.get(2),
                    Component.literal("a crater may cut dirt but never a chest or his bricks: " + wrote));
            helper.assertTrue(level.getBlockState(chest).is(Blocks.CHEST), Component.literal("the chest is still there"));
            helper.assertTrue(((ChestBlockEntity) level.getBlockEntity(chest)).getItem(0).getCount() == 5,
                    Component.literal("and so are his diamonds"));
            helper.assertTrue(level.getBlockState(bricks).is(Blocks.STONE_BRICKS), Component.literal("and his wall"));
            StagedEvent e = EventDirector.get(level.getServer(), id);
            helper.assertTrue(e != null && e.phase() == EventPhase.KEPT && e.refused() == 2,
                    Component.literal("a crater stays until undo, having refused two cells"));
            EventDirector.undo(level.getServer(), id);
            EventDirector.tickAll(level.getServer());
            helper.assertTrue(level.getBlockState(dirt).is(Blocks.DIRT), Component.literal("undo gives the dirt back"));
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void theWaterAnEventPoursIsNeverLeftReadyToFlow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> cells = box(helper);
        // The control: water placed the ordinary way, with the same flags, IS scheduled to spread.
        // Without it, "nothing is scheduled" could just mean the probe cannot see a scheduled tick.
        BlockPos control = helper.absolutePos(new BlockPos(1, 5, 1));
        level.setBlock(control, Blocks.WATER.defaultBlockState(), EventWriter.FLAGS);
        helper.assertTrue(level.getFluidTicks().hasScheduledTick(control, Fluids.WATER),
                Component.literal("control: plain water should be scheduled to flow, or the probe proves nothing"));
        level.setBlock(control, Blocks.AIR.defaultBlockState(), 3);

        String id = id("pin");
        EventDirector.start(level, id, "", EventScale.LITTLE, EventGuard.Policy.INTO_OPEN, true,
                fill(cells, Blocks.WATER.defaultBlockState(), 40));
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, id);
        helper.runAfterDelay(2, () -> {
            for (BlockPos p : cells) {
                helper.assertTrue(level.getBlockState(p).is(Blocks.WATER), Component.literal("the water is there"));
                helper.assertTrue(!level.getFluidTicks().hasScheduledTick(p, Fluids.WATER),
                        Component.literal("and nothing of it is waiting to flow at " + p));
            }
            EventDirector.undo(level.getServer(), id);
        });
        helper.runAfterDelay(6, () -> {
            for (BlockPos p : cells) {
                helper.assertTrue(level.getBlockState(p).isAir(), Component.literal("undo drained it at " + p));
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void aBlockTheChildPutInTheWaterSurvivesTheUndo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> cells = box(helper);
        BlockPos his = cells.get(4);
        String id = id("child");
        EventDirector.start(level, id, "", EventScale.LITTLE, EventGuard.Policy.INTO_OPEN, false,
                fill(cells, Blocks.WATER.defaultBlockState(), 0));
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, id);
        helper.runAfterDelay(2, () -> {
            level.setBlock(his, Blocks.GLASS.defaultBlockState(), 3);
            EventDirector.undo(level.getServer(), id);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(EventDirector.get(level.getServer(), id) == null, Component.literal("the undo finished"));
            helper.assertTrue(level.getBlockState(his).is(Blocks.GLASS), Component.literal("his glass is still there"));
            for (BlockPos p : cells) {
                if (!p.equals(his)) {
                    helper.assertTrue(level.getBlockState(p).isAir(), Component.literal("the rest drained at " + p));
                }
            }
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void aServerThatStopsMidEventGivesTheWorldBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> cells = box(helper);
        String id = id("restart");
        // A motion that never ends: only the restart can stop it.
        EventDirector.start(level, id, "", EventScale.LITTLE, EventGuard.Policy.INTO_OPEN, true,
                fill(cells, Blocks.WATER.defaultBlockState(), Integer.MAX_VALUE));
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, id);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(level.getBlockState(cells.get(0)).is(Blocks.WATER), Component.literal("flooded"));
            // Save the world and load it again: the motion is gone, the journal is not.
            EventDirector.store(level.getServer()).reloadForTest(level.registryAccess(), id);   // only this one
            StagedEvent e = EventDirector.get(level.getServer(), id);
            helper.assertTrue(e != null && e.changed() == cells.size(),
                    Component.literal("the journal came back from the save with every cell"));
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(EventDirector.get(level.getServer(), id) == null,
                    Component.literal("the orphaned event should have been rolled back and forgotten"));
            for (BlockPos p : cells) {
                helper.assertTrue(level.getBlockState(p).isAir(), Component.literal("given back at " + p));
            }
        });
    }
}
