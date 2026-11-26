package com.moddingfromamod.redstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MechanismPlannerTest {

    /** Flat ground: bedrock at -64, natural ground -63..-61, air above; plus whatever a test puts in. */
    static final class FakeWorld implements WorldView {
        final Map<RsPos, Kind> kinds = new HashMap<>();
        final Map<RsPos, String> ids = new HashMap<>();

        @Override
        public Kind kind(RsPos p) {
            Kind k = kinds.get(p);
            if (k != null) {
                return k;
            }
            return p.y() <= -64 ? Kind.LOCKED : p.y() <= -61 ? Kind.NATURAL : Kind.AIR;
        }

        @Override
        public String blockId(RsPos p) {
            return ids.getOrDefault(p, kind(p).name().toLowerCase());
        }

        @Override
        public boolean conductor(RsPos p) {
            return p.y() <= -64 || WorldView.super.conductor(p);
        }

        void put(RsPos p, Kind k, String id) {
            kinds.put(p, k);
            ids.put(p, id);
        }

        /** A stone-brick wall along x at z=0 from x0 to x1, 5 tall, with a 2x2 opening at x=0..1. */
        FakeWorld wall(int x0, int x1) {
            for (int x = x0; x <= x1; x++) {
                for (int y = -60; y <= -56; y++) {
                    boolean opening = (x == 0 || x == 1) && y <= -59;
                    if (!opening) {
                        put(new RsPos(x, y, 0), Kind.SOLID, "minecraft:stone_bricks");
                    }
                }
            }
            return this;
        }
    }

    static Frame frame() {
        return new Frame(new RsPos(0, -60, 0), RsDir.EAST, RsDir.SOUTH);
    }

    static Module door() {
        return PistonDoor.plan(frame(), 2, "minecraft:stone_bricks", "minecraft:stone_bricks");
    }

    @Test
    void aDoorWithALeverInFrontPlans() {
        FakeWorld w = new FakeWorld().wall(-10, 11);
        MechanismPlan p = MechanismPlanner.plan(w, door(),
                List.of(new MechanismPlanner.ControlAsk("lever", new RsPos(0, -61, -6))), Set.of());
        assertTrue(p.ok(), p.why());
        assertEquals(1, p.controls().size());
        assertEquals(new RsPos(0, -60, -6), p.controls().get(0).use());
        // every wire and repeater sits at channel depth or on the control's drop, and on a support
        for (RsCell c : p.cells()) {
            if (c.block().equals("minecraft:redstone_wire") || c.block().equals("minecraft:repeater")) {
                assertTrue(c.pos().y() <= -62, "wire above the floor at " + c.pos());
            }
        }
        // what it replaces is wall only (the pistons, the towers and the parked blocks), never the ground
        assertTrue(p.replaced().stream().allMatch(r -> r.startsWith("minecraft:stone_bricks")), p.replaced().toString());
        // controls are placed last, torches after plain parts
        RsCell last = p.cells().get(p.cells().size() - 1);
        assertEquals("minecraft:lever", last.block());
    }

    @Test
    void controlsInsideAndOutsideBothReachTheDoor() {
        FakeWorld w = new FakeWorld().wall(-10, 11);
        MechanismPlan p = MechanismPlanner.plan(w, door(), List.of(
                new MechanismPlanner.ControlAsk("lever", new RsPos(-1, -61, -5)),
                new MechanismPlanner.ControlAsk("button", new RsPos(3, -61, 5))), Set.of());
        assertTrue(p.ok(), p.why());
        assertEquals(2, p.controls().size());
    }

    @Test
    void aLongRunGetsARepeaterFacingItsInput() {
        FakeWorld w = new FakeWorld().wall(-10, 11);
        MechanismPlan p = MechanismPlanner.plan(w, door(),
                List.of(new MechanismPlanner.ControlAsk("lever", new RsPos(0, -61, -30))), Set.of());
        assertTrue(p.ok(), p.why());
        List<RsCell> reps = p.cells().stream().filter(c -> c.block().equals("minecraft:repeater")).toList();
        assertFalse(reps.isEmpty(), "a 30-block run needs a repeater");
        // the signal travels south (toward the door, +z), so each repeater's input is on its north side
        assertTrue(reps.stream().allMatch(r -> r.props().get("facing").equals("north")), reps.toString());
    }

    @Test
    void waterBesideTheShortestWayMakesTheWireGoRound() {
        FakeWorld w = new FakeWorld().wall(-10, 11);
        for (int z = -5; z <= -2; z++) {
            w.put(new RsPos(0, -63, z), WorldView.Kind.FLUID, "minecraft:water");
        }
        MechanismPlan p = MechanismPlanner.plan(w, door(),
                List.of(new MechanismPlanner.ControlAsk("lever", new RsPos(0, -61, -8))), Set.of());
        assertTrue(p.ok(), p.why());
        Set<RsPos> wire = p.cells().stream().filter(c -> c.role() == RsCell.Role.CHANNEL).map(RsCell::pos)
                .collect(Collectors.toSet());
        for (RsPos x : wire) {
            for (RsDir d : RsDir.values()) {
                assertFalse(w.kind(x.step(d)) == WorldView.Kind.FLUID, "wire beside water at " + x);
            }
        }
    }

    @Test
    void aChestInTheDoorsWayRefusesWithAReason() {
        FakeWorld w = new FakeWorld().wall(-10, 11);
        w.put(new RsPos(-3, -60, 0), WorldView.Kind.LOCKED, "minecraft:chest");
        MechanismPlan p = MechanismPlanner.plan(w, door(),
                List.of(new MechanismPlanner.ControlAsk("lever", new RsPos(0, -61, -6))), Set.of());
        assertFalse(p.ok());
        assertTrue(p.why().contains("minecraft:chest"), p.why());
    }

    @Test
    void theRouterNeverRunsBesideForeignRedstone() {
        FakeWorld w = new FakeWorld().wall(-10, 11);
        Set<RsPos> foreign = Set.of(new RsPos(0, -63, -3), new RsPos(1, -63, -3), new RsPos(-1, -63, -3));
        MechanismPlan p = MechanismPlanner.plan(w, door(),
                List.of(new MechanismPlanner.ControlAsk("lever", new RsPos(0, -61, -6))), foreign);
        assertTrue(p.ok(), p.why());
        for (RsCell c : p.cells()) {
            if (c.role() != RsCell.Role.CHANNEL || c.pos().y() != -63) {
                continue;
            }
            for (RsDir d : RsDir.values()) {
                assertFalse(foreign.contains(c.pos().step(d)), "wire beside foreign redstone at " + c.pos());
            }
        }
    }

    @Test
    void theDoorsExpectationsCoverTheOpeningAndEveryPiston() {
        Module m = door();
        long opening = m.expects().stream().filter(e -> e.what().equals("the doorway")).count();
        long pistons = m.expects().stream().filter(e -> e.what().equals("a door piston")).count();
        assertEquals(4, opening);
        assertEquals(4, pistons);
    }

    @Test
    void aFrameRotatesTheDrawing() {
        Frame f = new Frame(new RsPos(10, 64, 10), RsDir.NORTH, RsDir.EAST);
        assertEquals(new RsPos(10, 64, 8), f.at(2, 0, 0));
        assertEquals(new RsPos(11, 65, 10), f.at(0, 1, 1));
    }

    @Test
    void aDrivenDoorLaysNoWireAndPutsItsFeedAtAPort() {
        FakeWorld w = new FakeWorld().wall(-8, 10);
        Module door = PistonDoor.plan(frame(), 2, "minecraft:stone_bricks", "minecraft:stone_bricks",
                "minecraft:stone_bricks", false);
        // a lever 12 blocks out in front: far beyond any easy route, and nothing is dug for it
        MechanismPlan p = MechanismPlanner.planLinked(w, door,
                List.of(new MechanismPlanner.ControlAsk("lever", new RsPos(0, -61, -12))), Set.of());
        assertTrue(p.ok(), p.why());
        assertTrue(p.linked());
        assertTrue(door.ports().contains(p.feed()));
        Set<RsPos> doorCells = door.cells().stream().map(RsCell::pos).collect(Collectors.toSet());
        for (RsCell c : p.cells()) {
            if (!doorCells.contains(c.pos())) {
                assertTrue(c.pos().equals(p.feed()) || c.role() == RsCell.Role.CONTROL,
                        "only the feed and the switch are added, not " + c);
            }
        }
        assertEquals(1, p.controls().size());
        assertEquals(null, p.controls().get(0).port());
    }

    @Test
    void aDrivenMechanismIsRefusedOnlyWhenEveryPortIsBlocked() {
        FakeWorld w = new FakeWorld().wall(-8, 10);
        Module door = PistonDoor.plan(frame(), 2, "minecraft:stone_bricks", "minecraft:stone_bricks",
                "minecraft:stone_bricks", false);
        MechanismPlan p = MechanismPlanner.planLinked(w, door,
                List.of(new MechanismPlanner.ControlAsk("lever", new RsPos(0, -61, -3))), Set.copyOf(door.ports()));
        assertFalse(p.ok());
    }
}
