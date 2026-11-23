package com.moddingfromamod.place;

/**
 * <b>Where a NEW building goes when it is put by a thing</b> — "a stable next to my house",
 * "a wall around my castle", "a lighthouse on top of my castle", "a doghouse in front of me".
 * Minecraft-free: {@code BuildBesideTest}.
 *
 * <p>Two ways, by the spot:
 * <ul>
 *   <li><b>OUTWARD</b> — front, behind, beside, a compass side: the new building stands OUTSIDE the
 *   thing on that side, its near edge on the spot (a block of air between the walls), centred along
 *   it. The way out is from the thing's middle to the spot, snapped to the compass, so it serves a
 *   building's side and the space in front of him alike.</li>
 *   <li><b>CENTRED</b> — around, top, a room, a marked spot, a creature's or his own place: the new
 *   building's footprint is centred on the spot (a wall ring round the castle, a tower on its roof).</li>
 * </ul>
 * Its ground layer ({@code dy 0}) goes at the spot's feet height, the same height an ordinary build's
 * anchor has: the air on top of the ground.
 */
public final class BuildBeside {

    private BuildBeside() {
    }

    /** Spots a new building stands OUTSIDE of, rather than centred on. */
    public static boolean outward(String spot) {
        return switch (spot == null ? "" : spot) {
            case "front", "behind", "beside", "north", "east", "south", "west" -> true;
            default -> false;
        };
    }

    /**
     * The new building's anchor.
     *
     * @param nb     its bounds relative to its own anchor, {x0, y0, z0, x1, y1, z1} inclusive
     * @param sx,sz  the spot, in the world
     * @param feetY  the feet height there
     * @param ox,oz  the thing's middle (a building's footprint centre, a body's position)
     * @param out    {@link #outward}
     * @return {x, y, z}
     */
    public static int[] anchor(int[] nb, double sx, double feetY, double sz, double ox, double oz, boolean out) {
        int bx = (int) Math.floor(sx), bz = (int) Math.floor(sz), by = (int) Math.floor(feetY);
        int midX = Math.floorDiv(nb[0] + nb[3], 2), midZ = Math.floorDiv(nb[2] + nb[5], 2);
        if (!out) {
            return new int[]{bx - midX, by, bz - midZ};
        }
        double dx = sx - ox, dz = sz - oz;
        if (Math.abs(dx) >= Math.abs(dz) && Math.abs(dx) > 1e-9) {
            return dx > 0 ? new int[]{bx - nb[0], by, bz - midZ}     // east: its west edge on the spot
                    : new int[]{bx - nb[3], by, bz - midZ};          // west: its east edge on the spot
        }
        return dz > 0 ? new int[]{bx - midX, by, bz - nb[2]}         // south: its north edge on the spot
                : new int[]{bx - midX, by, bz - nb[5]};              // north: its south edge on the spot
    }
}
