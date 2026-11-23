package com.moddingfromamod.seam;

import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestException;
import net.minecraft.network.chat.Component;

/**
 * What a GameTest failure is made of, for the two places shipped code builds one: {@code GameTestCrashGuardMixin}
 * (a mixin the mod always carries, which only ever runs in a test run) and the harness's own
 * {@code testing/gt/GameTestCompat}, which delegates here. It lives outside {@code testing} because that package
 * never ships and a mixin in the jar must not reach it.
 */
public final class GameTestFailure {

    private GameTestFailure() {
    }

    /**
     * What {@code new GameTestAssertException(String)} was. The tick is the runner's to report (it prints "on tick N"
     * itself), so 0 here: no caller ever knew it.
     */
    public static GameTestAssertException assertion(String message) {
        return new GameTestAssertException(Component.literal(message), 0);
    }

    /** What {@code testInfo.fail(Throwable)} took: a test exception passes through, anything else is wrapped. */
    public static GameTestException of(Throwable t) {
        if (t instanceof GameTestException g) {
            return g;
        }
        // Not a verdict but a BUG in the test or the code: keep its stack in the log, or the failure line is one
        // exception name and nothing to go on (measured: "ArrayIndexOutOfBoundsException: Index 0 out of bounds for
        // length 0", no trace).
        org.slf4j.LoggerFactory.getLogger("moddingfromamod/gametest").error("a test threw (not a verdict)", t);
        GameTestAssertException e = assertion(t.getClass().getSimpleName() + ": " + t.getMessage());
        e.initCause(t);
        return e;
    }
}
