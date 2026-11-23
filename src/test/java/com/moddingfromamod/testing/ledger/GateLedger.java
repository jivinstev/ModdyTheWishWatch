package com.moddingfromamod.testing.ledger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * <b>Gate A's fingerprint ledger: re-test only what changed</b> (phase 2, docs/GATE_PERFORMANCE.md → "The
 * fingerprint ledger").
 *
 * <p>A check that walks many VARIANTS (a building type at a size, a preset, a corpus) asks this for each one. A variant's
 * FINGERPRINT is a hash of
 * <ul>
 *   <li>its own inputs (the preset's files, the corpus file: {@link Check#variant});</li>
 *   <li>the inputs every variant of the check reads (declared once, {@link #check});</li>
 *   <li>the code the check can run: every method of ours reachable from its test method, read from the bytecode
 *       ({@link CodeReach}), plus the test classpath's jar names;</li>
 * </ul>
 * and when that fingerprint already PASSED (the committed ledger, {@code corpus/gate-a-ledger.jsonl}), the variant is
 * skipped and counted. So an untouched preset is not re-walked, an edited preset re-walks only itself, and an edited
 * {@code WalkThrough} method re-walks every variant of every check that can reach it, and nothing else.
 *
 * <p>Safe by default: a missing or unreadable ledger runs everything (never "skip because unsure"), and
 * {@code -Pgate.ledger=off} runs everything. A pass is RECORDED by every run (to {@code build/gate-a-ledger/pending/}),
 * but reaches the committed ledger only through {@code tools/gate-a-ledger.py fold}, from a clean tree, as data that is
 * never gated (the {@code tools/gate-history.py} rule).
 *
 * <p>A stale pass — a fingerprint that missed a dependency — is hunted, not hoped away: {@code -Pgate.nightly}
 * re-verifies a rotating slice of the cached passes within a budget, {@code -Pgate.reverify=all} (what a release runs)
 * re-verifies every one, and a cached pass that fails, or whose recorded facts differ, is a loud finding that names the
 * fingerprint, what it hashed, and the files that changed since it passed and are not in it.
 */
public final class GateLedger {

    public static final String LEDGER = "corpus/gate-a-ledger.jsonl";
    public static final String PENDING = "build/gate-a-ledger/pending";
    public static final String SUMMARY = "build/gate-a-ledger/summary";
    /** Bump to invalidate every fingerprint at once (a change to how one is computed). */
    static final String VERSION = "gate-a-ledger v1";
    /** The nightly re-verifies a cached pass when its rotation lands on today: about one night in this many. */
    static final int ROTATION = 7;

    public enum Reverify { NONE, SLICE, ALL }

    /** The files a fingerprint hashes: the working tree in a real run, a map in a test. Paths are repo-relative. */
    public interface Tree {
        /** The files at {@code path}: itself if a file, every file under it if a directory, none if absent. */
        List<String> list(String path);

        byte[] read(String file);
    }

    /** A recorded pass. {@code facts}: what the variant measured that a check across variants still needs. */
    public record Pass(String check, String variant, String commit, String at, List<String> facts) {
    }

    private final Map<String, Pass> passes;
    private final String unreadable;
    private final CodeReach reach;
    private final Tree files;
    private final boolean on;
    private final Reverify reverify;
    private final long sliceBudgetNanos;
    private final String day;
    private final Consumer<JsonObject> pending;
    private final Consumer<JsonObject> summary;
    private final Supplier<String[]> git;
    private final Function<String, List<String>> changedSince;
    private final LongSupplier clock;
    private final List<String> classpathJars;
    private final Map<String, Object[]> codeDigests = new ConcurrentHashMap<>();

    GateLedger(Map<String, Pass> passes, String unreadable, CodeReach reach, Tree files, boolean on,
            Reverify reverify, long sliceBudgetNanos, String day, Consumer<JsonObject> pending, Consumer<JsonObject> summary,
            Supplier<String[]> git, Function<String, List<String>> changedSince, LongSupplier clock, List<String> classpathJars) {
        this.passes = passes;
        this.unreadable = unreadable;
        this.reach = reach;
        this.files = files;
        this.on = on;
        this.reverify = reverify;
        this.sliceBudgetNanos = sliceBudgetNanos;
        this.day = day;
        this.pending = pending;
        this.summary = summary;
        this.git = git;
        this.changedSince = changedSince;
        this.clock = clock;
        this.classpathJars = classpathJars;
    }

    // ── the shared instance a Gate A run uses ───────────────────────────────────────────────────────────────────────
    private static volatile GateLedger shared;

    /**
     * The ledger this JVM uses: {@code corpus/gate-a-ledger.jsonl} (or {@code moddy.gate.ledgerFile}), the classpath's
     * bytecode, the working tree's files. System properties, all forwarded by the {@code test} task from Gradle
     * properties: {@code moddy.gate.ledger=off} runs everything; {@code moddy.gate.reverify=all} re-verifies every
     * cached pass; {@code moddy.gate.nightly} re-verifies a rotating slice within {@code moddy.gate.reverifyBudget}
     * seconds a check (default 60).
     */
    public static GateLedger shared() {
        GateLedger g = shared;
        if (g == null) {
            synchronized (GateLedger.class) {
                g = shared;
                if (g == null) {
                    g = fromSystem();
                    shared = g;
                }
            }
        }
        return g;
    }

    private static GateLedger fromSystem() {
        boolean on = !"off".equalsIgnoreCase(System.getProperty("moddy.gate.ledger", "on"));
        String rv = System.getProperty("moddy.gate.reverify", "");
        boolean nightly = Boolean.getBoolean("moddy.gate.nightly");
        Reverify reverify = "all".equalsIgnoreCase(rv) ? Reverify.ALL : "slice".equalsIgnoreCase(rv) || nightly ? Reverify.SLICE
                : Reverify.NONE;
        long budget = (long) (Double.parseDouble(System.getProperty("moddy.gate.reverifyBudget", "60")) * 1e9);
        Loaded loaded = load(Path.of(System.getProperty("moddy.gate.ledgerFile", LEDGER)));
        Map<String, Pass> passes = loaded.passes();
        String unreadable = loaded.unreadable();
        long pid = ProcessHandle.current().pid();
        Object lock = new Object();
        Consumer<JsonObject> pend = row -> append(Path.of(PENDING, pid + ".jsonl"), row, lock);
        Consumer<JsonObject> sum = row -> append(Path.of(SUMMARY, pid + ".jsonl"), row, lock);
        List<String> jars = new ArrayList<>();
        for (String e : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            if (e.endsWith(".jar")) {
                jars.add(Path.of(e).getFileName().toString());
            }
        }
        jars.sort(null);
        String[] gitState = gitState();
        return new GateLedger(passes, unreadable, CodeReach.ofClasspath(), WORKING_TREE, on, reverify, budget,
                LocalDate.now(ZoneOffset.UTC).toString(), pend, sum, () -> gitState, GateLedger::gitChangedSince,
                System::nanoTime, jars);
    }

    private static void append(Path f, JsonObject row, Object lock) {
        synchronized (lock) {
            try {
                Files.createDirectories(f.getParent());
                Files.writeString(f, row + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.out.println("gate A ledger: could not record to " + f + ": " + e);
            }
        }
    }

    /** A ledger file read: its passes, or none and why (then everything runs). */
    record Loaded(Map<String, Pass> passes, String unreadable) {
    }

    /** Never throws: a missing or unreadable ledger is an EMPTY one, and says so — never "skip because unsure". */
    static Loaded load(Path ledger) {
        try {
            return new Loaded(read(Files.readString(ledger)), null);
        } catch (IOException | RuntimeException e) {
            return new Loaded(new HashMap<>(), ledger + " could not be read (" + e.getClass().getSimpleName() + "): everything runs");
        }
    }

    /** The ledger file's passes, keyed by fingerprint. */
    static Map<String, Pass> read(String text) {
        // One pass per line, union-merged (.gitattributes): a line that cannot be read is a pass we do not know about,
        // so it only makes a run LARGER. A file with not one readable row is unreadable, and everything runs.
        Map<String, Pass> out = new HashMap<>();
        int bad = 0;
        for (String line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonObject r = JsonParser.parseString(line).getAsJsonObject();
                if (!r.has("v") || r.get("v").getAsInt() != 1) {
                    bad++;
                    continue;
                }
                List<String> facts = new ArrayList<>();
                if (r.has("facts")) {
                    r.getAsJsonArray("facts").forEach(f -> facts.add(f.getAsString()));
                }
                out.put(r.get("fp").getAsString(), new Pass(r.get("check").getAsString(), r.get("variant").getAsString(),
                        r.has("commit") ? r.get("commit").getAsString() : "", r.has("at") ? r.get("at").getAsString() : "", facts));
            } catch (RuntimeException e) {
                bad++;
            }
        }
        if (out.isEmpty() && bad > 0) {
            throw new IllegalStateException(bad + " rows and not one readable");
        }
        return out;
    }

    static final Tree WORKING_TREE = new Tree() {
        @Override
        public List<String> list(String path) {
            Path p = Path.of(path);
            if (Files.isRegularFile(p)) {
                return List.of(path);
            }
            if (!Files.isDirectory(p)) {
                return List.of();
            }
            try (Stream<Path> all = Files.walk(p)) {
                return all.filter(Files::isRegularFile).map(f -> f.toString().replace('\\', '/')).sorted().toList();
            } catch (IOException e) {
                throw new IllegalStateException("cannot list " + path + ": " + e, e);
            }
        }

        @Override
        public byte[] read(String file) {
            try {
                return Files.readAllBytes(Path.of(file));
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + file + ": " + e, e);
            }
        }
    };

    /** One hash over the files at {@code paths}, leaving out any under {@code excluded}; an absent path says so. */
    String digestPaths(List<String> paths, List<String> excluded) {
        MessageDigest md = ClassFile.sha();
        for (String path : paths) {
            List<String> all = files.list(path);
            if (all.isEmpty()) {
                md.update(("absent " + path + "\n").getBytes(StandardCharsets.UTF_8));
            }
            for (String f : all) {
                if (excluded.stream().anyMatch(x -> under(f, x))) {
                    continue;
                }
                md.update((f + "\n").getBytes(StandardCharsets.UTF_8));
                md.update(ClassFile.sha().digest(files.read(f)));
            }
        }
        return hex(md.digest());
    }

    static boolean under(String file, String path) {
        return file.equals(path) || file.startsWith(path.endsWith("/") ? path : path + "/");
    }

    private static String run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).redirectInput(ProcessBuilder.Redirect.PIPE).start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return p.waitFor() == 0 ? out : null;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    /** {commit, "true"/"false"/"" for dirty}. A run that cannot say is never folded. */
    static String[] gitState() {
        String head = run("git", "rev-parse", "HEAD");
        String status = run("git", "status", "--porcelain");
        return new String[] {head == null ? "" : head.trim(), status == null ? "" : status.isBlank() ? "false" : "true"};
    }

    static List<String> gitChangedSince(String commit) {
        if (commit == null || commit.isBlank()) {
            return List.of();
        }
        String out = run("git", "diff", "--name-only", commit);
        return out == null ? List.of() : out.lines().filter(l -> !l.isBlank()).toList();
    }

    public static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(String.format("%02x", x));
        }
        return s.toString();
    }

    // ── a check and its variants ────────────────────────────────────────────────────────────────────────────────────

    /** A check whose code is the test method {@code method} of {@code test} (every overload), reading {@code shared}. */
    public Check check(Class<?> test, String method, String... shared) {
        return check(test, method, List.of(shared), List.of());
    }

    /**
     * {@code shared}: what every variant reads; {@code excluded}: the parts of it that are some variant's OWN input (a
     * preset in the library every variant can see), so editing one preset changes only its own variant's fingerprint.
     */
    public Check check(Class<?> test, String method, List<String> shared, List<String> excluded) {
        return check(test.getSimpleName() + "#" + method, test.getName().replace('.', '/'), Set.of(method), shared, excluded);
    }

    public Check check(String id, String ownerInternal, Set<String> roots, List<String> shared, List<String> excluded) {
        Object[] code = codeDigests.computeIfAbsent(ownerInternal + roots, k -> {
            CodeReach.Reached r = reach.from(ownerInternal, roots);
            MessageDigest md = ClassFile.sha();
            md.update(r.digest(reach).getBytes(StandardCharsets.UTF_8));
            md.update(String.join("\n", classpathJars).getBytes(StandardCharsets.UTF_8));
            return new Object[] {hex(md.digest()), r};
        });
        return new Check(id, (String) code[0], (CodeReach.Reached) code[1], digestPaths(shared, excluded), shared);
    }

    public final class Check {
        public final String id;
        final String codeDigest;
        final CodeReach.Reached reached;
        final String sharedDigest;
        final List<String> shared;
        int variants;
        int ran;
        int skipped;
        int reverified;
        long reverifyNanos;
        final List<String> stale = new ArrayList<>();
        final List<String> fingerprints = new ArrayList<>();

        Check(String id, String codeDigest, CodeReach.Reached reached, String sharedDigest, List<String> shared) {
            this.id = id;
            this.codeDigest = codeDigest;
            this.reached = reached;
            this.sharedDigest = sharedDigest;
            this.shared = shared;
        }

        /** A variant whose own inputs are the files (or directories) {@code own}, repo-relative. */
        public Variant variant(String variantId, String... own) {
            return variant(variantId, List.of(own), "");
        }

        /** A variant with own input files and an inline input (a spec written in the test, say). */
        public Variant variant(String variantId, List<String> own, String inline) {
            MessageDigest md = ClassFile.sha();
            md.update((VERSION + "\n" + id + "\n" + codeDigest + "\n" + sharedDigest + "\n" + variantId + "\n" + inline + "\n"
                    + digestPaths(own, List.of())).getBytes(StandardCharsets.UTF_8));
            String fp = hex(md.digest());
            variants++;
            fingerprints.add(fp);
            return new Variant(this, variantId, fp, on ? passes.get(fp) : null, own);
        }

        /**
         * Print the summary and record it; return the stale-pass findings, which the check must fail on (add them to its
         * problems). Call once, after the last variant.
         */
        public List<String> finish() {
            String line = String.format("gate A ledger: %s: %d variants, %d ran, %d skipped (passed before)%s%s", id, variants, ran,
                    skipped, reverified > 0 ? ", " + reverified + " cached passes re-verified" : "",
                    !on ? " [ledger off]" : unreadable != null ? " [" + unreadable + "]" : "");
            System.out.println(line);
            JsonObject row = new JsonObject();
            row.addProperty("check", id);
            row.addProperty("variants", variants);
            row.addProperty("ran", ran);
            row.addProperty("skipped", skipped);
            row.addProperty("reverified", reverified);
            row.addProperty("stale", stale.size());
            summary.accept(row);
            return List.copyOf(stale);
        }

        public int skipped() {
            return skipped;
        }

        public int ran() {
            return ran;
        }

        String describeHashed(List<String> own) {
            return CodeReach.summary(reached) + "; inputs " + shared + " + " + own;
        }

        /** Files changed since {@code commit} that the fingerprint did not hash: the candidates for what it missed. */
        List<String> unhashedChanges(String commit, List<String> own) {
            Set<String> sources = reached.sourceFiles();
            List<String> out = new ArrayList<>();
            for (String f : changedSince.apply(commit)) {
                boolean hashed = sources.stream().anyMatch(s -> f.endsWith("/" + s))
                        || Stream.concat(shared.stream(), own.stream()).anyMatch(p -> under(f, p));
                if (!hashed && !f.equals(LEDGER)) {
                    out.add(f);
                }
            }
            return out;
        }
    }

    public final class Variant {
        private final Check check;
        public final String id;
        public final String fingerprint;
        private final Pass cached;
        private final List<String> own;
        private boolean reverifying;
        private long started;
        private boolean decided;
        private boolean skippedHere;

        Variant(Check check, String id, String fingerprint, Pass cached, List<String> own) {
            this.check = check;
            this.id = id;
            this.fingerprint = fingerprint;
            this.cached = cached;
            this.own = own;
        }

        /**
         * True when this variant's fingerprint already passed and it is not due for re-verification: skip it (and use
         * {@link #facts()} for whatever the check needs from it). False: run it, then call {@link #done}.
         */
        public boolean skip() {
            decided = true;
            if (cached == null) {
                check.ran++;
                started = clock.getAsLong();
                return false;
            }
            boolean due = switch (reverify) {
                case ALL -> true;
                case SLICE -> check.reverifyNanos < sliceBudgetNanos
                        && Math.floorMod(hex(ClassFile.sha().digest((fingerprint + day).getBytes(StandardCharsets.UTF_8))).hashCode(), ROTATION) == 0;
                case NONE -> false;
            };
            if (due) {
                reverifying = true;
                check.reverified++;
                started = clock.getAsLong();
                return false;
            }
            check.skipped++;
            skippedHere = true;
            return true;
        }

        /** Whether {@link #skip} said to skip it. */
        public boolean skipped() {
            return skippedHere;
        }

        /** What this variant recorded when it passed (empty when it recorded nothing). */
        public List<String> facts() {
            return cached == null ? List.of() : cached.facts();
        }

        public void done(List<String> problems) {
            done(problems, List.of());
        }

        /**
         * The variant ran: {@code problems} empty is a pass (recorded, with {@code facts}); otherwise it failed (and the
         * check fails on its own problems). A RE-VERIFIED cached pass that fails, or whose facts changed, is also a
         * stale-pass finding in {@link Check#finish}.
         */
        public void done(List<String> problems, List<String> facts) {
            if (!decided) {
                throw new IllegalStateException(check.id + " " + id + ": done() without skip()");
            }
            if (reverifying) {
                check.reverifyNanos += clock.getAsLong() - started;
                boolean factsMoved = !facts.equals(cached.facts());
                if (!problems.isEmpty() || factsMoved) {
                    List<String> missed = check.unhashedChanges(cached.commit(), own);
                    check.stale.add("STALE PASS — the fingerprint is incomplete: " + check.id + " / " + id + " passed at "
                            + (cached.commit().isEmpty() ? "?" : cached.commit().substring(0, Math.min(12, cached.commit().length())))
                            + " (" + cached.at() + ") with fingerprint " + fingerprint.substring(0, 16) + ", and on re-verification "
                            + (problems.isEmpty() ? "its facts changed: " + cached.facts() + " -> " + facts : "FAILS: " + problems.get(0))
                            + ". It hashed " + check.describeHashed(own) + ". Changed since it passed and NOT in the fingerprint: "
                            + (missed.isEmpty() ? "nothing git can see (reflection by name? a library?)" : missed)
                            + " — the missing dependency is among these: add it to the check's inputs (docs/GATE_PERFORMANCE.md → \"The fingerprint ledger\").");
                }
            }
            if (problems.isEmpty()) {
                String[] g = git.get();
                JsonObject row = new JsonObject();
                row.addProperty("fp", fingerprint);
                row.addProperty("check", check.id);
                row.addProperty("variant", id);
                row.addProperty("commit", g[0]);
                row.addProperty("dirty", g[1]);
                row.addProperty("at", Instant.ofEpochMilli(System.currentTimeMillis()).toString());
                JsonArray f = new JsonArray();
                facts.forEach(f::add);
                row.add("facts", f);
                pending.accept(row);
            }
        }
    }

    /** For tests: a ledger over given parts. */
    static GateLedger forTest(Map<String, Pass> passes, CodeReach reach, Tree files, boolean on, Reverify reverify,
            long sliceBudgetNanos, String day, List<JsonObject> pending, Function<String, List<String>> changedSince, LongSupplier clock) {
        return new GateLedger(passes, null, reach, files, on, reverify, sliceBudgetNanos, day, pending::add, r -> { }, () -> new String[] {"c0ffee", "false"},
                changedSince, clock, List.of());
    }

    /** For tests: the passes the pending rows say, as a ledger would hold them after a fold. */
    static Map<String, Pass> folded(Map<String, Pass> into, List<JsonObject> pending) {
        Map<String, Pass> out = new LinkedHashMap<>(into);
        for (JsonObject r : pending) {
            List<String> facts = new ArrayList<>();
            r.getAsJsonArray("facts").forEach(f -> facts.add(f.getAsString()));
            out.put(r.get("fp").getAsString(), new Pass(r.get("check").getAsString(), r.get("variant").getAsString(),
                    r.get("commit").getAsString(), r.get("at").getAsString(), facts));
        }
        return out;
    }
}
