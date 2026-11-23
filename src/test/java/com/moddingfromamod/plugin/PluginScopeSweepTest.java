package com.moddingfromamod.plugin;

import com.moddingfromamod.testing.SourceRoots;
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

import org.junit.jupiter.api.Test;

/**
 * <b>a unit test never fills the mod's one plug-in registry.</b> The real {@link Plugins#shared()} has no remove,
 * so a unit test that called {@code Plugins.shared().load(...)} left its plug-ins behind for every later test in
 * its JVM, and tests passed or failed by class order. A unit test gets its own registry with
 * {@code @ExtendWith(PluginScope.class)} (empty, add stubs to {@code Plugins.shared()}) or
 * {@code @ExtendWith(RealPlugins.class)} (the real plug-ins loaded).
 *
 * <p>Fails, by file and line, on any {@code src/test} file that:
 * <ul>
 *   <li>calls {@code Plugins.shared().load(}, or passes {@code Plugins.shared()} to a plug-in's {@code register(} —
 *       always (RealPlugins does the loading);</li>
 *   <li>calls {@code Plugins.shared().add(} or {@code .hideForTest(} without one of the two extensions on the class.</li>
 * </ul>
 */
class PluginScopeSweepTest {

    private static final Path ROOT = Path.of("src/test/java");
    private static final Pattern LOAD = Pattern.compile("Plugins\\.shared\\(\\)\\s*\\.\\s*load\\s*\\(|"
            + "register\\s*\\(\\s*(?:com\\.moddingfromamod\\.plugin\\.)?Plugins\\.shared\\(\\)");
    private static final Pattern FILL = Pattern.compile("Plugins\\.shared\\(\\)\\s*\\.\\s*(?:add|hideForTest)\\s*\\(");
    private static final Pattern SCOPED = Pattern.compile("@(?:org\\.junit\\.jupiter\\.api\\.extension\\.)?ExtendWith\\s*\\("
            + "[^)]*\\b(?:PluginScope|RealPlugins)\\.class");

    @Test
    void noUnitTestLoadsOrFillsTheRealPluginRegistry() throws IOException {
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = SourceRoots.walk(ROOT)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                if (f.getFileName().toString().equals("PluginScopeSweepTest.java")) {
                    continue;   // its own patterns
                }
                String src = Files.readString(f, StandardCharsets.UTF_8);
                boolean scoped = SCOPED.matcher(src).find();
                scan(f, src, LOAD, "fills the mod's one registry with the real plug-ins: use @ExtendWith(RealPlugins.class)",
                        bad);
                if (!scoped) {
                    scan(f, src, FILL, "changes the mod's one registry: put @ExtendWith(PluginScope.class) on the class",
                            bad);
                }
            }
        }
        assertTrue(bad.isEmpty(), "unit tests that leave plug-ins behind for the next test in their JVM (#2476):\n  "
                + String.join("\n  ", bad));
    }

    private static void scan(Path f, String src, Pattern p, String why, List<String> bad) {
        Matcher m = p.matcher(src);
        while (m.find()) {
            int line = 1 + (int) src.substring(0, m.start()).chars().filter(c -> c == '\n').count();
            bad.add(f + ":" + line + " " + why);
        }
    }
}
