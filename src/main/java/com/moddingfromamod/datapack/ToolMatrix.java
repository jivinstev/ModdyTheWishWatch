package com.moddingfromamod.datapack;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * <b>Run a matrix of TOOL CALLS on a pool, from a GameTest body on the server thread.</b>
 *
 * <p>docs/GATE_PERFORMANCE.md (now "You can parallelise a COMPILE — and a TOOL CALL") recorded why
 * {@code ItemMatrixGameTest} could not copy {@code TriggerMatrixGameTest}'s pool: a
 * tool called off the server thread hops BACK onto it ({@code AgentBridge.onServer} →
 * {@code server.submit}) to activate its pack, and a test body blocked on the pool's futures is
 * holding the very thread that hop is queued for. Deadlock.
 *
 * <p>The way out is the one vanilla uses when it waits for a chunk on the main thread:
 * {@link MinecraftServer#managedBlock}. The body still BLOCKS — so the tick clock stays frozen and a
 * deadline in ticks still means what it meant serially (the trap TriggerMatrix's comment names) —
 * but while it waits it RUNS the server's queued tasks, which is exactly where the tools' hops are.
 * Compiles (in-process) run four at a time; activations run on the server thread, one at a
 * time, as they always did.
 *
 * <p>Measured: {@code everyEffectBuildsInEveryShape} was 203 s of Gate B serially (214 s for its
 * batch, at 0 ticks/s), the single largest cost in the suite; 87 s once the compiler's per-read
 * sleep was gone, and 34 s on this pool.
 *
 * <p>Results come back in SUBMISSION order, so a failure list reads the same as a serial run's.
 * With no server, or off the server thread, it runs the cells serially — the shortcut can never be
 * the reason a cell did not run. {@code -Dmoddingfromamod.matrixthreads=1} is the serial run.
 */
public final class ToolMatrix {

    /** Far beyond any honest matrix; a cell still running past it is reported, never waited on. */
    private static final long WALL_LIMIT_MS = 10 * 60 * 1000L;

    private ToolMatrix() {
    }

    /**
     * Run every cell; each returns {@code null} on success or a failure line.
     *
     * @return the failure lines, in cell order
     */
    public static List<String> run(MinecraftServer server, List<Supplier<String>> cells) {
        List<String> failures = new ArrayList<>();
        int threads = Integer.getInteger(ModdingFromAModMod.MODID + ".matrixthreads",
                Math.max(2, Runtime.getRuntime().availableProcessors()));
        if (server == null || !server.isSameThread() || threads <= 1) {
            for (Supplier<String> c : cells) {
                String bad = c.get();
                if (bad != null) {
                    failures.add(bad);
                }
            }
            return failures;
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "tool-matrix");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<String>> pending = new ArrayList<>();
            for (Supplier<String> c : cells) {
                pending.add(pool.submit(c::get));
            }
            long deadline = System.currentTimeMillis() + WALL_LIMIT_MS;
            // Serve the cells' hops onto this thread until every one has answered.
            server.managedBlock(() -> pending.stream().allMatch(Future::isDone)
                    || System.currentTimeMillis() > deadline);
            for (int i = 0; i < pending.size(); i++) {
                Future<String> f = pending.get(i);
                if (!f.isDone()) {
                    failures.add("cell " + i + " did not finish within " + (WALL_LIMIT_MS / 1000) + " s");
                    continue;
                }
                try {
                    String bad = f.get();
                    if (bad != null) {
                        failures.add(bad);
                    }
                } catch (ExecutionException e) {
                    failures.add("cell " + i + " threw " + e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failures.add("cell " + i + " was interrupted");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return failures;
    }
}
