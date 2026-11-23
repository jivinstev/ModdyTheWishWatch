package com.moddingfromamod.compat;

import net.minecraft.world.level.LevelHeightAccessor;

/**
 * <b>The vertical extent of a level</b> (1.21.4 half).
 *
 * <p>1.21.2 renamed {@code getMinBuildHeight} to {@code getMinY} (same value) and replaced the EXCLUSIVE
 * {@code getMaxBuildHeight} with an INCLUSIVE {@code getMaxY}. A bare rename of the second shortens
 * every bound by one block with a clean compile (the migrator's section V23), so the question here is
 * named by its meaning: {@link #topExclusive} is the first Y that is NOT in the world, on both targets.
 */
public final class LevelHeights {
    private LevelHeights() {
    }

    /** The lowest Y inside the world (inclusive). */
    public static int bottom(LevelHeightAccessor level) {
        return level.getMinY();
    }

    /** The first Y above the world (exclusive) -- 1.21.1's {@code getMaxBuildHeight()}. */
    public static int topExclusive(LevelHeightAccessor level) {
        return level.getMaxY() + 1;
    }
}
