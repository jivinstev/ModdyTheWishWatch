package com.moddingfromamod.datapack;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.GZIPOutputStream;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * <b>Compile the ready-made skeletons into the precompiled store</b>. Not a test: the second half of
 * {@code ./tools/precompile-ready-mades.sh} ({@code ./gradlew precompileReadyMades -Pskeletons=DIR}). The first half
 * runs {@code ReadyMadePrecompileGameTest} with {@code precompiled.record=DIR}, which writes every skeleton a Builder
 * or Apprentice power asked for; this compiles each one with the bundled CBScript compiler (Python, at build time
 * only), under {@link ReadyMadeSkeleton#NAMESPACE} and {@link ReadyMadeSkeleton#DESC}, and writes
 * {@code src/main/resources/precompiled/ready-mades.json.gz} and its readable {@code manifest.json}.
 *
 * <p>{@code -Pskeletons=store} recompiles the skeletons the store already holds (a compiler-only change needs no
 * GameTest run).
 */
public final class PrecompiledPacksBuild {

    private PrecompiledPacksBuild() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args[0].isBlank()) {
            throw new IllegalArgumentException("usage: PrecompiledPacksBuild <skeleton dir | store> [out dir]");
        }
        Path out = Path.of(args.length > 1 && !args[1].isBlank() ? args[1] : "src/main/resources/precompiled");
        if (!CbScriptCompiler.available()) {
            throw new IllegalStateException("the CBScript compiler cannot run here: " + CbScriptCompiler.unavailableReason());
        }
        Map<String, String> sources = new TreeMap<>();
        if ("store".equals(args[0])) {
            PrecompiledPacks.store().keySet();   // load
            sources.putAll(PrecompiledPacks.sources());
        } else {
            try (var s = Files.list(Path.of(args[0]))) {
                for (Path f : s.filter(p -> p.toString().endsWith(".cbscript")).sorted().toList()) {
                    String src = Files.readString(f, StandardCharsets.UTF_8);
                    sources.put(PrecompiledPacks.key(src), src);
                }
            }
        }
        if (sources.isEmpty()) {
            throw new IllegalStateException("no skeletons to compile in " + args[0]);
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
        Map<String, Future<Map<String, String>>> jobs = new TreeMap<>();
        for (var e : sources.entrySet()) {
            jobs.put(e.getKey(), pool.submit(() -> compileOne(e.getValue())));
        }
        JsonObject entries = new JsonObject();
        List<String> failed = new ArrayList<>();
        for (var j : jobs.entrySet()) {
            try {
                Map<String, String> files = j.getValue().get();
                JsonObject entry = new JsonObject();
                entry.addProperty("source", sources.get(j.getKey()));
                JsonArray fs = new JsonArray();
                for (var f : files.entrySet()) {
                    JsonArray pair = new JsonArray();
                    pair.add(f.getKey());
                    pair.add(f.getValue());
                    fs.add(pair);
                }
                entry.add("files", fs);
                entries.add(j.getKey(), entry);
            } catch (java.util.concurrent.ExecutionException e) {
                failed.add(j.getKey() + ": " + e.getCause().getMessage());
            }
        }
        pool.shutdown();
        if (!failed.isEmpty()) {
            throw new IllegalStateException(failed.size() + " skeleton(s) did not compile:\n" + String.join("\n", failed));
        }
        String fingerprint = PrecompiledPacks.fingerprint(Path.of("src/main/resources"));
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("fingerprint", fingerprint);
        root.add("entries", entries);
        Files.createDirectories(out);
        try (OutputStream os = Files.newOutputStream(out.resolve("ready-mades.json.gz"));
             GZIPOutputStream gz = new GZIPOutputStream(os)) {
            gz.write(root.toString().getBytes(StandardCharsets.UTF_8));
        }
        JsonObject manifest = new JsonObject();
        manifest.addProperty("_comment", "#2655: the ready-made packs compiled at build time, so no Builder or "
                + "Apprentice power needs Python. Generated: do not edit. Regenerate with "
                + "./tools/precompile-ready-mades.sh (PrecompiledFingerprintTest fails Gate A when it is stale).");
        manifest.addProperty("fingerprint", fingerprint);
        manifest.addProperty("entries", entries.size());
        Files.writeString(out.resolve("manifest.json"),
                new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(manifest) + "\n",
                StandardCharsets.UTF_8);
        System.out.println("precompiled " + entries.size() + " ready-made skeleton(s) into " + out
                + " (fingerprint " + fingerprint.substring(0, 12) + ")");
    }

    /** One skeleton through the real compiler: its files, in order. */
    static Map<String, String> compileOne(String skeletonSource) throws Exception {
        Path tmp = Files.createTempDirectory("precompile_");
        try {
            Path zip = tmp.resolve("p.zip");
            CompileResult r = CbScriptCompiler.compile(tmp, ReadyMadeSkeleton.NAMESPACE, ReadyMadeSkeleton.DESC,
                    skeletonSource, zip);
            if (!r.ok()) {
                throw new IllegalStateException(r.failureSummary());
            }
            return PrecompiledPacks.readZip(zip);
        } finally {
            try (var w = Files.walk(tmp)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
    }
}
