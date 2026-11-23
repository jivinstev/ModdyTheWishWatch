package com.moddingfromamod.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * <b>Which model grants a wish, and where it lives — decided at RUNTIME, not at setup time.</b>
 *
 * <p>A model is named {@code <provider>:<model-id>}, e.g. {@code muse:muse-spark-1.2}. A bare name
 * ({@code opus}, {@code sonnet}) is this machine's own Claude sign-in and redirects nothing, which
 * is what a child's game is always on.
 *
 * <p><b>Why the split.</b> Credentials are a property of the MACHINE and belong in its environment;
 * which model to grant a wish with is a property of the RUN and belongs on the command that starts
 * it. Putting the endpoint in the environment made the arm a setup-time decision — to compare two
 * models you had to reconfigure the environment between them, which is both slow and the exact
 * shape of mistake that produces an arm silently running as the control. Now the environment holds
 * one credential pair per provider, permanently, and {@code MODEL=} selects among them per run.
 *
 * <p><b>Adding a provider is one entry in {@link #PROVIDERS}</b> plus its two environment
 * variables — deliberately, so a third endpoint is a config line rather than a code edit. The same
 * argument keeps {@code COMPANION_REPOS} from naming a mod.
 *
 * <p>Pure and Minecraft-free: the environment arrives as a lookup function, so every rule below is
 * unit-testable. {@code System.getenv} cannot be set from a JUnit test, and a guard first exercised
 * in a 900-second live run is a guard nobody knows fires.
 */
public final class WishModel {

    /** A place models can be granted from: where to reach it, and what the key is called. */
    public record Provider(String name, String baseUrlEnv, String apiKeyEnv, String defaultBaseUrl) {
    }

    /**
     * The answer: the bare model id to pass as {@code --model}, and the endpoint to reach it at.
     *
     * @param baseUrl empty for the default Anthropic endpoint — see {@link #redirects()}
     */
    public record Resolved(String provider, String model, String baseUrl, String apiKey) {

        /** True when the wish worker must be pointed somewhere other than this machine's default. */
        public boolean redirects() {
            return !baseUrl.isEmpty();
        }
    }

    /**
     * The default endpoint when a provider's {@code *_BASE_URL} is unset.
     *
     * <p><b>{@code openai} is NOT OpenAI's own endpoint, and that is deliberate.</b> Every other
     * provider here speaks the Anthropic Messages API natively, so the CLI can be pointed straight
     * at it. OpenAI does not, so the default is a <i>local translation proxy</i> (LiteLLM or
     * equivalent) that accepts Anthropic requests and forwards OpenAI ones. Two consequences worth
     * knowing before an arm is believed:
     *
     * <ul>
     *   <li><b>The key MFAM sends is the PROXY's, not OpenAI's</b>, and they are named apart on
     *       purpose. The shim reads {@code OPENAI_API_KEY} for its own upstream call, so if MFAM
     *       sent that same variable as its bearer the shim would reject it against its master key.
     *       {@code OPENAI_PROXY_API_KEY} is the shim's credential; {@code OPENAI_API_KEY} is
     *       OpenAI's and MFAM never sees it.
     *   <li><b>Prompt caching and {@code --effort} both cross the proxy</b>, and both are things
     *       this project has already been burned by measuring wrong. Caching is ~84% of the bill
     * and a proxy that perturbs the prefix silently multiplies cost ~10x; effort is
     *       an Anthropic-shaped flag with no OpenAI equivalent, so a proxy that drops it leaves
     *       the arm running at the endpoint's default — the failure, where 25 consecutive
     *       runs measured a configuration nobody ships. Probe both before spending a wish.
     * </ul>
     *
     * <p>⚠ MEASURED 2026-08-29 in a cloud session: {@code api.openai.com} answers <b>HTTP 000</b>
     * (the egress proxy denies the CONNECT with a 403 policy denial). So do {@code
     * api.deepseek.com}, {@code api.moonshot.ai}, {@code dashscope-intl.aliyuncs.com}, {@code
     * api.z.ai} and {@code openrouter.ai}. Only {@code api.anthropic.com} and {@code api.meta.ai}
     * are reachable today. A host must be added to the environment's network allowlist before any
     * arm against it can run — the same fix that made Modrinth reachable (see CLAUDE.md). The
     * local proxy default needs no allowlist itself; the OpenAI host it forwards to does.
     */
    static final Map<String, Provider> PROVIDERS = new LinkedHashMap<>(Map.of(
            "muse", new Provider("muse", "MUSE_BASE_URL", "MUSE_API_KEY", "https://api.meta.ai"),
            "openai", new Provider("openai", "OPENAI_BASE_URL", "OPENAI_PROXY_API_KEY",
                    "http://127.0.0.1:4000")));

    /** The provider name used when no prefix is given: this machine's own Claude sign-in. */
    public static final String DEFAULT_PROVIDER = "anthropic";

    private WishModel() {
    }

    /**
     * Resolve a {@code MODEL=} spec against the environment.
     *
     * @param spec {@code muse:muse-spark-1.2}, {@code opus}, or empty for the CLI's own default
     * @param env  an environment lookup — {@code System::getenv} in production
     * @throws IllegalStateException if a known provider is named but its key is not set, or if the
     *                               prefix looks like a provider and is not one
     */
    public static Resolved resolve(String spec, UnaryOperator<String> env) {
        String s = trimmed(spec);
        int colon = s.indexOf(':');

        // NOT every colon is a provider prefix. A Bedrock-style id ends in a version segment
        // (`anthropic.claude-3-5-sonnet-20241022-v2:0`), so the prefix must additionally LOOK like
        // a provider name — lowercase, no dots — before it is treated as one. Anything else is
        // passed through whole, because silently eating part of a model id is unrecoverable.
        String prefix = colon <= 0 ? "" : s.substring(0, colon);
        boolean looksLikeProvider = !prefix.isEmpty() && prefix.matches("[a-z][a-z0-9_-]*");

        if (!looksLikeProvider) {
            return new Resolved(DEFAULT_PROVIDER, s, "", "");
        }

        Provider p = PROVIDERS.get(prefix);
        if (p == null) {
            // A TYPO MUST NOT FALL THROUGH TO THE DEFAULT. `muze:muse-spark-1.2` would otherwise be
            // sent to Anthropic as a model it has never heard of, or — worse, on a gateway — be
            // quietly substituted, and the run would record an arm it never ran.
            throw new IllegalStateException(
                    "unknown model provider '" + prefix + "' in MODEL='" + s + "'. Known providers: "
                    + String.join(", ", PROVIDERS.keySet())
                    + ". A bare name (opus, sonnet) means this machine's own Claude sign-in.");
        }

        String model = trimmed(s.substring(colon + 1));
        if (model.isEmpty()) {
            throw new IllegalStateException(
                    "MODEL='" + s + "' names the provider '" + prefix + "' but no model. "
                    + "Pass e.g. " + prefix + ":muse-spark-1.2.");
        }

        String key = trimmed(env.apply(p.apiKeyEnv()));
        if (key.isEmpty()) {
            // Falling back to the default endpoint here is the failure this whole class exists to
            // prevent: every wish would be granted, the archive would be complete, and the scores
            // would say `arm=muse` for a run that was the control.
            throw new IllegalStateException(
                    "MODEL='" + s + "' selects provider '" + prefix + "', but " + p.apiKeyEnv()
                    + " is not set. Set it in the environment, or pick a different model — "
                    + "running on the default endpoint under this arm's name would be a wrong "
                    + "result, not a missing one.");
        }

        String base = trimmed(env.apply(p.baseUrlEnv()));
        return new Resolved(prefix, model, base.isEmpty() ? p.defaultBaseUrl() : base, key);
    }

    /**
     * Apply a resolution to a child process's environment.
     *
     * <p>Mutates the map in place and touches nothing else, so the redirect reaches the wish worker
     * subprocess and no other process on the machine.
     */
    public static void applyTo(Map<String, String> env, Resolved r) {
        if (!r.redirects()) {
            return;
        }
        env.put("ANTHROPIC_BASE_URL", r.baseUrl());
        env.put("ANTHROPIC_AUTH_TOKEN", r.apiKey());
        // EVERY WISH TOOL UP FRONT is set for every worker now, redirected or not
        // (worker.ToolExposure). It started here (2026-10-21): CLI 2.1.306 defers
        // tools OPTIMISTICALLY, and three Spark workers each called ToolSearch 51–98 times
        // ("No such tool available: DeferredToolPlaceholder") and never listened. Kept on the
        // redirect too, so a caller that applies only this still gets it.
        com.moddingfromamod.worker.ToolExposure.applyTo(env);
        // A leftover key out-ranks the token we just set on some CLI versions, and one is present
        // in every cloud session. Removing it makes the redirect unambiguous rather than dependent
        // on a precedence rule that is not ours and could change under us.
        env.remove("ANTHROPIC_API_KEY");

        // ⚠ AND THE ONE THAT ACTUALLY BROKE THE FIRST REAL MUSE SPARK RUN (27 Aug 2026).
        //
        // In a Claude Code CLOUD session the CLI's credentials are injected by the host runner
        // through CLAUDE_* variables (CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST,
        // CLAUDE_CODE_OAUTH_TOKEN_FILE_DESCRIPTOR, and others). Those OUT-RANK
        // ANTHROPIC_AUTH_TOKEN, so setting the two lines above was NOT enough: the worker sent
        // this machine's own `sk-ant-` OAuth token to Meta's endpoint, got 401, retried the
        // identical 193KB body and then hung SILENTLY -- no stderr, nothing surfaced to MFAM. The
        // wish timed out at 600s having made zero tool calls, which reads as "the model is slow"
        // rather than "the model was never reached". Captured with a local TLS logging proxy;
        // `Authorization: Bearer sk-an…` where MUSE_API_KEY starts `LLM_`.
        //
        // MEASURED, same proxy, same prompt: with every CLAUDE* variable cleared the CLI sends
        // `Bearer LLM_…` and the endpoint answers 200 in 1.8s. It is NOT a binary problem --
        // /opt/claude-code/bin/claude is byte-identical (sha256 5fb321bf…) to the stock
        // @anthropic-ai/claude-code 2.1.247 tarball from npm, so there is no "clean build" to
        // fetch; the credential lives entirely in the environment.
        //
        // We clear the WHOLE CLAUDE* prefix rather than a named list because the minimal set is
        // not known -- clearing four of them by name was NOT enough, and narrowing further was
        // not completed. Over-clearing is safe here: MFAM sets no CLAUDE* variable itself, and a
        // wish worker has no business inheriting the identity of the session that launched the
        // game.
        //
        // CLAUDE_CLI is the one exception, and it is not a credential: it is the PATH to the
        // binary, read by ClaudeCli in this process. It stays.
        //
        // ONLY on a redirect. The BASELINE arm needs exactly these variables to authenticate, and
        // stripping them there would break the half of an A/B that nobody checks.
        env.keySet().removeIf(k -> k != null && k.startsWith("CLAUDE") && !k.equals("CLAUDE_CLI"));
    }

    /** Null-safe trim, so an unset variable and a blank one are the same thing. */
    private static String trimmed(String s) {
        return s == null ? "" : s.trim();
    }
}
