package com.moddingfromamod.testing.gt;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <b>A Gate B test</b>. Vanilla 1.21.5+ has no {@code @GameTest}: tests are registry entries.
 * This is the same annotation the suite always used — {@code template}, {@code batch},
 * {@code timeoutTicks} were the only settings any of its 1274 uses set — and {@link GameTestAdapter}
 * registers each one. A test needs nothing else (docs/VERSION_BUMP_1218.md §7).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GameTest {
    /** The structure, in the holder's namespace ({@code empty_test}). */
    String template();

    /** Tests in one batch run together; batches run one after another (an environment each). */
    String batch() default "defaultBatch";

    int timeoutTicks() default 100;

    boolean required() default true;

    int attempts() default 1;

    /**
     * False runs this test's whole BATCH with {@code randomTickSpeed 0}. Since 1.21.5 a force-loaded
     * chunk random-ticks with no player near it (1.21.4 skipped it), so grass under a placed block dies
     * to dirt mid-test; a test that checks the world comes back exactly as it was asks for this.
     */
    boolean randomTicks() default true;

    /**
     * True leaves the plot with no barrier ROOF. Since 1.21.5 the roof sits directly on the structure
     * (9 high for {@code empty_test}), so a test that leaps a big mount six blocks hits it at four.
     */
    boolean skyAccess() default false;
}
