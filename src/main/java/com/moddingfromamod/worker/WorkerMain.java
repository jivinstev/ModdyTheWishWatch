package com.moddingfromamod.worker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The wish worker container's runner — <b>Minecraft-free, and deliberately stupid</b>.
 *
 * <p>HO-6. The server keeps DECIDING: {@code PoolPlan}, {@code WishSlots} and {@code IdlePolicy}
 * do not move, and this process never forms an opinion about how many workers there should be. It
 * reads one number the server publishes and converges on it.
 *
 * <p>⚠ <b>The number is a LEVEL, never a delta, and that is what makes this safe to write at all.</b>
 * A runner that reads "spawn one more" twice — a retry, a duplicated poll, a stale read — launches
 * two. A runner that reads "you should have two" twice still has two. Every failure mode of this
 * loop therefore converges instead of accumulating, which matters because the accumulating
 * direction costs money: a parked worker bills ~$0.033 a loop at ~65 loops an hour, so three of
 * them are ~$6.40/h doing nothing.
 *
 * <p>⚠ <b>Reaping travels on that same number.</b> {@code desired < running} IS the stop
 * instruction, so there is no arrangement in which this process can be told to start workers and
 * never be told to stop them.
 *
 * <p><b>What it does NOT have</b>, and each absence is a requirement rather than an oversight: no
 * Docker socket (A4 forbids it), no world, no server environment file, no RCON, no shared filesystem with
 * the server. Everything the agent legitimately needs is an MCP tool, which is why a worker needs
 * nothing but the MCP URL, a token, and the CLI.
 */
public final class WorkerMain {

    /** How often to ask. Short enough that a child does not wait, long enough to be free. */
    private static final long POLL_MS = Long.getLong("moddy.worker.pollMs", 5_000L);

    /** Prometheus gauge published by the server (HO-2/HO-6). */
    private static final Pattern DESIRED =
            Pattern.compile("(?m)^moddy_agent_desired_workers(?:\\{[^}]*})?\\s+([0-9]+(?:\\.[0-9]+)?)\\s*$");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * The CLI's own id for each background session — <b>not</b> a {@link Process}.
     *
     * <p>⚠ FOUND BY RUNNING IT, and it is the failure this chunk is about. {@code claude --bg}
     * <b>detaches</b>: the process we launch prints {@code backgrounded · <id> · <name>} and exits
     * <b>rc=0 within a second</b>, so a runner that counts Process handles sees zero workers
     * immediately, decides it is short, and starts another — every poll, forever. Measured: one
     * session started, `a worker exited on its own (rc=0)`, another started, and no wish was ever
     * claimed because the runner never believed it had a worker. That is the ~$6.40/h runaway
     * `ModdyWorkersNotConverging` exists to catch, arriving through the door marked "convergence".
     *
     * <p>{@code AgentSession} has known this since it was written (its {@code sessionIds} javadoc
     * says {@code destroy()} kills nothing) — the server tracks ids and stops them with
     * {@code claude stop <id>}. This does the same, for the same reason.
     */
    private final List<String> sessions = new ArrayList<>();

    private String claudeCli = "claude";

    private WorkerMain() {
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static String require(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            // Named, and fatal. A worker that starts with no server to talk to produces wishes
            // that time out, which reads as a dead agent rather than as a missing variable —
            // this chunk's own headline risk.
            throw new IllegalStateException(key + " is not set. A worker with no " + key
                    + " cannot reach the wish server, and a wish that times out looks like a "
                    + "broken agent rather than a missing setting.");
        }
        return v.trim();
    }

    public static void main(String[] args) throws Exception {
        String mcpUrl = require("MODDY_MCP_URL");
        String metricsUrl = require("MODDY_METRICS_URL");
        String claudeCli = env("MODDY_CLAUDE_CLI", "claude");
        Path tokenFile = Path.of(env("MODDY_TOKEN_FILE", "/run/secrets/moddy-token"));
        Path workDir = Path.of(env("MODDY_WORKER_DIR", "/srv/worker"));

        String token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
        if (token.isEmpty()) {
            throw new IllegalStateException(tokenFile + " is empty. The MCP endpoint requires a "
                    + "bearer token on every request; an empty one is rejected by the server and "
                    + "every wish would fail authentication rather than time out.");
        }
        Files.createDirectories(workDir);
        Path mcpConfig = writeMcpConfig(workDir, mcpUrl, token);

        String allowedTools = WorkerArgv.allowedToolsFromResource();
        String model = env("MODDY_MODEL", "");
        String effort = env("MODDY_EFFORT", "");

        say("runner up — mcp=" + mcpUrl + " metrics=" + metricsUrl
                + " tools=" + allowedTools.split(",").length + " model="
                + (model.isEmpty() ? "<cli default>" : model)
                + " effort=" + (effort.isEmpty() ? "<cli default>" : effort));

        WorkerMain runner = new WorkerMain();
        runner.claudeCli = claudeCli;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> runner.stopAll("shutdown")));
        runner.loop(metricsUrl, claudeCli, mcpConfig, allowedTools, model, effort);
    }

    private void loop(String metricsUrl, String claudeCli, Path mcpConfig,
                      String allowedTools, String model, String effort) throws InterruptedException {
        while (true) {
            Integer desired = readDesired(metricsUrl);
            if (desired == null) {
                // ⚠ DO NOTHING on an unreadable answer, rather than assuming zero or one. The
                // server being briefly unreachable — a restart, a slow scrape — must not stop a
                // worker that is mid-wish, and must not start one either. Holding is the only
                // action that is wrong in neither direction.
                say("could not read desiredWorkers; holding at " + sessions.size());
            } else {
                converge(desired, claudeCli, mcpConfig, allowedTools, model, effort);
            }
            Thread.sleep(POLL_MS);
        }
    }

    private void converge(int desired, String claudeCli, Path mcpConfig,
                          String allowedTools, String model, String effort) {
        while (sessions.size() > desired) {
            stopOne("the pool wants " + desired);
        }
        while (sessions.size() < desired) {
            // ONE AT A TIME, and the loop re-reads the level next tick. Starting the whole
            // shortfall at once is how a momentary spike becomes a bill.
            if (!startOne(claudeCli, mcpConfig, allowedTools, model, effort)) {
                break;
            }
        }
    }

    private boolean startOne(String claudeCli, Path mcpConfig, String allowedTools,
                             String model, String effort) {
        String name = "wish-worker-" + (sessions.size() + 1);
        // The CLI refuses an untrusted working directory and a fresh container has no trust
        // record at all — the same launch-and-die AgentSession's pool hit (WorkspaceTrust).
        try {
            WorkspaceTrust.ensure(WorkspaceTrust.configPath(), Path.of("").toAbsolutePath());
        } catch (java.io.IOException e) {
            say("could not record the working directory as trusted for the CLI: " + e);
        }
        List<String> cmd = WorkerArgv.build(claudeCli, name, WorkerPrompt.text(),
                mcpConfig.toString(), allowedTools, model, effort, List.of());
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // The same scrub as AgentSession's pool: one rule, both launchers.
            WorkerEnv.scrub(pb.environment());
            // Every wish tool up front, on every model.
            ToolExposure.applyTo(pb.environment());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // ⚠ READ THE ID OUT OF ITS OUTPUT BEFORE IT EXITS. The launcher is gone in about a
            // second and takes the only handle on the real agent with it; without the id this
            // process could neither count the worker nor ever stop it, and the agent would
            // outlive the container — the stray daemon AgentSession's own javadoc warns about.
            String id = readSessionId(p);
            if (id == null) {
                say("started " + name + " but the CLI never printed a session id — refusing to "
                        + "count a worker this process could not stop");
                return false;
            }
            sessions.add(id);
            say("started " + name + " as session " + id + " (" + sessions.size() + " running)");
            return true;
        } catch (IOException e) {
            say("could not start " + name + ": " + e);
            return false;
        }
    }

    /** Parse {@code backgrounded · <id> · <name>} — the same shape AgentSession parses. */
    private static String readSessionId(Process p) {
        Pattern pat = Pattern.compile("backgrounded\\s*\\W\\s*(\\w{6,})");
        try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(
                p.getInputStream(), StandardCharsets.UTF_8))) {
            long deadline = System.currentTimeMillis() + 30_000L;
            String line;
            while (System.currentTimeMillis() < deadline && (line = r.readLine()) != null) {
                Matcher m = pat.matcher(line);
                if (m.find()) {
                    return m.group(1);
                }
            }
        } catch (IOException e) {
            say("could not read the CLI's output: " + e);
        }
        return null;
    }

    private void stopOne(String why) {
        String id = sessions.remove(sessions.size() - 1);
        // `claude stop <id>` — the same call AgentSession makes, and the only thing that reaches
        // a detached session. destroy() on the launcher kills nothing: it exited long ago.
        try {
            Process stop = new ProcessBuilder(claudeCli, "stop", id)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            // ⚠ stdin is CLOSED rather than redirected to the null device, which is what all
            // eleven older sites do and what PlatformParityTest ratchets DOWN: a child's Windows
            // machine has no such path, so each of those is a process that cannot start there.
            // Closing the default pipe gives the child an immediate EOF — the same thing an empty
            // stdin means — and costs nothing on any platform. (Redirect.DISCARD is a WRITE
            // redirect and cannot be used for input, so it is not the alternative here.)
            // NB that sweep is a text sweep over source: writing the constructor form in a
            // COMMENT counts as a site, which is how this comment came to be phrased this way.
            stop.getOutputStream().close();
            if (!stop.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                stop.destroyForcibly();
            }
        } catch (IOException e) {
            say("could not stop session " + id + " (" + e + ") — it may still be running");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        say("stopped session " + id + " — " + why + " (" + sessions.size() + " running)");
    }

    private void stopAll(String why) {
        while (!sessions.isEmpty()) {
            stopOne(why);
        }
    }

    private static Integer readDesired(String metricsUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(metricsUrl))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return null;
            }
            Matcher m = DESIRED.matcher(res.body());
            // ⚠ ABSENT IS ABSENT, NEVER ZERO — the rule MetricsExposition is built on. A server
            // that publishes no gauge has not said "stop all workers"; it has said nothing, and
            // reading it as zero would reap a healthy pool every time the field is missing.
            return m.find() ? (int) Double.parseDouble(m.group(1)) : null;
        } catch (IOException | InterruptedException | RuntimeException e) {
            return null;
        }
    }

    private static Path writeMcpConfig(Path dir, String url, String token) throws IOException {
        // ⚠ The 900 s timeout is what lets a worker PARK on await_wish instead of polling, and it
        // is measured rather than guessed: without it the CLI aborts any tool call at 60 s, so
        // await_wish could never hold longer than that however the server configured itself.
        // 900 s here against AwaitWishTool's 540 s hold, so the SERVER always returns first and
        // the client never aborts.
        String json = "{\"mcpServers\":{\"wishwatch\":{\"type\":\"http\","
                + "\"url\":\"" + url + "\","
                + "\"timeout\":900000,"
                + "\"headers\":{\"Authorization\":\"Bearer " + token + "\"}}}}";
        Path file = dir.resolve("mcp.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private static void say(String msg) {
        System.out.println("[wish-worker] " + msg);
        System.out.flush();
    }
}
