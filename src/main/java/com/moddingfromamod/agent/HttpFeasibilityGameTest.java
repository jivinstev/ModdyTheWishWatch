package com.moddingfromamod.agent;

import net.minecraft.network.chat.Component;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.moddingfromamod.ModdingFromAModMod;
import com.sun.net.httpserver.HttpServer;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Phase 0 feasibility probe</b> for the Wish Watch's MCP server.
 *
 * <p>The whole agent design rests on running a small HTTP server <em>inside</em> the Minecraft
 * process so Claude can connect to it. The JDK ships one ({@code com.sun.net.httpserver}) in the
 * {@code jdk.httpserver} module — but NeoForge loads mods through <b>modlauncher's own module
 * layer</b>, and a JDK module that resolves fine in a plain JVM is not automatically readable from
 * there. If it isn't, the fallback is a hand-rolled {@code ServerSocket} handler, and it is far
 * cheaper to learn that now than after building the protocol on top of it.
 *
 * <p>This keeps its value after Phase 0: it's a permanent regression test that the mod can still
 * bind a loopback port and complete a request under the current toolchain.
 *
 * <p>Binds an <b>ephemeral port</b> (port 0) so it never collides with a running game or a parallel
 * test, and always stops the server in a {@code finally}.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class HttpFeasibilityGameTest {

    /**
     * Bind an HTTP server on loopback from inside the running game and complete a real round-trip.
     * The request is issued from a background thread — a {@code @GameTest} body runs ON the server
     * thread, so calling our own blocking endpoint synchronously here would deadlock. That
     * constraint is exactly why the real design routes every tool through a thread hop.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void httpServerBindsInsideMinecraft(GameTestHelper helper) {
        helper.assertTrue(ModuleLayer.boot().findModule("jdk.httpserver").isPresent(),
                Component.literal("jdk.httpserver should be resolvable from the boot layer"));

        HttpServer server;
        try {
            // Bind IPv4 loopback EXPLICITLY. getLoopbackAddress() can hand back ::1 when the JVM
            // prefers IPv6, and then a client dialling 127.0.0.1 gets connection-refused.
            server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        } catch (Throwable t) {
            helper.fail(Component.literal("could not create an HttpServer inside Minecraft: " + t));
            return;
        }

        // Daemon threads: if this test ever times out before the stop() below runs, a leaked server
        // must never keep the JVM (or the test server) alive.
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "wish-http-probe-worker");
            t.setDaemon(true);
            return t;
        }));

        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<Integer> status = new AtomicReference<>();

        server.createContext("/probe", exchange -> {
            byte[] payload = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        InetSocketAddress bound = server.getAddress();
        int port = bound.getPort();
        System.out.println("WISH_PROBE: server bound to " + bound);
        helper.assertTrue(port > 0, Component.literal("should have bound an ephemeral port, bound=" + bound));

        Thread caller = new Thread(() -> {
            // Raw socket + hand-written HTTP/1.1, so a failure can't be blamed on HttpClient
            // (proxy selectors, IPv6 preference, etc.). Connect to the SAME address we bound.
            try (java.net.Socket s = new java.net.Socket()) {
                // Backstops, not deadlines — the sweep. Less exposed than McpGameTest's
                // (this handler runs on the in-test HttpServer's own executor, not the server
                // thread), but it is the same shape: real seconds asserted inside a GameTest on a
                // box running 50 tests at once. Raised preventively rather than after it goes red.
                s.connect(new InetSocketAddress(bound.getAddress(), port), 30_000);
                s.setSoTimeout(60_000);
                s.getOutputStream().write(
                        ("GET /probe HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.UTF_8));
                s.getOutputStream().flush();
                String raw = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                status.set(raw.startsWith("HTTP/1.1 200") ? 200 : -1);
                body.set(raw);
            } catch (Exception e) {
                body.set("EXCEPTION: " + e);
            }
        }, "wish-http-probe");
        caller.setDaemon(true);
        caller.start();

        // NOTE: succeedWhen() REGISTERS a polled condition and returns immediately — it does not
        // block. So the server must NOT be stopped in a finally here (that would tear it down before
        // the request lands; it manifests as a ConnectException). Stop it on the success path.
        helper.succeedWhen(() -> {
            helper.assertTrue(body.get() != null, Component.literal("no HTTP reply yet"));
            helper.assertTrue(Integer.valueOf(200).equals(status.get()),
                    Component.literal("expected HTTP 200, got " + status.get() + " body=" + body.get()));
            helper.assertTrue(body.get().contains("\"ok\":true"), Component.literal("unexpected body: " + body.get()));
            System.out.println("WISH_PROBE: HttpServer works inside Minecraft on port " + port);
            server.stop(0);
        });
    }
}
