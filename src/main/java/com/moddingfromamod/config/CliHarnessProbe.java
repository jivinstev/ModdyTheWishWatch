package com.moddingfromamod.config;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The real {@link HarnessProbe}: runs {@code claude} and {@code muse}. Each trivial call is the cheap model
 * asked to say one word, so a full probe costs a fraction of a cent. Started from an empty temporary directory, as
 * every other model call here, so no project file is read.
 */
public final class CliHarnessProbe implements HarnessProbe {

    static final String ASK = "Reply with the single word OK.";
    private static final long VERSION_MS = 15_000L;
    private static final long CALL_MS = 90_000L;

    @Override
    public boolean installed(SupportedModels.Harness h) {
        return (h == SupportedModels.Harness.CLAUDE_CODE ? com.moddingfromamod.agent.ClaudeCli.locate()
                : com.moddingfromamod.agent.MuseExec.locate()) != null;
    }

    /** The real Python check: every candidate, then a tiny compile with the bundled compiler. */
    @Override
    public com.moddingfromamod.datapack.PythonCheck.Verdict python() {
        return com.moddingfromamod.datapack.PythonCheck.checkNow();
    }

    @Override
    public Result probe(SupportedModels.Harness h, Function<String, String> env) {
        String bin = h == SupportedModels.Harness.CLAUDE_CODE ? com.moddingfromamod.agent.ClaudeCli.locate()
                : com.moddingfromamod.agent.MuseExec.locate();
        if (bin == null) {
            return Result.missing(h);
        }
        Run v = run(List.of(bin, "--version"), null, null, VERSION_MS);
        String version = v.out.strip().lines().findFirst().orElse("");
        if (version.length() > 40) {
            version = version.substring(0, 40);
        }
        Map<SupportedModels.Mode, String> answers = new EnumMap<>(SupportedModels.Mode.class);
        SupportedModels.Provider provider = h == SupportedModels.Harness.CLAUDE_CODE
                ? SupportedModels.Provider.ANTHROPIC : SupportedModels.Provider.MUSE;
        String key = env.apply(provider.keyVar);
        answers.put(SupportedModels.Mode.SUBSCRIPTION, call(h, bin, null));
        if (key != null && !key.isBlank()) {
            answers.put(SupportedModels.Mode.API, call(h, bin, key.strip()));
        }
        return new Result(h, true, version, answers);
    }

    /** One trivial call: "" when it answered, else why not. {@code key} null: the program's own sign-in. */
    private static String call(SupportedModels.Harness h, String bin, String key) {
        Run r;
        if (h == SupportedModels.Harness.CLAUDE_CODE) {
            List<String> argv = List.of(bin, "-p", ASK, "--model", SupportedModels.find(SupportedModels.HAIKU)
                            .orElseThrow().workerSpec(), "--tools", "", "--allowed-tools", "", "--strict-mcp-config",
                    "--max-turns", "3", "--output-format", "text");
            r = run(argv, env -> {
                com.moddingfromamod.worker.WorkerEnv.scrub(env);
                env.remove(SupportedModels.Provider.ANTHROPIC.keyVar);
                if (key != null) {
                    env.put(SupportedModels.Provider.ANTHROPIC.keyVar, key);
                }
            }, null, CALL_MS);
        } else {
            List<String> argv = com.moddingfromamod.agent.MuseGrade.argv(bin, "muse-ember-1.0", null, "low", null,
                    key != null, ASK);
            r = run(argv, null, key, CALL_MS);
        }
        if (answered(h, r)) {
            return "";
        }
        return why(h, key != null, r);
    }

    private static final java.util.regex.Pattern MUSE_OK =
            java.util.regex.Pattern.compile("\"text\"\\s*:\\s*\"\\s*ok\\.?\\s*\"", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Did the call really answer? Exit 0 AND the answer itself is the word: Claude's text output is just "OK", Muse's
     * JSON events carry {@code "text":"OK"}. Never a substring of the whole output, where an error naming a TOKEN
     * would read as an answer.
     */
    static boolean answered(SupportedModels.Harness h, Run r) {
        if (r.exit != 0) {
            return false;
        }
        if (h == SupportedModels.Harness.MUSE_CODE) {
            return MUSE_OK.matcher(r.out).find();
        }
        String last = r.out.strip().lines().reduce((a, b) -> b).orElse("").strip();
        return last.equalsIgnoreCase("ok") || last.equalsIgnoreCase("ok.");
    }

    static String why(SupportedModels.Harness h, boolean api, Run r) {
        String low = r.out.toLowerCase(Locale.ROOT);
        if (r.exit == -2) {
            return "it did not answer in time";
        }
        if (api && (low.contains("401") || low.contains("invalid") || low.contains("unauthor"))) {
            return "the key was refused";
        }
        if (!api && (low.contains("login") || low.contains("log in") || low.contains("sign in")
                || low.contains("not authenticated") || low.contains("401"))) {
            return h == SupportedModels.Harness.MUSE_CODE ? "not signed in: run `muse login`"
                    : "not signed in: run `claude` once and sign in";
        }
        String last = r.out.strip().lines().reduce((a, b) -> b).orElse("no answer");
        return last.length() > 100 ? last.substring(0, 100) + "…" : last;
    }

    record Run(int exit, String out) {
    }

    private static Run run(List<String> argv, java.util.function.Consumer<Map<String, String>> envEdit, String stdin,
                           long timeoutMs) {
        Path dir = null;
        Process p = null;
        try {
            dir = Files.createTempDirectory("moddy-probe-");
            ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(argv));
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true);
            if (envEdit != null) {
                envEdit.accept(pb.environment());
            }
            p = pb.start();
            try (OutputStream in = p.getOutputStream()) {
                if (stdin != null) {
                    in.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            }
            StringBuilder out = new StringBuilder();
            Process proc = p;
            Thread reader = new Thread(() -> {
                try (var s = proc.getInputStream()) {
                    out.append(new String(s.readAllBytes(), StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                    // the pipe closes when the process ends
                }
            }, "mfam-probe-read");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return new Run(-2, "");
            }
            reader.join(2_000L);
            return new Run(p.exitValue(), out.toString());
        } catch (Exception e) {
            if (p != null) {
                p.destroyForcibly();
            }
            return new Run(-1, e.toString());
        } finally {
            if (dir != null) {
                try (var s = Files.walk(dir)) {
                    s.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
                } catch (Exception ignored) {
                    // a temp dir left behind is harmless
                }
            }
        }
    }
}
