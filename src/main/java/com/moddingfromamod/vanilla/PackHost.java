package com.moddingfromamod.vanilla;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.sun.net.httpserver.HttpServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Serves the rig pack over HTTP — the ONE way pack bytes reach any client.</b>
 *
 * <p>Deliberately small. It zips a directory, hashes it, and hands the bytes back to whoever asks
 * for that hash. It has no routes but one, no state but a map of hash to bytes, and it never reads
 * a path a caller supplied.
 *
 * <h2>Why this exists at all, given a stock client needs no mod code to be served</h2>
 *
 * <p>Vanilla {@code server.properties} really does carry {@code resource-pack},
 * {@code resource-pack-sha1} and {@code require-resource-pack}, and for a STATIC pack that is the
 * whole delivery with no code. It is not enough here for one reason: the pack changes when a child
 * wishes a creature, mid-session, and a properties file is read at boot. So something in-process
 * has to publish new bytes and name them.
 *
 * <h2>Bind address versus advertised host — different questions</h2>
 *
 * <p>{@link #start} binds where it is told and {@link #publish} returns a URL built from the
 * ADVERTISED host, which may be a different string entirely: a server may bind {@code 0.0.0.0} and
 * still have to tell a client a name that resolves for it. This class takes both as inputs and
 * decides neither, because the right answer differs per deployment — loopback for a co-located
 * camera, a LAN address for a family server, and something else again in a cloud environment. That
 * last one is an open design question and is deliberately not answered here.
 *
 * <h2>What it does not do, on purpose</h2>
 *
 * <ul>
 *   <li><b>No disk cache.</b> Packs are small (measured: 44,855 bytes for a Spikor) and are
 *       regenerated on publish anyway. Holding them in memory keyed by hash makes an unchanged
 *       republish free and removes a whole class of stale-file bug.</li>
 *   <li><b>No range requests, no compression negotiation, no keep-alive tuning.</b> A client fetches
 *       one small zip once per changed pack. Anything more is unearned.</li>
 *   <li><b>No authentication.</b> It serves resource packs, which are already public to anyone the
 *       game server admits. Locking it down belongs with the hosting decision, not here.</li>
 * </ul>
 */
public final class PackHost {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-pack-host");

    /** Ask the OS for a free port. Mirrors {@code McpHttpServer}'s convention. */
    public static final int ANY_PORT = 0;

    private static HttpServer server;
    private static int port;
    private static final Map<String, byte[]> BY_SHA1 = new ConcurrentHashMap<>();

    /**
     * The rate and concurrency caps. Rebuilt at every {@link #start}, so a property set
     * before a restart takes effect and a test can drive a small bucket.
     */
    private static volatile PackHostLimits limits = PackHostLimits.fromProperties();

    private PackHost() {
    }

    /** The caps in force. Null never; see {@link PackHostLimits}. */
    public static PackHostLimits limits() {
        return limits;
    }

    /**
     * Swap the caps under a running host. <b>For a test's own fixture only</b>, and its purpose is
     * a driven clock: proving the bucket refills over HTTP at the shipped 60/min would mean a test
     * that waits a minute, and proving it at rpm=2 would mean one that waits thirty seconds.
     * A health plug-in's test seam is here for the same reason.
     */
    static void limitsForTest(PackHostLimits replacement) {
        limits = replacement == null ? PackHostLimits.fromProperties() : replacement;
    }

    /**
     * Start the host, or do nothing if it is already running.
     *
     * @param bindHost the address to LISTEN on — {@code 127.0.0.1} to serve only this machine,
     *                 {@code 0.0.0.0} to serve the network
     * @param wantPort {@link #ANY_PORT} to let the OS choose
     * @return the port actually bound
     */
    public static synchronized int start(String bindHost, int wantPort) throws IOException {
        if (server != null) {
            return port;
        }
        // BIND FIRST, THEN ASK WHAT WE GOT. Choosing a free port and then binding it is a race --
        // the number can be taken in between -- which is the same reasoning McpHttpServer records.
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName(bindHost), wantPort), 0);
        // The caps, read now. An operator's property reaches the host at its next start,
        // which is also how a test gets a two-token bucket without a back door.
        limits = PackHostLimits.fromProperties();
        server.createContext(PackDelivery.PATH_PREFIX, exchange -> {
            // ⚠ ONE READ OF THE VOLATILE, AND BOTH SIDES OF THE ACCOUNTING USE IT. This was two
            // reads with a whole response in between -- `caps.tryEnter()` here and `limits.exit()`
            // in the finally -- so a slot was taken from one object and given back to whatever the
            // field pointed at by the time the body had been written. Take from A, give back to B.
            // It took CI red on `main` on 2026-09-21 (PackHostCapsTest, 503 expected, 200 served)
            // and it presented as an unreliable test rather than as the accounting bug it is.
            final PackHostLimits caps = limits;
            boolean entered = false;
            try {
                // ── THE TWO CAPS, BEFORE ANY WORK (plan §5) ────────────────────────────
                // This host is the one thing a home deployment puts on the public internet, and it
                // is unauthenticated by design. Neither cap is reachable by playing: a small server
                // fetches a pack a handful of times a day.
                String who = PackHostLimits.keyFor(exchange.getRemoteAddress());
                if (!caps.allow(who)) {
                    LOG.warn("pack host: 429 for {} ({}/min)", who, caps.limiter().perMinute());
                    exchange.sendResponseHeaders(429, -1);
                    return;
                }
                if (!caps.tryEnter()) {
                    LOG.warn("pack host: 503 for {} — {} responses already in flight",
                            who, caps.maxConcurrent());
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                entered = true;
                // The path is never concatenated into a filesystem path. It is parsed into a hash,
                // and a hash either names bytes we published or names nothing at all.
                String sha1 = PackDelivery.sha1InPath(exchange.getRequestURI().getPath());
                byte[] body = sha1 == null ? null : BY_SHA1.get(sha1);
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.getResponseHeaders().add("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                LOG.info("served pack {} ({} bytes) to {}", sha1.substring(0, 8), body.length,
                        exchange.getRemoteAddress());
            } catch (RuntimeException | IOException e) {
                LOG.warn("pack request failed", e);
                try {
                    exchange.sendResponseHeaders(500, -1);
                } catch (IOException ignored) {
                    // the client is gone; nothing useful left to say
                }
            } finally {
                // A LEAKED SLOT IS A HOST THAT ANSWERS 503 FOREVER with nothing running, which
                // looks exactly like being under attack. Released here, whatever happened above,
                // and onto `caps` -- the very object the slot was taken from.
                if (entered) {
                    caps.exit();
                }
                exchange.close();
            }
        });
        // ── /healthz (plan §6 item 5) ───────────────────────────────────────────────────
        //
        // HERE rather than in a listener of its own, because this is the one port a home server
        // already forwards: a free external pinger (UptimeRobot-class) is the only thing that can
        // notice the SERVER going away, which no watchdog running on the same machine can report.
        //
        // The body is one word and nothing else, DELIBERATELY. It is internet-facing and
        // unauthenticated like everything else on this host, so it must not leak who is online,
        // what is being built, or whether a child is playing — a pinger needs a status code, and a
        // status code is all it gets. The JSON lives behind RCON, which is authenticated.
        //
        // ⚠ EXEMPT FROM THE RATE LIMIT, and that is the point of the exemption: a pinger
        // that got a 429 would report the server DOWN, turning a cap into an alerting bug. It is
        // NOT exempt from the concurrency gate — 503 is the true answer when the host is saturated,
        // and it is the answer /healthz would want to give anyway.
        server.createContext("/healthz", exchange -> {
            // One read, both sides -- see the pack route above.
            final PackHostLimits caps = limits;
            boolean entered = false;
            try {
                if (!caps.tryEnter()) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                entered = true;
                // the answer is a HealthHook plug-in's; the public mod has no health
                // reporting to answer from, so it says there is no such page.
                var hook = com.moddingfromamod.plugin.Plugins.shared()
                        .first(com.moddingfromamod.health.HealthHook.class);
                if (hook.isEmpty()) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                boolean ok = hook.get().healthy(System.currentTimeMillis());
                byte[] body = (ok ? "ok" : "unhealthy").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(ok ? 200 : 503, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (RuntimeException | IOException e) {
                // A health endpoint that throws is DOWN, not a 500 with a stack in it.
                try {
                    exchange.sendResponseHeaders(503, -1);
                } catch (IOException ignored) {
                    // the pinger is gone; nothing useful left to say
                }
            } finally {
                if (entered) {
                    caps.exit();
                }
                exchange.close();
            }
        });
        // ── /canary ─────────────────────────────────────────────────────────────────────
        //
        // A health plug-in's last canary verdict, for an external monitor (so an operator can see
        // "Moddy is granting wishes" without asking the server itself). Same port as /healthz for
        // the same reason: it is the one a home server already exposes.
        //
        // ⚠ Unauthenticated and internet-facing like everything here, so the hook allows only
        // a verdict from a closed set and two integers — nothing read from disk passes as text.
        // 404 when there is no verdict (the canary timer is not loaded). And unlike /healthz it is
        // NOT exempt from the rate limit: a refused read is "no data" to its reader, never "down".
        server.createContext("/canary", exchange -> {
            final PackHostLimits caps = limits;
            boolean entered = false;
            try {
                if (!caps.allow(PackHostLimits.keyFor(exchange.getRemoteAddress()))) {
                    exchange.sendResponseHeaders(429, -1);
                    return;
                }
                if (!caps.tryEnter()) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                entered = true;
                var line = com.moddingfromamod.plugin.Plugins.shared()
                        .first(com.moddingfromamod.health.HealthHook.class)
                        .flatMap(com.moddingfromamod.health.HealthHook::canary);
                if (line.isEmpty()) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] body = line.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (RuntimeException | IOException e) {
                try {
                    exchange.sendResponseHeaders(503, -1);
                } catch (IOException ignored) {
                    // the reader is gone
                }
            } finally {
                if (entered) {
                    caps.exit();
                }
                exchange.close();
            }
        });
        // ⚠ A POOL, NOT ONE THREAD, AND THE CONCURRENCY CAP IS WHY.
        //
        // This was `newSingleThreadExecutor`, and with one handler thread the in-flight count can
        // never exceed one — so the 503 branch above would have been a guard whose enabling
        // condition is false forever, which is an earlier issue's shape and reads identically to a working cap.
        // A cached pool lets requests really overlap, and {@link PackHostLimits#tryEnter} is then
        // the thing that bounds them: refuse fast rather than queue without limit. The handlers
        // touch only a ConcurrentHashMap and a byte[] they do not own, so they were already safe to
        // run side by side.
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mfam-pack-host");
            t.setDaemon(true);
            return t;
        }));
        startOnADaemonThread(server);
        port = server.getAddress().getPort();
        LOG.info("pack host listening on {}:{}", bindHost, port);
        return port;
    }

    /**
     * <b>Call {@code HttpServer.start()} from a daemon thread, and wait for it.</b>
     *
     * <p>Not a flourish: this is the difference between a headless run that ends and one that does
     * not. {@code sun.net.httpserver.ServerImpl.start()} creates its listener as
     * {@code new Thread(null, dispatcher, "HTTP-Dispatcher", 0L, false)} — read off the 21.0.10
     * bytecode; that last argument is {@code inheritThreadLocals}, <b>not</b> daemon — so the
     * dispatcher takes its daemon status from whoever called {@code start()}. Both production
     * callers ({@link ItemPackService#onServerStarted}, {@link RigReveal}'s deliver) are on
     * Minecraft's <b>Server thread</b>, which is not a daemon, so the dispatcher was not either and
     * the JVM could never exit on its own.
     *
     * <p>Measured on a Gate C½ {@code runServer} millstone wish (2026-09-09): the wish PASSed at
     * 21:34:30, the worlds were saved at 21:34:33, and at 21:39:10 the process was still alive with
     * exactly two live Java threads — {@code DestroyJavaVM} waiting, and this dispatcher. (The MCP
     * server's dispatcher, on the same jstack, is a daemon: it is started from
     * {@code modloading-sync-worker}. Same class, same thread name, opposite outcome — which is why
     * the fix belongs HERE, in the one place every caller goes through, rather than in a caller.)
     *
     * <p><b>Joined, deliberately.</b> {@link #start} promises the bound port as its return value and
     * {@link #publish} builds URLs from it, so handing back before {@code start()} has run would
     * turn a lifetime bug into a startup race. The starter thread does one call and dies; only the
     * dispatcher it fathers outlives it.
     */
    private static void startOnADaemonThread(HttpServer toStart) throws IOException {
        var failure = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
        Thread starter = new Thread(() -> {
            try {
                toStart.start();
            } catch (RuntimeException e) {
                failure.set(e);
            }
        }, "mfam-pack-host-starter");
        starter.setDaemon(true);
        starter.start();
        try {
            starter.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server = null;
            throw new IOException("interrupted while starting the pack host", e);
        }
        RuntimeException failed = failure.get();
        if (failed != null) {
            // Leave nothing half-started behind: `start` returns early when `server != null`, so a
            // failed attempt that kept the field would make every later call report a host that is
            // not listening.
            server = null;
            throw failed;
        }
    }

    /** The bound port, or -1 when not started. */
    public static synchronized int port() {
        return server == null ? -1 : port;
    }

    // ── WHAT HAPPENED TO THE LAST PACK WE OFFERED (health.json's packhost.lastPushResult) ──────
    //
    // Here rather than in one of the two services, because there are two of them — the item pack at
    // JOIN and the rig pack at SCULPT (docs/ATOM_RIG_COST.md §14) — and an operator's question is
    // "did a pack reach anybody", not "which service was it". Null until something is offered, the
    // same "never happened is null" rule HealthSnapshot uses for its timestamps.

    private static volatile String lastPushResult;

    /**
     * Record how the last pack offer went, in a few words a status screen can print.
     *
     * <p>Deliberately not a boolean: {@code WITHHELD} is a correct, deliberate outcome (it was
     * the pack rig's interim-pack withholding; the pack rig is deleted), and an
     * operator seeing {@code false} would go looking for a bug in the one case that is working
     * exactly as designed.
     */
    public static void notePush(String result) {
        if (result != null && !result.isBlank()) {
            lastPushResult = result.strip();
        }
    }

    /** @see #notePush(String) */
    public static String lastPushResult() {
        return lastPushResult;
    }

    /**
     * Stop listening and forget every pack. Idempotent.
     *
     * <p>Called from {@link ItemPackService#onServerStopping} — belt to
     * {@link #startOnADaemonThread}'s braces. A daemon dispatcher lets the JVM exit, but it does
     * not free the bound port while the process lives, and a headless server that runs two wishes
     * back to back is the same process twice.
     */
    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            BY_SHA1.clear();
        }
    }

    /**
     * Zip a pack directory, hash it, hold it, and return the URL a client should fetch.
     *
     * @param packRoot       the pack directory to serve (the item pack's,)
     * @param advertiseHost  the host a CLIENT should connect to (see the class javadoc)
     */
    public static synchronized String publish(Path packRoot, String advertiseHost)
            throws IOException {
        if (server == null) {
            throw new IllegalStateException("the pack host is not running");
        }
        byte[] zip = zip(packRoot);
        String sha1 = sha1(zip);
        // Keyed by content, so republishing an unchanged pack is genuinely free and a client that
        // already has this hash can skip the fetch entirely.
        BY_SHA1.put(sha1, zip);
        String url = PackDelivery.url(advertiseHost, port, sha1);
        LOG.info("pack published: {} bytes, sha1 {}, at {}", zip.length, sha1, url);
        return url;
    }

    /** The sha1 of the last thing published under this URL, for the vanilla push that needs it. */
    public static String sha1Of(String url) {
        int slash = url.lastIndexOf('/');
        int dot = url.lastIndexOf(".zip");
        return slash < 0 || dot < slash ? null : url.substring(slash + 1, dot);
    }

    /**
     * Deterministically zip a directory.
     *
     * <p><b>Sorted, and with no timestamps.</b> A zip whose entry order or mtimes vary produces a
     * different sha1 for identical content, which would defeat the cache on both sides and make
     * every republish look like a change — turning "an unchanged pack costs nothing" into a
     * multi-second reload on a stock client for no reason at all.
     */
    static byte[] zip(Path packRoot) throws IOException {
        if (!Files.isDirectory(packRoot)) {
            throw new IOException("no pack at " + packRoot);
        }
        var out = new java.io.ByteArrayOutputStream();
        List<Path> files;
        try (var walk = Files.walk(packRoot)) {
            files = walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> packRoot.relativize(p).toString()))
                    .toList();
        }
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Path f : files) {
                ZipEntry e = new ZipEntry(
                        packRoot.relativize(f).toString().replace(java.io.File.separatorChar, '/'));
                // A fixed time, not "now": see the javadoc. Zero is the epoch and is as good as any
                // other constant.
                e.setTime(0L);
                zos.putNextEntry(e);
                zos.write(Files.readAllBytes(f));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    static String sha1(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(bytes);
            StringBuilder sb = new StringBuilder(40);
            for (byte b : d) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by the JVM spec", e);
        }
    }
}
