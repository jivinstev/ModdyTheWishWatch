package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The thread hop carries the worker's SLOT NAME as well as its session.
 *
 * <p>Found on a dedicated server, 2026-10-25: its launch shim logged {@code arm=subscription
 * model=muse-wonder-1.0} on 29 launches, yet all 20 settled records since 2026-10-15 named Spark
 * and carried no {@code workerName}. The settle runs inside {@code AgentBridge.onServer}, which
 * re-bound the session only, so {@code MuseShimState.servedSpecForCurrentWorker()} read a blank
 * slot on the server thread and fell back to the requested model.
 */
class AgentContextCarryTest {

    @TempDir
    Path state;

    private final ExecutorService runner = Executors.newSingleThreadExecutor();

    @AfterEach
    void tidy() throws Exception {
        runner.shutdownNow();
        runner.awaitTermination(5, TimeUnit.SECONDS);
        AgentContext.unbind();
        AgentContext.unbindWorkerName();
    }

    private <T> T onAnotherThread(Supplier<T> work) throws Exception {
        return runner.submit(work::get).get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("the slot name and the session both arrive on the thread that runs the work")
    void bothBindingsCross() throws Exception {
        AgentContext.bind("session-abc");
        AgentContext.bindWorkerName("wish-granter-2");
        Supplier<String> seen = AgentContext.carried(
                () -> AgentContext.workerId() + "|" + AgentContext.workerName());
        assertEquals("session-abc|wish-granter-2", onAnotherThread(seen),
                "the server thread must see the caller's slot as well as its session");
    }

    @Test
    @DisplayName("so the served model resolves there: a Wonder-served wish is stamped Wonder")
    void theServedModelResolvesAcrossTheHop() throws Exception {
        Files.createDirectories(state.resolve("served"));
        Files.writeString(state.resolve("served").resolve("wish-granter-2.json"),
                "{\"model\":\"muse:muse-wonder-1.0\",\"arm\":\"subscription\"}");
        AgentContext.bind("session-abc");
        AgentContext.bindWorkerName("wish-granter-2");
        Supplier<Optional<String>> served = AgentContext.carried(
                () -> MuseShimState.servedSpec(state, AgentContext.workerName()));
        assertEquals(Optional.of("muse:muse-wonder-1.0"), onAnotherThread(served),
                "with the slot blank this read empty and the settle fell back to 'Spark'");
    }

    @Test
    @DisplayName("the runner's own bindings come back afterwards (a pooled thread is not left as us)")
    void theRunnerIsRestored() throws Exception {
        onAnotherThread(() -> {
            AgentContext.bind("runner-own");
            AgentContext.bindWorkerName("wish-granter-9");
            return null;
        });
        AgentContext.bind("session-abc");
        AgentContext.bindWorkerName("wish-granter-2");
        onAnotherThread(AgentContext.carried(() -> null));
        assertEquals("runner-own|wish-granter-9",
                onAnotherThread(() -> AgentContext.workerId() + "|" + AgentContext.workerName()));
    }

    @Test
    @DisplayName("nothing bound: the work runs as it is, and the runner sees nothing of ours")
    void nothingBound() throws Exception {
        Supplier<String> work = () -> AgentContext.workerId() + "|" + AgentContext.workerName();
        assertTrue(AgentContext.carried(work) == work, "no binding, no wrapper");
        assertEquals("|", onAnotherThread(AgentContext.carried(work)));
    }

    @Test
    @DisplayName("AgentBridge.onServer hops through carried(), not a session-only re-bind")
    void theBridgeUsesIt() throws Exception {
        // onServer needs a live server, so the wiring is checked where it is written: the one hop
        // every server-side tool goes through must carry both bindings.
        String src = Files.readString(Path.of("src/main/java/com/moddingfromamod/agent/AgentBridge.java"));
        assertTrue(src.contains("AgentContext.carried(work)"),
                "AgentBridge.onServer must wrap its work in AgentContext.carried, or the slot name "
                        + "is lost on the server thread again and Wonder wishes settle as Spark");
    }
}
