package com.moddingfromamod.datapack;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Is there a working Python 3 for the power maker?</b> The Magician's agent writes its own powers in
 * CBScript, which the bundled compiler turns into a datapack using the player's Python. This finds one: it asks each
 * candidate {@link CbScriptCompiler#pythonCandidates} names for its version, recognising the two that exist but do not
 * work (the Windows Microsoft Store stub, which opens the Store instead of running, and Python 2), then compiles a
 * tiny script with the first Python 3 to prove the bundled compiler really runs on it.
 *
 * <p>Run only from the Magician setup, after the host pressed a button there ({@code MagicianOffTest}: with the
 * Magician off nothing is probed). Its answer is remembered ({@link #last}), and {@link CbScriptCompiler#python} then
 * uses the Python it proved, or none, so a power wished with no Python gets {@link #agentMessage} rather than a raw
 * compiler failure.
 *
 * <p>The decisions ({@link #classify}, {@link #find}, the words) are pure; what runs a program is a {@link Runner}
 * the caller hands in, so Gate A tests every case with no Python on the machine ({@code PythonCheckTest}).
 */
public final class PythonCheck {

    private PythonCheck() {
    }

    /** The oldest Python 3 the bundled compiler is known to run on. */
    static final int MIN_MINOR = 8;
    private static final long VERSION_MS = 10_000L;
    private static final long COMPILE_MS = 60_000L;
    private static final Pattern VERSION = Pattern.compile("Python\\s+(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    /** What a check found. */
    public enum Kind {
        /** A Python 3 that runs the bundled compiler. */
        FOUND,
        /** No Python at all. */
        MISSING,
        /** Only the Windows "python" that opens the Microsoft Store. */
        STORE_STUB,
        /** Only Python 2 (or a Python 3 older than 3.8). */
        TOO_OLD,
        /** A Python 3 that runs, on which the bundled compiler did not. */
        BROKEN
    }

    /**
     * One answer.
     *
     * @param path    the interpreter (FOUND, TOO_OLD, BROKEN), else ""
     * @param version "3.12.1", "2.7.18", or ""
     * @param detail  BROKEN: what the compiler said (one line); else ""
     */
    public record Verdict(Kind kind, String path, String version, String detail) {

        public Verdict {
            path = path == null ? "" : path;
            version = version == null ? "" : version;
            detail = detail == null ? "" : detail;
        }

        public static Verdict missing() {
            return new Verdict(Kind.MISSING, "", "", "");
        }

        public boolean works() {
            return kind == Kind.FOUND;
        }
    }

    /** What running a program said: its exit code and its output, stdout and stderr together. */
    public record Run(int exit, String out) {
    }

    /** Runs a program and waits; a test hands in its own. */
    public interface Runner {
        Run run(List<String> argv, Path dir, long timeoutMs);
    }

    // ── the decisions (pure) ───────────────────────────────────────────────────────────────────────────

    /**
     * What {@code python --version} said about one candidate. FOUND here only means "a Python 3 new enough"; the
     * compile proves the rest. A program that does not answer like a Python at all is MISSING.
     */
    public static Verdict classify(String path, Run r) {
        String out = r.out() == null ? "" : r.out();
        String low = out.toLowerCase(Locale.ROOT);
        // The Store stub: "Python was not found; run without arguments to install from the Microsoft Store, or
        // disable this shortcut from Settings > Manage App Execution Aliases." and exit code 9009.
        if (r.exit() == 9009 || low.contains("microsoft store") || low.contains("app execution alias")) {
            return new Verdict(Kind.STORE_STUB, path, "", "");
        }
        Matcher m = VERSION.matcher(out);
        if (!m.find()) {
            return new Verdict(Kind.MISSING, path, "", "");
        }
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        String version = m.group(0).substring(m.group(0).indexOf(m.group(1)));
        if (major < 3 || (major == 3 && minor < MIN_MINOR)) {
            return new Verdict(Kind.TOO_OLD, path, version, "");
        }
        return new Verdict(Kind.FOUND, path, version, "");
    }

    /**
     * Try each candidate in order: the first Python 3 whose compile of a tiny script works wins. With none, the
     * most useful thing to tell the player: a Python 3 the compiler failed on, then Python 2 (its version is known),
     * then the Store stub, then nothing at all.
     *
     * @param compile runs the bundled compiler with this interpreter: "" when it worked, else why not (one line)
     */
    public static Verdict find(List<String> candidates, Runner runner, java.util.function.Function<String, String> compile) {
        Verdict best = Verdict.missing();
        for (String c : candidates) {
            Verdict v = classify(c, runner.run(List.of(c, "--version"), null, VERSION_MS));
            if (v.kind() == Kind.FOUND) {
                String why = compile.apply(c);
                if (why.isEmpty()) {
                    return v;
                }
                v = new Verdict(Kind.BROKEN, c, v.version(), why);
            }
            if (rank(v.kind()) > rank(best.kind())) {
                best = v;
            }
        }
        return best;
    }

    private static int rank(Kind k) {
        return switch (k) {
            case MISSING -> 0;
            case STORE_STUB -> 1;
            case TOO_OLD -> 2;
            case BROKEN -> 3;
            case FOUND -> 4;
        };
    }

    /** "On the installer's first page, tick "Add python.exe to PATH"." on Windows, else "". */
    static String pathTip(boolean windows) {
        return windows ? " On the installer's first page, tick \"Add python.exe to PATH\"." : "";
    }

    /**
     * The Magician setup's result line, or empty when Python works (no line). The one thing to do, in a player's
     * words: the Magician is still on, and only powers wait for Python.
     */
    public static Optional<String> setupLine(Verdict v, boolean windows) {
        if (v == null || v.works()) {
            return Optional.empty();
        }
        String tip = pathTip(windows);
        return Optional.of(switch (v.kind()) {
            case STORE_STUB -> "✖ Python 3 isn't installed (the \"python\" on this computer only opens the Microsoft"
                    + " Store), so the Magician can't give things powers yet. Install it from python.org, then press"
                    + " the button again." + tip;
            case TOO_OLD -> "✖ This computer has Python " + v.version() + ", which is too old, so the Magician can't"
                    + " give things powers yet. Install Python 3 from python.org, then press the button again." + tip;
            case BROKEN -> "✖ Python " + v.version() + " is here but Moddy's power maker didn't run on it ("
                    + v.detail() + "), so the Magician can't give things powers yet. Install Python 3 again from"
                    + " python.org, then press the button again." + tip;
            default -> "✖ Python 3 isn't installed, so the Magician can't give things powers yet. Install it from"
                    + " python.org, then press the button again." + tip;
        });
    }

    /** The program check's line on the per-choice page: what was found, good or not. */
    public static String statusLine(Verdict v, boolean windows) {
        if (v.works()) {
            return "Python " + v.version() + ": works (the Magician can give things powers).";
        }
        return setupLine(v, windows).orElse("").replaceFirst("^✖ ", "");
    }

    /** The first words of every {@link #agentMessage}: how a tool knows the failure is the machine's, not the source's. */
    public static final String AGENT_PREFIX = "No power can be made on this computer:";

    /**
     * What the agent is told when a power needs Python and there is none that works (a tool error it can relay).
     * Never "fix the source": nothing about the source is wrong.
     */
    public static String agentMessage(Verdict v) {
        String what = v == null ? "Python 3 isn't installed"
                : switch (v.kind()) {
                    case STORE_STUB -> "Python 3 isn't installed (only the Microsoft Store shortcut is)";
                    case TOO_OLD -> "its Python (" + v.version() + ") is too old";
                    case BROKEN -> "the power maker did not run on its Python " + v.version();
                    default -> "Python 3 isn't installed";
                };
        return AGENT_PREFIX + " " + what + ", and powers (datapacks) are made with Python 3. Do not retry this tool or"
                + " rewrite the CBScript: nothing in it is wrong. Grant the rest of the wish without the power if you"
                + " can (creatures, buildings, items and pictures still work), and tell the player, in friendly words,"
                + " that this power has to wait: a grown-up can install Python 3 from python.org and then press the"
                + " Magician button on the watch again.";
    }

    /** True when {@code error} is {@link #agentMessage}'s. */
    public static boolean isAgentMessage(String error) {
        return error != null && error.startsWith(AGENT_PREFIX);
    }

    // ── what was found (remembered) ────────────────────────────────────────────────────────────────────

    private static volatile Verdict last;

    /** The last check's answer, or null when none ran this session (then the compiler looks for Python as before). */
    public static Verdict last() {
        return last;
    }

    /** A test's answer (null forgets it). */
    public static void setLastForTest(Verdict v) {
        last = v;
    }

    public static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /**
     * <b>The real check</b>: every candidate, then the tiny compile. Blocking (a second or two): never on the server
     * thread. Remembers the answer.
     */
    public static Verdict checkNow() {
        Path dir = CbScriptCompiler.cbscriptDir();
        Verdict v = find(CbScriptCompiler.pythonCandidates(System::getenv, windows()), PythonCheck::runProgram,
                python -> dir == null ? "the bundled compiler is missing" : tinyCompile(python, dir, PythonCheck::runProgram));
        last = v;
        return v;
    }

    /** The tiny script the check compiles. */
    static final String TINY = "dir \".\"\ndesc \"Moddy's Python check\"\n\nreset\n    /say ok\nend\n";

    /** Compile {@link #TINY} with {@code python}: "" when the compiler said ok and wrote the pack, else why not. */
    static String tinyCompile(String python, Path compilerDir, Runner runner) {
        Path work = null;
        try {
            work = Files.createTempDirectory("moddy-python-check-");
            Path src = work.resolve("moddy_check.cbscript");
            Files.writeString(src, TINY, StandardCharsets.UTF_8);
            Path zip = work.resolve("moddy_check.zip");
            Run r = runner.run(List.of(python, compilerDir.resolve("compile_once.py").toString(), src.toString(),
                    "--out", zip.toString()), work, COMPILE_MS);
            if (r.exit() == 0 && r.out().replace(" ", "").contains("\"ok\":true") && Files.isRegularFile(zip)) {
                return "";
            }
            if (r.exit() == -2) {
                return "it did not finish in time";
            }
            String lastLine = r.out().strip().lines().filter(l -> !l.startsWith("WARNING:"))
                    .reduce((a, b) -> b).orElse("no answer").strip();
            return lastLine.length() > 80 ? lastLine.substring(0, 80) + "…" : lastLine;
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        } finally {
            if (work != null) {
                try (var s = Files.walk(work)) {
                    s.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
                } catch (Exception ignored) {
                    // a temp dir left behind is harmless
                }
            }
        }
    }

    /** What {@code python --version} says, run for real (the compiler's no-result path asks it once). */
    static Verdict versionOf(String python) {
        return classify(python, runProgram(List.of(python, "--version"), null, VERSION_MS));
    }

    static Run runProgram(List<String> argv, Path dir, long timeoutMs) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(argv));
            if (dir != null) {
                pb.directory(dir.toFile());
            }
            pb.redirectErrorStream(true);
            pb.environment().put("CBSCRIPT_MC", com.moddingfromamod.compat.Target.MINECRAFT);
            p = pb.start();
            try (OutputStream in = p.getOutputStream()) {
                // nothing to say: a Store stub run with no input must not wait on it
            }
            StringBuilder out = new StringBuilder();
            Process proc = p;
            Thread reader = new Thread(() -> {
                try (var s = proc.getInputStream()) {
                    out.append(new String(s.readAllBytes(), StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                    // the pipe closes when the process ends
                }
            }, "mfam-python-check-read");
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
        }
    }
}
