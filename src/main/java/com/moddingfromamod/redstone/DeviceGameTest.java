package com.moddingfromamod.redstone;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Every {@link DeviceModule} device, built in a real world on real controls and proved: lamps, an iron door, a fence
 * gate, dispensers, a note block; following the switch and inverted.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class DeviceGameTest {

    /** Flat dirt 9 deep under a grass top at y=-1, air above, chunks forced. Returns local (0,0,0) = the surface. */
    static RsPos site(GameTestHelper helper, ServerLevel level, int height) {
        BlockPos base = helper.absolutePos(new BlockPos(1, 1, 1)).offset(0, height, 0);
        RsPos o = new RsPos(base.getX() + 16, base.getY(), base.getZ() + 16);
        for (int cx = (o.x() - 16) >> 4; cx <= (o.x() + 16) >> 4; cx++) {
            for (int cz = (o.z() - 16) >> 4; cz <= (o.z() + 16) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        for (int x = -16; x <= 16; x++) {
            for (int z = -16; z <= 16; z++) {
                for (int y = -10; y <= 6; y++) {
                    BlockState s = y < -1 ? Blocks.DIRT.defaultBlockState() : y == -1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                            : Blocks.AIR.defaultBlockState();
                    level.setBlock(new BlockPos(o.x() + x, o.y() + y, o.z() + z), s, 2);
                }
            }
        }
        return o;
    }

    interface Maker {
        List<DeviceModule.Device> make(RsPos o);
    }

    static void run(GameTestHelper helper, int height, Maker maker, boolean follows, List<int[]> ctl, String... kinds) {
        ServerLevel level = helper.getLevel();
        RsPos o = site(helper, level, height);
        LevelView view = new LevelView(level);
        DeviceModule.Result m = DeviceModule.plan(view, "devices", maker.make(o), follows, Set.of(), new ArrayList<>());
        if (!m.ok()) {
            throw helper.assertionException("the module was refused: " + m.why());
        }
        List<MechanismPlanner.ControlAsk> asks = new ArrayList<>();
        for (int i = 0; i < ctl.size(); i++) {
            asks.add(new MechanismPlanner.ControlAsk(kinds[i], o.plus(ctl.get(i)[0], -1, ctl.get(i)[1])));
        }
        MechanismPlan plan = MechanismPlanner.plan(view, m.module(), asks, Set.of());
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
        }, "device-proof");
        t.setDaemon(true);
        t.start();
        helper.succeedWhen(() -> {
            helper.assertTrue(result.get() != null, Component.literal("waiting for the proof"));
            if (!result.get().ok()) {
                throw helper.assertionException("it did not work: " + result.get().why());
            }
            MechanismWorld.takeBack(level, placed);
        });
    }

    @GameTest(template = "empty_test", batch = "redstone_devices", timeoutTicks = 2400)
    public static void fourLampsInTheGroundOnALever(GameTestHelper helper) {
        run(helper, 140, o -> List.of(Devices.lamp(o.plus(0, -1, 0)), Devices.lamp(o.plus(4, -1, 0)),
                Devices.lamp(o.plus(0, -1, 4)), Devices.lamp(o.plus(4, -1, 4))), true,
                List.of(new int[] {-6, -6}), "lever");
    }

    @GameTest(template = "empty_test", batch = "redstone_devices", timeoutTicks = 2400)
    public static void twoLampsSideBySideOnAButton(GameTestHelper helper) {
        run(helper, 170, o -> List.of(Devices.lamp(o.plus(0, -1, 0)), Devices.lamp(o.plus(1, -1, 0))), true,
                List.of(new int[] {6, -6}), "button");
    }

    @GameTest(template = "empty_test", batch = "redstone_devices", timeoutTicks = 2400)
    public static void aDoubleIronDoorOpensOnPlatesBothSides(GameTestHelper helper) {
        run(helper, 200, o -> List.of(Devices.ironDoor(o, RsDir.SOUTH, false),
                Devices.ironDoor(o.plus(1, 0, 0), RsDir.SOUTH, true)), true,
                List.of(new int[] {0, -3}, new int[] {1, 3}), "pressure_plate", "pressure_plate");
    }

    @GameTest(template = "empty_test", batch = "redstone_devices2", timeoutTicks = 2400)
    public static void aGateOnALever(GameTestHelper helper) {
        run(helper, 140, o -> List.of(Devices.fenceGate(o, RsDir.SOUTH, "minecraft:oak_fence_gate")), true,
                List.of(new int[] {3, -4}), "lever");
    }

    @GameTest(template = "empty_test", batch = "redstone_devices2", timeoutTicks = 2400)
    public static void threeDispensersOfArrowsOnAButton(GameTestHelper helper) {
        run(helper, 170, o -> List.of(Devices.dispenser(o, "north", "minecraft:arrow*16", false),
                Devices.dispenser(o.plus(3, 0, 0), "north", "minecraft:arrow*16", false),
                Devices.dispenser(o.plus(6, 0, 0), "north", "minecraft:arrow*16", false)), true,
                List.of(new int[] {3, 6}), "button");
    }

    @GameTest(template = "empty_test", batch = "redstone_devices2", timeoutTicks = 2400)
    public static void aDoorbellAndALampLitUntilSwitched(GameTestHelper helper) {
        run(helper, 200, o -> List.of(Devices.noteBlock(o, 12, "harp")), true,
                List.of(new int[] {-4, -4}), "button");
    }

    @GameTest(template = "empty_test", batch = "redstone_devices3", timeoutTicks = 2400)
    public static void lampsLitUntilTheSwitchTurnsThemOff(GameTestHelper helper) {
        // inverted: lit at rest, out when switched; the proof's expectations swap
        run(helper, 140, o -> {
            DeviceModule.Device d = Devices.lamp(o.plus(0, -1, 0));
            Expect e = d.expects().get(0);
            return List.of(new DeviceModule.Device(d.cells(), List.of(new Expect(e.pos(), e.fired(), e.rest(),
                    e.what())), d.feed(), d.what()));
        }, false, List.of(new int[] {-5, -5}), "lever");
    }

    /** A secret button low on a stone wall lights a lamp: the wall control's wire goes down through the footing. */
    @GameTest(template = "empty_test", batch = "redstone_devices3", timeoutTicks = 2400)
    public static void aButtonLowOnAWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RsPos o = site(helper, level, 170);
        for (int x = -6; x <= 6; x++) {
            for (int y = 0; y <= 3; y++) {
                level.setBlock(new BlockPos(o.x() + x, o.y() + y, o.z() - 5), Blocks.STONE_BRICKS.defaultBlockState(), 2);
            }
        }
        LevelView view = new LevelView(level);
        DeviceModule.Result m = DeviceModule.plan(view, "lamp", List.of(Devices.lamp(o.plus(0, -1, 0))), true,
                Set.of(), new ArrayList<>());
        if (!m.ok()) {
            throw helper.assertionException(m.why());
        }
        MechanismPlan plan = MechanismPlanner.plan(view, m.module(), List.of(new MechanismPlanner.ControlAsk("button",
                o.plus(2, 0, -5), RsDir.SOUTH, false)), Set.of());
        if (!plan.ok()) {
            throw helper.assertionException("the planner refused: " + plan.why());
        }
        List<MechanismWorld.Placed> placed = MechanismWorld.place(level, plan);
        AtomicReference<MechanismProof.Result> result = new AtomicReference<>();
        Thread t = new Thread(() -> result.set(MechanismProof.prove(level, plan)), "wall-proof");
        t.setDaemon(true);
        t.start();
        helper.succeedWhen(() -> {
            helper.assertTrue(result.get() != null, Component.literal("waiting for the proof"));
            if (!result.get().ok()) {
                throw helper.assertionException("it did not work: " + result.get().why());
            }
            MechanismWorld.takeBack(level, placed);
        });
    }
}
