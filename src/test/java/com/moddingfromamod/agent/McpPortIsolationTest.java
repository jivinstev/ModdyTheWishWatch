package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * <b>Test runs and live play must never share the MCP port.</b>
 *
 * <p>The incident this pins: a Gate-D client test and a child's live game both defaulted to
 * {@link McpHttpServer#DEFAULT_PORT}, raced for it, and the child's wish reached the wrong agent (or
 * none). {@link McpHttpServer#defaultPortFor} is the single seam that decides the default port, so a
 * test run always gets an ephemeral (OS-assigned) port while a live session keeps 25585 to itself —
 * which also means any number of concurrent test JVMs each get their own free port with no clash.
 */
class McpPortIsolationTest {

    @Test
    void livePlayDefaultsToTheWellKnownPort() {
        assertEquals(McpHttpServer.DEFAULT_PORT, McpHttpServer.defaultPortFor(false),
                "a real play session must keep the fixed, documented port");
    }

    @Test
    void aTestRunDefaultsToAnEphemeralPortNotThePlayPort() {
        // 0 == "let the OS pick a free one" — see McpHttpServer.start's port==0 path. This is what
        // makes concurrent test runs collision-free (each gets its own socket) and keeps every test
        // off the live-game port.
        assertEquals(McpHttpServer.EPHEMERAL_PORT, McpHttpServer.defaultPortFor(true),
                "a test run must default to an OS-assigned ephemeral port");
        assertNotEquals(McpHttpServer.defaultPortFor(false), McpHttpServer.defaultPortFor(true),
                "the whole point: test and live can never default to the same socket");
    }
}
