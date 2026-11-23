package com.moddingfromamod.testing.gt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import net.minecraft.gametest.framework.GameTestHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>A wish a GameTest put in flight ends with that test</b> — passed, failed or timed out.
 *
 * <p>{@code Marshal.global()} is a process-wide static, and {@code AgentContext.currentRun()} answers
 * "the single wish in flight" when nothing is bound. So ONE run a test leaves behind is read by every
 * later test that asks for the current wish with no worker of its own: the provenance publisher stamped
 * {@code ProvenanceWiringGameTest}'s manifest with <i>"make it daytime forever"</i>, the words
 * {@code OneEndingGameTest} submits — in a batch that ran minutes earlier. Batches are sequential, so
 * it was never a race inside a batch: {@code OneEndingGameTest} cleans up only on its success path, and
 * its 25-second wall-clock deadline (or any red assertion that fails the test some other way) left the
 * run WORKING for the rest of the server's life.
 *
 * <p>Fixing that one test fixes one leak; there are ~65 GameTests that submit to the global marshal and
 * every one has its own cleanup on its own path. So the adapter does it for all of them: it snapshots
 * the live runs before a test's body and, when THAT test ends, finishes any run its body added that is
 * still live — and says so by name, because a leak this catches is still a bug in the test that made it.
 *
 * <p>Attribution is exact for what a body submits synchronously: bodies run one at a time on the
 * server thread, so the runs that appear between "before" and "after" one body are that body's. A run a
 * test submits later (from a driver thread or a {@code runAfterDelay}) is not seen here and stays that
 * test's own job.
 */
public final class TestHygiene {
    private static final Logger LOG = LoggerFactory.getLogger("gt-hygiene");

    private TestHygiene() {
    }

    /** The ids of the runs live before a test's body. */
    public static Set<UUID> before() {
        Set<UUID> ids = new HashSet<>();
        for (WishRun r : Marshal.global().live()) {
            ids.add(r.wishId());
        }
        return ids;
    }

    /** After the body: whatever it put in flight is finished when the test ends, however it ends. */
    public static void after(GameTestHelper helper, String test, Set<UUID> before) {
        List<UUID> mine = new ArrayList<>();
        for (WishRun r : Marshal.global().live()) {
            if (!before.contains(r.wishId())) {
                mine.add(r.wishId());
            }
        }
        if (mine.isEmpty()) {
            return;
        }
        com.moddingfromamod.testing.MockPlayerNetwork.afterTest(helper, () -> {
            for (UUID id : mine) {
                WishRun r = Marshal.global().run(id);
                if (r != null && r.state().isLive()) {
                    LOG.warn("[gt-hygiene] {} ended with its wish '{}' still {} in Marshal.global(); finished it "
                            + "so no later test reads it as the wish in flight (#2240)", test, r.text(), r.state());
                    Marshal.global().finish(id);
                }
            }
        });
    }
}
