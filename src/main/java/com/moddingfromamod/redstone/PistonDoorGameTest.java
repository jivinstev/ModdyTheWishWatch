package com.moddingfromamod.redstone;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The 2x2 flush piston door, planned by the pure planner and built in a real world: placed, then proved by pulling the
 * real lever (a block update, never a written powered state), then taken back. Each test builds its own ground and
 * wall high over its plot, so nothing the GameTest plot holds is in the way.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class PistonDoorGameTest {

    /** Ground: 5 of dirt (the channel runs 3 under the floor, its support 4), a stone-brick wall along x, 2x2 hole. */
    static RsPos site(GameTestHelper helper, ServerLevel level, int height) {
        BlockPos base = helper.absolutePos(new BlockPos(1, 1, 1)).offset(0, height, 0);
        RsPos o = new RsPos(base.getX() + 16, base.getY(), base.getZ() + 16);   // door origin: bottom-left opening cell
        // The site is off the test's own plot, so its chunks are not ticking unless forced: unticked, a torch or a
        // repeater waits for its scheduled tick and a plate never sees its stand (measured: answers 20-31 ticks late).
        // In play the child stands beside his door, so its chunks tick.
        for (int cx = (o.x() - 16) >> 4; cx <= (o.x() + 17) >> 4; cx++) {
            for (int cz = (o.z() - 16) >> 4; cz <= (o.z() + 16) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        for (int x = -16; x <= 17; x++) {
            for (int z = -16; z <= 16; z++) {
                for (int y = -8; y <= 8; y++) {
                    BlockState s = y <= -1 ? Blocks.DIRT.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    level.setBlock(new BlockPos(o.x() + x, o.y() + y, o.z() + z), s, 2);
                }
            }
        }
        for (int x = -8; x <= 9; x++) {
            for (int y = 0; y <= 4; y++) {
                boolean opening = (x == 0 || x == 1) && y <= 1;
                if (!opening) {
                    level.setBlock(new BlockPos(o.x() + x, o.y() + y, o.z()), Blocks.STONE_BRICKS.defaultBlockState(), 2);
                }
            }
        }
        return o;
    }

    static void run(GameTestHelper helper, int height, List<int[]> controls, String[] kinds) {
        run(helper, height, controls, kinds, false);
    }

    static void run(GameTestHelper helper, int height, List<int[]> controls, String[] kinds, boolean opens) {
        ServerLevel level = helper.getLevel();
        RsPos o = site(helper, level, height);
        Frame f = new Frame(o, RsDir.EAST, RsDir.SOUTH);
        Module door = PistonDoor.plan(f, 2, "minecraft:stone_bricks", "minecraft:stone_bricks",
                "minecraft:stone_bricks", opens);
        LevelView view = new LevelView(level);
        List<MechanismPlanner.ControlAsk> asks = new java.util.ArrayList<>();
        for (int i = 0; i < controls.size(); i++) {
            int[] c = controls.get(i);
            String k = kinds[i];
            boolean toggle = k.endsWith("+toggle");
            k = k.replace("+toggle", "");
            asks.add(new MechanismPlanner.ControlAsk(k, new RsPos(o.x() + c[0], o.y() - 1, o.z() + c[1]), null,
                    toggle));
        }
        MechanismPlan plan = MechanismPlanner.plan(view, door, asks,
                view.foreignRedstone(o.plus(-16, -6, -16), o.plus(17, 8, 16)));
        if (!plan.ok()) {
            throw helper.assertionException("the planner refused: " + plan.why());
        }
        List<MechanismWorld.Placed> placed = MechanismWorld.place(level, plan);
        AtomicReference<MechanismProof.Result> result = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                result.set(MechanismProof.prove(level, plan));
            } catch (Throwable e) {
                result.set(new MechanismProof.Result("the proof threw " + e, List.of()));
            }
        }, "redstone-proof");
        t.setDaemon(true);
        t.start();
        helper.succeedWhen(() -> {
            helper.assertTrue(result.get() != null, Component.literal("waiting for the proof"));
            MechanismProof.Result r = result.get();
            if (!r.ok()) {
                throw helper.assertionException("the door did not work: " + r.why() + " | " + String.join(" / ", r.steps()));
            }
            MechanismWorld.takeBack(level, placed);
            // taken back: the doorway is open air again (as the site was made) and the wall is whole brick
            BlockState gap = level.getBlockState(LevelView.bp(o));
            helper.assertTrue(gap.isAir(), Component.literal("after the take-back the doorway holds " + gap));
            BlockState wall = level.getBlockState(LevelView.bp(o.plus(-2, 0, 0)));
            helper.assertTrue(wall.is(Blocks.STONE_BRICKS), Component.literal("after the take-back the wall holds " + wall));
        });
    }

    @GameTest(template = "empty_test", batch = "redstone_door", timeoutTicks = 2400)
    public static void aLeverInFrontOpensAndClosesTheDoor(GameTestHelper helper) {
        run(helper, 140, List.of(new int[] {0, -6}), new String[] {"lever"});
    }

    @GameTest(template = "empty_test", batch = "redstone_door", timeoutTicks = 2400)
    public static void aButtonOutsideAndALeverInside(GameTestHelper helper) {
        run(helper, 170, List.of(new int[] {-1, -5}, new int[] {3, 5}), new String[] {"button", "lever"});
    }

    @GameTest(template = "empty_test", batch = "redstone_door", timeoutTicks = 2400)
    public static void aPressurePlateFarAwayNeedsARepeater(GameTestHelper helper) {
        run(helper, 200, List.of(new int[] {0, -14}), new String[] {"pressure_plate"});
    }

    @GameTest(template = "empty_test", batch = "redstone_door_opens", timeoutTicks = 2400)
    public static void aPlateInFrontOpensAShutDoor(GameTestHelper helper) {
        run(helper, 140, List.of(new int[] {0, -6}), new String[] {"pressure_plate"}, true);
    }

    @GameTest(template = "empty_test", batch = "redstone_door_opens", timeoutTicks = 2400)
    public static void aButtonEachSideOpensAShutDoor(GameTestHelper helper) {
        run(helper, 170, List.of(new int[] {-1, -5}, new int[] {3, 5}), new String[] {"button", "button"}, true);
    }

    @GameTest(template = "empty_test", batch = "redstone_door_opens", timeoutTicks = 2400)
    public static void twoSwitchesOnTheSameSideBothOpenIt(GameTestHelper helper) {
        run(helper, 200, List.of(new int[] {-2, -6}, new int[] {4, -6}), new String[] {"lever", "button"}, true);
    }

    @GameTest(template = "empty_test", batch = "redstone_door_toggle", timeoutTicks = 2400)
    public static void oneButtonOpensItAndThePressAfterShutsIt(GameTestHelper helper) {
        run(helper, 140, List.of(new int[] {0, -6}), new String[] {"button+toggle"}, true);
    }

    @GameTest(template = "empty_test", batch = "redstone_door_toggle", timeoutTicks = 2400)
    public static void aPlateThatToggles(GameTestHelper helper) {
        run(helper, 170, List.of(new int[] {3, -6}), new String[] {"pressure_plate+toggle"}, true);
    }
}
