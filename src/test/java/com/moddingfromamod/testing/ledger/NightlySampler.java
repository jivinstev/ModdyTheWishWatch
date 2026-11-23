package com.moddingfromamod.testing.ledger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.testing.AllTriples;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * <b>The nightly's budgeted sampler</b> (phase 2, docs/GATE_PERFORMANCE.md → "The nightly sampler"): for a check
 * over a product of factors that Gate A must never enumerate, the nightly ({@code -Pgate.nightly}) tries
 * <ol>
 *   <li>an ALL-TRIPLES set ({@link AllTriples}: every three values of every three factors together at least once), then</li>
 *   <li>random combinations, preferring ones no earlier night has tried (the sampler ledger), until</li>
 *   <li>its TIME BUDGET is spent — a fixed number of seconds, so the nightly cannot grow with the product.</li>
 * </ol>
 * Each night has a new SEED (from the date and the check), so the random half and the all-triples rows differ night to
 * night; every combination tried is written to {@code build/gate-a-sampler/} with its seed and index, and
 * {@code tools/gate-a-ledger.py sampler-fold} adds them to the accumulated ledger, so coverage grows. A failure prints its
 * seed and index, and {@code -Pgate.seed=S:I} replays exactly that combination ({@code -Pgate.seed=S}: the whole night's
 * sequence again, ignoring what earlier nights tried, so it is the same sequence whatever the ledger holds).
 */
public final class NightlySampler {

    public static final String SAMPLES = "build/gate-a-sampler";

    /** What a run did. */
    public record Outcome(String check, long seed, int tried, int triples, boolean triplesComplete, int random, int skippedSeen,
            boolean exhausted, double seconds, double budget, List<String> failures) {

        public String summary() {
            return String.format("nightly sampler: %s: seed %d, %d combinations in %.0f s of a %.0f s budget (%d all-triples rows%s, "
                    + "%d random%s%s)%s", check, seed, tried, seconds, budget, triples, triplesComplete ? "" : ", NOT all reached",
                    random, skippedSeen > 0 ? ", " + skippedSeen + " tried on an earlier night passed over" : "",
                    exhausted ? ", every combination tried" : "", failures.isEmpty() ? "" : " — " + failures.size() + " FAILED");
        }
    }

    private final String check;
    private final String testClass;
    private final List<List<String>> factors;
    private final double budgetSeconds;
    private final Long seedGiven;
    private final Integer indexGiven;
    private final long seed;
    private final Set<String> tried;
    private final Consumer<JsonObject> sink;
    private final LongSupplier clock;

    NightlySampler(String check, String testClass, List<List<String>> factors, double budgetSeconds, String seedSpec, String day,
            Set<String> tried, Consumer<JsonObject> sink, LongSupplier clock) {
        this.check = check;
        this.testClass = testClass;
        this.factors = factors;
        this.budgetSeconds = budgetSeconds;
        this.sink = sink;
        this.clock = clock;
        if (seedSpec != null && !seedSpec.isBlank()) {
            String[] p = seedSpec.trim().split(":");
            seedGiven = Long.parseLong(p[0]);
            indexGiven = p.length > 1 ? Integer.parseInt(p[1]) : null;
            seed = seedGiven;
            this.tried = Set.of();   // a replay is the same sequence whatever earlier nights tried
        } else {
            seedGiven = null;
            indexGiven = null;
            seed = daySeed(day, check);
            this.tried = tried;
        }
    }

    /** Tonight's seed for a check: the date and the check's name, so each night (and each check) differs. */
    static long daySeed(String day, String check) {
        long h = 1125899906842597L;
        for (char c : (day + "|" + check).toCharArray()) {
            h = 31 * h + c;
        }
        return Math.abs(h % 1_000_000_000L);
    }

    /**
     * The sampler a nightly test uses: {@code budgetSeconds} unless {@code -Pgate.budget=S} says otherwise, the seed from
     * {@code -Pgate.seed} or tonight's date, the accumulated ledger from {@code -Pgate.samplerLedger} (default
     * {@code ~/.moddingfromamod/gate-a-sampler/ledger.jsonl}; absent is fine: nothing has been tried).
     */
    public static NightlySampler of(Class<?> test, String method, List<List<String>> factors, double budgetSeconds) {
        String check = test.getSimpleName() + "#" + method;
        double budget = Double.parseDouble(System.getProperty("moddy.gate.budget", String.valueOf(budgetSeconds)));
        String home = System.getenv().getOrDefault("MODDINGFROMAMOD_HOME", System.getProperty("user.home") + "/.moddingfromamod");
        Path ledger = Path.of(System.getProperty("moddy.gate.samplerLedger", home + "/gate-a-sampler/ledger.jsonl"));
        Path out = Path.of(SAMPLES, ProcessHandle.current().pid() + "-" + method + ".jsonl");
        return new NightlySampler(check, test.getName(), factors, budget, System.getProperty("moddy.gate.seed"),
                LocalDate.now(ZoneOffset.UTC).toString(), triedIn(ledger, check), row -> {
                    try {
                        Files.createDirectories(out.getParent());
                        Files.writeString(out, row + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } catch (IOException e) {
                        System.out.println("nightly sampler: could not record to " + out + ": " + e);
                    }
                }, System::nanoTime);
    }

    /** The keys of {@code check} the accumulated ledger says were tried (empty when there is no ledger). */
    static Set<String> triedIn(Path ledger, String check) {
        Set<String> out = new HashSet<>();
        try {
            for (String line : Files.readAllLines(ledger)) {
                try {
                    JsonObject r = JsonParser.parseString(line).getAsJsonObject();
                    if (check.equals(r.get("check").getAsString())) {
                        out.add(r.get("key").getAsString());
                    }
                } catch (RuntimeException e) {
                    // an unreadable row is a combination we do not know was tried: it may be tried again
                }
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    public long seed() {
        return seed;
    }

    /**
     * Run {@code test} over the sequence until the budget is spent (or, for a replay of one index, on that index alone).
     * {@code test} returns the combination's problems (empty: it passed); a throw is a failure too.
     */
    public Outcome run(Function<List<String>, List<String>> test) {
        int[] sizes = new int[factors.size()];
        long product = 1;
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = factors.get(i).size();
            product = product > Long.MAX_VALUE / Math.max(1, sizes[i]) ? Long.MAX_VALUE : product * sizes[i];
        }
        List<int[]> triples = AllTriples.rows(sizes, seed);
        Random rnd = new Random(seed ^ 0x5DEECE66DL);
        long start = clock.getAsLong();
        long budget = (long) (budgetSeconds * 1e9);
        List<String> failures = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int count = 0;
        int random = 0;
        int passedOver = 0;
        int tripleRows = 0;
        boolean exhausted = false;
        long misses = 0;
        for (int index = 0; ; index++) {
            boolean replayOne = indexGiven != null;
            if (replayOne && index > indexGiven) {
                break;
            }
            if (!replayOne && clock.getAsLong() - start >= budget) {
                break;
            }
            int[] r;
            if (index < triples.size()) {
                r = triples.get(index);
            } else {
                r = new int[sizes.length];
                for (int i = 0; i < r.length; i++) {
                    r[i] = rnd.nextInt(sizes[i]);
                }
            }
            if (replayOne && index != indexGiven) {
                continue;
            }
            List<String> row = new ArrayList<>();
            for (int i = 0; i < r.length; i++) {
                row.add(factors.get(i).get(r[i]));
            }
            String key = String.join("|", row);
            if (index >= triples.size()) {
                if (seen.size() >= product) {
                    exhausted = true;
                    break;
                }
                if (!replayOne && (seen.contains(key) || this.tried.contains(key))) {
                    passedOver += this.tried.contains(key) && !seen.contains(key) ? 1 : 0;
                    seen.add(key);
                    if (++misses > 20 * Math.min(product, 1_000_000L)) {   // everything has been tried: stop spinning
                        exhausted = true;
                        break;
                    }
                    continue;
                }
                random++;
            } else {
                tripleRows++;
            }
            seen.add(key);
            count++;
            List<String> problems;
            try {
                problems = test.apply(row);
            } catch (RuntimeException | AssertionError e) {
                problems = List.of(e.toString());
            }
            JsonObject rec = new JsonObject();
            rec.addProperty("check", check);
            rec.addProperty("key", key);
            rec.addProperty("seed", seed);
            rec.addProperty("index", index);
            rec.addProperty("passed", problems.isEmpty());
            rec.addProperty("at", Instant.now().toString());
            sink.accept(rec);
            if (!problems.isEmpty()) {
                failures.add(check + ": combination #" + index + " " + row + " FAILED (seed " + seed + "): " + problems.get(0)
                        + (problems.size() > 1 ? " (+" + (problems.size() - 1) + " more)" : "")
                        + "\n  reproduce exactly: ./gradlew test --tests '" + testClass + "' -Pgate.nightly -Pgate.seed=" + seed + ":" + index);
            }
        }
        Outcome o = new Outcome(check, seed, count, tripleRows, tripleRows == triples.size() || indexGiven != null, random, passedOver,
                exhausted, (clock.getAsLong() - start) / 1e9, budgetSeconds, List.copyOf(failures));
        System.out.println(o.summary());
        return o;
    }
}
