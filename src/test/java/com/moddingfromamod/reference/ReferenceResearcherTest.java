package com.moddingfromamod.reference;

import java.nio.file.Files;
import java.nio.file.Path;

import com.moddingfromamod.agent.AgentSession;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceResearcherTest {

    /**
     * A cache MISS here starts a background prefetch that writes into this test's @TempDir after
     * the test has returned -- the race quiesceForTest exists for, and the one that failed a
     * 1.21.1 Gate A. Every test drains before JUnit deletes its directory.
     */
    @org.junit.jupiter.api.AfterEach
    void drainPrefetches() {
        ReferenceResearcher.quiesceForTest();
    }

    @Test
    @DisplayName("THE security property: the wish agent is never given web tools")
    void wishAgentStaysWebFree() {
        // The whole design rests on this. The wish agent can write datapacks that are NOT passed
        // through CommandGuard, straight into a live world -- so page text must never reach its
        // context. If someone ever adds WebFetch to allowedTools() "just to try it", the walled
        // researcher becomes decorative and this test is the thing that says so.
        String allowed = AgentSession.allowedTools();
        assertFalse(allowed.contains("WebSearch"), allowed);
        assertFalse(allowed.contains("WebFetch"), allowed);
        assertFalse(allowed.contains("Bash"), allowed);
        assertTrue(allowed.contains("mcp__wishwatch__look_up_character"),
                "the walled lookup must be the route in: " + allowed);
    }

    @Test
    @DisplayName("a cached answer is reused, so evals are reproducible and repeats are instant")
    void cacheIsHonoured(@TempDir Path dir) throws Exception {
        ReferenceResearcher.cacheDir(dir);
        Files.writeString(dir.resolve("spikor.json"), """
                {"colours":[{"colour":"green","percent":45},{"colour":"tan/orange","percent":35}],
                 "parts":["shell","horn"],"size":"LARGE","confidence":"HIGH",
                 "sources":["https://www.plumowiki.com/Spikor"]}""");

        var spec = ReferenceResearcher.lookUp("Spikor");
        assertEquals(2, spec.colours().size());
        assertEquals("green", spec.colours().get(0).colour());
        assertEquals(ReferenceSpec.Confidence.HIGH, spec.confidence());
    }

    @Test
    @DisplayName("the cache key survives how a child actually types a name")
    void cacheKeyNormalises(@TempDir Path dir) throws Exception {
        ReferenceResearcher.cacheDir(dir);
        Files.writeString(dir.resolve("chucko_mcburger.json"),
                "{\"colours\":[{\"colour\":\"red\",\"percent\":40}],\"confidence\":\"MEDIUM\"}");
        for (String typed : new String[] {"Chucko McBurger", "chucko mcburger",
                "  CHUCKO   McBurger  ", "Chucko-McBurger"}) {
            assertEquals(1, ReferenceResearcher.lookUp(typed).colours().size(),
                    "should have hit the same cache entry for: " + typed);
        }
    }

    @Test
    @DisplayName("a cached 'no canonical design' from a real lookup is honoured, not re-looked-up on every wish")
    void cachedNothingIsAnAnswer(@TempDir Path dir) throws Exception {
        ReferenceResearcher.cacheDir(dir);
        Files.writeString(dir.resolve("omega_lad.json"),
                "{\"colours\":[],\"parts\":[],\"confidence\":\"NONE\",\"lookedUpAt\":"
                        + System.currentTimeMillis() + "}");
        var cached = ReferenceResearcher.cachedOnly("Omega Lad");
        assertTrue(cached != null && cached.isEmpty(), "a genuine NONE is an answer while it is fresh");
        var spec = ReferenceResearcher.lookUp("Omega Lad");
        assertTrue(spec.isEmpty());
        assertTrue(spec.describe().contains("child's own words"));
    }

    @Test
    @DisplayName("#2550 smoke: a NONE with no lookup provenance, or older than its lifetime, reads as ABSENT")
    void aStaleOrUnprovenNoneIsAbsent(@TempDir Path dir) throws Exception {
        ReferenceResearcher.cacheDir(dir);
        // What a setup with no researcher wrote for Tabbycat, before this fix: no lookedUpAt.
        Files.writeString(dir.resolve("tabbycat.json"),
                "{\"colours\":[],\"parts\":[],\"confidence\":\"NONE\",\"sources\":[]}");
        assertEquals(null, ReferenceResearcher.cachedOnly("Tabbycat"), "never a lookup: asked again");
        long eightDays = 8L * 24 * 60 * 60 * 1000;
        Files.writeString(dir.resolve("scruff_pup.json"),
                "{\"colours\":[],\"parts\":[],\"confidence\":\"NONE\",\"lookedUpAt\":"
                        + (System.currentTimeMillis() - eightDays) + "}");
        assertEquals(null, ReferenceResearcher.cachedOnly("Scruff Pup"), "a NONE past its lifetime is retried");
        // A POSITIVE answer has no lifetime: an old entry with no provenance is still the answer.
        Files.writeString(dir.resolve("puffo.json"),
                "{\"colours\":[{\"colour\":\"pink\",\"percent\":80}],\"parts\":[\"body\"],\"confidence\":\"HIGH\"}");
        assertEquals(ReferenceSpec.Confidence.HIGH, ReferenceResearcher.cachedOnly("Puffo").confidence());

        long now = 1_000_000_000_000L;
        assertTrue(ReferenceResearcher.negativeStillGood(now - 1000, now));
        assertFalse(ReferenceResearcher.negativeStillGood(0, now), "no provenance");
        assertFalse(ReferenceResearcher.negativeStillGood(now - ReferenceResearcher.NONE_LIFETIME_MS, now));
        assertFalse(ReferenceResearcher.negativeStillGood(now + 3_600_000L, now), "a clock from the future");
    }

    @Test
    @DisplayName("every cache entry carries when a real lookup wrote it, so its NONE can expire")
    void aWrittenEntrySaysWhenItWasLookedUp(@TempDir Path dir) throws Exception {
        ReferenceResearcher.cacheDir(dir);
        ReferenceResearcher.writeCacheForTest("nobody_test", ReferenceSpec.parse(
                "{\"colours\":[],\"parts\":[],\"confidence\":\"NONE\"}"));
        String raw = Files.readString(dir.resolve("nobody_test.json"));
        assertTrue(raw.contains("\"lookedUpAt\":"), raw);
        var back = ReferenceResearcher.cachedOnly("nobody test");
        assertTrue(back != null && back.isEmpty(), "a fresh genuine NONE is honoured");
    }

    @Test
    @DisplayName("#2550 smoke: with NO researcher, a lookup writes nothing to the cache (Magician off, a Builder make)")
    void noResearcherCachesNothing(@TempDir Path dir) throws Exception {
        String before = System.getProperty("moddingfromamod.cli");
        System.setProperty("moddingfromamod.cli", "none");
        try {
            ReferenceResearcher.cacheDir(dir);
            assertTrue(ReferenceResearcher.lookUp("builder", 2, SubjectKind.CHARACTER).isEmpty());
            ReferenceResearcher.quiesceForTest();
            try (var files = Files.list(dir)) {
                assertEquals(0, files.count(), "a lookup that never happened is not an answer to keep");
            }
        } finally {
            if (before == null) {
                System.clearProperty("moddingfromamod.cli");
            } else {
                System.setProperty("moddingfromamod.cli", before);
            }
        }
    }

    @Test
    @DisplayName("#2550 smoke: the researcher's route — Claude where chosen, Muse Code on a Muse-only setup, else none")
    void theResearcherRoute() {
        var r = ReferenceResearcher.Route.class;
        var claude = List.of(com.moddingfromamod.config.HelperRoute.Step.CLAUDE_KEY);
        var muse = List.of(com.moddingfromamod.config.HelperRoute.Step.MUSE_SUBSCRIPTION);
        var both = List.of(com.moddingfromamod.config.HelperRoute.Step.MUSE_KEY,
                com.moddingfromamod.config.HelperRoute.Step.CLAUDE_SUBSCRIPTION);
        assertEquals(ReferenceResearcher.Route.CLAUDE, ReferenceResearcher.route(true, claude, true, true),
                "Claude chosen (a dedicated server; picks naming Claude): the measured Claude researcher, unchanged");
        assertEquals(ReferenceResearcher.Route.NONE, ReferenceResearcher.route(true, claude, false, true),
                "Claude chosen but no CLI: no researcher (moving the Claude path to Ember is a maintainer's decision)");
        assertEquals(ReferenceResearcher.Route.MUSE, ReferenceResearcher.route(true, muse, false, true),
                "Muse-only: Ember on Muse Code (#2514 never starts Claude Code)");
        assertEquals(ReferenceResearcher.Route.NONE, ReferenceResearcher.route(true, muse, true, false),
                "Muse-only with no muse: none, and the wish goes on");
        assertEquals(ReferenceResearcher.Route.NONE, ReferenceResearcher.route(false, claude, true, true),
                "Magician off: none");
        assertEquals(ReferenceResearcher.Route.MUSE, ReferenceResearcher.route(true, both, true, true),
                "a route naming Muse first: Muse first");
        assertEquals(ReferenceResearcher.Route.CLAUDE, ReferenceResearcher.route(true, both, true, false),
                "no muse: the route's Claude step");
        assertEquals(ReferenceResearcher.Route.NONE, ReferenceResearcher.route(true, List.of(), true, true),
                "a route of none: no researcher");
        assertEquals(3, r.getEnumConstants().length);
    }

    @Test
    @DisplayName("an unparseable reply is not an answer; a genuine NONE is")
    void whatCountsAsAnAnswer() {
        assertFalse(ReferenceSpec.isAnswer(null));
        assertFalse(ReferenceSpec.isAnswer(""));
        assertFalse(ReferenceSpec.isAnswer("I could not reach the web."));
        assertFalse(ReferenceSpec.isAnswer("{ not json"));
        assertTrue(ReferenceSpec.isAnswer("Here: {\"confidence\":\"NONE\",\"parts\":[]}"));
    }

    @Test
    @DisplayName("an empty or missing name never reaches the sandbox at all")
    void blankIsRejected(@TempDir Path dir) {
        ReferenceResearcher.cacheDir(dir);
        assertTrue(ReferenceResearcher.lookUp("").isEmpty());
        assertTrue(ReferenceResearcher.lookUp("   ").isEmpty());
        assertTrue(ReferenceResearcher.lookUp(null).isEmpty());
    }

    @Test
    @DisplayName("a corrupt cache entry degrades to no reference rather than breaking the wish")
    void corruptCacheIsSurvivable(@TempDir Path dir) throws Exception {
        ReferenceResearcher.cacheDir(dir);
        Files.writeString(dir.resolve("dinko.json"), "{ this is not json");
        // Falls through to a live lookup, which in a test environment finds no usable CLI and
        // returns none(). Either way: no exception, and the wish continues.
        assertTrue(ReferenceResearcher.lookUp("Dinko").isEmpty());
    }

    /**
     * <b>The cache writer is hand-rolled, and it has silently lost a field twice.</b>
     *
     * <p>Both times the signature was identical: correct on a cache MISS, gone on every HIT — and
     * an eval runs on hits, so warming the cache to make a run <i>reproducible</i> was the very
     * thing that disabled the feature the run existed to measure. (The blockout was the first
     * casualty; it has since been removed for not earning its tokens, but the writer that dropped
     * it is still the writer.) This drives EVERY surviving field through write → read so the next
     * field added fails loudly instead.
     */
    @Test
    @DisplayName("REAL BUG CLASS: every field survives the cache round-trip")
    void everyFieldSurvivesTheCache(@TempDir Path dir) throws Exception {
        ReferenceResearcher.cacheDir(dir);
        Files.writeString(dir.resolve("richmon.json"), """
                {"colours":[{"colour":"green","percent":60},{"colour":"white","percent":40}],
                 "parts":["shell","horn","claw"],"size":"LARGE","confidence":"HIGH",
                 "expression":"ANGRY","sources":["https://example.com/richmon"],
                 "images":["https://example.com/a.png","https://example.com/b.png"]}""");
        var first = ReferenceResearcher.lookUp("richmon");

        // Now write it back out the way a real fetch would, and read it again.
        Path round = dir.resolve("round");
        Files.createDirectories(round);
        ReferenceResearcher.cacheDir(round);
        ReferenceResearcher.writeCacheForTest("richmon", first);
        var second = ReferenceResearcher.lookUp("richmon");

        assertEquals(first.colours(), second.colours(), "the colours were dropped by the cache");
        assertEquals(first.parts(), second.parts(), "the parts were dropped by the cache");
        assertEquals(ReferenceSpec.Size.LARGE, second.size(), "the size was dropped by the cache");
        assertEquals(ReferenceSpec.Confidence.HIGH, second.confidence(),
                "the confidence was dropped by the cache");
        assertEquals(ReferenceSpec.Expression.ANGRY, second.expression(),
                "the expression was dropped by the cache — this writer has lost a field twice");
        assertEquals(first.sources(), second.sources(), "the sources were dropped by the cache");
        assertEquals(first.images(), second.images(), "the image urls were dropped by the cache");
    }

    /**
     * <b>The palette measurement must never be able to break a wish.</b>
     *
     * <p>Every one of these URLs is refused before a socket opens, so this stays offline: the point
     * is that a named image which cannot be fetched leaves the reference exactly as the model gave
     * it, rather than emptying the palette or throwing on the researcher's background thread.
     */
    @Test
    @DisplayName("unfetchable artwork leaves the model's colours alone rather than failing")
    void aDeadImageUrlIsNotAFailure() {
        var spec = ReferenceSpec.parse("""
                {"colours":[{"colour":"green","percent":60}],"parts":["shell"],
                 "confidence":"HIGH",
                 "images":["https://127.0.0.1/a.png","https://localhost/b.png"]}""");
        var out = ReferenceResearcher.measurePalette(spec);
        assertEquals(spec.colours(), out.colours(),
                "a dead image url emptied the palette instead of leaving it alone");
        assertEquals(ReferenceSpec.Confidence.HIGH, out.confidence());
        // ...and a spec that named no artwork at all is returned untouched.
        var none = ReferenceSpec.parse("""
                {"colours":[{"colour":"red","percent":90}],"confidence":"MEDIUM"}""");
        assertEquals(none, ReferenceResearcher.measurePalette(none));
    }

    /**
     * <b>A search result is evidence, and refusing to use one cost the whole answer.</b>
     *
     * <p>Asked for OMEGA LAD from a machine whose network policy blocks every brainrot wiki, the
     * researcher returned confidence NONE and zero sources — while the very same search, on the
     * very same machine, was returning "a penguin with a salmon-sushi torso and sashimi hair" in
     * its result snippets. It had the answer and discarded it for not having come off a page it
     * could open.
     *
     * <p>That looked exactly like an environment problem and very nearly bought a second execution
     * environment to fix. It was a sentence in the brief.
     */
    @Test
    @DisplayName("REAL BUG: unfetchable pages must not mean 'found nothing' when SEARCH found it")
    void aSearchResultIsEvidence() {
        String brief = ReferenceResearcher.promptForTest("Omega Lad");
        assertTrue(brief.contains("SEARCH RESULT IS EVIDENCE"), brief);
        // ...and it must say what to do with it, not merely that it counts: report it, cite what
        // you saw, and drop the confidence because you could not read the page yourself.
        assertTrue(brief.contains("MEDIUM"), "the brief never says to lower the confidence");
        assertTrue(brief.contains("403"), "nothing tells it that a refused page is the case meant");
    }

    /**
     * <b>The researcher does NOT go looking for pictures any more — Java does.</b>
     *
     * <p>Measured across three traced lookups: 10 of 32 web tool calls (31%) were
     * {@code "png transparent"} searches and {@code /Gallery} fetches, 13.5 seconds of one run —
     * and the model was <i>worse</i> at it than one API call. It returned zero images for every
     * Omega Lad run, whose page it cannot fetch, and once returned a single image for Spikor,
     * under {@link ReferencePalette#MIN_IMAGES}, which silently produced no measurement at all.
     * {@link WikiArtwork} asks the wiki's own image API and returned six Spikor renders in 1.0 s.
     *
     * <p>So what the brief must now protect is {@code sources}: those URLs are what the artwork is
     * resolved FROM, which makes a page the researcher read and did not cite a picture nobody can
     * count.
     */
    @Test
    @DisplayName("the brief does not send the model hunting for artwork, and says why not")
    void theBriefLeavesTheArtworkToJava() {
        String brief = ReferenceResearcher.promptForTest("Spikor");
        assertFalse(brief.contains("\"images\""),
                "the brief still asks the model for image URLs, which costs a third of its web "
                        + "calls to do worse than one API call: " + brief);
        assertTrue(brief.contains("DO NOT GO LOOKING FOR PICTURES"), brief);
        // The model must NOT be asked to judge proportions -- that is the thing it was 8x wrong at.
        assertTrue(brief.contains("count") && brief.contains("pixels"), brief);
        // And the field the resolver actually needs has to be worth its while.
        assertTrue(brief.contains("every page you actually"),
                "nothing tells it that sources is now the load-bearing field: " + brief);
        assertTrue(brief.contains("STILL CITE THE PAGE YOU COULD NOT OPEN"),
                "a page whose FETCH was refused is very often one whose API answers fine — "
                        + "measured on Omega Lad, 403 to the page and 200 to the API");
    }

    /**
     * <b>The subprocess is a sandbox, and {@code --allowed-tools} was not making it one.</b>
     *
     * <p>{@code --allowed-tools} is a PERMISSION list; the tools remain present, and
     * {@code --permission-mode dontAsk} approves them. Observed in real traces: this process ran
     * {@code Bash} ("echo done", and once a {@code curl} at a page {@code WebFetch} had refused)
     * and called {@code ReadNotifications}, reading the parent session's queue. Attacker-controlled
     * page text is one hop from here.
     *
     * <p>{@code --tools} is the availability filter, and it is also the cheapest speed-up
     * available: 38 tool schemas plus 55 slash commands is ~40,000 input tokens re-read every turn.
     * Measured 304,871 -> 80,182 input tokens per lookup.
     */
    @Test
    @DisplayName("the researcher process is narrowed by --tools, not merely by --allowed-tools")
    void theSandboxIsNarrowedByAvailabilityNotPermission() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/moddingfromamod/reference/ReferenceResearcher.java"));
        int call = src.indexOf("new ProcessBuilder(");
        assertTrue(call > 0, "the subprocess launch moved; this gate is now blind");
        String args = src.substring(call, src.indexOf("pb.start()", call));
        assertTrue(args.contains("\"--tools\""),
                "--allowed-tools alone leaves Bash, Write, Edit, Task and Workflow PRESENT in a "
                        + "process one hop from attacker-controlled page text");
        assertTrue(args.contains("\"--strict-mcp-config\""), "MCP servers are not excluded");
        assertTrue(args.contains("\"--effort\""),
                "no effort level is pinned, so the lookup runs at whatever the CLI defaults to — "
                        + "measured at 1,469-2,721 thinking tokens per lookup");
    }

    /**
     * <b>The brief must resolve the SUBJECT before it ranks SOURCES.</b>
     *
     * <p>The ranking it carried was a ranking of source QUALITY and was blind to whether the page
     * was about the right thing at all. Asked for OMEGA LAD, the researcher took
     * {@code en.wikipedia.org/wiki/Omega_Lad} — rank 2, a genuinely excellent article about the
     * SONG — over the brainrot wiki at rank 3/4 that documents the penguin with the sushi torso.
     * It obeyed the ranking exactly. Tralalelo Tralala only worked because Wikipedia happens to
     * have no article under that name, so the ranking fell through to the right wiki BY ACCIDENT.
     */
    @Test
    @DisplayName("REAL BUG: the brief fixes the subject first, and says a right-but-humble source "
            + "beats a wrong-but-authoritative one")
    void theBriefResolvesTheSubjectBeforeRankingSources() {
        String brief = ReferenceResearcher.promptForTest("Omega Lad");
        int subject = brief.indexOf("WHICH THING THIS IS");
        int ranking = brief.indexOf("1. the owner");
        assertTrue(subject >= 0, "the brief never asks which entity this is: " + brief);
        assertTrue(ranking > subject,
                "the source ranking comes BEFORE the subject is fixed, which is the bug");
        assertTrue(brief.contains("BEATS A HIGH-RANKED PAGE ABOUT"),
                "nothing tells it a humble page about the right character wins: " + brief);
        // The other half: when it finds nothing, it must return nothing rather than fill the
        // schema with a generic humanoid, which measured worse than no reference at all.
        assertTrue(brief.contains("DO NOT PAD A SCHEMA"), brief);
        assertTrue(brief.contains("confidence NONE"), brief);
    }

    @Test
    @DisplayName("the brief actually ASKS for every field — an unasked-for field is never sent")
    void theResearcherIsToldAboutEveryField() {
        // A field the brief never mentions is a field that never comes back. This is this repo's
        // signature failure: seven capabilities built, wired, committed, and never called because
        // nothing asked for them.
        String brief = ReferenceResearcher.promptForTest("Spikor");
        assertTrue(brief.contains("\"colours\""), brief);
        assertTrue(brief.contains("\"parts\""), brief);
        assertTrue(brief.contains("\"size\""), brief);
        assertTrue(brief.contains("\"confidence\""), brief);
        assertTrue(brief.contains("\"sources\""), brief);
        // the researcher is the only thing that sees a face, so it has to be ASKED.
        assertTrue(brief.contains("\"expression\""), brief);
        for (var e : ReferenceSpec.Expression.values()) {
            assertTrue(brief.contains(e.name()), "the brief never offers " + e);
        }
        // The parts vocabulary is the ONLY channel for shape now that the blockout is gone, so a
        // part word missing from the brief is a shape the builder can never be told about.
        for (String part : ReferenceSpec.PARTS) {
            assertTrue(brief.contains(part), "the brief never offers the part word '" + part + "'");
        }
    }

    /**
     * <b>The warm cache and the live lookup disagreed on a key, and the eval measured nothing.</b>
     *
     * <p>References are warmed from the corpus's {@code reference} DESCRIPTION — "Spikor (Super
     * Plumo), modern/Voyage-era" — and the agent asks for "SPIKOR". Different slugs, so every
     * lookup missed; with the zero wait that returns nothing immediately, and a measured run built
     * Spikor with no sourced palette, no named parts and no expression. The log line saying so
     * read like ordinary background chatter.
     */
    @Test
    @DisplayName("REAL BUG: asking for 'SPIKOR' finds the reference warmed under its long name")
    void aShortNameFindsALongCachedKey(@TempDir Path dir) throws Exception {
        ReferenceResearcher.cacheDir(dir);
        Files.writeString(dir.resolve("spikor_super_plumo_modern_voyage_era.json"), """
                {"colours":[{"colour":"green","percent":50}],"parts":["shell","horn"],
                 "confidence":"HIGH","expression":"FIERCE"}""");

        var spec = ReferenceResearcher.lookUp("SPIKOR");

        assertFalse(spec.isEmpty(), "the warmed reference was not found, so the wish builds blind");
        assertEquals(ReferenceSpec.Expression.FIERCE, spec.expression());
    }

    @Test
    @DisplayName("the SHORTEST match wins, and an unrelated name still misses")
    void prefixMatchingIsNarrow() throws Exception {
        Path dir = Files.createTempDirectory("mfam-prefix");
        ReferenceResearcher.cacheDir(dir);
        Files.writeString(dir.resolve("zippy_the_hedgehog.json"),
                "{\"colours\":[{\"colour\":\"blue\",\"percent\":70}],\"confidence\":\"HIGH\"}");
        Files.writeString(dir.resolve("zippy_the_hedgehog_modern_arcade_design.json"),
                "{\"colours\":[{\"colour\":\"blue\",\"percent\":60}],\"confidence\":\"LOW\"}");
        // Least specific wins: it is the least likely to be a different character who merely
        // starts the same way.
        assertEquals(ReferenceSpec.Confidence.HIGH,
                ReferenceResearcher.lookUp("Zippy").confidence());
        // ...and a prefix must be a whole segment, not any old substring.
        assertTrue(ReferenceResearcher.lookUp("son").isEmpty(),
                "a three-letter fragment must not claim a character");
        assertTrue(ReferenceResearcher.lookUp("Dinko").isEmpty(), "nothing cached for Dinko");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("#1219 r2: the two measured rules are ON, and the measured-worse arm stays off")
    void roundTwoDefaults() {
        assertTrue(ReferenceResearcher.characterOpaqueFallback,
                "a character whose renders give nothing tries its JPEGs: 9 -> 11 correct, 0 new wrong");
        assertEquals(0.67, ReferenceResearcher.creatureLeaderShare, 1e-9,
                "above 2/3 measured 11 -> 13 correct with no new wrong; exactly 2/3 and 1/2 each "
                        + "added confident wrongs");
        // The creature brief measured WORSE (7 correct, 3 wrong against 9 and 1): characters
        // and creatures share one brief.
        String brief = ReferenceResearcher.promptForTest("Pegasus");
        assertTrue(brief.startsWith("Find out what this character looks like"), brief);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("#1219 r3: a part count survives the cache, or a warmed eval would lose it")
    void countsAreCached(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        ReferenceResearcher.cacheDir(dir);
        try {
            var spec = ReferenceSpec.parse("""
                    {"parts": [{"part": "head", "count": 3}, "tentacle"], "confidence": "HIGH"}""");
            ReferenceResearcher.writeCacheForTest("cerberus_test", spec);
            var back = ReferenceResearcher.cachedOnly("cerberus test");
            assertEquals(java.util.Map.of("head", 3), back.counts());
            assertEquals(java.util.List.of("head", "tentacle"), back.parts());
        } finally {
            ReferenceResearcher.quiesceForTest();
        }
    }
}
