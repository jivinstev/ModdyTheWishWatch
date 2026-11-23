package com.moddingfromamod.event.motion;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.moddingfromamod.event.EventMotion;
import com.moddingfromamod.event.EventWriter;
import com.moddingfromamod.event.shape.FrontShape;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>A {@link FrontShape} played in the world</b> (E2): each tick it works out every cell the
 * front occupies now, gives back the cells it has left and fills the cells it has reached. So the
 * event only ever holds the front itself, never the whole trail, and the world behind it is back as
 * it was the moment the front passes.
 */
public final class MovingFrontMotion implements EventMotion {

    private final FrontShape shape;
    private final BlockState material;
    private final double ox, oz;
    private final double hx, hz;   // heading, unit
    private final double px, pz;   // across, unit
    /** Ground under each column, read once the first time the front reaches it. */
    private final Map<Long, Integer> ground = new HashMap<>();
    private Set<Long> held = new LinkedHashSet<>();
    /** A wrecking wave smashes what stands in its way; these cells were his, and stay smashed. */
    private boolean wrecks;
    private final Set<Long> smashed = new java.util.HashSet<>();
    private double lastProgress = -1;
    private Runnable onBreak;

    /** A front born at {@code origin}, rolling toward {@code yawDegrees} (Minecraft's yaw). */
    public MovingFrontMotion(FrontShape shape, BlockState material, BlockPos origin, float yawDegrees) {
        this.shape = shape;
        this.material = material;
        this.ox = origin.getX() + 0.5;
        this.oz = origin.getZ() + 0.5;
        double r = Math.toRadians(yawDegrees);
        this.hx = -Math.sin(r);
        this.hz = Math.cos(r);
        this.px = -hz;
        this.pz = hx;
    }

    /** It smashes through his builds instead of flowing round them; what it smashed is left as air until undo. */
    public MovingFrontMotion wrecking(boolean on) {
        this.wrecks = on;
        return this;
    }

    /** Test seam: how many cells it has smashed. */
    public int smashed() {
        return smashed.size();
    }

    /** Called once, the tick the crest starts to fall (for the crash sound, E3's slam). */
    public MovingFrontMotion onBreak(Runnable r) {
        this.onBreak = r;
        return this;
    }

    public double progress(int age) {
        return age / (double) shape.lifeTicks();
    }

    /** How hard it catches now: as much as there is wave left (E3). */
    public double strength(int age) {
        return FrontShape.heightFactor(progress(age));
    }

    /** True while the crest is falling: it slams what it holds down rather than lifting it. */
    public boolean slamming(int age) {
        return shape.breaks && FrontShape.phaseAt(progress(age)) == FrontShape.Phase.TUMBLING;
    }

    /** The heading, as Minecraft's yaw, for a {@code Catch} to push along. */
    public float yaw() {
        return (float) Math.toDegrees(Math.atan2(-hx, hz));
    }

    /** The middle of the front face at this age, on the ground it started from. */
    public BlockPos frontAt(int age) {
        double dist = age * FrontShape.SPEED;
        return BlockPos.containing(ox + hx * dist, 0, oz + hz * dist);
    }

    @Override
    public boolean step(EventWriter w, int age) {
        double p = progress(age);
        if (onBreak != null && FrontShape.justBroke(lastProgress, p)) {
            onBreak.run();
        }
        lastProgress = p;
        Set<Long> want = p >= 1.0 ? Set.of() : cells(w, age, p);
        for (Long c : held) {
            if (!want.contains(c)) {
                if (smashed.contains(c)) {
                    w.leave(BlockPos.of(c), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                } else {
                    w.release(BlockPos.of(c));
                }
            }
        }
        Set<Long> now = new LinkedHashSet<>();
        for (Long c : want) {
            BlockPos pos = BlockPos.of(c);
            boolean in = w.put(pos, material) || w.ours(pos);
            if (!in && wrecks && !w.full() && w.breakBlock(pos)) {
                // In its way: smashed (journaled, so undo rebuilds it), then the water goes through.
                smashed.add(c);
                in = w.put(pos, material) || w.ours(pos);
            }
            if (in) {
                now.add(c);
            }
        }
        held = now;
        return p >= 1.0;
    }

    /** Every cell the front occupies at this age. */
    Set<Long> cells(EventWriter w, int age, double p) {
        Set<Long> out = new LinkedHashSet<>();
        double dist = age * FrontShape.SPEED;
        double fx = ox + hx * dist;
        double fz = oz + hz * dist;
        int half = Math.max(1, shape.halfWidthNow(p));
        double overhangBlocks = shape.overhang(p) * shape.height * FrontShape.heightFactor(p);
        for (int c = -half; c <= half; c++) {
            for (int d = 0; d < FrontShape.THICKNESS; d++) {
                int h = shape.columnHeight(p, c / (double) half, d);
                if (h <= 0) {
                    continue;
                }
                double bx = fx - hx * d + px * c;
                double bz = fz - hz * d + pz * c;
                int base = groundAt(w, (int) Math.floor(bx), (int) Math.floor(bz));
                if (base == Integer.MIN_VALUE) {
                    continue;
                }
                for (int y = 0; y < h; y++) {
                    double lean = FrontShape.leanAt(overhangBlocks, y, h);
                    int x = (int) Math.floor(bx + hx * lean);
                    int z = (int) Math.floor(bz + hz * lean);
                    out.add(BlockPos.asLong(x, base + 1 + y, z));
                }
            }
        }
        return out;
    }

    private int groundAt(EventWriter w, int x, int z) {
        long key = BlockPos.asLong(x, 0, z);
        Integer g = ground.get(key);
        if (g == null) {
            g = w.surface(x, z);
            if (wrecks && g != Integer.MIN_VALUE) {
                // A wrecker's ground is the land under his builds, so it goes THROUGH a wall rather than over it.
                net.minecraft.core.BlockPos.MutableBlockPos p = new net.minecraft.core.BlockPos.MutableBlockPos(x, g, z);
                int floor = com.moddingfromamod.compat.LevelHeights.bottom(w.level());
                while (g > floor + 1 && !EventWriter.natural(w.level().getBlockState(p.setY(g)))
                        && w.level().getBlockState(p).getFluidState().isEmpty()) {
                    g--;
                }
            }
            if (g != Integer.MIN_VALUE) {
                ground.put(key, g);
            }
        }
        return g;
    }
}
