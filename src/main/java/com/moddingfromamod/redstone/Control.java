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
public record Control(String kind, RsPos use, List<RsCell> cells, RsPos port, int strength) {

    public static final List<String> KINDS = List.of("lever", "button", "pressure_plate");

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
}
