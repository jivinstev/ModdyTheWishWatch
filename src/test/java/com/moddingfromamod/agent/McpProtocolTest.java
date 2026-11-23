package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Gate A for the MCP surface. Because {@link McpProtocol} is a pure function of headers+body, the
 * entire protocol — status codes, sessions, error shapes — is verified here in milliseconds, with no
 * Minecraft and no sockets. What's left for the runtime gates is only the adapter and the threading.
 */
class McpProtocolTest {

    private McpProtocol protocol;
    private ToolRegistry tools;

    /** A stand-in tool so tools/list and tools/call can be exercised without the game. */
    private static McpTool fakeTool(String name, ToolResult result) {
        return new McpTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "a fake tool for tests"; }
            @Override public JsonObject inputSchema() {
                return Schemas.prop(Schemas.object(), "thing", "string", "a thing");
            }
            @Override public ToolResult call(JsonObject args) { return result; }
        };
    }

    @BeforeEach
    void setUp() {
        tools = new ToolRegistry()
                .add(fakeTool("do_something", ToolResult.ok("did it")))
                .add(fakeTool("show_me", ToolResult.image("look", "QUJD", "image/png")))
                .add(fakeTool("always_fails", ToolResult.error("nope")));
        protocol = new McpProtocol(tools, new McpSessions());
    }

    private static McpProtocol.Headers headers(String... kv) {
        Map<String, String> m = new HashMap<>();
        m.put("Content-Type", "application/json");
        m.put("Accept", "application/json, text/event-stream");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return McpProtocol.Headers.of(m);
    }

    private String initializeAndGetSession() {
        HttpReply r = protocol.handlePost(headers(),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", 0L);
        return r.headers().get("Mcp-Session-Id");
    }

    // ── which worker is calling ──────────────────────────────────────

    /**
     * The name header on a worker's own config is what joins an MCP session to a pool slot. Tested
     * through {@code handlePost}, where it RUNS, rather than only in {@code WorkerRosterTest}.
     */
    @Test
    void theWorkerHeaderJoinsTheSessionToItsSlot() {
        String name = "wish-granter-mcp-test";
        AgentSession.roster().booted(name, 0L);
        try {
            String session = initializeAndGetSession();
            protocol.handlePost(headers("Mcp-Session-Id", session, AgentSession.WORKER_HEADER, name),
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", 1_000L);
            var slot = AgentSession.roster().snapshot().stream()
                    .filter(s -> s.name().equals(name)).findFirst().orElseThrow();
            assertEquals(session, slot.mcpId());
            assertEquals(1_000L, slot.lastHeardMs());
        } finally {
            AgentSession.roster().forget(name);
        }
    }

    // ── transport-level guards ──────────────────────────────────────────────

    @Test
    void wrongContentTypeIs415() {
        Map<String, String> m = new HashMap<>();
        m.put("Content-Type", "text/plain");
        m.put("Accept", "application/json, text/event-stream");
        HttpReply r = protocol.handlePost(McpProtocol.Headers.of(m), "{}", 0L);
        assertEquals(415, r.status(),
                "strict content-type is also the CSRF defence — it must be enforced");
    }

    @Test
    void missingAcceptPairIs406() {
        Map<String, String> m = new HashMap<>();
        m.put("Content-Type", "application/json");
        m.put("Accept", "application/json");
        assertEquals(406, protocol.handlePost(McpProtocol.Headers.of(m), "{}", 0L).status());
    }

    @Test
    void unsupportedProtocolVersionIs400() {
        HttpReply r = protocol.handlePost(headers("MCP-Protocol-Version", "1999-01-01"), "{}", 0L);
        assertEquals(400, r.status());
    }

    @Test
    void malformedJsonIsParseError() {
        HttpReply r = protocol.handlePost(headers(), "{{{not json", 0L);
        assertEquals(400, r.status());
        assertTrue(r.body().contains("-32700"), "expected a parse error, got " + r.body());
    }

    // ── handshake + sessions ────────────────────────────────────────────────

    @Test
    void initializeMintsASessionAndDescribesTheServer() {
        HttpReply r = protocol.handlePost(headers(),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", 0L);
        assertEquals(200, r.status());
        assertNotNull(r.headers().get("Mcp-Session-Id"), "initialize must mint a session id");
        JsonObject result = JsonParser.parseString(r.body()).getAsJsonObject()
                .getAsJsonObject("result");
        assertTrue(result.has("protocolVersion"));
        assertTrue(result.getAsJsonObject("capabilities").has("tools"));
        assertEquals(McpProtocol.SERVER_NAME,
                result.getAsJsonObject("serverInfo").get("name").getAsString());
    }

    @Test
    void initializeEchoesAVersionWeSupport() {
        HttpReply r = protocol.handlePost(headers(),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2024-11-05\"}}", 0L);
        assertEquals("2024-11-05", JsonParser.parseString(r.body()).getAsJsonObject()
                .getAsJsonObject("result").get("protocolVersion").getAsString());
    }

    @Test
    void notificationsGet202AndNoBody() {
        // notifications/initialized has no id; answering it with a result breaks strict clients.
        HttpReply r = protocol.handlePost(headers(),
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", 0L);
        assertEquals(202, r.status());
        assertTrue(r.body().isEmpty(), "a notification must not get a body");
    }

    @Test
    void callsWithoutASessionAreRejected() {
        HttpReply r = protocol.handlePost(headers(),
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", 0L);
        assertEquals(400, r.status());
    }

    @Test
    void unknownSessionIs404SoTheClientReinitializes() {
        HttpReply r = protocol.handlePost(headers("Mcp-Session-Id", "not-a-real-session"),
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", 0L);
        assertEquals(404, r.status(),
                "404 tells the client to re-initialize; 400 would wedge it permanently");
    }

    @Test
    void expiredSessionsAlsoGive404() {
        McpProtocol shortLived = new McpProtocol(tools, new McpSessions(1_000L));
        String session = shortLived.handlePost(headers(),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}", 0L)
                .headers().get("Mcp-Session-Id");
        HttpReply later = shortLived.handlePost(headers("Mcp-Session-Id", session),
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", 99_999L);
        assertEquals(404, later.status());
    }

    // ── methods ─────────────────────────────────────────────────────────────

    @Test
    void pingWorks() {
        String s = initializeAndGetSession();
        HttpReply r = protocol.handlePost(headers("Mcp-Session-Id", s),
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}", 0L);
        assertEquals(200, r.status());
        assertTrue(JsonParser.parseString(r.body()).getAsJsonObject().has("result"));
    }

    @Test
    void toolsListAdvertisesEveryTool() {
        String s = initializeAndGetSession();
        HttpReply r = protocol.handlePost(headers("Mcp-Session-Id", s),
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}", 0L);
        var list = JsonParser.parseString(r.body()).getAsJsonObject()
                .getAsJsonObject("result").getAsJsonArray("tools");
        assertEquals(tools.size(), list.size());
        JsonObject first = list.get(0).getAsJsonObject();
        assertTrue(first.has("name") && first.has("description") && first.has("inputSchema"),
                "each tool needs a name, description and inputSchema: " + first);
        assertEquals("object", first.getAsJsonObject("inputSchema").get("type").getAsString());
    }

    @Test
    void toolCallReturnsTextContent() {
        String s = initializeAndGetSession();
        HttpReply r = protocol.handlePost(headers("Mcp-Session-Id", s),
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"do_something\",\"arguments\":{}}}", 0L);
        JsonObject result = JsonParser.parseString(r.body()).getAsJsonObject()
                .getAsJsonObject("result");
        assertFalse(result.get("isError").getAsBoolean());
        assertEquals("did it", result.getAsJsonArray("content").get(0)
                .getAsJsonObject().get("text").getAsString());
    }

    @Test
    void aToolCanReturnAnImageTheAgentCanActuallySee() {
        // Load-bearing: the agent runs with no file-reading tool, so a screenshot PATH would be
        // useless to it. The pixels have to ride in the result as an image content block.
        String s = initializeAndGetSession();
        HttpReply r = protocol.handlePost(headers("Mcp-Session-Id", s),
                "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"show_me\",\"arguments\":{}}}", 0L);
        JsonObject result = JsonParser.parseString(r.body()).getAsJsonObject()
                .getAsJsonObject("result");
        assertFalse(result.get("isError").getAsBoolean());
        var content = result.getAsJsonArray("content");
        assertEquals(2, content.size(), "text AND image");
        assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString());
        JsonObject image = content.get(1).getAsJsonObject();
        assertEquals("image", image.get("type").getAsString());
        assertEquals("QUJD", image.get("data").getAsString());
        assertEquals("image/png", image.get("mimeType").getAsString());
    }

    @Test
    void aTextOnlyToolStillReturnsExactlyOneBlock() {
        // The image block must be OPTIONAL — adding it must not change every other tool's shape.
        String s = initializeAndGetSession();
        HttpReply r = protocol.handlePost(headers("Mcp-Session-Id", s),
                "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"do_something\",\"arguments\":{}}}", 0L);
        assertEquals(1, JsonParser.parseString(r.body()).getAsJsonObject()
                .getAsJsonObject("result").getAsJsonArray("content").size());
    }

    @Test
    void aFailingToolIsAResultWithIsErrorNotAJsonRpcError() {
        // The single most commonly botched distinction in MCP: a tool that fails has still been
        // called successfully. Returning a JSON-RPC error here makes clients treat a recoverable
        // failure as a broken connection.
        String s = initializeAndGetSession();
        HttpReply r = protocol.handlePost(headers("Mcp-Session-Id", s),
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"always_fails\",\"arguments\":{}}}", 0L);
        assertEquals(200, r.status());
        JsonObject body = JsonParser.parseString(r.body()).getAsJsonObject();
        assertFalse(body.has("error"), "must NOT be a JSON-RPC error: " + r.body());
        assertTrue(body.getAsJsonObject("result").get("isError").getAsBoolean());
    }

    @Test
    void unknownToolIsAToolErrorNotAProtocolError() {
        String s = initializeAndGetSession();
        HttpReply r = protocol.handlePost(headers("Mcp-Session-Id", s),
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"no_such_tool\",\"arguments\":{}}}", 0L);
        assertEquals(200, r.status());
        assertTrue(JsonParser.parseString(r.body()).getAsJsonObject()
                .getAsJsonObject("result").get("isError").getAsBoolean());
    }

    @Test
    void aThrowingToolDoesNotBreakTheConnection() {
        ToolRegistry boom = new ToolRegistry().add(new McpTool() {
            @Override public String name() { return "boom"; }
            @Override public String description() { return "throws"; }
            @Override public JsonObject inputSchema() { return Schemas.object(); }
            @Override public ToolResult call(JsonObject args) { throw new IllegalStateException("bang"); }
        });
        McpProtocol p = new McpProtocol(boom, new McpSessions());
        String s = p.handlePost(headers(), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}", 0L)
                .headers().get("Mcp-Session-Id");
        HttpReply r = p.handlePost(headers("Mcp-Session-Id", s),
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"boom\",\"arguments\":{}}}", 0L);
        assertEquals(200, r.status());
        assertTrue(JsonParser.parseString(r.body()).getAsJsonObject()
                .getAsJsonObject("result").get("isError").getAsBoolean());
    }

    @Test
    void unknownMethodIsMethodNotFound() {
        String s = initializeAndGetSession();
        HttpReply r = protocol.handlePost(headers("Mcp-Session-Id", s),
                "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"does/not/exist\"}", 0L);
        assertTrue(r.body().contains("-32601"), "expected method-not-found, got " + r.body());
    }

    @Test
    void everyToolSchemaIsAWellFormedObjectSchema() {
        // Guards against a new tool shipping a broken schema, which some clients reject outright.
        for (McpTool tool : tools.all()) {
            JsonObject schema = tool.inputSchema();
            assertEquals("object", schema.get("type").getAsString(),
                    tool.name() + " schema must be an object");
            assertTrue(schema.has("properties"), tool.name() + " schema needs properties");
            assertFalse(tool.description().isBlank(),
                    tool.name() + " needs a description — it's the agent's only documentation");
        }
    }
}
