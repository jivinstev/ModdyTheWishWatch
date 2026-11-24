package com.moddingfromamod.builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The Minecraft half of {@link BuildFooting}</b>: read what is under a building's
 * ground-touching columns, and carry out what the rule decides — a footing, an island, or a set-down
 * — as part of the build. {@link BuildFooting} decides; this only asks the world and executes.
 *
 * <p><b>The footing is part of the build.</b> Its blocks go up in the same job, first, under the same
 * dome; and every place that takes a building down ({@code remove_build}, an undo, an edit's
 * clear-before-rebuild) goes through {@link Builder#clearBuild}, which puts back what the footing
 * covered — the water an island displaced, the lava under an obsidian pad. Recorded per building id
 * beside its anchor, which is also in memory: where a building stands has never been on disk.
 */
public final class BuildGround {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-footing");

    private BuildGround() {
    }

    // ── how a placement asks ─────────────────────────────────────────────────────────────────

    /**
     * How a build is placed on the ground.
     *
     * @param floated the agent raised it on purpose ({@code offsetY} above zero, {@code conjure_at up}):
     *                it is left exactly where it was put
     * @param mayMove false for a rebuild where it already stands: filled under, never moved
     */
    public record Ground(boolean floated, boolean mayMove) {

        /** A new build, settled onto the ground where it was aimed. */
        public static Ground settle() {
            return new Ground(false, true);
        }

        /** Re-rendered where it already stands: footings as before, never moved. */
        public static Ground inPlace(String buildId) {
            return new Ground(wasFloated(buildId), false);
        }
    }

    /** What placing it decided, for the builder and the agent's tool result. */
    public record Placed(BlockPos anchor, BuildFooting.Decision decision, List<long[]> work,
                         List<BlockState> states, String forTheAgent) {

        public boolean moved() {
            return decision != null && decision.moved();
        }
    }

    // ── the world, as the rule reads it ──────────────────────────────────────────────────────

    /** {@link BuildFooting.World} over this level: loaded chunks only, leaves see-through. */
    public static BuildFooting.World world(ServerLevel level) {
        var chunks = level.getChunkSource();
        int bottom = com.moddingfromamod.compat.LevelHeights.bottom(level);
        return (x, z, fromY) -> {
            if (chunks.getChunkNow(x >> 4, z >> 4) == null) {
                return BuildFooting.Column.unloaded();
            }
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            boolean leaves = false;
            int stop = Math.max(bottom, fromY - BuildFooting.SCAN);
            for (int y = fromY - 1; y >= stop; y--) {
                m.set(x, y, z);
                BlockState s = level.getBlockState(m);
                if (s.is(BlockTags.LEAVES)) {
                    leaves = true;       // a wish goes down through a canopy, as WishAim's does
                    continue;
                }
                if (s.getFluidState().is(FluidTags.LAVA)) {
                    return mark(BuildFooting.Column.lava(y), leaves);
                }
                if (s.getFluidState().is(FluidTags.WATER) && s.getCollisionShape(level, m).isEmpty()) {
                    return mark(seabed(level, x, y, z, stop), leaves);
                }
                if (!s.getCollisionShape(level, m).isEmpty() && s.isFaceSturdy(level, m, Direction.UP)) {
                    return mark(BuildFooting.Column.ground(y, id(s)), leaves);
                }
                // air, grass, flowers, snow layers, a fence top: nothing to stand on yet
            }
            return mark(BuildFooting.Column.nothing(), leaves);
        };
    }

    /**
     * The rooms' natural blocks. The walk is the expensive part, so it runs only when something that
     * grows actually stands in the building's box at or above its floor, outside the plan's own cells and the
     * hillside cut — on bare ground, in a desert, on the sea or a flattened plot it never runs.
     */
    private static BuildClearing.Cut clearRooms(ServerLevel level, BuildPlan plan, BlockPos at, Set<Long> planRelative,
                                                BuildClearing.Cut cut) {
        BuildFraming.Bounds b = BuildFraming.bounds(plan);
        if (b == null) {
            return BuildClearing.Cut.NONE;
        }
        Set<String> already = new HashSet<>();
        for (int[] c : cut.cells()) {
            already.add(c[0] + "," + c[1] + "," + c[2]);
        }
        BuildClearing.World w = kinds(level);
        boolean any = false;
        for (int x = b.minX(); x <= b.maxX() && !any; x++) {
            for (int z = b.minZ(); z <= b.maxZ() && !any; z++) {
                for (int y = Math.max(0, b.minY()); y <= b.maxY(); y++) {
                    if (!planRelative.contains(ComposedPlan.key(x, y, z))
                            && w.at(at.getX() + x, at.getY() + y, at.getZ() + z) == BuildClearing.Kind.PLANT
                            && !already.contains((at.getX() + x) + "," + (at.getY() + y) + "," + (at.getZ() + z))) {
                        any = true;
                        break;
                    }
                }
            }
        }
        if (!any) {
            return BuildClearing.Cut.NONE;
        }
        WalkThrough walk = WalkThrough.plan(plan);
        return walk == null ? BuildClearing.Cut.NONE
                : BuildClearing.interiors(w, at.getX(), at.getY(), at.getZ(), walk.roomSpace(), planRelative, already);
    }

    /**
     * {@link BuildClearing.World} over this level: natural ground, what grows on it, or anything else.
     * An unloaded cell is {@link BuildClearing.Kind#OTHER}, so nothing is cut there.
     */
    public static BuildClearing.World kinds(ServerLevel level) {
        var chunks = level.getChunkSource();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        return (x, y, z) -> {
            if (chunks.getChunkNow(x >> 4, z >> 4) == null) {
                return BuildClearing.Kind.OTHER;
            }
            return kindOf(level.getBlockState(m.set(x, y, z)));
        };
    }

    /** What a block is to {@link BuildClearing}: never a fluid, never anything somebody built with. */
    public static BuildClearing.Kind kindOf(BlockState s) {
        if (s.isAir()) {
            return BuildClearing.Kind.AIR;
        }
        if (!s.getFluidState().isEmpty()) {
            return BuildClearing.Kind.OTHER;      // water, lava, a waterlogged anything: left alone
        }
        if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.FLOWERS) || s.is(net.minecraft.tags.BlockItemTags.SAPLINGS.block())
                || s.is(BlockTags.REPLACEABLE_BY_TREES) || s.is(BlockTags.CAVE_VINES)
                || s.is(Blocks.SNOW) || s.is(Blocks.MOSS_CARPET) || s.is(Blocks.SWEET_BERRY_BUSH)
                || s.is(Blocks.BROWN_MUSHROOM) || s.is(Blocks.RED_MUSHROOM) || s.is(Blocks.SUGAR_CANE)
                || s.is(Blocks.BAMBOO) || s.is(Blocks.CACTUS) || s.is(Blocks.BEE_NEST)
                || s.is(Blocks.MANGROVE_ROOTS) || s.is(Blocks.MUSHROOM_STEM)
                || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.RED_MUSHROOM_BLOCK)) {
            return BuildClearing.Kind.PLANT;
        }
        if (s.is(BlockTags.SUBSTRATE_OVERWORLD) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.SAND)
                || s.is(BlockTags.TERRACOTTA) || s.is(net.neoforged.neoforge.common.Tags.Blocks.ORES)
                || s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW)
                || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.DIRT_PATH)
                || s.is(Blocks.FARMLAND) || s.is(Blocks.PACKED_ICE) || s.is(Blocks.SUSPICIOUS_GRAVEL)) {
            return BuildClearing.Kind.GROUND;
        }
        return BuildClearing.Kind.OTHER;
    }

    private static BuildFooting.Column mark(BuildFooting.Column c, boolean leaves) {
        return leaves ? c.throughLeaves() : c;
    }

    /** A water column whose surface is {@code surface}: down to its seabed, within the scan. */
    private static BuildFooting.Column seabed(ServerLevel level, int x, int surface, int z, int stop) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = surface - 1; y >= stop; y--) {
            m.set(x, y, z);
            BlockState s = level.getBlockState(m);
            if (s.getFluidState().is(FluidTags.WATER) && s.getCollisionShape(level, m).isEmpty()) {
                continue;
            }
            if (s.isAir() && !s.getFluidState().isEmpty()) {
                continue;
            }
            return BuildFooting.Column.water(surface, y, id(s));
        }
        return BuildFooting.Column.water(surface, Integer.MIN_VALUE, "");
    }

    private static String id(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
    }

    // ── placing ──────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Decide where this build stands and what goes under it.</b> Pure arithmetic plus reads of
     * loaded chunks; nothing is placed — the returned work goes first in the build's own job.
     *
     * @param planWork the build's own blocks at {@code anchor} (packed position, palette index) — a
     *                 footing never fills a block the plan itself places, or removing the building
     *                 would find the plan's block there and leave the footing's hole behind
     */
    public static Placed place(ServerLevel level, BlockPos anchor, BuildPlan plan, Ground ground) {
        BuildFooting.Decision d = BuildFooting.decide(world(level), anchor.getX(), anchor.getY(), anchor.getZ(),
                BuildFooting.allAboveGround(plan) ? List.of() : BuildFooting.footprint(plan),
                ground.floated(), ground.mayMove());
        BlockPos at = new BlockPos(anchor.getX(), d.anchorY(), anchor.getZ());
        Set<Long> planCells = new HashSet<>();
        Set<Long> planRelative = new HashSet<>();
        plan.forEachBlock((x, y, z, block) -> {
            planCells.add(BlockPos.asLong(at.getX() + x, at.getY() + y, at.getZ() + z));
            planRelative.add(ComposedPlan.key(x, y, z));
        });
        List<long[]> work = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        // CUT INTO THE HILL: inside the footprint, the natural ground above the floor goes, as if the
        // building stood on flat ground — top first, before the footing and before the plan's own blocks.
        // -Ptestprops=hillcut=off is the A/B seam (the "before" arm 's films and measurement).
        boolean cutOff = "off".equalsIgnoreCase(System.getProperty(com.moddingfromamod.ModdingFromAModMod.MODID + ".hillcut"));
        BuildClearing.Cut cut = cutOff || ground.floated() || d.action() == BuildFooting.Action.UNLOADED
                || d.action() == BuildFooting.Action.FLOATED
                ? BuildClearing.Cut.NONE
                : BuildClearing.hillside(kinds(level), at.getX(), at.getY(), at.getZ(),
                        BuildFooting.allAboveGround(plan) ? List.of() : BuildFooting.footprint(plan), planRelative);
        if (!cut.isEmpty()) {
            states.add(Blocks.AIR.defaultBlockState());
            index.put("minecraft:air", 0);
            for (int[] c : cut.cells()) {
                work.add(new long[]{BlockPos.asLong(c[0], c[1], c[2]), 0});
            }
        }
        // CLEAR THE ROOMS: a tree standing where a room goes is cleared out of the room — natural
        // blocks only, room interiors only (WalkThrough.roomSpace), so a courtyard keeps its trees.
        BuildClearing.Cut rooms = cutOff || ground.floated() || d.action() == BuildFooting.Action.UNLOADED
                || "off".equalsIgnoreCase(System.getProperty(com.moddingfromamod.ModdingFromAModMod.MODID + ".roomclear"))
                ? BuildClearing.Cut.NONE
                : clearRooms(level, plan, at, planRelative, cut);
        if (!rooms.isEmpty()) {
            if (states.isEmpty()) {
                states.add(Blocks.AIR.defaultBlockState());
                index.put("minecraft:air", 0);
            }
            for (int[] c : rooms.cells()) {
                work.add(new long[]{BlockPos.asLong(c[0], c[1], c[2]), index.get("minecraft:air")});
            }
        }
        for (BuildFooting.Fill f : d.fills()) {
            long p = BlockPos.asLong(f.x(), f.y(), f.z());
            if (planCells.contains(p)) {
                continue;
            }
            Integer i = index.get(f.block());
            if (i == null) {
                i = states.size();
                index.put(f.block(), i);
                states.add(state(f.block()));
            }
            work.add(new long[]{p, i});
        }
        String agent = BuildFooting.forTheAgent(d, at.getX(), at.getY(), at.getZ(),
                List.of(BuildClearing.forTheAgent(cut), BuildClearing.roomsForTheAgent(rooms)));
        if (!rooms.isEmpty()) {
            LOG.info("[footing] '{}' at {} cleared {} natural block(s) out of its rooms", plan.id(),
                    at.toShortString(), rooms.cells().size());
        }
        if (!cut.isEmpty()) {
            LOG.info("[footing] '{}' at {} cut {} block(s) of hill out of {} column(s){}", plan.id(),
                    at.toShortString(), cut.cells().size(), cut.columns(),
                    cut.over() > 0 ? " (" + cut.over() + " rise above the cap)" : "");
        }
        if (d.action() != BuildFooting.Action.NONE) {
            LOG.info("[footing] '{}' at {} → {} at {}: {} fill(s), footing {} island {} lava {} hanging {}",
                    plan.id(), anchor.toShortString(), d.action(), at.toShortString(), work.size(),
                    d.footing(), d.island(), d.lava(), d.hanging());
        }
        return new Placed(at, d, work, states, agent);
    }

    private static BlockState state(String id) {
        try {
            var b = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id));
            if (b.isPresent()) {
                return b.get().defaultBlockState();
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return Blocks.DIRT.defaultBlockState();
    }

    /**
     * <b>The owner's condition: what moves, glides.</b> When the build was set down, the wish's spot
     * moves to where it really is through the SAME seam {@code conjure_at} and {@code SafePlacement}
     * use — {@code WishProgress.focusOn} locks the run's anchor, so the pillar GLIDES there (one glide,
     * never torn down: {@code WishSpotGameTest}) and the reveal and its camera frame the new spot —
     * and he is told where it went. A served wish (a button, a starter) has no run and its pillar is
     * already gone by the time its building goes up, so it is only told.
     *
     * @return what Moddy said, or null
     */
    public static String announce(ServerPlayer child, BlockPos from, Placed placed, String name) {
        if (placed == null || !placed.moved()) {
            return null;
        }
        String line = BuildFooting.told(name, placed.decision());
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        if (run != null) {
            com.moddingfromamod.wish.WishAimer.logMove(run.shortId(), from, placed.anchor(),
                    "set down " + placed.decision().dropped() + " onto the ground"
                            + (placed.decision().leaves() ? " (aimed at a treetop)" : ""));
            if (child != null) {
                com.moddingfromamod.wish.WishProgress.focusOn(child, placed.anchor());
            }
        }
        if (child != null && line != null) {
            com.moddingfromamod.wish.WishFeedback.moved(child, line);
        }
        return line;
    }

    // ── the record, and taking it away again ─────────────────────────────────────────────────

    /** One footing block: what was there, and what we put. */
    private record Laid(long pos, BlockState prior, BlockState placed) {
    }

    private record Record(ResourceKey<Level> dimension, List<Laid> laid) {
    }

    /** Footings by building id — per run, like the anchor they stand under. */
    private static final Map<String, Record> FOOTINGS = new HashMap<>();
    /** Builds placed deliberately floated, so a rebuild in place floats them again. */
    private static final Set<String> FLOATED = new HashSet<>();

    private static String key(String id) {
        return id == null ? "" : id.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Remember what this build's footing covers, read NOW — before the job places anything. A
     * building that already had a footing (an edit re-rendered in place) has had it taken away by
     * {@link #release} first, so what is read here is the world, not the old footing.
     */
    public static synchronized void remember(ServerLevel level, String buildId, Placed placed, Ground ground) {
        String k = key(buildId);
        if (ground.floated()) {
            FLOATED.add(k);
        } else {
            FLOATED.remove(k);
        }
        if (placed.work().isEmpty()) {
            FOOTINGS.remove(k);
            return;
        }
        List<Laid> laid = new ArrayList<>(placed.work().size());
        for (long[] w : placed.work()) {
            laid.add(new Laid(w[0], level.getBlockState(BlockPos.of(w[0])), placed.states().get((int) w[1])));
        }
        FOOTINGS.put(k, new Record(level.dimension(), laid));
    }

    /** Was this build placed floated on purpose? */
    public static synchronized boolean wasFloated(String buildId) {
        return FLOATED.contains(key(buildId));
    }

    /** How many footing blocks this building has. */
    public static synchronized int footingOf(String buildId) {
        Record r = FOOTINGS.get(key(buildId));
        return r == null ? 0 : r.laid().size();
    }

    /**
     * <b>Take a building's footing away</b>: every block still the one we put goes back to what it
     * was — the sea, the lava, the air on the hillside. A block he has since changed is his, and
     * stays. Returns how many.
     */
    public static int release(ServerLevel level, String buildId) {
        Record r;
        synchronized (BuildGround.class) {
            r = FOOTINGS.remove(key(buildId));
        }
        if (r == null || level == null) {
            return 0;
        }
        ServerLevel in = level.dimension().equals(r.dimension()) ? level
                : level.getServer().getLevel(r.dimension());
        if (in == null) {
            return 0;
        }
        int back = 0;
        // Top first, so nothing a footing held up is left standing on a hole for a tick.
        for (int i = r.laid().size() - 1; i >= 0; i--) {
            Laid l = r.laid().get(i);
            BlockPos p = BlockPos.of(l.pos());
            if (in.getBlockState(p) == l.placed()) {
                in.setBlock(p, l.prior(), Block.UPDATE_ALL);
                back++;
            }
        }
        if (back > 0) {
            LOG.info("[footing] took {} footing block(s) of '{}' away", back, buildId);
        }
        return back;
    }

    /** Forget every record — a world unload. */
    public static synchronized void clear() {
        FOOTINGS.clear();
        FLOATED.clear();
    }
}
