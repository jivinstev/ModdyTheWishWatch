package com.moddingfromamod.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** every worker, on every model, gets every wish tool up front — from both launchers. */
class ToolExposureTest {

    @Test
    void toolSearchIsOff() {
        Map<String, String> env = new HashMap<>();
        ToolExposure.applyTo(env);
        assertEquals("false", env.get("ENABLE_TOOL_SEARCH"));
    }

    @Test
    void bothLaunchersApplyIt() throws Exception {
        // A rule tested only where it is DEFINED is not tested where it RUNS: both places that start a
        // wish worker must call it, or one of them launches Opus with its tools deferred again.
        for (String f : new String[]{"src/main/java/com/moddingfromamod/agent/AgentSession.java",
                "src/main/java/com/moddingfromamod/worker/WorkerMain.java"}) {
            assertTrue(Files.readString(Path.of(f)).contains("ToolExposure.applyTo(pb.environment())"),
                    f + " starts a worker without ToolExposure — its model would get deferred tools");
        }
    }
}
