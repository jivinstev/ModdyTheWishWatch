package com.moddingfromamod;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.wish.RealBuilds;
import com.moddingfromamod.wish.WishCorpus;
import org.junit.jupiter.api.Assertions;

/**
 * <b>One test, two datasets</b> (§1): where a test that reads a corpus gets its rows.
 *
 * <p>A test that reads a corpus which must stay private (a child's typed wishes, real tool calls and builds, the
 * live worlds' packs) reads it through here, by name, and never by a path of its own:
 * <ul>
 *   <li><b>{@code -Dmoddy.corpus.dir} set</b> (this repo's Gate A sets it for the {@code test} task, to {@code corpus}
 *       and {@code src/main/resources/corpus}): the REAL file, searched in that list's order. A missing one FAILS, so a
 *       deleted corpus can never pass on the fixture.</li>
 *   <li><b>not set</b> (the public repo, a contributor's machine): the PUBLIC FIXTURE, {@code corpus/fixtures/<name>},
 *       a small committed sample that is synthetic or model-written and never a player's words. It always exists, so
 *       the test never skips.</li>
 * </ul>
 *
 * <p>A test may hold the two to different numbers (a floor measured on the real corpus means nothing on twenty
 * hand-written rows): {@link #real()} says which it got. What it checks must be the same code path either way.
 *
 * <p>Gate A routes a test class that names this class to the {@code test} task (the full classpath, with the real
 * corpus), as it does for {@link PrivateCorpora}; the public cut runs it on the fixture. A test that is only about
 * private data does not belong here: it is a {@code private_tests} row in {@code docs/public-repo/split.json}.
 */
public final class CorpusSource {

    /** The system property naming the real corpus directories ({@link File#pathSeparator}-separated). */
    public static final String PROPERTY = "moddy.corpus.dir";

    /** Where the public fixtures live, relative to the repo root (the working directory of a Gate A run). */
    public static final Path FIXTURES = Path.of("corpus", "fixtures");

    private CorpusSource() {
    }

    /** True when a real corpus was named, false when the public fixture is read. */
    public static boolean real() {
        String dirs = System.getProperty(PROPERTY);
        return dirs != null && !dirs.isBlank();
    }

    /** The file a test reads for {@code name} (e.g. {@code "real-wishes.jsonl"}, or {@code "compat/spelling-1.21.5.jsonl"} under a real directory); fails, never skips, when absent. */
    public static Path path(String name) {
        if (name.contains("..") || name.startsWith("/")) {
            throw new IllegalArgumentException("a corpus name is a relative file name: " + name);
        }
        if (real()) {
            List<String> tried = new ArrayList<>();
            for (String dir : System.getProperty(PROPERTY).split(File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                Path p = Path.of(dir).resolve(name);
                if (Files.isRegularFile(p)) {
                    return p;
                }
                tried.add(p.toString());
            }
            return Assertions.fail("corpus " + name + " is missing from the real corpus (" + PROPERTY + "): tried "
                    + tried + ". It is required here; only a run with no real corpus reads the fixture.");
        }
        Path p = FIXTURES.resolve(fixtureName(name));
        if (!Files.isRegularFile(p)) {
            return Assertions.fail("no public fixture " + p + " for corpus " + name + " (#2708): every corpus a test "
                    + "reads through CorpusSource has one, so the test never skips");
        }
        return p;
    }

    /**
     * The fixture's file name for a corpus: the same, except that a leaf named {@code real-…} becomes {@code sample-…}.
     * A public file is never NAMED as a child's real data ({@code tools/leak-scan.py} refuses {@code *real-wishes*});
     * the fixture is a stand-in, and its name says so.
     */
    public static String fixtureName(String name) {
        int slash = name.lastIndexOf('/');
        String leaf = name.substring(slash + 1);
        return leaf.startsWith("real-") ? name.substring(0, slash + 1) + "sample-" + leaf.substring("real-".length()) : name;
    }

    /** The whole file, UTF-8. */
    public static String read(String name) {
        try {
            return Files.readString(path(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every line, UTF-8 (blank lines included; the caller decides what a row is). */
    public static List<String> lines(String name) {
        try {
            return Files.readAllLines(path(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The buildings the agent really built: {@link RealBuilds#load()}'s rows, parsed the same way, but read from
     * {@code real-builds.jsonl} through here rather than the classpath. Here that is the real corpus; in the public repo,
     * {@code corpus/fixtures/sample-builds.jsonl}, every shipped preset building in the same shape. A test pins a NAMED
     * real build, or a count measured on the real corpus, only under {@link #real()} (or in a private test).
     *
     * <p>A method of this class on purpose: Gate A sends a test to the half that hands it the real corpus by finding this
     * class's name in the test's own class file, so a test that read the builds through a helper of another name would
     * quietly run on the fixture.
     */
    public static List<RealBuilds.Real> realBuilds() {
        List<RealBuilds.Real> out = new ArrayList<>();
        for (WishCorpus.Case c : WishCorpus.parse(read("real-builds.jsonl"))) {
            JsonObject planJson = new JsonObject();
            planJson.addProperty("id", c.id());
            planJson.addProperty("name", c.id());
            if (c.spec() != null && c.spec().has("shapes")) {
                planJson.add("shapes", c.spec().get("shapes"));
            }
            BuildPlan plan = BuildPlan.fromJson(planJson);
            if (plan != null) {
                out.add(new RealBuilds.Real(c.id(), c.id(), "", "", plan));
            }
        }
        return List.copyOf(out);
    }
}
