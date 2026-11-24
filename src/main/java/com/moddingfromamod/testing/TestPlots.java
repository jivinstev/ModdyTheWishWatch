package com.moddingfromamod.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * <b>Where each test is allowed to build, so two of them cannot land on each other.</b>
 *
 * <p>GameTest lays plots out in a grid and resets only the plot itself. Every Create test here
 * builds <i>outside</i> its plot, because a machine spans more than a plot is wide — so the space
 * around the plots is shared, and which neighbour you get depends on how many tests exist.
 *
 * <p>That is not hypothetical. Adding three GameTests made the crusher tests fail
 * <b>deterministically</b>, reading their own crushing wheels as {@code minecraft:stone} — someone
 * else's blocks were sitting where the machine should be. The message they printed says <i>"the
 * design principles are axis-specific, which means they are wrong"</i>, a confident lie about
 * Create. Removing the tests made it green.
 *
 * <h2>Two axes of separation, because there are two ways to collide</h2>
 *
 * <p>An earlier attempt gave each class its own <b>height</b> above its own plot. That failed, and
 * the way it failed is the useful part: three <i>empty</i> GameTests — no blocks at all — still
 * broke the crusher. Height was never the problem. Adding any plot shifts the whole grid, and a
 * test building 24 blocks sideways lands on whichever neighbour it now has.
 *
 * <p>The second attempt gave each <b>class</b> a lane and stacked that class's own tests in one
 * column, twenty blocks apart in Y. That broke the crusher too, and measurably worse than main:
 * tests in a batch run <b>concurrently</b>, so six tests sharing one column are six builders working
 * on top of each other. Per-class separation is not enough when the unit of concurrency is the
 * test.
 *
 * <p>So the address has two parts:
 *
 * <ul>
 *   <li>a <b>lane</b> per class — twenty thousand blocks out, {@value #LANE} apart, so nothing can
 *       reach a neighbouring plot however the grid shifts and no two classes can reach each
 *       other;</li>
 *   <li>a <b>site</b> per test within that lane, {@value #SITE} apart, so two tests of the same
 *       class running at the same moment cannot either. A site index is mandatory: there is no
 *       overload that defaults to zero, because the failure mode of two tests quietly sharing site
 *       0 is exactly the bug this class exists to end.</li>
 * </ul>
 *
 * <p>An address is <b>(lane, test, site)</b> and nothing else. It used to be relative to the
 * caller's plot, which sounded safer and was the opposite: plots are laid out from the set of tests
 * being run, so the same test landed somewhere different depending on which OTHER tests ran. Two
 * Create tests went deterministically red under a Create-only run and green in the full suite
 * because of it. See {@link #MAX_SITE}.
 */
public final class TestPlots {

    /** Far enough that no lane can reach a neighbouring plot however the grid shifts. */
    private static final int AWAY = 20_000;
    /** Between classes. A machine spans tens of blocks; this is three orders of magnitude more. */
    private static final int LANE = 16_384;
    /** Between concurrent tests of one class. The widest build here spans under a hundred blocks. */
    private static final int SITE = 1_024;

    /**
     * Build in open air ABOVE the ground, not level with it.
     *
     * <p>Not cosmetic. The test world is a superflat, so a lane at plot level sits inside the ground
     * layer, and the machines that failed there failed <i>physically</i>: a rope pulley has no room
     * to extend downward through stone, and an item cannot fall into a crusher's gap. Both reported
     * themselves as broken designs while turning perfectly, which is exactly the false negative this
     * whole subsystem exists to avoid. The old per-test constants each carried a lift of their own
     * (+40, +60); moving to lanes dropped it, and only those two tests could tell.
     */
    private static final int LIFT = 40;

    /** How far around a site is made to tick. Comfortably wider than the widest build here. */
    private static final int LOAD_CHUNKS = 6;

    /**
     * <b>Between TESTS — and the reason a build site may not be anchored to the caller's plot.</b>
     *
     * <p>{@code at()} used to add {@code helper.absolutePos(...)}, so a site's real coordinates
     * moved with the test's GameTest plot. Plots are laid out from the set of tests being run, so
     * <b>the same test lands somewhere different depending on which OTHER tests ran</b> — and this
     * repo already records the general form of that trap: plot origins differ by thousands of
     * blocks, so "plot + offset" cannot be made safe.
     *
     * <p>Measured: with only the Create tests selected, {@code theChainFillsABottle...} put
     * {@code 1x minecraft:grass_block} on a depot that should have held a filled bottle, and
     * {@code anItemOneBlockOffTheFunnel...} watched an item reach a basin it was placed too far
     * from to reach — both DETERMINISTIC in that configuration, both green in the full suite, at a
     * commit where the whole suite is 522/522. A test whose answer depends on which other tests ran
     * is not measuring the mod.
     *
     * <p>So a site is now addressed by {@code (test, site)} and nothing else — the test picks the
     * X band, the site index picks the Z column, and every pair is its own {@code SITE}-square
     * cell. Only the plot's <b>Y</b> is still borrowed, because every plot shares it and it is
     * what {@link #LIFT} is relative to.
     *
     * <p>{@code lane} no longer contributes to the address, and cannot: a per-test band is finer
     * than a per-class lane, and adding both lets {@code (lane 0, band 20)} and
     * {@code (lane 1, band 4)} land on the same square. The field stays because it names the
     * owning class in diagnostics, which is worth having when a site turns up somewhere odd.
     *
     * <p>The first cut budgeted 64 sites per test and Gate B said no, out loud, 13 times: sites
     * run past 160. That is the guard working — the alternative was one test's sites running
     * silently into another test's ground, which is the bug being fixed.
     */
    private static final int MAX_SITE = 1_024;

    /**
     * Which Z band each test owns. Unique per test NAME, so it cannot depend on the plot grid.
     *
     * <p>⚠ <b>But it does depend on the set and order of tests being run</b>, because the band is
     * handed out in first-seen order. Insert a test and every test first seen after it shifts one
     * band along — onto ground that, in a <b>reused</b> GameTest world, may still hold the previous
     * run's blocks from whichever test used to live there.
     *
     * <p><b>That is a hazard, not a diagnosis.</b> It was written here once as the established cause
     * of {@code thetagprobecatchesahalfconfiguredcopycat} going red, on the strength of two
     * failures and one passing run after wiping the world. The recorded history refuted it: the
     * same test failed four times that day on clean trees at commits the branch never touched, and
     * a later clean-world run failed too — 7 failures in 53 runs, mostly other people's. A 1-in-8
     * test passes 7 times in 8 whether or not you changed anything.
     *
     * <p>So if an unrelated test goes red after you add one, <b>ask the history first</b> — does it
     * fail on trees that are not yours? ({@code corpus/gate-history.jsonl}, the per-run
     * {@code failures} map.) That is two minutes and it settles what re-running cannot. A
     * name-derived band would remove this hazard regardless of whether it has ever caused a
     * failure, and is worth doing in a change of its own, since it moves every test's coordinates.
     */
    private static final java.util.Map<String, Integer> TEST_BAND =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_BAND =
            new java.util.concurrent.atomic.AtomicInteger();

    private static int bandOf(GameTestHelper helper) {
        return TEST_BAND.computeIfAbsent(helper.testInfo.id().toString(),
                k -> NEXT_BAND.getAndIncrement());
    }

    /** One test class's own patch of world. */
    public record Region(String owner, int lane) {

        /**
         * A build position at one <b>site</b> in this region, with that site made to tick.
         *
         * <p>The site is loaded here rather than by a separate call, because the two must agree:
         * an earlier version force-loaded a fixed area around the <i>lane origin</i> while handing
         * back positions hundreds of blocks away, so the build sat in a chunk nobody had loaded. An
         * unloaded Create machine does not tick, and every kinetic reading comes back zero — a
         * perfectly good machine reporting as dead, which is the exact false negative this whole
         * subsystem exists to prevent.
         */
        public BlockPos at(GameTestHelper helper, int site, int dx, int dy, int dz) {
            BlockPos base = base(helper, site);
            load(helper.getLevel(), base);
            return base.offset(dx, dy, dz);
        }

        private BlockPos base(GameTestHelper helper, int site) {
            if (site < 0 || site >= MAX_SITE) {
                throw new IllegalArgumentException("site " + site + " is outside the " + MAX_SITE
                        + " a test may use; raise MAX_SITE rather than letting one test's sites "
                        + "run into another test's ground");
            }
            // (test, site) and NOTHING ELSE. See MAX_SITE for why neither the plot nor the lane is
            // in here: a site that moves with the plot grid moves with the SET OF TESTS BEING RUN,
            // and the Create shard proved that turns a green test red with no code change.
            return new BlockPos(
                    AWAY + bandOf(helper) * SITE,
                    helper.absolutePos(new BlockPos(2, 3, 2)).getY() + LIFT,
                    site * SITE);
        }

        /**
         * <b>A spot in ONE site, loaded around ITSELF with a small square — for a test with many
         * small spots.</b>
         *
         * <p>{@link #at} gives every site its own {@code (2*LOAD_CHUNKS+1)²} = 169 forced chunks, a
         * thousand blocks from the next. That is right for a Create machine that must TICK, and a
         * heavy price for a test that only needs its spot to exist: each fully generated chunk
         * generates a ring of half-built neighbours, which unload later and are SAVED one by one
         * ({@code ChunkMap.isExistingChunkFull}, a region-file read each). Measured with
         * StarterReplay's fifteen sites: a 17–23 s stall on this box and <b>69 s on Cloud Build</b>,
         * charged to whichever innocent batch ran next. Spots {@code spacing} blocks apart along one
         * site share their rings.
         */
        public BlockPos spot(GameTestHelper helper, int site, int index, int spacing, int radius) {
            BlockPos at = base(helper, site).offset(0, 0, index * spacing);
            load(helper.getLevel(), at, radius);
            return at;
        }

        /**
         * Make a site tick. Idempotent, and once per site per run rather than once per call.
         *
         * <p>Keyed on the <b>chunk</b>, not on the lane. Keying on the lane looked equivalent and
         * was not: every test has its own plot, so "lane 0" is a different place for every test in
         * the class. The first test loaded its chunks, every later one saw the lane marked done and
         * loaded nothing, and their machines sat frozen — blocks present, correct, and reading
         * STOPPED.
         *
         * <p>Held for the whole run rather than acquired and released per test, because GameTest
         * gives no teardown hook and threading a release through every callback exit — including
         * the {@code helper.fail(...); return;} paths — would leak on exactly the runs that already
         * went wrong. The cost is bounded: one square of chunks per site, taken once. The server
         * hands them back when it stops.
         */
        private static void load(ServerLevel level, BlockPos base) {
            load(level, base, LOAD_CHUNKS);
        }

        private static void load(ServerLevel level, BlockPos base, int radius) {
            ChunkPos centre = ChunkPos.containing(base);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (LOADED.add(ChunkPos.pack(centre.x() + dx, centre.z() + dz))) {
                        level.setChunkForced(centre.x() + dx, centre.z() + dz, true);
                    }
                }
            }
        }
    }

    /**
     * <b>How long Create needs before a freshly placed machine can be read at all.</b>
     *
     * <p>Create builds its kinetic networks over the ticks after the blocks land. Probe before that
     * and <i>every</i> component reads {@code STOPPED (no power reaches it)} — a perfect machine
     * reporting as totally dead, which is the exact false negative this whole subsystem exists to
     * avoid, and it reads as a product bug rather than a timing one.
     *
     * <p>It cost a Gate B cycle: a gate test placed a factory, verified it immediately, and failed
     * claiming the gate refused a working machine. That was a true sentence about a machine which
     * genuinely was not running yet.
     *
     * <p>Every Create test here had already discovered this and declared its own private constant.
     * It lives here now so the NEXT one does not have to discover it a seventh time.
     */
    public static final int SETTLE_TICKS = 60;

    /**
     * Run {@code then} once Create has settled — the thing to reach for instead of a bare
     * {@link GameTestHelper#runAfterDelay} with a number somebody guessed.
     */
    public static void afterCreateSettles(GameTestHelper helper, Runnable then) {
        helper.runAfterDelay(SETTLE_TICKS, then);
    }

    /** Chunks already force-loaded this run, so re-entering a site costs nothing. */
    private static final Set<Long> LOADED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ── One lane per class. Adding a test class means adding a line, which TestPlotsTest checks.
    // Lanes 0–35 and 37 belonged to the Create and train GameTests, removed with Create.
    // The numbers left in use are kept as they were, so no surviving test moves.
    /** the camera's line-of-sight check, driven against a REAL world. */
    public static final Region CLEAR_VIEW = new Region("ClearViewGameTest", 36);
    // 37 for the reason this file keeps recording: this branch claimed 36 while it was open and
    // main got there first with ClearViewGameTest. Stepping aside is cheaper than renumbering
    // someone else's tests, whose coordinates are already in their run logs.
    /** Every starter replayed: a lava-moat castle and a treehouse, far from every plot. */
    public static final Region STARTER_REPLAY = new Region("StarterReplayGameTest", 38);
    /** open ground, so "did it follow him" is not answered by a plot's walls. */
    public static final Region INSTINCTS = new Region("InstinctGameTest", 39);

    public static List<Region> all() {
        return List.of(CLEAR_VIEW, STARTER_REPLAY, INSTINCTS);
    }

    /** Two classes sharing a lane would reach each other. Empty is the only acceptable answer. */
    public static List<String> clashes() {
        List<String> out = new ArrayList<>();
        List<Region> regions = all();
        for (int i = 0; i < regions.size(); i++) {
            for (int j = i + 1; j < regions.size(); j++) {
                if (regions.get(i).lane() == regions.get(j).lane()) {
                    out.add(regions.get(i).owner() + " and " + regions.get(j).owner()
                            + " share lane " + regions.get(i).lane());
                }
            }
        }
        return out;
    }

    /** How far apart two lanes are, so a test can assert its own build fits inside one. */
    public static int laneWidth() {
        return LANE;
    }

    /** How far apart two sites are, so a test can assert its own build fits inside one. */
    public static int siteWidth() {
        return SITE;
    }

    private TestPlots() {
    }
}
