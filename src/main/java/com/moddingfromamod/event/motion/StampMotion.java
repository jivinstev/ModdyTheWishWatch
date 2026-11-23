package com.moddingfromamod.event.motion;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.compat.LevelHeights;
import com.moddingfromamod.event.EventMotion;
import com.moddingfromamod.event.EventWriter;
import com.moddingfromamod.event.shape.StampShape;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>A {@link StampShape} pressed into the world</b> (E2) over {@code growTicks}: a crater
 * opens from the middle out, a cone or mound builds from the ground up.
 *
 * <p>The ground under every column is read ONCE, the first tick: an earlier mod's volcano re-read it every tick,
 * found its own slopes, and grew a cone specified at 26 blocks to 42 with its crater buried.
 */
public final class StampMotion implements EventMotion {

    /** Materials by role; the dug-out space of a crater is always air. */
    public record Palette(BlockState body, BlockState core, BlockState rim) {
    }

    private final StampShape shape;
    private final Palette palette;
    private final BlockPos centre;
    private final int growTicks;
    private List<int[]> columns; // x, z, ground, distance*100
    private int lastFullPass = -1;

    public StampMotion(StampShape shape, Palette palette, BlockPos centre, int growTicks) {
        this.shape = shape;
        this.palette = palette;
        this.centre = centre;
        this.growTicks = Math.max(1, growTicks);
    }

    @Override
    public boolean step(EventWriter w, int age) {
        if (columns == null) {
            columns = new ArrayList<>();
            int r = shape.outerRadius();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (shape.deltaAt(d) == 0 && shape.roleAt(d) == StampShape.Role.NONE) {
                        continue;
                    }
                    int x = centre.getX() + dx, z = centre.getZ() + dz;
                    int g = w.surface(x, z);
                    if (g != Integer.MIN_VALUE) {
                        columns.add(new int[]{x, z, g, (int) Math.round(d * 100)});
                    }
                }
            }
        }
        double f = Math.min(1.0, (age + 1) / (double) growTicks);
        int bottom = LevelHeights.bottom(w.level());
        for (int[] col : columns) {
            if (w.full()) {
                return false; // carry on next tick
            }
            double d = col[3] / 100.0;
            int g = col[2];
            int delta = shape.deltaAt(d);
            BlockState mat = switch (shape.roleAt(d)) {
                case CORE -> palette.core();
                case RIM -> palette.rim();
                default -> palette.body();
            };
            if (delta < 0) {
                if (!shape.revealed(d, 0, f)) {
                    continue;
                }
                int dig = StampShape.clampDig(-delta, g - bottom);
                for (int y = g; y > g - dig; y--) {
                    w.put(new BlockPos(col[0], y, col[1]), Blocks.AIR.defaultBlockState());
                }
                // Line the new floor, so a crater is a crater and never a shaft with no bottom.
                w.put(new BlockPos(col[0], g - dig, col[1]), mat);
            } else if (delta > 0) {
                for (int k = 1; k <= delta; k++) {
                    if (!shape.revealed(d, k, f)) {
                        break;
                    }
                    BlockState m = shape.form == StampShape.Form.CONE && k == delta
                            && shape.roleAt(d) == StampShape.Role.CORE ? palette.core()
                            : shape.form == StampShape.Form.CONE ? palette.body() : mat;
                    w.put(new BlockPos(col[0], g + k, col[1]), m);
                }
            }
        }
        if (f >= 1.0) {
            lastFullPass = age;
        }
        return lastFullPass >= 0;
    }
}
