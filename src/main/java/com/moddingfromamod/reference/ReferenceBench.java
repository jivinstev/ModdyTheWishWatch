package com.moddingfromamod.reference;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>an earlier issue's instrument: how well does the reference image pipeline do on twenty subjects, with
 * the researcher held still?</b>
 *
 * <pre>./gradlew classpathFile
 * CP="$(cat build/runtime-classpath.txt)"
 * java -cp "$CP" com.moddingfromamod.reference.ReferenceBench freeze  corpus/reference-lookup
 * java -cp "$CP" com.moddingfromamod.reference.ReferenceBench measure corpus/reference-lookup STEP</pre>
 *
 * <p>Two halves, because they cost wildly different amounts. {@code freeze} runs the real
 * researcher (a Claude CLI web lookup, ~1–2 minutes and real money) ONCE per subject and saves its
 * raw reply; it refuses to overwrite a frozen subject, so "one lookup per subject" is structural
 * rather than remembered. {@code measure} replays only the deterministic half — the same
 * {@link ReferenceResearcher#measure} a wish runs — over those frozen replies, so the difference
 * between two steps is the code and nothing else, and a re-measure is free.
 *
 * <p>⚠ Run {@code measure} from a disposable VM, not a cloud session: Wikimedia answers the
 * session's shared egress with HTTP 429, which this pipeline would faithfully report as "no
 * images" (docs/REFERENCE_LOOKUP_RESULTS.md).
 *
 * <p>Scoring is against hand labels written before anything was measured
 * ({@code subjects.json}): a step is judged on CORRECT palettes, never on more images.
 */
public final class ReferenceBench {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Between subjects, so the bench is a polite client of the wikis it measures. */
    private static final long PAUSE_MS = 1500;

    private ReferenceBench() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: ReferenceBench freeze <corpusDir> [jobs]\n"
                    + "       ReferenceBench measure <corpusDir> <stepLabel> [subject...]");
            System.exit(2);
        }
        if (args[0].equals("probe")) {
            for (int i = 1; i < args.length; i++) {
                System.out.println("PROBE " + GSON.toJson(probe(args[i])));
            }
            return;
        }
        if (args[0].equals("gate")) {
            gate(Path.of(args[1]), args.length > 2 ? Integer.parseInt(args[2]) : 4);
            return;
        }
        Path dir = Path.of(args[1]);
        JsonArray subjects = JsonParser.parseString(Files.readString(dir.resolve("subjects.json")))
                .getAsJsonObject().getAsJsonArray("subjects");
        switch (args[0]) {
            case "freeze" -> freeze(dir, subjects, args.length > 2 ? Integer.parseInt(args[2]) : 4);
            case "measure" -> measure(dir, subjects, args.length > 2 ? args[2] : "unlabelled",
                    args.length > 3 ? Set.of(java.util.Arrays.copyOfRange(args, 3, args.length))
                            : Set.of());
            default -> {
                System.err.println("unknown command " + args[0]);
                System.exit(2);
            }
        }
    }

    static String slug(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }

    // ── freeze: the expensive half, once per subject ─────────────────────────────────────────

    private static void freeze(Path dir, JsonArray subjects, int jobs) throws Exception {
        Path frozen = dir.resolve(frozenDir());
        Files.createDirectories(frozen);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, jobs));
        for (JsonElement el : subjects) {
            String name = el.getAsJsonObject().get("name").getAsString();
            Path f = frozen.resolve(slug(name) + ".json");
            if (Files.exists(f)) {
                System.out.println("frozen already, NOT re-asked: " + name);
                continue;
            }
            pool.submit(() -> {
                long t0 = System.nanoTime();
                String raw = ReferenceResearcher.askRaw(name);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                ReferenceSpec spec = ReferenceSpec.parse(raw);
                JsonObject o = new JsonObject();
                o.addProperty("name", name);
                o.addProperty("frozenAt", java.time.Instant.now().toString());
                o.addProperty("researcherMs", ms);
                o.add("sources", GSON.toJsonTree(spec.sources()));
                o.add("images", GSON.toJsonTree(spec.images()));
                o.addProperty("raw", raw == null ? "" : raw);
                try {
                    Files.writeString(f, GSON.toJson(o) + "\n", StandardCharsets.UTF_8);
                } catch (Exception e) {
                    System.out.println("could not write " + f + ": " + e);
                }
                System.out.printf("froze %-20s %6d ms  %d source(s), %d image(s)%n", name, ms,
                        spec.sources().size(), spec.images().size());
            });
        }
        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.HOURS);
    }

    // ── probe: why does THIS page yield nothing? (round 2) ─────────────────────────────

    /**
     * Every step {@link WikiArtwork#forPage} takes for one URL, with the status codes and the
     * MIME types it saw — so "0 images" can be told apart as unreachable, no API, no images on
     * the page, or images of a type the filter refuses.
     */
    static Map<String, Object> probe(String url) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", url);
        try {
            java.net.URI page = java.net.URI.create(url);
            out.put("allowed", ReferenceImages.allowed(page));
            var at = WikiArtwork.locate(page);
            out.put("landedOn", at.page().toString());
            out.put("title", at.title());
            out.put("apis", at.apis());
            out.put("pageStatus", status(page.toString()));
            for (String api : at.apis()) {
                String q = WikiArtwork.queryUrl(api, at.title() == null ? "" : at.title());
                var res = WikiArtwork.getRaw(q);
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("status", res.statusCode());
                String body = res.body();
                a.put("json", body.trim().startsWith("{"));
                Map<String, Integer> mimes = new java.util.TreeMap<>();
                var m = java.util.regex.Pattern.compile("\"mime\":\"([^\"]+)\"").matcher(body);
                while (m.find()) {
                    mimes.merge(m.group(1), 1, Integer::sum);
                }
                a.put("mimes", mimes);
                if (!body.trim().startsWith("{")) {
                    a.put("bodyHead", body.substring(0, Math.min(160, body.length())));
                }
                out.put(api, a);
            }
            out.put("artAlphaOnly", WikiArtwork.forPage(url, false).size());
            out.put("artWithOpaque", WikiArtwork.forPage(url, true).size());
        } catch (Exception e) {
            out.put("error", e.toString());
        }
        return out;
    }

    private static int status(String url) {
        try {
            return WikiArtwork.getRaw(url).statusCode();
        } catch (Exception e) {
            return -1;
        }
    }

    // ── gate: what the resolver would look up, one wish per line (step 4) ──────────────

    /**
     * Runs the real {@link CharacterResolver} (one small-model call per line) and prints what it
     * would look up. Lines are wish texts; the output carries the text so a reader can check it,
     * which is why a file of a child's real wishes stays OUT of the repo and only counts go in.
     */
    private static void gate(Path wishes, int jobs) throws Exception {
        List<String> lines = Files.readAllLines(wishes).stream()
                .map(String::strip).filter(l -> !l.isEmpty()).distinct().toList();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, jobs));
        List<java.util.concurrent.Future<String>> out = new ArrayList<>();
        for (String w : lines) {
            out.add(pool.submit(() -> {
                var r = CharacterResolver.resolveSubject(w);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("wish", w);
                row.put("kind", r.map(x -> x.kind().name()).orElse("NONE"));
                row.put("name", r.map(CharacterResolver.Resolved::name).orElse(""));
                return GSON.toJson(row);
            }));
        }
        for (var f : out) {
            System.out.println("GATE " + f.get());
        }
        pool.shutdown();
    }

    // ── measure: the deterministic half, as often as you like ────────────────────────────────

    private static void measure(Path dir, JsonArray subjects, String step, Set<String> only)
            throws Exception {
        if ("0".equals(System.getenv("BENCH_CHAR_OPAQUE"))) {
            ReferenceResearcher.characterOpaqueFallback = false;    // the control arm
        }
        String trust = System.getenv("BENCH_TRUST");
        if (trust != null && !trust.isBlank()) {
            ReferenceResearcher.creatureLeaderShare = Double.parseDouble(trust);
        }
        String search = System.getenv("BENCH_COMMONS_SEARCH");
        if (search != null && search.contains("%s")) {
            CommonsCutouts.searchTemplate = search;     // an A/B arm, recorded in the step label
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonElement el : subjects) {
            JsonObject s = el.getAsJsonObject();
            String name = s.get("name").getAsString();
            if (!only.isEmpty() && !only.contains(name)) {
                continue;
            }
            Path f = dir.resolve(frozenDir()).resolve(slug(name) + ".json");
            if (!Files.exists(f)) {
                f = dir.resolve("frozen").resolve(slug(name) + ".json");   // not re-frozen: the original
            }
            if (!Files.exists(f)) {
                System.out.println("NOT FROZEN, skipped: " + name);
                continue;
            }
            JsonObject frozen = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            ReferenceSpec spec = ReferenceSpec.parse(frozen.get("raw").getAsString());
            long t0 = System.nanoTime();
            // The kind from the LABEL: the bench measures the image pipeline, and the runtime
            // classification (CharacterResolver) is scored separately against the same labels.
            SubjectKind kind = "character".equals(s.get("kind").getAsString())
                    ? SubjectKind.CHARACTER : SubjectKind.CREATURE;
            ReferenceResearcher.Measurement m = ReferenceResearcher.measure(spec, name, kind);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            Map<String, Object> row = row(step, s, spec, m, ms);
            if ("1".equals(System.getenv("BENCH_SURVEY"))) {
                // The source survey (step 5): what EACH cited page yields on its own.
                Map<String, Integer> perSource = new LinkedHashMap<>();
                for (String src : spec.sources()) {
                    perSource.put(src, WikiArtwork.forPage(src,
                            kind == SubjectKind.CREATURE).size());
                }
                row.put("perSource", perSource);
            }
            rows.add(row);
            System.out.println("ROW " + GSON.toJson(row));
            Thread.sleep(PAUSE_MS);
        }
        System.out.println("SUMMARY " + GSON.toJson(summary(step, rows)));
    }

    static Map<String, Object> row(String step, JsonObject subject, ReferenceSpec spec,
                                   ReferenceResearcher.Measurement m, long ms) {
        List<String> dominant = strings(subject.getAsJsonArray("dominant"));
        List<String> palette = strings(subject.getAsJsonArray("palette"));
        ReferencePalette.Reading r = m.reading();
        List<String> top = new ArrayList<>();
        for (var c : r.colours()) {
            top.add(c.colour());
        }
        Score sc = score(top, r.trustworthy(), dominant, palette);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("step", step);
        row.put("name", subject.get("name").getAsString());
        row.put("kind", subject.get("kind").getAsString());
        row.put("sources", spec.sources().size());
        row.put("sourceHosts", hosts(spec.sources()));
        row.put("urls", m.urls().size());
        row.put("downloaded", m.downloaded());
        row.put("usable", m.usable());
        row.put("measured", !r.isEmpty());
        row.put("trusted", r.trustworthy());
        row.put("correct", sc.correct());
        row.put("overlap", sc.overlap());
        row.put("colours", r.colours().stream()
                .map(c -> c.colour() + " " + c.percent()).toList());
        row.put("ms", ms);
        row.put("imageHosts", hosts(m.urls()));
        // PARTS (round 2): the reference carries more than colour, so score that too.
        List<String> signature = strings(subject.getAsJsonArray("signature"));
        Parts pt = parts(spec.parts(), signature);
        row.put("parts", spec.parts());
        row.put("partsRecall", pt.recall());
        row.put("partsMissed", pt.missed());
        row.put("partsInexpressible", pt.inexpressible());
        return row;
    }

    /**
     * The frozen lookups this run reads: {@code frozen/} by default, or {@code BENCH_FROZEN} —
     * how a researcher change is measured against the same image pipeline (round 2's creature
     * brief froze into its own directory, so the original freeze stays the control).
     */
    static String frozenDir() {
        String d = System.getenv("BENCH_FROZEN");
        return d == null || d.isBlank() ? "frozen" : d;
    }

    /**
     * Parts against the hand-labelled signature. {@code recall} is over the signature parts the
     * vocabulary CAN say; {@code inexpressible} is the rest — the evidence for growing it.
     */
    record Parts(double recall, List<String> missed, List<String> inexpressible) {
    }

    static Parts parts(List<String> named, List<String> signature) {
        List<String> expressible = new ArrayList<>();
        List<String> inexpressible = new ArrayList<>();
        for (String sgn : signature) {
            (ReferenceSpec.PARTS.contains(sgn) ? expressible : inexpressible).add(sgn);
        }
        List<String> missed = expressible.stream().filter(x -> !named.contains(x)).toList();
        double recall = expressible.isEmpty() ? 1.0
                : Math.round(100.0 * (expressible.size() - missed.size()) / expressible.size())
                        / 100.0;
        return new Parts(recall, missed, inexpressible);
    }

    /** What a step is scored on. {@code correct} counts only a palette a wish would USE. */
    record Score(boolean correct, double overlap) {
    }

    /**
     * Pure, so it is unit-tested: correct = a trusted reading whose colour is one a person
     * would name; overlap = the share of its top three that a person would accept at all.
     */
    static Score score(List<String> measuredTopFirst, boolean trusted, List<String> dominant,
                       List<String> palette) {
        if (!trusted || measuredTopFirst.isEmpty()) {
            return new Score(false, 0);
        }
        boolean correct = dominant.contains(measuredTopFirst.get(0));
        List<String> top3 = measuredTopFirst.subList(0, Math.min(3, measuredTopFirst.size()));
        long in = top3.stream().filter(palette::contains).count();
        return new Score(correct, Math.round(100.0 * in / top3.size()) / 100.0);
    }

    static Map<String, Object> summary(String step, List<Map<String, Object>> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("step", step);
        out.put("subjects", rows.size());
        out.put("withUrls", rows.stream().filter(r -> (int) r.get("urls") > 0).count());
        out.put("downloaded", rows.stream().mapToInt(r -> (int) r.get("downloaded")).sum());
        out.put("usable", rows.stream().mapToInt(r -> (int) r.get("usable")).sum());
        out.put("measured", rows.stream().filter(r -> (boolean) r.get("measured")).count());
        out.put("trusted", rows.stream().filter(r -> (boolean) r.get("trusted")).count());
        out.put("correct", rows.stream().filter(r -> (boolean) r.get("correct")).count());
        // The number that hurts: a palette a wish WILL use, and it is wrong.
        out.put("trustedWrong", rows.stream()
                .filter(r -> (boolean) r.get("trusted") && !(boolean) r.get("correct")).count());
        out.put("meanOverlapOfTrusted", Math.round(100 * rows.stream()
                .filter(r -> (boolean) r.get("trusted"))
                .mapToDouble(r -> (double) r.get("overlap")).average().orElse(0)) / 100.0);
        out.put("meanPartsRecall", Math.round(100 * rows.stream()
                .mapToDouble(r -> (double) r.get("partsRecall")).average().orElse(0)) / 100.0);
        long[] ms = rows.stream().mapToLong(r -> (long) r.get("ms")).sorted().toArray();
        out.put("medianMs", ms.length == 0 ? 0 : ms[ms.length / 2]);
        out.put("maxMs", ms.length == 0 ? 0 : ms[ms.length - 1]);
        return out;
    }

    static List<String> hosts(List<String> urls) {
        return ReferenceResearcher.hosts(urls);
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) {
            a.forEach(e -> out.add(e.getAsString()));
        }
        return out;
    }
}
