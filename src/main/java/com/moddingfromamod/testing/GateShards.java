package com.moddingfromamod.testing;

/**
 * <b>The Gate B shards, and the ONE lever that can express them.</b>
 *
 * <p>Gate B is a single dedicated server that runs every {@code @GameTest} in one process, and
 * {@code GameTestRunner.runBatch(i)} starts batch {@code i+1} only once batch {@code i} has
 * finished — so the whole suite is a strictly sequential list of batches. The only way to make two
 * of them run at once is to boot two servers, each told to run a subset.
 *
 * <p><b>Why a NAMESPACE and not something more obvious.</b> NeoForge offers exactly one filter:
 * {@code neoforge.enabledGameTestNamespaces}, matched against each holder's namespace. The
 * tempting alternative — a {@code RegisterGameTestsEvent} listener that drops the methods this
 * shard does not want — cannot work: that event is posted at {@code GameTestHooks:49}, <i>before</i>
 * the {@code @GameTestHolder} classpath scan at {@code :51-55}, so a listener is handed an empty
 * set and can only ADD to it. There is no runtime filter to derive a shard from. Verified against
 * the 21.1.228 bytecode; do not re-derive it.
 *
 * <p>The property takes a <b>comma-separated list</b>, which is what keeps the default honest: a
 * plain {@code ./gradlew runGameTestServer} passes every namespace and still means "run
 * everything in one server".
 *
 * <h2>Adding a test</h2>
 *
 * <p><b>Do nothing.</b> {@link #DEFAULT} is the mod id, so a class with the ordinary
 * {@code @GameTestHolder(ModdingFromAModMod.MODID)} lands in the default shard and runs. That is
 * deliberate: a forgotten annotation must mean "runs somewhere", never "runs nowhere". A test that
 * silently stops running is the worst outcome available here, and it is invisible — the gate stays
 * green with a smaller number in it. The one other shard is {@link #CI}, which is hand-picked.
 *
 * <p><b>The {@code trains} and {@code create} shards, and the SMOKE set that promoted a few of
 * their tests into the default run, were removed with the Create mod (2026-10-15).</b> They
 * are parked, working, on branch {@code parked/create-trains-mc1.21.1}.
 *
 * <p>See docs/GATE_PERFORMANCE.md for the measurements and for what this cost to learn.
 */
public final class GateShards {

    /** Everything not claimed by another shard. The mod id, so an unannotated class still runs. */
    public static final String DEFAULT = "moddingfromamod";

    /**
     * <b>CI — the screen a pull request gets, and the only shard chosen for SPEED.</b>
     *
     * <p>This shard exists because <b>a GameTest run costs ~20 s of boot and about nothing per
     * test</b> (measured: 52 tests in 25 s wall), so the useful question for a PR
     * is not "which tests are cheap" — they nearly all are — but <b>"which handful covers every
     * mainline path and has never once been flaky"</b>.
     *
     * <p>Its members are chosen on two facts from {@code corpus/gate-history.jsonl}, not on taste:
     * <b>zero failures across 100+ runs each</b>, and one holder per wish kind the server serves —
     * {@code species}, {@code build}, {@code item}, {@code painting}, {@code template},
     * {@code datapack} — plus the plumbing every wish goes through: the MCP
     * protocol, the agent pool, concurrency, and the child-safety enforcement point.
     *
     * <p>⚠ <b>And this shard is ADDITIVE.</b> Every tier in {@code tools/gate-select.py} runs {@code ci} alongside whatever else it selects, so promoting
     * a class here removes it from no larger run. A shard that took tests AWAY from the merge gate
     * to make a PR faster would be trading the thing that catches bugs for the thing that reports
     * quickly.
     *
     * <p>⚠ <b>What it cannot see, stated so a green PR is not over-read:</b> the 340-case stress
     * corpus (its holder is one of only two demonstrably flaky tests in 264 runs) and every other
     * default-tier test. A green CI run is a SCREEN. The merge gate is still {@code gate-b.sh --all}.
     */
    public static final String CI = "moddingfromamod_ci";

    /** Every shard, in the order a sequential run would take them. */
    public static final java.util.List<String> ALL = java.util.List.of(DEFAULT, CI);

    private GateShards() {
    }
}
