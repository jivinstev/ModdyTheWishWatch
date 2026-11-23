package com.moddingfromamod.agent;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.moddingfromamod.ModdingFromAModMod;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The HTTP adapter that puts {@link McpProtocol} on a socket so Claude can connect to the running
 * game. Deliberately thin: parse the request, call the pure protocol, write the reply.
 *
 * <p><b>Process lifetime, not world lifetime.</b> Started once at mod setup and stopped at JVM
 * shutdown — <em>not</em> on world load/unload. In singleplayer those fire every time the player
 * returns to the title screen, and a socket that disappears underneath an MCP client leaves it
 * wedged; a tool that politely says "no world is open" does not.
 *
 * <p><b>Security</b> (there is no TLS and no OAuth here, by design — it's a loopback dev tool):
 * <ul>
 *   <li>binds <b>IPv4 127.0.0.1 explicitly</b> — {@code getLoopbackAddress()} can return {@code ::1},
 *       which then refuses connections from clients dialling 127.0.0.1 (measured, not theoretical);</li>
 *   <li>requires a <b>bearer token</b>, so other local processes can't drive the kid's game;</li>
 *   <li>rejects any request carrying an {@code Origin} header (DNS-rebinding defence);</li>
 *   <li>requires {@code Content-Type: application/json}, which is what stops a web page reaching it.</li>
 * </ul>
 */
public final class McpHttpServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** Where Claude connects: http://127.0.0.1:&lt;port&gt;/mcp */
    public static final String PATH = "/mcp";
    public static final int DEFAULT_PORT = 25585;

    /**
     * The port value that asks the OS to pick a free one. Passing this to {@link #start} binds an
     * <b>ephemeral</b> port — see the {@code port == 0} path there.
     */
    public static final int EPHEMERAL_PORT = 0;

    /**
     * The port a run should DEFAULT to before any explicit {@code -Dmoddingfromamod.mcp.port}
     * override.
     *
     * <p>A <b>live play</b> session gets the fixed, well-known {@link #DEFAULT_PORT}. A <b>test</b>
     * run gets an {@link #EPHEMERAL_PORT} — the OS assigns a guaranteed-free, unique port per
     * process. That does three things at once:
     * <ul>
     *   <li>a test running the real "production" agent can never contend for the port a child's
     *       live game holds (the incident that lost a "blue lava" wish: a Gate-D run and the live
     *       game both wanted 25585, the test won the race, and the wish reached the wrong agent);</li>
     *   <li>any number of <b>concurrent</b> test JVMs — several branches/worktrees building at
     *       once — each get their own socket with zero contention;</li>
     *   <li>it beats a hand-rolled "random port + check": OS ephemeral allocation is atomic, with
     *       no pick-then-bind race where the number we chose is taken by the time we bind it.</li>
     * </ul>
     * Nothing needs a <i>predictable</i> test port — the bound port is logged and written to the
     * agent's env, and every consumer reads {@link #boundPort()} rather than assuming a number.
     * Pure and unit-tested: the production port is fixed, the test port is "let the OS decide".
     */
    public static int defaultPortFor(boolean testRun) {
        return testRun ? EPHEMERAL_PORT : DEFAULT_PORT;
    }

    /**
     * Sized for <b>blocking</b> tools. {@code await_wish} parks a thread for up to 55 s at a time,
     * so the pool must stay comfortably above the number of parked agents — otherwise the agent's
     * own next tool call has nowhere to run and it deadlocks against its own long-poll. Four was
     * fine while every tool returned immediately; it is not once one of them waits.
     */
    private static final int HTTP_THREADS = 12;

    private static HttpServer server;
    private static int boundPort = -1;
    private static McpProtocol protocol;
    private static McpSessions sessions;

    private McpHttpServer() {
    }

    /** The port actually bound (tests use an ephemeral port, so this is the source of truth). */
    public static int boundPort() {
        return boundPort;
    }

    public static boolean isRunning() {
        return server != null;
    }

    /** The property that moves this endpoint off loopback. Unset is the shipped behaviour. */
    private static final String BIND_PROPERTY = ModdingFromAModMod.MODID + ".mcp.bind";

    /**
     * What the endpoint binds: {@code -Dmoddingfromamod.mcp.bind}, else {@code 127.0.0.1}.
     *
     * <p>HO-6. The agent is a subprocess of this JVM today, so loopback is not a setting — it is
     * the whole topology. A worker in another container needs to reach this port over a private
     * network, and that is the one reason this is configurable at all.
     *
     * <p>⚠ <b>The default stays the IPv4 LITERAL, not {@code getLoopbackAddress()}</b>, and that is
     * not tidiness: {@code getLoopbackAddress()} can return {@code ::1}, which then refuses
     * connections from a client dialling {@code 127.0.0.1} — measured, not theoretical, and the
     * reason the literal was written here in the first place. A property that is unset must
     * therefore produce the identical bind it always did, byte for byte.
     *
     * <p>⚠ <b>Moving it off loopback is a security decision, so it is ASSERTED</b>, not merely
     * allowed: {@link com.moddingfromamod.ops.DeploymentAssertion}'s {@code mcp.exposed} row
     * refuses a {@code prod} boot that binds this anywhere but loopback without a token on disk.
     * The token has always been required per request; what the assertion adds is refusing the
     * arrangement where there is no token to require.
     */
    public static String resolvedBind() {
        String explicit = System.getProperty(BIND_PROPERTY, "").trim();
        return explicit.isEmpty() ? "127.0.0.1" : explicit;
    }

    /**
     * Start the endpoint. Idempotent, and never fatal: if the port is taken (a second game, or a
     * stale process) we log loudly and carry on — the watch's offline wishes must keep working.
     *
     * @param port 0 for an ephemeral port (used by tests so they never collide with a live game)
     */
    public static synchronized void start(ToolRegistry tools, int port) {
        if (server != null) {
            return;
        }
        sessions = new McpSessions();
        protocol = new McpProtocol(tools, sessions);
        try {
            server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName(resolvedBind()), port), 0);
        } catch (IOException e) {
            // Almost always a SECOND Minecraft already holding the fixed port. Fall back to an
            // ephemeral one rather than going without an agent: this instance hands its own agent
            // whatever port it actually bound, so two games each get their own, which is exactly
            // what the per-instance design promised. Observed for real -- a second client started
            // while a game was running got no agent at all and reported nothing amiss.
            if (port != 0) {
                LOGGER.warn("port {} is taken ({}) — falling back to an automatic port",
                        port, e.toString());
                try {
                    server = HttpServer.create(
                            new InetSocketAddress(InetAddress.getByName(resolvedBind()), 0), 0);
                } catch (IOException retry) {
                    LOGGER.error("Wish agent endpoint could not bind at all ({}). "
                            + "Wishes still work offline; Claude just can't connect.", retry.toString());
                    server = null;
                    return;
                }
            } else {
                LOGGER.error("Wish agent endpoint could not bind ({}). "
                        + "Wishes still work offline; Claude just can't connect.", e.toString());
                server = null;
                return;
            }
        }
        AtomicInteger n = new AtomicInteger();
        server.setExecutor(Executors.newFixedThreadPool(HTTP_THREADS, r -> {
            Thread t = new Thread(r, "wish-mcp-" + n.incrementAndGet());
            t.setDaemon(true); // never hold the JVM open
            return t;
        }));
        server.createContext(PATH, McpHttpServer::handle);
        // READY BEFORE IT LISTENS. The token file exists before anybody can connect — not left to
        // the first request or the first worker's config — and the tool list has been rendered once, so the
        // first worker's tools/list is answered warm (75 ms cold against 10–26 ms warm, measured). Neither is
        // what made the first wish go unclaimed — that is the CLI fixing a turn's tools before a large list
        // lands, answered by the worker's own wait (AgentSession.writeToolsWait) — but a fresh machine's
        // first worker should not also be the one that pays for a cold endpoint.
        AgentToken.token();
        protocol.warm();
        server.start();
        boundPort = server.getAddress().getPort();
        // ⚠ THE ADDRESS IS READ BACK FROM THE SOCKET, not printed as a constant. Both of these
        // lines said "127.0.0.1" literally, which was true for as long as the bind could not be
        // configured and became a lie the moment it could (HO-6) — a boot line that reports a
        // constant cannot tell you it is wrong, and this one is what an operator checks when a
        // worker in another container cannot connect.
        String host = server.getAddress().getAddress().getHostAddress();
        LOGGER.info("Wish agent endpoint listening on http://{}:{}{}", host, boundPort, PATH);
        LOGGER.info("Connect Claude with:  claude mcp add --transport http wishwatch "
                + "http://{}:{}{} --header \"Authorization: Bearer <token>\"",
                host, boundPort, PATH);
        LOGGER.info("Token file: {}", AgentToken.tokenFile());
    }

    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            boundPort = -1;
            LOGGER.info("Wish agent endpoint stopped");
        }
    }

    private static void handle(HttpExchange exchange) {
        try {
            String method = exchange.getRequestMethod();
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> {
                if (!v.isEmpty()) {
                    headers.put(k, v.get(0));
                }
            });

            // A browser-originated request has no business here.
            if (headers.keySet().stream().anyMatch(h -> h.equalsIgnoreCase("Origin"))) {
                send(exchange, HttpReply.json(403, "{\"error\":\"origin not allowed\"}"));
                return;
            }
            if (!AgentToken.isAuthorised(find(headers, "Authorization"))) {
                send(exchange, HttpReply.json(401,
                        "{\"error\":\"missing or bad bearer token — see the token file in the log\"}"));
                return;
            }

            switch (method) {
                case "POST" -> {
                    String body = new String(exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8);
                    send(exchange, protocol.handlePost(McpProtocol.Headers.of(headers), body,
                            System.currentTimeMillis()));
                }
                case "DELETE" -> {
                    sessions.end(find(headers, "Mcp-Session-Id"));
                    send(exchange, HttpReply.empty(200));
                }
                case "GET" -> {
                    // We don't offer a server-initiated SSE stream. 405 is the spec's way of saying
                    // so, and clients treat it as "no server-push" and carry on.
                    send(exchange, HttpReply.empty(405).withHeader("Allow", "POST, DELETE"));
                }
                default -> send(exchange, HttpReply.empty(405).withHeader("Allow", "POST, DELETE"));
            }
        } catch (Throwable t) {
            LOGGER.warn("wish agent request blew up: {}", t.toString());
            try {
                send(exchange, HttpReply.json(500, "{\"error\":\"internal\"}"));
            } catch (Exception ignored) {
                // the connection is already gone
            }
        } finally {
            exchange.close();
        }
    }

    private static String find(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static void send(HttpExchange exchange, HttpReply reply) throws IOException {
        reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        byte[] payload = reply.body() == null ? new byte[0]
                : reply.body().getBytes(StandardCharsets.UTF_8);
        if (payload.length == 0) {
            exchange.sendResponseHeaders(reply.status(), -1); // no body
            return;
        }
        exchange.sendResponseHeaders(reply.status(), payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
