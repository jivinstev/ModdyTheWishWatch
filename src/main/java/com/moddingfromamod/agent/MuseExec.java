package com.moddingfromamod.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import com.moddingfromamod.config.HelperRoute;

/**
 * One grade through MUSE CODE ({@code muse exec}): the process half of {@link MuseGrade}.
 *
 * <p><b>The chain </b>, each step only when the one before gave no answer:
 * <ol>
 *   <li><b>a server's Muse Code SUBSCRIPTION</b> (the OAuth login Wonder's workers use, flat fee),
 *       through {@code tools/muse-cli-shim.py grade}: the shim decides whether to try it by the SAME
 *       sticky policy as Wonder's workers ({@code choose_arm}: no login, {@code subs_active} false, the
 *       5-hour or weekly window spent until {@code resetsAt}, the login failing, plus "the plan does
 *       not serve this model"), carries the login the way it carries a worker's, and writes what the
 *       call met to the same account files ({@code MUSE_SHIM_SHARED}). The policy lives in Python
 *       only; this class reads nothing but the shim's exit code. Present only on a server whose
 *       {@code bin/} carries the shim ({@link #shim()});</li>
 *   <li><b>the API key</b> ({@code MUSE_API_KEY} on stdin, metered);</li>
 *   <li>null — the CALLER asks its job's pinned Haiku with the original prompt
 *       ({@code SafetyGrader.CliTransport}, {@code ReadyMadeNudge.CliTransport},
 *       {@code CharacterResolver}).</li>
 * </ol>
 * <b>Which of these steps a call may take is its job's ROUTE</b> ({@link HelperRoute}): every entry
 * point takes the Muse steps of that route and tries only those, so a key or a sign-in the route does not name is
 * never used, whatever the environment holds. With no route set the steps are exactly the chain above.
 *
 * <p>The two Muse steps share ONE budget ({@code timeoutMs}): a subscription refusal costs the key step
 * ~50 ms, a subscription timeout leaves it nothing, so a grade never waits longer than before.
 * {@link Answer#arm()} says which step answered; the grade records carry it.
 *
 * <p><b>Null means "no answer" and the caller falls back</b>: no {@code muse} binary, no key and no
 * login, an error, a timeout, or an answer that is not the JSON the schema asked for. It never throws.
 * The binary is {@code $MUSE_BIN}, else {@code ~/.local/bin/muse} (where
 * Muse Code's installer puts it). With no shim and no key, Muse Code uses its
 * own login (a dev box).
 */
public final class MuseExec {

    private static final Logger LOGGER = LoggerFactory.getLogger("moddingfromamod/muse-exec");

    /** A grade's own budget, short of a grader's: a slow Muse must leave time for the fallback. */
    public static final long TIMEOUT_MS = 20_000L;

    /** Which step of the chain answered (the {@code arm} of a grade record). */
    public static final String ARM_SUBSCRIPTION = "subscription";
    public static final String ARM_API = "api";
    /** Muse Code on its own login with no shim (a dev box, not a server with the shim). */
    public static final String ARM_LOGIN = "login";
    /** The caller's last step: its job's pinned Haiku on the Claude CLI. */
    public static final String ARM_HAIKU = "haiku";

    /** The shim's {@code grade} exit codes (tools/muse-cli-shim.py cmd_grade). */
    static final int SHIM_SKIPPED = 3;

    /**
     * What came back: the answer (JSON when a schema was asked for, else text), its cost, and which
     * arm served it. A subscription answer costs 0: it is the plan's flat fee, never API spend.
     */
    public record Answer(String text, long wallMs, double costUsd, String modelId, String arm) {
    }

    /** Where the API key comes from: the environment. A unit test replaces it (MuseExecChainTest). */
    static volatile java.util.function.Supplier<String> keySource = () -> System.getenv("MUSE_API_KEY");

    private MuseExec() {
    }

    /** A test in another package replaces the key's source; null puts the environment back. */
    public static void keyForTest(java.util.function.Supplier<String> source) {
        keySource = source != null ? source : () -> System.getenv("MUSE_API_KEY");
    }

    /** Is there a Muse API key to bill ({@code MUSE_API_KEY}, not the {@code subscription} placeholder)? */
    public static boolean keyPresent() {
        String key = keySource.get();
        return key != null && !key.isBlank() && !"subscription".equals(key.strip());
    }

    /** The binary, or null when this machine has none. */
    public static String binary() {
        // Magician off: no Muse Code call of any kind.
        if (!com.moddingfromamod.config.Magician.allowsAi()) {
            return null;
        }
        return locate();
    }

    /** Where {@code muse} is, with no Magician gate: the Magician setup's program check only. */
    public static String locate() {
        // A test points this at a fake muse (MuseExecTest); nothing in production sets it.
        String prop = System.getProperty("moddingfromamod.muse.bin");
        if (prop != null && !prop.isBlank()) {
            return new File(prop).canExecute() ? prop : null;
        }
        String env = System.getenv("MUSE_BIN");
        if (env != null && !env.isBlank() && new File(env).canExecute()) {
            return env;
        }
        File f = new File(System.getProperty("user.home", ""), ".local/bin/muse");
        return f.canExecute() ? f.getPath() : null;
    }

    /**
     * {@code tools/muse-cli-shim.py}, when this server launches its workers through it (a dedicated
     * server's launcher sets {@code -Dmoddingfromamod.cli} to the shim in its {@code bin/}), else
     * null. {@code -Dmoddingfromamod.muse.shim} names one directly (tests; {@code none} turns it off).
     *
     * <p><b>Dedicated-server only.</b> This is the one place the mod can start a {@code python3}: the shim is a Python
     * script, run through {@code python3} when it is not executable itself. Only a dedicated server's own launcher
     * sets either property, so a player's game (single player, or a LAN world) never has a shim and never starts
     * Python. Everything a player's Magician does, CBScript datapacks included, is compiled in-process
     * ({@code CbScriptCompiler}); {@code NoPythonTest} holds every other process the jar can start to that.
     */
    public static String shim() {
        String prop = System.getProperty("moddingfromamod.muse.shim");
        if (prop != null && !prop.isBlank()) {
            return "none".equalsIgnoreCase(prop.strip()) || !new File(prop.strip()).isFile() ? null : prop.strip();
        }
        String cli = System.getProperty("moddingfromamod.cli");
        if (cli != null && cli.strip().endsWith("muse-cli-shim.py") && new File(cli.strip()).isFile()) {
            return cli.strip();
        }
        return null;
    }

    /** The Muse steps a caller that names none takes: a grader's ({@link HelperRoute}, the plan grader's route). */
    static List<HelperRoute.Step> defaultSteps() {
        return HelperRoute.muse(HelperRoute.steps(HelperRoute.Job.PLAN, true));
    }

    /**
     * A structured grade. Returns the answer's JSON with the optional fields that came back null
     * removed (see {@link MuseGrade#strictSchema}), or null.
     */
    public static Answer grade(String systemPrompt, String userText, String jsonSchema, String modelId,
                               String effort, long timeoutMs) {
        return grade(systemPrompt, userText, jsonSchema, modelId, effort, timeoutMs, defaultSteps());
    }

    /** The same, on exactly {@code steps} (the Muse steps of the job's {@link HelperRoute}). */
    public static Answer grade(String systemPrompt, String userText, String jsonSchema, String modelId,
                               String effort, long timeoutMs, List<HelperRoute.Step> steps) {
        JsonObject schema;
        try {
            JsonElement el = JsonParser.parseString(jsonSchema);
            if (!el.isJsonObject()) {
                return null;
            }
            schema = el.getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
        Answer a = run(systemPrompt, userText, MuseGrade.strictSchema(schema).toString(), modelId, effort,
                timeoutMs, List.of(), MuseGrade.FLAGS, steps);
        if (a == null) {
            return null;
        }
        try {
            JsonElement el = JsonParser.parseString(a.text());
            if (!el.isJsonObject()) {
                return null;
            }
            return new Answer(MuseGrade.dropNulls(el.getAsJsonObject(), schema).toString(), a.wallMs(),
                    a.costUsd(), a.modelId(), a.arm());
        } catch (RuntimeException e) {
            LOGGER.info("muse grade: the answer was not JSON; falling back");
            return null;
        }
    }

    /** A free-text answer (CharacterResolver's classifier), or null. */
    public static Answer text(String systemPrompt, String userText, String modelId, String effort,
                              long timeoutMs) {
        return text(systemPrompt, userText, modelId, effort, timeoutMs, defaultSteps());
    }

    public static Answer text(String systemPrompt, String userText, String modelId, String effort,
                              long timeoutMs, List<HelperRoute.Step> steps) {
        return run(systemPrompt, userText, null, modelId, effort, timeoutMs, List.of(), MuseGrade.FLAGS, steps);
    }

    /**
     * A free-text answer about PICTURES (an earlier issue's describer) on exactly {@code steps}: the image files ride in the
     * first message ({@code --image}), one model turn with no tools. On a server with the shim the subscription step goes through the
     * shim by the same sticky policy and account files as a grade's; elsewhere it is Muse Code's own login, or the
     * host's helper chain when their picks are in charge. Null when no step answered. (The describer takes its key
     * step DIRECT, {@code reference/EmberVision}, so it passes the subscription step alone.)
     */
    public static Answer describe(String systemPrompt, String userText, List<Path> images, String modelId,
                                  String effort, long timeoutMs, List<HelperRoute.Step> steps) {
        List<String> paths = new ArrayList<>();
        for (Path im : images == null ? List.<Path>of() : images) {
            paths.add(im.toAbsolutePath().toString());
        }
        return run(systemPrompt, userText, null, modelId, effort, timeoutMs, List.copyOf(paths), MuseGrade.FLAGS,
                steps);
    }

    /**
     * <b>A web lookup through Muse Code</b>: the reference researcher's Muse steps, with
     * {@link MuseGrade#RESEARCH_FLAGS}: the two web tools and nothing else. Null when no step answered. The answer is
     * untrusted text; the caller parses it into a validated type ({@code reference/ReferenceSpec}).
     */
    public static Answer research(String systemPrompt, String userText, String modelId, String effort,
                                  long timeoutMs, List<HelperRoute.Step> steps) {
        return run(systemPrompt, userText, null, modelId, effort, timeoutMs, List.of(), MuseGrade.RESEARCH_FLAGS,
                steps);
    }

    /**
     * Walk the Muse {@code steps} of a job's route ({@link HelperRoute}) inside one budget. Only those steps: a key or
     * a sign-in the route does not name is never used, whatever the environment holds.
     * <ul>
     *   <li>a server with the shim: {@code muse-subscription} through the shim's account policy, {@code muse-key} the key
     *       on stdin;</li>
     *   <li>the host's Magician picks in charge: their helper chain's Muse entries (each skipped while its
     *       window is spent, by the same decision as the wish workers'), only those the steps name;</li>
     *   <li>otherwise: {@code muse-key} the key, {@code muse-subscription} Muse Code's own login (with no Muse key in
     *       its environment, so the login is what pays).</li>
     * </ul>
     */
    private static Answer run(String systemPrompt, String userText, String strictSchemaJson, String modelId,
                              String effort, long timeoutMs, List<String> images, List<String> flags,
                              List<HelperRoute.Step> steps) {
        List<HelperRoute.Step> museSteps = steps == null ? List.of() : HelperRoute.muse(steps);
        if (museSteps.isEmpty()) {
            return null;                         // the route names no Muse step: no Muse call of any kind
        }
        String bin = binary();
        if (bin == null) {
            LOGGER.debug("muse grade: no muse binary; falling back");
            return null;
        }
        String key = keySource.get();
        boolean haveKey = key != null && !key.isBlank() && !"subscription".equals(key.strip());
        long deadline = System.currentTimeMillis() + timeoutMs;
        String shim = shim();
        if (shim == null) {
            var chain = com.moddingfromamod.config.MagicianWiring.cheapChain();
            if (chain.isPresent()) {
                List<ModelFallback.Entry> muse = chain.get().stream()
                        .filter(e -> museSteps.contains(HelperRoute.Step.of(e.model().provider(), e.mode())))
                        .toList();
                return muse.isEmpty() ? null : runChain(bin, muse, systemPrompt, userText, strictSchemaJson, effort,
                        key, deadline, images, flags);
            }
        }
        for (HelperRoute.Step step : museSteps) {
            long left = deadline - System.currentTimeMillis();
            if (left < 1_000L) {
                return null;                     // no time left: the caller's next step
            }
            Answer a;
            if (step == HelperRoute.Step.MUSE_KEY) {
                if (!haveKey) {
                    continue;
                }
                a = attempt(bin, null, systemPrompt, userText, strictSchemaJson, modelId, effort, key.strip(), left,
                        null, images, flags);
            } else {
                // The server's subscription through the shim, else Muse Code's own login.
                a = attempt(bin, shim, systemPrompt, userText, strictSchemaJson, modelId, effort, null, left, null,
                        images, flags);
            }
            if (a != null) {
                return a;
            }
        }
        return null;
    }

    /** The helper chain's Muse entries in turn, inside one budget; null when none answered. */
    static Answer runChain(String bin, List<ModelFallback.Entry> chain, String systemPrompt, String userText,
                           String strictSchemaJson, String effort, String key, long deadline) {
        return runChain(bin, chain, systemPrompt, userText, strictSchemaJson, effort, key, deadline, List.of(),
                MuseGrade.FLAGS);
    }

    static Answer runChain(String bin, List<ModelFallback.Entry> chain, String systemPrompt, String userText,
                           String strictSchemaJson, String effort, String key, long deadline, List<String> images,
                           List<String> flags) {
        for (int tries = 0; tries < chain.size(); tries++) {
            ModelFallback.Decision d = ModelFallbackRuntime.decide(ModelFallbackRuntime.Chain.HELPER, chain);
            long left = deadline - System.currentTimeMillis();
            if (d.exhausted() || left < 1_000L) {
                return null;                     // every Muse entry is skipped, or no time: the caller's Haiku is next
            }
            ModelFallback.Entry e = d.entry().get();
            if (e.paid() && (key == null || key.isBlank())) {
                return null;
            }
            StringBuilder why = new StringBuilder();
            Answer a = attempt(bin, null, systemPrompt, userText, strictSchemaJson, e.model().id(), effort,
                    e.paid() ? key.strip() : null, left, why, images, flags);
            if (a != null) {
                ModelFallbackRuntime.ok(e);
                return a;
            }
            ModelFallback.Classified c = ModelFallback.classify(why.toString());
            if (!c.failure().fallsThrough()) {
                return null;                     // the grade's own problem: the next entry would meet it too
            }
            ModelFallbackRuntime.failed(e, c);
        }
        return null;
    }

    /**
     * One {@code muse exec}. {@code shim} non-null: through the shim's subscription arm (no key);
     * else {@code key} non-null: the API key on stdin; else Muse Code's own login.
     */
    private static Answer attempt(String bin, String shim, String systemPrompt, String userText,
                                 String strictSchemaJson, String modelId, String effort, String key,
                                 long timeoutMs, StringBuilder failure, List<String> images, List<String> flags) {
        if (com.moddingfromamod.marshal.LocalOnly.refuse("a Muse call (" + modelId + ")")) {   //: a Wish Builder make is local only
            failure.append("local only: a Wish Builder make calls no model (#2789)");
            return null;
        }
        boolean keyOnStdin = shim == null && key != null;
        String arm = shim != null ? ARM_SUBSCRIPTION : keyOnStdin ? ARM_API : ARM_LOGIN;
        Path dir = null;
        Process p = null;
        long started = System.nanoTime();
        try {
            // A clean temporary directory: started anywhere with docs in it, a CLI reads them.
            dir = Files.createTempDirectory("muse-grade-");
            String schemaPath = null;
            if (strictSchemaJson != null) {
                Path sp = dir.resolve("schema.json");
                Files.writeString(sp, strictSchemaJson, StandardCharsets.UTF_8);
                schemaPath = sp.toString();
            }
            List<String> argv = MuseGrade.argv(bin, modelId, systemPrompt, effort, schemaPath, keyOnStdin, images,
                    userText, flags);
            if (shim != null) {
                // `<shim> grade --timeout S -- <everything after "muse exec">`: the shim runs the same
                // binary (MUSE_BIN below) with the same flags, on the login, or refuses to try.
                List<String> via = new ArrayList<>();
                if (!new File(shim).canExecute()) {
                    via.add("python3");
                }
                via.add(shim);
                via.addAll(List.of("grade", "--timeout", String.valueOf(Math.max(1L, timeoutMs / 1000L)), "--"));
                via.addAll(argv.subList(2, argv.size()));
                argv = via;
            }
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(dir.toFile());
            if (failure != null) {
                pb.redirectErrorStream(true);    //: the chain reads why it failed (parseRun skips non-JSON lines)
            } else {
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            }
            if (shim != null) {
                pb.environment().put("MUSE_BIN", bin);
            } else if (!keyOnStdin) {
                // Muse Code's own login: no Muse key in its environment to outrank it (HelperRoute).
                HelperRoute.museEnv(HelperRoute.Step.MUSE_SUBSCRIPTION, pb.environment());
            }
            p = pb.start();
            try (OutputStream in = p.getOutputStream()) {
                if (keyOnStdin) {
                    in.write(key.getBytes(StandardCharsets.UTF_8));
                }
            }
            StringBuilder out = new StringBuilder();
            Process proc = p;
            Thread reader = new Thread(() -> {
                try (var s = proc.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = s.read(buf)) > 0) {
                        out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                } catch (Exception ignored) {
                    // A closed pipe is how this ends normally.
                }
            }, "mfam-muse-read");
            reader.setDaemon(true);
            reader.start();
            // The shim enforces its own timeout on muse; this one is the backstop for the shim itself.
            if (!p.waitFor(timeoutMs + (shim != null ? 2_000L : 0L), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                LOGGER.info("muse grade ({}) timed out after {}ms; falling back", arm, timeoutMs);
                return null;
            }
            reader.join(2_000);
            long wall = (System.nanoTime() - started) / 1_000_000L;
            if (shim != null && p.exitValue() != 0) {
                // The shim's one status line: why the subscription was not tried, or what it met.
                String said = out.toString().strip();
                said = said.isEmpty() ? "exit " + p.exitValue() : said.substring(said.lastIndexOf('\n') + 1);
                LOGGER.info("muse grade: subscription {} ({}); {}", p.exitValue() == SHIM_SKIPPED ? "not tried"
                        : "gave no answer", said, "trying the API key");
                return null;
            }
            MuseGrade.Run r = MuseGrade.parseRun(out.toString(), modelId);
            if (r.finalText() == null && failure != null) {
                failure.append(r.failure() == null ? "" : r.failure()).append('\n').append(out);
            }
            if (r.finalText() == null) {
                LOGGER.info("muse grade ({}) gave no answer ({}); falling back", arm, r.failure());
                return null;
            }
            // The plan's flat fee is never priced as API spend (as tools/wish-cost.py's spend.subscription).
            double cost = ARM_SUBSCRIPTION.equals(arm) ? 0.0 : MuseGrade.price(modelId, r);
            return new Answer(r.finalText(), wall, cost, modelId, arm);
        } catch (Exception e) {
            LOGGER.info("muse grade ({}) could not run ({}); falling back", arm, e.toString());
            return null;
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
            if (dir != null) {
                try (Stream<Path> s = Files.walk(dir)) {
                    s.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
                } catch (Exception ignored) {
                    // A temp dir left behind is harmless.
                }
            }
        }
    }
}
