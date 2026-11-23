package com.moddingfromamod.testing;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Three tests that do nothing, and that is the entire point —.</b>
 *
 * <p>The bug being guarded against is that adding a GameTest broke a <i>different, unrelated</i>
 * one. Three new tests were added to this project and the crusher test began failing every single
 * run — not intermittently, deterministically — because the tests built into the world at offsets
 * from their own plot, GameTest hands out plots in a row, and a build large enough spilled out of
 * its own plot and into its neighbour's. The crusher was not broken. Something had been dropped on
 * top of it.
 *
 * <p>That failure mode is vicious because the test that fails is never the test that caused it, and
 * the diff that caused it does not touch the file that fails. It cost days of chasing a "flaky
 * crusher" that was neither flaky nor a crusher problem.
 *
 * <p>So these three occupy plots and assert nothing. If the region scheme in {@link TestPlots} is
 * right, every real test builds far away in its own lane and three extra plots change nothing. If
 * it regresses — if some test goes back to building at a plot-relative offset — these push the
 * plot row along and something else in the suite goes red. An empty test cannot fail on its own
 * merits, so any failure in a run containing them is a real collision.
 *
 * <p>They cost roughly a second in total. Do not delete them because they look pointless; looking
 * pointless is the job.
 */
@GameTestHolder("moddingfromamod")
public class PlotCanaryGameTest {

    @GameTest(template = "empty_test", timeoutTicks = 20)
    public void plotCanaryOne(GameTestHelper helper) {
        helper.succeed();
    }

    @GameTest(template = "empty_test", timeoutTicks = 20)
    public void plotCanaryTwo(GameTestHelper helper) {
        helper.succeed();
    }

    @GameTest(template = "empty_test", timeoutTicks = 20)
    public void plotCanaryThree(GameTestHelper helper) {
        helper.succeed();
    }
}
