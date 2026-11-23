package com.moddingfromamod.world;

import com.moddingfromamod.agent.MuseGrade;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.moddingfromamod.safety.SafetyGrader;
import com.moddingfromamod.wish.WishText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The world-rules grader</b>: a Haiku call at wish SUBMIT, in parallel with — and
 * entirely separate from — the safety grader. It asks ONE question ("does this wish need a change
 * this world denies?") and answers in {@link WorldRulesVerdict}'s closed vocabulary. There is no
 * word matcher anywhere in it: wishes about world effects are ambiguous ("make it rain cod" is a
 * shower of fish, not weather), which is the analysis's whole finding.
 *
 * <p>The shape is {@code SafetyGrader}'s, deliberately: fired from {@code WishService.submit}
 * without blocking (nothing on the submission path may block), memoised on the text so a re-offer
 * joins the grade in flight, enforced at pickup in {@code AwaitWishTool} between
 * {@code queue.await} and {@code Marshal.assign}. It even rides the same {@code claude -p}
 * transport ({@link SafetyGrader.CliTransport}: no tools, no MCP, one schema), with its own model
 * knob — but it shares nothing else: its own prompt ({@code /worldrules/grader-prompt.md}), its own
 * schema, its own memo, and no effect on "Moddy is tired". The safety prompt, its floors and the
 * lexicon are untouched by construction.
 *
 * <p><b>It fails OPEN.</b> No CLI, a timeout, a reply nobody can read — every failure is
 * {@link WorldRulesVerdict#unavailable()}, which the gate treats as UNSURE: the wish is granted with
 * the world's rules in the brief, and the deterministic reach rule at {@code tools/call} still
 * judges what the agent actually does. Only a grader that ANSWERED can refuse.
 *
 * <p>The world's rules go in the USER message, not the system prompt, so one prompt serves every
 * world and a rules edit needs no prompt edit: {@link #userText} is the exact text, pinned by a test
 * and rebuilt identically by {@code tools/world-rules-eval.py}.
 */
public final class WorldRulesGrader {

    private static final Logger LOGGER = LoggerFactory.getLogger("moddingfromamod/world-rules");

    /**
     * {@code -Dmoddingfromamod.world_rules.model} — default {@link MuseGrade#EMBER} since 2026-11-15
     * (docs/EMBER_GRADERS.md: it caught 91 of 111 rule-breaking wishes against Haiku's 22),
     * falling back to {@code haiku} when Muse Code gives no answer; {@code haiku} puts it back.
     */
    public static final String MODEL_PROPERTY = "moddingfromamod.world_rules.model";
    /** {@code -Dmoddingfromamod.world_rules.grader} — {@code on} (default) or {@code off}. */
    public static final String GRADER_PROPERTY = "moddingfromamod.world_rules.grader";
    /** How long pickup waits for a grade that has not landed yet. */
    public static final String BUDGET_PROPERTY = "moddingfromamod.world_rules.budgetMs";
    static final long DEFAULT_BUDGET_MS = 30_000L;
    static final int MAX_MEMO = 256;

    /** A grade and what it cost, for the log. */
    public record Graded(WorldRulesVerdict verdict, long latencyMs, double costUsd, String modelId, String arm) {
        /** No arm named. */
        public Graded(WorldRulesVerdict verdict, long latencyMs, double costUsd, String modelId) {
            this(verdict, latencyMs, costUsd, modelId, "");
        }

        public static Graded unavailable(long latencyMs) {
            return new Graded(WorldRulesVerdict.unavailable(), latencyMs, 0.0, "");
        }
    }

    private static volatile SafetyGrader.Transport transport =
            new SafetyGrader.CliTransport(WorldRulesGrader::model, () -> SafetyGrader.HAIKU_PIN,
                    com.moddingfromamod.config.HelperRoute.Job.WORLD_RULES);
    private static volatile Function<String, WorldRulesVerdict> stub;
    private static final LinkedHashMap<String, CompletableFuture<Graded>> MEMO = new LinkedHashMap<>();
    private static final Object LOCK = new Object();

    private WorldRulesGrader() {
    }

    public static String model() {
        String m = System.getProperty(MODEL_PROPERTY, com.moddingfromamod.config.MagicianWiring.cheapSpec(MuseGrade.EMBER));
        return m == null || m.isBlank() ? com.moddingfromamod.config.MagicianWiring.cheapSpec(MuseGrade.EMBER) : m.strip();
    }

    /** Is the grader on? Off is the A/B seam and the escape hatch; the backstop still runs. */
    public static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty(GRADER_PROPERTY, "on").strip());
    }

    public static long budgetMs() {
        try {
            return Math.max(0L, Long.parseLong(System.getProperty(BUDGET_PROPERTY, String.valueOf(DEFAULT_BUDGET_MS))));
        } catch (NumberFormatException e) {
            return DEFAULT_BUDGET_MS;
        }
    }

    /** The exact user message: this world's rules, then the wish. */
    public static String userText(WorldRules rules, String wishText) {
        List<String> denied = new ArrayList<>();
        for (WorldEffect e : WorldEffect.values()) {
            if (rules.denies(e)) {
                denied.add(e.key());
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("THIS WORLD DENIES: ").append(denied.isEmpty() ? "nothing" : String.join(", ", denied)).append('\n');
        List<String> scopes = rules.allowedScopes();
        if (!scopes.isEmpty()) {
            sb.append("THIS WORLD ALLOWS standing rules scoped to: ").append(String.join(", ", scopes)).append('\n');
        }
        sb.append("THE CHILD'S WISH: ").append(WishText.sanitize(wishText));
        return sb.toString();
    }

    /**
     * Start grading a wish in the background, under the rules in force. Does nothing — no model
     * call, no cost — on a world whose rules deny nothing (that world sees no change).
     *
     * @return a future that always completes, never exceptionally
     */
    public static CompletableFuture<Graded> gradeInput(String wishText) {
        return gradeInput(wishText, WorldRulesService.current());
    }

    public static CompletableFuture<Graded> gradeInput(String wishText, WorldRules rules) {
        String text = WishText.sanitize(wishText);
        if (text.isEmpty() || rules == null || !rules.deniesAnything() || !enabled()) {
            return CompletableFuture.completedFuture(Graded.unavailable(0));
        }
        String user = userText(rules, text);
        synchronized (LOCK) {
            CompletableFuture<Graded> existing = MEMO.get(user);
            if (existing != null) {
                return existing;
            }
            CompletableFuture<Graded> f = new CompletableFuture<>();
            Thread worker = new Thread(() -> {
                long started = System.currentTimeMillis();
                try {
                    f.complete(run(user, started));
                } catch (Throwable e) {
                    LOGGER.debug("world-rules grade failed: {}", e.toString());
                    f.complete(Graded.unavailable(System.currentTimeMillis() - started));
                }
            }, "mfam-world-rules-grade");
            worker.setDaemon(true);
            MEMO.put(user, f);
            trim();
            worker.start();
            return f;
        }
    }

    /**
     * Wait for the grade at pickup, for at most the budget; a grade that is late is UNSURE (the
     * wish goes through) and keeps running into the memo. Never throws.
     */
    public static Graded await(String wishText, WorldRules rules) {
        long started = System.currentTimeMillis();
        try {
            return gradeInput(wishText, rules).get(budgetMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Graded.unavailable(System.currentTimeMillis() - started);
        } catch (Exception e) {
            return Graded.unavailable(System.currentTimeMillis() - started);
        }
    }

    private static void trim() {
        if (MEMO.size() <= MAX_MEMO) {
            return;
        }
        var it = MEMO.entrySet().iterator();
        while (it.hasNext() && MEMO.size() > MAX_MEMO) {
            if (it.next().getValue().isDone()) {
                it.remove();
            }
        }
    }

    private static Graded run(String user, long started) {
        Function<String, WorldRulesVerdict> s = stub;
        if (s != null) {
            WorldRulesVerdict v = s.apply(user);
            return new Graded(v == null ? WorldRulesVerdict.unavailable() : v,
                    System.currentTimeMillis() - started, 0.0, "test");
        }
        String prompt = resource("grader-prompt.md");
        String schema = resource("verdict-schema.json");
        if (prompt.isEmpty() || schema.isEmpty()) {
            return Graded.unavailable(System.currentTimeMillis() - started);
        }
        SafetyGrader.Reply reply = transport.grade(prompt, user, schema, SafetyGrader.CliTransport.TIMEOUT_MS);
        long wall = System.currentTimeMillis() - started;
        if (reply == null) {
            return Graded.unavailable(wall);
        }
        return new Graded(WorldRulesVerdict.parse(reply.structuredOutput()), wall, reply.costUsd(),
                reply.modelId(), reply.arm() == null ? "" : reply.arm());
    }

    /** The grader's prompt or schema, from the jar — the eval hands the model these same bytes. */
    static String resource(String name) {
        try (InputStream in = WorldRulesGrader.class.getResourceAsStream("/worldrules/" + name)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (Exception e) {
            return "";
        }
    }

    /** The grade if it is already there. Never starts one. */
    public static Optional<Graded> ifReady(String wishText, WorldRules rules) {
        CompletableFuture<Graded> f;
        synchronized (LOCK) {
            f = MEMO.get(userText(rules, WishText.sanitize(wishText)));
        }
        return f != null && f.isDone() ? Optional.ofNullable(f.getNow(null)) : Optional.empty();
    }

    // ── test seams ───────────────────────────────────────────────────────────────────────────────

    /** Answer every grade with this function (null restores the model). */
    public static void forTest(Function<String, WorldRulesVerdict> fixed) {
        stub = fixed;
        clear();
    }

    /** Swap the transport (null restores the CLI). */
    public static void transportForTest(SafetyGrader.Transport t) {
        transport = t == null ? new SafetyGrader.CliTransport(WorldRulesGrader::model, () -> SafetyGrader.HAIKU_PIN,
                    com.moddingfromamod.config.HelperRoute.Job.WORLD_RULES) : t;
        clear();
    }

    public static void clear() {
        synchronized (LOCK) {
            MEMO.clear();
        }
    }
}
