package com.moddingfromamod.redstone;

import java.util.ArrayList;
import java.util.List;

/**
 * A control a player uses, standing on the ground, with its own way down to channel depth. A lever, a button or a
 * pressure plate on the floor strongly powers the block it stands on. Wire directly under that block takes the power
 * (measured: 15), then steps down to channel depth through a buried air pocket, so nothing shows at the surface.
 *
 * @param kind     lever, button or pressure_plate
 * @param use      where the control itself is (where a player clicks or steps)
 * @param cells    the control and its drop, ending at {@link #port}
 * @param port     the channel-depth cell the router starts from
 * @param strength the signal strength at the port
 */
public record Control(String kind, RsPos use, List<RsCell> cells, RsPos port, int strength, boolean toggle) {

    public Control(String kind, RsPos use, List<RsCell> cells, RsPos port, int strength) {
        this(kind, use, cells, port, strength, false);
    }

    public static final List<String> KINDS = List.of("lever", "button", "pressure_plate", "logic");

    /**
     * Plans a floor control on the block {@code ground} (the top solid block where the player will stand), dropping
     * toward {@code toward} until it reaches {@code channelY}. Returns null when the ground is below channel depth plus
     * one (no room to drop).
     */
    public static Control floor(String kind, RsPos ground, RsDir toward, int channelY, String plateBlock) {
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("unknown control " + kind);
        }
        int under = ground.y() - 1;
        if (under < channelY) {
            return null;
        }
        List<RsCell> cells = new ArrayList<>();
        RsPos use = ground.up(1);
        cells.add(switch (kind) {
            case "lever" -> RsCell.of(use, "minecraft:lever", RsCell.Role.CONTROL,
                    "face", "floor", "facing", toward.id(), "powered", "false");
            case "button" -> RsCell.of(use, "minecraft:stone_button", RsCell.Role.CONTROL,
                    "face", "floor", "facing", toward.id(), "powered", "false");
            default -> RsCell.of(use, plateBlock == null ? "minecraft:stone_pressure_plate" : plateBlock,
                    RsCell.Role.CONTROL, "powered", "false");
        });
        // the block the control stands on must be a conducting block: keep it if it is, else the planner checks
        RsPos dust = new RsPos(ground.x(), under, ground.z());
        cells.add(RsCell.of(dust.up(-1), "minecraft:stone", RsCell.Role.CASING));
        cells.add(RsCell.of(dust, "minecraft:redstone_wire", RsCell.Role.CHANNEL));
        int strength = 15;
        RsPos at = dust;
        while (at.y() > channelY) {
            RsPos next = at.step(toward).up(-1);
            cells.add(RsCell.of(next.up(1), "minecraft:air", RsCell.Role.CLEAR));
            cells.add(RsCell.of(next.up(-1), "minecraft:stone", RsCell.Role.CASING));
            cells.add(RsCell.of(next, "minecraft:redstone_wire", RsCell.Role.CHANNEL));
            strength--;
            at = next;
        }
        return new Control(kind, use, cells, at, strength);
    }

    /**
     * A control on a WALL (a secret button low on a wall, a lever on a pillar): the same drop as a floor control, from
     * under the block it is mounted on ({@code anchor}, which it strongly powers), with the control on the face of
     * that block that looks {@code face}. Mounted at floor level, its wire runs down through the wall's own footing.
     */
    public static Control wall(String kind, RsPos anchor, RsDir face, RsDir toward, int channelY) {
        Control f = floor(kind, anchor, toward, channelY, null);
        if (f == null || kind.equals("pressure_plate")) {
            return null;
        }
        RsPos use = anchor.step(face);
        List<RsCell> cells = new ArrayList<>(f.cells());
        cells.set(0, RsCell.of(use, kind.equals("lever") ? "minecraft:lever" : "minecraft:stone_button",
                RsCell.Role.CONTROL, "face", "wall", "facing", face.id(), "powered", "false"));
        return new Control(kind, use, cells, f.port(), f.strength(), false);
    }

    /**
     * The same control made a TOGGLE: press once and the mechanism switches on and stays on, press again and it
     * switches off. At the bottom of the drop the wire points into a waxed copper bulb (which flips each time it is
     * powered) and a comparator reads the bulb into the channel at full strength. The drop's last step runs along
     * {@code toward}, so the wire at the port points straight into the bulb.
     */
    public static Control toggled(Control c, RsDir toward) {
        List<RsCell> cells = new ArrayList<>(c.cells());
        RsPos bulb = c.port().step(toward), comp = bulb.step(toward), out = comp.step(toward);
        cells.add(RsCell.of(bulb, "minecraft:waxed_copper_bulb", RsCell.Role.PART, "lit", "false", "powered", "false"));
        cells.add(RsCell.of(bulb.up(-1), "minecraft:stone", RsCell.Role.CASING));
        // a comparator's facing names its INPUT side, as a repeater's does
        cells.add(RsCell.of(comp, "minecraft:comparator", RsCell.Role.CHANNEL, "facing", toward.opposite().id(),
                "mode", "compare", "powered", "false"));
        cells.add(RsCell.of(comp.up(-1), "minecraft:stone", RsCell.Role.CASING));
        cells.add(RsCell.of(out, "minecraft:redstone_wire", RsCell.Role.CHANNEL));
        cells.add(RsCell.of(out.up(-1), "minecraft:stone", RsCell.Role.CASING));
        return new Control(c.kind(), c.use(), cells, out, 14, true);
    }

    /**
     * A control with NO wire of its own (a mechanism driven by its datapack, {@link RedstoneScript}): only the lever,
     * button or plate, on the floor ({@code face} null: on top of {@code ground}) or on the face of the block
     * {@code ground} that looks {@code face}. The datapack reads its state; nothing is dug under it. A toggle is done by
     * the datapack too, so it needs no copper bulb.
     */
    public static Control standalone(String kind, RsPos ground, RsDir face, RsDir facing, boolean toggle,
                                     String plateBlock) {
        if (!KINDS.contains(kind) || kind.equals("logic")) {
            throw new IllegalArgumentException("not a standalone control: " + kind);
        }
        RsPos use = face == null ? ground.up(1) : ground.step(face);
        RsCell cell;
        if (face != null) {
            if (kind.equals("pressure_plate")) {
                return null;
            }
            cell = RsCell.of(use, kind.equals("lever") ? "minecraft:lever" : "minecraft:stone_button",
                    RsCell.Role.CONTROL, "face", "wall", "facing", face.id(), "powered", "false");
        } else {
            cell = switch (kind) {
                case "lever" -> RsCell.of(use, "minecraft:lever", RsCell.Role.CONTROL,
                        "face", "floor", "facing", facing.id(), "powered", "false");
                case "button" -> RsCell.of(use, "minecraft:stone_button", RsCell.Role.CONTROL,
                        "face", "floor", "facing", facing.id(), "powered", "false");
                default -> RsCell.of(use, plateBlock == null ? "minecraft:stone_pressure_plate" : plateBlock,
                        RsCell.Role.CONTROL, "powered", "false");
            };
        }
        return new Control(kind, use, List.of(cell), null, 0, toggle);
    }

    /**
     * A LOGIC control (the logic bridge, {@link Logic}): a buried cell at channel depth where the mod sets a redstone
     * block while its condition holds, beside the wire the router starts from. Nothing of it shows: what the child
     * sees are the condition's own parts (a coin barrel, code buttons, a target), which are not wired at all.
     */
    public static Control logic(RsPos cell, RsDir toward) {
        List<RsCell> cells = new ArrayList<>();
        cells.add(RsCell.of(cell, "minecraft:air", RsCell.Role.CLEAR));
        cells.add(RsCell.of(cell.up(-1), "minecraft:stone", RsCell.Role.CASING));
        RsPos wire = cell.step(toward);
        cells.add(RsCell.of(wire, "minecraft:redstone_wire", RsCell.Role.CHANNEL));
        cells.add(RsCell.of(wire.up(-1), "minecraft:stone", RsCell.Role.CASING));
        return new Control("logic", cell, cells, wire, 14, false);
    }
}
