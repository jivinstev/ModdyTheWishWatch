package com.moddingfromamod.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * smoke: after a Muse → Claude switch in one session every Claude worker logged "Workspace not trusted" and the
 * Magician check said only "never claimed after 128s". The workspace is now trusted at every Claude launch (not only
 * when the pool STARTED on Claude), and the check's failure names the refusal it finds in a worker's newest output.
 */
class WorkerTrustRefusalTest {

    @Test
    void theRefusalIsFoundInAWorkersOutput() {
        assertNull(AgentSession.trustRefusal(null));
        assertNull(AgentSession.trustRefusal("session_id abc\nready\n"));
        assertEquals("Workspace not trusted. Run `claude` in /x once and accept the trust prompt",
                AgentSession.trustRefusal("boot\nError: Workspace not trusted. Run `claude` in /x once and accept "
                        + "the trust prompt\n"));
    }

    @Test
    void everyClaudeLaunchEnsuresTheTrust() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/moddingfromamod/agent/AgentSession.java"));
        int launch = src.indexOf("// ⚠ ONE BUILDER, TWO CALLERS (HO-6).");
        int trust = src.lastIndexOf("trustWorkspace(configFile.getParent());", launch);
        int startOne = src.indexOf("private static void startOne(");
        assertTrue(launch > 0 && trust > startOne,
                "startOne trusts the workers' directory before every Claude Code launch, not only at a Claude pool start");
    }
}
