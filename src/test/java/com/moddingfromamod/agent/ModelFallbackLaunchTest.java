package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 *, end to end with stub CLIs: entry 1 of the chain (Wonder on the player's Muse Code sign-in) dies of a spent
 * window; the next worker launches on entry 2 (Spark Contributor on the player's key) and grants the wish; the player
 * is told why and what it costs; nothing is switched mid-wish, and the graders' chain does the same.
 */
@DisabledOnOs(OS.WINDOWS)
@org.junit.jupiter.api.extension.ExtendWith(com.moddingfromamod.config.MagicianOn.class)
class ModelFallbackLaunchTest {

    @TempDir
    Path tmp;

    final List<String> told = new ArrayList<>();
    final AtomicLong now = new AtomicLong(Instant.parse("2026-11-18T15:00:00Z").toEpochMilli());

    @AfterEach
    void reset() {
        ModelFallbackRuntime.forTest(null, null, null, null);
        ModelFallbackRuntime.fileForTest(null);
    }

    private Map<String, String> env() throws Exception {
        Path xdg = Files.createDirectories(tmp.resolve("xdg/muse"));
        Files.writeString(xdg.resolve("auth.json"), "{\"providers\":{\"meta\":{\"mechanism\":\"oauth\"}}}");
        return Map.of("MUSE_API_KEY", "LLM_stub_key", "XDG_CONFIG_HOME", tmp.resolve("xdg").toString(),
                "PATH", "/usr/bin:/bin");
    }

    /**
     * A stub {@code muse}: on the sign-in (no {@code --api-key-stdin}) it says what Muse Code 1.4 says when the
     * window runs out and exits; on the key it "grants the wish" (writes {@code granted} with its model).
     */
    private Path stubMuse() throws Exception {
        Path p = tmp.resolve("muse");
        Files.writeString(p, "#!/bin/sh\n"
                + "if [ \"$2\" = \"--help\" ]; then exit 0; fi\n"
                + "case \" $* \" in *' --api-key-stdin '*) "
                + "  model=''; prev=''; for a in \"$@\"; do [ \"$prev\" = --model ] && model=\"$a\"; prev=\"$a\"; done;"
                + "  echo \"granted $model $(cat)\" > '" + tmp.resolve("granted") + "'; echo 'finish_wish ok'; exit 0;;\n"
                + "esac\n"
                + "echo 'agent loop failed: model failed: API error 429 [request_id=r1]: Subscription quota exhausted. "
                + "Your usage window resets at 2026-11-18T18:00:00Z. (rate_limit_error)'\n"
                + "exit 1\n");
        assertTrue(p.toFile().setExecutable(true));
        return p;
    }

    /** What AgentSession.startOne does for one slot: decide, launch, and on exit read why it ended. */
    private ModelFallback.Entry launch(Path muse, Map<String, String> env, List<ModelFallback.Entry> chain,
                                       String worker) throws Exception {
        ModelFallback.Decision d = ModelFallbackRuntime.decide(ModelFallbackRuntime.Chain.WISH, chain);
        ModelFallback.Entry e = d.entry().orElseThrow();
        ModelFallbackRuntime.launched(worker, e);
        Path pool = Files.createDirectories(tmp.resolve("pool"));
        Path log = pool.resolve("agent-" + worker + ".log");
        long offset = Files.isRegularFile(log) ? Files.size(log) : 0L;
        var started = MuseWorker.start(muse.toString(), pool, log, worker, "PROMPT", 4711, "tok",
                e.model().workerSpec(), "low", e.paid(), 900_000L, env::get, tmp.resolve("home").toString());
        assertTrue(started.getKey().waitFor(20, TimeUnit.SECONDS));
        ModelFallbackRuntime.workerEnded(worker, AgentSession.logSince(log, offset));
        return e;
    }

    @Test
    void aSpentWindowOnEntryOneGrantsTheWishOnEntryTwo() throws Exception {
        Map<String, String> env = env();
        ModelFallbackRuntime.forTest(told::add, env::get, x -> Optional.empty(), now::get);
        Path state = tmp.resolve("model-fallback.json");
        ModelFallbackRuntime.fileForTest(state);
        List<ModelFallback.Entry> chain = List.of(ModelFallbackTest.WONDER_SUB, ModelFallbackTest.SPARK_KEY);
        Path muse = stubMuse();

        // 1. Wonder on the sign-in: the window runs out, the worker ends.
        assertEquals(ModelFallbackTest.WONDER_SUB, launch(muse, env, chain, "wish-granter"));
        assertFalse(Files.exists(tmp.resolve("granted")));
        assertTrue(told.isEmpty(), "the first launch on the pick says nothing");
        assertTrue(ModelFallbackRuntime.death("wish-granter").isPresent(),
                "a death is recorded, so the wish that worker held goes back to the queue (WishSweeper)");
        assertEquals("quota", ModelFallbackRuntime.death("wish-granter").get().reason());
        assertTrue(Files.readString(state).contains("muse-wonder-1.0:subscription"), "the skip is saved: sticky");

        // 2. The replacement launches on the key and grants the wish.
        now.addAndGet(5_000L);
        assertEquals(ModelFallbackTest.SPARK_KEY, launch(muse, env, chain, "wish-granter"));
        assertEquals("granted muse-spark-1.2-contributor LLM_stub_key", Files.readString(tmp.resolve("granted")).strip(),
                "entry 2's model, its key on stdin");
        assertEquals(1, told.size());
        assertTrue(told.get(0).startsWith("Moddy switched models: your Muse Code window is spent until "), told.get(0));
        assertTrue(told.get(0).contains("Muse Spark 1.2 Contributor with your API key (MUSE_API_KEY), $0.10 in / $0.20 "
                + "out per million tokens"), "the price of the paid fallback: " + told.get(0));

        // 3. Sticky: every later launch before the reset goes straight to the key, with no failed start.
        ModelFallback.Decision again = ModelFallbackRuntime.decide(ModelFallbackRuntime.Chain.WISH, chain);
        assertEquals(ModelFallbackTest.SPARK_KEY, again.entry().get());
        assertEquals(1, told.size(), "no second notice for the same switch");

        // 4. After a restart, the skip is read back.
        ModelFallbackRuntime.forTest(told::add, env::get, x -> Optional.empty(), now::get);
        ModelFallbackRuntime.fileForTest(state);
        assertEquals(ModelFallbackTest.SPARK_KEY, ModelFallbackRuntime.decide(ModelFallbackRuntime.Chain.WISH, chain)
                .entry().get());

        // 5. At the reset, the next launch is on the subscription again, and the player is told.
        now.set(Instant.parse("2026-11-18T18:00:00Z").toEpochMilli());
        ModelFallbackRuntime.decide(ModelFallbackRuntime.Chain.WISH, chain);   // the last decision was the key
        told.clear();
        ModelFallbackRuntime.forTest(told::add, env::get, x -> Optional.empty(), now::get);
        ModelFallbackRuntime.fileForTest(state);
        ModelFallbackRuntime.decide(ModelFallbackRuntime.Chain.WISH, List.of(ModelFallbackTest.SPARK_KEY));
        assertEquals(ModelFallbackTest.WONDER_SUB, ModelFallbackRuntime.decide(ModelFallbackRuntime.Chain.WISH, chain)
                .entry().get());
        assertEquals(List.of("Moddy is back on Muse Wonder 1.0 on your Muse Code sign-in."), told);
    }

    @Test
    void aWorkerThatEndsForAnotherReasonKeepsItsEntry() throws Exception {
        Map<String, String> env = env();
        ModelFallbackRuntime.forTest(told::add, env::get, x -> Optional.empty(), now::get);
        Path muse = tmp.resolve("muse");
        Files.writeString(muse, "#!/bin/sh\n[ \"$2\" = \"--help\" ] && exit 0\necho 'model failed: API error 500'\nexit 1\n");
        assertTrue(muse.toFile().setExecutable(true));
        List<ModelFallback.Entry> chain = List.of(ModelFallbackTest.WONDER_SUB, ModelFallbackTest.SPARK_KEY);
        launch(muse, env, chain, "w1");
        assertTrue(ModelFallbackRuntime.death("w1").isEmpty(), "not the account's: no death, no requeue");
        assertEquals(ModelFallbackTest.WONDER_SUB, ModelFallbackRuntime.decide(ModelFallbackRuntime.Chain.WISH, chain)
                .entry().get(), "other errors are not fallen through");
        assertTrue(told.isEmpty());
    }

    @Test
    void noKeySetMeansTheKeyEntryIsNeverLaunched() throws Exception {
        ModelFallbackRuntime.forTest(told::add, k -> null, x -> Optional.empty(), now::get);
        ModelFallback.Decision d = ModelFallbackRuntime.decide(ModelFallbackRuntime.Chain.WISH,
                List.of(ModelFallbackTest.SPARK_KEY));
        assertTrue(d.exhausted());
        assertEquals("MUSE_API_KEY isn't set", d.passed().get(0).why());
        assertEquals(1, told.size(), "told once");
        ModelFallbackRuntime.decide(ModelFallbackRuntime.Chain.WISH, List.of(ModelFallbackTest.SPARK_KEY));
        assertEquals(1, told.size(), "not once per launch attempt");
    }

    // ── the graders' chain ────────────────────────────────────────────────────────────────────────────

    private static final String DONE = "{\"payload_type\":\"run.terminal.completed\",\"payload\":"
            + "{\"terminal\":\"completed\",\"text\":\"{\\\"violence\\\":\\\"FINE\\\"}\"}}";

    @Test
    void theGradersChainUsesTheSubscriptionThenTheKeyAndRemembersTheSpentWindow() throws Exception {
        Map<String, String> env = env();
        ModelFallbackRuntime.forTest(told::add, env::get, x -> Optional.empty(), now::get);
        Path count = tmp.resolve("subscription-tries");
        Path muse = tmp.resolve("muse");
        Files.writeString(muse, "#!/bin/sh\ncase \" $* \" in *' --api-key-stdin '*) cat > /dev/null; echo '" + DONE
                + "'; exit 0;; esac\n"
                + "echo x >> '" + count + "'\n"
                + "echo 'agent loop failed: model failed: API error 429: Subscription quota exhausted. Your usage "
                + "window resets at 2026-11-18T18:00:00Z.' >&2\nexit 1\n");
        assertTrue(muse.toFile().setExecutable(true));
        List<ModelFallback.Entry> chain = List.of(ModelFallbackTest.e("muse-ember-1.0:subscription"),
                ModelFallbackTest.e("muse-ember-1.0:api"));
        long deadline = System.currentTimeMillis() + 10_000L;
        MuseExec.Answer a = MuseExec.runChain(muse.toString(), chain, "P", "a wish", null, "low", "LLM_k", deadline);
        assertNotNull(a);
        assertEquals(MuseExec.ARM_API, a.arm(), "entry 2 answered");
        assertEquals(1, Files.readAllLines(count).size());
        a = MuseExec.runChain(muse.toString(), chain, "P", "a wish", null, "low", "LLM_k",
                System.currentTimeMillis() + 10_000L);
        assertEquals(MuseExec.ARM_API, a.arm());
        assertEquals(1, Files.readAllLines(count).size(), "the spent window is not tried again before its reset");
        assertTrue(told.isEmpty(), "a helper switch is logged, not said to the player");
        assertNull(MuseExec.runChain(muse.toString(), chain.subList(1, 2), "P", "a wish", null, "low", null,
                System.currentTimeMillis() + 10_000L), "no key: the caller's Haiku is next");
    }
}
