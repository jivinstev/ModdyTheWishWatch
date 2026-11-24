package com.moddingfromamod.builder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Places a {@link BuildPlan} into the world <b>a slice at a time</b>.
 *
 * <p>The whole reason this class exists rather than a loop: a monument-scale castle is on the order
 * of half a million block placements. Doing that in one tick stalls the server thread for seconds
 * — the game simply freezes — and the block-update and heap churn can OOM a modest allocation. So
 * the plan is flattened once, then drained at a bounded rate, and a big castle rises over a couple
 * of seconds instead of hanging the game.
 *
 * <p>Jobs are transient and in memory. A server stop mid-build just leaves a partial building,
 * which is re-runnable by id — deliberately simpler than persisting a half-finished job, and the
 * failure mode is a building with a missing wall rather than a corrupt save.
 *
 * <p>Registered on {@code NeoForge.EVENT_BUS} (gameplay), not the mod bus.
 */
public final class Builder {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /**
     * Blocks placed per tick.
     *
     * <p>Sized to be invisible to the player rather than to finish fast: at 20 ticks/second this is
     * ~160k blocks a second, so even a maximum-size plan completes in about a second and a half
     * while leaving the server thread most of its budget for everything else.
     */
    public static final int BLOCKS_PER_TICK = 8_000;

    /** Bounded so a mashing child can't queue a hundred castles. */
    private static final int MAX_QUEUED_JOBS = 8;

    private static final Deque<Job> JOBS = new ArrayDeque<>();

    private Builder() {
    }

    /** One queued build: the positions to place, and where it is up to. */
    private static final class Job {
        final ServerLevel level;
        final String name;
        final List<long[]> work;   // packed [BlockPos.asLong, blockStateIndex]
        final List<BlockState> palette;
        final BlockPos anchor;
        /**
         * Which wish queued it, so a stop can take THIS building down without touching the one
         * another wish is putting up beside it. Null for a build that came from somewhere other
         * than a wish (a command, a test).
         */
        final java.util.UUID wishId;
        /** Which building this is: what a spot on it waits for. */
        final String buildId;
        /** Run once it is finished — a creature taking its seat on one of its spots. */
        final List<Runnable> then = new ArrayList<>();
        /** Cells it has written: a second write over its own block is not the ground it was built on. */
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet wrote = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        int index;
        /**
         * Ticks to wait before the first block: a build set down somewhere else waits for
         * its pillar to glide there, then {@link #onStart} raises its dome.
         */
        int waitTicks;
        Runnable onStart;

        Job(ServerLevel level, String buildId, String name, List<long[]> work, List<BlockState> palette,
            BlockPos anchor, java.util.UUID wishId) {
            this.level = level;
            this.buildId = buildId;
            this.name = name;
            this.work = work;
            this.palette = palette;
            this.anchor = anchor;
            this.wishId = wishId;
        }
    }

    /**
     * Queue a build. Returns a human-readable result for the agent.
     *
     * <p>Flattening happens here, once, on the server thread — it is pure arithmetic over the plan
     * and is cheap next to the placement itself.
     */
    public static String submit(ServerLevel level, BlockPos anchor, BuildPlan plan) {
        return submit(level, anchor, plan, true);
    }

    /**
     * @param rememberAnchor false for a COPY of a building somebody else owns (the
     *        {@code /moddy try}, via {@code /wishbuild}): its spot is the owner's, and taking it
     *        over would point the owner's "take me there" and undo at the copy
     */
    public static String submit(ServerLevel level, BlockPos anchor, BuildPlan plan,
                                boolean rememberAnchor) {
        return submit(level, anchor, plan, rememberAnchor, null, null);
    }

    /**
     * @param ground how it meets the ground: a footing, an island or a set-down
     *        ({@link BuildGround}), or null to place it exactly where it was put, as before — a
     *        test fixture, a picture of a building, a stress case that owns its own terrain
     * @param tell   the child to tell when it was set down somewhere else, when the wish has no run
     *        to find him by (a served wish); null to ask the run
     */
    public static String submit(ServerLevel level, BlockPos anchor, BuildPlan plan,
                                boolean rememberAnchor, BuildGround.Ground ground,
                                net.minecraft.server.level.ServerPlayer tell) {
        if (level == null || anchor == null || plan == null || !plan.isValid()) {
            return "That build plan is empty — give it an id and at least one shape.";
        }
        // NEVER A COMMAND BLOCK, STRUCTURE BLOCK OR JIGSAW — build_structure and a replayed wishbuild alike.
        for (String id : plan.blockIds()) {
            if (com.moddingfromamod.wish.ForbiddenBlocks.isForbiddenId(id)) {
                return "This build's palette has " + id + ". "
                        + com.moddingfromamod.wish.ForbiddenBlocks.refusal(id);
            }
        }
        if (!plan.fitsBudget()) {
            // named the offending shape at the agent's own tool; this is the OTHER door to
            // the same refusal, and it was still offering the generic advice that sent the agent
            // round eight blind edits.
            return "That build is too big (" + plan.exactVolume() + " blocks, limit "
                    + BuildPlan.MAX_BLOCKS + "). Make it smaller or use fewer/hollow shapes."
                    + plan.budgetBreakdown();
        }
        if (!plan.fitsTntBudget()) {
            return plan.tntRefusal();      // — the other door to the same refusal
        }
        if (JOBS.size() >= MAX_QUEUED_JOBS) {
            return "Too many buildings are already going up — wait for those to finish.";
        }

        // Resolve every block id ONCE, up front. A typo'd block must fail loudly here rather than
        // silently becoming air and leaving a building full of holes.
        List<BlockState> palette = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        List<String> stateless = new ArrayList<>();
        for (String id : plan.blockIds()) {
            BlockState state = resolve(level, id);
            if (state == null && id.indexOf('[') > 0) {
                // The block is real and its state is not (a typo'd property): place it plain, and
                // say so, rather than turning a ladder into a column of stone.
                state = resolve(level, id.substring(0, id.indexOf('[')));
                if (state != null) {
                    stateless.add(id);
                }
            }
            if (state == null) {
                unknown.add(id);
            }
            palette.add(state == null ? Blocks.STONE.defaultBlockState() : state);
        }
        List<String> ids = plan.blockIds();

        // WHAT IT STANDS ON: a footing down a slope, an island in the sea, or set down off a
        // treetop onto the ground below — decided before a block is placed, because a set-down
        // changes the anchor everything below is measured from. Its blocks go FIRST in the same
        // job, so they rise under the same dome and the building never stands on air for a tick.
        BlockPos aimed = anchor;
        BuildGround.Placed placed = ground == null ? null : BuildGround.place(level, anchor, plan, ground);
        if (placed != null) {
            anchor = placed.anchor();
        }
        final BlockPos at = anchor;
        List<long[]> work = new ArrayList<>(Math.min(plan.volume(), BuildPlan.MAX_BLOCKS));
        int footingBlocks = 0;
        if (placed != null && !placed.work().isEmpty()) {
            int base = palette.size();
            palette.addAll(placed.states());
            for (long[] f : placed.work()) {
                work.add(new long[]{f[0], base + f[1]});
            }
            footingBlocks = placed.work().size();
        }
        plan.forEachBlock((x, y, z, block) -> {
            int paletteIndex = ids.indexOf(block);
            work.add(new long[]{
                    BlockPos.asLong(at.getX() + x, at.getY() + y, at.getZ() + z),
                    paletteIndex < 0 ? 0 : paletteIndex});
        });
        // DOORS A CHILD CAN OPEN: placed LAST, over the plan's own blocks — proper halves, wood for
        // iron, a door in a ground-floor doorway. A pure rewrite of the plan, never saved into it, so an edit
        // re-renders it and clearBuild takes exactly these cells down again.
        BuildDoors.Fixed doors = BuildDoors.of(plan);
        for (java.util.Map.Entry<Long, String> e : doors.cells().entrySet()) {
            BlockState state = resolve(level, e.getValue());
            if (state == null) {
                continue;
            }
            palette.add(state);
            long k = e.getKey();
            work.add(new long[]{BlockPos.asLong(at.getX() + ComposedPlan.kx(k), at.getY() + ComposedPlan.ky(k),
                    at.getZ() + ComposedPlan.kz(k)), palette.size() - 1});
        }

        // FENCES, BARS AND PANES JOIN UP: placed with their arms, since nothing updates a neighbour here. The same
        // cells the plan draws, so clearBuild already takes them down.
        for (java.util.Map.Entry<Long, String> e : BuildConnect.of(plan).entrySet()) {
            BlockState state = resolve(level, e.getValue());
            if (state == null) {
                continue;
            }
            palette.add(state);
            long k = e.getKey();
            work.add(new long[]{BlockPos.asLong(at.getX() + ComposedPlan.kx(k), at.getY() + ComposedPlan.ky(k),
                    at.getZ() + ComposedPlan.kz(k)), palette.size() - 1});
        }

        // A WAY IN FROM THE WATER IS LEVEL WITH IT: a shaft's rim set to the waterline. The same kind of rewrite.
        for (java.util.Map.Entry<Long, String> e : BuildWaterEntry.fix(plan).cells().entrySet()) {
            BlockState state = resolve(level, e.getValue());
            if (state == null) {
                continue;
            }
            palette.add(state);
            long k = e.getKey();
            work.add(new long[]{BlockPos.asLong(at.getX() + ComposedPlan.kx(k), at.getY() + ComposedPlan.ky(k),
                    at.getZ() + ComposedPlan.kz(k)), palette.size() - 1});
        }

        // NOTHING FLOATS: a part standing on nothing is grounded as it goes up — a lamp hung from the block
        // above, a stem or post carried down, a lump set down a block or two. A pure rewrite like the doors', placed
        // after them and never saved into the plan, so an edit re-renders it and clearBuild takes exactly it down.
        for (java.util.Map.Entry<Long, String> e : BuildGrounding.fix(plan).cells().entrySet()) {
            BlockState state = resolve(level, e.getValue());
            if (state == null) {
                continue;
            }
            palette.add(state);
            long k = e.getKey();
            work.add(new long[]{BlockPos.asLong(at.getX() + ComposedPlan.kx(k), at.getY() + ComposedPlan.ky(k),
                    at.getZ() + ComposedPlan.kz(k)), palette.size() - 1});
        }

        // ONE-BLOCK FAULTS IN ITS WAYS IN ARE PUT RIGHT: a doorway cut a block too low or too short, a ladder a
        // rung short of its floor. Kept only where the walk says a room became reachable and none was lost. Last, so
        // nothing above fills what it opened; never saved into the plan.
        BuildRepair.Fixed repaired = BuildRepair.of(plan);
        for (java.util.Map.Entry<Long, String> e : repaired.cells().entrySet()) {
            BlockState state = resolve(level, e.getValue() == null ? "minecraft:air" : e.getValue());
            if (state == null) {
                continue;
            }
            palette.add(state);
            long k = e.getKey();
            work.add(new long[]{BlockPos.asLong(at.getX() + ComposedPlan.kx(k), at.getY() + ComposedPlan.ky(k),
                    at.getZ() + ComposedPlan.kz(k)), palette.size() - 1});
        }

        var owner = com.moddingfromamod.agent.AgentContext.currentRun();
        // NOTHING IS BURIED: before a block is placed, a creature whose box the build's
        // SOLID blocks would fill is moved the shortest safe distance — judged against the world
        // as it will be, so that can be into a room of the new building or onto its roof. One
        // standing where the build has air stays: "a castle for my Spikor" keeps Spikor inside.
        // The oasis wish's 2,484 blocks landed on a Dragon Shark, which suffocated.
        net.minecraft.server.level.ServerPlayer wisher = owner == null || owner.playerId() == null
                ? null : level.getServer().getPlayerList().getPlayer(owner.playerId());
        com.moddingfromamod.vanilla.SafePlacement.clearForBuild(level, work, palette, wisher);
        Job job = new Job(level, plan.id(), plan.name(), work, palette, anchor,
                owner == null ? null : owner.wishId());
        JOBS.addLast(job);
        BUILDING.merge(plan.id(), 1, Integer::sum);
        // SET DOWN SOMEWHERE ELSE: the wish's spot moves first — the pillar he has been
        // watching GLIDES to it (the condition: the same seam as conjure_at and
        // SafePlacement, one glide, never a rebuild) and he is told where it went. Before the dome,
        // because the dome takes the pillar's place: started now it would swallow the pillar
        // where it stood, and he would see it vanish up a tree and a building appear on the
        // ground. So a moved build WAITS for its pillar to arrive, and then rises there.
        String said = placed == null ? null
                : BuildGround.announce(tell != null ? tell : wisher, aimed, placed, plan.name());
        // A served wish has no run, but its building gets the same dome: its id is in
        // force while its own commands run (WishProgress.withServedSpot).
        java.util.UUID domeWish = owner != null ? owner.wishId()
                : com.moddingfromamod.wish.WishProgress.servedWish();
        if (placed != null && placed.moved() && domeWish != null
                && com.moddingfromamod.wish.reveal.WishOrbs.shown(domeWish)) {
            job.waitTicks = com.moddingfromamod.wish.reveal.OrbShape.glideTicks(
                    Math.sqrt(aimed.distSqr(anchor))) + ARRIVE_TICKS;
        }
        if (domeWish != null && work.size() > footingBlocks) {
            // A wish's building goes up under a dome he watches, and the dome shatters when
            // it is finished — rather than a half-wall appearing a layer at a time. Sized to the
            // BUILDING: an island's base twenty blocks down the seabed is not what he watches rise.
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
            int z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
            for (long[] e : work.subList(footingBlocks, work.size())) {
                BlockPos p = BlockPos.of(e[0]);
                x0 = Math.min(x0, p.getX()); x1 = Math.max(x1, p.getX());
                y0 = Math.min(y0, p.getY()); y1 = Math.max(y1, p.getY());
                z0 = Math.min(z0, p.getZ()); z1 = Math.max(z1, p.getZ());
            }
            final double cx = (x0 + x1 + 1) / 2.0, cz = (z0 + z1 + 1) / 2.0;
            final double hx = (x1 - x0 + 1) / 2.0, hz = (z1 - z0 + 1) / 2.0;
            final int fy = y0, height = y1 - y0 + 1;
            final boolean bubble = footingBlocks == 0 && floating(level, x0, x1, y0, z0, z1);
            Runnable dome = () -> com.moddingfromamod.wish.reveal.Reveals.domeFor(job, level, domeWish,
                    cx, fy, cz, hx, hz, height, bubble);
            if (job.waitTicks > 0) {
                job.onStart = dome;
            } else {
                dome.run();
            }
        }
        if (owner != null) {
            // So an undo can take it back down, and so a stop knows what to stop.
            owner.made(com.moddingfromamod.marshal.MadeKind.BUILD, plan.id());
        }
        // So a later edit of this same building can re-render it HERE rather than
        // putting a second one wherever the child happens to be standing — and in THIS world, so
        // removing it later clears the right one.
        if (rememberAnchor) {
            if (ground != null) {
                // Read what the footing covers NOW, before the job places it, so taking the
                // building away gives the sea back (BuildGround.release, from clearBuild).
                BuildGround.remember(level, plan.id(), placed, ground);
            }
            // Which wall faces him: the default FRONT spot when the building has no door.
            net.minecraft.world.entity.Entity facing = wisher != null ? wisher
                    : level.getNearestPlayer(anchor.getX(), anchor.getY(), anchor.getZ(), 64, false);
            int[] toward = facing == null ? null : new int[]{
                    (int) Math.floor(facing.getX()) - anchor.getX(),
                    (int) Math.floor(facing.getZ()) - anchor.getZ()};
            // SAVED, not just remembered: every restart used to forget where it stood.
            BuildRegistry.placed(level.getServer(), plan.id(), anchor.asLong(), level.dimension(), toward);
        }
        String note = (unknown.isEmpty() ? ""
                : " (I didn't recognise " + String.join(", ", unknown) + " — used stone instead.)")
                + (stateless.isEmpty() ? ""
                : " (I didn't understand the [state] of " + String.join(", ", stateless)
                        + " — placed the plain block instead.)");
        return "Building '" + plan.name() + "': " + work.size() + " blocks going up over the next "
                + Math.max(1, work.size() / BLOCKS_PER_TICK) + " tick(s)." + note
                + (placed == null ? "" : placed.forTheAgent())
                + (said == null ? "" : " He was told: \"" + said + "\"")
                + BuildDoors.note(doors) + BuildRepair.note(repaired);
    }

    /**
     * Is this build NOT standing on the ground — a sky castle, an upside-down wish? Sampled
     * on a grid under its lowest layer; more air than ground there and the reveal closes into a
     * bubble instead of a dome, so its open bottom does not show the building going up.
     */
    public static boolean floating(ServerLevel level, int x0, int x1, int y0, int z0, int z1) {
        int air = 0, n = 0;
        int sx = Math.max(1, (x1 - x0) / 4), sz = Math.max(1, (z1 - z0) / 4);
        for (int x = x0; x <= x1; x += sx) {
            for (int z = z0; z <= z1; z += sz) {
                n++;
                if (level.getBlockState(new BlockPos(x, y0 - 1, z)).canBeReplaced()) {
                    air++;
                }
            }
        }
        return n > 0 && air * 2 > n;
    }

    /** True while anything is still going up (used by tests and by {@code /moddy status}). */
    public static boolean isBusy() {
        return !JOBS.isEmpty();
    }

    public static int queuedJobs() {
        return JOBS.size();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        drain(BLOCKS_PER_TICK);
    }

    /**
     * Place every queued block right now, ignoring the per-tick budget.
     *
     * <p><b>A forced flush, for tests.</b> The stress harness runs many cases inside one server
     * tick and cannot wait for the queue to drain on its own; without this it would have to place
     * blocks itself, which would test a copy of the builder rather than the builder — the exact
     * "a test that bypasses the real path tests nothing" mistake this repo has already paid for
     * once.
     *
     * <p>Deliberately shares its body with {@link #onServerTick} so the two cannot diverge: the
     * only difference is the budget. Not for gameplay use — the budget exists because a 550k-block
     * monument placed in one tick stalls the server for seconds.
     */
    public static void drainNow(net.minecraft.server.level.ServerLevel level) {
        drain(Integer.MAX_VALUE);
    }

    /** How long a set-down build's pillar stands at its new spot before the dome takes over. */
    static final int ARRIVE_TICKS = 10;

    private static void drain(int startingBudget) {
        if (JOBS.isEmpty()) {
            return;
        }
        boolean forced = startingBudget == Integer.MAX_VALUE;
        int budget = startingBudget;
        while (budget > 0 && !JOBS.isEmpty()) {
            Job job = JOBS.peekFirst();
            if (job.onStart != null) {
                // A set-down's pillar is still gliding: nothing goes up until it arrives.
                // A forced flush (a test) does not wait.
                if (!forced && job.waitTicks-- > 0) {
                    return;
                }
                Runnable start = job.onStart;
                job.onStart = null;
                start.run();
            }
            while (budget > 0 && job.index < job.work.size()) {
                long[] entry = job.work.get(job.index++);
                BlockPos pos = BlockPos.of(entry[0]);
                // UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE: no neighbour updates (a wall of sand doesn't
                // avalanche, water doesn't flow while the building is still half-placed) AND no shape
                // update, so a support-requiring block the design names — a torch, ladder, door half,
                // banner — placed before the block it leans on isn't popped by a survival check.
                BlockState put = job.palette.get((int) entry[1]);
                // what it is built ON, so taking it down gives it back (air needs no record).
                if (job.wrote.add(entry[0])) {
                    BuildGroundJournal.record(job.level, job.buildId, entry[0], job.level.getBlockState(pos), put);
                } else {
                    BuildGroundJournal.rewrote(job.level, job.buildId, entry[0], put);
                }
                job.level.setBlock(pos, put, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                budget--;
            }
            if (job.index >= job.work.size()) {
                JOBS.pollFirst();
                done(job);
                LOGGER.info("finished building '{}' ({} blocks)", job.name, job.work.size());
                // The payoff. After a long wait of gathering sparkles, the thing arriving should
                // LAND -- a flash and a chime, so the moment reads as magic rather than as blocks
                // quietly appearing while he looks the other way.
                for (var p : job.level.getServer().getPlayerList().getPlayers()) {
                    com.moddingfromamod.wish.WishProgress.landed(p, job.anchor);
                }
                com.moddingfromamod.wish.reveal.Reveals.shatter(job);
                // what was waiting for it — a boss taking his seat on the throne, now that
                // there is a throne.
                for (Runnable r : job.then) {
                    try {
                        r.run();
                    } catch (RuntimeException e) {
                        LOGGER.warn("after building '{}': {}", job.name, e.toString());
                    }
                }
            }
        }
    }

    /** Builds by id with a job still going up — read off the server thread too. */
    private static final java.util.Map<String, Integer> BUILDING =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static void done(Job job) {
        BUILDING.computeIfPresent(job.buildId, (k, n) -> n <= 1 ? null : n - 1);
    }

    /** Is this building still going up? Safe from any thread. */
    public static boolean isBuilding(String buildId) {
        return buildId != null && BUILDING.containsKey(buildId.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * <b>Run {@code then} once this building is finished</b> — right now when it is not
     * going up. A creature seated on a spot must arrive AFTER the blocks it sits on: before, the
     * throne is air and the boss falls, or the walls land on him. Server thread only. A build that
     * is stopped or cleared drops what was waiting for it: a cancelled wish seats nobody.
     *
     * @return true when it was deferred
     */
    public static boolean whenBuilt(String buildId, Runnable then) {
        String id = buildId == null ? "" : buildId.trim().toLowerCase(java.util.Locale.ROOT);
        Job last = null;
        for (Job j : JOBS) {
            if (j.buildId.equals(id)) {
                last = j;
            }
        }
        if (last == null) {
            then.run();
            return false;
        }
        last.then.add(then);
        return true;
    }

    /** Don't let a half-built castle straddle a world unload. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        clear();
    }

    public static void clear() {
        for (Job j : JOBS) {
            com.moddingfromamod.wish.reveal.Reveals.dropDome(j);
        }
        JOBS.clear();
        BUILDING.clear();
        BuildGround.clear();
    }

    /**
     * Stop the blocks for one wish, mid-flight.
     *
     * <p><b>A stop that leaves a castle still going up is not a stop.</b> The bar and beam vanish
     * within a tick server-side, so if the placement kept draining the child would watch a building
     * he just cancelled continue to appear — which reads as the button not working, and is exactly
     * the "does a stop actually stop?" failure this is here to prevent.
     *
     * @return how many queued jobs were dropped
     */
    public static int cancelJobsFor(java.util.UUID wishId) {
        if (wishId == null) {
            return 0;
        }
        int before = JOBS.size();
        JOBS.removeIf(j -> {
            if (!wishId.equals(j.wishId)) {
                return false;
            }
            com.moddingfromamod.wish.reveal.Reveals.dropDome(j);   // a stopped build: no shatter
            done(j);
            return true;
        });
        return before - JOBS.size();
    }

    /** How many jobs belong to one wish — for tests and diagnostics. */
    public static int queuedJobsFor(java.util.UUID wishId) {
        int n = 0;
        for (Job j : JOBS) {
            if (wishId != null && wishId.equals(j.wishId)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Where a building goes: in front of the child, not on top of them.
     *
     * <p>An agent describes shape offsets from zero, so without this the first shape lands inside
     * the player and entombs them in their own castle.
     */
    /**
     * Air-clear exactly the blocks a {@link BuildPlan} places at {@code anchor} — so a building the
     * child rejected can be taken back down. Only the plan's own positions are touched (never terrain,
     * and never a block the child added on top that isn't part of the plan), the same non-destructive
     * rule the train footprint clear uses. Powers remove_build.
     *
     * @return how many blocks were cleared
     */
    public static int clearBuild(net.minecraft.server.level.ServerLevel level, BlockPos anchor,
                                 BuildPlan plan) {
        int[] cleared = {0};
        // each cell gives back what the building was built on, where the building's ground journal holds it and
        // the cell is still what we wrote; a cell he changed since is left as he left it; one with no record (it was
        // air, or the building predates the journal) comes down to air, as before. Each cell is taken down ONCE: a
        // door or a repair can share a position with the plan, and a second pass would clear what the first gave back.
        java.util.Set<Long> seen = new java.util.HashSet<>();
        java.util.function.Consumer<BlockPos> down = at -> {
            if (!seen.add(at.asLong())) {
                return;
            }
            BuildGroundJournal.Answer a = BuildGroundJournal.takeBack(level, plan.id(), at.asLong(), level.getBlockState(at));
            switch (a.what()) {
                // Restored with every update, so a lava or water source runs again and a torch finds its wall.
                case RESTORE -> level.setBlockAndUpdate(at, a.state());
                case CLEAR -> level.setBlock(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                case LEAVE -> {
                    return;
                }
            }
            cleared[0]++;
        };
        plan.forEachBlock((x, y, z, block) -> down.accept(anchor.offset(x, y, z)));
        // The doors BuildDoors hung as it went up: the same rewrite of the same plan, so exactly
        // the cells it added — a door in a doorway the plan left empty is not a plan position.
        for (long k : BuildDoors.of(plan).cells().keySet()) {
            down.accept(anchor.offset(ComposedPlan.kx(k), ComposedPlan.ky(k), ComposedPlan.kz(k)));
        }
        for (long k : BuildWaterEntry.fix(plan).cells().keySet()) {
            down.accept(anchor.offset(ComposedPlan.kx(k), ComposedPlan.ky(k), ComposedPlan.kz(k)));
        }
        // And what the grounding fix added: a chain, a stem carried down, a lump set lower.
        for (long k : BuildGrounding.fix(plan).cells().keySet()) {
            down.accept(anchor.offset(ComposedPlan.kx(k), ComposedPlan.ky(k), ComposedPlan.kz(k)));
        }
        // And a rung the repair added; what it opened is air already.
        for (long k : BuildRepair.of(plan).cells().keySet()) {
            down.accept(anchor.offset(ComposedPlan.kx(k), ComposedPlan.ky(k), ComposedPlan.kz(k)));
        }
        // Its footing or island goes with it: the sea comes back where the island was.
        cleared[0] += BuildGround.release(level, plan.id());
        return cleared[0];
    }

    public static BlockPos anchorInFrontOf(net.minecraft.server.level.ServerPlayer player,
                                           int forward, int offsetY) {
        double rad = Math.toRadians(player.getYRot());
        int dx = (int) Math.round(-Math.sin(rad) * forward);
        int dz = (int) Math.round(Math.cos(rad) * forward);
        return player.blockPosition().offset(dx, offsetY, dz);
    }

    /**
     * Snap an anchor DOWN to solid ground. A train needs its track on a surface, so it must never lay
     * at a flying player's Y or float in the sky (which is exactly how a child on an airplane got
     * McTrains scattered at y=177). Scans down from {@code pos} for the first block that can bear
     * weight and returns the spot on top of it; if nothing solid is found, falls back to the world
     * surface at (x,z). A platform the child built is respected — the scan stops on it — so only a
     * genuinely open-air anchor is pulled to the ground.
     */
    public static BlockPos groundAnchor(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos m = pos.mutable();
        for (int y = pos.getY(); y > com.moddingfromamod.compat.LevelHeights.bottom(level); y--) {
            m.set(pos.getX(), y - 1, pos.getZ());
            net.minecraft.world.level.block.state.BlockState below = level.getBlockState(m);
            if (below.getFluidState().isEmpty()
                    && below.isFaceSturdy(level, m, net.minecraft.core.Direction.UP)) {
                return new BlockPos(pos.getX(), y, pos.getZ());
            }
        }
        int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                pos.getX(), pos.getZ());
        return new BlockPos(pos.getX(), surface, pos.getZ());
    }

    /**
     * Where a build should anchor. The <b>locked manifest spot</b> (where the watch aimed / conjure_at
     * decided) wins — so the thing lands where it was promised, not wherever the child has since walked
     * to. Returns null only when there's neither a lock nor a player (the caller then falls back to
     * world spawn).
     *
     * <p><b>An offset never throws the aim away.</b> This used to read "explicit offsets win",
     * and {@code offsetForward: 0} on a held-out grant put a building up ON the child, 11 blocks from
     * where he aimed; the claim check then looked at the aimed spot, found no ladders, and the agent
     * "fixed" ladders that had never been missing. So with a locked spot {@code forward} is ignored
     * (the caller says so — {@link #ignoresForward}) and {@code offsetY} raises or lowers the build
     * FROM the locked spot, which keeps {@code offsetY > 0} meaning "floated on purpose".
     * {@code forward} only places a build in front of the child when nothing was aimed.
     */
    public static BlockPos chooseAnchor(net.minecraft.server.level.ServerPlayer player,
                                        BlockPos locked, int forward, int offsetY) {
        if (locked != null) {
            return locked.above(offsetY);
        }
        return player != null ? anchorInFrontOf(player, forward, offsetY) : null;
    }

    /**
     * True when the agent asked for {@code offsetForward} and {@link #chooseAnchor} will not honour
     * it, because there is an aimed spot. The tool result says so, in these words, so the agent does
     * not go looking for its building where it asked rather than where it stands.
     */
    public static boolean ignoresForward(BlockPos locked, boolean askedForward) {
        return locked != null && askedForward;
    }

    /** What a tool result says when {@link #ignoresForward} — one sentence, shared by every tool. */
    public static final String FORWARD_IGNORED = " (Built where he aimed: offsetForward only applies "
            + "when there is no aimed spot, so it was ignored. offsetY still raises it from that spot.)";

    /**
     * <b>Where a build goes when there is nobody to put it in front of.</b>
     *
     * <p>Every one of the three build tools had the same line — {@code level.getSharedSpawnPos()
     * .offset(forward, offsetY, 0)} — so with no player and no locked spot, <b>every playerless
     * build in the world landed on one square</b>. Buildings, trains and machines alike, each one
     * on top of the last.
     *
     * <p>Found as a red Gate B: two contraption tests both built at {@code 6, 60, 0} and the one
     * that asserted first read the other one's blocks — <i>"the chute should be at (0,2,0) and
     * instead that square holds create:water_wheel"</i>. It always collided; whether the test passed
     * depended only on which build landed inside its assertion window, so adding any unrelated test
     * anywhere could turn the gate red.
     *
     * <p><b>It is not only a test problem.</b> A wish granted while the child is away has no player
     * to anchor on — that is a real path, and the agent's own words for it are on record: <i>"You
     * were away from the world so I couldn't put it in the Lava Beast's hand yet."</i> Two machines
     * wished for in that state would have been built into each other.
     *
     * <p><b>Derived from the id, not from a counter.</b> Rebuilding the same thing must land in the
     * same place — that is what makes a rebuild a replacement rather than litter — while two
     * different things must not share a square. A hash of the id gives both, with no state to keep
     * and no order to depend on.
     *
     * @param id the thing being built, so the same thing always returns to the same spot
     */
    public static BlockPos fallbackAnchor(ServerLevel level, String id, int forward, int offsetY) {
        BlockPos spawn = level.getRespawnData().pos().offset(forward, offsetY, 0);
        if (id == null || id.isBlank()) {
            return spawn;
        }
        // THE TWO AXES MUST BE INDEPENDENT, and the first cut got that wrong in a way a test
        // caught: `h % 16` and `(h / 16) % 16` read overlapping low bits of a Java string hash,
        // which are badly distributed, and two ordinary ids ("bakery", "house") landed on the same
        // square. Avalanche the hash first, and derive the second axis from a second round.
        int h = mix(id.hashCode());
        int dx = Math.floorMod(h, SLOTS) * SPREAD;
        int dz = Math.floorMod(mix(h), SLOTS) * SPREAD;
        return spawn.offset(dx, 0, dz);
    }

    /**
     * The murmur3 finalizer. Not for security — for spreading: it makes every input bit affect
     * every output bit, which is exactly what a Java string hash does not do.
     */
    private static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return h;
    }

    /**
     * How far apart two unattended builds stand, in blocks — wider than any footprint built
     * without a player present. A monument castle is only ever built in front of somebody.
     */
    private static final int SPREAD = 64;

    /**
     * Slots per axis, so {@value #SLOTS}² distinct spots.
     *
     * <p>Hashing means two ids <em>can</em> in principle share one; what makes that acceptable is
     * that the registries record the anchor a thing was actually built at, so a rebuild follows the
     * record rather than the hash. This is a spread, not a allocator. {@code FallbackAnchorTest}
     * measures it over the ids this repo really builds.
     */
    private static final int SLOTS = 16;

    /**
     * Where the child was <b>aiming</b> the wish watch — the spot they were looking at when they made
     * the wish, so the thing manifests THERE instead of following them around.
     *
     * <p>This is the wish-time default, captured once and locked, which fixes the "it appeared in
     * mid-air behind me" bug (the old {@link #anchorInFrontOf} recomputed from wherever the player had
     * since wandered to). Deliberately NOT a hard rule that it must be exactly the aim point: it's a
     * sensible default the agent can accept or override (e.g. lift it into the sky for "up high").
     *
     * <p>If they were pointing at a surface within reach, that block is the spot. If they were looking
     * at open air or the sky, it's a point out along their gaze — so "make it up in the air" lands up
     * in the air, right where they were looking.
     *
     * <p><b>It reads only chunks that are already loaded</b>. This runs on the server thread
     * at wish time, and a plain clip reads every block it crosses — which LOADS any chunk it enters,
     * generating it if it never was. On a fresh sky island the gaze crosses chunks still queued for
     * generation: a load test measured ten new players wishing 20 s after arriving freeze the server for
     * 35 s and drop everyone. So the ray is cut where it would first
     * enter an unloaded chunk ({@link LoadedRay}), and an open-air aim lands at the last loaded point
     * along the gaze — still the direction he aimed, and still locked at wish time.
     */
    public static BlockPos aimAnchor(net.minecraft.server.level.ServerPlayer player, int maxDistance) {
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition(1.0f);
        net.minecraft.world.phys.Vec3 look = player.getViewVector(1.0f);
        net.minecraft.world.phys.Vec3 end = eye.add(look.scale(maxDistance));
        var chunks = player.level().getChunkSource();
        double t = LoadedRay.loadedFraction(eye.x, eye.z, end.x, end.z,
                (cx, cz) -> chunks.getChunkNow(cx, cz) != null);
        if (t <= 0.0) {
            // Not even his own chunk is loaded (he is mid-teleport): nothing can be read, so the
            // spot is just in front of him — arithmetic only.
            return anchorInFrontOf(player, Math.min(4, maxDistance), 0);
        }
        if (t < 1.0) {
            end = eye.add(end.subtract(eye).scale(t));
        }
        net.minecraft.world.phys.BlockHitResult hit = player.level().clip(
                new net.minecraft.world.level.ClipContext(eye, end,
                        net.minecraft.world.level.ClipContext.Block.OUTLINE,
                        net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return hit.getBlockPos();
        }
        // Open air / sky: the point they were gazing at, so a "build it in the sky" lands up there.
        return BlockPos.containing(end);
    }

    /**
     * Look up a block id, or null if no such block is installed.
     *
     * <p><b>A block may carry its state</b> — {@code minecraft:ladder[facing=south]}. This
     * used to hand the whole string to {@code ResourceLocation.parse}, which refuses a '[', so every
     * block the agent faced came out as STONE: the library's own Underwater Dome climbed a
     * 22-block column of stone it called a ladder, and the lesson that told the agent to face its
     * ladders would have built stone too. The game's own parser reads it, exactly as a
     * {@code /setblock} would.
     */
    private static BlockState resolve(ServerLevel level, String id) {
        try {
            if (id.indexOf('[') > 0) {
                return net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
                        level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                        id.contains(":") ? id : "minecraft:" + id, false).blockState();
            }
            ResourceLocation key = ResourceLocation.parse(
                    id.contains(":") ? id : "minecraft:" + id);
            if (!BuiltInRegistries.BLOCK.containsKey(key)) {
                return null;
            }
            return BuiltInRegistries.BLOCK.getValue(key).defaultBlockState();
        } catch (Exception e) {
            return null;
        }
    }
}
