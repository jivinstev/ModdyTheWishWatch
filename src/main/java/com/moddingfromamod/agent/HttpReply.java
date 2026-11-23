package com.moddingfromamod.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A ready-to-send HTTP response, produced by the pure {@link McpProtocol} and written out by
 * {@link McpHttpServer}. Keeping this a plain value (no {@code HttpExchange}) is what lets the whole
 * MCP surface be unit-tested in milliseconds.
 */
public record HttpReply(int status, Map<String, String> headers, String body) {

    public static HttpReply json(int status, String body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        return new HttpReply(status, h, body);
    }

    /** JSON response that also carries the MCP session id. */
    public static HttpReply json(int status, String body, String sessionId) {
        HttpReply r = json(status, body);
        if (sessionId != null && !sessionId.isEmpty()) {
            r.headers().put("Mcp-Session-Id", sessionId);
        }
        return r;
    }

    /** No content (used for JSON-RPC notifications, which must not get a response body). */
    public static HttpReply empty(int status) {
        return new HttpReply(status, new LinkedHashMap<>(), "");
    }

    public HttpReply withHeader(String name, String value) {
        headers().put(name, value);
        return this;
    }
}
