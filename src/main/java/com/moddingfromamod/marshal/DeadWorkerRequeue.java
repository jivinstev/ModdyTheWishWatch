package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * <b>Which wishes go back to the queue because their worker died of the account, not the wish</b>
 *.
 *
 * <p>On a Muse Code subscription the window can run out mid-wish: the worker's next model call is
 * answered 429 and it exits. Before this, nothing noticed until the no-progress watchdog
 * fired five minutes later and settled the wish {@code TIMED_OUT} ("Moddy fell asleep"), because
 * a dead worker that holds a wish is not "deaf" ({@code WorkerRosterTest.aLongWishIsNotDeafness}).
 * The shim records such a death; this decides which held wishes it applies to, and puts them back
 * with {@link Marshal#requeueAfterDeath} so the next (fallback) worker re-claims them.
 *
 * <p>A death applies only to a wish claimed BEFORE it: a slot name is reused by the replacement
 * worker, and an old death must never yank a wish from the live worker now holding it.
 *
 * <p>Pure: the roster lookup and the shim's death record arrive as functions. The caller does the
 * Minecraft half — the book back to {@code PENDING}, the worker recycled, the queue offered the wish.
 */
public final class DeadWorkerRequeue {

    /** A death as this class needs it: when, and why (for the log line). */
    public record Death(String reason, long atMs) {
    }

    /** One wish put back, with the slot whose death caused it. */
    public record Requeued(WishRun run, String mcpId, String worker, String reason) {
    }

    private DeadWorkerRequeue() {
    }

    /**
     * @param nameOfMcp the pool slot name for an MCP session id ({@code WorkerRoster.nameOfSession})
     * @param deathOf   the shim's recorded death for a slot name, if any
     */
    public static List<Requeued> sweep(Marshal marshal, Function<String, String> nameOfMcp,
            Function<String, Optional<Death>> deathOf) {
        List<Requeued> out = new ArrayList<>();
        for (WishRun run : marshal.live()) {
            String mcp = run.workerId();
            long claimed = run.assignedMs();
            if (mcp == null || claimed < 0) {
                continue;
            }
            String worker = nameOfMcp.apply(mcp);
            if (worker == null) {
                continue;
            }
            Optional<Death> d = deathOf.apply(worker);
            if (d.isEmpty() || d.get().atMs() < claimed) {
                continue;
            }
            WishRun back = marshal.requeueAfterDeath(mcp);
            if (back != null) {
                out.add(new Requeued(back, mcp, worker, d.get().reason()));
            }
        }
        return out;
    }
}
