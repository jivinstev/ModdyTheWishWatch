package com.moddingfromamod.reference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.safety.SafetyGrader;
import com.moddingfromamod.safety.SafetyInputGate;
import com.moddingfromamod.safety.SafetyLexicon;
import com.moddingfromamod.safety.SafetyVerdict;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>: a named character's wish is rewritten into a nameless design brief before a worker takes it.</b>
 *
 * <p>The sixth way words enter the pipeline, and so a named safety choke point (docs/CHILD_SAFETY.md):
 * the brief's LOOK is new words from the web (a description of reference pictures), never typed by the
 * child, so the child's own grade says nothing about it. The pattern is the child's own grade's:
 * <ol>
 *   <li><b>at submit</b> ({@link #atSubmit}), in parallel with the child's grade and the worker waking:
 *       resolve the character (structure, never "does a model know this name"), look it up, write the
 *       look, then start its two checks the moment it exists: {@link WishBrief#injection} and the
 *       child-safety grade of the look;</li>
 *   <li><b>at the hand-over</b> ({@link #atHandOver}), the only serial point: the worker is given the
 *       brief only once both checks passed. Anything else (not a character, no reference, a check that
 *       failed, timed out or could not run, a redirected wish) hands over the child's own words, which
 *       is today's path, never an empty wish.</li>
 * </ol>
 * Once a brief is handed over, the wish's name is VEILED: every tool result the worker reads has the
 * name replaced by {@link WishBrief#TOKEN}, and every argument it sends has the token replaced by the
 * name, so the child reads the real name and the worker never does ({@code McpProtocol}).
 *
 * <p>A brief that passed both checks is cached per character and kind ({@link #cacheDir}), so a
 * character seen before costs no lookup, no description and no grade.
 */
public final class WishBriefs {

    private static final Logger LOGGER = LoggerFactory.getLogger(WishBriefs.class);

    /**
     * <b>The longest a wish waits for its brief, in all, counted from the moment it was made</b>.
     * One deadline, shared by {@link #willBrief} and {@link #atHandOver}: each waits only for what is left
     * of it. It was 150 s per wait, waited twice on the same brief, so a stuck brief held a wish five
     * minutes while the bar said "Reading your wish…" and the claim ceiling (120 s) cancelled it as
     * never claimed. measured a warm brief at 1–2 s and its one cold lookup at 45 s; past this the
     * child's own words go (the path every wish took before briefs) and the brief carries on in the
     * background, so its cache is warm for the next wish naming that character.
     */
    public static final long BRIEF_WAIT_MS = 60_000L;

    /** {@code -Dmoddingfromamod.briefWaitMs}: a test's shorter deadline. */
    public static final String WAIT_PROPERTY = "moddingfromamod.briefWaitMs";

    /** The deadline in force (ms). */
    public static long briefWaitMs() {
        Long v = Long.getLong(WAIT_PROPERTY);
        return v == null || v <= 0 ? BRIEF_WAIT_MS : v;
    }

    /** How long a lookup may take inside the brief's own (background) work. */
    static final int LOOKUP_WAIT_SECONDS = 110;

    /** How long the look's grade may take. */
    static final long GRADE_WAIT_MS = 70_000L;

    /** What became of one wish's brief. {@code look} is set only when {@code outcome} is OK. */
    public record Result(String outcome, String name, String look, boolean cached, long readyMs, String why) {
        static Result not(String outcome, String name, long t0, String why) {
            return new Result(outcome, name, "", false, System.currentTimeMillis() - t0, why);
        }

        public boolean ok() {
            return "OK".equals(outcome);
        }
    }

    /** Where the brief's inputs come from: the real pipeline, or a test's stubs. */
    public interface Sources {
        Optional<CharacterResolver.Resolved> resolve(String wishText);

        ReferenceSpec lookUp(String name, SubjectKind kind);

        String describe(String name, ReferenceSpec spec, SubjectKind kind);

        /** The look's child-safety verdict; {@link SafetyVerdict#unavailable()} when no grade came back. */
        SafetyVerdict grade(String look);

        /** speed: picture links that arrive while the lookup runs, or null when there is no early start. */
        default CompletableFuture<List<String>> earlyPictures(String name) {
            return null;
        }

        /** The look from picture links (the early start's describer). */
        default String describeUrls(String name, List<String> urls) {
            return "";
        }

        /** The lookup is over: stop offering early pictures for this name. */
        default void lookupDone(String name) {
        }
    }

    static final Sources REAL = new Sources() {
        @Override
        public Optional<CharacterResolver.Resolved> resolve(String wishText) {
            return CharacterResolver.resolveSubject(wishText);
        }

        @Override
        public ReferenceSpec lookUp(String name, SubjectKind kind) {
            return ReferenceResearcher.lookUp(name, LOOKUP_WAIT_SECONDS, kind);
        }

        @Override
        public String describe(String name, ReferenceSpec spec, SubjectKind kind) {
            return BriefLooks.describe(name, spec, kind);
        }

        @Override
        public SafetyVerdict grade(String look) {
            try {
                return SafetyGrader.gradeInput(look).get(GRADE_WAIT_MS, TimeUnit.MILLISECONDS).verdict();
            } catch (Exception e) {
                return SafetyVerdict.unavailable();
            }
        }

        @Override
        public CompletableFuture<List<String>> earlyPictures(String name) {
            return ReferenceResearcher.earlyArtwork(name);
        }

        @Override
        public String describeUrls(String name, List<String> urls) {
            return BriefLooks.describeUrls(name, urls);
        }

        @Override
        public void lookupDone(String name) {
            ReferenceResearcher.forgetEarlyArtwork(name);
        }
    };

    /** Daemon threads for the early describer, so a describe in flight never holds a shutdown open. */
    private static final java.util.concurrent.ExecutorService EARLY = java.util.concurrent.Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mfam-brief-early");
        t.setDaemon(true);
        return t;
    });

    private static volatile Sources sources = REAL;

    private static final ConcurrentHashMap<UUID, CompletableFuture<Result>> PENDING = new ConcurrentHashMap<>();
    /** Every brief whose thread has not finished yet, even after a give-up took it out of PENDING: tests wait on it. */
    private static final java.util.Set<CompletableFuture<Result>> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<UUID, String> VEILED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> LOOKS = new ConcurrentHashMap<>();

    private WishBriefs() {
    }

    /** Start the brief for this wish, if briefs are on. Never blocks and never throws. */
    public static void atSubmit(UUID wishId, String childText) {
        WishBrief.Mode mode = WishBrief.Mode.current();
        if (wishId == null || mode == WishBrief.Mode.OFF || childText == null || childText.isBlank()) {
            return;
        }
        SafetyVerdict childLexicon = SafetyLexicon.shared().check(childText).orElse(null);
        if (childLexicon != null && SafetyInputGate.actionOf(childLexicon) != SafetyInputGate.Act.PASS) {
            // His own words already fail the lexicon (L0, synchronous): no brief is built for this wish, and
            // AwaitWishTool's enforcement of his grade decides it exactly as it would without briefs.
            return;
        }
        CompletableFuture<Result> f = new CompletableFuture<>();
        PENDING.put(wishId, f);
        IN_FLIGHT.add(f);
        f.whenComplete((r, e) -> IN_FLIGHT.remove(f));
        DEADLINE.put(wishId, System.currentTimeMillis() + briefWaitMs());
        // A plain daemon thread that owns its try/catch, so the future always completes (CLAUDE.md).
        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                f.complete(make(childText, mode, sources, t0, name -> lookingUp(wishId, name)));
            } catch (Throwable e) {
                f.complete(Result.not("ERROR", "", t0, e.toString()));
            }
        }, "mfam-wish-brief");
        t.setDaemon(true);
        t.start();
    }

    /** The whole brief, decided. Pure apart from what {@code src} does, so the gate is testable. */
    static Result make(String childText, WishBrief.Mode mode, Sources src, long t0) {
        return make(childText, mode, src, t0, name -> { });
    }

    /** @param coldLookup told the character's name when it is not cached, so it will be looked up (the bar says so) */
    static Result make(String childText, WishBrief.Mode mode, Sources src, long t0,
                       java.util.function.Consumer<String> coldLookup) {
        var who = src.resolve(childText);
        if (who.isEmpty() || who.get().kind() != SubjectKind.CHARACTER) {
            // A dragon named Sparky, a real person, an ordinary wish: never rewritten.
            return Result.not("NOT_A_CHARACTER", who.map(CharacterResolver.Resolved::name).orElse(""), t0,
                    who.map(r -> r.kind().name()).orElse("none"));
        }
        String name = who.get().name();
        Optional<String> cached = readCache(mode, name);
        if (cached.isPresent()) {
            return new Result("OK", name, cached.get(), true, System.currentTimeMillis() - t0, "");
        }
        try {
            coldLookup.accept(name);
        } catch (RuntimeException e) {
            // the bar's line is a nicety; the brief goes on
        }
        return forCharacter(name, who.get().kind(), mode, src, t0);
    }

    /**
     * <b>Warm the cache</b> for a most-wished character, off the wish path ("cached and reviewed
     * for the most-wished characters, so most wishes use a brief that was checked once"). The same pipeline a
     * wish runs: lookup, description, injection check, colour agreement, the child-safety grade; only a look
     * that passed all of them is written. {@link BriefWarm} is the command.
     */
    public static Result warm(String name, WishBrief.Mode mode) {
        return forCharacter(name, SubjectKind.CHARACTER, mode, sources, System.currentTimeMillis());
    }

    /** From a resolved character name on: the lookup, the look, the checks, the cache. */
    static Result forCharacter(String name, SubjectKind kind, WishBrief.Mode mode, Sources src, long t0) {
        // speed: for (b), describe the first cited page's pictures while the researcher is still
        // writing its answer, rather than after it. Taken BEFORE the lookup, so the early pictures are seen.
        CompletableFuture<List<String>> pics = mode == WishBrief.Mode.DESCRIPTIVE ? src.earlyPictures(name) : null;
        CompletableFuture<String> early = pics == null ? null
                : pics.thenApplyAsync(urls -> src.describeUrls(name, urls), EARLY);
        long tl = System.currentTimeMillis();
        ReferenceSpec spec;
        try {
            spec = src.lookUp(name, kind);
        } finally {
            if (pics != null) {
                src.lookupDone(name);
            }
        }
        long lookupMs = System.currentTimeMillis() - tl;
        if (spec == null || spec.isEmpty()) {
            if (early != null) {
                early.cancel(false);
            }
            return Result.not("NO_REFERENCE", name, t0, "");
        }
        BriefLooks.LAST_FETCH_MS.set(-1L);
        BriefLooks.LAST_ASK_MS.set(-1L);
        long td = System.currentTimeMillis();
        String look = "";
        boolean wentEarly = pics != null && pics.isDone() && !pics.isCompletedExceptionally();
        if (wentEarly) {
            try {
                look = early.get(BriefLooks.TIMEOUT_SECONDS + 5, TimeUnit.SECONDS);
            } catch (Exception e) {
                look = "";
            }
        } else if (early != null) {
            early.cancel(false);
        }
        if ((look == null || look.isBlank()) && mode == WishBrief.Mode.DESCRIPTIVE) {
            look = src.describe(name, spec, kind);   // no early pictures, or they gave nothing: the usual path
        }
        long describeMs = System.currentTimeMillis() - td;
        if (wentEarly) {
            LOGGER.info("brief look for '{}' started early, on the first cited page's pictures", name);
        }
        String fellBack = "";
        if (look != null && !look.isBlank()) {
            // The look must not carry the name: a describer told not to may still write it.
            look = WishBrief.veil(look, name).replace(WishBrief.TOKEN, "it");
            // An injected description ends the brief here (his own words go instead), never a fallback:
            // a describer that was talked into writing instructions is not trusted for anything else.
            Optional<String> injected = WishBrief.injection(look);
            if (injected.isPresent()) {
                return Result.not("INJECTION", name, t0, injected.get());
            }
        }
        if (wentEarly && look != null && !look.isBlank() && !WishBrief.agreesWith(look, spec)
                && mode == WishBrief.Mode.DESCRIPTIVE) {
            // (round 4): the EARLY look came from the first cited page's pictures alone, which
            // can show the wrong thing (Web-Slinger out of costume). Before giving up on a description, describe the
            // FULL picture set once; only if that disagrees too does the structured look go instead.
            LOGGER.info("brief look for '{}' from the early pictures disagrees with the measured colours; "
                    + "describing the full picture set", name);
            String full = src.describe(name, spec, kind);
            if (full != null && !full.isBlank()) {
                full = WishBrief.veil(full, name).replace(WishBrief.TOKEN, "it");
                Optional<String> injected = WishBrief.injection(full);
                if (injected.isPresent()) {
                    return Result.not("INJECTION", name, t0, injected.get());
                }
                look = full;
            }
            describeMs = System.currentTimeMillis() - td;
        }
        if (look != null && !look.isBlank() && !WishBrief.agreesWith(look, spec)) {
            fellBack = "description disagrees with the measured colours; structured look instead";
            LOGGER.info("brief look for '{}' disagrees with the measured colours ({}): {}", name, spec.colours(),
                    look.substring(0, Math.min(300, look.length())));
            look = "";
        }
        if (look == null || look.isBlank()) {
            // (b) with no description (no pictures, or none the describer could agree on) is (a): the
            // spec still knows the colours and parts, and that is better than the name alone.
            if (fellBack.isEmpty() && mode == WishBrief.Mode.DESCRIPTIVE) {
                fellBack = "no description; structured look instead";
            }
            look = WishBrief.structuredLook(spec);
        }
        // The look must not carry the name either: a describer told not to may still write it.
        look = WishBrief.veil(look == null ? "" : look, name).replace(WishBrief.TOKEN, "it");
        // (a)'s look, and (b)'s fallback to it, is written only from ReferenceSpec's closed sets and our own
        // template: no web string reaches it, so it is VOCABULARY-tested at build time
        // (StructuredLookVocabularyTest) rather than graded. Only a describer's words are graded.
        boolean described = fellBack.isEmpty() && mode == WishBrief.Mode.DESCRIPTIVE;
        if (described) {
            look = WishBrief.reconcileTail(look, spec);   //: front-facing pictures hide a tail
        }
        TOLD.put(name, new Body(WishBrief.stanceTold(look, spec), WishBrief.tailTold(look, spec)));
        long tc = System.currentTimeMillis();
        Result checked = check(name, look, src, t0, described);
        long[] steps = {lookupMs, describeMs, BriefLooks.LAST_FETCH_MS.get(), BriefLooks.LAST_ASK_MS.get(),
                System.currentTimeMillis() - tc};
        STEPS.put(name, steps);
        LOGGER.info("brief steps for '{}' ({}): lookup {} ms, look {} ms (pictures {} ms, describer {} ms), checks {} ms",
                name, mode.name().toLowerCase(Locale.ROOT), steps[0], steps[1], steps[2], steps[3], steps[4]);
        if (checked.ok() && fellBack.isEmpty()) {
            writeCache(mode, name, look);
        }
        return checked.ok() && !fellBack.isEmpty()
                ? new Result("OK", name, look, false, checked.readyMs(), fellBack) : checked;
    }

    /**
     * <b>The choke point.</b> A look reaches a worker only if the injection check and the lexicon find
     * nothing AND, for a DESCRIBED look ((b): a model's words about pictures from the web), the child-safety
     * grade (lexicon and model, combined exactly as for a child's wish) passes it with nothing to say. A grade
     * that did not come back is a failure here, unlike a child's own wish: the fallback (his own words) costs
     * nothing he asked for. A STRUCTURED look ((a)) is built from closed sets only and is not graded: its
     * every possible word is checked at build time (StructuredLookVocabularyTest).
     */
    static Result check(String name, String look, Sources src, long t0, boolean described) {
        Optional<String> injected = WishBrief.injection(look);
        if (injected.isPresent()) {
            return Result.not("INJECTION", name, t0, injected.get());
        }
        SafetyVerdict lexicon = SafetyLexicon.shared().check(look).orElse(null);
        if (!described) {
            if (lexicon != null && SafetyInputGate.actionOf(lexicon) != SafetyInputGate.Act.PASS) {
                return Result.not("UNSAFE", name, t0, "lexicon " + lexicon.overallLabel());
            }
            return new Result("OK", name, look, false, System.currentTimeMillis() - t0, "");
        }
        SafetyVerdict model = src.grade(look);
        if (model == null || model.isUnavailable()) {
            return Result.not(lexicon != null ? "UNSAFE" : "UNGRADED", name, t0,
                    lexicon != null ? "lexicon " + lexicon.overallLabel() : "no grade");
        }
        var act = SafetyInputGate.actionOf(SafetyInputGate.combine(lexicon, model));
        if (act != SafetyInputGate.Act.PASS) {
            return Result.not("UNSAFE", name, t0, act.name());
        }
        return new Result("OK", name, look, false, System.currentTimeMillis() - t0, "");
    }

    /**
     * The text this worker is handed. {@code runText} is what the pickup gates decided (the child's own
     * words, or a safety or world-rules redirect, which always wins over a brief).
     */
    public static String atHandOver(UUID wishId, String childText, String runText, String shortId) {
        CompletableFuture<Result> f = wishId == null ? null : PENDING.remove(wishId);
        if (f == null) {
            return runText;
        }
        Long waited = WAIT_FROM.remove(wishId);
        long t0 = waited != null ? waited : System.currentTimeMillis();
        long left = remainingMs(wishId);
        DEADLINE.remove(wishId);
        Result r;
        try {
            // only what is left of the one deadline, never a second full wait. A brief willBrief already
            // gave up on stays given up on, even if it lands a moment later: that wish may have been handed over
            // ahead of its grade, which a wish carrying a brief never is.
            if (GAVE_UP.remove(wishId)) {
                throw new java.util.concurrent.TimeoutException();
            }
            r = f.isDone() ? f.get() : f.get(left, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            r = Result.not("TIMEOUT", "", t0, "");
        } catch (Exception e) {
            r = Result.not("ERROR", "", t0, e.toString());
        }
        long held = System.currentTimeMillis() - t0;
        boolean redirected = runText != null && !runText.equals(childText);
        boolean used = r.ok() && !redirected;
        com.moddingfromamod.agent.ActionLog.brief(shortId, WishBrief.Mode.current().name().toLowerCase(Locale.ROOT),
                redirected && r.ok() ? "REDIRECTED" : r.outcome(), r.name(), r.cached(), r.readyMs(), held,
                used ? r.look() : "", r.why());
        LOGGER.info("wish {}: brief {} ({}{}) ready at {} ms, held the hand-over {} ms{}", shortId,
                used ? "USED" : "not used", r.outcome(), r.cached() ? ", cached" : "", r.readyMs(), held,
                r.why().isEmpty() ? "" : " — " + r.why());
        if (!used) {
            return runText;
        }
        VEILED.put(wishId, r.name());
        LOOKS.put(wishId, r.look());
        Body told = TOLD.get(r.name());
        BODIES.put(wishId, told != null ? told
                : new Body(WishBrief.stanceTold(r.look(), null), WishBrief.tailTold(r.look(), null)));
        return WishBrief.rewrite(childText, r.name(), r.look());
    }

    private static final ConcurrentHashMap<UUID, Long> WAIT_FROM = new ConcurrentHashMap<>();

    /** When each pending brief stops being waited for (epoch ms): {@link #BRIEF_WAIT_MS} after the wish was made. */
    private static final ConcurrentHashMap<UUID, Long> DEADLINE = new ConcurrentHashMap<>();

    /** Wishes whose brief {@link #willBrief} stopped waiting for: the hand-over never uses it. */
    private static final java.util.Set<UUID> GAVE_UP = ConcurrentHashMap.newKeySet();

    /** What is left of this wish's brief deadline (ms, never negative). */
    static long remainingMs(UUID wishId) {
        Long d = wishId == null ? null : DEADLINE.get(wishId);
        long deadline = d != null ? d : System.currentTimeMillis() + briefWaitMs();
        return Math.max(0L, deadline - System.currentTimeMillis());
    }

    /**
     * The bar's hook: told when a wish's character is being looked up, so the child reads "Looking up
     * Scorch…" rather than "Reading your wish…" for the whole wait. Set by {@code WishProgress}; a no-op until then.
     */
    public static volatile java.util.function.BiConsumer<UUID, String> lookingUpListener = (id, name) -> { };

    private static void lookingUp(UUID wishId, String name) {
        lookingUpListener.accept(wishId, name);
    }

    /** What a brief told its worker about how the character stands and its tail. */
    public record Body(ReferenceSpec.Stance stance, ReferenceSpec.Tail tail) {
    }

    private static final ConcurrentHashMap<String, Body> TOLD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Body> BODIES = new ConcurrentHashMap<>();

    /** What this wish's brief told its worker about stance and tail, or null when it had no brief. */
    public static Body bodyFor(UUID wishId) {
        return wishId == null ? null : BODIES.get(wishId);
    }

    /** speed: the last brief's step times per character (lookup, look, pictures, describer, checks), ms. */
    static final ConcurrentHashMap<String, long[]> STEPS = new ConcurrentHashMap<>();

    /**
     * <b>Will this wish be handed over as a brief?</b> Waits for the brief up to its one deadline (the
     * hand-over then waits only for what is left of it), and says whether a look passed its checks.
     * AwaitWishTool asks it BEFORE deciding to hand a wish over ahead of its grade: a wish that
     * will carry a brief is never handed over speculatively, so the child's own grade is decided first and
     * a refused or redirected wish never reaches a worker with a brief.
     */
    public static boolean willBrief(UUID wishId) {
        CompletableFuture<Result> f = wishId == null ? null : PENDING.get(wishId);
        if (f == null) {
            return false;
        }
        WAIT_FROM.putIfAbsent(wishId, System.currentTimeMillis());
        try {
            return f.isDone() ? f.get().ok() : f.get(remainingMs(wishId), TimeUnit.MILLISECONDS).ok();
        } catch (java.util.concurrent.TimeoutException e) {
            GAVE_UP.add(wishId);
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** The name hidden from this wish's worker, or empty. */
    public static String veiledName(UUID wishId) {
        return wishId == null ? "" : VEILED.getOrDefault(wishId, "");
    }

    /** The look this wish's worker was handed, or empty (the sculpt critique reads it for "solid eyes"). */
    public static String lookFor(UUID wishId) {
        return wishId == null ? "" : LOOKS.getOrDefault(wishId, "");
    }

    /** Is this wish being worked from a brief (so nothing may name the character to its worker)? */
    public static boolean veiled(UUID wishId) {
        return !veiledName(wishId).isEmpty();
    }

    /** Text the worker is about to read, with the name taken out. */
    public static String veil(UUID wishId, String text) {
        String name = veiledName(wishId);
        return name.isEmpty() ? text : WishBrief.veil(text, name);
    }

    /** Every string in the worker's arguments with the token turned back into the name the child reads. */
    public static JsonObject unveil(UUID wishId, JsonObject args) {
        String name = veiledName(wishId);
        if (name.isEmpty() || args == null || !args.toString().contains(WishBrief.TOKEN)) {
            return args;
        }
        String json = args.toString().replace(WishBrief.TOKEN, name.strip().replace("\\", "").replace("\"", ""));
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** {@link #unveil}, written back into the same object (the caller's reference is captured elsewhere). */
    public static void unveilInPlace(UUID wishId, JsonObject args) {
        JsonObject out = unveil(wishId, args);
        if (out == null || out == args) {
            return;
        }
        for (String k : new java.util.ArrayList<>(args.keySet())) {
            args.remove(k);
        }
        for (var e : out.entrySet()) {
            args.add(e.getKey(), e.getValue());
        }
    }

    /** The wish is over: nothing more to hide or hold. */
    public static void forget(UUID wishId) {
        if (wishId != null) {
            VEILED.remove(wishId);
            LOOKS.remove(wishId);
            PENDING.remove(wishId);
            WAIT_FROM.remove(wishId);
            DEADLINE.remove(wishId);
            GAVE_UP.remove(wishId);
            BODIES.remove(wishId);
        }
    }

    // ---- the cache of briefs that passed ----------------------------------------------------------

    static Path cacheDir() {
        return ReferenceResearcher.cacheDirForBriefs();
    }

    private static Path cacheFile(WishBrief.Mode mode, String name) {
        String key = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        return cacheDir().resolve(mode.name().toLowerCase(Locale.ROOT) + "-" + key + ".txt");
    }

    /** A cached look is re-checked for injection on read (a file on disk is not a promise). */
    static Optional<String> readCache(WishBrief.Mode mode, String name) {
        if (Boolean.getBoolean("moddingfromamod.wishbriefnocache")) {
            return Optional.empty();
        }
        try {
            Path f = cacheFile(mode, name);
            if (!Files.isRegularFile(f)) {
                return Optional.empty();
            }
            String look = Files.readString(f, StandardCharsets.UTF_8).strip();
            return WishBrief.injection(look).isEmpty() ? Optional.of(look) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static void writeCache(WishBrief.Mode mode, String name, String look) {
        if (Boolean.getBoolean("moddingfromamod.wishbriefnocache")) {
            return;   // a gate never writes the real cache
        }
        try {
            Files.createDirectories(cacheDir());
            Files.writeString(cacheFile(mode, name), look, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.debug("could not cache a brief: {}", e.toString());
        }
    }

    // ---- tests ------------------------------------------------------------------------------------

    /** Swap the inputs (null restores the real pipeline). */
    public static void sourcesForTest(Sources s) {
        sources = s == null ? REAL : s;
    }

    /**
     * Forget every wish's brief. First waits (up to 10 s) for every brief thread still running, so a brief a test let
     * go on cannot finish later and write its look into the next test's cache directory.
     */
    public static void clearForTest() {
        long until = System.currentTimeMillis() + 10_000;
        for (CompletableFuture<Result> f : List.copyOf(IN_FLIGHT)) {
            long left = until - System.currentTimeMillis();
            if (left <= 0) {
                break;
            }
            try {
                f.get(left, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // stuck for good, or failed: nothing more to wait for
            }
        }
        IN_FLIGHT.clear();
        PENDING.clear();
        VEILED.clear();
        LOOKS.clear();
        WAIT_FROM.clear();
        DEADLINE.clear();
        GAVE_UP.clear();
        BODIES.clear();
        TOLD.clear();
    }
}
