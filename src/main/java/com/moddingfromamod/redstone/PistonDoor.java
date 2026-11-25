package com.moddingfromamod.redstone;

import java.util.ArrayList;
import java.util.List;

/**
 * A flush sticky-piston door, 1 or 2 wide and 2 tall, set into a wall. Proven in a vanilla 1.21.8 lab before it was
 * written here (docs/REDSTONE.md §3):
 * <ul>
 *   <li>Each side has two sticky pistons stacked in the wall, facing the opening, with the door's blocks parked in the
 *       wall beside the opening while the door is open.</li>
 *   <li>Behind each column is a torch tower: block, torch, block, torch, block, from channel depth up. The lower torch
 *       inverts the channel, and the upper torch inverts it back, so both pistons follow the channel. The upper torch
 *       powers the lower piston directly, and the top block, strongly powered by that torch, powers the upper piston.
 *       Nothing depends on quasi-connectivity.</li>
 *   <li>At channel depth (3 below the floor) a wire bridge runs under the doorway between the two towers. A wire
 *       arrives at the bridge from in front or from behind.</li>
 * </ul>
 * Polarity: channel powered = pistons extended = the door CLOSED; channel off = OPEN.
 *
 * <p>Local frame ({@link Frame}): the opening's lower-left cell is (0, 0, 0); u runs along the wall; w = 0 is the wall's
 * outer layer.
 */
public final class PistonDoor {

    public static final int CHANNEL_DY = -3;

    private PistonDoor() {
    }

    /**
     * @param f        the frame
     * @param width    1 or 2
     * @param material the door's block (also the casing at floor level); should be a full solid block
     * @param towerTop a full solid conducting block for the top of each tower (the wall's own block where it conducts)
     */
    public static Module plan(Frame f, int width, String material, String towerTop) {
        return plan(f, width, material, towerTop, towerTop);
    }

    /**
     * {@code material} is what the door is made of (it fills the doorway when shut); {@code wallBlock} is the wall's
     * own block, which every visible casing is made of so the mechanism reads as wall.
     */
    public static Module plan(Frame f, int width, String material, String towerTop, String wallBlock) {
        if (width < 1 || width > 2) {
            throw new IllegalArgumentException("a piston door is 1 or 2 wide, not " + width);
        }
        List<RsCell> cells = new ArrayList<>();
        List<Expect> expects = new ArrayList<>();
        // the opening: clear at rest (open); filled with the door's blocks when the channel is on (closed)
        for (int u = 0; u < width; u++) {
            for (int dy = 0; dy < 2; dy++) {
                cells.add(RsCell.of(f.at(u, dy, 0), "minecraft:air", RsCell.Role.CLEAR));
                expects.add(new Expect(f.at(u, dy, 0), "minecraft:air", material, "the doorway"));
            }
        }
        side(f, cells, expects, -1, f.alongPlus(), material, towerTop, wallBlock);
        if (width == 2) {
            side(f, cells, expects, width, f.alongMinus(), material, towerTop, wallBlock);
        }
        // the bridge under the doorway at channel depth, from the left tower to the right tower (or to u=0 when 1 wide)
        int bridgeTo = width == 2 ? width + 1 : 0;
        for (int u = -2; u <= bridgeTo; u++) {
            cells.add(RsCell.of(f.at(u, CHANNEL_DY - 1, 0), "minecraft:stone", RsCell.Role.CASING));
            cells.add(RsCell.of(f.at(u, CHANNEL_DY, 0), "minecraft:redstone_wire", RsCell.Role.CHANNEL));
        }
        List<RsPos> ports = new ArrayList<>();
        int portU = width == 2 ? 0 : 0;
        ports.add(f.at(portU, CHANNEL_DY, -1));
        ports.add(f.at(portU, CHANNEL_DY, 1));
        List<String> notes = List.of(
                "a flush sticky-piston door " + width + " wide and 2 tall: switched on it closes, switched off it opens");
        return new Module("piston_door", cells, expects, ports, f.origin().y() + CHANNEL_DY, notes);
    }

    /** One side: parked blocks, two pistons and the torch tower. {@code parkU} is the parked column's u. */
    private static void side(Frame f, List<RsCell> cells, List<Expect> expects, int parkU, RsDir toOpening,
                             String material, String towerTop, String wallBlock) {
        int step = parkU < 0 ? -1 : 1;                 // away from the opening along u
        int pistonU = parkU + step;
        int towerU = pistonU + step;
        String facing = toOpening.id();
        for (int dy = 0; dy < 2; dy++) {
            cells.add(RsCell.of(f.at(parkU, dy, 0), material, RsCell.Role.PART));
            cells.add(RsCell.of(f.at(pistonU, dy, 0), "minecraft:sticky_piston", RsCell.Role.PART,
                    "facing", facing, "extended", "false"));
            expects.add(new Expect(f.at(pistonU, dy, 0), "minecraft:sticky_piston[extended=false]",
                    "minecraft:sticky_piston[extended=true]", "a door piston"));
        }
        // the tower, channel depth up to the top piston's height
        cells.add(RsCell.of(f.at(towerU, CHANNEL_DY - 1, 0), "minecraft:stone", RsCell.Role.CASING));
        cells.add(RsCell.of(f.at(towerU, -3, 0), "minecraft:stone", RsCell.Role.PART));
        cells.add(RsCell.of(f.at(towerU, -2, 0), "minecraft:redstone_torch", RsCell.Role.PART));
        cells.add(RsCell.of(f.at(towerU, -1, 0), "minecraft:stone", RsCell.Role.PART));
        // placed in its resting state, OFF: placed lit it would pulse the sticky pistons for the 2 ticks it takes to go
        // out, and a sticky piston given a short pulse spits its block (measured: the doorway built shut).
        cells.add(RsCell.of(f.at(towerU, 0, 0), "minecraft:redstone_torch", RsCell.Role.PART, "lit", "false"));
        cells.add(RsCell.of(f.at(towerU, 1, 0), towerTop, RsCell.Role.PART));
        // casing: round the buried torch, and a pillar of the wall's own blocks over the pistons and the tower on
        // both faces, so neither a piston's side nor a torch shows (measured on film: both did, and stone casing
        // stood proud of the grass). The row at floor level -1 is the wall's material too, in case it is exposed.
        for (int w : new int[]{-1, 1}) {
            cells.add(RsCell.of(f.at(towerU, -2, w), "minecraft:stone", RsCell.Role.CASING));
            cells.add(RsCell.of(f.at(towerU, -1, w), wallBlock, RsCell.Role.CASING));
            for (int u : new int[]{pistonU, towerU}) {
                for (int dy = 0; dy < 2; dy++) {
                    cells.add(RsCell.of(f.at(u, dy, w), wallBlock, RsCell.Role.CASING));
                }
            }
        }
        // and the block beyond the tower, so the tower's torches touch nothing but their own blocks
        for (int dy = -2; dy <= 0; dy++) {
            cells.add(RsCell.of(f.at(towerU + step, dy, 0), dy >= -1 ? wallBlock : "minecraft:stone", RsCell.Role.CASING));
        }
    }
}
