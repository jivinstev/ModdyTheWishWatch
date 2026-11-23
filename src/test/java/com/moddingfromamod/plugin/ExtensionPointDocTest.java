package com.moddingfromamod.plugin;

import com.moddingfromamod.testing.SourceRoots;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.moddingfromamod.config.Setting;
import com.moddingfromamod.config.SettingsCatalog;
import org.junit.jupiter.api.Test;

/**
 * <b>Every hook the core reads is documented or declared internal</b> (epic). {@code docs/EXTENDING.md}
 * is the public repo's page for building on Moddy; this test keeps it true. It fails when:
 * <ul>
 *   <li>the core reads a hook ({@code Plugins.shared().first/all(X.class)}, {@code plugins.all(X.class)}) that has no
 *       {@code ### `X`} section under "Documented extension points" and no row under "Internal seams";</li>
 *   <li>a section or row names a hook the core no longer reads (remove it), or names one in both lists;</li>
 *   <li>a hook's javadoc does not say which it is ("A documented extension point" / "An internal seam");</li>
 *   <li>a setting in {@link SettingsCatalog} or a key of the server's configuration file is missing from the page.</li>
 * </ul>
 * A new hook's PR answers an earlier issue's three questions (still two sides? a public hook or not? documented or internal?)
 * in {@code docs/public-repo/PUBLIC_REPO_SPLIT.md} and adds its section or row here.
 */
class ExtensionPointDocTest {

    private static final Path MAIN = Path.of("src/main/java/com/moddingfromamod");
    private static final Path DOC = Path.of("docs/EXTENDING.md");
    private static final Pattern READ = Pattern.compile(
            "(?:Plugins\\.shared\\(\\)|\\bplugins)\\s*\\.\\s*(?:first|all)\\(\\s*(?:[a-z][\\w.]*\\.)?([A-Z]\\w*)\\.class\\s*\\)");
    private static final Pattern SECTION = Pattern.compile("(?m)^### `(\\w+)`\\s*$");
    private static final Pattern ROW = Pattern.compile("(?m)^\\| `(\\w+)` \\(");
    private static final Pattern SERVER_KEY = Pattern.compile("m\\.put\\(\"((?:magician|helper\\.route)\\.\\w+)\"");

    /** Hook type → the files that read it. GameTests and the test package are test code, not the core. */
    static Map<String, Set<String>> hooksRead() throws IOException {
        Map<String, Set<String>> out = new TreeMap<>();
        try (Stream<Path> files = SourceRoots.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = SourceRoots.relativize(MAIN, f).toString().replace('\\', '/');
                if (rel.endsWith("GameTest.java") || rel.startsWith("test/")) {
                    continue;
                }
                Matcher m = READ.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    out.computeIfAbsent(m.group(1), k -> new TreeSet<>()).add(rel);
                }
            }
        }
        return out;
    }

    private static String doc() throws IOException {
        return Files.readString(DOC, StandardCharsets.UTF_8);
    }

    /** The text between {@code ## heading} and the next {@code ## }. */
    private static String part(String doc, String heading) {
        int at = doc.indexOf("\n## " + heading);
        assertTrue(at >= 0, DOC + " has no '## " + heading + "' section");
        int end = doc.indexOf("\n## ", at + 4);
        return end < 0 ? doc.substring(at) : doc.substring(at, end);
    }

    private static Set<String> all(Pattern p, String text) {
        Set<String> out = new TreeSet<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static String sourceOf(String type) throws IOException {
        Pattern decl = Pattern.compile("\\bpublic\\s+(?:interface|abstract class|class)\\s+" + type + "\\b");
        try (Stream<Path> files = SourceRoots.walk(MAIN)) {
            for (Path f : files.filter(p -> p.getFileName().toString().equals(type + ".java")).toList()) {
                String s = Files.readString(f, StandardCharsets.UTF_8);
                if (decl.matcher(s).find()) {
                    return s;
                }
            }
        }
        throw new AssertionError("no source file declares the hook " + type);
    }

    @Test
    void theScanFindsTheHooksItMust() throws IOException {
        // A control: if the pattern stopped matching, every other check here would pass over nothing.
        Map<String, Set<String>> read = hooksRead();
        for (String known : List.of("WishCharge", "SettingsOverride", "ChildBranch", "WorldModuleType", "BusWiring")) {
            assertTrue(read.containsKey(known), "the scan no longer finds the core reading " + known + ": " + read);
        }
    }

    @Test
    void everyHookTheCoreReadsIsDocumentedOrDeclaredInternal() throws IOException {
        String doc = doc();
        Set<String> documented = all(SECTION, part(doc, "Documented extension points"));
        Set<String> internal = all(ROW, part(doc, "Internal seams"));
        Map<String, Set<String>> read = hooksRead();

        List<String> problems = new ArrayList<>();
        for (var e : read.entrySet()) {
            if (!documented.contains(e.getKey()) && !internal.contains(e.getKey())) {
                problems.add(e.getKey() + " (read in " + e.getValue() + ") has no '### `" + e.getKey()
                        + "`' section and no internal-seams row");
            }
        }
        for (String d : documented) {
            if (!read.containsKey(d)) problems.add("section '### `" + d + "`' names a hook the core does not read");
            if (internal.contains(d)) problems.add(d + " is both documented and internal");
        }
        for (String i : internal) {
            if (!read.containsKey(i)) problems.add("internal-seams row " + i + " names a hook the core does not read");
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems) + "\nEvery hook the core reads is a section of "
                + DOC + " (a documented extension point) or a row in its 'Internal seams' table, after answering "
                + "#2459's three questions in docs/public-repo/PUBLIC_REPO_SPLIT.md.");
    }

    @Test
    void everyHookSaysWhichItIsInItsJavadoc() throws IOException {
        String doc = doc();
        Set<String> documented = all(SECTION, part(doc, "Documented extension points"));
        Set<String> internal = all(ROW, part(doc, "Internal seams"));
        List<String> problems = new ArrayList<>();
        for (String d : documented) {
            if (!sourceOf(d).contains("A documented extension point")) {
                problems.add(d + "'s javadoc must say \"A documented extension point\"");
            }
        }
        for (String i : internal) {
            if (!sourceOf(i).contains("An internal seam")) {
                problems.add(i + "'s javadoc must say \"An internal seam\" (so nobody builds on it by accident)");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void everySettingAndServerFileKeyIsOnThePage() throws IOException {
        String settings = part(doc(), "Settings");
        List<String> missing = new ArrayList<>();
        for (Setting s : SettingsCatalog.ALL) {
            if (!settings.contains("`" + s.key() + "`")) missing.add(s.key());
        }
        String file = part(doc(), "The dedicated server's configuration file");
        Set<String> keys = all(SERVER_KEY, Files.readString(MAIN.resolve("config/ServerConfigFile.java"),
                StandardCharsets.UTF_8));
        assertTrue(keys.size() >= 5, "the server file's keys were not found in ServerConfigFile.raw(): " + keys);
        for (String k : keys) {
            if (!file.contains("`" + k + "`")) missing.add(k + " (server file)");
        }
        assertTrue(missing.isEmpty(), "keys with no row in " + DOC + ": " + missing);
    }
}
