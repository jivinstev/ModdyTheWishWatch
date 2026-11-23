package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.runtime.CaptureTimings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The one invariant that spans the whole stack: a tool must not be able to block for longer than
 * its caller will wait.</b>
 *
 * <p>Lives in this package because {@code AgentSession.MCP_CLIENT_TIMEOUT_MS} is package-private,
 * and the alternative — widening it so a test elsewhere could see it — would loosen production
 * visibility to serve a gate.
 *
 * <p>The failure it prevents is a misdiagnosis rather than a hang. A tool that outlasts the client's
 * timeout produces a TRANSPORT error, so a slow answer is reported as a broken bridge; the agent
 * then retries a tool that was working, and whoever reads the transcript later goes looking at the
 * MCP layer. Cheap to assert, and impossible to notice by reading either file alone.
 */
class ToolBlockBudgetTest {

    @Test
    @DisplayName("the longest a tool can block is far inside the MCP client's own timeout")
    void theSettleWaitFitsInsideTheClientTimeout() {
        long worst = CaptureTimings.maxToolBlockMs(AgentBridge.DEFAULT_TIMEOUT_MS);
        assertTrue(worst < AgentSession.MCP_CLIENT_TIMEOUT_MS,
                "a tool that can block for " + worst + " ms against a client timeout of "
                        + AgentSession.MCP_CLIENT_TIMEOUT_MS + " ms answers with a transport error "
                        + "instead of an answer");
        // And by a real margin, not by one millisecond: await_wish deliberately blocks for
        // ~110 s inside the same budget, so the settle wait must be a rounding error beside it
        // rather than something that competes for the same headroom.
        assertTrue(worst * 10 < AgentSession.MCP_CLIENT_TIMEOUT_MS,
                "the margin should be an order of magnitude, or the next thing that wants to wait "
                        + "has nowhere to go");
    }
}
