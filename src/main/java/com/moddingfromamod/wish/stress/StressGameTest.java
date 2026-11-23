package com.moddingfromamod.wish.stress;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.testing.GateShards;
import com.moddingfromamod.wish.WishCorpus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The stress driver: run every corpus case, and fail if any case is ignored.</b>
 *
 * <p>. The corpus that preceded this loaded 160 cases, exercised 19, built 4, and reported
 * green — converting an absence of testing into an appearance of testing. Three things fix that,
 * and the first is the one that keeps the other two honest:
 *
 * <ol>
 *   <li><b>Nothing may be silently skipped.</b> {@link #noCorpusCaseIsIgnored()} fails when a case's
 *       kind has no {@link StressHarness}, so adding cases without a harness is a build failure
 *       rather than a quiet no-op.</li>
 *   <li><b>Every case runs</b> through its harness, which materialises it, asserts the shared
 *       guardrail invariants, and cleans up.</li>
 *   <li><b>The run says what it did</b> — how many cases, of which kinds, and which invariants were
 *       actually checked. A budget is fine; a silent budget is the thing being fixed.</li>
 * </ol>
 */
@GameTestHolder(GateShards.DEFAULT)
public class StressGameTest {

    /** Cases are spaced far apart so no artifact can be mistaken for its neighbour. */
    private static final int SPACING = 40;
    /**
     * How far the stress grid sits from the test plot.
     *
     * <p>NOT decoration. The grid spreads up to 800 blocks, and GameTest plots are packed close
     * together — so building in place trespassed into neighbouring plots and broke an unrelated
     * test with "the train disappeared while being driven". A stress run that damages other
     * tests produces failures that look like product bugs and are not; this repo has already lost
     * time to exactly that.
     */
    private static final int AWAY = 20_000;

    /**
     * <b>The keystone.</b> A corpus case whose kind nobody claims is a case that never runs.
     *
     * <p>This is deliberately a separate, tiny, always-run test rather than a check inside the main
     * driver: if the driver itself breaks, this one keeps failing, and it fails with the sentence
     * that says what to do about it.
     */
    @GameTest(template = "empty_test", timeoutTicks = 100, batch = "stress")
    public void noCorpusCaseIsIgnored(GameTestHelper helper) {
        List<WishCorpus.Case> all = WishCorpus.load();
        if (all.isEmpty()) {
            helper.fail(Component.literal("the corpus did not load at all, so every stress gate below is vacuous"));
            return;
        }
        List<String> orphans = StressRegistry.unclaimed(all);
        if (!orphans.isEmpty()) {
            helper.fail(Component.literal("THE CORPUS IS IGNORING ITSELF:\n  - " + String.join("\n  - ", orphans)
                    + "\n\nThis check exists because the first version of this corpus loaded 160 "
                    + "cases and ran 19 of them while reporting green. A corpus that skips cases "
                    + "is worse than no corpus: it makes untested things look tested."));
            return;
        }
        System.out.println("STRESS: every corpus kind is claimed by a harness "
                + StressRegistry.claimedKinds() + "; " + StressRegistry.EXEMPT
                + " is exempt by declaration");
        helper.succeed();
    }

    /**
     * Cases PARKED by name: known flakes with an open issue, left out of every run and printed on
     * every run, so the gap is never silent. Remove the row in the PR that fixes the case.
     */
    static final Map<String, String> PARKED = Map.of();

    /** Run the corpus cases {@code which} selects, with every check this driver has always made. */
    static void runCorpus(GameTestHelper helper, java.util.function.Predicate<WishCorpus.Case> which,
                          String part) {
        ServerLevel level = helper.getLevel();
        List<WishCorpus.Case> all = WishCorpus.load();
        List<WishCorpus.Case> selected = all.stream()
                .filter(c -> StressRegistry.forKind(c.kind()) != null).filter(which).toList();
        selected.stream().filter(c -> PARKED.containsKey(c.id())).forEach(c -> System.out.println(
                "STRESS: ⏸ PARKED " + c.kind() + "/" + c.id() + " — " + PARKED.get(c.id())));
        List<WishCorpus.Case> runnable = selected.stream().filter(c -> !PARKED.containsKey(c.id())).toList();
        if (runnable.isEmpty()) {
            helper.fail(Component.literal("no runnable corpus cases in " + part + " — this gate is vacuous"));
            return;
        }
        System.out.println("STRESS: running " + part + " of the corpus");

        List<String> failures = new ArrayList<>();
        Map<String, Integer> ranByKind = new LinkedHashMap<>();
        Map<String, Integer> invariantCounts = new LinkedHashMap<>();
        List<String> skips = new ArrayList<>();
        // Cases whose kind has a harness but whose subsystem does not exist yet. Counted and
        // printed, never silent — see Capabilities.
        List<WishCorpus.Case> waiting = Capabilities.waiting(runnable);
        List<WishCorpus.Case> allLive = runnable.stream().filter(Capabilities::runnable).toList();

        // ── THE ONE-CASE DIAGNOSTIC. -Dmoddingfromamod.onlycase=<id prefix> runs a single case
        // with the whole runner to itself. It exists because two machines now behave differently
        // HERE than in their own GameTest lane (cmp_steam_scoreboard's powered shaft reverting,
        // cmp_juice_delivery's spout never filling), and the first question about any such fault
        // is the one nothing could previously ask: is it the runner's ENVIRONMENT, or is it the
        // five other machines sharing the column? Alone means environment; still-broken-together
        // means interference. Never on in a normal run.
        String only = System.getProperty("moddingfromamod.onlycase");
        final List<WishCorpus.Case> live = only == null || only.isBlank() ? allLive
                : allLive.stream().filter(c -> c.id().startsWith(only)).toList();
        if (live != allLive) {
            System.out.println("STRESS: ONLYCASE=" + only + " — running " + live.size()
                    + " case(s) alone. This is a DIAGNOSTIC run and proves nothing about the rest.");
        }

        // Staged harnesses cannot assert in the tick that places the artifact: rotation propagates
        // over ticks, and Create's actuators self-assemble on the tick AFTER they receive speed.
        //
        // Done in BATCHES, stacked above this test's own plot — see runBatch. The plot's chunk is
        // already loaded and already ticking, so nothing has to be force-loaded for a Create
        // network to turn, and nothing spreads sideways into a neighbouring plot.
        List<Pending> staged = new ArrayList<>();
        int i = 0;

        for (WishCorpus.Case c : live) {
            StressHarness harness = StressRegistry.forKind(c.kind());
            boolean needsTicking = harness instanceof StressHarness.Staged;
            // Staged cases are anchored later, per batch, DIRECTLY ABOVE THIS TEST'S OWN PLOT.
            // Everything else keeps the far-away grid.
            BlockPos anchor = needsTicking ? BlockPos.ZERO
                    : helper.absolutePos(new BlockPos(2, 3, 2))
                            .offset(AWAY + (i % 20) * SPACING, 0, (i / 20) * SPACING);
            if (!needsTicking) {
                i++;
            }
            StressHarness.Context ctx = new StressHarness.Context(level, anchor);
            ranByKind.merge(c.kind(), 1, Integer::sum);
            try {
                if (harness instanceof StressHarness.Staged s) {
                    staged.add(new Pending(s, ctx, c, null));
                } else {
                    record(harness.run(ctx, c), c, failures, invariantCounts, skips);
                }
            } catch (RuntimeException | AssertionError e) {
                // A harness that dies must not hide every case after it.
                failures.add(c.kind() + "/" + c.id() + "\n        the harness threw: " + e);
            }
        }

        Runnable done = () -> {
            report(all, runnable, live, waiting, ranByKind, invariantCounts, skips);
            if (!failures.isEmpty()) {
                helper.fail(Component.literal("STRESS FAILURES (" + failures.size() + " across " + live.size()
                        + " case(s)):\n\n  - " + String.join("\n\n  - ", failures)));
                return;
            }
            System.out.println("STRESS: all " + live.size() + " case(s) held their invariants ✓");
            helper.succeed();
        };

        if (staged.isEmpty()) {
            done.run();
        } else {
            runBatch(helper, level, staged, 0, failures, invariantCounts, skips, done);
        }
    }

    /**
     * How many ticking artifacts are built at once, stacked above this test's own plot.
     *
     * <p>Bounded by world height rather than by taste: the tallest thing in the corpus occupies
     * 34 blocks and they are stacked {@link #STAGED_Y_SPACING} apart, so six fits comfortably
     * between the plot and the build limit.
     */
    private static final int STAGED_BATCH = 6;
    /** Far enough above the plot floor to clear whatever a neighbouring test built on the ground. */
    private static final int STAGED_BASE_Y = 60;
    /** The tallest machine in the corpus is 34 blocks; this leaves clearance. */
    private static final int STAGED_Y_SPACING = 40;
    /** How many slots before the sideways stagger wraps onto the next row. See runBatch. */
    private static final int STAGED_STAGGER = 3;

    /** One staged case, before ({@code state == null}) and after placing. */
    private record Pending(StressHarness.Staged harness, StressHarness.Context ctx,
                           WishCorpus.Case testCase, Object state) {
    }

    /**
     * <b>How far west of the plot the whole stack is built</b>, so every staged case ends inside
     * the chunks the server ticks. Usually zero.
     *
     * <p>"The plot's chunk is ticking" is true of that chunk only for ENTITIES and of the ring
     * around it for blocks; the ring after that is loaded and frozen. Anchored on the plot, the
     * 23-block cmp_whole_factory reached the frozen ring whenever the plot sat far enough into its
     * chunk: its 'shop' port and conveyor never ticked, the parcel looped there for the whole
     * window, and the case failed one run in ten as "NOTHING ARRIVED" with every wire correct.
     * Measured: red at exactly one anchor residue (x mod 16 = 10, where x+22 is the next-but-one
     * chunk's first block, shouldTickBlocksAt false), green at the other seven.
     *
     * <p>ONE pull for the whole run, two ways tried first. Moving every anchor a chunk west put the
     * crusher, fed DROPPED cobblestone, outside the one chunk that ticks entities: eleven machine
     * cases red. Pulling each wide case by its own reach broke the stagger — two elevators in one
     * (x, z) column — and stopped the batches reusing the same anchors. A pull shared by every slot
     * and batch keeps both, never moves an anchor out of the plot's chunk, and builds exactly what
     * a plot sitting further west in its chunk would have built anyway.
     */
    static int westPull(GameTestHelper helper, List<Pending> staged) {
        int reach = 0;
        for (Pending p : staged) {
            reach = Math.max(reach, p.harness().reachEast(p.testCase()));
        }
        int x = helper.absolutePos(new BlockPos(2, 3, 2)).getX();
        int chunkWest = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(x));
        int lastTicking = chunkWest + 2 * SectionPos.SECTION_SIZE - 1;
        int east = x + STAGED_STAGGER - 1 + reach;
        return Math.min(x - chunkWest, Math.max(0, east - lastTicking));
    }

    /**
     * Place one batch, let it settle, assert it, hand its chunks back, then start the next.
     *
     * <p>Recursive rather than a loop because the settle is a real wait: each batch must actually
     * tick before its claims can be made. Peak forced chunks stay bounded by {@link #STAGED_BATCH}
     * however long the corpus gets.
     */
    private static void runBatch(GameTestHelper helper, ServerLevel level, List<Pending> staged,
                                 int from, List<String> failures,
                                 Map<String, Integer> invariantCounts, List<String> skips,
                                 Runnable done) {
        if (from >= staged.size()) {
            done.run();
            return;
        }
        int to = Math.min(from + STAGED_BATCH, staged.size());
        int pull = westPull(helper, staged);
        List<Pending> placed = new ArrayList<>();
        int settle = 1;
        int slot = 0;

        for (Pending p : staged.subList(from, to)) {
            // STACKED ABOVE THE PLOT, one per slot. This is the whole fix: a GameTest's own plot
            // chunk is already loaded and already ticking, so a Create network built above it turns
            // without anybody force-loading anything -- which is exactly how ContraptionGameTest
            // has ticked nine machines reliably all along, building at BlockPos(0, 140, 0).
            //
            // The previous version put these on the far-away grid and force-loaded their chunks,
            // and that destabilised an unrelated train test twenty thousand blocks away with
            // "the train disappeared while being driven". Measured: 3/3 clean before the
            // force-loading existed, 2/3 flaky after, 3/3 clean with it disabled, 2/5 flaky with it
            // merely batched. Stacking vertically also cannot trespass sideways into a neighbouring
            // plot, which is what the far-away grid existed to avoid in the first place.
            // ...but NOT in the same (x, z) column, which is a distinction Create cares about and
            // a stack of machines hides. An ElevatorColumn is registered globally by (x, z, side)
            // with NO Y COMPONENT, so every lift in a single column is the same column to Create:
            // the first one to assemble claims it and every later one is refused with "Another
            // Elevator is already targeting this column of contacts". Two elevator cases failed
            // that way and read as broken designs. One block of sideways stagger per slot is
            // enough — the separation that matters is still the 40 blocks of Y.
            //
            // ...and inside the chunks the server TICKS. See westPull.
            int stagger = slot++;
            BlockPos anchor = helper.absolutePos(new BlockPos(2, 3, 2))
                    .offset(stagger % STAGED_STAGGER - pull,
                            STAGED_BASE_Y + stagger * STAGED_Y_SPACING, stagger / STAGED_STAGGER);
            StressHarness.Context ctx = new StressHarness.Context(level, anchor);
            try {
                placed.add(new Pending(p.harness(), ctx, p.testCase(),
                        p.harness().place(ctx, p.testCase())));
                settle = Math.max(settle, p.harness().settleTicks());
            } catch (RuntimeException | AssertionError e) {
                failures.add(p.testCase().kind() + "/" + p.testCase().id()
                        + "\n        the harness threw: " + e);
            }
        }

        // Four phases, not two: place → settle → FEED → stage → process → assert. The feed cannot
        // happen before the machine is running and its result is not available in the same tick, so
        // "rock in, gravel out" needs its own step. Only cases that ask for one pay for it.
        //
        // ...and STAGE, because some claims have to act on what the machine MADE. A parcel
        // carrying the bucket a spout filled cannot be posted at feed time: the spout needs
        // hundreds of ticks to charge, so at feed time there is no bucket, and posting a fabricated
        // one is precisely the fiction that left seven composites unable to claim anything about
        // the machines they are named for.
        int process = 0;
        int stage = 0;
        for (Pending p : placed) {
            process = Math.max(process, p.harness().processTicks(p.testCase()));
            stage = Math.max(stage, p.harness().stageTicks(p.testCase()));
        }
        int afterFeed = process;
        int beforeStage = stage;
        helper.runAfterDelay(settle, () -> {
            for (Pending p : placed) {
                try {
                    p.harness().feed(p.ctx(), p.testCase(), p.state());
                } catch (RuntimeException | AssertionError e) {
                    failures.add(p.testCase().kind() + "/" + p.testCase().id()
                            + "\n        the harness threw while feeding: " + e);
                }
            }
        });
        // Scheduled unconditionally but a no-op by default, so a batch with nothing to stage
        // costs one tick rather than a branch nobody exercises.
        helper.runAfterDelay(settle + beforeStage, () -> {
            for (Pending p : placed) {
                try {
                    p.harness().stage(p.ctx(), p.testCase(), p.state());
                } catch (RuntimeException | AssertionError e) {
                    failures.add(p.testCase().kind() + "/" + p.testCase().id()
                            + "\n        the harness threw while staging: " + e);
                }
            }
        });
        helper.runAfterDelay(settle + beforeStage + Math.max(1, afterFeed), () -> {
            for (Pending p : placed) {
                try {
                    record(p.harness().assertSettled(p.ctx(), p.testCase(), p.state()),
                            p.testCase(), failures, invariantCounts, skips);
                } catch (RuntimeException | AssertionError e) {
                    failures.add(p.testCase().kind() + "/" + p.testCase().id()
                            + "\n        the harness threw while asserting: " + e);
                }
            }
            // EVERY BATCH REUSES THESE SAME ANCHORS — `slot` restarts at 0 up there. So the
            // machines have to come down before the next batch moves in, or each case is built
            // inside the last one's wreckage and its result depends on who its predecessor was.
            for (Pending p : placed) {
                try {
                    p.harness().cleanUp(p.ctx(), p.testCase(), p.state());
                } catch (RuntimeException | AssertionError e) {
                    failures.add(p.testCase().kind() + "/" + p.testCase().id()
                            + "\n        the harness threw while cleaning up: " + e);
                }
            }
            runBatch(helper, level, staged, to, failures, invariantCounts, skips, done);
        });
    }

    private static void record(StressHarness.Outcome outcome, WishCorpus.Case c,
                               List<String> failures, Map<String, Integer> invariantCounts,
                               List<String> skips) {
        for (String inv : outcome.invariants()) {
            invariantCounts.merge(inv, 1, Integer::sum);
        }
        for (String s : outcome.skipped()) {
            skips.add(c.kind() + "/" + c.id() + ": " + s);
        }
        for (String p : outcome.problems()) {
            failures.add(c.kind() + "/" + c.id() + " (\"" + c.prompt() + "\")\n        " + p
                    + "\n        [source: " + c.source() + "]");
        }
    }

    /**
     * THE RUN REPORT. Printed whether it passes or fails.
     *
     * <p>"What did the stress actually do?" must be answerable without reading the source. The
     * skip and gap lines are the important ones: a quiet skip reports green over an evaluation that
     * never ran, and that is this package's founding defect.
     */
    private static void report(List<WishCorpus.Case> all, List<WishCorpus.Case> runnable,
                               List<WishCorpus.Case> live, List<WishCorpus.Case> waiting,
                               Map<String, Integer> ranByKind,
                               Map<String, Integer> invariantCounts, List<String> skips) {
        System.out.println("STRESS RUN: " + live.size() + " case(s) run of " + all.size()
                + " corpus entries (" + WishCorpus.unsupported(all).size()
                + " unsupported, " + waiting.size() + " waiting on an unbuilt capability)");
        ranByKind.forEach((k, n) -> System.out.println("   kind " + k + ": " + n + " case(s)"));
        invariantCounts.forEach((inv, n) ->
                System.out.println("   checked " + n + "x: " + inv));
        Capabilities.gapReport(runnable).forEach((cap, n) ->
                System.out.println("   ⏳ " + n + " case(s) WAITING on '" + cap + "' — written, "
                        + "not yet runnable, and not counted as passing"));
        if (!skips.isEmpty()) {
            System.out.println("   ⚠ " + skips.size() + " claim(s) SKIPPED:");
            skips.stream().distinct().limit(5).forEach(s -> System.out.println("      " + s));
        }
    }

    /**
     * <b>Coverage may rise, never quietly fall.</b>
     *
     * <p>The keystone catches a case nobody runs. This catches a whole KIND falling out — delete a
     * harness or mistype a kind and the suite would still pass, just over less.
     */
    @GameTest(template = "empty_test", timeoutTicks = 100, batch = "stress")
    public void stressCoverageDoesNotRegress(GameTestHelper helper) {
        List<String> regressions = StressBaseline.regressions(WishCorpus.load());
        if (!regressions.isEmpty()) {
            helper.fail(Component.literal("STRESS COVERAGE REGRESSED:\n  - " + String.join("\n  - ", regressions)));
            return;
        }
        System.out.println("STRESS: coverage floor holds for all "
                + StressBaseline.FLOOR.size() + " stressed kind(s) \u2713");
        helper.succeed();
    }

    /**
     * <b>A written-but-unrunnable case is backlog, and backlog may not grow unwatched.</b>
     *
     * <p>The machines corpus deliberately describes subsystems nobody has built (–), so that
     * each one lands against cases it did not get to write for itself. The obvious risk is equally
     * deliberate to guard: a case naming a capability that does not exist is a case that never
     * runs, and "adding cases" would otherwise be indistinguishable from progress in every report.
     *
     * <p>So the number of waiting cases is capped, and a {@code needs} entry nobody has heard of is
     * a hard failure — a typo there parks a case forever in a gap that can never close, while
     * looking exactly like honest backlog.
     */
    @GameTest(template = "empty_test", timeoutTicks = 100, batch = "stress")
    public void unbuiltCapabilitiesAreBacklogAndNotSilence(GameTestHelper helper) {
        List<WishCorpus.Case> all = WishCorpus.load();
        List<String> unknown = Capabilities.unknownNames(all);
        if (!unknown.isEmpty()) {
            helper.fail(Component.literal("a corpus case needs a capability Capabilities has never heard of:\n  - "
                    + String.join("\n  - ", unknown)
                    + "\n\nA typo here is invisible: the case is skipped forever and the gap can "
                    + "never close, which reads as backlog rather than as a mistake."));
            return;
        }
        List<WishCorpus.Case> waiting = Capabilities.waiting(all);
        if (waiting.size() > Capabilities.MAX_GAPS) {
            helper.fail(Component.literal(waiting.size() + " corpus case(s) are waiting on an unbuilt capability, "
                    + "over the ceiling of " + Capabilities.MAX_GAPS + ".\n"
                    + Capabilities.gapReport(all)
                    + "\n\nEither build one of these oracles or raise the ceiling deliberately. "
                    + "Adding cases nobody can run is not progress, and it is the one thing that "
                    + "looks like progress in every report."));
            return;
        }
        System.out.println("STRESS: " + waiting.size() + "/" + Capabilities.MAX_GAPS
                + " case(s) waiting on an unbuilt capability " + Capabilities.gapReport(all));
        helper.succeed();
    }

    /**
     * The shared invariants must actually be exercised somewhere.
     *
     * <p>Without this, a harness could quietly stop checking P5 and the suite would look identical.
     * The invariant names are reported by the harnesses themselves, so this asserts on what was
     * genuinely run rather than on what the code appears to contain.
     */
    // ITS OWN BATCH, and that is a fix rather than tidiness —.
    //
    // This test runs the SAME harnesses over the SAME corpus cases as
    // everyCorpusCaseHoldsItsInvariants, so MachinesHarness.place registers the same
    // `stress_<case-id>` ids into the process-wide ContraptionRegistry — but at ITS OWN anchors,
    // which it computes independently. GameTests in one batch run CONCURRENTLY, so the two raced:
    // one remembered an id at its anchor, the other overwrote that anchor, and the first then
    // failed P4_LOCKED_ANCHOR saying "the machine was recorded at a different place from where it
    // was built". Which was true, and was this test's doing rather than a product defect.
    //
    // It presented as flakiness (3 failures in 17 runs) with a stable signature: always machines,
    // always a contiguous group of cases — the interleaving window — and twice the byte-identical
    // set of six. Batches run sequentially, so a batch of its own is the isolation.
    //
    // Exactly the hazard ArtifactLevelGameTest already documents for
    // ContraptionGameTest.aDesignedMachineSurvivesAReload and its clear(): a test that shares a
    // static resource with a concurrent test is measuring both of them.
    @GameTest(template = "empty_test", timeoutTicks = 3000, batch = "stressguardrail")
    public void theSharedGuardrailInvariantsAreActuallyChecked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<WishCorpus.Case> all = WishCorpus.load();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Set<String> kindsRun = new java.util.HashSet<>();
        int i = 0;
        List<String> mustBeChecked = new ArrayList<>(List.of(
                Invariants.MATERIALISES, Invariants.P5_REMOVES_CLEAN,
                Invariants.P5_NON_DESTRUCTIVE, Invariants.NO_SILENT_CAPS,
                Invariants.P6_SURVIVES_RELOAD,
                Invariants.BUILDINGS_HAVE_FOUR_WALLS));
        // STOP AS SOON AS THE QUESTION IS ANSWERED. The assertion below is EXISTENTIAL -- "is each
        // required invariant exercised by ANY harness" -- so once the prefix covers the list, no
        // later case can change the verdict: running more can only ADD to `seen`, never remove.
        //
        // That makes the early exit exactly equivalent rather than merely cheaper:
        //   covered by the prefix  -> exit and pass; the rest could not have made it fail
        //   NOT covered            -> the loop runs every case, exactly as it always did
        // So the only direction this moves is conservative.
        //
        // It matters because the loop was doing 359 real builds (machines 130, buildings 103,
        // creatures 100, trains 14, composite 10, datapacks 2) to learn what SIX harnesses report,
        // and an invariant set is a property of the harness rather than of the case. Measured
        // 2026-09-01: 19.1s in a batch of its own, at 1.0 ticks/second -- the whole loop
        // runs inside one tick, so it is 19 seconds of pure serialization for the suite.
        //
        // Every harness is still exercised at least once, deliberately: a harness that checks
        // NOTHING is worth seeing, and stopping purely on the invariant list could skip it.
        //
        // ⚠ AND THE ORDER IS WHAT MAKES THE EXIT PAY. Corpus order is FILE order, so the last kind
        // needed arrives late: measured, an early exit over `all` as loaded still ran 304 of 697
        // cases and saved 4.5s of 18.0s. Round-robin across kinds puts one case of every kind in
        // the first six, so both conditions are reachable almost immediately.
        //
        // Reordering cannot change the verdict -- the assertion is existential over the whole
        // corpus, so a different order only changes which prefix is TRIED first; if no prefix
        // satisfies, every case is still visited.
        for (WishCorpus.Case c : roundRobinByKind(all)) {
            StressHarness h = StressRegistry.forKind(c.kind());
            if (h == null) {
                continue;
            }
            if (kindsRun.containsAll(StressRegistry.claimedKinds())
                    && seen.containsAll(mustBeChecked)) {
                break;
            }
            BlockPos anchor = helper.absolutePos(new BlockPos(2, 3, 2))
                    .offset(-AWAY - (i % 10) * SPACING, 0, (i / 10) * SPACING);
            i++;
            kindsRun.add(c.kind());
            try {
                seen.addAll(h.run(new StressHarness.Context(level, anchor), c).invariants());
            } catch (RuntimeException | AssertionError ignored) {
                // failures are the other test's business
            }
        }
        List<String> unchecked = mustBeChecked.stream().filter(inv -> !seen.contains(inv)).toList();
        if (!unchecked.isEmpty()) {
            helper.fail(Component.literal("these shared guardrail invariants are not exercised by ANY harness:\n  - "
                    + String.join("\n  - ", unchecked)
                    + "\n\nThe same P5 hole shipped twice in one day (machines, then datapacks) "
                    + "because each was checked by a different gate that happened not to look. "
                    + "An invariant nobody checks is a guardrail on paper."));
            return;
        }
        System.out.println("STRESS: at least " + seen.size() + " distinct invariant(s) exercised "
                + "over " + i + " of " + all.size() + " case(s), including all "
                + mustBeChecked.size() + " required ones ✓");
        helper.succeed();
    }

    /**
     * The same cases, interleaved one-per-kind, so a prefix covers every harness immediately.
     *
     * <p>Order-only: the returned list is a permutation of the input, never a subset, so a caller
     * that consumes all of it sees exactly what it saw before.
     */
    private static List<WishCorpus.Case> roundRobinByKind(List<WishCorpus.Case> all) {
        java.util.Map<String, java.util.List<WishCorpus.Case>> byKind =
                new java.util.LinkedHashMap<>();
        for (WishCorpus.Case c : all) {
            byKind.computeIfAbsent(c.kind(), k -> new ArrayList<>()).add(c);
        }
        List<WishCorpus.Case> out = new ArrayList<>(all.size());
        int row = 0;
        boolean any = true;
        while (any) {
            any = false;
            for (java.util.List<WishCorpus.Case> cases : byKind.values()) {
                if (row < cases.size()) {
                    out.add(cases.get(row));
                    any = true;
                }
            }
            row++;
        }
        return out;
    }
}
