package com.moddingfromamod.reference;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.moddingfromamod.agent.ClaudeCli;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The sandbox.</b> Looks up what a real character looks like, in a process that cannot touch
 * the game.
 *
 * <p>The wish agent must never get web access itself. It holds {@code author_datapack}, which has
 * no {@code CommandGuard} call site and therefore writes functions carrying the full vanilla
 * command set into a child's live world — attacker-controlled page text one hop from that is the
 * thing being prevented. So the lookup runs as a <b>separate CLI process with no MCP config at
 * all</b>: it has web search and page fetch, and literally zero wish tools. It cannot spawn a mob, run a
 * command, or write a datapack, because those tools do not exist in its world.
 *
 * <p>Its answer comes back as text and is immediately narrowed through {@link ReferenceSpec#parse},
 * which keeps only enums, bounded numbers and shape-checked URLs. Nothing the researcher writes is
 * ever handed to the wish agent verbatim.
 *
 * <p>Results are <b>cached on disk by character name</b>, which matters for three separate reasons:
 * repeat wishes are instant, evaluation runs are reproducible (an eval whose inputs change under it
 * measures nothing), and a child asking for Spikor twice does not cost two lookups.
 */
public final class ReferenceResearcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReferenceResearcher.class);

    /**
     * How long the researcher process may run in the BACKGROUND. Generous, because nobody is
     * waiting on it once prefetching is used.
     */
    private static final int TIMEOUT_SECONDS = 150;

    /**
     * <b>The longest a child ever waits for a reference.</b>
     *
     * <p>Measured, not guessed: five live lookups took 75, 90, 97, 109 and 109 seconds. That is not
     * a tail, it is the entire distribution — the fastest was 75s. A blocking lookup therefore adds
     * about a minute and a half to a wish while a seven-year-old watches a ticker, and no cap short
     * enough to fix that would ever succeed, since the minimum exceeds any tolerable wait.
     *
     * <p>Prefetching alone did not fix it: measured, the agent asks within SECONDS of the wish, not
     * minutes, so there was never enough overlap to hide the fetch behind. A 20s cap then produced
     * the worst of both — the child waited 20 seconds AND got no reference.
     *
     * <p>So the wait is now <b>zero</b>. A cached reference is returned; an absent one is reported
     * as absent, immediately, and the fetch continues into the cache. The reference is then spent on
     * <b>evaluating</b> the finished creature ({@link ReferenceVerdict}) and on <b>generating</b> the
     * NEXT wish, by which time it is warm. Nothing ever waits.
     */
    public static final int MAX_WAIT_SECONDS = 0;

    /** In-flight prefetches, so a later lookUp can wait on one instead of starting a second. */
    private static final java.util.Map<String, java.util.concurrent.CompletableFuture<ReferenceSpec>>
            IN_FLIGHT = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Where references live between sessions.
     *
     * <p>Beside the mod's other persistent state, NOT in {@code java.io.tmpdir} -- which on macOS
     * is a per-process {@code /var/folders/...} path, so a cache warmed by one process was
     * invisible to the game and every lookup re-fetched. Warming it before an eval, the whole point
     * of which is to remove a network confound, silently achieved nothing.
     *
     * <p>Overridable by {@code MFAM_REFERENCE_CACHE} for tests and for warming into a scratch dir.
     */
    private static Path cacheDir = resolveCacheDir();

    private static Path resolveCacheDir() {
        String env = System.getenv("MFAM_REFERENCE_CACHE");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        return Path.of(System.getProperty("user.home"), "." + com.moddingfromamod
                .ModdingFromAModMod.MODID, "reference-cache");
    }

    private ReferenceResearcher() {
    }

    /** Where an earlier issue's checked briefs are kept: beside the references they were written from. */
    static Path cacheDirForBriefs() {
        return cacheDir.resolve("briefs");
    }

    /**
     * Wait for an in-flight lookup to land in the cache, for OFFLINE callers only.
     *
     * <p>{@link #MAX_WAIT_SECONDS} is zero because a child must never sit through a fetch, and
     * {@link #lookUp} is right to return immediately. A batch warmer is the opposite case: nobody
     * is watching, and returning immediately means the process exits and kills the fetch. That is
     * not hypothetical — it is why warming seven creatures once wrote nothing at all.
     *
     * <p>Never call this from anything a player is waiting on.
     */
    public static ReferenceSpec awaitCached(String character, int seconds) {
        if (character == null || character.isBlank()) {
            return ReferenceSpec.none();
        }
        String key = key(character);
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ReferenceSpec cached = readCache(key);
            if (cached != null) {
                return cached;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ReferenceSpec.none();
    }

    /** Point the cache somewhere else (tests, and eval runs that want a clean slate). */
    public static void cacheDir(Path dir) {
        cacheDir = dir;
    }

    /**
     * <b>Stop writing into the last cache directory, and wait until nothing still is.</b>
     *
     * <p>For tests, and it fixes a real intermittent failure rather than tidying up. A lookup starts
     * a background prefetch and — because {@link #MAX_WAIT_SECONDS} is 0 — returns <i>immediately</i>,
     * leaving that prefetch creating directories and running a CLI process inside whatever
     * {@link #cacheDir} pointed at. When that is a JUnit {@code @TempDir}, the framework deletes it
     * the moment the test method returns, while the prefetch is still writing.
     *
     * <p>The result is {@code Failed to delete temp directory}, blamed on whichever test lost the
     * race — <b>not the test that started the prefetch</b>. That is why it looked like flakiness in
     * an unrelated assertion: one Gate A run failed here and passed on a straight re-run, with no
     * code between them.
     *
     * <p>Resets the cache to the real default first, so anything that starts up late lands somewhere
     * harmless, then drains what is already running.
     */
    static void quiesceForTest() {
        cacheDir = resolveCacheDir();
        for (java.util.concurrent.CompletableFuture<ReferenceSpec> f : IN_FLIGHT.values()) {
            try {
                f.get(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // A prefetch that failed or timed out is finished either way, which is all this
                // needs to know — it is waiting for the writer to STOP, not for it to succeed.
            }
        }
        IN_FLIGHT.clear();
    }

    /**
     * <b>Start looking a character up now, in the background.</b>
     *
     * <p>Call this the moment a wish mentioning a real character arrives. The agent then spends
     * minutes planning before it asks, and the answer is waiting when it does — turning a 90 second
     * stall into nothing. Safe to call repeatedly; a second call for the same character joins the
     * first rather than starting another.
     */
    public static void prefetch(String character) {
        prefetch(character, SubjectKind.CHARACTER);
    }

    /** With the {@link SubjectKind} the resolver gave, which decides where pictures come from. */
    public static void prefetch(String character, SubjectKind kind) {
        if (character == null || character.isBlank()
                || com.moddingfromamod.marshal.LocalOnly.refuse("the reference researcher (a web lookup)")) {   //: before the hand-off to another thread
            return;
        }
        String key = key(character);
        if (readCache(key) != null || IN_FLIGHT.containsKey(key)) {
            return;
        }
        if (route() == Route.NONE) {
            // smoke: with no researcher (Magician off, a Muse-only setup before it had one, no program), a lookup
            // "answered" NONE in milliseconds and that was cached as the character's answer, forever. Nothing started.
            return;
        }
        IN_FLIGHT.computeIfAbsent(key, k ->
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        ReferenceSpec spec = ask(character, kind);
                        if (spec == null) {
                            // No answer (timeout, error, junk): never cached, so the next wish asks again.
                            return ReferenceSpec.none();
                        }
                        writeCache(k, spec);
                        return spec;
                    } catch (RuntimeException e) {
                        return ReferenceSpec.none();
                    }
                }));
    }

    /**
     * Look up a character. Never throws: every failure path — no CLI, timeout, junk output,
     * hostile output — degrades to {@link ReferenceSpec#none()}, and the wish carries on without a
     * reference.
     *
     * <p><b>Bounded by {@link #MAX_WAIT_SECONDS}.</b> A cached answer is instant. An in-flight
     * prefetch is waited on, but only briefly. Anything else returns "no reference" immediately and
     * lets the fetch continue into the cache, so the child is never stalled and the NEXT wish — or
     * the revision of this one — has it.
     */
    public static ReferenceSpec lookUp(String character) {
        if (character == null || character.isBlank()) {
            return ReferenceSpec.none();
        }
        long started = System.nanoTime();
        return lookUp(character, MAX_WAIT_SECONDS);
    }

    /**
     * <b>The same lookup, but willing to wait — for a caller that is INSIDE a wish already.</b>
     *
     * <p>{@link #MAX_WAIT_SECONDS} is zero to protect the SUBMISSION path, where a child has just
     * typed a wish and is watching for any response at all. A tool call is a different situation:
     * the wish is already being granted, the progress narration is already running, and the agent
     * is the only thing waiting.
     *
     * <p>And the timing measurement says this is the whole ballgame. Over 25 real wishes the agent
     * calls {@code look_up_character} at a median of <b>9 s</b> and {@code sculpt_creature} at
     * <b>33–56 s</b>; a cold lookup takes <b>48 s</b>. Returning nothing at 9 s means the FIRST
     * sculpt — the one that decides what the creature is — is always built from memory, and the
     * reference can only ever be a grading step afterwards. Waiting here is what turns it into an
     * input.
     */
    public static ReferenceSpec lookUp(String character, int waitSeconds) {
        return lookUp(character, waitSeconds, SubjectKind.CHARACTER);
    }

    /** With the kind, for when this call is the one that starts the fetch. */
    public static ReferenceSpec lookUp(String character, int waitSeconds, SubjectKind kind) {
        long started = System.nanoTime();
        if (character == null || character.isBlank()
                || com.moddingfromamod.marshal.LocalOnly.refuse("the reference researcher (a web lookup)")) {   //: before the hand-off to another thread
            return ReferenceSpec.none();
        }
        String key = key(character);
        ReferenceSpec cached = readCache(key);
        if (cached != null) {
            LOGGER.info("reference cache hit for '{}'", character);
            // record the condition, including that it was a cache hit -- a cached reference
            // is reproducible, a live fetch is not, and an eval needs to know which it got.
            ReferenceUse.record(character, cached, true,
                    (System.nanoTime() - started) / 1_000_000);
            return cached;
        }
        // Not cached. Join an in-flight prefetch if there is one, otherwise start one -- but
        // either way wait only MAX_WAIT_SECONDS. A child must never sit through a 90 second fetch.
        prefetch(character, kind);
        var pending = IN_FLIGHT.get(key);
        try {
            ReferenceSpec spec = pending == null ? ReferenceSpec.none()
                    : pending.get(waitSeconds, TimeUnit.SECONDS);
            IN_FLIGHT.remove(key);
            long waited = (System.nanoTime() - started) / 1_000_000;
            LOGGER.info("reference for '{}' ready after {}ms of waiting", character, waited);
            ReferenceUse.record(character, spec, false, waited);
            return spec;
        } catch (java.util.concurrent.TimeoutException e) {
            LOGGER.info("reference for '{}' not ready within {}s — building without it; the fetch "
                    + "continues and will be cached for next time", character, waitSeconds);
            ReferenceUse.record(character, ReferenceSpec.none(), false,
                    (System.nanoTime() - started) / 1_000_000);
            return ReferenceSpec.none();
        } catch (Exception e) {
            IN_FLIGHT.remove(key);
            ReferenceUse.record(character, ReferenceSpec.none(), false);
            return ReferenceSpec.none();
        }
    }

    /**
     * The cached reference, or null. <b>Never fetches.</b>
     *
     * <p>For callers that want to enrich a wish only when the answer is already sitting there —
     * the whole point being that nothing waits.
     */
    public static ReferenceSpec cachedOnly(String character) {
        return character == null || character.isBlank() ? null : readCache(key(character));
    }

    /** The lookup's answer, or NULL when there was none to give (no researcher, timeout, error, junk): never cached. */
    private static ReferenceSpec ask(String character, SubjectKind kind) {
        String out = askRaw(character, kind);
        if (!ReferenceSpec.isAnswer(out)) {
            return null;
        }
        ReferenceSpec spec = measurePalette(ReferenceSpec.parse(out), character, kind);
        LOGGER.info("reference for '{}': {} colour(s), confidence {}", character,
                spec.colours().size(), spec.confidence());
        return spec;
    }

    /**
     * <b>A page the researcher just opened: look up its artwork and start the downloads now</b>
     *. Both are taken over by the final {@link #measure} if it asks for the same page and
     * the same pictures; otherwise they expire unused. Never blocks the caller.
     */
    private static void startEarly(String page, boolean creature, String character) {
        WikiArtwork.page(page, creature).thenAccept(arts -> {
            List<String> urls = new java.util.ArrayList<>();
            for (WikiArtwork.Art a : arts) {
                if (urls.size() >= ReferenceImages.MAX_IMAGES) {
                    break;
                }
                if (!urls.contains(a.url())) {
                    urls.add(a.url());
                }
            }
            ReferenceImages.warm(urls);
            // speed: the first cited page with pictures enough to describe hands them to the brief
            // now, so the describer runs while the researcher is still writing its answer.
            if (urls.size() >= 2) {
                earlyArtwork(character).complete(List.copyOf(urls));
            }
        });
    }

    /**
     * speed: the first cited page's picture links for this character, completed while its lookup is
     * still running (never completed on a cache hit, or when no page had at least two pictures). A caller
     * takes this BEFORE the lookup and stops waiting on it when the lookup ends.
     */
    public static java.util.concurrent.CompletableFuture<List<String>> earlyArtwork(String character) {
        return EARLY_ART.computeIfAbsent(key(character == null ? "" : character),
                k -> new java.util.concurrent.CompletableFuture<>());
    }

    /** Forget a character's early pictures once its lookup is over (a later lookup starts fresh). */
    public static void forgetEarlyArtwork(String character) {
        EARLY_ART.remove(key(character == null ? "" : character));
    }

    private static final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.CompletableFuture<List<String>>> EARLY_ART =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The researcher's raw reply, or null when there is no CLI, it timed out or it failed.
     *
     * <p>Split from {@link #ask} so a lookup can be FROZEN: {@link ReferenceBench} saves
     * this once per subject, and every later measurement replays only the deterministic image
     * pipeline over it.
     */
    static String askRaw(String character) {
        return askRaw(character, SubjectKind.CHARACTER);
    }

    /**
     * With the kind, which decides whether a page's photographs count when its artwork is looked
     * up EARLY -- the same {@code includeOpaque} the final {@link #artworkFor} will use, so the
     * early work is work the final step would have done.
     */
    static String askRaw(String character, SubjectKind kind) {
        if (!com.moddingfromamod.config.Magician.allowsAi()) {
            return null;
        }
        // The route (HelperRoute): the Claude CLI researcher and Ember at high, in the order, and paid the way, the
        // player chose (or the server's route, or today's: the Claude CLI). Never a provider the route does not name.
        return com.moddingfromamod.config.HelperRoute.first(steps(), museSteps -> askMuse(character, museSteps),
                step -> askClaude(character, kind, step));
    }

    private static java.util.List<com.moddingfromamod.config.HelperRoute.Step> steps() {
        return com.moddingfromamod.config.HelperRoute.steps(com.moddingfromamod.config.HelperRoute.Job.RESEARCHER, true);
    }

    /** Which program researches (smoke). */
    enum Route { CLAUDE, MUSE, NONE }

    /**
     * <b>Which researcher this setup has</b>, from its route ({@link com.moddingfromamod.config.HelperRoute}). Pure: the
     * first step whose program is here. Claude Code where the route names a Claude step (no Magician picks in charge —
     * a dedicated server, by default — or picks that name a Claude helper): the measured researcher, unchanged (moving
     * it to Ember is a maintainer's decision). Muse Code where it names a Muse step (a Muse-only setup): Ember at
     * high effort, which measured at ~10 s and ~$0.002 a lookup, agreeing with Opus on parts 0.89 and tails 81%
     * (corpus/researcher-arms/2026-11-19-2566/). Neither (Magician off, or no program the route names): no researcher,
     * and the wish goes on without a reference.
     */
    static Route route(boolean aiAllowed, java.util.List<com.moddingfromamod.config.HelperRoute.Step> steps,
                       boolean claudeCli, boolean museCli) {
        if (!aiAllowed) {
            return Route.NONE;
        }
        for (var st : steps) {
            if (st.muse() ? museCli : claudeCli) {
                return st.muse() ? Route.MUSE : Route.CLAUDE;
            }
        }
        return Route.NONE;
    }

    static Route route() {
        boolean ai = com.moddingfromamod.config.Magician.allowsAi();
        var steps = steps();
        return route(ai, steps, ai && com.moddingfromamod.config.HelperRoute.hasClaude(steps) && ClaudeCli.resolve() != null,
                ai && !com.moddingfromamod.config.HelperRoute.muse(steps).isEmpty()
                        && com.moddingfromamod.agent.MuseExec.binary() != null);
    }

    /** The Muse researcher's model and effort (an earlier issue's ember-high arm). */
    static final String MUSE_MODEL = "muse-ember-1.0";
    static final String MUSE_EFFORT = "high";

    /** Its whole budget, every step of the chain together: ~10 s measured, p90 17 s; the Claude one gets 150. */
    static final long MUSE_TIMEOUT_MS = 120_000L;

    /**
     * Muse Code's own system prompt replaced by a plain one (as an earlier issue's arm ran it): the brief itself is the user
     * message, the same {@link #prompt} the Claude researcher reads.
     */
    static final String MUSE_SYSTEM = "You are a careful web researcher. Use your web search and web fetch tools as "
            + "the user asks, then reply exactly in the form the user asks for.";

    /**
     * The same lookup on Muse Code (smoke): the same prompt and the same boundary — web search and web fetch,
     * no other tool, no wish tool ({@code MuseGrade.RESEARCH_FLAGS}) — and the reply is parsed into a
     * {@link ReferenceSpec} exactly as the Claude one's is. No pages arrive early here, so the describer starts after.
     */
    private static String askMuse(String character, java.util.List<com.moddingfromamod.config.HelperRoute.Step> steps) {
        long started = System.nanoTime();
        var a = com.moddingfromamod.agent.MuseExec.research(MUSE_SYSTEM, prompt(character), MUSE_MODEL, MUSE_EFFORT,
                MUSE_TIMEOUT_MS, steps);
        long ms = (System.nanoTime() - started) / 1_000_000;
        if (a == null) {
            LOGGER.warn("reference lookup for '{}' on Muse Code gave no answer after {} ms", character, ms);
            return null;
        }
        LOGGER.info("reference researcher for '{}' answered in {} ms (Muse Code, {} {}, arm {}, ${})", character, ms,
                MUSE_MODEL, MUSE_EFFORT, a.arm(), String.format(Locale.ROOT, "%.4f", a.costUsd()));
        return a.text();
    }

    private static String askClaude(String character, SubjectKind kind,
                                    com.moddingfromamod.config.HelperRoute.Step step) {
        boolean creature = kind == SubjectKind.CREATURE;
        String cli = ClaudeCli.resolve();
        if (cli == null || cli.isBlank()) {
            return null;
        }
        Process p = null;
        try {
            // -p (print) so the answer arrives on stdout and the process exits.
            //
            // NOTE what is ABSENT: no --mcp-config, so this process has no wish tools whatsoever,
            // and --tools narrows the BUILT-IN set to the two web tools (see below -- this is the
            // real boundary; --allowed-tools alone was not one).
            ProcessBuilder pb = new ProcessBuilder(List.of(
                    cli, "-p", prompt(character),
                    // --tools, NOT just --allowed-tools. They are different things and the
                    // difference was a hole: --allowed-tools is a PERMISSION list, so all 38
                    // built-in tools were still PRESENT and --permission-mode dontAsk approved
                    // them. Measured in real traces -- this process ran Bash ("echo done", and
                    // once a curl at a page WebFetch had refused) and called ReadNotifications,
                    // reading the PARENT session's queue. Attacker-controlled page text is one
                    // hop from here; it must not also have Bash, Write, Edit, Task and Workflow.
                    //
                    // It is also the single cheapest speed-up available, for the same reason:
                    // 38 tool schemas plus 55 slash commands is ~40,000 input tokens re-read
                    // EVERY turn (312,580 cache reads across one lookup), and the tools are
                    // deferred, so a whole turn was being spent on ToolSearch loading the two
                    // we had already named. Measured: 304,871 -> 80,182 input tokens, -74%.
                    "--tools", "WebSearch,WebFetch",
                    "--allowed-tools", "WebSearch,WebFetch",
                    "--strict-mcp-config",
                    // MEDIUM, chosen by measurement rather than by taste. At the default the
                    // model spent 1,469-2,721 thinking tokens per lookup; at medium it is ~419
                    // and the answer is unchanged (beak and wing 2/2 on the hard case). At LOW
                    // it is 2.7x faster still and starts losing the character: `wing` half the
                    // time, and with a trimmed brief one run returned unparseable prose and one
                    // returned confidence NONE. Speed that loses the character is not speed.
                    "--effort", "medium",
                    // STREAMED: the pages it opens arrive as it opens them, so their
                    // artwork is looked up and downloaded while it is still writing, and the
                    // answer is taken the moment it is written rather than when the process
                    // exits ~3 s later. ResearcherStream keeps a plain-text reply working.
                    "--output-format", "stream-json", "--verbose",
                    "--permission-mode", "dontAsk"));
            if (!com.moddingfromamod.config.HelperRoute.claudeEnv(step, pb.environment())) {
                return null;                     // a key step with no key: the route's next step
            }
            pb.redirectErrorStream(true);
            pb.redirectInput(new File("/dev/null"));
            // A clean cwd: started in the game's directory the CLI reads whatever CLAUDE.md is
            // lying around and invents a mission from it.
            Files.createDirectories(cacheDir);
            pb.directory(cacheDir.toFile());
            long started = System.nanoTime();
            p = pb.start();

            long[] firstPageMs = {-1};
            ResearcherStream stream = new ResearcherStream(page -> {
                if (firstPageMs[0] < 0) {
                    firstPageMs[0] = (System.nanoTime() - started) / 1_000_000;
                }
                startEarly(page, creature, character);
            });
            try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(
                    p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (stream.accept(line)) {
                        break;
                    }
                }
            }
            long ms = (System.nanoTime() - started) / 1_000_000;
            if (stream.answeredEarly()) {
                // The reply is complete; what is left is the CLI's own bookkeeping.
                p.destroy();
            } else if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                LOGGER.warn("reference lookup for '{}' timed out", character);
                return null;
            }
            // the researcher's own time was only ever recoverable by subtraction.
            LOGGER.info("reference researcher for '{}' answered in {} ms ({}); {} page(s) started "
                    + "early, the first at {} ms", character, ms,
                    stream.answeredEarly() ? "taken from the stream" : "at exit",
                    stream.opened().size(), firstPageMs[0]);
            return stream.answer();
        } catch (Exception e) {
            LOGGER.warn("reference lookup for '{}' failed: {}", character, e.toString());
            return null;
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    /**
     * <b>Replace the model's guess at the palette with a count of the real artwork's pixels.</b>
     *
     * <p>This is the one reference field a page cannot supply. Prose says "green shell, tan skin"
     * and never says which you mostly see — and the measured cost of guessing it was Spikor at
     * green 40% / tan 35% when six official renders say tan 40% and green is outside the top four.
     * The creature built to the guess, and every gate stayed green.
     *
     * <p>Runs on the researcher's own background thread, after the CLI process has exited, so
     * nothing a child could be waiting on is on this path. Measured: 0.38s to download and 0.19s
     * to count, against the 75–109s the lookup already took.
     *
     * <p>Silently keeps the model's colours when there is not enough artwork. That is not a
     * fallback hiding a failure — {@link ReferencePalette} refuses to answer from fewer than three
     * usable images precisely because one render of a character in a kart is a picture of a kart,
     * and a confidently wrong measured palette is worse than an approximate remembered one.
     */
    /**
     * <b>Where the artwork comes from — Java, not the model.</b>
     *
     * <p>The researcher used to be asked for image URLs, and a third of its web calls (10 of 32,
     * measured across three traced runs) went on finding them: {@code "png transparent"} searches
     * and {@code /Gallery} fetches, 13.5 seconds of one run. {@link WikiArtwork} asks the wiki's
     * own MediaWiki API instead and returned <b>6 Spikor images in 1.0 s</b>, deterministically,
     * with no model in the loop.
     *
     * <p>Reliability was the bigger win, not the seconds. The model returned <b>zero</b> images
     * for every Omega Lad run — its {@code WebFetch} of the page is refused, while the same
     * host's API answers fine — and once returned a single image for Spikor, which is under
     * {@link ReferencePalette#MIN_IMAGES} and so silently produced no measurement at all.
     *
     * <p>Anything the model DID name is still honoured and goes first: an old cache entry has
     * urls in it, and a page the API cannot enumerate might still have been read by the model.
     * The two lists are merged rather than one replacing the other.
     */
    static List<String> artworkFor(ReferenceSpec spec) {
        return artworkFor(spec, SubjectKind.CHARACTER);
    }

    /** The distinct hosts of some URLs, in order — what a log line can say about its sources. */
    static List<String> hosts(List<String> urls) {
        List<String> out = new java.util.ArrayList<>();
        for (String u : urls == null ? List.<String>of() : urls) {
            try {
                String h = java.net.URI.create(u).getHost();
                if (h != null && !out.contains(h)) {
                    out.add(h);
                }
            } catch (RuntimeException ignored) {
                // not a URL; nothing to name
            }
        }
        return List.copyOf(out);
    }

    /** For a {@link SubjectKind#CREATURE}, the pages' photographs are candidates too. */
    static List<String> artworkFor(ReferenceSpec spec, SubjectKind kind) {
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>(spec.images());
        if (urls.size() < ReferenceImages.MAX_IMAGES) {
            long t0 = System.nanoTime();
            List<String> found = WikiArtwork.urlsFrom(spec.sources(), ReferenceImages.MAX_IMAGES,
                    kind == SubjectKind.CREATURE);
            urls.addAll(found);
            // THE HOSTS, not just a count: "4 source page(s): 0 url(s)" said nothing about
            // WHICH sites the researcher cites, so nobody could tell a broken API path from a
            // researcher citing shops and meme blogs.
            LOGGER.info("artwork resolved from {} source page(s) {}: {} url(s) from the wiki API "
                    + "in {} ms, {} named by the researcher, {} total", spec.sources().size(),
                    hosts(spec.sources()), found.size(), (System.nanoTime() - t0) / 1_000_000,
                    spec.images().size(), urls.size());
        }
        return urls.stream().limit(ReferenceImages.MAX_IMAGES).toList();
    }

    /**
     * <b>Everything the image pipeline did for one spec, as numbers.</b> One definition, so the
     * live path and {@link ReferenceBench} cannot drift: the bench measures exactly the
     * code a wish runs.
     *
     * @param urls       what the pipeline resolved to try (named by the researcher + the wiki API)
     * @param downloaded how many of those came back as decodable images
     * @param usable     how many of those {@link ReferencePalette#usable} accepted
     * @param reading    the combined reading (empty when too few were usable)
     */
    record Measurement(List<String> urls, int downloaded, int usable,
                       ReferencePalette.Reading reading, long resolveMs, long fetchMs) {
        static Measurement nothing(List<String> urls, long resolveMs) {
            return new Measurement(urls, 0, 0, ReferencePalette.empty(), resolveMs, 0);
        }
    }

    /** The deterministic half of a lookup: resolve, download, count. Never throws. */
    static Measurement measure(ReferenceSpec spec) {
        return measure(spec, null, SubjectKind.CHARACTER);
    }

    /**
     * With the subject's name, which is what lets a subject with no franchise wiki be measured at
     * all: when the cited pages yield fewer than {@link ReferencePalette#MIN_IMAGES}
     * USABLE pictures, {@link CommonsCutouts} is searched by name and those are measured
     * alongside. Gated on usable, not on found: a real animal's Wikipedia page offers plenty of
     * PNGs, every one an opaque diagram or photo. Only for a {@link SubjectKind#CREATURE}, and so
     * is masking an opaque picture's backdrop: a character's photos are of merchandise.
     */
    /**
     * Round-2 rules, each measured on the 36-subject corpus before it became one. Not
     * final only so {@link ReferenceBench} can switch them off as the control; nothing else
     * writes them. Measured (correct / confidently wrong): baseline 9/1; the JPEG fallback
     * gated on an EMPTY reading 11/1 (gated on "few renders" it was 10/2); plus creature trust
     * above 2/3 13/1 (at exactly 2/3 14/2, at 1/2 15/3: each extra right came with a
     * confident wrong).
     * {@code characterOpaqueFallback}: a character with fewer than three usable renders also
     * measures its pages' JPEGs, masked. {@code creatureLeaderShare}: a creature's reading is
     * trusted when its leading colour survives at least this share of the leave-one-out
     * subsets (1.0 = every one, the rule for everything else).
     */
    static volatile boolean characterOpaqueFallback = true;
    // 0.67, NOT 2.0/3.0, and the difference was measured: at exactly two thirds a THREE-render
    // reading whose leader one drop can flip is trusted, which added Axolotl "white" (wrong)
    // beside Octopus (right). Above two thirds needs 3 of 3, 3 of 4, 4 of 5, 5 of 6.
    static volatile double creatureLeaderShare = 0.67;

    static Measurement measure(ReferenceSpec spec, String subject, SubjectKind kind) {
        boolean creature = kind == SubjectKind.CREATURE && subject != null && !subject.isBlank();
        // STARTED NOW, used only if needed (step 5): the Commons search is asked IN
        // PARALLEL with the cited pages, so needing it adds no serial round trip. (A second
        // source was measured too -- the subject's Commons GALLERY page -- and left out: same
        // correct palettes, more requests. Its photos are of animals in the wild, which the
        // backdrop mask rightly refuses.)
        java.util.concurrent.Future<List<String>> commons = creature
                ? WikiArtwork.POOL.submit(() -> CommonsCutouts.urlsFor(subject,
                        ReferenceImages.MAX_IMAGES))
                : null;
        long t0 = System.nanoTime();
        List<String> urls = spec == null ? List.of() : artworkFor(spec, kind);
        long resolveMs = (System.nanoTime() - t0) / 1_000_000;
        try {
            long t1 = System.nanoTime();
            List<java.util.Map<String, Double>> per = new java.util.ArrayList<>();
            List<java.util.Map<ReferenceSpec.Region, java.util.Map<String, Double>>> bands =
                    new java.util.ArrayList<>();
            int[] counts = new int[2];      // downloaded, usable
            count(ReferenceImages.fetch(urls), per, bands, counts, creature);
            // ALLOW A SOLE RENDER ONLY WHEN THE WIKI HAD NO MORE TO OFFER. urls is what the
            // image API enumerated, so fewer than MIN_IMAGES means the character really has one
            // or two drawings in existence -- not that five downloads failed, which stays a
            // refusal. Measured: Omega Lad's page lists exactly one image, Spikor's lists 29.
            int offered = urls.size();
            List<String> all = urls;
            if (commons != null && counts[1] < ReferencePalette.MIN_IMAGES) {
                int fromPages = counts[1];
                List<String> cut;
                try {
                    cut = new java.util.ArrayList<>(commons.get(
                            WikiArtwork.PAGES_DEADLINE.toMillis(), TimeUnit.MILLISECONDS));
                } catch (Exception e) {
                    commons.cancel(true);
                    cut = new java.util.ArrayList<>();
                }
                cut.removeAll(urls);
                count(ReferenceImages.fetch(cut), per, bands, counts, true);
                offered += cut.size();
                all = new java.util.ArrayList<>(urls);
                all.addAll(cut);
                LOGGER.info("{} usable picture(s) on the cited pages, so Commons was searched for "
                        + "'{}': {} candidate(s)", fromPages, subject, cut.size());
            } else if (commons != null) {
                commons.cancel(true);
            }
            if (all.isEmpty() && !(characterOpaqueFallback && !creature && spec != null)) {
                return Measurement.nothing(all, resolveMs);
            }
            ReferencePalette.Reading r = ReferencePalette.across(per, bands,
                    offered < ReferencePalette.MIN_IMAGES);
            if (!creature && characterOpaqueFallback && spec != null && r.isEmpty()) {
                // A character whose cut-out renders gave NO reading at all -- not even the sole-
                // render one -- tries its pages' JPEGs, masked. Gated on an empty reading rather
                // than on few renders: measured, gating on few renders turned SoapyStan's
                // correct two-render reading into a wrong grey one.
                List<String> opaque = new java.util.ArrayList<>(WikiArtwork.urlsFrom(
                        spec.sources(), ReferenceImages.MAX_IMAGES * 2, true));
                opaque.removeAll(all);
                count(ReferenceImages.fetch(opaque), per, bands, counts, true);
                offered += opaque.size();
                all = new java.util.ArrayList<>(all);
                all.addAll(opaque);
                if (all.isEmpty()) {
                    return Measurement.nothing(all, resolveMs);
                }
                r = ReferencePalette.across(per, bands, offered < ReferencePalette.MIN_IMAGES);
            }
            if (creature && creatureLeaderShare < 1.0 && !r.isEmpty() && !r.trustworthy()
                    && ReferencePalette.leaderSurvival(per) >= creatureLeaderShare) {
                r = ReferencePalette.majorityTrusted(r);
            }
            return new Measurement(List.copyOf(all), counts[0], counts[1], r, resolveMs,
                    (System.nanoTime() - t1) / 1_000_000);
        } catch (Exception e) {
            // A wish must never break because a wiki moved a file.
            LOGGER.debug("palette measurement failed: {}", e.toString());
            return Measurement.nothing(urls, resolveMs);
        }
    }

    private static void count(List<ReferenceImages.Fetched> fetched,
                              List<java.util.Map<String, Double>> per,
                              List<java.util.Map<ReferenceSpec.Region,
                                      java.util.Map<String, Double>>> bands, int[] counts,
                              boolean maskBackdrop) {
        for (var f : fetched) {
            // A render's own alpha is its mask; a CREATURE's photo or painting gets one from its
            // border (step 3), or null when it has no backdrop to remove -- and null is
            // unusable.
            var img = maskBackdrop ? BackgroundMask.apply(f.image()) : f.image();
            var one = ReferencePalette.of(img);
            per.add(one);
            bands.add(ReferencePalette.byRegion(img));
            counts[0]++;
            counts[1] += ReferencePalette.usable(one) ? 1 : 0;
        }
    }

    static ReferenceSpec measurePalette(ReferenceSpec spec) {
        return measurePalette(spec, null, SubjectKind.CHARACTER);
    }

    static ReferenceSpec measurePalette(ReferenceSpec spec, String subject, SubjectKind kind) {
        if (spec == null) {
            return spec;
        }
        Measurement m = measure(spec, subject, kind);
        if (m.urls().isEmpty()) {
            return spec;
        }
        ReferencePalette.Reading r = m.reading();
        long ms = m.fetchMs();
        // AN UNSTABLE MEASUREMENT MUST NOT REPLACE THE MODEL'S COLOURS. The entire warrant
        // for overriding a remembered palette with a counted one is that the count is more
        // reliable; when the count says it is NOT reliable, that warrant is gone and what is
        // left is a confident wrong number, which is strictly worse than a vague right one.
        //
        // Measured, over six held-out characters: every reading that reported topStable was
        // correct (3 of 3) and every reading that reported unstable was wrong (0 of 2).
        // Barrel Ape came back "red 9%" -- his page's biggest images are a movie poster and
        // a group shot -- against a model that would simply have said brown.
        // trustworthy(), NOT topStable(): a SOLE render reports topStable=false because
        // leave-one-out is undefined at n=1, which is a different statement from "the renders
        // disagree". Reading the raw flag here made the whole sole-artwork path dead on
        // arrival -- it measured the image, then discarded the measurement one line later.
        if (!r.isEmpty() && !r.trustworthy()) {
            LOGGER.info("palette NOT trusted: {} render(s) disagree on the leading colour "
                    + "(swing {}pp) — keeping the model's colours", r.images(),
                    Math.round(r.topSwingPct()));
            return spec;
        }
        if (r.isEmpty()) {
            LOGGER.info("palette NOT measured: {} url(s) resolved, {} downloaded, {} usable "
                    + "— keeping the model's colours ({} ms)",
                    m.urls().size(), m.downloaded(), r.images(), ms);
            return spec;
        }
        if (r.sole()) {
            LOGGER.info("palette MEASURED from the character's ONLY render in {} ms: {} — "
                    + "bands {} — confidence capped at LOW, because one drawing cannot say "
                    + "whether a second would agree", ms, r.colours(), r.regions());
        } else {
            LOGGER.info("palette MEASURED from {} render(s) in {} ms: {} — bands {} — "
                    + "leave-one-out swing {}pp, top colour stable={}", r.images(), ms,
                    r.colours(), r.regions(), Math.round(r.topSwingPct()), r.topStable());
        }
        return spec.withMeasuredColours(r.colours(), r.confidenceCeiling(), r.regions());
    }

    /**
     * The researcher's brief.
     *
     * <p>Carries the <b>trust ranking</b> — deliberately a ranking and not an allowlist. The
     * creatures a child asks for range from video-game characters to Italian-brainrot memes whose
     * only sources are meme wikis and short-form video; an allowlist would simply refuse half the
     * corpus. Ranking says "prefer the owner, cross-check harder the further down you go", which
     * degrades gracefully instead.
     */
    /**
     * The character brief serves CREATURES too, and that was measured (round 2). A
     * creature-specific brief ("the real animal, never the cartoon of that name") was frozen for
     * all 18 creatures and found the right subjects -- Wikipedia's tiger instead of Tom and
     * Jerry's -- and measured WORSE: 7 correct and 3 confidently wrong against 9 and 1. A real
     * animal's pictures are photographs and paintings, which read poorly; and a child's idea of
     * a hydra is the green cartoon one, not a bronze vase painting. The brief is in git history
     * (the commit before this one) and its freeze in corpus/reference-lookup/frozen-creature/.
     */
    private static String prompt(String character) {
        return characterOpening(character) + "\n" + common();
    }

    private static String characterOpening(String character) {
        return String.join("\n",
                "Find out what this character looks like: " + character,
                "",
                "FIRST WORK OUT WHICH THING THIS IS, THEN RANK SOURCES WITHIN IT. A name is often",
                "shared by a character and by a song, album, game, film or trend named after it.",
                "A child asked for this so it could be BUILT, so the answer is always the one with",
                "a body: the character. A page about the song is not a worse source for the",
                "character, it is a source for something else, and it must not be used.",
                "",
                "The measured failure this exists to stop: asked for a meme character named after a",
                "song, a previous",
                "researcher answered from the song's Wikipedia article and returned head, body,",
                "arm, leg, eye, hair in black, tan and grey — a generic human. The character is a",
                "penguin with a salmon-sushi torso and sashimi hair, and it has its own page on",
                "the same brainrot wiki that answers the rest of that corpus correctly. Nothing",
                "was low-quality about the source. It was about the wrong subject.",
                "",
                "So SEARCH FOR THE CHARACTER, not the name: add 'character', 'appearance' or the",
                "property it belongs to, and if the first page you land on describes a song, a",
                "chart position or a release date, you are on the wrong page — go back.",
                "",
                "Having fixed the subject, prefer better sources, in this order, and cross-check",
                "harder the further down you go (two independent sources agreeing beats one",
                "confident page):",
                "  1. the owner — the company's own site",
                "  2. a dedicated wiki with editorial standards, or Wikipedia",
                "  3. a general fandom wiki for the property",
                "  4. anything else — meme aggregators, video stills, fan art",
                "",
                "A LOW-RANKED PAGE ABOUT THE RIGHT CHARACTER BEATS A HIGH-RANKED PAGE ABOUT",
                "SOMETHING ELSE. Meme and brainrot characters live at ranks 3-4 by nature; that is",
                "where they are documented, so going there is correct, not a compromise.",
                "");
    }

    private static String common() {
        return String.join("\n",
                "A SEARCH RESULT IS EVIDENCE. If the pages themselves will not open -- 403, a",
                "refused connection, a network policy -- do NOT report that you found nothing.",
                "What the search results consistently SAY is real evidence: report it, cite the",
                "URLs you saw, and set confidence MEDIUM rather than HIGH because you could not",
                "read the page itself. STILL CITE THE PAGE YOU COULD NOT OPEN: a page your fetch",
                "was refused is very often one the artwork resolver can still read, because it",
                "asks the wiki\'s API rather than its HTML. Measured -- one meme character\'s wiki answers",
                "403 to a page fetch and 200 to its own API, and the artwork was there all along.",
                "",
                "This is a measured failure, not caution. Asked for that meme character from a machine whose",
                "network policy blocks every brainrot wiki, a researcher returned nothing at all --",
                "while the very same search, on the very same machine, was returning \"a penguin",
                "with a salmon-sushi torso and sashimi hair\" in its result snippets. It had the",
                "answer in hand and discarded it for not having come off a page it could open.",
                "",
                "Some characters really have NO canonical design, and only exist as other people's",
                "drawings. If so, report the CONSENSUS across what you find and set confidence low",
                "or none. Saying 'there is no official look' is a correct, useful answer — do not",
                "invent one to seem helpful.",
                "",
                "BUT DO NOT PAD A SCHEMA YOU CANNOT FILL. If you did not find the character,",
                "return EMPTY colours and parts with confidence NONE. Listing head, body, arm,",
                "leg, eye, hair is not a cautious answer, it is a description of nobody, and it",
                "builds WORSE than saying nothing: it was measured making a creature worse than",
                "the same wish with no reference at all.",
                "",
                "Reply with ONLY this JSON object and nothing else:",
                "{",
                "  \"colours\": [{\"colour\": <one of: " + String.join(", ", new java.util.TreeSet<>(ReferenceSpec.COLOURS))
                        + ">, \"percent\": <0-100 of the visible body>}],",
                "  \"parts\": [<any of: " + String.join(", ", new java.util.TreeSet<>(
                        ReferenceSpec.PARTS)) + ">, or {\"part\": <one of those>, \"count\": <n>} "
                        + "for a part it has an UNUSUAL number of -- three heads, eight "
                        + "tentacles>],",
                "  \"size\": <SMALL|MEDIUM|LARGE|HUGE>,",
                "  \"expression\": <HAPPY|ANGRY|SAD|SURPRISED|SLEEPY|FIERCE|NEUTRAL|UNKNOWN>,",
                "  \"stance\": <UPRIGHT_BIPED|QUADRUPED|NO_LEGS|FLYING|UNKNOWN>,",
                "  \"tail\": <NONE|SHORT|LONG|UNKNOWN>,",
                "  \"confidence\": <NONE|LOW|MEDIUM|HIGH>,",
                "  \"sources\": [<the URLs you actually used>],",
                "  \"colourSource\": <SOURCED if you READ the colours off a page or a search",
                "                    result, RECALLED if you are going from memory>",
                "}",
                "",
                "NAME THE PARTS THAT MAKE THIS CHARACTER RECOGNISABLE, and be specific with the",
                "part words: a shark has a fin, a snout and teeth, and saying so is what stops the",
                "builder falling back on a generic body. The builder never sees a picture, so a",
                "part you leave out is a part it will not know to build.",
                "",
                "BE HONEST IN \"colourSource\". SOURCED means you read the colours somewhere just",
                "now; RECALLED means you are remembering them. It changes how much the builder",
                "leans on them, and a remembered palette dressed up as a sourced one is the exact",
                "failure this whole lookup exists to stop.",
                "",
                "DO NOT GO LOOKING FOR PICTURES, and do NOT judge the colour proportions",
                "yourself. Something downstream reads the wiki\'s own image API and counts the",
                "actual pixels, which is more accurate than either of us, takes about a second",
                "and costs nothing. Measured: a third of a previous researcher\'s web calls went",
                "on hunting artwork it was worse at finding than one API call.",
                "",
                "WHAT THAT MAKES VALUABLE INSTEAD IS \"sources\": list every page you actually",
                "read, in full. Those URLs are what the artwork is resolved FROM, so a page you",
                "used and did not cite is a picture nobody can count.",
                "",
                "SO YOU MUST ACTUALLY LOOK. Search, and open at least one page, EVEN IF YOU ARE",
                "SURE YOU KNOW THIS CHARACTER -- especially then. Returning \"sources\": [] is not",
                "a fast answer, it is a different and worse answer: it is your memory, and the",
                "whole reason you were asked is that memory gets the proportions wrong. Measured:",
                "for a famous game villain, remembered colours are green 40% / tan 25%, and",
                "counting the pixels of his own official artwork gives tan 40% and green outside the top four -- most",
                "of what you see from the front is skin, because the shell is behind him.",
                "",
                "An answer with no sources therefore MUST say colourSource RECALLED and MUST set",
                "confidence no higher than LOW, because nothing was checked. If that feels wrong",
                "for a character you know well, the fix is to open the page, not to raise the",
                "confidence.",
                "",
                "SAY HOW IT STANDS, AS DRAWN IN ITS OWN SHOW OR GAME, NOT AS ITS ANIMAL DOES. Many",
                "comic-strip cats and cartoon dogs stand and walk upright on two legs like a",
                "person: that is UPRIGHT_BIPED. A character that stands on four legs is QUADRUPED;",
                "one with no legs (a ghost, a blob, a round arcade chomper) is NO_LEGS; one that is mostly seen",
                "in the air is FLYING. And say its TAIL as drawn: NONE, SHORT (a rabbit's puff, a",
                "stub), or LONG (a cat's, a dragon's). A builder told only \"a cat\" builds a",
                "four-legged cat with a long tail, which is the wrong character.",
                "",
                                "EXPRESSION IS PART OF WHO A MASCOT IS. A fast-food clown mascot is cheerful; a",
                "game's villain scowls. Say which, because the builder never sees a picture and a clown built",

                "with a straight line for a mouth reads as menacing. Answer UNKNOWN when the",
                "character has no consistent expression -- that is a real answer, not a gap.",
                "",
                "Use ONLY those exact colour and part words — anything else is discarded. Colour",
                "percentages should reflect how much of the character you SEE in that colour, so a",
                "character with a big coloured shell and lighter skin is roughly half and half, not",
                "mostly one of them.");
    }

    // ── cache ────────────────────────────────────────────────────────────────────────────────

    private static String key(String character) {
        return character.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }

    private static ReferenceSpec readCache(String key) {
        try {
            Path f = cacheDir.resolve(key + ".json");
            if (!Files.isRegularFile(f)) {
                f = byPrefix(key);
            }
            if (f == null || !Files.isRegularFile(f)) {
                return null;
            }
            String raw = Files.readString(f);
            ReferenceSpec spec = ReferenceSpec.parse(raw);
            // A cached "nothing" is still a real answer and is honoured, so a character with no
            // canonical design is not looked up again on every single wish -- but only for NONE_LIFETIME_MS,
            // and only when it says a lookup really happened (smoke: Tabbycat and Scruff-Pup were cached
            // NONE, for ever, by a setup that had no researcher to ask).
            if (spec.isEmpty() && !negativeStillGood(lookedUpAt(raw), System.currentTimeMillis())) {
                return null;
            }
            return spec;
        } catch (Exception e) {
            return null;
        }
    }

    /** How long a genuine "nothing found" is believed before it is asked again. */
    static final long NONE_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000;

    /** The cache field that says when a real lookup wrote this entry. */
    static final String LOOKED_UP_AT = "lookedUpAt";

    /**
     * Is a cached negative answer still to be believed? Only when a real lookup wrote it ({@code lookedUpAt}, which
     * every entry 's smoke carries) and less than {@link #NONE_LIFETIME_MS} ago. An older entry, or one
     * with no provenance — the negatives written when no researcher could run — reads as absent. Pure.
     */
    static boolean negativeStillGood(long lookedUpAt, long now) {
        return lookedUpAt > 0 && now - lookedUpAt < NONE_LIFETIME_MS && lookedUpAt <= now + 60_000L;
    }

    private static long lookedUpAt(String raw) {
        try {
            var o = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
            return o.has(LOOKED_UP_AT) && o.get(LOOKED_UP_AT).isJsonPrimitive() ? o.get(LOOKED_UP_AT).getAsLong() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /** For the cache round-trip test — the write half is otherwise unreachable without a model. */
    static void writeCacheForTest(String key, ReferenceSpec spec) {
        writeCache(key, spec);
    }

    /** For the test that the brief actually asks for the fields it parses. */
    static String promptForTest(String character) {
        return prompt(character);
    }

    /**
     * <b>The warm cache and the live lookup have to agree on a key, and they did not.</b>
     *
     * <p>The eval warms references from the corpus's {@code reference} DESCRIPTION — "Spikor (Super
     * Plumo), modern/Voyage-era" — while the agent asks for the character by NAME: "SPIKOR".
     * Different slugs, so every lookup missed. With {@link #MAX_WAIT_SECONDS} at zero that returns
     * nothing immediately and the fetch lands afterwards, so a measured run built Spikor with <b>no
     * reference at all</b> — no sourced palette, no named parts, no expression — and the log
     * line saying so scrolled past looking like ordinary background chatter.
     *
     * <p>So a miss falls back to a PREFIX match over what is already cached: {@code spikor} finds
     * {@code spikor_super_plumo_modern_voyage_era}. The shortest match wins, because it is the
     * least specific and therefore the least likely to be a different character who merely starts
     * the same way. An ambiguous prefix is still better than nothing here: the alternative is a
     * creature built from recall, which is what "Spikor is mostly tan" was.
     */
    private static Path byPrefix(String key) {
        if (key == null || key.length() < 3) {
            return null;
        }
        try (java.util.stream.Stream<Path> files = Files.list(cacheDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.substring(0, name.length() - 5).startsWith(key + "_");
                    })
                    .min(java.util.Comparator.comparingInt(p -> p.getFileName().toString().length()))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(String key, ReferenceSpec spec) {
        try {
            Files.createDirectories(cacheDir);
            StringBuilder sb = new StringBuilder("{\"colours\":[");
            for (int i = 0; i < spec.colours().size(); i++) {
                var c = spec.colours().get(i);
                sb.append(i > 0 ? "," : "").append("{\"colour\":\"").append(c.colour())
                  .append("\",\"percent\":").append(c.percent()).append('}');
            }
            sb.append("],\"parts\":[");
            for (int i = 0; i < spec.parts().size(); i++) {
                String part = spec.parts().get(i);
                Integer n = spec.counts().get(part);
                sb.append(i > 0 ? "," : "");
                if (n == null) {
                    sb.append('"').append(part).append('"');
                } else {
                    sb.append("{\"part\":\"").append(part).append("\",\"count\":").append(n)
                      .append('}');
                }
            }
            // EVERY FIELD MUST BE CACHED. A field the writer forgets parses on a cache MISS and
            // vanishes on every HIT -- which is the state an eval runs in, so warming the cache to
            // make a run reproducible would silently disable the thing the run exists to measure.
            // That has happened here once already (the blockout, since removed).
            sb.append("],");
            sb.append("\"images\":[");
            for (int i = 0; i < spec.images().size(); i++) {
                sb.append(i > 0 ? "," : "").append('"').append(spec.images().get(i)).append('"');
            }
            sb.append("],\"regions\":[");
            for (int i = 0; i < spec.regions().size(); i++) {
                var r = spec.regions().get(i);
                sb.append(i > 0 ? "," : "").append("{\"region\":\"").append(r.region().name())
                  .append("\",\"colour\":\"").append(r.colour())
                  .append("\",\"percent\":").append(r.percent()).append('}');
            }
            sb.append("],\"colourConfidence\":\"").append(spec.colourConfidence().name())
              .append("\",\"colourSource\":\"").append(spec.colourSource().name())
              .append("\",\"expression\":\"").append(spec.expression().name())
              .append("\",\"stance\":\"").append(spec.stance().name())
              .append("\",\"tail\":\"").append(spec.tail().name())
              .append("\",\"size\":\"").append(spec.size().name())
              .append("\",\"confidence\":\"").append(spec.confidence().name())
              .append("\",\"sources\":[");
            for (int i = 0; i < spec.sources().size(); i++) {
                sb.append(i > 0 ? "," : "").append('"').append(spec.sources().get(i)).append('"');
            }
            sb.append("],\"").append(LOOKED_UP_AT).append("\":").append(System.currentTimeMillis()).append('}');
            Files.writeString(cacheDir.resolve(key + ".json"), sb.toString());
        } catch (Exception e) {
            LOGGER.debug("could not cache reference for {}: {}", key, e.toString());
        }
    }
}
