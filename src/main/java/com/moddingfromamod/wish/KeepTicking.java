package com.moddingfromamod.wish;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>An empty server keeps ticking while a wish is unsettled</b>.
 *
 * <p>Vanilla 1.21.2+ stops ticking a dedicated server whose player list has been empty for
 * {@code pause-when-empty-seconds} (default 60). Queued tasks still run, so an agent's tool calls
 * still land, but every tick handler goes quiet. A server may keep vanilla's default on purpose (an
 * empty server should rest), and two kinds of wish run with nobody in the player list:
 * the canary (its stand-in is a FakePlayer) and any wish whose child logged off mid-grant.
 *
 * <p>Measured on a dedicated server, 2026-10-25: {@code 03:29:26 Server empty for 60 seconds, pausing}, then
 * a canary at 03:48. {@code CameraSupervisor} is a tick handler, so it never saw the wish, never
 * started a camera, and {@code finish_building} waited its full 75 s for a photograph that could
 * not come — on 6 of 7 canaries in three days. The reveal, builds, creature animation
 * and every timer were frozen the same way, so the canary was not testing a ticking world at all.
 *
 * <p>So the pause window is 0 while {@code Marshal} holds any unsettled wish (queued, granting, or
 * ended for the child and still being photographed), and vanilla's value otherwise. When the last
 * wish settles, vanilla's own counter — which did not move while the window was 0 — pauses the
 * server again on the next tick. Asked by {@code PauseWhileEmptyMixin}, every tick; pure apart from
 * a log line on each change, so the rule is a unit test.
 *
 * <p><b>The world module's work too</b> (follow-up): an islands module pre-generates the next players'
 * terrain while nobody is on, and the pause froze it 60 s after every boot (2026-11-19:
 * {@code 00:21:03 Server empty for 60 seconds, pausing} with 3 cells pending, then the server slept on a stale
 * record). So the window is also 0 while the running {@code WorldModule} names a reason
 * ({@code WorldModule#keepTicking}), and vanilla's again the moment it stops naming one. A world with no module,
 * or a module with no such work, is untouched.
 */
public final class KeepTicking {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-keep-ticking");

    /** The reason a wish gives. */
    public static final String WISH = "a wish is unsettled";

    private static volatile String holding;

    private KeepTicking() {
    }

    /** The pause window to use: 0 while a wish is unsettled, else vanilla's. */
    public static int pauseSeconds(int vanillaSeconds, boolean wishUnsettled) {
        return wishUnsettled && vanillaSeconds > 0 ? 0 : vanillaSeconds;
    }

    /** {@link #pauseSeconds(int, boolean)}, saying once each time it starts or stops holding. */
    public static int pauseSeconds(int vanillaSeconds, BooleanSupplier wishUnsettled) {
        return pauseSecondsFor(vanillaSeconds, () -> wishUnsettled.getAsBoolean() ? WISH : null);
    }

    /** Why to hold: a wish first, else the world module's own reason (null: none). Pure. */
    public static String why(boolean wishUnsettled, String moduleWhy) {
        return wishUnsettled ? WISH : moduleWhy;
    }

    /**
     * The pause window to use, holding it at 0 while {@code why} names a reason; says once each time the reason
     * changes. {@code why} is asked only on a server that pauses at all.
     */
    public static int pauseSecondsFor(int vanillaSeconds, Supplier<String> why) {
        if (vanillaSeconds <= 0) {
            return vanillaSeconds;           // this server never pauses: nothing to hold
        }
        String reason = why.get();
        if (!Objects.equals(reason, holding)) {
            holding = reason;
            if (reason != null) {
                LOG.info("{}: an empty server keeps ticking (pause-when-empty held)", reason);
            } else {
                LOG.info("nothing holds the server: pause-when-empty applies again ({} s)", vanillaSeconds);
            }
        }
        return pauseSeconds(vanillaSeconds, reason != null);
    }
}
