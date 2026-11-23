package com.moddingfromamod.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SourceRoots}: a sweep sees a source file wherever the build compiles it from, and no test walks a
 * source tree by its path any more, which is how a moved file would silently leave a sweep while it stayed green.
 */
class SourceRootsTest {

    private static void write(Path f, String text) throws IOException {
        Files.createDirectories(f.getParent());
        Files.writeString(f, text, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a walk sees a file in src/ AND its twin moved under another tree, and nothing else")
    void aMovedFileIsStillWalked(@TempDir Path repo) throws IOException {
        write(repo.resolve("src/main/java/com/x/a/A.java"), "class A {}");
        write(repo.resolve("house/src/main/java/com/x/a/B.java"), "class B {}");     // a package that moved
        write(repo.resolve("house/src/main/java/com/x/other/C.java"), "class C {}"); // not under the base
        write(repo.resolve("build/src/main/java/com/x/a/D.java"), "class D {}");     // never a source tree
        write(repo.resolve(".hidden/src/main/java/com/x/a/E.java"), "class E {}");
        write(repo.resolve("house/src/test/java/com/x/a/ATest.java"), "class ATest {}");
        write(repo.resolve("levels/isle/src/main/java/com/x/a/L.java"), "class L {}");  // a level jar's tree
        write(repo.resolve("levels/README.md"), "levels itself is not a tree");

        List<String> seen;
        try (Stream<Path> s = SourceRoots.walk(repo, Path.of("src/main/java/com/x/a"))) {
            seen = s.filter(Files::isRegularFile).map(p -> repo.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
        assertEquals(List.of("house/src/main/java/com/x/a/B.java", "levels/isle/src/main/java/com/x/a/L.java",
                "src/main/java/com/x/a/A.java"), seen);

        assertEquals(List.of(repo.resolve("src/test/java"), repo.resolve("house/src/test/java")),
                SourceRoots.roots(repo, Path.of("src/test/java")));
        assertEquals(List.of(repo.resolve("src/main/java/com/x/a"), repo.resolve("house/src/main/java/com/x/a"),
                        repo.resolve("levels/isle/src/main/java/com/x/a")),
                SourceRoots.twins(repo, Path.of("src/main/java/com/x/a")));
    }

    @Test
    @DisplayName("with no other tree it is exactly src/, even for a base that does not exist")
    void noOtherTreeIsJustSrc(@TempDir Path repo) throws IOException {
        write(repo.resolve("src/main/java/A.java"), "class A {}");
        assertEquals(List.of(repo.resolve("src/main/java")), SourceRoots.roots(repo, Path.of("src/main/java")));
        assertEquals(List.of(repo.resolve("src/main/java/nope")), SourceRoots.twins(repo, Path.of("src/main/java/nope")));
        assertEquals(List.of(repo.resolve("planted")), SourceRoots.twins(repo, Path.of("planted")));
        assertEquals(List.of(repo), SourceRoots.twins(repo));   // an absolute temp tree is itself
    }

    @Test
    @DisplayName("relativize names a file by the twin that holds it, so a moved file reads as it did")
    void relativizeIsPerTwin() {
        Path base = Path.of("src/main/java/com/moddingfromamod");
        assertEquals(Path.of("wish/W.java"), SourceRoots.relativize(base, base.resolve("wish/W.java")));
        for (Path twin : SourceRoots.twins(base)) {
            assertEquals(Path.of("isle/I.java"), SourceRoots.relativize(base, twin.resolve("isle/I.java")));
        }
    }

    // ── the ratchet: no test walks a source tree by its path ─────────────────────────────────────────

    /** PublicSplitTest classifies the packages of src/ ITSELF (another tree is private as a whole, in split.json). */
    private static final List<String> ALLOWED = List.of("PublicSplitTest.java", "SourceRootsTest.java");
    private static final Pattern CONSTANT =
            Pattern.compile("Path\\s+(\\w+)\\s*=\\s*Path\\.of\\(\"src/(?:main|test)/java[^\"]*\"\\)");
    private static final String LITERAL = "Path\\.of\\(\"src/(?:main|test)/java[^\"]*\"\\)";

    static List<String> offenders(Path file, String code) {
        List<String> names = new ArrayList<>();
        Matcher m = CONSTANT.matcher(code);
        while (m.find()) {
            names.add(m.group(1));
        }
        List<String> walked = new ArrayList<>(List.of(LITERAL));
        names.forEach(n -> walked.add("\\b" + n + "\\b"));
        List<String> out = new ArrayList<>();
        for (String w : walked) {
            Matcher hit = Pattern.compile("Files\\.(walk|find|list)\\(\\s*" + w).matcher(code);
            if (hit.find()) {
                out.add(file + ": Files." + hit.group(1) + "(" + w.replace("\\b", "") + "…)");
            }
        }
        if (Pattern.compile("List\\.of\\(\\s*" + LITERAL).matcher(code).find()) {
            out.add(file + ": a List.of(Path.of(\"src/…/java\"), …) of roots");
        }
        return out;
    }

    @Test
    @DisplayName("the ratchet catches each way a test used to walk src/ by its path")
    void theRatchetFires() {
        Path f = Path.of("X.java");
        assertEquals(1, offenders(f, "try (var s = Files.walk(Path.of(\"src/main/java\"))) {}").size());
        assertEquals(1, offenders(f, "static final Path MAIN = Path.of(\"src/main/java/com/x\");\n"
                + "Files.walk(MAIN)").size());
        assertEquals(1, offenders(f, "for (Path r : List.of(Path.of(\"src/main/java\"), Path.of(\"src/test/java\")))")
                .size());
        assertEquals(0, offenders(f, "static final Path MAIN = Path.of(\"src/main/java/com/x\");\n"
                + "SourceRoots.walk(MAIN); Files.readString(MAIN.resolve(\"A.java\"))").size());
    }

    @Test
    @DisplayName("no test walks a source tree by its path (it would not see a file moved to another tree)")
    void everySweepGoesThroughSourceRoots() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path root : List.of(Path.of("src/test/java"), Path.of("src/main/java/com/moddingfromamod/test"),
                Path.of("src/main/java/com/moddingfromamod/testing"))) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> s = SourceRoots.walk(root)) {
                for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                    if (ALLOWED.contains(p.getFileName().toString())) {
                        continue;
                    }
                    found.addAll(offenders(p, Files.readString(p, StandardCharsets.UTF_8)));
                }
            }
        }
        assertTrue(found.isEmpty(), "walk the sources through testing/SourceRoots (walk, relativize, javaRootsAnd), "
                + "or a file moved to another source tree drops out of the sweep while it stays green (#2708):\n  "
                + String.join("\n  ", found));
    }
}
