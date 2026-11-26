package com.moddingfromamod.redstone;

import java.util.ArrayList;
import java.util.List;

/**
 * The devices a {@link DeviceModule} switches, each with what must change when it is switched, and WHERE they go for a
 * wish: in a building, at a spot outside, or in a doorway. Pure.
 */
public final class Devices {

    private Devices() {
    }

    static DeviceModule.Device one(RsCell cell, String rest, String fired, String what) {
        return new DeviceModule.Device(List.of(cell), List.of(new Expect(cell.pos(), rest, fired, what)),
                cell.pos().up(-1), what);
    }

    /** A redstone lamp at {@code p} (lit when switched). */
    public static DeviceModule.Device lamp(RsPos p) {
        return one(RsCell.of(p, "minecraft:redstone_lamp", RsCell.Role.PART, "lit", "false"),
                "minecraft:redstone_lamp[lit=false]", "minecraft:redstone_lamp[lit=true]", "a lamp");
    }

    /** An iron door, lower half at {@code p}, facing {@code facing}, hinge left or right. */
    public static DeviceModule.Device ironDoor(RsPos p, RsDir facing, boolean hingeRight) {
        String h = hingeRight ? "right" : "left";
        RsCell lower = RsCell.of(p, "minecraft:iron_door", RsCell.Role.PART, "facing", facing.id(), "half", "lower",
                "hinge", h, "open", "false", "powered", "false");
        RsCell upper = RsCell.of(p.up(1), "minecraft:iron_door", RsCell.Role.PART, "facing", facing.id(), "half",
                "upper", "hinge", h, "open", "false", "powered", "false");
        return new DeviceModule.Device(List.of(lower, upper),
                List.of(new Expect(p, "minecraft:iron_door[open=false]", "minecraft:iron_door[open=true]", "an iron door")),
                p.up(-1), "an iron door");
    }

    /** A fence gate (any wood) at {@code p}, facing along the way through it. */
    public static DeviceModule.Device fenceGate(RsPos p, RsDir facing, String block) {
        return one(RsCell.of(p, block, RsCell.Role.PART, "facing", facing.id(), "open", "false", "powered", "false",
                        "in_wall", "false"),
                block + "[open=false]", block + "[open=true]", "a gate");
    }

    /** A trapdoor at {@code p} (iron unless a wooden one is asked for), fed from the block {@code feed} beside it. */
    public static DeviceModule.Device trapdoor(RsPos p, RsDir facing, String block, String half, RsPos feed) {
        RsCell c = RsCell.of(p, block, RsCell.Role.PART, "facing", facing.id(), "half", half, "open", "false",
                "powered", "false", "waterlogged", "false");
        return new DeviceModule.Device(List.of(c), List.of(new Expect(p, block + "[open=false]", block + "[open=true]",
                "a trapdoor")), feed, "a trapdoor");
    }

    /**
     * A dispenser (or dropper) at {@code p} facing {@code facing} ("up", "down" or a compass direction), loaded with
     * {@code items} ("minecraft:arrow*64;…").
     */
    public static DeviceModule.Device dispenser(RsPos p, String facing, String items, boolean dropper) {
        String id = dropper ? "minecraft:dropper" : "minecraft:dispenser";
        return one(RsCell.of(p, id, RsCell.Role.PART, "facing", facing, "triggered", "false", "_items", items),
                id + "[triggered=false]", id + "[triggered=true]", dropper ? "a dropper" : "a dispenser");
    }

    /** A note block at {@code p} playing {@code note} (0–24) on the block under it (dirt: a harp). */
    public static DeviceModule.Device noteBlock(RsPos p, int note, String instrument) {
        return one(RsCell.of(p, "minecraft:note_block", RsCell.Role.PART, "note", String.valueOf(note),
                        "instrument", instrument, "powered", "false"),
                "minecraft:note_block[powered=false]", "minecraft:note_block[powered=true]", "a note block");
    }

    // ── where they go ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Floor spots inside a building's box for {@code count} things set flush in the floor: an even grid two blocks in
     * from the walls, on floor cells (a solid floor with two walkable cells above). Box is {x0,y0,z0,x1,y1,z1}.
     */
    public static List<RsPos> floorGrid(WorldView w, int[] box, int count) {
        List<RsPos> floor = new ArrayList<>();
        for (int y = box[1]; y <= box[1] + 1; y++) {
            for (int x = box[0] + 2; x <= box[3] - 2; x++) {
                for (int z = box[2] + 2; z <= box[5] - 2; z++) {
                    RsPos p = new RsPos(x, y, z);
                    if (Doorway.wall(w, p) && Doorway.passable(w, p.up(1)) && Doorway.passable(w, p.up(2))) {
                        floor.add(p);
                    }
                }
            }
            if (!floor.isEmpty()) {
                break;
            }
        }
        // an even lattice first (every 4 blocks from the walls), so lamps spread over the room rather than a corner
        floor.sort(java.util.Comparator.comparingInt((RsPos p) -> ((p.x() - box[0] - 2) % 4 == 0
                && (p.z() - box[2] - 2) % 4 == 0) ? 0 : 1));
        // 4 apart, not 3: at 3 the bus between two rows has to pass beside a column's strongly powered base, which
        // the router refuses (measured: 6 and 12 lamps in a house could not be wired)
        return spread(floor, count, 4);
    }

    /** {@code count} ground spots round {@code centre} (flush in the ground: solid with two walkable cells above). */
    public static List<RsPos> groundRing(WorldView w, RsPos centre, int count, int radius) {
        List<RsPos> ground = new ArrayList<>();
        for (int r = radius; r <= radius + 3 && ground.size() < count * 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    for (int dy = 1; dy >= -2; dy--) {
                        RsPos p = centre.plus(dx, dy, dz);
                        if (Doorway.wall(w, p) && Doorway.passable(w, p.up(1)) && Doorway.passable(w, p.up(2))) {
                            ground.add(p);
                            break;
                        }
                    }
                }
            }
        }
        return spread(ground, count, 3);
    }

    /** Picks up to {@code count} of {@code cells}, each at least {@code gap} from the others (by Chebyshev distance). */
    static List<RsPos> spread(List<RsPos> cells, int count, int gap) {
        List<RsPos> out = new ArrayList<>();
        for (RsPos c : cells) {
            if (out.size() >= count) {
                break;
            }
            boolean far = true;
            for (RsPos o : out) {
                if (Math.max(Math.abs(o.x() - c.x()), Math.abs(o.z() - c.z())) < gap) {
                    far = false;
                    break;
                }
            }
            if (far) {
                out.add(c);
            }
        }
        return out;
    }
}
