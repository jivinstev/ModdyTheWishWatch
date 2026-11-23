package com.moddingfromamod.world;

import java.util.UUID;

/**
 * One player's base: whose, in which dimension, its origin, where he stands when sent home,
 * and its two regions — the {@code area} nothing his wishes do may reach past, and the smaller
 * {@code reach} a wish's anchor must be inside (the area less a margin as wide as the widest build,
 * so a building anchored anywhere in the reach cannot overhang a neighbour). Minecraft-free.
 */
public record Base(UUID owner, String dimension, int x, int y, int z, int homeY, Region area, Region reach) {

    /** Is this position on this base (its dimension, inside its area)? */
    public boolean holds(String dim, double px, double pz) {
        return (dim == null || dim.isEmpty() || dimension.equals(dim)) && area.contains(px, pz);
    }

    /** "(x, y, z)" — the origin, for a refusal the agent reads. */
    public String origin() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
