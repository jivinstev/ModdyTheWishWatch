package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>The first wish on a fresh machine.</b> Starts the REAL endpoint from a home with no token file and
 * plays the first worker's handshake over HTTP: the token is on disk before anybody can connect, the tool list
 * was rendered before the socket listened (so the first worker's {@code tools/list} is answered warm — the
 * cold one was what landed after its first model turn), and the token a worker's config is written with
 * opens the door on the very first request.
 */
class McpEndpointColdStartTest {

    private static final String HOME = "moddingfromamod.home";

    @AfterEach
    void tearDown() {
        McpHttpServer.stop();
        System.clearProperty(HOME);
        AgentToken.forgetForTest();
    }

    @Test
    void fromNoTokenFileTheEndpointIsReadyBeforeItListensAndTheFirstWorkerGetsItsTools(@TempDir Path home)
            throws Exception {
        System.setProperty(HOME, home.toString());
        AgentToken.forgetForTest();
        Path token = AgentToken.tokenFile();
        assertFalse(Files.exists(token), "the test starts on a fresh machine");

        AtomicInteger rendered = new AtomicInteger();
        McpTool awaitWish = new McpTool() {
            @Override public String name() { return "await_wish"; }
            @Override public String description() { rendered.incrementAndGet(); return "wait for a wish"; }
            @Override public JsonObject inputSchema() { return new JsonObject(); }
            @Override public ToolResult call(JsonObject args) { return ToolResult.ok("a wish"); }
        };
        McpHttpServer.start(new ToolRegistry().add(awaitWish), McpHttpServer.EPHEMERAL_PORT);
        assertTrue(McpHttpServer.isRunning());

        // ready BEFORE anybody connected
        assertTrue(Files.isRegularFile(token), "the token must exist before the endpoint listens (#1957)");
        String onDisk = Files.readString(token, StandardCharsets.UTF_8).trim();
        assertFalse(onDisk.isEmpty());
        assertTrue(rendered.get() >= 1, "the tool list must be rendered before the first worker asks for it");

        // the first worker: its config carries the token read from disk
        HttpClient http = HttpClient.newHttpClient();
        String url = "http://127.0.0.1:" + McpHttpServer.boundPort() + McpHttpServer.PATH;
        HttpResponse<String> init = http.send(post(url, onDisk, null,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, init.statusCode(), init.body());
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        HttpResponse<String> list = http.send(post(url, onDisk, session,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, list.statusCode(), list.body());
        JsonObject result = JsonParser.parseString(list.body()).getAsJsonObject().getAsJsonObject("result");
        assertEquals("await_wish", result.getAsJsonArray("tools").get(0).getAsJsonObject().get("name").getAsString());
        HttpResponse<String> call = http.send(post(url, onDisk, session,
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"await_wish\",\"arguments\":{}}}"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, call.statusCode(), call.body());
        assertTrue(call.body().contains("a wish"), call.body());
    }

    private static HttpRequest post(String url, String token, String session, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (session != null) {
            b.header("Mcp-Session-Id", session);
        }
        return b.build();
    }
}
