package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.config.SupportedModels;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** the fallback chain's decision — classification, stickiness, the choice, the player's words. Pure. */
class ModelFallbackTest {

    static final ZoneId UTC = ZoneId.of("UTC");
    static final ModelFallback.Entry WONDER_SUB = e("muse-wonder-1.0:subscription");
    static final ModelFallback.Entry SPARK_KEY = e("muse-spark-1.2-contributor:api");
    static final ModelFallback.Entry OPUS_KEY = e("opus-5.5:api");
    static final List<ModelFallback.Entry> CHAIN = List.of(WONDER_SUB, SPARK_KEY, OPUS_KEY);
    static final long NOW = Instant.parse("2026-11-18T15:00:00Z").toEpochMilli();
    static final Function<ModelFallback.Entry, Optional<String>> ALL_HERE = x -> Optional.empty();
    static final Function<ModelFallback.Entry, Long> NO_STAMP = x -> 0L;

    static ModelFallback.Entry e(String s) {
        return ModelFallback.entry(s, s.startsWith("muse-ember") || s.startsWith("haiku")
                ? SupportedModels.Role.CHEAP : SupportedModels.Role.MAIN).orElseThrow(() -> new AssertionError(s));
    }

    // ── the shared fixture: the shim reads the same file (tools/test-muse-cli-shim.py §27) ─────────────

    @Test
    void everyCaseInTheSharedFixtureClassifiesAsItSays() throws Exception {
        JsonObject root;
        try (InputStream in = getClass().getResourceAsStream("/fallback/classify-cases.json")) {
            root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        int n = 0;
        for (var el : root.getAsJsonArray("cases")) {
            JsonObject c = el.getAsJsonObject();
            ModelFallback.Classified got = ModelFallback.classify(c.get("text").getAsString());
            String name = c.get("name").getAsString();
            assertEquals(c.get("java").getAsString(), got.failure().name(), name);
            if (c.has("javaResetsAt") && !c.get("javaResetsAt").isJsonNull()) {
                assertEquals(Instant.parse(c.get("javaResetsAt").getAsString()).toEpochMilli(), got.resetsAtMs(), name);
            } else {
                assertEquals(0L, got.resetsAtMs(), name);
            }
            // Where the shim names a kind, Java names the same one.
            if (c.has("shim") && !c.get("shim").isJsonNull()) {
                String want = switch (c.get("shim").getAsString()) {
                    case "quota" -> "QUOTA";
                    case "model" -> "NOT_SERVED";
                    case "nosub" -> "NO_SUBSCRIPTION";
                    case "login" -> "LOGIN";
                    default -> throw new AssertionError(c);
                };
                assertEquals(want, got.failure().name(), "the shim's kind: " + name);
            }
            n++;
        }
        assertTrue(n >= 15, "the fixture lost cases: " + n);
    }

    @Test
    void onlyAccountFailuresFallThrough() {
        for (ModelFallback.Failure f : ModelFallback.Failure.values()) {
            assertEquals(f != ModelFallback.Failure.OTHER, f.fallsThrough(), f.name());
        }
        ModelFallback.State s = new ModelFallback.State();
        var c = ModelFallback.classify("model failed: API error 500 internal server error");
        assertTrue(s.failed(WONDER_SUB, c, NOW, 0L).isEmpty(), "a wish's own error skips nothing");
        assertEquals(WONDER_SUB, choose(s, NOW).entry().orElseThrow(), "the same entry runs the next worker");
    }

    // ── stickiness ────────────────────────────────────────────────────────────────────────────────────

    private static ModelFallback.Decision choose(ModelFallback.State s, long now) {
        return ModelFallback.choose(CHAIN, s, now, ALL_HERE, NO_STAMP, UTC);
    }

    @Test
    void aSpentWindowIsSkippedUntilTheResetTheMessageNamed() {
        ModelFallback.State s = new ModelFallback.State();
        s.failed(WONDER_SUB, ModelFallback.classify("Subscription quota exhausted. Your usage window resets at "
                + "2026-11-18T18:00:00Z."), NOW, 0L);
        ModelFallback.Decision d = choose(s, NOW + 60_000L);
        assertEquals(SPARK_KEY, d.entry().orElseThrow());
        assertEquals(1, d.index());
        assertEquals("your Muse Code window is spent until 18:00", d.passed().get(0).why());
        assertEquals(SPARK_KEY, choose(s, Instant.parse("2026-11-18T17:59:59Z").toEpochMilli()).entry().get(),
                "still spent a second before the reset: no failed start in between");
        assertEquals(WONDER_SUB, choose(s, Instant.parse("2026-11-18T18:00:00Z").toEpochMilli()).entry().get(),
                "back on the subscription at the reset");
        assertTrue(s.skips().isEmpty(), "an expired skip is dropped");
    }

    @Test
    void cooldownsAreTheHousesOwn() {
        ModelFallback.State s = new ModelFallback.State();
        assertEquals(NOW + ModelFallback.QUOTA_GUESS_MS, s.failed(WONDER_SUB,
                ModelFallback.classify("Subscription quota exhausted."), NOW, 0L).get().untilMs());
        assertEquals(NOW + ModelFallback.LOGIN_RETRY_MS, s.failed(WONDER_SUB,
                ModelFallback.classify("still unauthorized after a token refresh"), NOW, 0L).get().untilMs());
        assertEquals(NOW + ModelFallback.SUBS_RECHECK_MS, s.failed(WONDER_SUB,
                ModelFallback.classify("No active subscription"), NOW, 0L).get().untilMs());
        assertEquals(NOW + ModelFallback.RATE_LIMIT_MS, s.failed(SPARK_KEY,
                ModelFallback.classify("rate_limit_error"), NOW, 0L).get().untilMs());
        assertEquals(NOW + 300_000L, s.failed(SPARK_KEY,
                ModelFallback.classify("429 rate_limit_error, retry after 300s"), NOW, 0L).get().untilMs());
        assertEquals(1800_000L, ModelFallback.LOGIN_RETRY_MS, "tools/muse-cli-shim.py LOGIN_RETRY_S");
        assertEquals(3600_000L, ModelFallback.QUOTA_GUESS_MS, "tools/muse-cli-shim.py QUOTA_GUESS_S");
        assertEquals(3600_000L, ModelFallback.SUBS_RECHECK_MS, "tools/muse-cli-shim.py SUBS_RECHECK_S");
    }

    @Test
    void aResetTooFarAwayIsNotBelievedAndOneInThePastIsTheGuess() {
        ModelFallback.State s = new ModelFallback.State();
        var past = new ModelFallback.Classified(ModelFallback.Failure.QUOTA, NOW - 1L, "x");
        assertEquals(NOW + ModelFallback.QUOTA_GUESS_MS, s.failed(WONDER_SUB, past, NOW, 0L).get().untilMs());
        var far = new ModelFallback.Classified(ModelFallback.Failure.QUOTA, NOW + 30L * 86_400_000L, "x");
        assertEquals(NOW + ModelFallback.QUOTA_GUESS_MS, s.failed(WONDER_SUB, far, NOW, 0L).get().untilMs());
    }

    @Test
    void signingInAgainClearsALoginSkipAtOnceButNotAQuota() {
        ModelFallback.State s = new ModelFallback.State();
        s.failed(WONDER_SUB, ModelFallback.classify("run `muse login`"), NOW, 1000L);
        assertTrue(s.skip(WONDER_SUB, NOW + 1, 1000L).isPresent(), "the same sign-in file: still skipped");
        assertTrue(s.skip(WONDER_SUB, NOW + 1, 2000L).isEmpty(), "a newer sign-in: tried again at once");
        s.failed(WONDER_SUB, ModelFallback.classify("Subscription quota exhausted."), NOW, 1000L);
        assertTrue(s.skip(WONDER_SUB, NOW + 1, 2000L).isPresent(), "a new sign-in does not refill a spent window");
    }

    @Test
    void theStateSurvivesARestart() {
        ModelFallback.State s = new ModelFallback.State();
        s.failed(WONDER_SUB, ModelFallback.classify("Subscription quota exhausted. Your usage window resets at "
                + "2026-11-18T18:00:00Z."), NOW, 7L);
        ModelFallback.State back = ModelFallback.State.fromJson(s.toJson());
        assertEquals(s.skips(), back.skips());
        assertTrue(ModelFallback.State.fromJson("not json").skips().isEmpty(), "unreadable: no skips, never a throw");
        assertTrue(ModelFallback.State.fromJson(null).skips().isEmpty());
    }

    // ── the choice ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void anEntryThatCannotRunHereIsPassedOverWithItsReasonAndNoSkip() {
        ModelFallback.State s = new ModelFallback.State();
        ModelFallback.Decision d = ModelFallback.choose(CHAIN, s,
                NOW, x -> x == SPARK_KEY ? Optional.of("MUSE_API_KEY isn't set") : Optional.empty(), NO_STAMP, UTC);
        assertEquals(WONDER_SUB, d.entry().get(), "the first entry runs; the second's absence is not consulted");
        s.failed(WONDER_SUB, ModelFallback.classify("Subscription quota exhausted."), NOW, 0L);
        d = ModelFallback.choose(CHAIN, s, NOW, x -> x == SPARK_KEY ? Optional.of("MUSE_API_KEY isn't set")
                : Optional.empty(), NO_STAMP, UTC);
        assertEquals(OPUS_KEY, d.entry().get());
        assertEquals(2, d.index());
        assertEquals("MUSE_API_KEY isn't set", d.passed().get(1).why());
        assertTrue(s.skips().containsKey(WONDER_SUB.key()) && !s.skips().containsKey(SPARK_KEY.key()));
    }

    @Test
    void everyEntrySpentMeansNoLaunchAndTheSoonestReset() {
        ModelFallback.State s = new ModelFallback.State();
        s.failed(WONDER_SUB, new ModelFallback.Classified(ModelFallback.Failure.QUOTA, NOW + 7_200_000L, ""), NOW, 0L);
        s.failed(SPARK_KEY, ModelFallback.classify("rate_limit_error"), NOW, 0L);
        s.failed(OPUS_KEY, ModelFallback.classify("Invalid API key"), NOW, 0L);
        ModelFallback.Decision d = choose(s, NOW);
        assertTrue(d.exhausted());
        assertEquals(NOW + ModelFallback.RATE_LIMIT_MS, d.retryAtMs());
        assertTrue(ModelFallback.switchNotice(d, WONDER_SUB).orElseThrow().startsWith("Moddy can't grant wishes right now:"));
    }

    // ── the player's words (D27: a paid fallback names its price) ─────────────────────────────────────

    @Test
    void theNoticeSaysWhyAndWhatItCosts() {
        ModelFallback.State s = new ModelFallback.State();
        assertTrue(ModelFallback.switchNotice(choose(s, NOW), null).isEmpty(), "the first launch on the pick: silent");
        assertTrue(ModelFallback.switchNotice(choose(s, NOW), WONDER_SUB).isEmpty(), "no change: silent");
        s.failed(WONDER_SUB, ModelFallback.classify("Subscription quota exhausted. Your usage window resets at "
                + "2026-11-18T18:00:00Z."), NOW, 0L);
        assertEquals("Moddy switched models: your Muse Code window is spent until 18:00, so wishes now use Muse Spark "
                        + "1.2 Contributor with your API key (MUSE_API_KEY), $0.10 in / $0.20 out per million tokens.",
                ModelFallback.switchNotice(choose(s, NOW), WONDER_SUB).orElseThrow());
        assertEquals("Moddy is back on Muse Wonder 1.0 on your Muse Code sign-in.",
                ModelFallback.switchNotice(choose(s, Instant.parse("2026-11-18T18:00:00Z").toEpochMilli()), SPARK_KEY)
                        .orElseThrow());
    }

    @Test
    void theOtherReasonsInPlainWords() {
        ModelFallback.State s = new ModelFallback.State();
        s.failed(WONDER_SUB, ModelFallback.classify("still unauthorized after a token refresh"), NOW, 0L);
        assertEquals("your Muse Code sign-in isn't working (sign in again with `muse login`)",
                choose(s, NOW).passed().get(0).why());
        s.failed(WONDER_SUB, ModelFallback.classify("No active subscription"), NOW, 0L);
        assertEquals("your Muse Code account has no active subscription", choose(s, NOW).passed().get(0).why());
        s.failed(WONDER_SUB, ModelFallback.classify("model `x` does not exist or you lack access"), NOW, 0L);
        assertEquals("your plan doesn't include Muse Wonder 1.0", choose(s, NOW).passed().get(0).why());
        s.failed(SPARK_KEY, ModelFallback.classify("API error 401"), NOW, 0L);
        assertEquals("your MUSE_API_KEY key was refused", choose(s, NOW).passed().get(1).why());
    }

    // ── the setting ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void aFallbackListParsesInOrderAndNamesWhatItCannotRead() {
        var p = ModelFallback.parse("muse-spark-1.2-contributor:api, opus-5.5:API,muse-spark-1.2-contributor:api",
                SupportedModels.Role.MAIN);
        assertEquals(List.of(SPARK_KEY, OPUS_KEY), p.entries(), "in order, a duplicate dropped, case ignored");
        assertTrue(p.problems().isEmpty());
        assertEquals("muse-spark-1.2-contributor:api,opus-5.5:api", ModelFallback.format(p.entries()));
        assertTrue(ModelFallback.parse("none", SupportedModels.Role.MAIN).entries().isEmpty());
        assertEquals("none", ModelFallback.format(List.of()));
        var bad = ModelFallback.parse("muse-ember-1.0:api,fable-5.1:api,opus-5.5", SupportedModels.Role.MAIN);
        assertTrue(bad.entries().isEmpty());
        assertEquals(3, bad.problems().size(), "a helper model, an unsupported model and no mode: " + bad.problems());
        assertEquals(List.of(WONDER_SUB, SPARK_KEY), ModelFallback.chain(WONDER_SUB, List.of(WONDER_SUB, SPARK_KEY)),
                "the pick never twice");
    }

    @Test
    void theSettingAcceptsAListOfEntriesAndNothingElse() {
        var s = com.moddingfromamod.config.SettingsCatalog.find(com.moddingfromamod.config.SettingsCatalog.MAIN_FALLBACK)
                .orElseThrow();
        assertEquals("none", s.dflt());
        assertTrue(s.accepts("none"));
        assertTrue(s.accepts("muse-spark-1.2-contributor:api"));
        assertTrue(s.accepts("muse-spark-1.2-contributor:api,opus-5.5:api"));
        assertFalse(s.accepts("muse-ember-1.0:api"), "a helper model is not a wish model");
        assertFalse(s.accepts("opus-5.5:api,none"));
        assertFalse(s.accepts(""));
        assertFalse(s.accepts("fable-5.1:api"));
    }

    // ── one decision: the launch shim's arm cases, replayed through this one ─────────────────────────

    /**
     * {@code tools/test-muse-cli-shim.py}'s choose_arm cases as a chain: a shim server's policy is
     * {@code [Wonder on the subscription, the world's model on the API key]}, a "spark-only" world is a chain without
     * its first entry, and "no login" is an unavailable first entry. Each case's arm is the shim's.
     */
    @Test
    void theShimsArmCasesGiveTheSameArmThroughThisDecision() {
        List<ModelFallback.Entry> house = List.of(WONDER_SUB, SPARK_KEY);
        Function<ModelFallback.Entry, Optional<String>> loggedIn = x -> Optional.empty();
        Function<ModelFallback.Entry, Optional<String>> noLogin = x -> x == WONDER_SUB
                ? Optional.of("no Muse Code login on this server") : Optional.empty();
        // shim §1: a window open -> subscription
        ModelFallback.State s = new ModelFallback.State();
        assertEquals(WONDER_SUB, ModelFallback.choose(house, s, NOW, loggedIn, NO_STAMP, UTC).entry().get());
        // §2/§3: the 429 -> the next worker is the fallback, the configured model, billed to the API
        s.failed(WONDER_SUB, ModelFallback.classify("agent loop failed: model failed: API error 429: Subscription "
                + "quota exhausted. Your usage window resets at 2026-11-18T18:00:00Z. (rate_limit_error)"), NOW, 0L);
        ModelFallback.Decision d = ModelFallback.choose(house, s, NOW + 1, loggedIn, NO_STAMP, UTC);
        assertEquals(SPARK_KEY, d.entry().get());
        assertTrue(d.entry().get().paid());
        // §5: after the reset -> the subscription again
        assertEquals(WONDER_SUB, ModelFallback.choose(house, s, Instant.parse("2026-11-18T18:00:00Z").toEpochMilli(),
                loggedIn, NO_STAMP, UTC).entry().get());
        // §6: a failing login -> the fallback; a newer login -> the subscription again
        s.failed(WONDER_SUB, ModelFallback.classify("still unauthorized after a token refresh"), NOW, 100L);
        assertEquals(SPARK_KEY, ModelFallback.choose(house, s, NOW + 1, loggedIn, x -> 100L, UTC).entry().get());
        assertEquals(WONDER_SUB, ModelFallback.choose(house, s, NOW + 1, loggedIn, x -> 200L, UTC).entry().get());
        // §10: no login on the server -> the first worker is already the fallback
        assertEquals(SPARK_KEY, ModelFallback.choose(house, new ModelFallback.State(), NOW, noLogin, NO_STAMP, UTC)
                .entry().get());
        // §14: no active subscription -> the fallback, quietly, until an hour or a re-login
        s = new ModelFallback.State();
        s.failed(WONDER_SUB, ModelFallback.classify("No active subscription — you're on pay-as-you-go."), NOW, 100L);
        assertEquals(SPARK_KEY, ModelFallback.choose(house, s, NOW + 1, loggedIn, x -> 100L, UTC).entry().get());
        assertEquals(WONDER_SUB, ModelFallback.choose(house, s, NOW + ModelFallback.SUBS_RECHECK_MS, loggedIn,
                x -> 100L, UTC).entry().get());
        // spark-only: the chain without its first entry
        assertEquals(SPARK_KEY, ModelFallback.choose(List.of(SPARK_KEY), new ModelFallback.State(), NOW, loggedIn,
                NO_STAMP, UTC).entry().get());
    }
}
