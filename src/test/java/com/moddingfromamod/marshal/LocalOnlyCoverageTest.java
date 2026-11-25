package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>Every way the mod reaches a model or the web is either guarded by {@link LocalOnly} or named as never running
 * inside a Wish Builder make</b> (the Apprentice's promise is "local only").
 *
 * <p>{@code BuilderMakeGameTest.everyKindIsMadeThroughTheRealTools} proves no guarded call is asked during a make. This
 * test makes sure a NEW call site cannot slip past that: a class that opens an HTTP connection, starts a process, or
 * calls a model through {@code MuseExec} or {@code HelperRoute.first} fails here until it either asks
 * {@code LocalOnly.refuse(…)} or is listed in {@link #NEVER_IN_A_MAKE} with the reason it can never run during one.
 * A listed reason is a claim a reviewer reads; keep it true.
 *
 * <p>A call handed to ANOTHER thread escapes the thread check, so a guarded class asks before the hand-off (the
 * reference researcher does).
 */
class LocalOnlyCoverageTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/moddingfromamod");

    /** What counts as reaching a model or the web. */
    private static final Pattern REACHES_OUT = Pattern.compile(
            "HttpClient\\.new|HttpURLConnection|openConnection\\(|new ProcessBuilder|"
                    + "MuseExec\\.(grade|text|describe|research)\\(|HelperRoute\\.first\\(");

    /** Must contain {@code LocalOnly.refuse(}: the place every call of its kind passes. */
    private static final Set<String> GUARDED = Set.of(
            "config/HelperRoute.java",          // every helper job's model call: the grader, the lookup, the nudge, briefs
            "agent/MuseExec.java",              // every Muse call, wherever it is asked from
            "reference/ReferenceResearcher.java",   // the web research agent, guarded before it hands off to a thread
            "reference/WikiArtwork.java",
            "reference/ReferenceImages.java",
            "reference/EmberVision.java",
            "paint/SkinFetch.java");

    /**
     * Their model calls go through {@code HelperRoute.first} (and its Muse step through {@code MuseExec}), both guarded;
     * the {@code ProcessBuilder} in each is the Claude step of that same call. Asserted to call it.
     */
    private static final Set<String> THROUGH_THE_HELPER_ROUTE = Set.of(
            "reference/CharacterResolver.java",
            "safety/SafetyGrader.java",
            "readymade/ReadyMadeNudge.java",
            "reference/BriefLooks.java");

    /** Reaches out, but can never run inside a Builder make — and why. */
    private static final Map<String, String> NEVER_IN_A_MAKE = Map.ofEntries(
            Map.entry("agent/AgentSession.java", "starts the Magician's wish agent; a Builder make has no agent"),
            Map.entry("agent/MuseWorker.java", "the Magician's Muse worker process; a Builder make has no worker"),
            Map.entry("agent/WorkerStopper.java", "stops worker processes; it calls nothing"),
            Map.entry("worker/WorkerMain.java", "the worker process's own main"),
            Map.entry("config/CliHarnessProbe.java", "the Magician setup's check that a program is installed, host-pressed"),
            Map.entry("provenance/GitSync.java",
                    "opt-in (wish_git_sync, off by default) git commit of the player's own wish files to his own repo; "
                            + "carries nothing to a model or a third-party service"),
            Map.entry("wish/CameraHang.java", "the house's own wish camera, a local Minecraft client process"),
            Map.entry("wish/CameraSupervisor.java", "starts the house's own wish camera, a local Minecraft client process"),
            Map.entry("judge/VisionJudge.java", "the evals' vision judge, run by eval tooling over stored photographs"));

    @Test
    void everyCallThatReachesOutIsGuardedOrNamedAside() throws IOException {
        List<String> unclassified = new ArrayList<>();
        List<String> unguarded = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        try (Stream<Path> files = com.moddingfromamod.testing.SourceRoots.walk(SOURCE_ROOT)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = com.moddingfromamod.testing.SourceRoots.relativize(SOURCE_ROOT, p).toString().replace('\\', '/');
                if (rel.endsWith("GameTest.java") || rel.startsWith("testing/")) {
                    continue;   // never in the jar
                }
                String src = Files.readString(p, StandardCharsets.UTF_8);
                if (!REACHES_OUT.matcher(src).find()) {
                    continue;
                }
                seen.add(rel);
                if (GUARDED.contains(rel)) {
                    continue;   // checked below, whether or not it matches (HelperRoute DEFINES first())
                } else if (THROUGH_THE_HELPER_ROUTE.contains(rel)) {
                    if (!src.contains("HelperRoute.first(")) {
                        unguarded.add(rel + ": listed as going through HelperRoute.first, and does not call it");
                    }
                } else if (!NEVER_IN_A_MAKE.containsKey(rel)) {
                    unclassified.add(rel);
                }
            }
        }
        assertTrue(unclassified.isEmpty(), "these reach a model, a process or the web, and nothing says whether a Wish "
                + "Builder make (local only, #2789) could reach them: " + unclassified
                + ". Ask LocalOnly.refuse(…) before the call and add it to GUARDED, or add it to NEVER_IN_A_MAKE with why.");
        // A guarded class is a chokepoint: it must ask, whatever the pattern sees in it.
        for (String rel : GUARDED) {
            Path p = com.moddingfromamod.testing.SourceRoots.file(SOURCE_ROOT.resolve(rel));
            if (Files.exists(p) && !Files.readString(p, StandardCharsets.UTF_8).contains("LocalOnly.refuse(")) {
                unguarded.add(rel + ": listed as GUARDED but never asks LocalOnly.refuse(…)");
            }
        }
        assertTrue(unguarded.isEmpty(), String.join("\n", unguarded));
        // A row for a class that no longer reaches out is stale; a missing file may be one the public cut leaves out.
        List<String> stale = new ArrayList<>();
        for (String rel : concat(Set.of(), THROUGH_THE_HELPER_ROUTE, NEVER_IN_A_MAKE.keySet())) {
            if (Files.exists(com.moddingfromamod.testing.SourceRoots.file(SOURCE_ROOT.resolve(rel))) && !seen.contains(rel)) {
                stale.add(rel);
            }
        }
        assertTrue(stale.isEmpty(), "listed, but no longer reaches out (delete the row): " + stale);
    }

    @Test
    void outsideAMakeNothingIsRefused() {
        assertTrue(!LocalOnly.active(), "a plain test thread is not a Builder make");
        long before = LocalOnly.refusedSoFar();
        assertTrue(!LocalOnly.refuse("a test call"), "outside a make every call is allowed");
        assertTrue(LocalOnly.refusedSoFar() == before);
    }

    @Test
    void insideAMakeEveryCallIsRefusedAndCounted() {
        long before = LocalOnly.refusedSoFar();
        boolean refused = LocalOnly.during(() -> LocalOnly.refuse("a test call"));
        assertTrue(refused, "during a make a model or web call is refused");
        assertTrue(LocalOnly.refusedSoFar() == before + 1, "and counted");
        assertTrue(!LocalOnly.active(), "the flag ends with the make");
    }

    private static Set<String> concat(Set<String> a, Set<String> b, Set<String> c) {
        Set<String> all = new TreeSet<>(a);
        all.addAll(b);
        all.addAll(c);
        return all;
    }
}
