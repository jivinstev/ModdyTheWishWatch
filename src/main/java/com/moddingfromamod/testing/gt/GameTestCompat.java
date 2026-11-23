package com.moddingfromamod.testing.gt;

import com.moddingfromamod.seam.GameTestFailure;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestException;

/**
 * The two places 1.21.8's test API wanted more than a {@code Component} (
 * tools/bump/gametest-codemod.py writes calls to these). Kept tiny on purpose; the bodies are
 * {@link GameTestFailure}'s, which the crash-guard mixin shares.
 */
public final class GameTestCompat {

    private GameTestCompat() {
    }

    /** What {@code new GameTestAssertException(String)} was. */
    public static GameTestAssertException assertion(String message) {
        return GameTestFailure.assertion(message);
    }

    /** What {@code testInfo.fail(Throwable)} took: a test exception passes through, anything else is wrapped. */
    public static GameTestException failure(Throwable t) {
        return GameTestFailure.of(t);
    }
}
