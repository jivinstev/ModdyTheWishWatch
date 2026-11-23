package com.moddingfromamod.agent;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * <b>A wish worker on Muse Code</b> ({@code muse exec}), for a player's own Muse Code sign-in or their own
 * {@code MUSE_API_KEY}: the lockdown
 * ({@code docs/MODEL_HARNESS.md} → "The worker's boundary") and no state shared across launches.
 *
 * <ul>
 *   <li><b>Its own config dir</b> ({@code XDG_CONFIG_HOME} → the run's directory), holding only OUR MCP server: the
 *       player's own MCP servers, skills and rules never reach a child-facing worker. Muse Code reads
 *       {@code $XDG_CONFIG_HOME/muse/settings.json} (measured 1.4.23: it connects to that server and to no
 *       other).</li>
 *   <li><b>Subscription</b>: no key at all (every key variable removed, since a key out-ranks the sign-in), and the
 *       player's own {@code auth.json} copied in; a token Muse Code refreshed in the copy is written back to the
 *       player's file ({@link #writeBack}), guarded against a stale overwrite. No account files, no quota or
 *       login state shared across launches: a spent window simply ends the worker, and the wish is retried by the
 *       pool like any other worker's.</li>
 *   <li><b>API key</b>: {@code --api-key-stdin} with {@code MUSE_API_KEY}, the only place a key is read from: the
 *       player's own key, Wonder included (D27, offered with its price shown). A server whose settings
 *       override locks the model never reaches this worker.</li>
 *   <li><b>The lockdown</b>: {@link #LOCKDOWN} always; {@link #LOCKDOWN_IF_SUPPORTED} when this binary's
 *       {@code exec --help} lists it (an unknown flag would stop every worker at start).</li>
 *   <li><b>A pinned session id</b>, so what the run spent is read back from Muse Code's own session log
 *       ({@link MuseUsage}).</li>
 * </ul>
 *
 * <p>Not detached: the process IS the worker, so {@code destroy()} stops it and nothing outlives the game. The pure
 * parts ({@link #argv}, {@link #settingsJson}, {@link #applyEnv}, {@link #writeBack}) are {@code MuseWorkerTest}'s.
 */
public final class MuseWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger("moddingfromamod/muse-worker");

    /**
     * Always. {@code --yolo} alone offers 32 built-ins (bash, read_file, write_file, web_fetch…); {@code --preset
     * empty} offers none and keeps every MCP tool; the three {@code --disable-*} flags are a second lock. The
     * fixed list; {@code MuseWorkerTest} pins it.
     */
    public static final List<String> LOCKDOWN = List.of("--yolo", "--preset", "empty", "--disable-shell",
            "--disable-write", "--disable-web-tools");

    /** Added only when {@code muse exec --help} lists them: no foreign personal rules or skills, no reminders. */
    public static final List<String> LOCKDOWN_IF_SUPPORTED = List.of("--no-foreign-personal-context",
            "--disable-reminders");

    /** Claude effort → Muse reasoning effort. Only mapped values are passed on. */
    static final Map<String, String> EFFORT = Map.of("low", "low", "medium", "medium", "high", "high",
            "xhigh", "xhigh", "max", "ultra");

    /** A worker's id in the pool's session list: never a Claude session id, so {@code claude stop} is never asked. */
    static final String ID_PREFIX = "musew";

    /** The MCP server's name, as the Claude workers see it, so the tool names ({@code mcp__wishwatch__*}) match. */
    static final String SERVER = "wishwatch";

    private MuseWorker() {
    }

    /** Is {@code id} one of these workers (stopped by destroying its process, never through a CLI)? */
    public static boolean isMuseId(String id) {
        return id != null && id.startsWith(ID_PREFIX);
    }

    // ── pure ───────────────────────────────────────────────────────────────────────────────────────────────────

    /** The {@link #LOCKDOWN_IF_SUPPORTED} flags {@code helpText} names, in order; none when it is null. */
    static List<String> supportedLocks(String helpText) {
        List<String> out = new ArrayList<>();
        if (helpText == null) {
            return out;
        }
        for (String f : LOCKDOWN_IF_SUPPORTED) {
            if (Pattern.compile("(^|\\s)" + Pattern.quote(f) + "(\\s|,|=|$)", Pattern.MULTILINE).matcher(helpText)
                    .find()) {
                out.add(f);
            }
        }
        return out;
    }

    /** {@code muse:muse-wonder-1.0} → {@code muse-wonder-1.0}; a bare id is returned as it is. */
    static String bareModel(String spec) {
        String s = spec == null ? "" : spec.strip();
        return s.startsWith("muse:") ? s.substring(5) : s;
    }

    /**
     * The worker's command line. The key never appears here: with {@code apiKey} it goes on stdin.
     */
    static List<String> argv(String bin, boolean apiKey, List<String> extraLocks, Path promptFile, String model,
                             String effort, String sessionId) {
        List<String> cmd = new ArrayList<>(List.of(bin, "exec"));
        if (apiKey) {
            cmd.add("--api-key-stdin");
        }
        cmd.addAll(LOCKDOWN);
        cmd.addAll(extraLocks);
        cmd.add("--prompt-file");
        cmd.add(promptFile.toString());
        String m = bareModel(model);
        if (!m.isEmpty()) {
            cmd.add("--model");
            cmd.add(m);
        }
        String e = effort == null ? null : EFFORT.get(effort);
        if (e != null) {
            cmd.add("--reasoning-effort");
            cmd.add(e);
        }
        cmd.add("--session-id");
        cmd.add(sessionId);
        return cmd;
    }

    /** The run's {@code muse/settings.json}: our MCP server and nothing else (the Claude workers' shape). */
    static String settingsJson(int port, String token, String worker, long timeoutMs) {
        JsonObject headers = new JsonObject();
        headers.addProperty("Authorization", "Bearer " + token);
        headers.addProperty(AgentSession.WORKER_HEADER, worker);
        JsonObject server = new JsonObject();
        server.addProperty("enabled", true);
        server.addProperty("type", "http");
        server.addProperty("url", "http://127.0.0.1:" + port + McpHttpServer.PATH);
        server.addProperty("timeout", timeoutMs);
        server.add("headers", headers);
        JsonObject servers = new JsonObject();
        servers.add(SERVER, server);
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", 1);
        root.add("mcpServers", servers);
        return root.toString();
    }

    /** Every variable that carries a key Muse Code (or a CLI it might start) would read before the sign-in. */
    static final List<String> KEY_VARS = List.of("MUSE_API_KEY", "META_API_KEY", "ANTHROPIC_API_KEY",
            "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL", "OPENAI_API_KEY", "OPENROUTER_API_KEY");

    /**
     * The worker's environment: not the session that started the game ({@code WorkerEnv.scrub}), no key in either
     * mode (the API key goes on stdin), no foreign config dir, and {@code XDG_CONFIG_HOME} at the run's own.
     */
    static void applyEnv(Map<String, String> env, Path xdgConfigHome) {
        com.moddingfromamod.worker.WorkerEnv.scrub(env);
        env.remove("CLAUDE_CONFIG_DIR");
        env.remove("CODEX_HOME");
        KEY_VARS.forEach(env::remove);
        env.put("XDG_CONFIG_HOME", xdgConfigHome.toString());
    }

    /** The player's own Muse Code config root: {@code $XDG_CONFIG_HOME/muse}, else {@code ~/.config/muse}. */
    static Path playerConfigRoot(Function<String, String> env, String home) {
        String xdg = env.apply("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg.strip()) : Path.of(home, ".config");
        return base.resolve("muse");
    }

    /**
     * Copy the player's {@code auth.json} into the run ({@code runMuseDir}). Returns the copy's modification time, or
     * -1 when the player has no sign-in file (Muse Code then says so in the worker's log, and the probe says
     * "run `muse login`" before a player can pick it).
     */
    static long carryLogin(Path playerRoot, Path runMuseDir) throws IOException {
        Path auth = playerRoot.resolve("auth.json");
        if (!Files.isRegularFile(auth)) {
            return -1;
        }
        Path dst = runMuseDir.resolve("auth.json");
        Files.copy(auth, dst, StandardCopyOption.REPLACE_EXISTING);
        restrict(dst);
        return Files.getLastModifiedTime(dst).toMillis();
    }

    /**
     * Hand a token Muse Code refreshed in the run's copy back to the player's file. Only a regular file Muse Code
     * rewrote AFTER the copy, that is still an OAuth sign-in and differs from the player's: so a signed-out copy is
     * never written over a good one. Atomic (a temp file, then a rename). Returns whether it wrote.
     */
    static boolean writeBack(Path copy, long copiedMtime, Path playerRoot) {
        try {
            if (copiedMtime < 0 || !Files.isRegularFile(copy, LinkOption.NOFOLLOW_LINKS)
                    || Files.getLastModifiedTime(copy).toMillis() <= copiedMtime) {
                return false;
            }
            byte[] bytes = Files.readAllBytes(copy);
            var doc = com.google.gson.JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!doc.isJsonObject()) {
                return false;
            }
            var providers = doc.getAsJsonObject().getAsJsonObject("providers");
            var meta = providers == null ? null : providers.getAsJsonObject("meta");
            if (meta == null || !meta.has("mechanism") || !"oauth".equals(meta.get("mechanism").getAsString())) {
                return false;
            }
            Path master = playerRoot.resolve("auth.json");
            if (Files.isRegularFile(master) && java.util.Arrays.equals(Files.readAllBytes(master), bytes)) {
                return false;
            }
            Files.createDirectories(playerRoot);
            Path tmp = playerRoot.resolve(".auth.json." + Long.toHexString(System.nanoTime()) + ".tmp");
            Files.write(tmp, bytes);
            restrict(tmp);
            Files.move(tmp, master, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void restrict(Path p) {
        try {
            Files.setPosixFilePermissions(p, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows: the user's own profile directory already restricts it
        }
    }

    // ── the process ────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * One launched worker: its pool id, Muse Code's session id (for {@link MuseUsage}), how it is paid for, the model,
     * when it started, and the sign-in copy to write back.
     */
    public record Launch(String id, String museSession, boolean apiKey, String model, long startedMs, Path authCopy,
                         long authCopiedMtime) {
    }

    /** Every worker launched by this JVM, by pool id (finished ones stay, for {@link MuseUsage} and write-back). */
    private static final Map<String, Launch> LAUNCHED = new ConcurrentHashMap<>();

    /** The launches since {@code sinceMs} (the Magician check reads what its wish model spent). */
    public static List<Launch> launchedSince(long sinceMs) {
        return LAUNCHED.values().stream().filter(l -> l.startedMs() >= sinceMs).toList();
    }

    /** {@code muse exec --help}, or null when the binary will not answer it. */
    static String helpText(String bin) {
        try {
            Process p = new ProcessBuilder(bin, "exec", "--help").redirectErrorStream(true)
                    .redirectInput(ProcessBuilder.Redirect.from(new File(nullDevice()))).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(20, TimeUnit.SECONDS) || p.exitValue() != 0) {
                p.destroyForcibly();
                return null;
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String nullDevice() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows") ? "NUL"
                : "/dev/null";
    }

    /** Write back any token a worker refreshed (every launch first, so a new worker starts on the newest token). */
    static void writeBackAll(Path playerRoot) {
        for (Launch l : LAUNCHED.values()) {
            if (l.authCopy() != null && writeBack(l.authCopy(), l.authCopiedMtime(), playerRoot)) {
                LOGGER.info("muse worker {}: wrote a refreshed Muse Code sign-in back to {}", l.id(), playerRoot);
                LAUNCHED.put(l.id(), new Launch(l.id(), l.museSession(), l.apiKey(), l.model(), l.startedMs(),
                        l.authCopy(), lastModified(l.authCopy())));
            }
        }
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Start one worker. {@code dir} is the pool's directory (the run gets a fresh subdirectory), {@code log} the
     * worker's own appended log. Returns the process and its launch, or throws when it could not start.
     */
    static Map.Entry<Process, Launch> start(String bin, Path dir, Path log, String worker, String prompt, int port,
                                            String token, String modelSpec, String effort, boolean apiKey,
                                            long mcpTimeoutMs) throws IOException {
        return start(bin, dir, log, worker, prompt, port, token, modelSpec, effort, apiKey, mcpTimeoutMs,
                System::getenv, System.getProperty("user.home", ""));
    }

    /** {@link #start} with the environment and home handed in ({@code MuseWorkerLaunchTest}). */
    static Map.Entry<Process, Launch> start(String bin, Path dir, Path log, String worker, String prompt, int port,
                                            String token, String modelSpec, String effort, boolean apiKey,
                                            long mcpTimeoutMs, Function<String, String> env, String home)
            throws IOException {
        String key = env.apply("MUSE_API_KEY");
        if (apiKey && (key == null || key.isBlank())) {
            throw new IOException("API-key mode needs MUSE_API_KEY where Minecraft starts");
        }
        Path playerRoot = playerConfigRoot(env, home);
        writeBackAll(playerRoot);
        String id = ID_PREFIX + Long.toHexString(System.nanoTime() & 0xffffffffffL);
        Path run = dir.resolve("muse-" + worker + "-" + id);
        Path museDir = run.resolve("muse");
        Files.createDirectories(museDir);
        Path settings = museDir.resolve("settings.json");
        Files.writeString(settings, settingsJson(port, token, worker, mcpTimeoutMs), StandardCharsets.UTF_8);
        restrict(settings);
        Path promptFile = run.resolve("prompt.txt");
        Files.writeString(promptFile, prompt, StandardCharsets.UTF_8);
        long copied = -1;
        Path authCopy = null;
        if (!apiKey) {
            copied = carryLogin(playerRoot, museDir);
            authCopy = copied < 0 ? null : museDir.resolve("auth.json");
            if (copied < 0) {
                LOGGER.warn("muse worker {}: no Muse Code sign-in at {} — run `muse login`", worker, playerRoot);
            }
        }
        String museSession = UUID.randomUUID().toString();
        List<String> cmd = argv(bin, apiKey, supportedLocks(helpText(bin)), promptFile, modelSpec, effort,
                museSession);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        applyEnv(pb.environment(), run);
        pb.directory(run.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        if (!apiKey) {
            pb.redirectInput(ProcessBuilder.Redirect.from(new File(nullDevice())));
        }
        Process p = pb.start();
        if (apiKey) {
            try (OutputStream in = p.getOutputStream()) {
                in.write(key.strip().getBytes(StandardCharsets.UTF_8));
            }
        }
        Launch launch = new Launch(id, museSession, apiKey, bareModel(modelSpec), System.currentTimeMillis(),
                authCopy, copied);
        LAUNCHED.put(id, launch);
        // Never the prompt, the key or the token: the command line without them.
        LOGGER.info("muse worker {} ({}): {} on {}, session {}", worker, id, launch.model(),
                apiKey ? "MUSE_API_KEY" : "the Muse Code sign-in", museSession);
        p.onExit().thenRun(() -> {
            writeBackAll(playerRoot);
            LOGGER.info("muse worker {} ({}) ended with exit {}", worker, id, p.exitValue());
        });
        return Map.entry(p, launch);
    }
}
