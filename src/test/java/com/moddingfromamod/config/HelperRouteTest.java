package com.moddingfromamod.config;

import static com.moddingfromamod.config.HelperRoute.Step.CLAUDE_KEY;
import static com.moddingfromamod.config.HelperRoute.Step.CLAUDE_SUBSCRIPTION;
import static com.moddingfromamod.config.HelperRoute.Step.MUSE_KEY;
import static com.moddingfromamod.config.HelperRoute.Step.MUSE_SUBSCRIPTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.agent.ModelFallback;
import com.moddingfromamod.config.HelperRoute.Job;
import com.moddingfromamod.config.HelperRoute.Machine;
import com.moddingfromamod.config.HelperRoute.Step;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The one decision every helper job's provider and payment come from: <b>a helper never reaches a
 * provider the player did not choose</b>, a server's route is honoured job by job, and with nothing set every job does
 * exactly what it did before.
 */
class HelperRouteTest {

    private static final Machine SHIM_WITH_KEYS = new Machine(true, true, true);
    private static final Machine SHIM_NO_ANTHROPIC_KEY = new Machine(true, true, false);
    private static final Machine LOCAL_MUSE_KEY = new Machine(false, true, false);
    private static final Machine LOCAL_NO_KEYS = new Machine(false, false, false);
    private static final Machine BOTH_KEYS = new Machine(false, true, true);

    private static List<ModelFallback.Entry> chain(String... entries) {
        List<ModelFallback.Entry> out = new ArrayList<>();
        for (String e : entries) {
            out.addAll(ModelFallback.parse(e, SupportedModels.Role.CHEAP).entries());
        }
        return out;
    }

    // ── single-player: the player's choice is the whole route ───────────────────────────────────────────

    @Test
    void aClaudeOnlyPlayerWithAMuseKeyInTheEnvironmentNeverGetsAMuseStep() {
        for (Job job : Job.values()) {
            for (String pay : List.of("subscription", "api")) {
                List<Step> r = HelperRoute.decide(job, Optional.of(chain("haiku-5.5:" + pay)), Optional.empty(), true,
                        BOTH_KEYS);
                assertEquals(List.of("subscription".equals(pay) ? CLAUDE_SUBSCRIPTION : CLAUDE_KEY), r,
                        job + ": a Claude-only helper choice, paid by " + pay);
                assertTrue(HelperRoute.muse(r).isEmpty(), job + " reached Muse, which the player did not choose");
            }
        }
    }

    @Test
    void aMuseOnlyPlayerWithAnAnthropicKeyInTheEnvironmentNeverGetsAClaudeStep() {
        for (Job job : Job.values()) {
            List<Step> sub = HelperRoute.decide(job, Optional.of(chain("muse-ember-1.0:subscription")), Optional.empty(),
                    true, BOTH_KEYS);
            assertEquals(List.of(MUSE_SUBSCRIPTION), sub, job + ": Ember on the sign-in, and no key at all");
            List<Step> key = HelperRoute.decide(job, Optional.of(chain("muse-ember-1.0:subscription",
                    "muse-ember-1.0:api")), Optional.empty(), true, BOTH_KEYS);
            assertEquals(List.of(MUSE_SUBSCRIPTION, MUSE_KEY), key, job + ": the sign-in, then the key they chose");
            assertFalse(HelperRoute.hasClaude(key), job + " reached Anthropic, which the player did not choose");
        }
    }

    @Test
    void aMixedChoiceIsFollowedInItsOrder() {
        assertEquals(List.of(MUSE_SUBSCRIPTION, CLAUDE_KEY), HelperRoute.decide(Job.PLAN,
                Optional.of(chain("muse-ember-1.0:subscription", "haiku-5.5:api")), Optional.empty(), true,
                LOCAL_NO_KEYS));
    }

    @Test
    void aServerValueCanNarrowOrReorderThePlayersChoiceButNeverWidenIt() {
        var picked = Optional.of(chain("muse-ember-1.0:subscription", "muse-ember-1.0:api"));
        assertEquals(List.of(MUSE_KEY, MUSE_SUBSCRIPTION), HelperRoute.decide(Job.PLAN, picked,
                Optional.of("muse-key,muse-subscription"), true, BOTH_KEYS), "reordered");
        assertEquals(List.of(MUSE_KEY), HelperRoute.decide(Job.PLAN, picked,
                Optional.of("claude-key,muse-key"), true, BOTH_KEYS), "a provider they did not choose is dropped");
        assertEquals(List.of(), HelperRoute.decide(Job.PLAN, picked, Optional.of("claude-key"), true, BOTH_KEYS),
                "nothing left: the job makes no call rather than one they did not choose");
    }

    // ── servers: the configured route, job by job ─────────────────────────────────────────────────────

    @Test
    void aServerRouteIsHonouredInItsOrder() {
        assertEquals(List.of(CLAUDE_KEY, MUSE_KEY), HelperRoute.decide(Job.WISH_TEXT, Optional.empty(),
                Optional.of("claude-key,muse-key"), true, SHIM_WITH_KEYS));
        assertEquals(List.of(MUSE_SUBSCRIPTION, MUSE_KEY, CLAUDE_KEY, CLAUDE_SUBSCRIPTION),
                HelperRoute.decide(Job.DESCRIBER, Optional.empty(),
                        Optional.of("muse-subscription, muse-key, claude-key, claude-subscription"), true,
                        LOCAL_NO_KEYS));
        assertEquals(List.of(), HelperRoute.decide(Job.RESEARCHER, Optional.empty(), Optional.of("none"), true,
                SHIM_WITH_KEYS), "none: no model call for that job");
        assertEquals(HelperRoute.today(Job.PLAN, true, SHIM_WITH_KEYS), HelperRoute.decide(Job.PLAN,
                Optional.empty(), Optional.of("default"), true, SHIM_WITH_KEYS), "default is today's");
    }

    @Test
    void anUnreadableRouteIsRefusedWholeNeverHalfRead() {
        assertEquals(Optional.empty(), HelperRoute.parse("muse-key,openai"));
        assertTrue(HelperRoute.invalid("muse-key,openai").orElseThrow().contains("openai"));
        assertTrue(HelperRoute.invalid("muse-key, claude-subscription").isEmpty());
        assertTrue(HelperRoute.invalid("default").isEmpty() && HelperRoute.invalid("none").isEmpty()
                && HelperRoute.invalid(null).isEmpty());
        assertEquals(Optional.of(List.of(MUSE_KEY)), HelperRoute.parse("muse-key,muse-key"), "each step once");
    }

    // ── defaults: exactly what each job did before ────────────────────────────────────────────────────

    @Test
    void theDefaultsAreTodaysChainsJobByJob() {
        for (Job grader : List.of(Job.PLAN, Job.WISH_TEXT, Job.WORLD_RULES, Job.READY_MADE, Job.CHARACTER)) {
            // A server with the Muse shim (a plug-in): Ember on the plan through the shim, then the key, then the pinned
            // Haiku on the CLI as the server has it (its key when its environment sets one, else its sign-in).
            assertEquals(List.of(MUSE_SUBSCRIPTION, MUSE_KEY, CLAUDE_KEY), HelperRoute.today(grader, true,
                    SHIM_WITH_KEYS), grader + " on a shim server with an Anthropic key");
            assertEquals(List.of(MUSE_SUBSCRIPTION, MUSE_KEY, CLAUDE_SUBSCRIPTION), HelperRoute.today(grader, true,
                    SHIM_NO_ANTHROPIC_KEY), grader + " on a shim server signed in to Claude");
            // No shim: the key when there is one, else Muse Code's own login; then Haiku.
            assertEquals(List.of(MUSE_KEY, CLAUDE_SUBSCRIPTION), HelperRoute.today(grader, true, LOCAL_MUSE_KEY));
            assertEquals(List.of(MUSE_SUBSCRIPTION, CLAUDE_SUBSCRIPTION), HelperRoute.today(grader, true,
                    LOCAL_NO_KEYS));
            // A grader switched to Haiku never starts Muse Code (docs/EMBER_GRADERS.md, "One switch per job").
            assertEquals(List.of(CLAUDE_KEY), HelperRoute.today(grader, false, SHIM_WITH_KEYS));
        }
        assertEquals(List.of(MUSE_SUBSCRIPTION, MUSE_KEY, CLAUDE_KEY), HelperRoute.today(Job.DESCRIBER, true,
                SHIM_WITH_KEYS), "the describer: the plan, the key (direct), Haiku");
        assertEquals(List.of(MUSE_KEY, CLAUDE_SUBSCRIPTION), HelperRoute.today(Job.DESCRIBER, true, LOCAL_MUSE_KEY));
        assertEquals(List.of(MUSE_SUBSCRIPTION, CLAUDE_SUBSCRIPTION), HelperRoute.today(Job.DESCRIBER, true,
                LOCAL_NO_KEYS), "no key and no shim: Muse Code's own login, then Haiku");
        assertEquals(List.of(CLAUDE_KEY), HelperRoute.today(Job.RESEARCHER, true, SHIM_WITH_KEYS),
                "the researcher on a shim server: the Claude CLI researcher, unchanged");
        assertEquals(List.of(CLAUDE_SUBSCRIPTION), HelperRoute.today(Job.RESEARCHER, true, SHIM_NO_ANTHROPIC_KEY));
    }

    @Test
    void withNoPicksAndNoRouteTheDecisionIsToday() {
        for (Job job : Job.values()) {
            for (Machine m : List.of(SHIM_WITH_KEYS, SHIM_NO_ANTHROPIC_KEY, LOCAL_MUSE_KEY, LOCAL_NO_KEYS)) {
                assertEquals(HelperRoute.today(job, true, m), HelperRoute.decide(job, Optional.empty(),
                        Optional.empty(), true, m), job + " on " + m);
            }
        }
    }

    // ── walking a route ───────────────────────────────────────────────────────────────────────────────

    @Test
    void aRouteIsWalkedInOrderWithEachRunOfMuseStepsAsOneCall() {
        List<String> calls = new ArrayList<>();
        String got = HelperRoute.first(List.of(MUSE_SUBSCRIPTION, MUSE_KEY, CLAUDE_KEY, CLAUDE_SUBSCRIPTION),
                muse -> {
                    calls.add("muse" + muse);
                    return null;
                }, st -> {
                    calls.add(st.id);
                    return st == CLAUDE_SUBSCRIPTION ? "answer" : null;
                });
        assertEquals("answer", got);
        assertEquals(List.of("muse[MUSE_SUBSCRIPTION, MUSE_KEY]", "claude-key", "claude-subscription"), calls);

        calls.clear();
        assertNull(HelperRoute.first(List.of(CLAUDE_KEY, MUSE_KEY), null, st -> {
            calls.add(st.id);
            return null;
        }), "a job with no Muse model skips its Muse steps");
        assertEquals(List.of("claude-key"), calls);
        assertNull(HelperRoute.first(List.of(), m -> "x", s -> "y"), "an empty route answers nothing");
    }

    // ── the processes' environments ───────────────────────────────────────────────────────────────────

    @Test
    void aClaudeSubscriptionStepNeverCarriesAnAnthropicKeyAndAKeyStepNeedsOne() {
        Map<String, String> env = new HashMap<>(Map.of("ANTHROPIC_API_KEY", "sk-ant", "MUSE_API_KEY", "LLM_x"));
        assertTrue(HelperRoute.claudeEnv(CLAUDE_SUBSCRIPTION, env));
        assertFalse(env.containsKey("ANTHROPIC_API_KEY"), "the sign-in pays, never a key that happens to be set");
        Map<String, String> keyed = new HashMap<>(Map.of("ANTHROPIC_API_KEY", "sk-ant"));
        assertTrue(HelperRoute.claudeEnv(CLAUDE_KEY, keyed));
        assertEquals("sk-ant", keyed.get("ANTHROPIC_API_KEY"));
        assertFalse(HelperRoute.claudeEnv(CLAUDE_KEY, new HashMap<>()), "no key: the step cannot run");
    }

    @Test
    void aMuseSignInStepCarriesNoMuseKey() {
        Map<String, String> env = new HashMap<>(Map.of("MUSE_API_KEY", "LLM_x", "META_API_KEY", "LLM_y"));
        HelperRoute.museEnv(MUSE_SUBSCRIPTION, env);
        assertTrue(env.isEmpty(), "a Muse key outranks Muse Code's login, so a sign-in step must not carry one");
        Map<String, String> key = new HashMap<>(Map.of("MUSE_API_KEY", "LLM_x"));
        HelperRoute.museEnv(MUSE_KEY, key);
        assertEquals("LLM_x", key.get("MUSE_API_KEY"));
    }

    // ── the settings ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void everyJobHasASettingNoPlayerCanChange() {
        for (Job job : Job.values()) {
            Setting s = SettingsCatalog.find(job.key()).orElseThrow(() -> new AssertionError(job.key()));
            assertFalse(s.playerChangeable(), job.key() + " must be an override's or the server file's only");
            assertTrue(s.accepts("muse-subscription,claude-key") && s.accepts("none") && s.accepts("default"));
            assertFalse(s.accepts("muse-key,openai"));
        }
    }
}
