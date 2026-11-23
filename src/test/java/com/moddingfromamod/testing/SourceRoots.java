package com.moddingfromamod.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Where the mod's Java sources live, for a test that reads them as TEXT (a sweep, a register, a census).
 *
 * <p>The mod's main sources are not only {@code src/main/java}: a build may compile a second tree into the same jar
 * ({@code <dir>/src/main/java} at the top of the repository), and a sweep that walks {@code src/main/java} alone
 * silently stops seeing a file the day it moves there, while staying green. So a test that walks the sources goes
 * through here: {@link #walk} walks a directory under {@code src/main/java} (or {@code src/test/java}) AND its twin in
 * every other tree, and {@link #relativize} names a file relative to whichever twin holds it, so messages and keys
 * read the same wherever a file lives. {@code SourceRootsTest} fails a test that walks a source tree by its path.
 *
 * <p>The other trees are found, never named: every top-level directory with a {@code src/main/java} (or
 * {@code src/test/java}) of its own, and each {@code levels/<name>/} (a level jar). With none, this is exactly {@code src/}.
 */
public final class SourceRoots {
    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Path TEST = Path.of("src", "test", "java");
    /** one jar per level, each its own tree under {@code levels/<name>/}. */
    private static final String LEVELS = "levels";

    /** Every main Java root, {@code src/main/java} first. */
    public static final List<Path> MAIN_JAVA = roots(Path.of(""), MAIN);
    /** Every test Java root, {@code src/test/java} first. */
    public static final List<Path> TEST_JAVA = roots(Path.of(""), TEST);

    private SourceRoots() {
    }

    /** The roots of kind {@code kind} (src/main/java or src/test/java) under {@code repo}: its own first, then each
     *  top-level directory's, sorted by name. */
    public static List<Path> roots(Path repo, Path kind) {
        List<Path> out = new ArrayList<>();
        out.add(repo.resolve(kind));
        try (Stream<Path> dirs = Files.list(repo.toAbsolutePath().normalize())) {
            dirs.filter(Files::isDirectory)
                    .map(d -> d.getFileName().toString())
                    .filter(n -> !n.equals("src") && !n.equals("build") && !n.equals("run") && !n.startsWith("."))
                    .sorted()
                    .flatMap(n -> n.equals(LEVELS) ? levelTrees(repo) : Stream.of(repo.resolve(n)))
                    .map(d -> d.resolve(kind))
                    .filter(Files::isDirectory)
                    .forEach(out::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(out);
    }

    /** each level jar is a tree one level down, {@code levels/<name>/}; {@code levels} itself is not one. */
    private static Stream<Path> levelTrees(Path repo) {
        try (Stream<Path> dirs = Files.list(repo.toAbsolutePath().normalize().resolve(LEVELS))) {
            return dirs.filter(Files::isDirectory)
                    .map(d -> d.getFileName().toString())
                    .filter(n -> !n.startsWith("."))
                    .sorted()
                    .map(n -> repo.resolve(LEVELS).resolve(n))
                    .toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every main and test Java root, then {@code more} (other trees a sweep reads: tools/, docs/, a file). */
    public static List<Path> javaRootsAnd(Path... more) {
        List<Path> out = new ArrayList<>(MAIN_JAVA);
        out.addAll(TEST_JAVA);
        out.addAll(List.of(more));
        return List.copyOf(out);
    }

    /** {@code base} (a path under src/main/java or src/test/java, e.g. {@code src/main/java/com/moddingfromamod}) and
     *  its twin in every other tree that has one, {@code base} first. Any other path is just itself. */
    public static List<Path> twins(Path base) {
        return twins(Path.of(""), base);
    }

    /** {@link #twins(Path)} for a repository at {@code repo}; {@code base} is relative to it. */
    public static List<Path> twins(Path repo, Path base) {
        Path kind = base.startsWith(MAIN) ? MAIN : base.startsWith(TEST) ? TEST : null;
        if (kind == null || base.isAbsolute()) {
            return List.of(repo.resolve(base));   // not a source tree (a test's own temp tree): just itself
        }
        Path sub = kind.relativize(base);
        List<Path> out = new ArrayList<>();
        for (Path root : roots(repo, kind)) {
            Path twin = root.resolve(sub);
            if (out.isEmpty() || Files.exists(twin)) {
                out.add(twin);
            }
        }
        return out;
    }

    /** One FILE by its path under src/main/java or src/test/java ({@code src/main/java/com/moddingfromamod/x/Y.java}),
     *  wherever it lives now: the first twin that exists, else the path itself (so a missing file still fails by the
     *  name the test wrote). For a test that reads a single source file as text. */
    public static Path file(Path path) {
        for (Path twin : twins(path)) {
            if (Files.exists(twin)) {
                return twin;
            }
        }
        return path;
    }

    /** {@link #file(Path)} for a path given as a string. */
    public static Path file(String path) {
        return file(Path.of(path));
    }

    /** Files.walk over {@code base} and each of its twins, concatenated ({@code base}'s own tree first). Close it. */
    public static Stream<Path> walk(Path base) throws IOException {
        return walkAll(twins(base));
    }

    /** {@link #walk(Path)} for a repository at {@code repo}. */
    public static Stream<Path> walk(Path repo, Path base) throws IOException {
        return walkAll(twins(repo, base));
    }

    /** Files.list (one level) over {@code base} and each of its twins, concatenated. Close it. */
    public static Stream<Path> list(Path base) throws IOException {
        Stream<Path> out = Stream.empty();
        for (Path d : twins(base)) {
            if (Files.isDirectory(d)) {
                out = Stream.concat(out, Files.list(d));
            }
        }
        return out;
    }

    private static Stream<Path> walkAll(List<Path> dirs) throws IOException {
        Stream<Path> out = Stream.empty();
        for (Path d : dirs) {
            out = Stream.concat(out, Files.walk(d));
        }
        return out;
    }

    /** {@code file} relative to whichever twin of {@code base} holds it ({@code base.relativize(file)} for one in
     *  {@code base} itself), so a moved file is named as it was. */
    public static Path relativize(Path base, Path file) {
        for (Path twin : twins(base)) {
            if (file.startsWith(twin)) {
                return twin.relativize(file);
            }
        }
        return base.relativize(file);
    }
}
