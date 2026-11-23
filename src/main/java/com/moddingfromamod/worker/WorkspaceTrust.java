package com.moddingfromamod.worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>Tell the Claude Code CLI that the wish workers' own directory is trusted</b> — before any
 * worker is launched in it.
 *
 * <p>The CLI refuses to start in a directory nobody has accepted its trust prompt for:
 * <i>"Workspace not trusted. Run `claude` in &lt;dir&gt; once and accept the trust prompt, then
 * retry."</i> — printed to the worker's log, and the process exits. The pool then sees a worker
 * that booted and never called {@code await_wish}, reports it DEAF (an earlier issue's symptom, a different
 * cause), replaces it, and the replacement dies the same way. No wish is ever heard.
 *
 * <p>Measured on the cloud rig (2026-09-24): the SHIPPED game image on a fresh machine had
 * no {@code ~/.claude.json} at all, so every worker died like that; and reproduced locally in one
 * command each way — a clean HOME printed the refusal, the same HOME with one trust record printed
 * {@code backgrounded}. {@code docs/WISH_WATCH.md} did record "trust the folder once per machine"
 * as a manual step, and nothing — the image, the installer, preflight, the runbook — ever did it,
 * so every fresh install (the Linux home server's first boot included) would have started deaf.
 *
 * <p>The directory is the pool's OWN — a scratch directory holding only its MCP config, created by
 * this program — so trusting it is not trusting anything the operator wrote. The rest of the file
 * is the CLI's and is preserved: only {@code projects[dir].hasTrustDialogAccepted} is set, and a
 * file that does not parse is left alone and reported rather than overwritten.
 *
 * <p>The JSON edit is pure ({@link #withTrust}) and unit-tested; {@link #ensure} is the thin IO.
 */
public final class WorkspaceTrust {

    private WorkspaceTrust() {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** What {@link #ensure} did, for one log line. */
    public enum Outcome {
        /** The record was already there. Nothing was written. */
        ALREADY,
        /** The record was added (or the file created). */
        WRITTEN,
        /** The existing file is not JSON the CLI could have written; it was left untouched. */
        UNREADABLE
    }

    /**
     * The CLI's config with {@code dir} marked trusted, or {@code null} if {@code existing} is not
     * a JSON object (an unreadable file is the operator's problem to see, not ours to overwrite).
     *
     * @param existing the current file's text, or null/blank when there is no file yet
     */
    public static String withTrust(String existing, String dir) {
        JsonObject root;
        if (existing == null || existing.isBlank()) {
            root = new JsonObject();
        } else {
            try {
                JsonElement e = JsonParser.parseString(existing);
                if (!e.isJsonObject()) {
                    return null;
                }
                root = e.getAsJsonObject();
            } catch (RuntimeException e) {
                return null;
            }
        }
        JsonObject projects = root.has("projects") && root.get("projects").isJsonObject()
                ? root.getAsJsonObject("projects") : new JsonObject();
        JsonObject project = projects.has(dir) && projects.get(dir).isJsonObject()
                ? projects.getAsJsonObject(dir) : new JsonObject();
        project.addProperty("hasTrustDialogAccepted", true);
        projects.add(dir, project);
        root.add("projects", projects);
        return GSON.toJson(root);
    }

    /** True when {@code json} already marks {@code dir} trusted. */
    public static boolean isTrusted(String json, String dir) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            JsonObject p = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("projects");
            JsonObject d = p == null ? null : p.getAsJsonObject(dir);
            return d != null && d.has("hasTrustDialogAccepted")
                    && d.get("hasTrustDialogAccepted").getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Where the CLI keeps that file: {@code $CLAUDE_CONFIG_DIR/.claude.json} when that is set,
     * else {@code ~/.claude.json}.
     */
    public static Path configPath() {
        String dir = System.getenv("CLAUDE_CONFIG_DIR");
        if (dir != null && !dir.isBlank()) {
            return Path.of(dir).resolve(".claude.json");
        }
        return Path.of(System.getProperty("user.home")).resolve(".claude.json");
    }

    /** Make sure {@code workDir} is trusted in the CLI config at {@code config}. */
    public static Outcome ensure(Path config, Path workDir) throws IOException {
        String dir = workDir.toAbsolutePath().normalize().toString();
        String existing = Files.isRegularFile(config)
                ? Files.readString(config, StandardCharsets.UTF_8) : null;
        if (isTrusted(existing, dir)) {
            return Outcome.ALREADY;
        }
        String next = withTrust(existing, dir);
        if (next == null) {
            return Outcome.UNREADABLE;
        }
        Path parent = config.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // Temp file + atomic move: the CLI rewrites this file itself, and a half-written one is a
        // config it would refuse to read at all.
        Path tmp = Files.createTempFile(parent, ".claude.json.", ".tmp");
        Files.writeString(tmp, next, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, config, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, config, StandardCopyOption.REPLACE_EXISTING);
        }
        return Outcome.WRITTEN;
    }
}
