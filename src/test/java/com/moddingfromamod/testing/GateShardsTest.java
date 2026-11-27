package com.moddingfromamod.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Everything that has to stay true for Gate B's shards to mean anything.</b>
 *
 * <p>A shard is not a scheduling detail — it decides which tests RUN. Every failure mode here is
 * silent in the direction that matters: a test lands in no shard, or in a shard whose template it
 * cannot resolve, and the gate goes green with a smaller number in it. Nobody reads the number.
 *
 * <p>So each rule below is one way that can happen, closed at Gate A where it costs seconds rather
 * than at Gate B where it costs a quarter of an hour and does not fail.
 */
class GateShardsTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/moddingfromamod");
    private static final Path DATA_ROOT = Path.of("src/main/resources/data");

    /**
     * <b>Does this test need Create?</b> It may not: Create support was removed and is
     * parked on branch {@code parked/create-trains-mc1.21.1}. A GameTest that names a
     * {@code create:} block or asks the mod list for Create could only ever skip, and a test that
     * can only skip reads as coverage. Matched on comment-stripped text with string literals KEPT,
     * since the block id is a literal.
     */
    private static final Pattern NEEDS_CREATE = Pattern.compile(
            "\\bcreateInstalled\\b|isLoaded\\(\"create\"\\)|\\bCreateReflection\\b|\"create:");

    /** Accepts every spelling in the tree, fully-qualified ones included. */
    private static final Pattern DECLARED_SHARD = Pattern.compile(
            "@GameTestHolder\\(\\s*(?:(?:[\\w.]*\\.)?GateShards\\.(\\w+)"
                    + "|(?:[\\w.]*\\.)?ModdingFromAModMod\\.MODID"
                    + "|\"([\\w_]+)\")\\s*\\)");

    /**
     * <b>CI — the PR screen. Adding one is two edits</b>: the name here and {@code @GameTestHolder(GateShards.CI)} there.
     *
     * <p>Chosen on facts rather than taste: each holder has <b>zero failures across 100+ runs</b>
     * in {@code corpus/gate-history.jsonl}, and between them they cover one path per wish kind a
     * server serves, plus the plumbing every wish crosses.
     *
     * <p>⚠ <b>The 340-case stress corpus is deliberately absent.</b> Its holder is one of only two
     * demonstrably flaky tests in 264 runs, and a screen that goes red for reasons nobody trusts is
     * a screen people stop reading. It belongs here the day it is fixed, not before.
     */
    private static final java.util.Set<String> CI = java.util.Set.of(
            "SpeciesGameTest",             // species: a creature is sculpted and persists
            "WishMobRuntimeGameTest",      // species at runtime: the rig behaves in the world
            "BuildEditGameTest",           // build: a structure is built and edited
            "WishItemGameTest",            // item: a wished item exists and is given
            "PaintUndoGameTest",           // painting: a painting is made and undone
            "TemplateServabilityGameTest", // template: a cached wish is servable
            "DatapackGameTest",            // datapack: CBScript compiles and loads
            "NoPythonGameTest",            // a real datapack wish starts no Python (the compiler is in the jar)
            "WishGameTest",                // the wish object itself, end to end
            "WishFlowGameTest",            // the lifecycle a wish moves through
            "AgentSessionGameTest",        // the agent pool starts, counts and stops
            "ChildSafetyEnforceGameTest",  // the enforcement point a child's words cross
            "McpGameTest",                 // the MCP protocol the whole agent speaks
            "ConcurrentWishGameTest",      // two wishes at once -- where a green suite lies most easily
            "PublicCommandsReadmeGameTest"); // the README lists the real command tree (Gate A's check until 1.21.11)

    /**
     * A cap.
     *
     * <p>Every member is paid for on every PR, and a screen that grows becomes the suite it was
     * built to avoid. But the binding cost here is not seconds — a GameTest run is ~20 s of boot
     * and about nothing per test — it is <b>trust</b>: each addition is another chance for the PR
     * gate to go red for a reason nobody believes.
     */
    private static final int CI_CAP = 20;

    @Test
    @DisplayName("every GameTest is in the shard the rule gives it, and none needs Create")
    void everyTestIsInTheShardTheRuleGivesIt() throws IOException {
        List<String> wrong = new ArrayList<>();
        for (Path p : gameTestSources()) {
            String file = p.getFileName().toString();
            String cls = file.substring(0, file.length() - ".java".length());
            String text = Files.readString(p, StandardCharsets.UTF_8);
            if (NEEDS_CREATE.matcher(stripComments(text)).find()) {
                wrong.add(file + " — needs Create, which this tree no longer supports (#1584); "
                        + "it is parked on branch parked/create-trains-mc1.21.1");
                continue;
            }
            String want = CI.contains(cls) ? GateShards.CI : GateShards.DEFAULT;

            Matcher m = DECLARED_SHARD.matcher(stripComments(text));
            if (!m.find()) {
                wrong.add(file + " — no readable @GameTestHolder");
                continue;
            }
            String tag = m.group(1);
            String got = tag == null ? (m.group(2) == null ? GateShards.DEFAULT : m.group(2))
                    : switch (tag) {
                        case "CI" -> GateShards.CI;
                        case "DEFAULT" -> GateShards.DEFAULT;
                        default -> "GateShards." + tag;   // a shard that no longer exists
                    };
            if (!want.equals(got)) {
                wrong.add(file + " — declares " + got + ", the rule says " + want);
            }
        }
        assertTrue(wrong.isEmpty(),
                "Gate B decides what to run from where a test lives. The rule: a GameTest named in "
                        + "GateShardsTest.CI says @GameTestHolder(GateShards.CI); every other one "
                        + "stays on ModdingFromAModMod.MODID. Fix on:\n  "
                        + String.join("\n  ", wrong));
    }

    /**
     * The convention every one of the 110 GameTest classes follows and that nothing checked.
     *
     * <p>Without {@code @PrefixGameTestTemplate(false)}, {@code GameTestHooks} prefixes the CLASS
     * NAME onto the template id, so a class asking for {@code empty_test} asks for
     * {@code mixerspeedgametest.empty_test} instead — which does not exist, and every method in
     * the class dies with <i>Missing test structure</i>. Loud, but 250–880 s into a Gate B run
     * rather than in Gate A's seconds, and the message names a template nobody wrote rather than
     * the annotation nobody added.
     */
    @Test
    @DisplayName("the CI set stays small, and every member of it exists")
    void theCiSetIsRealAndBounded() throws IOException {
        // ⚠ BOTH HALVES. A name in this set with no such class is a promotion that
        // never happened and reads as coverage; a class annotated GateShards.CI without a name
        // here is caught by the rule test above, which computes DEFAULT for it and finds CI.
        java.util.Set<String> present = new java.util.HashSet<>();
        for (Path p : gameTestSources()) {
            String f = p.getFileName().toString();
            present.add(f.substring(0, f.length() - ".java".length()));
        }
        List<String> missing = CI.stream().filter(c -> !present.contains(c)).sorted().toList();
        assertTrue(missing.isEmpty(),
                "GateShardsTest.CI names classes that do not exist: " + missing
                        + ". A name here with no class is a PR screen that reports coverage it "
                        + "does not have.");
        assertTrue(CI.size() <= CI_CAP,
                "the CI set holds " + CI.size() + " classes against a cap of " + CI_CAP
                        + ". It is a SCREEN, not the suite: every member is another chance for a "
                        + "pull request to go red for a reason nobody believes, and a screen "
                        + "people stop believing is worse than no screen.");
    }

    /**
     * on 1.21.8 our own {@code testing.gt} annotations register the suite ({@code GameTestAdapter}),
     * and the adapter never prefixes a template with the class name — so {@code @PrefixGameTestTemplate}
     * is gone with NeoForge's holder. What can still go wrong silently is a class whose {@code @GameTest}
     * methods carry OUR annotation but whose holder does not: the adapter scans for
     * {@code testing.gt.GameTestHolder} and would never see it, and Gate B would stay green with fewer tests.
     */
    @Test
    @DisplayName("every GameTest class registers through our own annotations")
    void everyTestClassUsesOurAnnotations() throws IOException {
        List<String> wrong = new ArrayList<>();
        for (Path p : gameTestSources()) {
            String code = stripComments(Files.readString(p, StandardCharsets.UTF_8));
            if (!code.contains("@GameTest(")) {
                continue;
            }
            boolean ours = code.contains("import com.moddingfromamod.testing.gt.GameTestHolder;")
                    || code.contains("@com.moddingfromamod.testing.gt.GameTestHolder");
            if (!ours || code.contains("PrefixGameTestTemplate")) {
                wrong.add(p.getFileName().toString());
            }
        }
        assertTrue(wrong.isEmpty(),
                "These classes do not register through testing.gt.GameTestHolder (or still name "
                        + "PrefixGameTestTemplate), so GameTestAdapter would never see their tests:\n  "
                        + String.join("\n  ", wrong));
    }

    /**
     * A shard is a resource namespace, so it needs its own copy of every template its tests name.
     *
     * <p>{@code GameTestHooks.getTemplateNamespace} resolves {@code template = "empty_test"}
     * against the holder's namespace, so a CI test asks for
     * {@code moddingfromamod_ci:empty_test}. Without the file the framework throws
     * <i>Missing test structure</i> — loudly, which is the good case. The bad case is a DIFFERENT
     * file: a shard quietly testing against a plot that is not the one everybody else gets.
     */
    @Test
    @DisplayName("every shard can resolve the empty_test template, byte for byte the same one")
    void everyShardShipsTheSameTestPlot() throws IOException {
        byte[] canonical = Files.readAllBytes(
                DATA_ROOT.resolve(GateShards.DEFAULT).resolve("structure/empty_test.nbt"));
        for (String ns : GateShards.ALL) {
            Path p = DATA_ROOT.resolve(ns).resolve("structure/empty_test.nbt");
            assertTrue(Files.isRegularFile(p),
                    "shard '" + ns + "' has no " + p + ", so every one of its tests fails with "
                            + "'Missing test structure'");
            assertEquals(-1, java.util.Arrays.mismatch(canonical, Files.readAllBytes(p)),
                    "shard '" + ns + "' has a DIFFERENT empty_test plot from the default shard. "
                            + "It must be a byte-for-byte copy, or the same test measures two "
                            + "different worlds depending on which shard runs it.");
        }
    }

    /**
     * build.gradle has to name the shards too, and Groovy cannot read a Java constant.
     *
     * <p>Two lists of the same thing in two languages is exactly the drift this repo keeps finding
     * the expensive way. A shard added to {@link GateShards} but not to the Gradle map is a shard
     * {@code -Pshard} rejects and, worse, one the default run silently omits — so its tests stop
     * running and the gate stays green.
     */
    @Test
    @DisplayName("build.gradle's shard map is exactly GateShards.ALL")
    void theGradleKnobKnowsTheSameShards() throws IOException {
        String gradle = Files.readString(Path.of("build.gradle"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("def shardNames = \\[([^\\]]*)\\]").matcher(gradle);
        assertTrue(m.find(), "build.gradle no longer declares `def shardNames = [...]`; the shard "
                + "knob and GateShards can now drift with nothing to catch it");
        List<String> inGradle = new ArrayList<>();
        Matcher v = Pattern.compile("'([a-z_]+)'\\s*\\]?\\s*$|:\\s*'([a-z_]+)'").matcher(m.group(1));
        while (v.find()) {
            inGradle.add(v.group(2) != null ? v.group(2) : v.group(1));
        }
        assertEquals(GateShards.ALL, inGradle,
                "build.gradle's shard namespaces and GateShards.ALL disagree. A shard missing from "
                        + "the Gradle map is omitted from the default run, and its tests stop "
                        + "running without failing anything.");
    }

    /**
     * <b>The condition that actually makes a shard unsafe, checked directly.</b>
     *
     * <p>{@code SourceRuleSweepTest} forbids clearing a registry by NAME — anything ending
     * {@code Registry}/{@code Store}/{@code Affinity}/{@code Ability}. That is a rule about
     * spelling, and it misses {@code MissingContent}, {@code ProvenanceContext},
     * {@code WishItemCatalogue} and {@code WishCapture}, all of which are process-wide statics a
     * GameTest wipes. This asks the question the name was standing in for: <b>does any OTHER
     * GameTest class read the thing being cleared?</b>
     *
     * <p>That is the whole hazard. A clear nobody else reads is a class tidying up after itself;
     * a clear somebody else reads deletes their fixture mid-flight, and the damage usually shows
     * up as their test PASSING (see docs/GATE_PERFORMANCE.md). Sharding makes it worse by
     * concentrating a topic's tests into one batch, which is exactly how the (since removed)
     * trains shard failed.
     */
    @Test
    @DisplayName("no GameTest wipes shared state that another GameTest class reads")
    void nothingWipesStateAnotherTestClassReads() throws IOException {
        Pattern clears = Pattern.compile("\\b([A-Z][A-Za-z0-9]*)\\.(?:clear|reset)\\(\\)");
        Map<Path, String> code = new LinkedHashMap<>();
        for (Path p : gameTestSources()) {
            code.put(p, stripCommentsAndStrings(Files.readString(p, StandardCharsets.UTF_8)));
        }
        List<String> shared = new ArrayList<>();
        for (Map.Entry<Path, String> e : code.entrySet()) {
            Matcher m = clears.matcher(e.getValue());
            while (m.find()) {
                String type = m.group(1);
                if (type.equals(type.toUpperCase(java.util.Locale.ROOT))) {
                    continue;   // A CONSTANT field of this very class, not another class's state.
                }
                List<String> others = code.entrySet().stream()
                        .filter(o -> !o.getKey().equals(e.getKey()))
                        .filter(o -> Pattern.compile("\\b" + type + "\\b").matcher(o.getValue()).find())
                        .map(o -> o.getKey().getFileName().toString())
                        .sorted().toList();
                if (!others.isEmpty()) {
                    shared.add(e.getKey().getFileName() + " clears " + type
                            + ", also read by " + others);
                }
            }
        }
        assertTrue(shared.isEmpty(),
                "A GameTest is wiping process-wide state that another GameTest class reads. Tests "
                        + "in a batch run interleaved on one server thread, so anything spanning "
                        + "ticks is exposed for its whole life — and the neighbour does not go "
                        + "red, it goes green having asserted nothing. Give it a seam (a "
                        + "savedOnDisk-style read, or a scoped swap like "
                        + "BuiltAbility.inIsolationForTest), or delete the clear if it was only "
                        + "tidying up:\n  " + String.join("\n  ", shared));
    }

    /** Comments removed, string literals kept — for a rule that reads a literal. */
    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", "");
    }

    /**
     * Comments AND string literals blanked, for a rule that matches code rather than prose.
     *
     * <p>This repo documents its hazards where they happened, so several files carry a comment
     * saying — correctly — that they do not do the thing a rule forbids. A regex over raw text
     * reads those as the violation they warn about.
     */
    private static String stripCommentsAndStrings(String text) {
        return stripComments(text).replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    }

    private static List<Path> gameTestSources() throws IOException {
        try (Stream<Path> walk = SourceRoots.walk(SOURCE_ROOT)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("GameTest.java"))
                    // testing/gt/GameTest.java is the annotation itself, not a test class
                    .filter(p -> !p.toString().replace('\\', '/').contains("/testing/gt/"))
                    .sorted()
                    .toList();
        }
    }
}
