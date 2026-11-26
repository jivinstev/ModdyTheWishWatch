package com.moddingfromamod.redstone;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>Any block that a powered block switches on</b> — a lamp, an iron door, a trapdoor, a fence gate, a dispenser, a
 * dropper, a note block, a piston — wired from underground, so one design serves them all.
 *
 * <p>Each device has a FEED block: the block whose being powered switches the device (normally the block it stands
 * on). Under the feed is a torch column: feed, torch, block, torch, block, from the bottom of which a repeater faces
 * up into it from the side. Two torches (four blocks down) make the device follow the signal (on when switched on);
 * one torch (two blocks down) inverts it (on at rest, off when switched on). A repeater rather than a wire enters
 * each column, because wire beside another column's wire bends into a line and stops pointing at its own column; a
 * repeater never does, so devices can stand side by side. Every repeater's input is a wire cell, and those wires
 * are joined into one buried bus, from a hub to every other device; a control's wire arrives at the hub's port.
 *
 * <p>Pure: it reads only a {@link WorldView}; the device blocks themselves are given by the caller.
 */
public final class DeviceModule {

    /**
     * One device: the cells that make it (one for a lamp, two for a door), what must change when it is switched
     * ({@code expects}), and its feed block.
     */
    public record Device(List<RsCell> cells, List<Expect> expects, RsPos feed, String what) {
    }

    /** A plan or the reason there is none. */
    public record Result(Module module, String why) {
        public boolean ok() {
            return why == null;
        }
    }

    /** The bus's need at each device's wire: the repeater fires at 1, and one more for the step into it. */
    static final int NEED = 2;
    /** What a leg of the bus must arrive with when another leg continues from it. */
    static final int CARRY = 8;

    private DeviceModule() {
    }

    /**
     * @param follows   true: a device is ON while switched on (two torches); false: ON at rest (one torch)
     * @param foreign   redstone already in the world to keep away from
     */
    public static Result plan(WorldView w, String kind, List<Device> devices, boolean follows, Set<RsPos> foreign,
                              List<String> notes) {
        if (devices.isEmpty()) {
            return new Result(null, "no place for any " + kind + " was found");
        }
        int feedY = devices.get(0).feed().y();
        for (Device d : devices) {
            if (d.feed().y() != feedY) {
                return new Result(null, "the " + kind + " stand at different heights; one mechanism switches things "
                        + "on one level (make one for each level)");
            }
        }
        int depth = follows ? 4 : 2;
        int busY = feedY - depth;
        List<String> whys = new ArrayList<>();
        for (RsDir d : RsDir.values()) {
            Result r = tryDir(w, kind, devices, follows, foreign, notes, busY, d);
            if (r.ok()) {
                return r;
            }
            whys.add(d.id() + ": " + r.why());
        }
        return new Result(null, String.join("; ", whys));
    }

    static Result tryDir(WorldView w, String kind, List<Device> devices, boolean follows, Set<RsPos> foreign,
                         List<String> notes, int busY, RsDir d) {
        List<RsCell> cells = new ArrayList<>();
        List<Expect> expects = new ArrayList<>();
        List<RsPos> wires = new ArrayList<>();      // each device's bus wire (the repeater's input)
        for (Device dev : devices) {
            cells.addAll(dev.cells());
            expects.addAll(dev.expects());
            RsPos f = dev.feed();
            // the feed itself: kept if it already conducts, else made solid
            cells.add(RsCell.of(f, "minecraft:stone", RsCell.Role.CASING));
            // the column under it, torches in their RESTING state (the signal off)
            if (follows) {
                cells.add(RsCell.of(f.up(-1), "minecraft:redstone_torch", RsCell.Role.PART, "lit", "false"));
                cells.add(RsCell.of(f.up(-2), "minecraft:stone", RsCell.Role.PART));
                cells.add(RsCell.of(f.up(-3), "minecraft:redstone_torch", RsCell.Role.PART, "lit", "true"));
            } else {
                cells.add(RsCell.of(f.up(-1), "minecraft:redstone_torch", RsCell.Role.PART, "lit", "true"));
            }
            RsPos base = f.up(follows ? -4 : -2);
            cells.add(RsCell.of(base, "minecraft:stone", RsCell.Role.PART));
            RsPos rep = base.step(d), wire = rep.step(d);
            // a repeater's facing names its INPUT side (measured)
            cells.add(RsCell.of(rep, "minecraft:repeater", RsCell.Role.CHANNEL, "facing", d.id(), "delay", "1"));
            cells.add(RsCell.of(wire, "minecraft:redstone_wire", RsCell.Role.CHANNEL));
            cells.add(RsCell.of(rep.up(-1), "minecraft:stone", RsCell.Role.CASING));
            cells.add(RsCell.of(wire.up(-1), "minecraft:stone", RsCell.Role.CASING));
            wires.add(wire);
        }
        // cells a bus wire may pass beside: each column's repeater (a wire beside a repeater's side changes
        // nothing) and every stone support or casing
        Set<RsPos> inert = new HashSet<>();
        for (RsCell c : cells) {
            if (c.role() == RsCell.Role.CASING || c.block().equals("minecraft:repeater")) {
                inert.add(c.pos());
            }
        }
        // every planned cell must be placeable
        Set<RsPos> taken = new HashSet<>();
        for (RsCell c : cells) {
            if (!taken.add(c.pos()) && c.role() != RsCell.Role.CASING) {
                return new Result(null, "two " + kind + " are too close together to wire (" + c.pos() + ")");
            }
            WorldView.Kind k = w.kind(c.pos());
            if (k == WorldView.Kind.LOCKED && !(c.role() == RsCell.Role.CASING && w.conductor(c.pos()))) {
                return new Result(null, "it would have to replace " + w.blockId(c.pos()) + " at " + c.pos());
            }
            if (k == WorldView.Kind.FLUID) {
                return new Result(null, "water or lava at " + c.pos() + " is in the way of the wiring");
            }
        }
        // the bus: a tree from the hub (the first device) to every device whose wire is not already touching it
        RsPos hub = wires.get(0);
        // the hub's spine: three wires straight out from the hub's wire. Controls arrive at its far end (three
        // ports: ahead and either side); branches to the other devices leave from its near end. One port alone was
        // measured too few (a switch each side of a door could not both reach it), and branches leaving from the
        // hub itself boxed it in.
        RsDir e = d.clockwise();
        RsPos s1 = hub.step(d), s2 = s1.step(d), s3 = s2.step(d);
        for (RsPos sp : List.of(s1, s2, s3)) {
            if (!taken.add(sp)) {
                return new Result(null, "no room for the wiring's spine at " + sp);
            }
            cells.add(RsCell.of(sp, "minecraft:redstone_wire", RsCell.Role.CHANNEL));
            cells.add(RsCell.of(sp.up(-1), "minecraft:stone", RsCell.Role.CASING));
            taken.add(sp.up(-1));
        }
        List<RsPos> ports = List.of(s3.step(d), s3.step(e), s3.step(e.opposite()));
        Set<RsPos> reserved = new HashSet<>(ports);
        reserved.add(s3.step(d).step(d));
        reserved.add(s3.step(e).step(e));
        reserved.add(s3.step(e.opposite()).step(e.opposite()));
        // the weakest signal each joined wire can carry: a control arrives at a port with at least PORT_NEED
        java.util.Map<RsPos, Integer> joined = new java.util.HashMap<>();
        int arrive = MechanismPlanner.PORT_NEED;
        joined.put(s1, arrive - 3);
        joined.put(hub, arrive - 4);
        // the bus: ONE chain from the hub to the nearest unjoined device, then on from there to the next. A tree of
        // branches from the hub was measured to box itself in (the first branch took the only corridor out).
        RsPos at = s1;
        int strength = arrive - 3;
        List<RsPos> left = new ArrayList<>(wires.subList(1, wires.size()));
        java.util.Set<RsPos> cluster = new HashSet<>(List.of(hub));
        while (!left.isEmpty()) {
            RsPos here = at;
            left.sort(java.util.Comparator.comparingInt(q -> q.manhattan(here)));
            RsPos target = left.remove(0);
            if (touchesAny(target, cluster)) {
                cluster.add(target);           // side by side with a joined device: their wires already touch
                continue;
            }
            Set<RsPos> own = new HashSet<>(taken);
            own.addAll(reserved);
            own.remove(at);
            // join beside the target's wire, at a free cell touching nothing else planned. A column's base is
            // STRONGLY powered by its repeater, so a bus wire beside another column's base would pick up full power
            // and latch the whole mechanism on (measured: two of four lamps stayed lit after the lever).
            // straight ahead of the wire first (a row of devices then gets a bus in a line beyond them, which a
            // later leg can continue from); either side only if that cell is not free
            List<RsPos> goals = new ArrayList<>();
            RsPos ahead = target.step(d);
            if (!own.contains(ahead) && ChannelRouter.free(w, ahead, without(own, target), foreign)) {
                goals.add(ahead);
            } else {
                for (RsDir g : List.of(e, e.opposite())) {
                    RsPos q = target.step(g);
                    if (!own.contains(q) && ChannelRouter.free(w, q, without(own, target), inert, foreign)) {
                        goals.add(q);
                    }
                }
            }
            if (goals.isEmpty()) {
                return new Result(null, "no room beside one of the " + kind + " to join the wiring");
            }
            // a leg that is not the last must arrive strong enough to carry on (a weak arrival died at the next
            // corner, measured on a 2x2 lamp grid), so the router puts its repeaters in earlier
            int need = left.isEmpty() ? NEED : CARRY;
            ChannelRouter.Route r = ChannelRouter.route(w, busY, at, strength, goals, need, own, inert, foreign);
            if (!r.ok()) {
                return new Result(null, "the wiring between the " + kind + " could not be laid (from " + at + " to "
                        + target + "): " + r.why());
            }
            for (RsCell c : r.cells()) {
                if (taken.add(c.pos())) {
                    cells.add(c);
                }
                if (c.role() == RsCell.Role.CASING || c.block().equals("minecraft:repeater")) {
                    inert.add(c.pos());
                }
            }
            RsPos end = r.cells().stream().filter(c -> c.pos().y() == busY).reduce((a, b) -> b).get().pos();
            cluster.add(target);
            at = end;
            strength = r.arrival();
        }
        // the control's port: beyond the hub's wire, on the far side from its repeater

        return new Result(new Module(kind, cells, expects, ports, busY, notes), null);
    }

    static Set<RsPos> without(Set<RsPos> set, RsPos p) {
        Set<RsPos> s = new HashSet<>(set);
        s.remove(p);
        return s;
    }

    static boolean touchesAny(RsPos p, Set<RsPos> set) {
        for (RsDir d : RsDir.values()) {
            if (set.contains(p.step(d))) {
                return true;
            }
        }
        return false;
    }

    /** The nearest joined wire that still carries at least 3 (enough to branch from). */
    static RsPos nearest(java.util.Map<RsPos, Integer> joined, RsPos to) {
        RsPos best = null;
        for (var en : joined.entrySet()) {
            RsPos p = en.getKey();
            if (en.getValue() < 3) {
                continue;
            }
            if (best == null || p.manhattan(to) < best.manhattan(to)) {
                best = p;
            }
        }
        return best;
    }
}
