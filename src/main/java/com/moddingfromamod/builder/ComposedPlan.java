package com.moddingfromamod.builder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>The building as it will stand, not as it was drawn.</b>
 *
 * <p>A plan is a list of shapes, and later shapes overwrite earlier ones: an island laid over a
 * lava moat leaves no lava there, and an air box carved through a wall leaves a doorway. Anything
 * that asks a question of the building, such as "is there a light near here" or "is this water",
 * must ask it of the result. Asked of the shapes, it reads every overwritten cell as still there.
 * {@link BuildLighting} did exactly that: an earlier issue's castle counted 3,548 cells of a moat the island
 * had covered as lights, hung its lanterns on the lid one storey above the throne room, and the
 * child got a black room. The census over every build with a light shape found 14 of 37 with light
 * cells overwritten ({@code ComposedLightCensusTest}).
 *
 * <p>This is the one composition: {@link BuildPlan#forEachBlock} in order, the last block written
 * at a position wins, and air removes rather than fills. Compose once and hand the result to every
 * check that needs it ({@link BuildLighting}, {@link DarkInterior}, {@link BuildFraming}), so they
 * agree about one building and a big plan is not composed three times in one tool call.
 *
 * <p>Pure and Minecraft-free.
 */
public final class ComposedPlan {

    static final String AIR = "minecraft:air";

    private final BuildPlan plan;
    private final Map<Long, String> blocks;
    /** Light-emitting cells, worked out on first use; [x, y, z, level]. */
    private List<int[]> lights;

    private ComposedPlan(BuildPlan plan, Map<Long, String> blocks) {
        this.plan = plan;
        this.blocks = blocks;
    }

    /** Compose a plan the way the builder places it. A null plan composes to nothing. */
    public static ComposedPlan of(BuildPlan plan) {
        Map<Long, String> blocks = new HashMap<>();
        if (plan != null) {
            plan.forEachBlock((x, y, z, block) -> put(blocks, x, y, z, block));
        }
        return new ComposedPlan(plan, blocks);
    }

    /**
     * This building with more shapes laid on top, without composing the rest again: the lanterns
     * {@link BuildLighting#lightsFor} adds go on last, so they compose last.
     */
    public ComposedPlan plus(BuildPlan withMore, List<BuildShape> added) {
        if (added == null || added.isEmpty()) {
            return withMore == plan ? this : new ComposedPlan(withMore, blocks);
        }
        Map<Long, String> more = new HashMap<>(blocks);
        for (BuildShape s : added) {
            s.forEachBlock((x, y, z) -> put(more, x, y, z, s.block()));
        }
        return new ComposedPlan(withMore, more);
    }

    /**
     * This composition for {@code other}, re-using the blocks when its shapes are the same ones —
     * a plan that only gained spots, a new owner or a stored id between composing it and asking
     * about it. Composes afresh when the shapes differ, so it can never answer for a different
     * building.
     */
    public ComposedPlan as(BuildPlan other) {
        if (other == plan) {
            return this;
        }
        if (other != null && plan != null && other.shapes().equals(plan.shapes())) {
            ComposedPlan same = new ComposedPlan(other, blocks);
            same.lights = lights;
            return same;
        }
        return of(other);
    }

    private static void put(Map<Long, String> blocks, int x, int y, int z, String block) {
        if (block == null || AIR.equals(block)) {
            blocks.remove(key(x, y, z));
        } else {
            blocks.put(key(x, y, z), block);
        }
    }

    /** The plan this was composed from (its shapes and spots). Null for a null plan. */
    public BuildPlan plan() {
        return plan;
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    public int size() {
        return blocks.size();
    }

    /** The block that ends up here, or null for empty. */
    public String blockAt(int x, int y, int z) {
        return blocks.get(key(x, y, z));
    }

    /** Every placed block, in no particular order. */
    public void forEach(BuildPlan.BlockSink sink) {
        for (Map.Entry<Long, String> e : blocks.entrySet()) {
            long k = e.getKey();
            sink.at(kx(k), ky(k), kz(k), e.getValue());
        }
    }

    /**
     * How many of each block the finished building holds, for "what is it mostly made of" (the
     * watch's icon for a building). Counted from the result, not from shape volumes: the lava-moat
     * castle DRAWS more lava than stone bricks (most of the moat is under its island) and its icon
     * was a lava bucket. Remembered per plan — the watch asks on every open, for every building he
     * has, and the biggest real build takes ~20 ms to compose — and a plan is immutable, so a
     * changed building is a different key.
     */
    public static Map<String, Integer> blockCounts(BuildPlan plan) {
        if (plan == null) {
            return Map.of();
        }
        synchronized (COUNTS) {
            Map<String, Integer> known = COUNTS.get(plan);
            if (known != null) {
                return known;
            }
        }
        Map<String, Integer> out = new java.util.TreeMap<>();
        of(plan).forEach((x, y, z, block) -> out.merge(block, 1, Integer::sum));
        Map<String, Integer> counts = Collections.unmodifiableMap(out);
        synchronized (COUNTS) {
            COUNTS.put(plan, counts);
        }
        return counts;
    }

    private static final Map<BuildPlan, Map<String, Integer>> COUNTS = new java.util.WeakHashMap<>();

    /**
     * Every cell that gives light once placed, with its level ({@link DarkInterior#emission}, so a
     * candle or lamp placed off gives none). Only what survives composition.
     */
    public List<int[]> lights() {
        if (lights == null) {
            List<int[]> out = new ArrayList<>();
            forEach((x, y, z, block) -> {
                int em = DarkInterior.emission(block);
                if (em > 0) {
                    out.add(new int[]{x, y, z, em});
                }
            });
            lights = Collections.unmodifiableList(out);
        }
        return lights;
    }

    /** Is a light that survives composition within {@code r} blocks on every axis of here? */
    public boolean lightWithin(int x, int y, int z, int r) {
        for (int[] l : lights()) {
            if (Math.abs(l[0] - x) <= r && Math.abs(l[1] - y) <= r && Math.abs(l[2] - z) <= r) {
                return true;
            }
        }
        return false;
    }

    // Same packing as BuildGeometry: offsets are bounded by MAX_OFFSET + MAX_DIM, so 20 bits each
    // with a bias is plenty and a whole building lives in one map.
    static long key(int x, int y, int z) {
        return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
    }

    static int kx(long k) {
        return (int) ((k >> 40) & 0xFFFFF) - 512;
    }

    static int ky(long k) {
        return (int) ((k >> 20) & 0xFFFFF) - 512;
    }

    static int kz(long k) {
        return (int) (k & 0xFFFFF) - 512;
    }
}
