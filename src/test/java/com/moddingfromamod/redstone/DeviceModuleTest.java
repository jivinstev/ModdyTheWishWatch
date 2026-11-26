package com.moddingfromamod.redstone;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The device module on the fake world (natural ground y <= -61, air above). */
class DeviceModuleTest {

    static String draw(MechanismPlan p, int y) {
        StringBuilder b = new StringBuilder("\n");
        for (int z = -12; z <= 12; z++) {
            for (int x = -12; x <= 12; x++) {
                RsPos q = new RsPos(x, y, z);
                String ch = ".";
                for (RsCell c : p.cells()) {
                    if (c.pos().equals(q)) {
                        ch = c.block().contains("wire") ? "+" : c.block().contains("repeater") ? "r"
                                : c.block().contains("torch") ? "t" : c.block().contains("stone") ? "#" : "?";
                    }
                }
                b.append(ch);
            }
            b.append('\n');
        }
        return b.toString();
    }

    @Test
    void fourLampsInTheGroundOnALever() {
        WorldView w = new WorldView() {
            @Override
            public Kind kind(RsPos p) {
                return p.y() <= -61 ? Kind.NATURAL : Kind.AIR;
            }

            @Override
            public String blockId(RsPos p) {
                return kind(p).name();
            }
        };
        RsPos o = new RsPos(0, -61, 0);
        DeviceModule.Result m = DeviceModule.plan(w, "lamps", List.of(Devices.lamp(o), Devices.lamp(o.plus(4, 0, 0)),
                Devices.lamp(o.plus(0, 0, 4)), Devices.lamp(o.plus(4, 0, 4))), true, Set.of(), new ArrayList<>());
        assertTrue(m.ok(), m.why());
        MechanismPlan p = MechanismPlanner.plan(w, m.module(),
                List.of(new MechanismPlanner.ControlAsk("lever", new RsPos(-6, -61, -6))), Set.of());
        MechanismPlan bare = new MechanismPlan("x", m.module().cells(), List.of(), List.of(), List.of(), List.of(), null);
        assertTrue(p.ok(), p.why() + " ports " + m.module().ports() + " busY " + m.module().channelY()
                + draw(bare, m.module().channelY()));
    }

    static WorldView deep() {
        return new WorldView() {
            @Override
            public Kind kind(RsPos p) {
                return p.y() <= -61 ? Kind.NATURAL : Kind.AIR;
            }

            @Override
            public String blockId(RsPos p) {
                return kind(p).name();
            }
        };
    }

    static void plans(List<DeviceModule.Device> devs, String kind, int[]... ctl) {
        WorldView w = deep();
        DeviceModule.Result m = DeviceModule.plan(w, "x", devs, true, Set.of(), new ArrayList<>());
        assertTrue(m.ok(), m.why());
        List<MechanismPlanner.ControlAsk> asks = new ArrayList<>();
        for (int[] c : ctl) {
            asks.add(new MechanismPlanner.ControlAsk(kind, new RsPos(c[0], -61, c[1])));
        }
        MechanismPlan p = MechanismPlanner.plan(w, m.module(), asks, Set.of());
        MechanismPlan bare = new MechanismPlan("x", m.module().cells(), List.of(), List.of(), List.of(), List.of(), null);
        assertTrue(p.ok(), p.why() + " ports " + m.module().ports() + " busY " + m.module().channelY()
                + draw(bare, m.module().channelY()));
    }

    @Test
    void threeDispensersInARow() {
        RsPos o = new RsPos(0, -60, 0);
        plans(List.of(Devices.dispenser(o, "north", "", false), Devices.dispenser(o.plus(3, 0, 0), "north", "", false),
                Devices.dispenser(o.plus(6, 0, 0), "north", "", false)), "button", new int[] {3, 6});
    }

    @Test
    void aDoubleIronDoorWithAPlateEachSide() {
        RsPos o = new RsPos(0, -60, 0);
        plans(List.of(Devices.ironDoor(o, RsDir.SOUTH, false), Devices.ironDoor(o.plus(1, 0, 0), RsDir.SOUTH, true)),
                "pressure_plate", new int[] {0, -3}, new int[] {1, 3});
    }
}
