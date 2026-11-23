package com.moddingfromamod.worker;

import java.util.Map;
import java.util.Set;

/**
 * <b>A wish worker does not inherit the identity of whatever launched it</b>.
 *
 * <p>When the game is started from inside another Claude Code session — every cloud session, and
 * any grown-up running the server from a Claude terminal — the launcher's environment carries that
 * session's identity: {@code CLAUDE_CODE_SESSION_ID}, {@code CLAUDE_CODE_REMOTE_SESSION_ID}, a
 * messaging socket and token, a diagnostics file, {@code CLAUDE_CODE_REMOTE=true}. Every worker used
 * to inherit all of it, so N background agents each believed they were the parent — the same
 * session id, the same socket, the same remote session to report into. Measured in a cloud
 * container on 2026-09-22: <b>49 {@code CLAUDE*} variables</b> reached each worker.
 *
 * <p><b>Why a keep-list rather than a strip-list.</b> The identity variables are the CLI's to
 * invent and they multiply release by release; a named strip-list is out of date the day a new one
 * ships. The variables a worker genuinely NEEDS are few and stable — where the credentials and
 * config live, and which provider to call — so those are kept by name and every other
 * {@code CLAUDE*} is dropped. {@code WishModel}'s redirect path already clears the whole prefix and
 * records why over-clearing is safe there.
 *
 * <p><b>Measured, not assumed:</b> with every {@code CLAUDE*} variable cleared, {@code claude -p}
 * still authenticated in the cloud container (2026-09-22), because the credential there lives in
 * {@code ANTHROPIC_*}, which this never touches. On a machine that signs in with
 * {@code CLAUDE_CODE_OAUTH_TOKEN} or points {@code CLAUDE_CONFIG_DIR} at its credentials, those
 * are on the keep-list. On the home Mac, where the sign-in is the keychain, there is nothing to
 * strip and this is a no-op.
 *
 * <p>Runs BEFORE the pool adds its own variables ({@code PromptCache}, {@code WishModel}), so it
 * can never remove one of ours. Minecraft-free, so the container worker shares it.
 */
public final class WorkerEnv {

    private WorkerEnv() {
    }

    /** Sign-in, config and provider selection — what a worker needs to reach a model at all. */
    static final Set<String> KEEP = Set.of(
            "CLAUDE_CLI",                          // the binary's path (ClaudeCli); not a credential
            "CLAUDE_CONFIG_DIR",                   // where the credentials and settings live
            "CLAUDE_CODE_OAUTH_TOKEN",             // a headless sign-in
            "CLAUDE_CODE_USE_BEDROCK",
            "CLAUDE_CODE_USE_VERTEX",
            "CLAUDE_CODE_USE_FOUNDRY",
            "CLAUDE_CODE_SKIP_BEDROCK_AUTH",
            "CLAUDE_CODE_SKIP_VERTEX_AUTH",
            "CLAUDE_CODE_SKIP_FOUNDRY_AUTH",
            "CLAUDE_CODE_CLIENT_CERT",
            "CLAUDE_CODE_CLIENT_KEY",
            "CLAUDE_CODE_CLIENT_KEY_PASSPHRASE",
            "CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST",
            "CLAUDE_CODE_API_KEY_HELPER_TTL_MS");

    /**
     * Drop every inherited {@code CLAUDE*} variable not on {@link #KEEP}.
     *
     * @return how many were removed, for the log line that says so
     */
    public static int scrub(Map<String, String> env) {
        int before = env.size();
        env.keySet().removeIf(k -> k != null && k.startsWith("CLAUDE") && !KEEP.contains(k));
        return before - env.size();
    }
}
