package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.GsonBuilder;
import com.moddingfromamod.CorpusSource;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.moddingfromamod.UsesCorpus;

/**
 * <b>The shipped matcher over every wish we have</b> (S3): the 870 corpus wishes and their misspelt twins
 * ({@code corpus/apprentice/corpus.jsonl}, written by {@code tools/apprentice-backtest.py --write-corpus}).
 *
 * <ul>
 *   <li>Every make it offers is PRESSED through the real menus, passes the allowlist and lowers to tool calls: an
 *       Apprentice tile can never be a make the Builder would refuse.</li>
 *   <li>Its answers are written to {@code build/apprentice-java.json}. The back-test scores THAT file, and
 *       {@code apprentice-backtest.py --sync} holds it equal to the Python reference, wish by wish.</li>
 *   <li>It is timed: the p99 decides whether the match may run on the server thread.</li>
 * </ul>
 */
@UsesCorpus
class ApprenticeCorpusTest {

    /**
     * The corpus wishes, through {@link CorpusSource}: the real corpus here, a hand-written fixture
     * ({@code corpus/fixtures/apprentice/}) in the public repo. The counts below are measured on the real corpus and
     * held only there; the fixture has its own.
     */
    static Path corpus() {
        return CorpusSource.path("apprentice/corpus.jsonl");
    }

    private static Path sensitivity() {
        return CorpusSource.path("apprentice/sensitivity/cases.jsonl");
    }

    private static Path typos() {
        return CorpusSource.path("apprentice/typos/cases.jsonl");
    }
    static final Path ANSWERS = Path.of("build", "apprentice-java.json");

    /** The budget for one match, on whatever runs the tests. The house's CPU is no slower than a CI runner. */
    static final double P99_BUDGET_MS = 5.0;
    /** The same budget on the public fixture, where 80 matches make p99 the slowest one or two. */
    static final double FIXTURE_P99_BUDGET_MS = 20.0;
    /** Cold timed passes; the verdict is the best one (see {@code aMatchIsCheap}). */
    static final int PASSES = 3;

    private static final BuilderTree TREE = BuilderTree.of(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);

    @Test
    @DisplayName("every offered make presses through the menus and lowers; the answers are written for the back-test")
    void everyOfferIsAMakeTheBuilderCanDo() throws IOException {
        Apprentice apprentice = new Apprentice(TREE, Apprentice.english());
        JsonObject answers = new JsonObject();
        List<String> bad = new ArrayList<>();
        Set<String> pressed = new LinkedHashSet<>();
        int wishes = 0;
        for (String line : Files.readAllLines(corpus(), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject w = JsonParser.parseString(line).getAsJsonObject();
            for (String variant : List.of("clean", "misspelt")) {
                String text = w.get(variant).getAsString();
                Apprentice.Answer a = apprentice.match(text);
                assertEquals(a, apprentice.match(text), "the same words must give the same answer: " + text);
                assertTrue(a.offers().size() <= Apprentice.MOST_OFFERS, text);
                assertEquals(a.reach() == Apprentice.Reach.OUT, a.offers().isEmpty(), "tiles and reach disagree: " + text);
                JsonObject row = new JsonObject();
                JsonArray offers = new JsonArray();
                for (Apprentice.Pick p : a.offers()) {
                    offers.add(json(p));
                    if (!pressed.add(Apprentice.key(p))) {
                        continue;   // already pressed for another wish
                    }
                    try {
                        BuilderSpec spec = ApprenticeMake.spec(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, TREE, p);
                        List<String> refused = BuilderAllowlist.REVIEWED.problems(spec);
                        if (!refused.isEmpty()) {
                            bad.add(text + " -> " + Apprentice.key(p) + ": " + refused);
                        }
                        assertTrue(!BuilderLowering.lower(spec, "apprentice_test", LibraryPresets.SHIPPED).isEmpty());
                    } catch (RuntimeException e) {
                        bad.add(text + " -> " + Apprentice.key(p) + ": " + e.getMessage());
                    }
                }
                row.add("offers", offers);
                row.addProperty("reach", a.reach().id());
                JsonArray un = new JsonArray();
                a.unmatched().forEach(un::add);
                row.add("unmatched", un);
                // what each tile leaves out and the stand-ins it used, for the reference to check word for word.
                JsonArray notes = new JsonArray();
                for (Apprentice.Pick p : a.offers()) {
                    Apprentice.Notes n = apprentice.notes(text, p);
                    JsonObject one = new JsonObject();
                    JsonArray cant = new JsonArray();
                    n.cant().forEach(cant::add);
                    JsonArray near = new JsonArray();
                    for (List<String> pair : n.near()) {
                        JsonArray two = new JsonArray();
                        pair.forEach(two::add);
                        near.add(two);
                    }
                    one.add("cant", cant);
                    one.add("near", near);
                    notes.add(one);
                }
                row.add("notes", notes);
                row.addProperty("stratum", apprentice.confidence(text, a).stratum());   //
                answers.add(w.get("id").getAsString() + "|" + variant, row);
                wishes++;
            }
        }
        System.out.println("apprentice corpus: " + wishes + " wishes, " + pressed.size() + " makes pressed");
        assertTrue(wishes > (CorpusSource.real() ? 1600 : 70), "only " + wishes + " wishes: the corpus file has stopped being read");
        assertTrue(pressed.size() > (CorpusSource.real() ? 400 : 60), "only " + pressed.size() + " distinct makes offered: the matcher has gone quiet");
        assertEquals(List.of(), bad.subList(0, Math.min(10, bad.size())),
                bad.size() + " offered makes the Builder's own menus, allowlist or lowering refuse");
        Files.createDirectories(ANSWERS.getParent());
        Files.writeString(ANSWERS, new GsonBuilder().disableHtmlEscaping().create().toJson(answers) + "\n", StandardCharsets.UTF_8);
    }

    /**
     * The sensitivity corpus (a base wish plus ONE modifier), answered by the matcher that ships, for
     * {@code tools/apprentice-sensitivity.py check}: it holds this Java to the Python reference on every case and
     * the own-words tier to its floor.
     */
    @Test
    @DisplayName("the sensitivity corpus's answers are written for its check")
    void theSensitivityAnswersAreWritten() throws IOException {
        Path cases = sensitivity();
        Apprentice apprentice = new Apprentice(TREE, Apprentice.english());
        JsonObject answers = new JsonObject();
        for (String line : Files.readAllLines(cases, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject c = JsonParser.parseString(line).getAsJsonObject();
            Apprentice.Answer a = apprentice.match(c.get("text").getAsString());
            JsonObject row = new JsonObject();
            JsonArray offers = new JsonArray();
            a.offers().forEach(p -> offers.add(json(p)));
            row.add("offers", offers);
            row.addProperty("reach", a.reach().id());
            answers.add(c.get("id").getAsString(), row);
        }
        assertTrue(answers.size() > (CorpusSource.real() ? 2000 : 20), "only " + answers.size() + " cases: the sensitivity corpus has stopped being read");
        Path out = Path.of("build", "apprentice-java-sensitivity.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, new GsonBuilder().disableHtmlEscaping().create().toJson(answers) + "\n", StandardCharsets.UTF_8);
    }

    /**
     * The typo suite (docs/APPRENTICE.md §4⅞): every case clean and with each kind of typo, answered by the matcher
     * that ships, for {@code tools/apprentice-typos.py --sync} (Java equals the reference) and {@code --check}.
     */
    @Test
    @DisplayName("the typo suite's answers are written for its check")
    void theTypoAnswersAreWritten() throws IOException {
        Path cases = typos();
        Apprentice apprentice = new Apprentice(TREE, Apprentice.english());
        JsonObject answers = new JsonObject();
        for (String line : Files.readAllLines(cases, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject c = JsonParser.parseString(line).getAsJsonObject();
            List<String[]> texts = new ArrayList<>();
            texts.add(new String[] {"clean", c.get("clean").getAsString()});
            c.getAsJsonObject("typos").entrySet().forEach(e -> texts.add(new String[] {e.getKey(), e.getValue().getAsString()}));
            for (String[] t : texts) {
                Apprentice.Answer a = apprentice.match(t[1]);
                JsonObject row = new JsonObject();
                JsonArray offers = new JsonArray();
                a.offers().forEach(p -> offers.add(json(p)));
                row.add("offers", offers);
                row.addProperty("reach", a.reach().id());
                JsonArray un = new JsonArray();
                a.unmatched().forEach(un::add);
                row.add("unmatched", un);
                answers.add(c.get("id").getAsString() + "|" + t[0], row);
            }
        }
        assertTrue(answers.size() > (CorpusSource.real() ? 20000 : 150), "only " + answers.size() + " answers: the typo suite has stopped being read");
        Path out = Path.of("build", "apprentice-java-typos.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, new GsonBuilder().disableHtmlEscaping().create().toJson(answers) + "\n", StandardCharsets.UTF_8);
    }

    static final Path FIXTURE = Path.of("corpus", "apprentice", "layer", "fixture.json.gz");

    /** The trained layer's recorded answers as a reader, and its threshold: the hybrid with no model. */
    record Recorded(ClauseReader reader, double threshold) {
        static Recorded read() throws IOException {
            JsonObject f;
            try (java.io.Reader in = new java.io.InputStreamReader(new java.util.zip.GZIPInputStream(Files.newInputStream(FIXTURE)), StandardCharsets.UTF_8)) {
                f = JsonParser.parseReader(in).getAsJsonObject();
            }
            JsonObject answers = f.getAsJsonObject("answers");
            return new Recorded(clause -> {
                JsonArray rows = answers.getAsJsonArray(clause);
                if (rows == null) {
                    throw new IllegalStateException("the layer fixture has no answer for '" + clause
                            + "': python3 tools/apprentice-layer.py fixture");
                }
                List<ClauseReader.Guess> out = new ArrayList<>();
                rows.forEach(r -> out.add(new ClauseReader.Guess(r.getAsJsonArray().get(0).getAsString(), r.getAsJsonArray().get(1).getAsDouble())));
                return out;
            }, f.get("threshold").getAsDouble());
        }
    }

    /**
     * The hybrid (keywords + the trained layer) over the sensitivity corpus AND every corpus wish, from the
     * layer's recorded answers. Every make it offers is pressed through the real menus like any other tile, and its
     * answers are written for {@code tools/apprentice-sensitivity.py check --java-hybrid}, which holds this Java to
     * the Python reference on every case and the layer to its floors.
     */
    @Test
    @DisplayName("with the trained layer: every offered make still presses and lowers; the answers are written for the check")
    void theHybridAnswersAreWritten() throws IOException {
        // ~110 s over the whole corpus, so not in Gate A. tools/apprentice-gate.sh runs it (-Pgate.apprentice,
        // and it reads the file this writes), as does the nightly; Gate A keeps the keyword answers above.
        org.junit.jupiter.api.Assumptions.assumeTrue(com.moddingfromamod.testing.GateTier.apprentice(),
                "the trained layer over the whole corpus runs in tools/apprentice-gate.sh and the nightly (#2222)");
        Recorded rec = Recorded.read();
        Apprentice apprentice = new Apprentice(TREE, Apprentice.english());
        List<String[]> wishes = new ArrayList<>();   // {id or null, text}
        for (String line : Files.readAllLines(sensitivity(), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                JsonObject c = JsonParser.parseString(line).getAsJsonObject();
                wishes.add(new String[] {c.get("id").getAsString(), c.get("text").getAsString()});
            }
        }
        int cases = wishes.size();
        for (String line : Files.readAllLines(corpus(), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                JsonObject w = JsonParser.parseString(line).getAsJsonObject();
                wishes.add(new String[] {null, w.get("clean").getAsString()});
                wishes.add(new String[] {null, w.get("misspelt").getAsString()});
            }
        }
        JsonObject answers = new JsonObject();
        List<String> bad = new ArrayList<>();
        Set<String> pressed = new LinkedHashSet<>();
        int guessed = 0;
        for (String[] w : wishes) {
            String text = w[1];
            Apprentice.Answer plain = apprentice.match(text);
            Apprentice.Answer a = apprentice.match(text, rec.reader(), rec.threshold());
            assertEquals(a, apprentice.match(text, rec.reader(), rec.threshold()), "the same words must give the same answer: " + text);
            assertEquals(plain, apprentice.match(text, null, rec.threshold()), "no reader is the keyword answer: " + text);
            assertTrue(a.offers().size() <= Apprentice.MOST_OFFERS, text);
            assertEquals(a.offers().size(), a.layer().size(), "every tile says what the layer supplied: " + text);
            assertEquals(plain.offers().isEmpty(), a.offers().isEmpty(), "the layer never makes a page from nothing, or empties one: " + text);
            if (a.guessed(0)) {
                guessed++;
                assertEquals(Apprentice.Reach.PART, a.reach(), "a page the layer added to always says so: " + text);
            }
            assertEquals(apprentice.wantsReader(text) || a.equals(plain), true, "wantsReader said no and the layer changed the answer: " + text);
            for (Apprentice.Pick p : a.offers()) {
                if (!pressed.add(Apprentice.key(p))) {
                    continue;
                }
                try {
                    BuilderSpec spec = ApprenticeMake.spec(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, TREE, p);
                    List<String> refused = BuilderAllowlist.REVIEWED.problems(spec);
                    if (!refused.isEmpty()) {
                        bad.add(text + " -> " + Apprentice.key(p) + ": " + refused);
                    }
                    assertTrue(!BuilderLowering.lower(spec, "apprentice_test", LibraryPresets.SHIPPED).isEmpty());
                } catch (RuntimeException e) {
                    bad.add(text + " -> " + Apprentice.key(p) + ": " + e.getMessage());
                }
            }
            if (w[0] != null) {
                JsonObject row = new JsonObject();
                JsonArray offers = new JsonArray();
                a.offers().forEach(p -> offers.add(json(p)));
                row.add("offers", offers);
                row.addProperty("reach", a.reach().id());
                JsonArray un = new JsonArray();
                a.unmatched().forEach(un::add);
                row.add("unmatched", un);
                JsonArray layer = new JsonArray();
                for (List<String> by : a.layer()) {
                    JsonArray one = new JsonArray();
                    for (String key : by) {
                        JsonArray pair = new JsonArray();
                        pair.add(key.substring(0, key.indexOf('=')));
                        pair.add(key.substring(key.indexOf('=') + 1));
                        one.add(pair);
                    }
                    layer.add(one);
                }
                row.add("layer", layer);
                answers.add(w[0], row);
            }
        }
        assertTrue(cases > (CorpusSource.real() ? 2000 : 20), "only " + cases + " cases: the sensitivity corpus has stopped being read");
        assertTrue(guessed > (CorpusSource.real() ? 200 : 0), "the layer supplied a pick on only " + guessed + " wishes: it has gone quiet");
        assertEquals(List.of(), bad.subList(0, Math.min(10, bad.size())),
                bad.size() + " makes offered with the layer that the Builder's own menus, allowlist or lowering refuse");
        Path out = Path.of("build", "apprentice-java-sensitivity-hybrid.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, new GsonBuilder().disableHtmlEscaping().create().toJson(answers) + "\n", StandardCharsets.UTF_8);
    }

    private static JsonObject json(Apprentice.Pick p) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", p.kind());
        o.addProperty("form", p.form());
        JsonObject picks = new JsonObject();
        p.picks().forEach((slot, ids) -> {
            JsonArray a = new JsonArray();
            ids.forEach(a::add);
            picks.add(slot, a);
        });
        o.add("picks", picks);
        return o;
    }

    @Test
    @DisplayName("one match costs well under the server-thread budget (p99 over the whole corpus)")
    void aMatchIsCheap() throws IOException {
        List<String> texts = new ArrayList<>();
        for (String line : Files.readAllLines(corpus(), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                JsonObject w = JsonParser.parseString(line).getAsJsonObject();
                texts.add(w.get("clean").getAsString());
                texts.add(w.get("misspelt").getAsString());
            }
        }
        long buildStart = System.nanoTime();
        Apprentice warm = new Apprentice(TREE, Apprentice.english());
        double buildMs = (System.nanoTime() - buildStart) / 1e6;
        texts.forEach(warm::match);   // warm the JIT, as a running server is
        // Timed COLD of its spelling cache (a fresh matcher), which is the worst a first wish after a start sees.
        // BEST OF THREE cold passes, each on its own fresh matcher (2026-11-15): one pass on a busy box (Gate A runs
        // two forks, plus whatever else the machine is doing) can be preempted or meet a GC pause on 1% of its
        // matches and read p99 over budget with nothing grown. A real slowdown raises EVERY pass, so the minimum
        // still catches it; scheduler noise rarely lands on all three. Each pass is still cold, never warmed.
        long[] nanos = null;
        double p99 = Double.MAX_VALUE;
        for (int pass = 0; pass < PASSES; pass++) {
            Apprentice apprentice = new Apprentice(TREE, Apprentice.english());
            long[] these = new long[texts.size()];
            for (int i = 0; i < texts.size(); i++) {
                long t0 = System.nanoTime();
                apprentice.match(texts.get(i));
                these[i] = System.nanoTime() - t0;
            }
            Arrays.sort(these);
            double passP99 = these[(int) (these.length * 0.99)] / 1e6;
            System.out.printf("apprentice perf: pass %d p99 %.3f ms%n", pass + 1, passP99);
            if (passP99 < p99) {
                p99 = passP99;
                nanos = these;
            }
        }
        double p50 = nanos[nanos.length / 2] / 1e6;
        double max = nanos[nanos.length - 1] / 1e6;
        System.out.printf("apprentice perf: %d matches, p50 %.3f ms, p99 %.3f ms, max %.3f ms; building the matcher %.1f ms%n",
                nanos.length, p50, p99, max, buildMs);
        Files.createDirectories(ANSWERS.getParent());
        Files.writeString(Path.of("build", "apprentice-perf.txt"),
                String.format("matches %d%np50_ms %.3f%np99_ms %.3f%nmax_ms %.3f%nbuild_ms %.1f%n", nanos.length, p50, p99, max, buildMs));
        // The budget is measured on the real corpus (~1,900 matches). On the fixture's ~80, p99 is all but the slowest
        // single match, so it has its own, looser budget: it still catches a matcher that grew by an order of magnitude.
        double budget = CorpusSource.real() ? P99_BUDGET_MS : FIXTURE_P99_BUDGET_MS;
        assertTrue(p99 < budget, "p99 " + p99 + " ms (the best of " + PASSES + " cold passes) is over the " + budget + " ms budget: run the match "
                + "off the server thread (it is pure), or find what grew");
    }
}
