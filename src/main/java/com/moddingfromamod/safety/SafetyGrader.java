package com.moddingfromamod.safety;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.agent.ClaudeCli;
import com.moddingfromamod.runtime.RuntimeConfig;
import com.moddingfromamod.wish.WishText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The cheap second model, asked one closed question about one piece of text.</b>
 *
 * <p>{@code docs/CHILD_SAFETY_PLAN.md} §3 layers L1 and L2, and the shape
 * {@code docs/PATTERNS.md} §1 already argues for: a fast model answers a narrow question and the
 * strong model does the work. Extraction and classification, where the answer is short, the
 * question is closed, and being slightly wrong is cheap.
 *
 * <h2>Measured, and the numbers decide the flags</h2>
 *
 * <p>One Haiku grade through the CLI, from §0:
 *
 * <pre>
 *   as CharacterResolver does it (--allowed-tools "" only)   12.6s   $0.075   33k prompt tokens
 *   --system-prompt replaced, tools still present            15.0s   $0.070   30k
 *   --tools "" + --json-schema                                9.6s   $0.0055   1.6k
 *   --bare                                                    1.0s   unusable: drops settings AND auth
 * </pre>
 *
 * <p>So {@code --tools ""} is not an optimisation, it is the difference between $0.0055 and $0.075
 * a wish — and it is the same flag {@link com.moddingfromamod.reference.ReferenceResearcher}
 * reaches for on security grounds: {@code --allowed-tools} is a PERMISSION list and leaves all 38
 * built-in tools PRESENT. This process has no tools, no MCP config and one turn. The floor is ~10 s
 * whatever the prompt says, which is why nothing on the submission path waits for it.
 *
 * <h2>Every failure is UNAVAILABLE, and UNAVAILABLE is never a throw</h2>
 *
 * <p>No CLI, a timeout, a non-zero exit, an envelope nothing can read, a reply that is not a
 * verdict — all of them land on {@link SafetyVerdict#unavailable()}. A child must never see a wish
 * break because a subprocess was strange, and a grader that can throw is a grader that can take a
 * wish down with it.
 */
public final class SafetyGrader {

    private static final Logger LOGGER = LoggerFactory.getLogger(SafetyGrader.class);

    private SafetyGrader() {
    }

    // ---- the boundary ----

    /**
     * What a grade came back as, before it is a verdict.
     *
     * <p>The cost and latency fields are here rather than measured by the caller because they come
     * out of the CLI's own envelope, which is the only place the API time and the real model id are
     * stated. §6 logs all three per grade, and a cost worked out later from a token estimate is
     * exactly the substitution the observability section warns against.
     *
     * @param structuredOutput the {@code structured_output} object as JSON, or null when there
     *                         was none
     * @param apiMs            {@code duration_api_ms} — model time, excluding CLI startup
     * @param costUsd          {@code total_cost_usd}
     * @param modelId          the resolved model, e.g. {@code claude-haiku-4-5-20251001} — read
     *                         from the reply rather than from config, because an alias is not a
     *                         measurement
     * @param raw              the whole envelope, for the log's {@code raw} field
     */
    public record Reply(String structuredOutput, long apiMs, double costUsd, String modelId,
                        String raw, String arm) {
        /** A reply with no arm named (every stub, and any transport that is not the chain). */
        public Reply(String structuredOutput, long apiMs, double costUsd, String modelId, String raw) {
            this(structuredOutput, apiMs, costUsd, modelId, raw, "");
        }
    }

    /** How a grade is obtained. One implementation today; §7's direct-API arm is the second. */
    public interface Transport {
        /**
         * Ask once. Must not throw; null means "no answer", which becomes
         * {@link SafetyVerdict#unavailable()}.
         */
        Reply grade(String systemPrompt, String userText, String jsonSchema, long timeoutMs);

        /**
         * Ask once, as a given model at a given effort (the input and plan graders are
         * measured on different models, so the caller names the one it was measured on). A
         * transport that has no notion of a model — every test stub — ignores both.
         */
        default Reply grade(String systemPrompt, String userText, String jsonSchema,
                            long timeoutMs, String model, String effort) {
            return grade(systemPrompt, userText, jsonSchema, timeoutMs);
        }
    }

    /** A verdict plus what it cost to get, which is what {@code ActionLog.safety} writes. */
    public record Graded(SafetyVerdict verdict, long latencyMs, double costUsd, String modelId,
                         String raw, String arm) {

        /** No arm named: {@code arm} is written only when the chain said which step answered. */
        public Graded(SafetyVerdict verdict, long latencyMs, double costUsd, String modelId, String raw) {
            this(verdict, latencyMs, costUsd, modelId, raw, "");
        }

        /** The shape every failure path returns: nothing graded, nothing spent. */
        public static Graded unavailable(long latencyMs) {
            return new Graded(SafetyVerdict.unavailable(), latencyMs, 0.0, "", "");
        }
    }

    // ---- state ----

    private static volatile Transport transport = new CliTransport();

    /**
     * A whole-path stub for wiring tests. Non-null means no subprocess is ever started.
     *
     * <p>Separate from {@link #transport} on purpose: a Gate B test wants to say "this wish grades
     * CLEARLY" and does not want to hand-write an envelope, while an offline transport test wants
     * the real parse path exercised. Two questions, two seams.
     */
    private static volatile Function<String, SafetyVerdict> stub;

    /**
     * <b>How many grades are remembered at once.</b>
     *
     * <p>A session is a few hundred wishes at the very outside, so this is not a working-set
     * limit — it is a ceiling, and the memo it bounds holds <b>the child's own words</b> plus the
     * model's whole envelope per entry. The map used to be unbounded and evicted only by the test
     * seams, which meant a long-lived server kept every wish text of the evening in heap for the
     * life of the process (CS-REVIEW-2). 256 is chosen so that nothing a real session does can
     * reach it and a process that runs for a week still cannot grow without limit.
     */
    static final int MAX_INPUT_GRADES = 256;

    /**
     * One grade per sanitised text, so the same wish is not graded twice on re-offer.
     *
     * <p><b>Insertion-ordered and capped at {@link #MAX_INPUT_GRADES}</b>, oldest out first — and
     * <b>an in-flight future is never evicted</b>. That second rule is the one that matters: the
     * three re-wish paths that skip {@code WishService.submit} ({@code /moddy no} made it an undo,
     * {@code /moddy redirect}, an agent re-offer) JOIN the grade already running rather than
     * starting a second one, so dropping a running future would not break a wish but would spend
     * a second ~10 s and ~$0.01 on a question already being asked. A completed entry costs nothing
     * to lose but the memo.
     *
     * <p>A plain {@link LinkedHashMap} under an explicit lock rather than a
     * {@link ConcurrentHashMap}, because the cap and the insertion have to be one atomic step —
     * two of them would let the size drift under the concurrency the old type was chosen for. The
     * lock is only ever held for a map operation and a thread start; the grading worker itself
     * never touches the map, so nothing can wait on a model while holding it.
     *
     * <p>Declared as {@code LinkedHashMap} and not {@code Map} on purpose: {@link #trim} evicts by
     * ITERATION ORDER, so insertion order is not a detail of the initialiser, it is the contract.
     * A later tidy-up to {@code Map} + {@code HashMap} would leave the cap working and make WHICH
     * grade it forgets arbitrary, which is the kind of change nothing would report.
     */
    private static final LinkedHashMap<String, CompletableFuture<Graded>> INPUT_GRADES =
            new LinkedHashMap<>();

    /** Guards {@link #INPUT_GRADES} and nothing else. */
    private static final Object GRADES_LOCK = new Object();

    /**
     * Whether an input grade was ever started for this wish text and is still remembered. A READ
     * for {@code PreAgentModelsGameTest}, which proves a pressed recipe starts NO grade (
     * step 1) and a typed wish still does. Nothing in the mod decides anything with it.
     */
    public static boolean startedFor(String wishText) {
        String text = WishText.sanitize(wishText);
        synchronized (GRADES_LOCK) {
            return INPUT_GRADES.containsKey(text);
        }
    }

    // ---- L1: the input grade ----

    /**
     * Start grading a child's wish, in the background.
     *
     * <p>Launched from {@code WishService.submit} beside {@code ReferenceHint.prefetchFrom} — the
     * same fire-and-forget shape for the same reason: <b>nothing on the wish-submission path may
     * block</b>, and even a cheap model is a network round trip (PATTERNS.md, learned by review
     * rather than by testing after {@code CharacterResolver} was called inline and could have
     * frozen the game for fifteen seconds per wish).
     *
     * <p>Memoised on the sanitised text, so the three re-wish paths that skip {@code submit}
     * ({@code /moddy no}, {@code /moddy redirect}, an agent re-offer) join the grade already in
     * flight rather than starting a second one.
     *
     * @return a future that <b>always completes</b> — with {@link Graded#unavailable} on every
     *         failure, and never exceptionally
     */
    public static CompletableFuture<Graded> gradeInput(String wishText) {
        String text = WishText.sanitize(wishText);
        // Magician off: no model call; the lexicon (L0) grades on its own, and nothing counts as the grader
        // being down (an answer never asked for is not a failure, so Moddy is not put to bed for it).
        if (text.isEmpty() || !ChildSafetyConfig.grading() || !mayCallAModel()) {
            return CompletableFuture.completedFuture(Graded.unavailable(0));
        }
        synchronized (GRADES_LOCK) {
            CompletableFuture<Graded> existing = INPUT_GRADES.get(text);
            if (existing != null) {
                return existing;
            }
            CompletableFuture<Graded> f = new CompletableFuture<>();
            // A PLAIN DAEMON THREAD, not an executor. CLAUDE.md: a task that dies inside a
            // ScheduledExecutorService takes its reason into a Future nobody reads -- a narrator
            // built that way worked in a unit test and never ran once inside the game. This thread
            // owns its own try/catch and the future is completed on every path including the
            // catch, so "the grade failed" is a value rather than a silence.
            Thread worker = new Thread(() -> {
                long started = System.currentTimeMillis();
                try {
                    f.complete(run(promptFor("input-grader-prompt.md"), text, schema(), started,
                            ChildSafetyConfig.model(), ChildSafetyConfig.effort()));
                } catch (Throwable e) {
                    LOGGER.debug("input grade failed: {}", e.toString());
                    f.complete(Graded.unavailable(System.currentTimeMillis() - started));
                }
            }, "mfam-safety-input");
            worker.setDaemon(true);
            // PUT AND TRIM BEFORE START, so `trim` cannot possibly see this entry as finished and
            // evict the grade it was called for. Nothing has completed `f` yet, so it is in flight
            // by construction at the moment the cap is applied.
            INPUT_GRADES.put(text, f);
            trim();
            worker.start();
            return f;
        }
    }

    /**
     * Bring the memo back under its cap, oldest first, <b>skipping anything still running</b>.
     *
     * <p>Called with {@link #GRADES_LOCK} held. If every entry is in flight — which would need 256
     * grades outstanding at once and cannot happen in play — the map is left OVER the cap rather
     * than a running grade thrown away: a ceiling exceeded for a moment is a smaller problem than
     * paying twice for a question already being asked, and the next completed grade brings it back.
     */
    private static void trim() {
        if (INPUT_GRADES.size() <= MAX_INPUT_GRADES) {
            return;
        }
        var it = INPUT_GRADES.entrySet().iterator();
        while (it.hasNext() && INPUT_GRADES.size() > MAX_INPUT_GRADES) {
            if (it.next().getValue().isDone()) {
                it.remove();
            }
        }
    }

    /**
     * The grade if it is already there, or empty. <b>Never starts one.</b>
     *
     * <p>For a caller that wants to enrich a decision only when the answer is sitting there — the
     * {@code ReferenceResearcher.cachedOnly} shape, and the safe thing to call from a tick.
     */
    /**
     * <b>One fresh input grade, now, blocking</b> — the Magician check's. Not memoised: the check is asking
     * whether the model the host just picked answers, so an earlier grade of the same words by another model must
     * not stand in for it. Never on the server thread.
     */
    public static Graded gradeInputNow(String wishText) {
        String text = WishText.sanitize(wishText);
        if (text.isEmpty() || !ChildSafetyConfig.grading() || !mayCallAModel()) {
            return Graded.unavailable(0);
        }
        long started = System.currentTimeMillis();
        try {
            return run(promptFor("input-grader-prompt.md"), text, schema(), started,
                    ChildSafetyConfig.model(), ChildSafetyConfig.effort());
        } catch (Throwable e) {
            return Graded.unavailable(System.currentTimeMillis() - started);
        }
    }

    public static java.util.Optional<Graded> inputGradeIfReady(String wishText) {
        CompletableFuture<Graded> f;
        synchronized (GRADES_LOCK) {
            f = INPUT_GRADES.get(WishText.sanitize(wishText));
        }
        return f != null && f.isDone() ? java.util.Optional.ofNullable(f.getNow(null))
                : java.util.Optional.empty();
    }

    // ---- L2: the plan grade ----

    /**
     * Grade a plan, synchronously.
     *
     * <p>Synchronous because the agent is already waiting on this tool call, so the ~10 s is spent
     * inside a wish that costs 60–300 s — and because the property §3 L2 wants is that the grade
     * returns <i>before the tool body runs</i>, so {@code WishFeedback.planned} never shows a child
     * "✨ Here's my plan:" for a stage that is about to be diverted. Grading inside
     * {@code PlanWishTool} would not have that property.
     *
     * <p>Not memoised: two plans for one wish are two different plans, and a second call after a
     * divert is the retry we specifically want graded again.
     */
    public static Graded gradePlan(String wishText, List<String> steps, List<String> claims,
                                   String status) {
        if (!ChildSafetyConfig.grading() || !mayCallAModel()) {
            return Graded.unavailable(0);
        }
        long started = System.currentTimeMillis();
        try {
            return run(promptFor("plan-grader-prompt.md"),
                    planText(wishText, steps, claims, status), planSchema(), started,
                    ChildSafetyConfig.planModel(), ChildSafetyConfig.planEffort());
        } catch (Throwable e) {
            LOGGER.debug("plan grade failed: {}", e.toString());
            return Graded.unavailable(System.currentTimeMillis() - started);
        }
    }

    /**
     * The plan, as the grader sees it.
     *
     * <p>Steps are NUMBERED FROM 0 and the numbering is stated, because {@code stageIndex} is the
     * whole point of the plan grade: a refusal that cannot name the stage makes the agent rewrite
     * the plan from scratch, which is the failure {@code RefusalRedirectTest} already records for
     * refusals that name no alternative.
     */
    static String planText(String wishText, List<String> steps, List<String> claims,
                           String status) {
        StringBuilder sb = new StringBuilder();
        sb.append("THE CHILD'S WISH: ").append(WishText.sanitize(wishText)).append('\n');
        sb.append("\nTHE PLAN, one step per line, numbered from 0:\n");
        List<String> s = steps == null ? List.of() : steps;
        if (s.isEmpty()) {
            sb.append("  (no steps were given)\n");
        }
        for (int i = 0; i < s.size(); i++) {
            sb.append("  ").append(i).append(": ").append(clean(s.get(i))).append('\n');
        }
        if (claims != null && !claims.isEmpty()) {
            sb.append("\nWHAT IT CLAIMS THE RESULT WILL DO:\n");
            for (String c : claims) {
                sb.append("  - ").append(clean(c)).append('\n');
            }
        }
        if (status != null && !status.isBlank()) {
            sb.append("\nTHE STATUS LINE THE CHILD WILL SEE: ").append(clean(status)).append('\n');
        }
        return sb.toString();
    }

    /** One line, bounded, no control characters — the same treatment the wish itself gets. */
    private static String clean(String s) {
        String t = WishText.sanitize(s);
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    // ---- the common path ----

    private static Graded run(String systemPrompt, String userText, String jsonSchema,
                              long started, String model, String effort) {
        Function<String, SafetyVerdict> s = stub;
        if (s != null) {
            SafetyVerdict v = s.apply(userText);
            return new Graded(v == null ? SafetyVerdict.unavailable() : v,
                    System.currentTimeMillis() - started, 0.0, "test", "");
        }
        Reply reply = transport.grade(systemPrompt, userText, jsonSchema, CliTransport.TIMEOUT_MS,
                model, effort);
        long wall = System.currentTimeMillis() - started;
        if (reply == null) {
            lastFailAtMs = System.currentTimeMillis();
            // An unanswered grade is what puts Moddy to bed for NEW grants. Only a
            // transport-level silence — a reply that came back and would not parse is below, and
            // counts as ANSWERED, because the grader was reachable (decision 12.2 keeps it open).
            com.moddingfromamod.health.ModdyTired.global().graderFailed(lastFailAtMs,
                    "the safety grader gave no answer after " + wall + "ms");
            return Graded.unavailable(wall);
        }
        SafetyVerdict v = SafetyVerdict.parse(reply.structuredOutput());
        if (answered(reply)) {
            lastOkAtMs = System.currentTimeMillis();
            com.moddingfromamod.health.ModdyTired.global().graderAnswered(lastOkAtMs);
        } else {
            // AN ENVELOPE NOBODY WROTE. A signed-out CLI, a revoked token or a CLI that fell over
            // before the model ran still prints a JSON envelope — with no structured output and no
            // model usage — so "the transport returned something" is not "the grader answered".
            // Counted as answered, a signed-out grader would never tire Moddy, and D8's outage
            // would pass every new wish on the lexicon alone, silently, for as long as it lasted.
            lastFailAtMs = System.currentTimeMillis();
            com.moddingfromamod.health.ModdyTired.global().graderFailed(lastFailAtMs,
                    "the safety grader answered without a model (signed out, or the CLI failed)");
        }
        return new Graded(v, wall, reply.costUsd(), reply.modelId(), reply.raw(),
                reply.arm() == null ? "" : reply.arm());
    }

    /**
     * <b>Did a MODEL answer?</b> — structured output, or reported model usage. The one
     * predicate both halves of "Moddy is tired" read: this class stamps the tired state with it,
     * and {@code AwaitWishTool} asks the same question of a graded wish (an empty model id beside
     * an unavailable verdict). A reply that ran the model and came back unusable — the
     * {@code error_max_turns} envelope, which carries its usage — is answered, and decision 12.2's
     * {@code open} still holds for it.
     */
    static boolean answered(Reply reply) {
        return reply != null && (reply.structuredOutput() != null
                || (reply.modelId() != null && !reply.modelId().isEmpty()));
    }

    // ── IS THE GRADER ANSWERING? (health.json's `safety` block) ─────────────────────────
    //
    // Stamped HERE, in the one common path, rather than at the three call sites: a "last ok" that
    // some paths recorded and others did not is the completion-time defect WishBook's javadoc
    // records — an average that looks real. The distinction being drawn is the operator's, not the
    // child's: `unavailable` means the subprocess did not answer, and on a machine configured
    // `child_safety_unavailable=closed` that is a wish refused for an outage. A watchdog needs to
    // know it is happening; SafetyWords.UNAVAILABLE is what the child is told either way.

    private static volatile long lastOkAtMs;
    private static volatile long lastFailAtMs;

    /** When the grader last answered, in epoch millis, or 0 if it never has. */
    public static long lastOkAtMs() {
        return lastOkAtMs;
    }

    /** When the grader last failed to answer, in epoch millis, or 0 if it never has. */
    public static long lastFailAtMs() {
        return lastFailAtMs;
    }

    // ---- resources ----

    /**
     * The grader's own system prompt, from the jar.
     *
     * <p>A resource rather than a Java string so that {@code tools/safety-arm.py} can hand the very
     * same bytes to the model it replays with. Two copies of a prompt agree only by luck, and an
     * arm measuring a different prompt from the one that ships measures nothing (the same argument
     * {@code routing-arm.py} makes about replaying the agent's REAL contract).
     */
    static String promptFor(String resource) {
        // Also registers an Ember-only fork (`X.ember.md`) when one exists: a Muse grade asks with it,
        // the Haiku fallback with this original (agent/MusePrompts).
        return com.moddingfromamod.agent.MusePrompts.load(SafetyGrader.class, "/safety/" + resource);
    }

    /** The input grader's prompt resource; its Muse call is the one whose text is framed. */
    static final String INPUT_PROMPT = "input-grader-prompt.md";

    /** Where the Muse frame's template lives; {@code tools/safety-arm.py} reads the same file. */
    static final String MUSE_WISH_FRAME = "/safety/muse-wish-frame.txt";

    /**
     * The user message a MUSE grade of {@code userText} is sent with. Only the WISH-TEXT
     * grade (its system prompt is the input grader's) is framed: Ember answered a bare greeting in
     * words ("Hello! How can I help?") instead of grading it, whatever the prompt said, and the frame
     * makes it plainly text to grade. Every other Muse job on this transport (the plan grader, world
     * rules) was measured unframed and is passed through untouched, and the Claude fallback never
     * comes here. The markers are removed from the child's text first, so it cannot close the frame.
     */
    static String museUserText(String systemPrompt, String userText) {
        if (userText == null || systemPrompt == null || !systemPrompt.equals(promptFor(INPUT_PROMPT))) {
            return userText;
        }
        String template = museWishFrame();
        if (template.isEmpty()) {
            return userText;
        }
        String wish = userText.replace("<<<WISH", "").replace("WISH>>>", "");
        return template.replace("{wish}", wish);
    }

    /** The effort the wish-text grade runs at on Muse: Ember's measured one. */
    static final String INPUT_MUSE_EFFORT = "low";

    /**
     * The effort a MUSE grade is asked at. The wish-text grade always runs at
     * {@link #INPUT_MUSE_EFFORT}, the effort Ember was validated at, whatever
     * {@code child_safety_effort} says: that knob is Haiku's ({@code high}) and still reaches the
     * Haiku fallback unchanged, so switching the grader to Ember is ONE setting,
     * {@code child_safety_model=muse:muse-ember-1.0}. Every other job keeps the caller's effort,
     * and no effort means low.
     */
    static String museEffort(String systemPrompt, String effort) {
        if (systemPrompt != null && systemPrompt.equals(promptFor(INPUT_PROMPT))) {
            return INPUT_MUSE_EFFORT;
        }
        return effort == null || effort.isBlank() ? "low" : effort;
    }

    private static volatile String museWishFrame;

    static String museWishFrame() {
        String t = museWishFrame;
        if (t == null) {
            try (InputStream in = SafetyGrader.class.getResourceAsStream(MUSE_WISH_FRAME)) {
                t = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                t = "";
            }
            museWishFrame = t;
        }
        return t;
    }

    /**
     * The JSON Schema handed to {@code --json-schema}, as the INPUT grade wants it.
     *
     * @see SafetyVerdict
     * @see #planSchema()
     */
    static String schema() {
        try (InputStream in = SafetyGrader.class.getResourceAsStream("/safety/verdict-schema.json")) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * The same schema with <b>{@code stageIndex} required</b> — the PLAN shape (decision 12.12).
     *
     * <p>The field is the whole point of the plan grade: a divert that cannot name the step makes
     * the agent re-plan from scratch, which is the failure {@code RefusalRedirectTest} records for
     * a refusal that names no alternative. Optional, it was a field a model could simply leave
     * out, and a grader answering nothing is indistinguishable from a plan that leans as a whole.
     * Required, the model has to state one — and {@code -1} is the real answer for "no single step
     * is responsible", so nothing is being forced into a lie.
     *
     * <p><b>{@link SafetyPlanGate#divertMessage}'s whole-plan wording stays</b>, and is now what it
     * was always meant to be: the honest reading of an explicit {@code -1}, plus the fallback for a
     * malformed grade — a reply that dodges the schema, or a hand-rolled one from a stub. A schema
     * bound is not a guarantee about a subprocess.
     *
     * <p><b>DERIVED rather than a second file</b>, which is the only interesting decision here.
     * Two schemas would be two statements of one vocabulary, and
     * {@code SafetyVerdictSchemaTest} exists because that drifts in both directions — the model
     * offered a word the parser drops, or the parser waiting for one the model is never offered.
     * Appending to {@code required} is a two-line transform on the one file, so the four axes, the
     * bands, the reasons and the bounds are shared by construction and cannot disagree.
     *
     * <p>The INPUT grade keeps the un-required shape deliberately: an input has no stages, so
     * demanding the field there would be asking a model to answer a question about something that
     * does not exist, every time, for a value nothing reads ({@code SafetyInputGate.combine} sets
     * {@link SafetyVerdict#NO_STAGE} outright). Falls back to the plain schema if anything about
     * the transform fails — a grade with a weaker bound beats no grade at all.
     */
    static String planSchema() {
        String base = schema();
        try {
            JsonObject o = JsonParser.parseString(base).getAsJsonObject();
            com.google.gson.JsonArray required = o.getAsJsonArray("required");
            for (JsonElement e : required) {
                if ("stageIndex".equals(e.getAsString())) {
                    return base;
                }
            }
            required.add("stageIndex");
            return o.toString();
        } catch (RuntimeException e) {
            LOGGER.debug("could not require stageIndex for the plan grade: {}", e.toString());
            return base;
        }
    }

    // ---- the CLI transport ----

    /**
     * The shipped transport: one {@code claude -p} subprocess, no tools, one turn, a JSON schema.
     *
     * <p>What is ABSENT matters as much as what is present — no {@code --mcp-config}, so this
     * process has no wish tools whatsoever, and {@code --tools ""} rather than
     * {@code --allowed-tools ""} alone, because the latter is a permission list and leaves every
     * built-in tool present (measured in real traces, a sibling process with that flag alone ran
     * Bash and read the parent session's notifications).
     */
    /**
     * The Haiku every Claude-side grader runs on, PINNED: the plan grader's and world rules' fallback,
     * the character lookup's fallback and the ready-made nudge. Never the {@code haiku} alias, which
     * moves when Anthropic moves it (no silent model upgrades under a measured
     * grader). It is what the alias resolved to when each of these was measured
     * (docs/EMBER_GRADERS.md).
     */
    public static final String HAIKU_PIN = ChildSafetyConfig.HAIKU_55;

    public static final class CliTransport implements Transport {

        /**
         * How long the subprocess gets — <b>deliberately not the same number</b> as
         * {@link ChildSafetyConfig#inputBudgetMs()}.
         *
         * <p>Those are two different questions and conflating them throws away the answer:
         * {@code inputBudgetMs} is how long the DEQUEUE side is willing to hold a child's wish,
         * while this is how long the grade is worth running at all. A grade that arrives at 25 s
         * is too late to act on and is still exactly what shadow mode exists to collect, so
         * killing the subprocess at the caller's budget would leave a hole in the base rate
         * precisely on the hardest wishes — the ones a floor most needs to be measured against.
         *
         * <p>MEASURED, 2026-09-11, four live Haiku grades through this transport: the easy cases
         * came back in <b>7.6 s and 9.5 s</b>, and the two that lean (a gore wish, a three-step
         * plan) were both still running at <b>20 s</b> and were lost. §0's "~10 s floor" is a
         * floor and not a distribution; the tail is what a timeout has to be set against.
         */
        public static final long TIMEOUT_MS = 60_000L;

        /**
         * Which model, asked at each grade. The safety grader's is {@link ChildSafetyConfig#model()}
         * (the no-argument constructor — the invocation its floors were measured on, unchanged);
         * The world-rules grader borrows this transport with its own knob, because the flags
         * below are the measured security boundary and a second copy of them would drift.
         */
        private final java.util.function.Supplier<String> model;

        /**
         * The model a {@code muse:} grade falls back to when Muse Code gives no answer (no binary, no
         * key, an error, a timeout, a non-JSON answer): the job's Haiku model, the one it ran on
         * before Ember was accepted (docs/EMBER_GRADERS.md). A Muse outage costs a grade a second
         * call, never a wish its grade.
         */
        private final java.util.function.Supplier<String> fallback;

        public CliTransport() {
            this(ChildSafetyConfig::model, () -> ChildSafetyConfig.HAIKU_55);
        }

        public CliTransport(java.util.function.Supplier<String> model) {
            this(model, () -> ChildSafetyConfig.HAIKU_55);
        }

        public CliTransport(java.util.function.Supplier<String> model,
                            java.util.function.Supplier<String> fallback) {
            this(model, fallback, null);
        }

        /**
         * Which helper job a grade is, for its route ({@link com.moddingfromamod.config.HelperRoute}): fixed for the
         * world-rules grader's own transport; null means this grader's two prompts, the wish text and the plan.
         */
        private final com.moddingfromamod.config.HelperRoute.Job job;

        public CliTransport(java.util.function.Supplier<String> model,
                            java.util.function.Supplier<String> fallback,
                            com.moddingfromamod.config.HelperRoute.Job job) {
            this.model = model == null ? ChildSafetyConfig::model : model;
            this.fallback = fallback == null ? () -> ChildSafetyConfig.HAIKU_55 : fallback;
            this.job = job;
        }

        com.moddingfromamod.config.HelperRoute.Job jobFor(String systemPrompt) {
            if (job != null) {
                return job;
            }
            return systemPrompt != null && systemPrompt.equals(promptFor(INPUT_PROMPT))
                    ? com.moddingfromamod.config.HelperRoute.Job.WISH_TEXT
                    : com.moddingfromamod.config.HelperRoute.Job.PLAN;
        }

        @Override
        public Reply grade(String systemPrompt, String userText, String jsonSchema,
                           long timeoutMs) {
            return grade(systemPrompt, userText, jsonSchema, timeoutMs, null, null);
        }

        /**
         * {@code model} null or blank means this transport's own supplier (the world-rules grader
         * passes nothing and keeps its knob); {@code effort} null means no {@code --effort} flag,
         * which is how every grade was taken before the current grader.
         */
        @Override
        public Reply grade(String systemPrompt, String userText, String jsonSchema,
                           long timeoutMs, String modelId, String effort) {
            String spec = modelId == null || modelId.isBlank() ? model.get() : modelId;
            boolean muse = com.moddingfromamod.agent.MuseGrade.isMuse(spec);
            // The job's two models, unchanged: Ember (or the picked Muse helper) and its pinned Haiku. WHICH of them
            // may be asked, and how each is paid, is the route's: one place, every helper job.
            String claudeModel = muse ? fallback.get() : spec;
            var steps = com.moddingfromamod.config.HelperRoute.steps(jobFor(systemPrompt), muse);
            boolean[] museAsked = {false};
            return com.moddingfromamod.config.HelperRoute.first(steps, !muse ? null : museSteps -> {
                if (systemPrompt.isEmpty() || jsonSchema.isEmpty()) {
                    return null;
                }
                museAsked[0] = true;
                com.moddingfromamod.agent.MuseExec.Answer a = com.moddingfromamod.agent.MuseExec.grade(
                        // The Ember-only fork when the prompt has one; the Claude step keeps the
                        // original, the one Haiku's floors were measured on. The WISH-TEXT grade's
                        // text is framed for Muse only (museUserText); the Claude step gets it as typed.
                        com.moddingfromamod.agent.MusePrompts.forMuse(systemPrompt),
                        museUserText(systemPrompt, userText), jsonSchema, com.moddingfromamod.agent.MuseGrade.modelId(spec),
                        // No effort from the caller means Ember's measured one (low); the Claude
                        // step still gets the caller's, so its invocation is unchanged.
                        museEffort(systemPrompt, effort), Math.min(timeoutMs, com.moddingfromamod.agent.MuseExec.TIMEOUT_MS),
                        museSteps);
                // The same Reply a Claude grade makes; apiMs is the wall time (muse exec reports
                // no API share) and the cost is list price from the run's own token counts.
                return a == null ? null : new Reply(a.text(), a.wallMs(), a.costUsd(), a.modelId(), a.text(), a.arm());
            }, step -> {
                if (museAsked[0]) {
                    LOGGER.info("muse grade unavailable; grading with {} instead", claudeModel);
                }
                Reply r = claude(systemPrompt, userText, jsonSchema, timeoutMs, claudeModel, effort, step);
                // The chain's Claude step: the record says which arm answered, Ember's or this one.
                return r == null ? null : new Reply(r.structuredOutput(), r.apiMs(), r.costUsd(), r.modelId(), r.raw(),
                        com.moddingfromamod.agent.MuseExec.ARM_HAIKU);
            });
        }

        private Reply claude(String systemPrompt, String userText, String jsonSchema,
                             long timeoutMs, String modelId, String effort,
                             com.moddingfromamod.config.HelperRoute.Step step) {
            String cli = ClaudeCli.resolve();
            if (cli == null || cli.isBlank() || systemPrompt.isEmpty() || jsonSchema.isEmpty()) {
                return null;
            }
            Process p = null;
            try {
                List<String> cmd = new ArrayList<>(List.of(
                        cli, "-p", userText,
                        "--system-prompt", systemPrompt,
                        "--model", modelId,
                        // Not --allowed-tools alone: see the class note. This is the flag that
                        // takes the prompt from 33k tokens to 1.6k and the cost from $0.075 to
                        // $0.0055, and it is also the whole security boundary.
                        "--tools", "",
                        "--allowed-tools", "",
                        "--strict-mcp-config",
                        // THREE, and it is not a performance knob. MEASURED 2026-09-11 (CS-S1b)
                        // over the whole 624-row labelled corpus: at "1", **57 of 624 grades
                        // (9.1%) came back with no structured output at all** -- the envelope says
                        // `is_error: true, subtype: error_max_turns`, carries a cost, and produces
                        // UNAVAILABLE. It is DETERMINISTIC per input rather than flaky ("give me a
                        // bloop" failed 4 of 4 at 1 and succeeded 4 of 4 at 3): the model spends a
                        // turn on prose before it reaches the structured-output call, and how often
                        // depends on the wish. Two is not enough -- the same input at 2 returns
                        // `subtype: success` with nothing in it, which is the worse failure shape.
                        //
                        // In the game that 9% is not an abstraction: with
                        // child_safety_unavailable=closed it would have been one wish in eleven
                        // answered "the genie is resting", and with `open` it is one wish in eleven
                        // reaching the agent ungraded. The extra turns are only paid when used
                        // (a 3-turn grade measured $0.0119 against a 2-turn $0.0070).
                        // tools/safety-arm.py carries the same number: an arm measuring a
                        // different invocation from the one that ships measures nothing.
                        "--max-turns", "3",
                        "--permission-mode", "dontAsk",
                        "--output-format", "json",
                        "--json-schema", jsonSchema));
                if (effort != null && !effort.isBlank()) {
                    // Part of the measured invocation. tools/safety-arm.py passes the same
                    // flag; an arm one flag apart from the shipped grader measures nothing.
                    cmd.add("--effort");
                    cmd.add(effort);
                }
                ProcessBuilder pb = new ProcessBuilder(cmd);
                if (!com.moddingfromamod.config.HelperRoute.claudeEnv(step, pb.environment())) {
                    return null;                 // a key step with no key: the route's next step
                }
                pb.redirectErrorStream(true);
                pb.redirectInput(new File("/dev/null"));
                // A CLEAN WORKING DIRECTORY. Started in the game's directory the CLI reads whatever
                // CLAUDE.md is lying around and invents a mission from it -- which for a grader
                // would mean a repo's own documentation silently editing the safety policy.
                Files.createDirectories(RuntimeConfig.home());
                pb.directory(RuntimeConfig.home().toFile());
                p = pb.start();

                // READ ON ANOTHER THREAD, and this is the one place this deliberately differs from
                // CharacterResolver and ReferenceResearcher. Both of those call readAllBytes() and
                // THEN waitFor(timeout) -- so a child that stops writing without exiting parks the
                // caller for ever and the timeout never gets a chance to run. Here the timeout is
                // the thing a wish depends on, so the wait is the outer operation.
                StringBuilder out = new StringBuilder();
                Process proc = p;
                Thread reader = new Thread(() -> {
                    try (var in = proc.getInputStream()) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        }
                    } catch (Exception ignored) {
                        // A closed pipe is how this ends normally.
                    }
                }, "mfam-safety-read");
                reader.setDaemon(true);
                reader.start();

                if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                    LOGGER.info("safety grade timed out after {}ms", timeoutMs);
                    return null;
                }
                reader.join(2_000);
                return parseEnvelope(out.toString());
            } catch (Exception e) {
                LOGGER.debug("safety grade could not run: {}", e.toString());
                return null;
            } finally {
                if (p != null && p.isAlive()) {
                    p.destroyForcibly();
                }
            }
        }
    }

    /**
     * Read the CLI's own JSON envelope.
     *
     * <p>{@code structured_output} is the typed field {@code --json-schema} produces and is the
     * only part that becomes a verdict. The other three are what §6 logs: the API time (not the
     * wall time, which includes CLI startup), the real cost, and the RESOLVED model id — because
     * "haiku" is an alias and an alias is not a measurement.
     */
    static Reply parseEnvelope(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int a = raw.indexOf('{');
            int b = raw.lastIndexOf('}');
            if (a < 0 || b <= a) {
                return null;
            }
            JsonElement el = JsonParser.parseString(raw.substring(a, b + 1));
            if (!el.isJsonObject()) {
                return null;
            }
            JsonObject o = el.getAsJsonObject();
            JsonElement so = o.get("structured_output");
            // No structured output at all is a real outcome -- an error envelope, a refusal, a
            // model that answered in prose -- and it is UNAVAILABLE rather than an exception.
            String structured = so != null && so.isJsonObject() ? so.toString() : null;
            // ⚠ THE THREE METRICS DEGRADE SEPARATELY FROM THE VERDICT, and that ordering is the
            // whole point. They are ANNOTATION -- §6 logs the API time, the cost and the resolved
            // model -- while `structured_output` is the only part that becomes a verdict. Read
            // inside the outer try, one malformed metric (a `duration_api_ms` the CLI wrote as a
            // string, a `total_cost_usd` of "n/a") threw and the whole envelope came back null,
            // which is UNAVAILABLE -- so on the shipped `open` default a real CLEARLY grade would
            // have PASSED the wish because a number nothing acts on was the wrong shape. That is
            // the same rule SafetyVerdict.parse states for its own fields ("nothing here throws,
            // and every field degrades on its own") applied one layer out.
            long apiMs = longOr(o, "duration_api_ms", 0L);
            double cost = doubleOr(o, "total_cost_usd", 0.0);
            String model = "";
            if (o.has("modelUsage") && o.get("modelUsage").isJsonObject()) {
                for (String k : o.getAsJsonObject("modelUsage").keySet()) {
                    model = k;
                    break;
                }
            }
            return new Reply(structured, apiMs, cost, model, raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A metric, or the default. Total: an unreadable number must not cost the verdict beside it. */
    private static long longOr(JsonObject o, String key, long fallback) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** @see #longOr */
    private static double doubleOr(JsonObject o, String key, double fallback) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // ---- test seams ----

    /**
     * Answer every grade with this function instead of running anything.
     *
     * <p>The seam §10's Gate B tests are written against: "a CLEARLY input never reaches
     * {@code Marshal.assign}" is a question about the wiring, and paying ten seconds and a live
     * model to ask it would make it a question about the model instead. Pass null to restore.
     */
    public static void forTest(Function<String, SafetyVerdict> fixed) {
        stub = fixed;
        clear();
    }

    /**
     * can a grade take real time here — a model behind it? False under the whole-path stub,
     * and for the shipped CLI transport with no CLI (every gate runs {@code cli=none}): there a grade
     * is an instant UNAVAILABLE, and the pickup and plan ratchets keep the waiting path, which costs
     * nothing. True with a model, or with a test transport (the ratchet's own GameTests delay one).
     */
    public static boolean gradesTakeTime() {
        if (stub != null) {
            return false;
        }
        return !(transport instanceof CliTransport) || com.moddingfromamod.agent.ClaudeCli.resolve() != null;
    }

    /**
     * may a grade reach a model? Only with Magician on — the shipped CLI transport starts a program. A test's
     * stub or transport starts none, so it is always allowed.
     */
    static boolean mayCallAModel() {
        return stub != null || !(transport instanceof CliTransport) || com.moddingfromamod.config.Magician.allowsAi();
    }

    /** Swap the transport itself — for testing the real parse path without a subprocess. */
    public static void transportForTest(Transport t) {
        transport = t == null ? new CliTransport() : t;
        clear();
    }

    /** Forget every memoised grade. Between wishes in a test, never in play. */
    public static void clear() {
        synchronized (GRADES_LOCK) {
            INPUT_GRADES.clear();
        }
    }

    /** How many grades are remembered right now. For the cap's own test. */
    static int memoSizeForTest() {
        synchronized (GRADES_LOCK) {
            return INPUT_GRADES.size();
        }
    }

    /**
     * Put a finished grade straight into the memo, as if it had been asked for.
     *
     * <p>For the cap's test alone: filling it through {@link #gradeInput} would start 257 real
     * grading threads, and what is under test is the EVICTION rule, not the transport.
     */
    static void rememberForTest(String text, CompletableFuture<Graded> f) {
        synchronized (GRADES_LOCK) {
            INPUT_GRADES.put(WishText.sanitize(text), f);
            trim();
        }
    }

    /**
     * Wait for every in-flight grade to finish, then forget them.
     *
     * <p>The {@code ReferenceResearcher.quiesceForTest} shape: a test is waiting for the background
     * writer to STOP, not for it to succeed, so a failed or timed-out grade satisfies this exactly
     * as well as a good one.
     */
    public static void quiesceForTest() {
        List<CompletableFuture<Graded>> inFlight;
        synchronized (GRADES_LOCK) {
            // A SNAPSHOT, waited on outside the lock: holding it for the whole budget would block
            // every other grade for as long as the slowest one takes.
            inFlight = new ArrayList<>(INPUT_GRADES.values());
        }
        for (CompletableFuture<Graded> f : inFlight) {
            try {
                f.get(ChildSafetyConfig.MAX_INPUT_BUDGET_MS, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // finished either way, which is all this needs to know
            }
        }
        clear();
    }
}
