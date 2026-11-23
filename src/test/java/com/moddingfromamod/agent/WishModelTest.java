package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

/**
 * Which model grants a wish, and where it lives.
 *
 * <p>Every case here is a way the choice could be wrong while looking right, because that is the
 * only failure mode that matters: a wish agent granting on the wrong endpoint still produces a full
 * archive, a clean {@code run.json} and a set of scores — all of which are lies about which model
 * made them. An arm that is silently the control is a wrong result, not a missing one.
 */
class WishModelTest {

    private static UnaryOperator<String> env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m::get;
    }

    private static final UnaryOperator<String> WITH_KEY = env("MUSE_API_KEY", "k-123");

    // ── the ordinary case: a child's game ───────────────────────────────────────────────────

    @Test
    void aBareNameIsThisMachinesOwnClaudeSignIn() {
        WishModel.Resolved r = WishModel.resolve("opus", env());
        assertEquals(WishModel.DEFAULT_PROVIDER, r.provider());
        assertEquals("opus", r.model());
        assertFalse(r.redirects(), "a bare name must never redirect — this is the path a child is on");
    }

    @Test
    void anEmptySpecLeavesTheChoiceToTheCli() {
        WishModel.Resolved r = WishModel.resolve("", env());
        assertEquals("", r.model());
        assertFalse(r.redirects());
    }

    @Test
    void noCredentialsAreNeededForTheDefaultProvider() {
        // The baseline arm must run on a machine that has never heard of any other provider.
        assertFalse(WishModel.resolve("sonnet", env()).redirects());
    }

    // ── selecting a provider at runtime ─────────────────────────────────────────────────────

    @Test
    void aProviderPrefixSelectsThatEndpointAndStripsItselfFromTheModelId() {
        WishModel.Resolved r = WishModel.resolve("muse:muse-spark-1.2", WITH_KEY);
        assertEquals("muse", r.provider());
        assertEquals("muse-spark-1.2", r.model(),
                "the CLI must be given the BARE id — `--model muse:muse-spark-1.2` is not a model");
        assertEquals("https://api.meta.ai", r.baseUrl());
        assertEquals("k-123", r.apiKey());
        assertTrue(r.redirects());
    }

    @Test
    void openaiDefaultsToALocalProxyBecauseOpenAiSpeaksNoAnthropicEndpoint() {
        // MEASURED 2026-08-29: POST https://api.openai.com/v1/messages -> 404, and
        // /anthropic/v1/messages -> 404. There is no native Anthropic Messages endpoint to point
        // at, so the default MUST be a local translation shim. A default of api.openai.com would
        // 404 every wish and read as a broken arm rather than a missing proxy.
        WishModel.Resolved r = WishModel.resolve("openai:gpt-5.6-luna",
                env("OPENAI_PROXY_API_KEY", "sk-proxy"));
        assertEquals("openai", r.provider());
        assertEquals("gpt-5.6-luna", r.model());
        assertEquals("http://127.0.0.1:4000", r.baseUrl());
        assertTrue(r.redirects());
        assertFalse(r.baseUrl().contains("api.openai.com"),
                "the default must be the shim, not OpenAI — OpenAI has no /v1/messages");
    }

    @Test
    void theOpenaiProxyEndpointIsOverridablePerMachine() {
        // The shim's port is a local choice, and a hosted gateway is a valid substitute.
        WishModel.Resolved r = WishModel.resolve("openai:gpt-5.6-luna",
                env("OPENAI_PROXY_API_KEY", "k", "OPENAI_BASE_URL", "http://127.0.0.1:8080"));
        assertEquals("http://127.0.0.1:8080", r.baseUrl());
    }

    @Test
    void openaiWithNoKeyIsARefusalLikeEveryOtherProvider() {
        // Same rule as muse: running this arm on the default endpoint would record `openai` for a
        // run that was the control. The proxy needs SOME credential, even if only its own.
        assertThrows(IllegalStateException.class,
                () -> WishModel.resolve("openai:gpt-5.6-luna", env()));
    }

    @Test
    void everyProviderKeepsItsOwnCredentialsSeparate() {
        // One environment holds both keys; neither arm may borrow the other's. Guards the shape
        // where a second provider is added by copy-paste and silently reuses MUSE_API_KEY.
        UnaryOperator<String> both = env("MUSE_API_KEY", "muse-k", "OPENAI_PROXY_API_KEY", "openai-k");
        assertEquals("muse-k", WishModel.resolve("muse:muse-spark-1.2", both).apiKey());
        assertEquals("openai-k", WishModel.resolve("openai:gpt-5.6-luna", both).apiKey());
        assertThrows(IllegalStateException.class,
                () -> WishModel.resolve("openai:gpt-5.6-luna", env("MUSE_API_KEY", "muse-k")));
    }

    @Test
    void theBaseUrlCanBeOverriddenPerMachine() {
        WishModel.Resolved r = WishModel.resolve("muse:muse-spark-1.2",
                env("MUSE_API_KEY", "k", "MUSE_BASE_URL", "https://gateway.internal"));
        assertEquals("https://gateway.internal", r.baseUrl());
    }

    @Test
    void twoArmsDifferByTheirSpecAloneWithNoEnvironmentChange() {
        // THE WHOLE POINT: one environment, both arms, selected per run.
        UnaryOperator<String> one = WITH_KEY;
        assertFalse(WishModel.resolve("opus", one).redirects());
        assertTrue(WishModel.resolve("muse:muse-spark-1.2", one).redirects());
    }

    // ── the ways it could be silently wrong ─────────────────────────────────────────────────

    @Test
    void aKnownProviderWithNoKeyIsAHardFailure() {
        // THE DANGEROUS DIRECTION. Falling back to the default endpoint here would grant every
        // wish, complete the archive, and record `arm=muse` for a run that was the control.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> WishModel.resolve("muse:muse-spark-1.2", env()));
        assertTrue(e.getMessage().contains("MUSE_API_KEY"), "must name the variable to set");
    }

    @Test
    void aBlankKeyIsTheSameAsAnUnsetOne() {
        assertThrows(IllegalStateException.class,
                () -> WishModel.resolve("muse:muse-spark-1.2", env("MUSE_API_KEY", "   ")));
    }

    @Test
    void aMistypedProviderIsRefusedRatherThanSentToTheDefault() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> WishModel.resolve("muze:muse-spark-1.2", WITH_KEY));
        assertTrue(e.getMessage().contains("muze") && e.getMessage().contains("muse"),
                "must name both the typo and the providers that do exist");
    }

    @Test
    void aProviderWithNoModelIsRefused() {
        assertThrows(IllegalStateException.class, () -> WishModel.resolve("muse:", WITH_KEY));
    }

    @Test
    void aVersionedModelIdIsNotMistakenForAProviderPrefix() {
        // Bedrock-style ids carry a trailing `:0`. Eating part of a model id is unrecoverable, so
        // a prefix must LOOK like a provider name (lowercase, no dots) before it is treated as one.
        String bedrock = "anthropic.claude-3-5-sonnet-20241022-v2:0";
        WishModel.Resolved r = WishModel.resolve(bedrock, env());
        assertEquals(bedrock, r.model(), "the whole id must survive");
        assertFalse(r.redirects());
    }

    @Test
    void surroundingWhitespaceIsTrimmedFromTheKey() {
        // A key pasted into an environment-variable form routinely arrives with a trailing
        // newline. Sent as a bearer token that is a 401 with no visible cause.
        WishModel.Resolved r = WishModel.resolve("  muse:muse-spark-1.2 ",
                env("MUSE_API_KEY", "\tk-123\n"));
        assertEquals("muse-spark-1.2", r.model());
        assertEquals("k-123", r.apiKey());
    }

    // ── applying it to a child process ──────────────────────────────────────────────────────

    @Test
    void applyingARedirectRewritesOnlyTheThreeAnthropicVariables() {
        Map<String, String> child = new HashMap<>(Map.of(
                "PATH", "/usr/bin", "HOME", "/root", "CLAUDE_CLI", "/opt/node22/bin/claude",
                "ANTHROPIC_BASE_URL", "https://api.anthropic.com",
                "ANTHROPIC_API_KEY", "sk-ant-inherited"));
        WishModel.applyTo(child, WishModel.resolve("muse:muse-spark-1.2", WITH_KEY));

        assertEquals("https://api.meta.ai", child.get("ANTHROPIC_BASE_URL"));
        assertEquals("k-123", child.get("ANTHROPIC_AUTH_TOKEN"));
        assertNull(child.get("ANTHROPIC_API_KEY"),
                "every cloud session carries one, and it can out-rank the bearer token");
        assertEquals("/usr/bin", child.get("PATH"));
        assertEquals("/root", child.get("HOME"));
        assertEquals("/opt/node22/bin/claude", child.get("CLAUDE_CLI"),
                "the CLI binary is resolved by the parent and must survive the redirect");
        assertEquals("false", child.get("ENABLE_TOOL_SEARCH"),
                "a redirected worker gets every wish tool up front: Muse cannot load deferred ones "
                        + "(2026-10-21, three Spark workers looped on ToolSearch until the wish timed out)");
    }

    @Test
    void aRedirectStripsTheHostManagedCredentialVariablesThatOutrankOurToken() {
        // THE BUG THAT KILLED THE FIRST REAL MUSE SPARK RUN. In a cloud session the CLI's
        // credentials arrive through CLAUDE_* variables injected by the host runner, and they
        // OUT-RANK ANTHROPIC_AUTH_TOKEN -- so the worker sent this machine's own sk-ant- OAuth
        // token to Meta's endpoint, got 401, retried and hung silently for 600s. Setting the
        // two ANTHROPIC_* variables was never enough on its own.
        Map<String, String> child = new HashMap<>(Map.of(
                "CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST", "1",
                "CLAUDE_CODE_OAUTH_TOKEN_FILE_DESCRIPTOR", "3",
                "CLAUDE_SESSION_INGRESS_TOKEN_FILE", "/run/ingress",
                "CLAUDECODE", "1",
                "CLAUDE_CLI", "/opt/node22/bin/claude",
                "PATH", "/usr/bin"));
        WishModel.applyTo(child, WishModel.resolve("muse:muse-spark-1.2", WITH_KEY));

        assertNull(child.get("CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST"));
        assertNull(child.get("CLAUDE_CODE_OAUTH_TOKEN_FILE_DESCRIPTOR"));
        assertNull(child.get("CLAUDE_SESSION_INGRESS_TOKEN_FILE"));
        assertNull(child.get("CLAUDECODE"));
        assertEquals("k-123", child.get("ANTHROPIC_AUTH_TOKEN"));
        assertEquals("/opt/node22/bin/claude", child.get("CLAUDE_CLI"),
                "CLAUDE_CLI is a binary PATH, not a credential, and must survive");
        assertEquals("/usr/bin", child.get("PATH"));
    }

    @Test
    void theBaselineArmKeepsItsHostManagedCredentials() {
        // The other half, and the one that would fail silently: the baseline arm authenticates
        // WITH these variables. Stripping them there breaks the control.
        Map<String, String> child = new HashMap<>(Map.of(
                "CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST", "1",
                "CLAUDE_CODE_OAUTH_TOKEN_FILE_DESCRIPTOR", "3"));
        WishModel.applyTo(child, WishModel.resolve("opus", WITH_KEY));
        assertEquals("1", child.get("CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST"));
        assertEquals("3", child.get("CLAUDE_CODE_OAUTH_TOKEN_FILE_DESCRIPTOR"));
    }

    @Test
    void applyingANonRedirectLeavesTheEnvironmentCompletelyAlone() {
        Map<String, String> child = new HashMap<>(Map.of(
                "ANTHROPIC_BASE_URL", "https://api.anthropic.com",
                "ANTHROPIC_API_KEY", "sk-ant-inherited"));
        WishModel.applyTo(child, WishModel.resolve("opus", WITH_KEY));
        assertEquals("https://api.anthropic.com", child.get("ANTHROPIC_BASE_URL"));
        assertEquals("sk-ant-inherited", child.get("ANTHROPIC_API_KEY"),
                "the baseline arm must not have its own credentials stripped");
        assertFalse(child.containsKey("ANTHROPIC_AUTH_TOKEN"));
        assertFalse(child.containsKey("ENABLE_TOOL_SEARCH"), "Opus keeps tool search");
    }
}
