package com.moddingfromamod.event;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.compat.LevelHeights;
import com.moddingfromamod.event.motion.CountdownMotion;
import com.moddingfromamod.event.motion.CrackMotion;
import com.moddingfromamod.event.motion.SkyDropMotion;
import com.moddingfromamod.event.motion.SlideMotion;
import com.moddingfromamod.event.shape.Countdown;
import com.moddingfromamod.event.shape.CrackShape;
import com.moddingfromamod.event.shape.SkyDrop;
import com.moddingfromamod.event.motion.LayerMotion;
import com.moddingfromamod.event.motion.StampMotion;
import com.moddingfromamod.event.shape.FallShape;
import com.moddingfromamod.event.shape.StampShape;
import com.moddingfromamod.event.motion.MovingFrontMotion;
import com.moddingfromamod.event.shape.FrontShape;
import com.moddingfromamod.event.shape.LayerShape;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The event motions, played in a real world</b> (E2). Each test builds in its OWN lane far
 * from every plot: a wave travels a hundred blocks, and "plot + offset" is not isolation because plot
 * origins differ by thousands of blocks (an earlier mod's cool-blocks lesson). The lane's chunks are FORCED, since
 * a loaded chunk that nothing holds can unload between ticks and an event never loads one itself.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class EventMotionGameTest {

    private static final String BATCH = "eventmotions";
    private static final AtomicInteger LANE = new AtomicInteger();

    /** A lane of its own: 400 blocks apart, far beyond every plot. */
    private static BlockPos lane() {
        return new BlockPos(90_000 + LANE.incrementAndGet() * 400, 0, 90_000);
    }

    private static void force(ServerLevel level, int x0, int z0, int x1, int z1, boolean on) {
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                level.setChunkForced(cx, cz, on);
            }
        }
    }

    private static int top(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    /** Wraps a motion to record the most cells the event ever held. */
    private static EventMotion watching(EventMotion m, String id, ServerLevel level, int[] peak) {
        return (w, age) -> {
            boolean done = m.step(w, age);
            StagedEvent e = EventDirector.get(level.getServer(), id);
            if (e != null) {
                peak[0] = Math.max(peak[0], e.changed());
            }
            return done;
        };
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600, randomTicks = false)
    public static void aWaveRollsAcrossAndLeavesTheWorldAsItFoundIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        FrontShape shape = new FrontShape(6, 16, true);
        int travel = shape.travel();
        force(level, o.getX() - 8, o.getZ() - 16, o.getX() + travel + 16, o.getZ() + 16, true);
        int g = top(level, o.getX(), o.getZ());
        BlockPos origin = new BlockPos(o.getX(), g, o.getZ());

        // In its path: a meadow of poppies and a little brick wall the child built.
        BlockPos wall = new BlockPos(o.getX() + 30, g + 1, o.getZ());
        level.setBlock(wall, Blocks.STONE_BRICKS.defaultBlockState(), 3);
        level.setBlock(wall.above(), Blocks.STONE_BRICKS.defaultBlockState(), 3);
        List<BlockPos> poppies = new ArrayList<>();
        for (int x = 10; x < 60; x += 7) {
            BlockPos p = new BlockPos(o.getX() + x, g + 1, o.getZ() + 3);
            level.setBlock(p, Blocks.POPPY.defaultBlockState(), 3);
            poppies.add(p);
        }
        // The whole corridor, remembered block by block.
        Map<BlockPos, BlockState> before = new HashMap<>();
        for (int x = -2; x < travel + 8; x++) {
            for (int z = -14; z <= 14; z++) {
                for (int y = 0; y <= 10; y++) {
                    BlockPos p = new BlockPos(o.getX() + x, g + y, o.getZ() + z);
                    before.put(p, level.getBlockState(p));
                }
            }
        }

        String id = "wave_" + o.getX();
        int[] peak = {0};
        int[] wet = {0};
        // A flag, not an assertion: an exception inside a motion is caught by the director (which
        // rolls the event back), so an assertion there would vanish rather than fail the test.
        boolean[] wallHit = {false};
        // Yaw -90 faces +X (east).
        MovingFrontMotion front = new MovingFrontMotion(shape, Blocks.WATER.defaultBlockState(), origin, -90f);
        EventDirector.start(level, id, "", EventScale.LITTLE, EventGuard.Policy.INTO_OPEN, true,
                watching((w, age) -> {
                    boolean done = front.step(w, age);
                    if (age == 30) {
                        for (int x = 0; x < 50; x++) {
                            if (level.getBlockState(new BlockPos(o.getX() + x, g + 1, o.getZ()))
                                    .is(Blocks.WATER)) {
                                wet[0]++;
                            }
                        }
                    }
                    wallHit[0] |= !level.getBlockState(wall).is(Blocks.STONE_BRICKS);
                    return done;
                }, id, level, peak));
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, id);

        helper.succeedWhen(() -> {
            helper.assertTrue(EventDirector.get(level.getServer(), id) == null, Component.literal("the wave has gone"));
            helper.assertTrue(!wallHit[0], Component.literal("the wave flows round his wall, never through it"));
            helper.assertTrue(wet[0] > 0, Component.literal("there really was a wave (water on the ground at tick 30)"));
            helper.assertTrue(peak[0] > 0 && peak[0] <= EventScale.LITTLE.totalBlocks,
                    Component.literal("it held only the front, within its budget: peak " + peak[0]));
            int wrong = 0;
            BlockPos first = null;
            for (Map.Entry<BlockPos, BlockState> e : before.entrySet()) {
                if (level.getBlockState(e.getKey()) != e.getValue()) {
                    wrong++;
                    if (first == null) {
                        first = e.getKey();
                    }
                }
            }
            helper.assertTrue(wrong == 0, Component.literal(wrong + " cells not given back, e.g. " + first + " is "
                    + (first == null ? "" : level.getBlockState(first) + " not " + before.get(first))));
            for (BlockPos p : poppies) {
                helper.assertTrue(level.getBlockState(p).is(Blocks.POPPY), Component.literal("the meadow is back at " + p));
            }
            force(level, o.getX() - 8, o.getZ() - 16, o.getX() + travel + 16, o.getZ() + 16, false);
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 1500, randomTicks = false)
    public static void aFloodRisesFillsTheLowGroundFirstAndDrainsAway(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        LayerShape shape = new LayerShape(3, 10);
        force(level, o.getX() - 12, o.getZ() - 12, o.getX() + 12, o.getZ() + 12, true);
        int g = top(level, o.getX(), o.getZ());
        // A pit one block deep near the middle: the low ground must flood too.
        BlockPos pit = new BlockPos(o.getX() + 3, g, o.getZ());
        BlockState pitWas = level.getBlockState(pit);
        level.setBlock(pit, Blocks.AIR.defaultBlockState(), 3);
        Map<BlockPos, BlockState> before = new HashMap<>();
        for (int x = -11; x <= 11; x++) {
            for (int z = -11; z <= 11; z++) {
                for (int y = -1; y <= 5; y++) {
                    BlockPos p = new BlockPos(o.getX() + x, g + y, o.getZ() + z);
                    before.put(p, level.getBlockState(p));
                }
            }
        }
        String id = "flood_" + o.getX();
        int[] peak = {0};
        int[] deepest = {0};
        boolean[] pitFlooded = {false};
        LayerMotion flood = new LayerMotion(shape, Blocks.WATER.defaultBlockState(),
                new BlockPos(o.getX(), g, o.getZ()));
        EventDirector.start(level, id, "", EventScale.LITTLE, EventGuard.Policy.INTO_OPEN, true,
                watching((w, age) -> {
                    boolean done = flood.step(w, age);
                    int d = 0;
                    while (d < 6 && level.getBlockState(new BlockPos(o.getX() - 4, g + 1 + d, o.getZ()))
                            .is(Blocks.WATER)) {
                        d++;
                    }
                    deepest[0] = Math.max(deepest[0], d);
                    pitFlooded[0] |= level.getBlockState(pit).is(Blocks.WATER);
                    return done;
                }, id, level, peak));
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, id);

        helper.succeedWhen(() -> {
            helper.assertTrue(EventDirector.get(level.getServer(), id) == null, Component.literal("the flood has drained"));
            helper.assertTrue(deepest[0] == 3, Component.literal("it rose to its full three blocks: " + deepest[0]));
            helper.assertTrue(pitFlooded[0], Component.literal("the low ground flooded first"));
            helper.assertTrue(peak[0] <= EventScale.LITTLE.totalBlocks, Component.literal("within budget: " + peak[0]));
            int wrong = 0;
            for (Map.Entry<BlockPos, BlockState> e : before.entrySet()) {
                if (level.getBlockState(e.getKey()) != e.getValue()) {
                    wrong++;
                }
            }
            helper.assertTrue(wrong == 0, Component.literal(wrong + " cells not given back"));
            level.setBlock(pit, pitWas, 3);
            force(level, o.getX() - 12, o.getZ() - 12, o.getX() + 12, o.getZ() + 12, false);
        });
    }

    private static final StampMotion.Palette ROCK = new StampMotion.Palette(
            Blocks.BASALT.defaultBlockState(), Blocks.MAGMA_BLOCK.defaultBlockState(),
            Blocks.COBBLESTONE.defaultBlockState());

    private static Map<BlockPos, BlockState> snapshot(ServerLevel level, BlockPos c, int r, int y0, int y1) {
        Map<BlockPos, BlockState> out = new HashMap<>();
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                for (int y = y0; y <= y1; y++) {
                    BlockPos p = new BlockPos(c.getX() + x, y, c.getZ() + z);
                    out.put(p, level.getBlockState(p));
                }
            }
        }
        return out;
    }

    private static int differences(ServerLevel level, Map<BlockPos, BlockState> before) {
        int wrong = 0;
        for (Map.Entry<BlockPos, BlockState> e : before.entrySet()) {
            if (level.getBlockState(e.getKey()) != e.getValue()) {
                wrong++;
            }
        }
        return wrong;
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400, randomTicks = false)
    public static void aCraterIsDugWithARimStaysUntilUndoAndThenComesBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        force(level, o.getX() - 20, o.getZ() - 20, o.getX() + 20, o.getZ() + 20, true);
        int g = top(level, o.getX(), o.getZ());
        // A stone plateau ten deep, so this crater is not clamped by the bottom of the world.
        for (int x = -16; x <= 16; x++) {
            for (int z = -16; z <= 16; z++) {
                for (int y = 1; y <= 10; y++) {
                    level.setBlock(o.offset(x, g + y - o.getY(), z), Blocks.STONE.defaultBlockState(), 2);
                }
            }
        }
        int top = g + 10;
        BlockPos c = new BlockPos(o.getX(), top, o.getZ());
        Map<BlockPos, BlockState> before = snapshot(level, c, 15, top - 8, top + 4);
        StampShape shape = new StampShape(StampShape.Form.CRATER, 10);
        String id = "crater_" + o.getX();
        EventDirector.start(level, id, "", EventScale.LITTLE, EventGuard.Policy.TERRAIN, false,
                new StampMotion(shape, ROCK, c, 5));
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, id);
        boolean[] undone = {false};
        helper.succeedWhen(() -> {
            {
                StagedEvent e = EventDirector.get(level.getServer(), id);
                if (!undone[0]) {
                    helper.assertTrue(e != null && e.phase() == EventPhase.KEPT, Component.literal("a crater stays until undo"));
                    int d = shape.depth();
                    for (int y = top; y > top - d; y--) {
                        helper.assertTrue(level.getBlockState(new BlockPos(c.getX(), y, c.getZ())).isAir(),
                                Component.literal("dug out at y " + y));
                    }
                    helper.assertTrue(level.getBlockState(new BlockPos(c.getX(), top - d, c.getZ()))
                            .is(Blocks.MAGMA_BLOCK), Component.literal("a hot middle on the floor"));
                    helper.assertTrue(level.getBlockState(new BlockPos(c.getX() + 11, top + 1, c.getZ()))
                            .is(Blocks.COBBLESTONE), Component.literal("a rim raised outside the hole"));
                    EventDirector.undo(level.getServer(), id);
                    undone[0] = true;
                    // Not a failure: succeedWhen retries, and the next ticks are the undo's.
                    helper.fail(Component.literal("stamped; now waiting for the undo"));
                }
                helper.assertTrue(e == null, Component.literal("the undo finished"));
                int wrong = differences(level, before);
                helper.assertTrue(wrong == 0, Component.literal(wrong + " cells not given back after the undo"));
            }
            force(level, o.getX() - 20, o.getZ() - 20, o.getX() + 20, o.getZ() + 20, false);
        });
    }

    /**
     * The rule: a crater that STAYS is a major event and breaks his builds too,
     * while a chest survives and undo builds the house back exactly.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400, randomTicks = false)
    public static void aCraterThatStaysBreaksHisHouseButNotHisChestAndUndoBuildsItBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        force(level, o.getX() - 20, o.getZ() - 20, o.getX() + 20, o.getZ() + 20, true);
        int g = top(level, o.getX(), o.getZ());
        for (int x = -16; x <= 16; x++) {
            for (int z = -16; z <= 16; z++) {
                for (int y = 1; y <= 10; y++) {
                    level.setBlock(o.offset(x, g + y - o.getY(), z), Blocks.STONE.defaultBlockState(), 2);
                }
            }
        }
        int top = g + 10;
        BlockPos c = new BlockPos(o.getX(), top, o.getZ());
        // His house: a plank floor he laid into the ground, with a chest in the corner.
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                level.setBlock(c.offset(x, 0, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
            }
        }
        BlockPos chest = c.offset(2, 0, 2);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 2);
        Map<BlockPos, BlockState> before = snapshot(level, c, 15, top - 8, top + 4);
        StampShape shape = new StampShape(StampShape.Form.CRATER, 10);
        String id = "househit_" + o.getX();
        EventDirector.start(level, id, "", EventScale.LITTLE, EventGuard.digging(false), false,
                new StampMotion(shape, ROCK, c, 5));
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, id);
        boolean[] undone = {false};
        helper.succeedWhen(() -> {
            {
                StagedEvent e = EventDirector.get(level.getServer(), id);
                if (!undone[0]) {
                    helper.assertTrue(e != null && e.phase() == EventPhase.KEPT, Component.literal("a crater stays until undo"));
                    helper.assertTrue(level.getBlockState(c).isAir(), Component.literal("the crater went through his floor"));
                    helper.assertTrue(level.getBlockState(chest).is(Blocks.CHEST), Component.literal("his chest is never broken"));
                    EventDirector.undo(level.getServer(), id);
                    undone[0] = true;
                    helper.fail(Component.literal("stamped; now waiting for the undo"));
                }
                helper.assertTrue(e == null, Component.literal("the undo finished"));
                int wrong = differences(level, before);
                helper.assertTrue(wrong == 0, Component.literal(wrong + " cells of his house not given back after the undo"));
            }
            force(level, o.getX() - 20, o.getZ() - 20, o.getX() + 20, o.getZ() + 20, false);
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400, randomTicks = false)
    public static void aCraterNearTheBottomOfTheWorldStillHasAFloorAndTheWorldStillHasABottom(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        force(level, o.getX() - 16, o.getZ() - 16, o.getX() + 16, o.getZ() + 16, true);
        int g = top(level, o.getX(), o.getZ());
        int bottom = LevelHeights.bottom(level);
        BlockPos c = new BlockPos(o.getX(), g, o.getZ());
        Map<BlockPos, BlockState> before = snapshot(level, c, 12, bottom, g + 3);
        StampShape shape = new StampShape(StampShape.Form.CRATER, 8);
        String id = "shallow_" + o.getX();
        EventDirector.start(level, id, "", EventScale.LITTLE, EventGuard.Policy.TERRAIN, false,
                new StampMotion(shape, ROCK, c, 3));
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, id);
        helper.succeedWhen(() -> {
            StagedEvent e = EventDirector.get(level.getServer(), id);
            helper.assertTrue(e != null && e.phase() == EventPhase.KEPT, Component.literal("stamped"));
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    BlockPos b = new BlockPos(c.getX() + x, bottom, c.getZ() + z);
                    helper.assertTrue(level.getBlockState(b).equals(before.get(b)),
                            Component.literal("the bottom of the world is still there at " + b));
                    boolean floor = false;
                    for (int y = bottom + 1; y <= g; y++) {
                        floor |= !level.getBlockState(new BlockPos(b.getX(), y, b.getZ())).isAir();
                    }
                    helper.assertTrue(floor, Component.literal("a floor to stand on over " + b));
                }
            }
            EventDirector.undo(level.getServer(), id);
            EventDirector.tickAll(level.getServer());
            helper.assertTrue(differences(level, before) == 0, Component.literal("and the undo gives it all back"));
            force(level, o.getX() - 16, o.getZ() - 16, o.getX() + 16, o.getZ() + 16, false);
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400, randomTicks = false)
    public static void aConeBuildsToItsOwnHeightAndNoHigher(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        force(level, o.getX() - 16, o.getZ() - 16, o.getX() + 16, o.getZ() + 16, true);
        int g = top(level, o.getX(), o.getZ());
        BlockPos c = new BlockPos(o.getX(), g, o.getZ());
        StampShape shape = new StampShape(StampShape.Form.CONE, 10);
        Map<BlockPos, BlockState> before = snapshot(level, c, 11, g - 1, g + shape.height() + 6);
        String id = "cone_" + o.getX();
        // Grows over 20 ticks: every tick re-reading the ground would find its own slopes (an earlier mod's 26 -> 42).
        EventDirector.start(level, id, "", EventScale.LITTLE, EventGuard.Policy.TERRAIN, false,
                new StampMotion(shape, ROCK, c, 20));
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, id);
        helper.succeedWhen(() -> {
            StagedEvent e = EventDirector.get(level.getServer(), id);
            helper.assertTrue(e != null && e.phase() == EventPhase.KEPT, Component.literal("built"));
            int highest = g;
            for (int x = -10; x <= 10; x++) {
                for (int z = -10; z <= 10; z++) {
                    highest = Math.max(highest, top(level, c.getX() + x, c.getZ() + z));
                }
            }
            helper.assertTrue(highest - g == shape.rimHeight(),
                    Component.literal("the cone stands exactly its lip high: " + (highest - g) + " vs " + shape.rimHeight()));
            helper.assertTrue(top(level, c.getX(), c.getZ()) < highest, Component.literal("with a bowl in the top"));
            EventDirector.undo(level.getServer(), id);
            EventDirector.tickAll(level.getServer());
            helper.assertTrue(differences(level, before) == 0, Component.literal("the undo takes the mountain away"));
            force(level, o.getX() - 16, o.getZ() - 16, o.getX() + 16, o.getZ() + 16, false);
        });
    }

    /**
     * The display-entity body: it falls on the same slant as displays, writes NO blocks on its
     * way down, and leaves no display behind once it lands.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400, randomTicks = false)
    public static void aDisplayBodyFallsAsDisplaysWritesNoBlocksAndLeavesNoneBehind(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        force(level, o.getX() - 64, o.getZ() - 16, o.getX() + 96, o.getZ() + 16, true);
        int g = top(level, o.getX(), o.getZ());
        FallShape.Point target = new FallShape.Point(o.getX() + 0.5, g + 1, o.getZ() + 0.5);
        FallShape.Point viewer = new FallShape.Point(o.getX() - 59.5, g + 2, o.getZ() + 0.5);
        var body = new com.moddingfromamod.event.motion.DisplayBodyMotion(
                com.moddingfromamod.event.design.BodyLooks.rock(Blocks.MAGMA_BLOCK.defaultBlockState(),
                        Blocks.BLACKSTONE.defaultBlockState(), 5f), 5f, 0.12f, viewer, target);
        boolean[] arrived = {false};
        int[] mostDisplays = {0};
        int[] mostChanged = {0};
        body.onArrive(() -> arrived[0] = true);
        String id = "dbody_" + o.getX();
        EventDirector.start(level, id, "", EventScale.LITTLE, EventGuard.Policy.INTO_OPEN, true,
                (w, age) -> {
                    boolean done = body.step(w, age);
                    mostDisplays[0] = Math.max(mostDisplays[0], body.displays().size());
                    StagedEvent e = EventDirector.get(level.getServer(), id);
                    if (e != null) {
                        mostChanged[0] = Math.max(mostChanged[0], e.changed());
                    }
                    return done;
                });
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, id);
        helper.succeedWhen(() -> {
            helper.assertTrue(EventDirector.get(level.getServer(), id) == null, Component.literal("it has come down"));
            helper.assertTrue(arrived[0], Component.literal("it arrived"));
            helper.assertTrue(mostDisplays[0] == 7, Component.literal("it was seven displays in the sky, not " + mostDisplays[0]));
            helper.assertTrue(mostChanged[0] == 0, Component.literal("it wrote " + mostChanged[0] + " blocks; a display body writes none"));
            var box = new net.minecraft.world.phys.AABB(o.getX() - 64, g - 4, o.getZ() - 16,
                    o.getX() + 96, g + 120, o.getZ() + 16);
            int left = level.getEntitiesOfClass(net.minecraft.world.entity.Display.BlockDisplay.class, box,
                    d -> d.entityTags().contains(EventDirector.entityTag(id))).size();
            helper.assertTrue(left == 0, Component.literal(left + " displays of its body were left behind"));
            force(level, o.getX() - 64, o.getZ() - 16, o.getX() + 96, o.getZ() + 16, false);
        });
    }

    private static void plateau(ServerLevel level, BlockPos o, int g, int r, int h) {
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                for (int y = 1; y <= h; y++) {
                    level.setBlock(new BlockPos(o.getX() + x, g + y, o.getZ() + z),
                            Blocks.STONE.defaultBlockState(), 2);
                }
            }
        }
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400, randomTicks = false)
    public static void cracksSpreadThroughGroundAndStopAtAChest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        force(level, o.getX() - 30, o.getZ() - 30, o.getX() + 30, o.getZ() + 30, true);
        int g = top(level, o.getX(), o.getZ());
        plateau(level, o, g, 26, 8);
        int t = g + 8;
        BlockPos c = new BlockPos(o.getX(), t, o.getZ());
        Map<BlockPos, BlockState> before = snapshot(level, c, 25, t - 7, t + 1);
        CrackShape shape = new CrackShape(4, 20, 5);
        // A chest sunk into the ground, on the first step of the first crack.
        CrackShape.Step s0 = shape.steps(11).get(2);
        BlockPos chest = new BlockPos(c.getX() + s0.dx(), t - 1, c.getZ() + s0.dz());
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        before.put(chest, level.getBlockState(chest));
        CrackMotion cracks = new CrackMotion(shape, 11, c, 2);
        String id = "cracks_" + o.getX();
        EventDirector.start(level, id, "", EventScale.LITTLE, EventGuard.Policy.TERRAIN, false, cracks);
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, id);
        boolean[] undone = {false};
        helper.succeedWhen(() -> {
            StagedEvent e = EventDirector.get(level.getServer(), id);
            if (!undone[0]) {
                helper.assertTrue(e != null && e.phase() == EventPhase.KEPT, Component.literal("the cracks have spread"));
                helper.assertTrue(cracks.opened() > 40, Component.literal("the ground really opened: " + cracks.opened()));
                helper.assertTrue(level.getBlockState(chest).is(Blocks.CHEST), Component.literal("his chest is untouched"));
                EventDirector.undo(level.getServer(), id);
                undone[0] = true;
                helper.fail(Component.literal("cracked; now waiting for the undo"));
            }
            helper.assertTrue(e == null, Component.literal("the undo finished"));
            int wrong = differences(level, before);
            helper.assertTrue(wrong == 0, Component.literal(wrong + " cells not given back"));
            force(level, o.getX() - 30, o.getZ() - 30, o.getX() + 30, o.getZ() + 30, false);
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 500, randomTicks = false)
    public static void aHillsideSlidesDownhillAndAPlainDoesNot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos hill = lane();
        BlockPos plain = lane();
        force(level, hill.getX() - 20, hill.getZ() - 20, hill.getX() + 70, hill.getZ() + 20, true);
        force(level, plain.getX() - 20, plain.getZ() - 20, plain.getX() + 70, plain.getZ() + 20, true);
        int g = top(level, hill.getX(), hill.getZ());
        // A ramp with no cliff anywhere near it (an earlier mod: a ramp that stops dead is a cliff inside the scan).
        for (int s = -10; s <= 16; s++) {
            int h = s <= 0 ? 12 : Math.max(0, 12 - (int) Math.round(s * 12 / 16.0));
            for (int z = -12; z <= 12; z++) {
                for (int y = 1; y <= h; y++) {
                    level.setBlock(new BlockPos(hill.getX() + s, g + y, hill.getZ() + z),
                            Blocks.STONE.defaultBlockState(), 2);
                }
            }
        }
        BlockPos crown = new BlockPos(hill.getX(), g + 12, hill.getZ());
        Map<BlockPos, BlockState> before = snapshot(level, new BlockPos(hill.getX() + 25, g, hill.getZ()),
                40, g - 1, g + 14);
        Map<BlockPos, BlockState> plainBefore = snapshot(level, plain, 20, g - 1, g + 4);
        SlideMotion slide = new SlideMotion(crown);
        SlideMotion none = new SlideMotion(new BlockPos(plain.getX(), g, plain.getZ()));
        EventDirector.start(level, "slide_" + hill.getX(), "", EventScale.LITTLE, EventGuard.Policy.TERRAIN,
                false, slide);
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, "slide_" + hill.getX());
        EventDirector.start(level, "flat_" + plain.getX(), "", EventScale.LITTLE, EventGuard.Policy.TERRAIN,
                false, none);
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, "flat_" + plain.getX());
        boolean[] undone = {false};
        helper.succeedWhen(() -> {
            StagedEvent e = EventDirector.get(level.getServer(), "slide_" + hill.getX());
            if (!undone[0]) {
                helper.assertTrue(e != null && e.phase() == EventPhase.KEPT, Component.literal("the slide has come to rest"));
                helper.assertTrue(none.moved() == 0, Component.literal("a plain has no hill to slide"));
                helper.assertTrue(differences(level, plainBefore) == 0, Component.literal("and nothing on the plain changed"));
                helper.assertTrue(slide.moved() > 20, Component.literal("the hillside let go: " + slide.moved() + " blocks, drop "
                        + slide.drop()));
                int heap = 0;
                for (int x = 17; x <= 60; x++) {
                    for (int z = -16; z <= 16; z++) {
                        heap += level.getBlockState(new BlockPos(hill.getX() + x, g + 1, hill.getZ() + z))
                                .is(Blocks.STONE) ? 1 : 0;
                    }
                }
                helper.assertTrue(heap > 10, Component.literal("rubble lies past the foot of the hill: " + heap));
                helper.assertTrue(level.getBlockState(crown).isAir(), Component.literal("and the crown is gone"));
                EventDirector.undo(level.getServer(), "slide_" + hill.getX());
                undone[0] = true;
                helper.fail(Component.literal("slid; now waiting for the undo"));
            }
            helper.assertTrue(e == null, Component.literal("the undo finished"));
            int wrong = differences(level, before);
            helper.assertTrue(wrong == 0, Component.literal(wrong + " cells of the hill not given back"));
            force(level, hill.getX() - 20, hill.getZ() - 20, hill.getX() + 70, hill.getZ() + 20, false);
            force(level, plain.getX() - 20, plain.getZ() - 20, plain.getX() + 70, plain.getZ() + 20, false);
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400, randomTicks = false)
    public static void chickensRainAndSnowSettlesAndTheUndoTakesBothAway(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        force(level, o.getX() - 16, o.getZ() - 16, o.getX() + 16, o.getZ() + 16, true);
        int g = top(level, o.getX(), o.getZ());
        BlockPos c = new BlockPos(o.getX(), g, o.getZ());
        Map<BlockPos, BlockState> before = snapshot(level, c, 12, g - 1, g + 3);
        String hens = "hens_" + o.getX(), snow = "snow_" + o.getX();
        SkyDropMotion rain = SkyDropMotion.entities(new SkyDrop(8, 2, 10), c, 5,
                net.minecraft.world.entity.EntityTypes.CHICKEN);
        SkyDropMotion flakes = SkyDropMotion.settling(new SkyDrop(10, 6, 20), c, 6,
                Blocks.SNOW.defaultBlockState());
        EventDirector.start(level, hens, "", EventScale.LITTLE, EventGuard.Policy.INTO_OPEN, false, rain);
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, hens);
        EventDirector.start(level, snow, "", EventScale.LITTLE, EventGuard.Policy.INTO_OPEN, false, flakes);
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, snow);
        boolean[] undone = {false};
        String tag = EventDirector.entityTag(hens);
        java.util.function.IntSupplier henCount = () -> level.getEntitiesOfClass(
                net.minecraft.world.entity.animal.chicken.Chicken.class,
                new net.minecraft.world.phys.AABB(c).inflate(20, 40, 20), x -> x.entityTags().contains(tag)).size();
        helper.succeedWhen(() -> {
            if (!undone[0]) {
                StagedEvent h = EventDirector.get(level.getServer(), hens);
                StagedEvent s = EventDirector.get(level.getServer(), snow);
                helper.assertTrue(h != null && s != null && h.phase() == EventPhase.KEPT
                        && s.phase() == EventPhase.KEPT, Component.literal("both have finished falling"));
                helper.assertTrue(rain.spawned() == 20 && henCount.getAsInt() == 20,
                        Component.literal("twenty chickens rained, each tagged: " + rain.spawned() + "/" + henCount.getAsInt()));
                int layers = 0;
                for (BlockPos p : before.keySet()) {
                    layers += level.getBlockState(p).is(Blocks.SNOW) ? 1 : 0;
                }
                helper.assertTrue(layers > 30, Component.literal("snow settled: " + layers));
                EventDirector.undo(level.getServer(), hens);
                EventDirector.undo(level.getServer(), snow);
                undone[0] = true;
                helper.fail(Component.literal("fallen; now waiting for the undo"));
            }
            helper.assertTrue(EventDirector.get(level.getServer(), hens) == null
                    && EventDirector.get(level.getServer(), snow) == null, Component.literal("both undone"));
            helper.assertTrue(henCount.getAsInt() == 0, Component.literal("the chickens went with the undo"));
            helper.assertTrue(differences(level, before) == 0, Component.literal("and the snow melted away"));
            force(level, o.getX() - 16, o.getZ() - 16, o.getX() + 16, o.getZ() + 16, false);
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 300, randomTicks = false)
    public static void aCountdownSaysEveryFinalSecondAndGoesOffOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Integer> said = new ArrayList<>();
        int[] zero = {0};
        String id = "clock_" + LANE.incrementAndGet();
        EventDirector.start(level, id, "", EventScale.LITTLE, EventGuard.Policy.INTO_OPEN, true,
                new CountdownMotion(new Countdown(5), said::add, () -> zero[0]++));
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, id);
        helper.succeedWhen(() -> {
            helper.assertTrue(EventDirector.get(level.getServer(), id) == null, Component.literal("the clock ran out"));
            helper.assertTrue(said.equals(List.of(5, 4, 3, 2, 1)), Component.literal("every second said once: " + said));
            helper.assertTrue(zero[0] == 1, Component.literal("it went off exactly once: " + zero[0]));
        });
    }
}
