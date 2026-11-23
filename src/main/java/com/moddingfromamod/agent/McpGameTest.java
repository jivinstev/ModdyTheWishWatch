package com.moddingfromamod.agent;

import net.minecraft.network.chat.Component;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.testing.GateShards;

/**
 * Gate B for the agent endpoint: the parts Gate A structurally cannot reach — the HTTP adapter, the
 * bearer token, and above all the <b>thread hop</b> from an HTTP handler into the live game.
 *
 * <p>Note the shape of every test here: the HTTP call runs on a <b>background thread</b> and the
 * assertions are polled with {@code succeedWhen}. A {@code @GameTest} body runs ON the server
 * thread, so calling our own blocking endpoint synchronously would deadlock — which is exactly the
 * hazard {@link AgentBridge}'s same-thread guard exists to prevent.
 */
@GameTestHolder(GateShards.CI)
public class McpGameTest {

    /** Minimal MCP client: one request/response over a raw socket. */
    private static String post(int port, String body, String sessionId, boolean withAuth) {
        try (Socket s = new Socket()) {
            // HANG BACKSTOPS, NOT DEADLINES —. These were 4s/8s, which is a wall-clock
            // assertion sitting inside a GameTest: the request hops to the SERVER thread to run,
            // and in a 50-test batch that thread is routinely occupied for tens of seconds by
            // another test's datapack reload (109 of them inside one batch, measured). The read
            // then times out and the test goes red for something it is not testing — and, being a
            // race, it rotates between tests run to run, which is how was found.
            //
            // Nothing here is measuring latency. The GameTest's own timeoutTicks is the deadline
            // that means something, and ticks do not advance while the server thread is busy, so
            // it cannot be lost to load the way a real-seconds budget can. These numbers only
            // exist so a genuine deadlock fails instead of hanging the suite forever.
            s.connect(new InetSocketAddress("127.0.0.1", port), 30_000);
            s.setSoTimeout(120_000);
            StringBuilder req = new StringBuilder();
            req.append("POST ").append(McpHttpServer.PATH).append(" HTTP/1.1\r\n")
               .append("Host: 127.0.0.1:").append(port).append("\r\n")
               .append("Content-Type: application/json\r\n")
               .append("Accept: application/json, text/event-stream\r\n");
            if (withAuth) {
                req.append("Authorization: Bearer ").append(AgentToken.token()).append("\r\n");
            }
            if (sessionId != null) {
                req.append("Mcp-Session-Id: ").append(sessionId).append("\r\n");
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            req.append("Content-Length: ").append(payload.length).append("\r\n")
               .append("Connection: close\r\n\r\n");
            s.getOutputStream().write(req.toString().getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().write(payload);
            s.getOutputStream().flush();
            return new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "EXCEPTION: " + e;
        }
    }

    private static String sessionIdFrom(String rawResponse) {
        for (String line : rawResponse.split("\r\n")) {
            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("mcp-session-id:")) {
                return line.substring(line.indexOf(':') + 1).trim();
            }
        }
        return null;
    }

    /** The endpoint is up and the handshake works over a real socket. */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void endpointIsListeningAndHandshakes(GameTestHelper helper) {
        int port = McpHttpServer.boundPort();
        helper.assertTrue(McpHttpServer.isRunning() && port > 0,
                Component.literal("the agent endpoint should be listening (port=" + port + ")"));

        AtomicReference<String> reply = new AtomicReference<>();
        run(() -> reply.set(post(port,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null, true)));
        helper.succeedWhen(() -> {
            helper.assertTrue(reply.get() != null, Component.literal("waiting for the handshake"));
            helper.assertTrue(reply.get().startsWith("HTTP/1.1 200"),
                    Component.literal("handshake should be 200: " + head(reply.get())));
            helper.assertTrue(sessionIdFrom(reply.get()) != null,
                    Component.literal("initialize must return a session id"));
        });
    }

    /** No token, no entry — otherwise any local process could drive the child's game. */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void unauthenticatedRequestsAreRefused(GameTestHelper helper) {
        int port = McpHttpServer.boundPort();
        AtomicReference<String> reply = new AtomicReference<>();
        run(() -> reply.set(post(port,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}", null, false)));
        helper.succeedWhen(() -> {
            helper.assertTrue(reply.get() != null, Component.literal("waiting"));
            helper.assertTrue(reply.get().startsWith("HTTP/1.1 401"),
                    Component.literal("expected 401 without a token, got: " + head(reply.get())));
        });
    }

    /**
     * <b>The headline test.</b> A tool call arriving on an HTTP thread reaches across into the game
     * thread and changes the world. Covers four failure classes at once: the adapter, the token, the
     * thread hop (a deadlock shows up as a timeout), and command execution.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void toolCallCrossesTheThreadBoundaryAndChangesTheWorld(GameTestHelper helper) {
        int port = McpHttpServer.boundPort();
        AtomicReference<String> reply = new AtomicReference<>();
        run(() -> {
            String init = post(port,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null, true);
            String session = sessionIdFrom(init);
            reply.set(post(port,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
                            + "{\"name\":\"run_command\",\"arguments\":"
                            + "{\"command\":\"summon minecraft:pig ~ ~ ~\"}}}", session, true));
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(reply.get() != null, Component.literal("waiting for the tool call"));
            helper.assertTrue(reply.get().contains("\"isError\":false"),
                    Component.literal("the tool should have succeeded: " + body(reply.get())));
            // Assert on the LEVEL, not the test structure: with no player online the command runs
            // at world spawn (see RunCommandTool), which is outside this test's little box.
            helper.assertTrue(
                    !helper.getLevel().getEntities(EntityType.PIG, e -> true).isEmpty(),
                    Component.literal("the agent's summon should have produced a pig somewhere in the world"));
        });
    }

    /** tools/list advertises the real tool set to the agent. */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void toolsAreAdvertised(GameTestHelper helper) {
        int port = McpHttpServer.boundPort();
        AtomicReference<String> reply = new AtomicReference<>();
        run(() -> {
            String session = sessionIdFrom(post(port,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}", null, true));
            reply.set(post(port, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                    session, true));
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(reply.get() != null, Component.literal("waiting"));
            String b = body(reply.get());
            for (String tool : new String[]{"list_wishes", "find_content", "look_around",
                    "run_command", "modify_mob", "conjure_at", "await_wish", "build_structure",
                    "notify_player", "add_template", "finish_wish",
                    "define_creature"}) {
                helper.assertTrue(b.contains(tool), Component.literal("tools/list should advertise " + tool + ": " + b));
            }
        });
    }

    /** The allowlist holds even when the caller is the agent. */
    @GameTest(template = "empty_test", timeoutTicks = 300)
    public static void agentCannotRunForbiddenCommands(GameTestHelper helper) {
        int port = McpHttpServer.boundPort();
        AtomicReference<String> reply = new AtomicReference<>();
        run(() -> {
            String session = sessionIdFrom(post(port,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}", null, true));
            reply.set(post(port,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
                            + "{\"name\":\"run_command\",\"arguments\":"
                            + "{\"command\":\"execute as @a run stop\"}}}", session, true));
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(reply.get() != null, Component.literal("waiting"));
            helper.assertTrue(reply.get().contains("\"isError\":true"),
                    Component.literal("a forbidden command must be refused: " + body(reply.get())));
            helper.assertFalse(helper.getLevel().getServer().isStopped(),
                    Component.literal("the server must still be running!"));
        });
    }

    private static void run(Runnable work) {
        Thread t = new Thread(work, "mcp-gametest-client");
        t.setDaemon(true);
        t.start();
    }

    private static String head(String raw) {
        int i = raw.indexOf("\r\n");
        return i < 0 ? raw : raw.substring(0, i);
    }

    private static String body(String raw) {
        int i = raw.indexOf("\r\n\r\n");
        return i < 0 ? raw : raw.substring(i + 4);
    }
}
