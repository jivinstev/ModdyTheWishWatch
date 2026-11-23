package com.moddingfromamod.agent;

import com.moddingfromamod.config.SupportedModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * <b>The fallback chain, live</b>: the one instance of {@link ModelFallback.State}, kept in
 * {@code <moddy home>/model-fallback.json} so a skip survives a restart (memory only in a test run), what each pool
 * slot was launched on, the deaths that send a held wish back to the queue ({@code health/WishSweeper}), and the
 * player's notice. The decisions are {@link ModelFallback}'s; this holds their state and does the I/O.
 *
 * <p>Active only where {@code config/MagicianWiring} hands it a chain: a public install whose host picked a model. A
 * server whose settings override locks the model never runs it (its own launcher decides).
 */
public final class ModelFallbackRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger("moddingfromamod/model-fallback");

    /** Which chain: the wish worker's or the helper calls'. Each remembers its own last entry. */
    public enum Chain { WISH, HELPER }

    private static final Object LOCK = new Object();
    private static ModelFallback.State state;
    private static Path fileForTest;
    private static final Map<Chain, ModelFallback.Entry> last = new ConcurrentHashMap<>();
    private static final Map<String, ModelFallback.Entry> launched = new ConcurrentHashMap<>();
    private static final Map<String, com.moddingfromamod.marshal.DeadWorkerRequeue.Death> deaths =
            new ConcurrentHashMap<>();
    private static volatile Consumer<String> tell = ModelFallbackRuntime::broadcast;
    private static volatile Function<String, String> env = System::getenv;
    private static volatile Function<ModelFallback.Entry, Optional<String>> missingProgram =
            ModelFallbackRuntime::programMissing;
    private static volatile java.util.function.LongSupplier clock = System::currentTimeMillis;

    private ModelFallbackRuntime() {
    }

    // ── test seams ────────────────────────────────────────────────────────────────────────────────────

    /** A test's sink for the player's notices, environment, program check and clock (null puts each back). */
    public static void forTest(Consumer<String> sink, Function<String, String> e,
                               Function<ModelFallback.Entry, Optional<String>> program,
                               java.util.function.LongSupplier now) {
        synchronized (LOCK) {
            tell = sink == null ? ModelFallbackRuntime::broadcast : sink;
            env = e == null ? System::getenv : e;
            missingProgram = program == null ? ModelFallbackRuntime::programMissing : program;
            clock = now == null ? System::currentTimeMillis : now;
            state = new ModelFallback.State();
            last.clear();
            launched.clear();
            deaths.clear();
        }
    }

    // ── the decision at a launch ──────────────────────────────────────────────────────────────────────

    /** Decide the next launch on {@code chain}, tell the player if it changed, and say what it was. */
    public static ModelFallback.Decision decide(Chain which, List<ModelFallback.Entry> chain) {
        ModelFallback.Decision d;
        synchronized (LOCK) {
            int before = state().skips().size();
            d = ModelFallback.choose(chain, state(), clock.getAsLong(), ModelFallbackRuntime::unavailable,
                    ModelFallbackRuntime::signInStamp, ZoneId.systemDefault());
            if (state().skips().size() != before) {
                save();                                   // an expired skip was dropped
            }
            ModelFallback.Entry prev = last.get(which);
            Optional<String> notice = ModelFallback.switchNotice(d, prev);
            boolean sameExhaustion = d.exhausted() && prev == EXHAUSTED;
            d.entry().ifPresentOrElse(e -> last.put(which, e), () -> last.put(which, EXHAUSTED));
            if (notice.isPresent() && !sameExhaustion && which == Chain.WISH) {
                LOGGER.info("model fallback: {}", notice.get());
                tell.accept(notice.get());
            } else if (notice.isPresent() && !sameExhaustion) {
                LOGGER.info("model fallback (helper): {}", notice.get());
            }
        }
        return d;
    }

    /** A marker for "the last decision found every entry skipped", so the player is told once, not per launch. */
    private static final ModelFallback.Entry EXHAUSTED = new ModelFallback.Entry(
            SupportedModels.ALL.get(0), SupportedModels.Mode.API);

    /** Remember what {@code worker} was launched on, for {@link #workerEnded}. */
    public static void launched(String worker, ModelFallback.Entry entry) {
        launched.put(worker, entry);
    }

    public static Optional<ModelFallback.Entry> launchedOn(String worker) {
        return Optional.ofNullable(launched.get(worker));
    }

    /**
     * {@code worker} ended (its process exited, or a start never came up), and {@code text} is what it wrote. An
     * account-shaped failure skips its entry and records a death, so the wish it held goes back to the queue and the
     * replacement worker launches on the next entry. Returns the classification.
     */
    public static ModelFallback.Classified workerEnded(String worker, String text) {
        ModelFallback.Entry e = launched.get(worker);
        ModelFallback.Classified c = ModelFallback.classify(text);
        if (e == null) {
            return c;
        }
        failed(e, c);
        if (c.failure().fallsThrough()) {
            deaths.put(worker, new com.moddingfromamod.marshal.DeadWorkerRequeue.Death(
                    c.failure().name().toLowerCase(java.util.Locale.ROOT), clock.getAsLong()));
        }
        return c;
    }

    /** Record what {@code e} met (a worker's end, or a helper call's answer). */
    public static void failed(ModelFallback.Entry e, ModelFallback.Classified c) {
        synchronized (LOCK) {
            Optional<ModelFallback.Skip> s = state().failed(e, c, clock.getAsLong(), signInStamp(e));
            if (s.isPresent()) {
                LOGGER.info("model fallback: {} skipped ({}: {}) until {}", e.key(), c.failure(), c.line(),
                        java.time.Instant.ofEpochMilli(s.get().untilMs()));
                save();
            }
        }
    }

    /** A helper call on {@code e} answered: forget any skip it had. */
    public static void ok(ModelFallback.Entry e) {
        synchronized (LOCK) {
            if (state().skips().containsKey(e.key())) {
                state().ok(e);
                save();
            }
        }
    }

    /** The death recorded for a pool slot, if any (read by {@code health/WishSweeper}). */
    public static Optional<com.moddingfromamod.marshal.DeadWorkerRequeue.Death> death(String worker) {
        return Optional.ofNullable(worker == null ? null : deaths.get(worker));
    }

    public static void consumeDeath(String worker) {
        if (worker != null) {
            deaths.remove(worker);
        }
    }

    /** Is any slot's death waiting to be acted on? */
    public static boolean anyDeath() {
        return !deaths.isEmpty();
    }

    /** The current skips (a status line, a test). */
    public static Map<String, ModelFallback.Skip> skips() {
        synchronized (LOCK) {
            return state().skips();
        }
    }

    // ── what this machine can run ─────────────────────────────────────────────────────────────────────

    /** Why {@code e} cannot run here at all: its key not set, no sign-in file, its program not installed. */
    static Optional<String> unavailable(ModelFallback.Entry e) {
        if (e.paid()) {
            String v = env.apply(e.model().provider().keyVar);
            if (v == null || v.isBlank()) {
                return Optional.of(e.model().provider().keyVar + " isn't set");
            }
        } else if (com.moddingfromamod.config.ServerSettings.dedicated()) {
            Optional<String> no = SupportedModels.refusedOnServer(e.model(), e.mode());
            if (no.isPresent()) {
                return no;
            }
        }
        if (!e.paid() && e.model().provider() == SupportedModels.Provider.MUSE && signInStamp(e) <= 0L) {
            return Optional.of("there is no Muse Code sign-in (run `muse login`)");
        }
        return missingProgram.apply(e);
    }

    private static Optional<String> programMissing(ModelFallback.Entry e) {
        boolean muse = e.harness() == SupportedModels.Harness.MUSE_CODE;
        String bin = muse ? MuseExec.binary() : AgentSession.claudeBinary();
        return bin == null ? Optional.of(e.harness().label + " isn't installed") : Optional.empty();
    }

    /** The entry's sign-in file's modification time (Muse Code's {@code auth.json}); 0 for a key or none. */
    static long signInStamp(ModelFallback.Entry e) {
        if (e.paid() || e.model().provider() != SupportedModels.Provider.MUSE) {
            return 0L;
        }
        Path auth = MuseWorker.playerConfigRoot(env, System.getProperty("user.home", "")).resolve("auth.json");
        try {
            return Files.isRegularFile(auth) ? Files.getLastModifiedTime(auth).toMillis() : 0L;
        } catch (IOException ex) {
            return 0L;
        }
    }

    // ── the file ──────────────────────────────────────────────────────────────────────────────────────

    private static Path file() {
        if (fileForTest != null) {
            return fileForTest;
        }
        if (com.moddingfromamod.RunContext.isTestRun()) {
            return null;
        }
        return com.moddingfromamod.runtime.RuntimeConfig.home().resolve("model-fallback.json");
    }

    /** For a test: keep the state in this file (null: memory only). Reloads it. */
    public static void fileForTest(Path f) {
        synchronized (LOCK) {
            fileForTest = f;
            state = null;
        }
    }

    private static ModelFallback.State state() {
        if (state == null) {
            Path f = file();
            String json = null;
            try {
                json = f != null && Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
            } catch (IOException e) {
                LOGGER.warn("model fallback: could not read {} ({}); starting with no skips", f, e.toString());
            }
            state = json == null ? new ModelFallback.State() : ModelFallback.State.fromJson(json);
        }
        return state;
    }

    private static void save() {
        Path f = file();
        if (f == null) {
            return;
        }
        try {
            Files.createDirectories(f.toAbsolutePath().getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, state().toJson(), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("model fallback: could not save {} ({})", f, e.toString());
        }
    }

    // ── the player's notice ───────────────────────────────────────────────────────────────────────────

    private static void broadcast(String line) {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        server.execute(() -> server.getPlayerList().broadcastSystemMessage(
                net.minecraft.network.chat.Component.literal(line)
                        .withStyle(net.minecraft.ChatFormatting.YELLOW), false));
    }
}
