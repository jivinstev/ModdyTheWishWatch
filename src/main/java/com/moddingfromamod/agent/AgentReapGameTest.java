package com.moddingfromamod.agent;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.IdlePolicy;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B step 2 — reaping the wish pool when nobody is playing.
 *
 * <h2>Why this exists beside {@code IdlePolicyTest} rather than instead of it</h2>
 *
 * <p>{@link IdlePolicy} is pure and its rule is fully covered by unit tests. That is exactly the
 * arrangement this repo keeps getting burned by: <b>a rule tested only where it is DEFINED is not
 * tested where it RUNS.</b> an earlier issue's melter feed gate was green for months while hand-dropping past
 * the tool the agent actually calls, and an earlier issue's note rule had 14 green unit tests before the first
 * one that drove the real code went red immediately and exposed — two guards whose enabling
 * condition was false forever, so neither had ever fired in a real session.
 *
 * <p>So these drive {@link AgentSession#wouldReapNow()} itself: the real presence clock, the real
 * {@link IdlePolicy} call, and the real {@link AgentSession#reapIfNobodyIsPlaying()} entry point the
 * supervisor calls every three seconds. The two INPUTS are substituted (see {@link #withPool}); that
 * they are wired to the real server and the real marshal is asserted separately, by
 * {@link #theRealPlayerCountIsReadableOnARealServer} and {@link #theRealBusyCountIsReadable}.
 *
 * <p><b>The first version of this class was vacuous and an A/B is what caught it</b>, which is the
 * reason that split exists. It read {@code Marshal.global().working()} live; a concurrent test in
 * the same batch holding a wish vetoed the reap, so an {@code assertFalse} passed for the wrong
 * reason. Deliberately breaking the presence clock produced {@code All 401 required tests passed}.
 * A green test is not evidence until you have watched it go red.
 *
 * <h2>What a gate cannot reach, said out loud</h2>
 *
 * <p>A gate must never start a real agent ({@code AgentSessionGameTest} exists to guarantee it), so
 * the pool is always empty here and the {@code claude stop} half of the reap cannot be exercised.
 * That is why the decision was split out of the action: this proves everything up to the moment of
 * stopping, and {@link AgentSession#stop()} — the thing it then calls — is already driven by
 * {@code AgentSessionGameTest.stoppingIsSafeWithAPoolOfAnySize}.
 *
 * <p>Both substitutions are restored in a {@code finally}: GameTests in the same batch run
 * concurrently and share every static, so a test that left one pointing at a fixture would make
 * another test's pool behaviour depend on the order they happened to run in.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class AgentReapGameTest {

    /** Long enough ago that the absence has outlasted the derived break-even. */
    private static long wellPastTheGrace() {
        return System.currentTimeMillis() - IdlePolicy.GRACE_MS - 60_000L;
    }

    /**
     * Run {@code body} with the two inputs the reaper reads substituted.
     *
     * <p>⚠ <b>{@code busy} is substituted, and that is not tidiness — it is the whole reason these
     * tests mean anything.</b> Reading the real {@code Marshal.global().working()} here made every
     * assertion below pass regardless of the code under it: GameTests in one batch run concurrently
     * and share every static, so a neighbour holding a live wish makes the busy count non-zero,
     * which vetoes the reap, which satisfies an {@code assertFalse} for entirely the wrong reason.
     * Proven by A/B — the presence clock was deliberately broken and Gate B still reported
     * {@code All 401 required tests passed}. The wiring to the real marshal is asserted separately
     * by {@link #theRealBusyCountIsReadable}.
     */
    private static void withPool(int players, int busy, Runnable body) {
        var priorPlayers = AgentSession.playerCount;
        var priorBusy = AgentSession.workersHoldingAWish;
        try {
            AgentSession.playerCount = () -> players;
            AgentSession.workersHoldingAWish = () -> busy;
            // Somebody touched Moddy just now, unless a test says otherwise — so the ONLINE rule
            // (step 4) stays out of the tests about the empty-world one.
            AgentSession.lastActivityForTest(System.currentTimeMillis());
            body.run();
        } finally {
            AgentSession.playerCount = priorPlayers;
            AgentSession.workersHoldingAWish = priorBusy;
            AgentSession.lastPlayersSeenForTest(System.currentTimeMillis());
            AgentSession.lastActivityForTest(System.currentTimeMillis());
        }
    }

    private static void withPlayers(int n, Runnable body) {
        withPool(n, 0, body);
    }

    /**
     * The busy signal really is the marshal's, not a stub left behind by a test.
     *
     * <p>The counterpart to substituting it above: a default that had drifted away from
     * {@code Marshal.working()} would make the veto silently inert in a real game, and every test
     * here would still be green because they all supply their own.
     */
    @GameTest(template = "empty_test")
    public static void theRealBusyCountIsReadable(GameTestHelper helper) {
        int n = AgentSession.workersHoldingAWish.getAsInt();
        helper.assertTrue(n >= 0, Component.literal("the reaper could not read how many workers hold a wish"));
        helper.assertTrue(n == com.moddingfromamod.marshal.Marshal.global().working().size(),
                Component.literal("the default busy supplier has drifted from the marshal, so the 'never reap a "
                        + "wish in flight' veto would be reading the wrong thing in a real game"));
        helper.succeed();
    }

    /**
     * <b>A WISH IN FLIGHT VETOES THE REAP</b>, even in a world that has been empty for days.
     *
     * <p>A player can close the laptop mid-grant. The artifact is already paid for and is worth
     * finishing and persisting for when they come back; reaping there throws the expensive thing
     * away to save the cheap one. Untestable in a gate until the busy count became substitutable.
     */
    @GameTest(template = "empty_test")
    public static void aWishBeingGrantedIsNeverReapedAway(GameTestHelper helper) {
        withPool(0, 1, () -> {
            AgentSession.lastPlayersSeenForTest(wellPastTheGrace());
            helper.assertFalse(AgentSession.wouldReapNow(),
                    Component.literal("a wish is still being granted — stopping the pool now would throw away work "
                            + "the child has already waited for"));
        });
        // ...and the same world IS reaped once it finishes, or a wish that ended badly would park
        // the pool forever, which is the cost this saving exists to remove.
        withPool(0, 0, () -> {
            AgentSession.lastPlayersSeenForTest(wellPastTheGrace());
            helper.assertTrue(AgentSession.wouldReapNow(), Component.literal("nothing in flight now"));
        });
        helper.succeed();
    }

    /**
     * <b>The production signal is readable at all.</b>
     *
     * <p>The cheapest way for this whole feature to be silently dead: {@code livePlayerCount}
     * throwing, or never being reachable, so the supervisor's reap branch is skipped forever and
     * the pool polls all night exactly as before. A GameTest server IS a real dedicated server, so
     * it can simply ask.
     */
    @GameTest(template = "empty_test")
    public static void theRealPlayerCountIsReadableOnARealServer(GameTestHelper helper) {
        int n = AgentSession.livePlayerCount();
        // 0 (a gate server has no players) or more. -1 would mean there was no server to ask,
        // which cannot be true from inside one — and would be the "fail safe, never reap" answer.
        helper.assertTrue(n >= 0,
                Component.literal("the reaper could not read the player count from a live server (got " + n + "), so "
                        + "#364 would never fire in a real game either"));
        helper.succeed();
    }

    /** A pool that has just booted has not been abandoned. */
    @GameTest(template = "empty_test")
    public static void aFreshlyStartedPoolIsNotReaped(GameTestHelper helper) {
        withPlayers(0, () -> {
            AgentSession.lastPlayersSeenForTest(System.currentTimeMillis());
            helper.assertFalse(AgentSession.wouldReapNow(),
                    Component.literal("a world that emptied a moment ago must not be reaped — a relog would then "
                            + "pay for a boot, which is the whole reason there is a grace at all"));
        });
        helper.succeed();
    }

    /** The case the issue is about: a world left open with nobody in it. */
    @GameTest(template = "empty_test")
    public static void anEmptyWorldPastTheBreakEvenIsReaped(GameTestHelper helper) {
        withPlayers(0, () -> {
            AgentSession.lastPlayersSeenForTest(wellPastTheGrace());
            helper.assertTrue(AgentSession.wouldReapNow(),
                    Component.literal("nobody online, nothing in flight, and past the break-even — this is the "
                            + "$96-over-72-hours case #364 exists for"));
        });
        helper.succeed();
    }

    /**
     * <b>THE PUBLISHED LEVEL FALLS TO ZERO, and it did not until HO-6's second pass.</b>
     *
     * <p>{@code HealthService.desiredWorkers} passed {@code shouldReap = false} as a literal, so
     * {@code moddy_agent_desired_workers} — the number the worker container converges on — could
     * never drop for idleness. The pool itself reaped correctly; the gauge said it should not
     * have. An operator watching that series through three empty days would have seen the exact
     * ~$96 case {@link IdlePolicy} exists for, reported as healthy.
     *
     * <p>⚠ This is the DIFFERENCE between the two, which is the only thing worth asserting:
     * {@code IdlePolicyTest} already proves the rule and {@code PoolPlanTest} already proves that
     * {@code desiredWorkers} returns 0 when told to reap. Neither could see that nobody was
     * telling it. That is this repo's most expensive recurring shape — a rule tested where it is
     * DEFINED and not where it RUNS — so it is pinned against the real snapshot, in the game.
     */
    @GameTest(template = "empty_test")
    public static void theSNAPSHOTsDesiredWorkersFallsToZeroWhenTheWorldEmpties(GameTestHelper helper) {
        withPool(0, 0, () -> {
            AgentSession.lastPlayersSeenForTest(wellPastTheGrace());
            helper.assertTrue(AgentSession.reapWanted(),
                    Component.literal("reapWanted() is the READER's form of the same question and must agree with "
                            + "wouldReapNow() on the case the issue is about"));
            int desired = com.moddingfromamod.marshal.PoolPlan.desiredWorkers(
                    2, 2, 0, com.moddingfromamod.marshal.WishSlots.slots(),
                    AgentSession.reapWanted(),
                    IdlePolicy.shouldBoot(0, true, 0));
            helper.assertTrue(desired == 0,
                    Component.literal("an empty world past the break-even must publish desiredWorkers=0, not "
                            + desired + " — the runner converges on this number and a level that "
                            + "cannot fall is a pool that is never told to stop"));
        });
        helper.succeed();
    }

    /**
     * <b>reapWanted() DOES NOT KEEP THE CLOCK, and that is why it is a separate method.</b>
     *
     * <p>{@code wouldReapNow} resets {@code lastPlayersSeenMs} when it sees anybody — correct for
     * a supervisor on a fixed tick, and wrong for a {@code /metrics} scrape that lands whenever
     * Prometheus feels like it, because the absence would then be measured from the last SCRAPE.
     * A reader that quietly restarted the grace period every fifteen seconds would mean an empty
     * world is never reaped at all, which is the bug this whole row is about, reintroduced through
     * the instrument that was supposed to reveal it.
     */
    @GameTest(template = "empty_test")
    public static void theReadersFormLeavesThePresenceClockAlone(GameTestHelper helper) {
        withPool(1, 0, () -> {
            AgentSession.lastPlayersSeenForTest(wellPastTheGrace());
            // A player is online, so both forms answer "no" — but only one of them is allowed to
            // move the clock while doing it.
            helper.assertFalse(AgentSession.reapWanted(), Component.literal("somebody is online"));
            AgentSession.playerCount = () -> 0;
            helper.assertTrue(AgentSession.reapWanted(),
                    Component.literal("the clock still says the world emptied long ago, so the moment the last "
                            + "player leaves the answer is yes — if reapWanted() had reset it, "
                            + "this would be false and the grace would restart on every scrape"));
        });
        helper.succeed();
    }

    /**
     * <b>A PLAYER PRESENT IS NEVER REAPED</b>, and the presence clock really is reset by seeing
     * them.
     *
     * <p>The second half is the one a pure test cannot check: {@code wouldReapNow} keeps the clock
     * as a side effect, so if that assignment were dropped a world someone had been playing in for
     * hours would be reaped the instant they stepped away.
     */
    @GameTest(template = "empty_test")
    public static void aPlayerOnlineIsNeverReapedAndResetsTheClock(GameTestHelper helper) {
        var prior = AgentSession.playerCount;
        var priorBusy = AgentSession.workersHoldingAWish;
        try {
            AgentSession.workersHoldingAWish = () -> 0;   // see withPool: without this, vacuous
            AgentSession.lastPlayersSeenForTest(wellPastTheGrace());
            AgentSession.lastActivityForTest(System.currentTimeMillis());
            AgentSession.playerCount = () -> 1;
            helper.assertFalse(AgentSession.wouldReapNow(),
                    Component.literal("somebody is playing and touched Moddy just now; the pool is theirs"));

            // They step away RIGHT NOW. The tick above must have reset the clock, so this one is
            // inside the grace even though the stamp before it was hours old.
            AgentSession.playerCount = () -> 0;
            helper.assertFalse(AgentSession.wouldReapNow(),
                    Component.literal("seeing a player did not reset the presence clock — a child who steps away for "
                            + "a moment would come back to no wish-granter"));
        } finally {
            AgentSession.playerCount = prior;
            AgentSession.workersHoldingAWish = priorBusy;
            AgentSession.lastPlayersSeenForTest(System.currentTimeMillis());
            AgentSession.lastActivityForTest(System.currentTimeMillis());
        }
        helper.succeed();
    }

    // ── step 4: quiet while they play, woken by the watch ─────────────────────────

    /** Long enough ago that nobody touching Moddy has outlasted the online limit. */
    private static long wellPastTheOnlineLimit() {
        return System.currentTimeMillis() - IdlePolicy.ONLINE_IDLE_MS - 60_000L;
    }

    /**
     * <b>Children online, nobody touching Moddy for twenty minutes: the pool is reaped</b> — and
     * the published level falls with it, and does not bounce straight back.
     *
     * <p>The second half is the one only this gate can see. {@code shouldBoot} feeds the same
     * {@code desiredWorkers} the worker container converges on (HO-6); had it stayed "somebody is
     * online and nothing runs", the runner would relaunch the worker this rule just stopped, on
     * the next scrape.
     */
    @GameTest(template = "empty_test")
    public static void onlineButIgnoredPastTheLimitIsReaped(GameTestHelper helper) {
        withPool(1, 0, () -> {
            AgentSession.lastActivityForTest(wellPastTheOnlineLimit());
            helper.assertTrue(AgentSession.wouldReapNow(),
                    Component.literal("a child is online but nobody has touched Moddy for longer than the chosen "
                            + "twenty minutes — the supervisor must stop the pool"));
            helper.assertTrue(AgentSession.reapWanted(), Component.literal("and the reader's form must agree"));
            int running = 1;
            int desired = com.moddingfromamod.marshal.PoolPlan.desiredWorkers(
                    running, running, 0, com.moddingfromamod.marshal.WishSlots.slots(),
                    AgentSession.reapWanted(),
                    IdlePolicy.shouldBoot(1, running > 0, AgentSession.msSinceActivity()));
            helper.assertTrue(desired == 0, Component.literal("desiredWorkers must fall to 0, got " + desired));
            int afterReap = com.moddingfromamod.marshal.PoolPlan.desiredWorkers(
                    0, 0, 0, com.moddingfromamod.marshal.WishSlots.slots(),
                    AgentSession.reapWanted(),
                    IdlePolicy.shouldBoot(1, false, AgentSession.msSinceActivity()));
            helper.assertTrue(afterReap == 0,
                    Component.literal("with the pool stopped and still nobody touching Moddy, the level must STAY 0 "
                            + "(got " + afterReap + ") — or the runner relaunches it every scrape"));
        });
        helper.succeed();
    }

    /** Nineteen quiet minutes is not twenty. */
    @GameTest(template = "empty_test")
    public static void onlineAndRecentlyTouchedIsNotReaped(GameTestHelper helper) {
        withPool(1, 0, () -> {
            AgentSession.lastActivityForTest(
                    System.currentTimeMillis() - IdlePolicy.ONLINE_IDLE_MS + 60_000L);
            helper.assertFalse(AgentSession.wouldReapNow(), Component.literal("a minute inside the limit"));
        });
        helper.succeed();
    }

    /**
     * <b>A grant in progress is activity</b> — never reaped, and it RESETS the clock, so the pool
     * is not stopped the instant a long wish finishes.
     */
    @GameTest(template = "empty_test")
    public static void aGrantWhileTheyPlayIsActivity(GameTestHelper helper) {
        withPool(1, 1, () -> {
            AgentSession.lastActivityForTest(wellPastTheOnlineLimit());
            helper.assertFalse(AgentSession.wouldReapNow(), Component.literal("a wish is being granted right now"));
        });
        withPool(1, 0, () -> {
            // withPool resets the clock itself, so this re-states the case by hand: the stamp is
            // old, a busy tick is seen, then the wish finishes.
            AgentSession.lastActivityForTest(wellPastTheOnlineLimit());
            var busy = AgentSession.workersHoldingAWish;
            AgentSession.workersHoldingAWish = () -> 1;
            AgentSession.wouldReapNow();
            AgentSession.workersHoldingAWish = busy;
            helper.assertFalse(AgentSession.wouldReapNow(),
                    Component.literal("the grant that just ended must count as the last touch — without it a "
                            + "40-minute build would be followed at once by a reap"));
        });
        helper.succeed();
    }

    /**
     * <b>THE WAKE, driven through the real door</b>: a watch panel opening resets the clock.
     *
     * <p>{@code WatchMenu.open} is where a stock client's right-click, a modded client's and a bare
     * {@code /wish} all arrive, so that is what is called — not {@code AgentSession.wake} directly,
     * which would prove the wake where it is DEFINED and not where it RUNS.
     */
    @GameTest(template = "empty_test")
    public static void openingTheWatchWakesThePool(GameTestHelper helper) {
        com.moddingfromamod.testing.MockPlayerNetwork.clearStaleMocks(helper);
        net.minecraft.server.level.ServerPlayer p =
                com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper);
        withPool(1, 0, () -> {
            AgentSession.lastActivityForTest(wellPastTheOnlineLimit());
            helper.assertTrue(AgentSession.wouldReapNow(), Component.literal("precondition: ignored past the limit"));
            com.moddingfromamod.testing.MockPlayerNetwork.withoutChannels(p,
                    () -> com.moddingfromamod.wish.WatchMenu.open(p));
            helper.assertFalse(AgentSession.wouldReapNow(),
                    Component.literal("opening the watch is the earliest sign of a wish and must count as a touch"));
            helper.assertTrue(IdlePolicy.shouldBoot(1, false, AgentSession.msSinceActivity()),
                    Component.literal("...and a reaped pool is now WANTED again, which is how a containerised pool "
                            + "wakes too"));
            helper.assertFalse(AgentSession.isRunning(),
                    Component.literal("a gate must never launch a real worker, and the wake did not"));
        });
        helper.succeed();
    }

    /** No gate wish is ever told Moddy is waking up: the agent does not run here. */
    @GameTest(template = "empty_test")
    public static void aGateWishIsNeverToldModdyIsWaking(GameTestHelper helper) {
        helper.assertFalse(AgentSession.wakeForWish(),
                Component.literal("wakeForWish must answer false wherever the agent may not run"));
        helper.assertFalse(AgentSession.isRunning(), Component.literal("and must not have started anything"));
        helper.succeed();
    }

    /**
     * "There is no server to ask" is not "the world is empty".
     *
     * <p>{@code livePlayerCount} answers -1 rather than 0 for exactly this, and the rule tests
     * {@code != 0} rather than {@code > 0} so the unknown answer lands on the money side. The two
     * failure modes are not symmetric: an unreaped pool costs dollars, a wrongly reaped one costs
     * a child their wish.
     */
    @GameTest(template = "empty_test")
    public static void anUnknownPlayerCountNeverReaps(GameTestHelper helper) {
        withPlayers(-1, () -> {
            AgentSession.lastPlayersSeenForTest(wellPastTheGrace());
            helper.assertFalse(AgentSession.wouldReapNow(),
                    Component.literal("a player count of -1 means 'could not ask', which must never reap"));
        });
        helper.succeed();
    }

    /**
     * The supervisor calls this every three seconds forever, so it must be a cheap safe no-op when
     * there is nothing to stop — and must not start anything either.
     */
    @GameTest(template = "empty_test")
    public static void reapingWithNoPoolIsASafeNoOp(GameTestHelper helper) {
        withPlayers(0, () -> {
            AgentSession.lastPlayersSeenForTest(wellPastTheGrace());
            helper.assertFalse(AgentSession.reapIfNobodyIsPlaying(),
                    Component.literal("there is no pool to stop, so nothing was stopped"));
            helper.assertFalse(AgentSession.isRunning(), Component.literal("and nothing was started either"));
        });
        helper.succeed();
    }

    /**
     * The grace is DERIVED from two measured costs, and this is the gate's copy of that claim.
     *
     * <p>Duplicated from {@code IdlePolicyTest} on purpose: this one is what a person reading Gate
     * B's output sees, and the number is the only thing standing between "reap an abandoned world"
     * and "take a granter away from someone who is still there".
     */
    @GameTest(template = "empty_test")
    public static void theGraceIsTheMeasuredBreakEven(GameTestHelper helper) {
        long expected = Math.round(
                IdlePolicy.REBOOT_DOLLARS / IdlePolicy.IDLE_DOLLARS_PER_HOUR * 3_600_000.0);
        helper.assertTrue(IdlePolicy.GRACE_MS == expected,
                Component.literal("GRACE_MS must be the break-even of the measured reboot and idle costs, not a "
                        + "number somebody liked the look of (P9)"));
        helper.succeed();
    }
}
