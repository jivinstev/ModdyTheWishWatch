package com.moddingfromamod.wish.stress;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.moddingfromamod.builder.BuildFooting;
import com.moddingfromamod.builder.BuildGround;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.builder.Builder;
import com.moddingfromamod.wish.WishCorpus;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stresses {@code kind=ground}: <b>a building wished onto ground that is not flat</b> — on a
 * slope, over the ocean, aimed at a treetop, over lava, and the library treehouse on a hillside.
 * Each case carves its terrain around the anchor, puts the case's building up through the REAL
 * builder with the ground rule on ({@link BuildGround.Ground#settle}), and checks the ground rule:
 * <ul>
 *   <li>every column designed to touch the ground stands on something solid (the footing, the
 *   island, or the ground it was set down on);</li>
 *   <li>nothing was filled under a part designed to be up high;</li>
 *   <li>a set-down happened when, and only when, the case expects one;</li>
 *   <li>removing the building gives the terrain back exactly as it was carved — the sea, the
 *   lava, the hillside.</li>
 * </ul>
 * The carved box is put back to what it was before the case, so the next case on the grid sees the
 * world untouched.
 */
public final class GroundHarness implements StressHarness {

    /** How far around the building the terrain is carved. */
    static final int MARGIN = 6;
    /** How far below the anchor the carved terrain reaches. */
    static final int DEPTH = 16;

    @Override
    public String kind() {
        return "ground";
    }

    @Override
    public int settleTicks() {
        return 5;
    }

    @Override
    public Outcome run(Context ctx, WishCorpus.Case c) {
        Outcome.Builder out = new Outcome.Builder();
        JsonObject spec = c.spec();
        JsonObject build = spec.getAsJsonObject("build").deepCopy();
        String id = "stress_" + c.id();
        build.addProperty("id", id);
        BuildPlan plan = BuildPlan.fromJson(build);
        if (plan == null) {
            return out.problem("the case's building is not a readable plan").build();
        }
        ServerLevel level = ctx.level();
        // Lifted clear of the test grid's floor: on a superflat the grid sits a few blocks above
        // the bottom of the world, where a treetop's ground twelve blocks down cannot be placed.
        BlockPos a = ctx.anchor().above(DEPTH + 8);
        int[] b = bounds(plan);
        int x0 = a.getX() + b[0] - MARGIN, x1 = a.getX() + b[1] + MARGIN;
        int z0 = a.getZ() + b[4] - MARGIN, z1 = a.getZ() + b[5] + MARGIN;
        int y0 = a.getY() - DEPTH, y1 = a.getY() + Math.max(b[3], 0) + 2;
        Map<Long, BlockState> before = new HashMap<>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    before.put(p.asLong(), level.getBlockState(p));
                }
            }
        }
        String terrain = spec.get("terrain").getAsString();
        try {
            carve(level, terrain, a, x0, x1, y0, y1, z0, z1);
            Map<Long, BlockState> carved = new HashMap<>();
            for (long p : before.keySet()) {
                carved.put(p, level.getBlockState(BlockPos.of(p)));
            }
            BuildRegistry.put(level.getServer(), plan);
            String said = Builder.submit(level, a, plan, true, BuildGround.Ground.settle(), null);
            Builder.drainNow(level);
            Long at = BuildRegistry.anchorOf(id);
            if (at == null) {
                return out.problem("the building did not go up: " + said).build();
            }
            BlockPos landed = BlockPos.of(at);
            boolean setDown = landed.getY() < a.getY();
            JsonObject expect = c.expect();
            boolean wantDown = expect.has("setDown") && expect.get("setDown").getAsBoolean();
            out.require(Invariants.GROUND_SET_DOWN_WHEN_A_DROP, setDown == wantDown,
                    (wantDown ? "aimed over a long drop and NOT set down — it hangs in the air at y "
                            : "set down from y " + a.getY() + " to ") + landed.getY() + ": " + said);
            if (expect.has("says")) {
                String w = expect.get("says").getAsString();
                out.require(Invariants.GROUND_SET_DOWN_WHEN_A_DROP, said.contains(w),
                        "the agent's result does not say \"" + w + "\": " + said);
            }

            // Every column designed to touch the ground stands on something.
            List<int[]> footprint = BuildFooting.footprint(plan);
            int floating = 0;
            String first = null;
            for (int[] col : footprint) {
                BlockPos under = landed.offset(col[0], col[2] - 1, col[1]);
                BlockState s = level.getBlockState(under);
                if (s.isAir() || !s.getFluidState().isEmpty()) {
                    floating++;
                    if (first == null) {
                        first = (col[0]) + "," + (col[1]) + " (" + s + ")";
                    }
                }
            }
            out.require(Invariants.GROUND_PARTS_STAND_ON_SOMETHING, floating == 0,
                    floating + " of " + footprint.size() + " column(s) designed to touch the ground stand on "
                            + "air or water, first at " + first);

            // Nothing filled under parts designed to be up high.
            java.util.Set<String> ground = new java.util.HashSet<>();
            for (int[] col : footprint) {
                ground.add(col[0] + "," + col[1]);
            }
            int[] filledHigh = {0};
            Map<String, Integer> lowest = new HashMap<>();
            plan.forEachBlock((x, y, z, block) -> lowest.merge(x + "," + z, y, Math::min));
            for (Map.Entry<String, Integer> e : lowest.entrySet()) {
                if (ground.contains(e.getKey())) {
                    continue;
                }
                String[] xz = e.getKey().split(",");
                BlockPos under = landed.offset(Integer.parseInt(xz[0]), e.getValue() - 1, Integer.parseInt(xz[1]));
                if (!level.getBlockState(under).equals(carved.get(under.asLong()))
                        && !inPlan(plan, landed, under)) {
                    filledHigh[0]++;
                }
            }
            out.require(Invariants.GROUND_NOTHING_UNDER_HIGH_PARTS, filledHigh[0] == 0,
                    filledHigh[0] + " column(s) under parts designed to be up high were filled — a "
                            + "treehouse's platform is held up by its trunk, never by a pillar of dirt");

            // Removing it gives the terrain back — the sea where the island was, the hillside
            // where the footing was. (The blocks the building itself stood IN are air afterwards,
            // as they always have been: clearBuild clears a plan's own positions. Not this rule's.)
            java.util.Set<Long> own = new java.util.HashSet<>();
            plan.forEachBlock((x, y, z, blk) -> own.add(landed.offset(x, y, z).asLong()));
            Builder.clearBuild(level, landed, plan);
            int differ = 0;
            String where = null;
            for (Map.Entry<Long, BlockState> e : carved.entrySet()) {
                if (own.contains(e.getKey())) {
                    continue;
                }
                BlockState now = level.getBlockState(BlockPos.of(e.getKey()));
                if (!sameKind(now, e.getValue())) {
                    differ++;
                    if (where == null) {
                        where = BlockPos.of(e.getKey()).subtract(a).toShortString() + " is " + now
                                + ", was " + e.getValue();
                    }
                }
            }
            out.require(Invariants.P5_REMOVES_CLEAN, differ == 0,
                    differ + " block(s) of the terrain did not come back after removal, first at " + where);
            return out.build();
        } finally {
            Builder.clearBuild(level, BuildRegistry.anchorOf(id) == null ? a
                    : BlockPos.of(BuildRegistry.anchorOf(id)), plan);
            for (Map.Entry<Long, BlockState> e : before.entrySet()) {
                level.setBlock(BlockPos.of(e.getKey()), e.getValue(), Block.UPDATE_CLIENTS);
            }
        }
    }

    /** Water that flowed back as a flowing block is still the sea; everything else must match. */
    private static boolean sameKind(BlockState now, BlockState was) {
        if (now.equals(was)) {
            return true;
        }
        return now.getBlock() == was.getBlock() && !now.getFluidState().isEmpty();
    }

    private static boolean inPlan(BuildPlan plan, BlockPos at, BlockPos p) {
        boolean[] hit = {false};
        BlockPos rel = p.subtract(at);
        plan.forEachBlock((x, y, z, block) -> hit[0] |= x == rel.getX() && y == rel.getY() && z == rel.getZ());
        return hit[0];
    }

    /** {x0, x1, y0, y1, z0, z1} of the plan, relative to its anchor. */
    static int[] bounds(BuildPlan plan) {
        int[] b = {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MIN_VALUE};
        plan.forEachBlock((x, y, z, block) -> {
            b[0] = Math.min(b[0], x);
            b[1] = Math.max(b[1], x);
            b[2] = Math.min(b[2], y);
            b[3] = Math.max(b[3], y);
            b[4] = Math.min(b[4], z);
            b[5] = Math.max(b[5], z);
        });
        return b;
    }

    /**
     * The case's terrain, around the anchor {@code a} (where the build's floor goes):
     * <ul>
     *   <li>{@code slope}: grass falling one block per two east of the anchor;</li>
     *   <li>{@code ocean}: a gravel seabed 9 below, water up to the block under the anchor, a stone
     *   rim so it stays put;</li>
     *   <li>{@code treetop}: grass 12 below, a trunk up to the anchor and a canopy at its top;</li>
     *   <li>{@code lava}: a lava lake right under the anchor, on stone.</li>
     * </ul>
     */
    static void carve(ServerLevel level, String terrain, BlockPos a, int x0, int x1, int y0, int y1, int z0, int z1) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    level.setBlock(new BlockPos(x, y, z), air, Block.UPDATE_CLIENTS);
                }
                int top;
                BlockState surface = Blocks.GRASS_BLOCK.defaultBlockState();
                switch (terrain) {
                    case "slope" -> top = a.getY() - 1 - Math.max(0, x - a.getX()) / 2;
                    case "treetop" -> top = a.getY() - 12;
                    case "ocean", "lava" -> {
                        top = a.getY() - 10;
                        surface = Blocks.GRAVEL.defaultBlockState();
                    }
                    default -> top = a.getY() - 1;
                }
                for (int y = y0; y <= top; y++) {
                    level.setBlock(new BlockPos(x, y, z), y == top ? surface : Blocks.STONE.defaultBlockState(),
                            Block.UPDATE_CLIENTS);
                }
                boolean rim = x == x0 || x == x1 || z == z0 || z == z1;
                if (terrain.equals("ocean") || terrain.equals("lava")) {
                    BlockState fluid = terrain.equals("ocean") ? Blocks.WATER.defaultBlockState()
                            : Blocks.LAVA.defaultBlockState();
                    // Lava is a shallow lake: one block deep, on stone, right under the floor.
                    int from = terrain.equals("lava") ? a.getY() - 1 : top + 1;
                    if (terrain.equals("lava")) {
                        for (int y = top + 1; y < from; y++) {
                            level.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                        }
                    }
                    for (int y = from; y <= a.getY() - 1; y++) {
                        level.setBlock(new BlockPos(x, y, z), rim ? Blocks.STONE.defaultBlockState() : fluid,
                                Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        if (terrain.equals("treetop")) {
            int cx = (x0 + x1) / 2, cz = (z0 + z1) / 2;
            for (int y = a.getY() - 11; y <= a.getY() - 1; y++) {
                level.setBlock(new BlockPos(cx, y, cz), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
            for (int x = x0 + 2; x <= x1 - 2; x++) {
                for (int z = z0 + 2; z <= z1 - 2; z++) {
                    for (int y = a.getY() - 3; y <= a.getY() - 1; y++) {
                        if (x != cx || z != cz) {
                            level.setBlock(new BlockPos(x, y, z), leaves, Block.UPDATE_CLIENTS);
                        }
                    }
                }
            }
        }
    }
}
