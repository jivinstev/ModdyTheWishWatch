package com.moddingfromamod.wish.stress;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.wish.WishCorpus;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * <b>How one kind of wish gets stress-tested.</b>
 *
 * <p>The contract that turns a corpus from a data file into a standard. Before this, a corpus case
 * only ran if someone had hand-written a gate that happened to read its kind — so of 160 mined
 * cases, 19 were exercised, 4 reached the world, and <b>141 sat inert while the suite reported
 * green</b>. Adding a case did nothing. Adding a wish class meant authoring another bespoke gate,
 * which is another chance to build another quietly-skipping corpus.
 *
 * <p>Now: a kind is stressed if and only if something implements this and registers it, and
 * {@link StressRegistry} fails the build when a case has no claimant. Adding a case to the JSONL
 * exercises it; adding a wish class means implementing one interface.
 *
 * <h2>The three phases, and why cleanup is part of the contract</h2>
 *
 * <p>{@link #run} must materialise the wish, assert what the case claims, and <b>put the world
 * back</b>. Cleanup is not politeness: the corpus runs many cases against one shared world, and a
 * case that leaves its artifact standing becomes the next case's mysterious neighbour. That exact
 * hazard — a search radius reaching a neighbouring artifact — has now produced three separate bugs
 * in this repo.
 */
public interface StressHarness {

    /** The corpus {@code kind} this harness claims. Exactly one harness per kind. */
    String kind();

    /**
     * Materialise the case, assert it, clean up.
     *
     * <p>Must not throw: a harness that dies takes the whole run with it and hides every case
     * after it. Return the failure instead.
     */
    Outcome run(Context ctx, WishCorpus.Case testCase);

    /**
     * How long one case of this kind needs before its assertions can be made.
     *
     * <p>Builds are time-sliced and Create assembles over ticks, so a harness declares its own
     * settle time rather than the driver guessing one that is wrong for everybody.
     *
     * <p><b>Only means anything for a {@link Staged} harness.</b> It was declared here from the
     * start, overridden by one harness, and <b>called by nobody</b> for its whole life — the driver
     * ran every case inside a single tick regardless. That is almost certainly why the machines
     * harness (removed with Create) never used its kinetic probe: a kinetic network
     * needs ticks to propagate speed from its source, so any rotation assertion made in the placing
     * tick reads zero, and a gate built on that would have called every working machine dead.
     */
    default int settleTicks() {
        return 0;
    }

    /**
     * <b>A harness whose claims cannot be made in the tick that places the artifact.</b>
     *
     * <p>Rotation propagates over ticks, Create's actuators self-assemble on the *next* tick after
     * they receive speed, and an item takes time to travel a belt. None of that can be observed by
     * code that places and asserts in one breath.
     *
     * <p>The driver runs this as <b>place every case, settle once, then assert every case</b> —
     * lifted from {@code ContraptionGameTest}, which already had to solve this: <i>"one settle for
     * all of them, then read every claim"</i>. That ordering is what keeps the settle affordable:
     * ninety machines that each need sixty ticks cost sixty ticks in total, not five thousand.
     *
     * <p>Which in turn is why {@link #place} hands back an opaque state object rather than the
     * harness keeping it in a field — the whole corpus is mid-flight at once.
     */
    interface Staged extends StressHarness {

        /**
         * Build the artifact and hand back whatever the assertion phase will need.
         *
         * <p>Must not assert anything that depends on time having passed. Must not throw.
         */
        Object place(Context ctx, WishCorpus.Case testCase);

        /**
         * Optionally put something INTO the artifact, once it has spun up.
         *
         * <p>Separate from {@link #place} because a machine cannot process what it is fed before it
         * is running, and separate from {@link #assertSettled} because the answer is not available
         * in the same tick either. "Rock in, gravel out" is a three-phase claim and pretending
         * otherwise is how it ends up untested — which is exactly what happened: the probe for it
         * existed and no corpus case used it.
         */
        default void feed(Context ctx, WishCorpus.Case testCase, Object state) {
        }

        /**
         * <b>Act again, once the machine has actually MADE something</b> — the fourth phase.
         *
         * <p>{@link #feed} happens the moment the machine is up, which is right for putting rock
         * into a crusher and impossible for anything that has to act on what the machine
         * <i>produced</i>. Measured on the pilot: a spout needs hundreds of ticks to charge and
         * fill one bucket, so a parcel that is supposed to carry that bucket cannot be posted in
         * {@code feed} — the bucket does not exist yet, and posting a fabricated one is the exact
         * fiction exists to remove.
         *
         * <p>So: place → settle → <b>feed</b> → {@link #stageTicks} → <b>stage</b> → {@link
         * #processTicks} → assert. Only cases that ask for it pay, the same rule as
         * {@code processTicks}.
         */
        default void stage(Context ctx, WishCorpus.Case testCase, Object state) {
        }

        /**
         * <b>How far east of its anchor this case builds</b>, in blocks. The runner keeps
         * that end inside the ring of chunks the server ticks; zero means "nowhere east of it".
         */
        default int reachEast(WishCorpus.Case testCase) {
            return 0;
        }

        /**
         * Ticks between {@link #feed} and {@link #stage} — how long this machine needs to make the
         * thing the staging step then acts on. Zero for everything that does not need it.
         */
        default int stageTicks(WishCorpus.Case testCase) {
            return 0;
        }

        /**
         * <b>Take the artifact back down once its claims have been read.</b>
         *
         * <p>Not tidiness — correctness. The staged runner reuses the same handful of anchors for
         * every batch, so without this each case is built into the wreckage of whichever case held
         * that slot before it, and its result depends on what ran earlier rather than on itself.
         * That is not hypothetical: a drain that measures 1000mB in a clean world reported an empty
         * tank here, with its pump turning and nothing visibly wrong, because a neighbour's
         * leftovers were still standing around it.
         *
         * <p>Leftover ITEMS matter just as much as leftover blocks: an item claim is a count, and a
         * bucket dropped by the previous tenant is indistinguishable from one this machine made.
         */
        default void cleanUp(Context ctx, WishCorpus.Case testCase, Object state) {
        }

        /**
         * Extra ticks needed AFTER feeding, before the claim can be made. Zero for most cases.
         *
         * <p>Per case rather than per harness, because it is expensive — a crusher takes hundreds
         * of ticks to turn cobblestone into gravel — and charging every machine in the corpus that
         * wait would make the gate too slow to run on every commit, which is the one property it
         * cannot lose.
         */
        default int processTicks(WishCorpus.Case testCase) {
            return 0;
        }

        /**
         * Make the claims, now that the world has ticked, and clean up.
         *
         * @param state exactly what {@link #place} returned, or null if placing failed
         */
        Outcome assertSettled(Context ctx, WishCorpus.Case testCase, Object state);

        /**
         * The one-shot path, kept so a {@code Staged} harness is still a {@link StressHarness}.
         *
         * <p>Deliberately NOT the real path: it asserts in the placing tick, which is exactly the
         * thing this interface exists to avoid. The driver always uses the two-phase pair; this
         * only runs if some future caller forgets, and it says so rather than quietly under-testing.
         */
        @Override
        default Outcome run(Context ctx, WishCorpus.Case testCase) {
            Object state = place(ctx, testCase);
            feed(ctx, testCase, state);
            return assertSettled(ctx, testCase, state);
        }
    }

    /** What the harness gets to work with. */
    record Context(ServerLevel level, BlockPos anchor) {
    }

    /**
     * What happened.
     *
     * @param problems   empty when the case passed; each entry is one broken claim
     * @param invariants which shared invariants were actually CHECKED — reported so a harness that
     *                   quietly stops checking something is visible, rather than looking like a
     *                   pass
     */
    record Outcome(List<String> problems, List<String> invariants, List<String> skipped) {

        public Outcome(List<String> problems, List<String> invariants) {
            this(problems, invariants, List.of());
        }

        public static Outcome pass(String... checked) {
            return new Outcome(List.of(), List.of(checked));
        }

        public boolean ok() {
            return problems.isEmpty();
        }

        /** Collect problems as you go, then finish. */
        public static final class Builder {
            private final List<String> problems = new ArrayList<>();
            private final List<String> checked = new ArrayList<>();
            private final List<String> skipped = new ArrayList<>();

            public Builder checked(String invariant) {
                checked.add(invariant);
                return this;
            }

            /**
             * Record a claim this run could NOT make, and why.
             *
             * <p>Exists because the alternative is a lie in one direction or the other. Machines
             * cases need Create installed; without it every {@code create:} block fails to parse
             * and nothing is placed. Reporting that as a pass would be a corpus that skips cases
             * while looking tested — the failure this whole package was written to stop. Reporting
             * it as a failure is what happens today, and it is arguably worse: a clean checkout
             * without Create shows eight red machines that look exactly like product bugs.
             *
             * <p>So it is neither. It is a skip that says so, is counted in the run report, and is
             * turned into a hard failure by {@code tools/ratchet.sh}, which already refuses to run
             * without the Create jar.
             */
            public Builder skip(String what) {
                skipped.add(what);
                return this;
            }

            public Builder problem(String what) {
                problems.add(what);
                return this;
            }

            /** Record the invariant as checked, and note a problem if it does not hold. */
            public Builder require(String invariant, boolean holds, String ifBroken) {
                checked.add(invariant);
                if (!holds) {
                    problems.add(invariant + ": " + ifBroken);
                }
                return this;
            }

            public Outcome build() {
                return new Outcome(List.copyOf(problems), List.copyOf(checked),
                        List.copyOf(skipped));
            }
        }
    }
}
