package com.moddingfromamod.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * <b>A redirected wish worker gets its OWN Claude CLI config directory, and so its own
 * {@code --bg} daemon</b>.
 *
 * <p>Every {@code claude --bg} goes through one daemon per config directory, and a new session runs
 * with the DAEMON's environment, not the one its launcher set. So when any other background session
 * was already alive on the machine — an Opus worker, an admin session, another test arm — a Spark
 * worker launched after it silently ran on the Claude API with that session's sign-in, and the API
 * refused {@code muse-spark-1.2-contributor} ("There's an issue with the selected model"). Measured
 * on CLI 2.1.295 and 2.1.312 alike; Spark alone worked on every version. With its own
 * {@code CLAUDE_CONFIG_DIR} the same launch answered while the other daemon was alive.
 *
 * <p>Only a REDIRECT is moved (a worker pointed at a non-default endpoint, {@link
 * WishModel.Resolved#redirects()}); a worker on this machine's own sign-in keeps the default
 * directory, where its credentials are. The directory's {@code projects/} is a link to the default
 * one, so the transcripts land exactly where {@code wish-cost.py}, the harvest and every other
 * reader already look. {@code jobs/} is deliberately NOT shared: it is the daemon's own state, and
 * the health check reads both ({@code ops.CliJobStates}).
 */
public final class WorkerCliConfig {

    /** The CLI's own variable. */
    public static final String VAR = "CLAUDE_CONFIG_DIR";

    private WorkerCliConfig() {
    }

    /**
     * Where a redirected provider's CLI config lives: {@code <modHome>/cli/<provider>}, or null for
     * a worker that is not redirected (it keeps the CLI's default directory).
     */
    public static Path dirFor(Path modHome, WishModel.Resolved endpoint) {
        if (endpoint == null || !endpoint.redirects()) {
            return null;
        }
        String p = endpoint.provider() == null ? "" : endpoint.provider().toLowerCase(Locale.ROOT);
        String safe = p.replaceAll("[^a-z0-9_-]", "");
        return modHome.resolve("cli").resolve(safe.isEmpty() ? "redirect" : safe);
    }

    /**
     * Create {@code dir} and link its {@code projects/} to {@code defaultDir/projects}, so the
     * transcripts stay where every reader looks. Idempotent; an existing real {@code projects/}
     * directory is left alone rather than replaced (its transcripts would be lost).
     */
    public static void prepare(Path dir, Path defaultDir) throws IOException {
        Files.createDirectories(dir);
        Path shared = defaultDir.resolve("projects");
        Files.createDirectories(shared);
        Path link = dir.resolve("projects");
        if (Files.isSymbolicLink(link) || Files.exists(link)) {
            return;
        }
        Files.createSymbolicLink(link, shared.toAbsolutePath());
    }

    /** The directory this JVM's own CLI calls use: {@code $CLAUDE_CONFIG_DIR}, else {@code ~/.claude}. */
    public static Path defaultDir() {
        String set = System.getenv(VAR);
        if (set != null && !set.isBlank()) {
            return Path.of(set);
        }
        return Path.of(System.getProperty("user.home")).resolve(".claude");
    }

    /** Point a CLI subprocess at {@code dir}. A null dir leaves the environment alone. */
    public static void applyTo(Map<String, String> env, Path dir) {
        if (dir != null) {
            env.put(VAR, dir.toString());
        }
    }
}
