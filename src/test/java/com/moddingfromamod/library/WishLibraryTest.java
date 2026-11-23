package com.moddingfromamod.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.RuntimeConfig;
import com.moddingfromamod.wish.WishCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>The wish library's rules, without a game</b>.
 *
 * <p>Three of these pin decisions that are invisible from the outside until they are wrong: the
 * PRECEDENCE (library &gt; learned &gt; built-in), the fact that a reload is a SWAP of an immutable
 * value rather than an edit of a live one, and that an absent library is an empty one.
 */
class WishLibraryTest {

    private static final String PROP = "moddingfromamod.libraryDir";

    /** These tests assert on exactly the library they wrote, so the shipped starter layer is off. */
    @org.junit.jupiter.api.BeforeEach
    void withoutTheStarterLayer() {
        StarterLibrary.disabledForTest(true);
    }

    @AfterEach
    void clearOverride() {
        System.clearProperty(PROP);
        WishLibrary.clear();
        StarterLibrary.disabledForTest(false);
    }

    @Test
    void anAbsentOwnerLibraryStillServesTheStarterNineThroughTheSamePath(@TempDir Path tmp) {
        // The starter row ships in the jar and needs no configuration, so a server with no
        // library at all still has it — through the library, the one path.
        StarterLibrary.disabledForTest(false);
        System.setProperty(PROP, tmp.resolve("not-here").toString());
        LibrarySnapshot snap = WishLibrary.reload();
        assertTrue(snap.hasTemplate("starter_pet_dragon"), snap.summary());
        assertTrue(WishLibrary.asPresets().stream()
                        .anyMatch(p -> "starter_pet_dragon".equals(p.templateId())),
                "the starter nine are BUTTONS, not only recipes");
    }

    /**
     * ONE change, as the poll sees it. A write followed by a separate mtime bump is two
     * changes to a 100 ms poll that looks between them (the truncate-and-write, then the new mtime),
     * and the product rightly reloads twice for a file that moved twice. So the new file is made
     * whole beside the library, stamped, and renamed in — a rename keeps the mtime and is one step.
     */
    private static void oneChange(Path target, String content) throws Exception {
        Path staged = target.getParent().resolveSibling("staged-" + target.getFileName());
        Files.writeString(staged, content);
        Files.setLastModifiedTime(staged,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        Files.move(staged, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static Path library(Path root, String templatesJson) throws Exception {
        Path dir = root.resolve("library");
        Files.createDirectories(dir);
        if (templatesJson != null) {
            Files.writeString(dir.resolve("templates.json"), templatesJson);
        }
        return dir;
    }

    private static JsonArray learned(String... entries) {
        JsonArray arr = new JsonArray();
        for (String e : entries) {
            arr.add(JsonParser.parseString(e).getAsJsonObject());
        }
        return arr;
    }

    private static List<String> idsOf(String composed) {
        List<String> out = new java.util.ArrayList<>();
        for (var el : JsonParser.parseString(composed).getAsJsonObject()
                .getAsJsonArray("templates")) {
            out.add(el.getAsJsonObject().get("id").getAsString());
        }
        return out;
    }

    // ── the directory ───────────────────────────────────────────────────────────────────────

    @Test
    void anAbsentDirectoryIsAnEmptyLibraryRatherThanAFailure(@TempDir Path tmp) {
        System.setProperty(PROP, tmp.resolve("not-here").toString());
        LibrarySnapshot snap = WishLibrary.reload();
        assertTrue(snap.isEmpty());
        assertTrue(snap.problems().isEmpty(), "a missing library is not a problem to report");
        assertTrue(WishLibrary.asPresets().isEmpty());
        assertNull(WishLibrary.artifactDir("species", "spikor"));
    }

    @Test
    void theDirectoryResolvesFromConfigAndDefaultsUnderTheModHome(@TempDir Path tmp) {
        System.setProperty(PROP, tmp.resolve("lib").toString());
        assertEquals(tmp.resolve("lib"), WishLibrary.dir());
        System.clearProperty(PROP);
        // An isolated home, so this machine's own config.properties cannot answer for the default.
        System.setProperty("moddingfromamod.home", tmp.resolve("home").toString());
        RuntimeConfig.invalidate();
        try {
            assertEquals(tmp.resolve("home").resolve("library"), WishLibrary.dir());
            assertEquals(RuntimeConfig.home().resolve("library"), RuntimeConfig.libraryDir());
        } finally {
            System.clearProperty("moddingfromamod.home");
            RuntimeConfig.invalidate();
        }
    }

    // ── precedence ──────────────────────────────────────────────────────────────────────────

    @Test
    void theLibraryBeatsTheLearnedEntryForTheSameId(@TempDir Path tmp)
            throws Exception {
        System.setProperty(PROP, library(tmp, """
                {"templates":[
                  {"id":"treasure","all":["treasure"],
                   "commands":["give @p minecraft:diamond 64"],"say":"The good treasure!"}
                ]}
                """).toString());
        WishLibrary.reload();

        String composed = WishLibrary.composeTemplatesJson(
                learned("""
                        {"id":"treasure","all":["treasure"],
                         "commands":["give @p minecraft:dirt 1"],"say":"learned"}
                        """));

        List<String> ids = idsOf(composed);
        assertEquals("treasure", ids.get(0), "the curated entry serves first");
        assertEquals(1, ids.stream().filter("treasure"::equals).count(),
                "the id the library claims appears ONCE — the learned copy is dropped rather than "
                + "left sitting behind it");
        // and it is the library's commands that a press would run
        JsonObject first = JsonParser.parseString(composed).getAsJsonObject()
                .getAsJsonArray("templates").get(0).getAsJsonObject();
        assertEquals("give @p minecraft:diamond 64",
                first.getAsJsonArray("commands").get(0).getAsString());
    }

    @Test
    void learnedFollowsTheLibraryWhenTheLibraryDoesNotClaimTheId(@TempDir Path tmp)
            throws Exception {
        System.setProperty(PROP, library(tmp, """
                {"templates":[{"id":"curated","all":["curated"],"commands":["say lib"]}]}
                """).toString());
        WishLibrary.reload();
        List<String> ids = idsOf(WishLibrary.composeTemplatesJson(
                learned("{\"id\":\"tower\",\"all\":[\"tower\"],\"commands\":[\"say mine\"]}")));
        assertEquals(List.of("curated", "tower"), ids, "library, then learned, and nothing else");
    }

    /**
     * There is no third source. The hard-coded built-ins used to follow
     * the learned set, so a composition with no library and one learned entry was eighteen
     * templates long; it is now exactly that one.
     */
    @Test
    void withNoLibraryTheCompositionIsExactlyTheLearnedSet(@TempDir Path tmp) {
        System.setProperty(PROP, tmp.resolve("nothing").toString());
        WishLibrary.reload();
        List<String> ids = idsOf(WishLibrary.composeTemplatesJson(
                learned("{\"id\":\"mine\",\"all\":[\"mine\"],\"commands\":[\"say x\"]}")));
        assertEquals(List.of("mine"), ids);
        assertEquals(List.of(), idsOf(WishLibrary.composeTemplatesJson(null)),
                "with nothing learned and no library, nothing answers offline");
    }

    // ── icons ───────────────────────────────────────────────────────────────────────────────

    @Test
    void anEntrysIconIsReadAndNormalisedAndNonsenseIsDropped(@TempDir Path tmp) throws Exception {
        System.setProperty(PROP, library(tmp, """
                {"templates":[
                  {"id":"cake_party","all":["cake"],"commands":["give @p minecraft:cake 5"],
                   "icon":"cake"},
                  {"id":"plain","all":["plain"],"commands":["say hi"]},
                  {"id":"rubbish","all":["rubbish"],"commands":["say hi"],
                   "icon":"not an item!!"}
                ]}
                """).toString());
        WishLibrary.reload();
        assertEquals("minecraft:cake", WishLibrary.iconFor("cake_party"), "a bare name gets a namespace");
        assertEquals("", WishLibrary.iconFor("plain"), "no icon means: scan the words");
        assertEquals("", WishLibrary.iconFor("rubbish"),
                "an unresolvable id must fall back to the scan, not be drawn as a missing model");
        assertEquals("", WishLibrary.iconFor("nobody"));
    }

    @Test
    void aLibraryPresetCarriesItsIconAndItsRecipeId(@TempDir Path tmp) throws Exception {
        System.setProperty(PROP, library(tmp, """
                {"templates":[
                  {"id":"cake_party","all":["cake","party"],
                   "commands":["give @p minecraft:cake 5"],"icon":"minecraft:cake"}
                ]}
                """).toString());
        WishLibrary.reload();
        List<WishCatalog.Preset> presets = WishLibrary.asPresets();
        assertEquals(1, presets.size());
        assertEquals("cake_party", presets.get(0).templateId());
        assertEquals("minecraft:cake", presets.get(0).icon());
        assertEquals("cake party", presets.get(0).wishText(),
                "the button's own text must reach its recipe");
    }

    // ── The wish and its icon, in two files ──────────────────────────────────────────

    @Test
    void iconsJsonDressesARecipeThatCarriesNoIconOfItsOwn(@TempDir Path tmp) throws Exception {
        Path dir = library(tmp, """
                {"templates":[{"id":"cake_party","all":["cake"],"commands":["say cake"]}]}
                """);
        Files.writeString(dir.resolve("icons.json"), """
                {"store":"library-icons","v":1,"icons":[{"id":"cake_party","item":"cake"}]}
                """);
        System.setProperty(PROP, dir.toString());
        WishLibrary.reload();
        assertEquals("minecraft:cake", WishLibrary.iconFor("cake_party"),
                "the wish was authored with no icon, and icons.json dresses it, normalised");
        assertEquals("minecraft:cake", WishLibrary.asPresets().get(0).icon(),
                "…all the way to the button");
    }

    @Test
    void iconsJsonWinsOverAnInlineIconAndAnInlineOneStillServesWithout(@TempDir Path tmp)
            throws Exception {
        Path dir = library(tmp, """
                {"templates":[
                  {"id":"redressed","all":["a"],"commands":["say a"],"icon":"minecraft:dirt"},
                  {"id":"old_shape","all":["b"],"commands":["say b"],"icon":"minecraft:egg"}
                ]}
                """);
        Files.writeString(dir.resolve("icons.json"), """
                {"store":"library-icons","v":1,"icons":[{"id":"redressed","item":"minecraft:cake"}]}
                """);
        System.setProperty(PROP, dir.toString());
        WishLibrary.reload();
        assertEquals("minecraft:cake", WishLibrary.iconFor("redressed"),
                "re-dressing a button is an icons.json edit, never a recipe edit");
        assertEquals("minecraft:egg", WishLibrary.iconFor("old_shape"),
                "a library written before the split keeps its buttons");
    }

    @Test
    void anIconsFileFromANewerModdyIsAProblemAndTheRecipesStillServe(@TempDir Path tmp)
            throws Exception {
        Path dir = library(tmp, """
                {"templates":[{"id":"x","all":["x"],"commands":["say x"],"icon":"minecraft:egg"}]}
                """);
        Files.writeString(dir.resolve("icons.json"),
                "{\"store\":\"library-icons\",\"v\":99,\"icons\":[]}");
        System.setProperty(PROP, dir.toString());
        LibrarySnapshot snap = WishLibrary.reload();
        assertTrue(snap.hasTemplate("x"), "an unreadable icons file costs no wish");
        assertEquals("minecraft:egg", WishLibrary.iconFor("x"));
        assertTrue(snap.problems().stream().anyMatch(p -> p.contains("newer Moddy")),
                "…and it is said out loud: " + snap.problems());
    }

    @Test
    void reDressingAButtonIsSomethingThePollNotices(@TempDir Path tmp) throws Exception {
        Path dir = library(tmp, "{\"templates\":[]}");
        long before = LibrarySnapshot.fingerprintOf(dir);
        Files.writeString(dir.resolve("icons.json"),
                "{\"store\":\"library-icons\",\"v\":1,\"icons\":[]}");
        Files.setLastModifiedTime(dir.resolve("icons.json"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertFalse(before == LibrarySnapshot.fingerprintOf(dir),
                "an icons-only edit is the whole point of the split, so hot reload must see it");
    }

    // ── the snapshot ────────────────────────────────────────────────────────────────────────

    @Test
    void aReloadSwapsTheSnapshotAndLeavesTheOldOneUntouched(@TempDir Path tmp) throws Exception {
        Path dir = library(tmp, """
                {"templates":[{"id":"one","all":["one"],"commands":["say one"]}]}
                """);
        System.setProperty(PROP, dir.toString());
        LibrarySnapshot before = WishLibrary.reload();

        Files.writeString(dir.resolve("templates.json"), """
                {"templates":[{"id":"two","all":["two"],"commands":["say two"]}]}
                """);
        LibrarySnapshot after = WishLibrary.reload();

        assertFalse(before == after, "a reload is a new value, never an edit of the live one");
        assertEquals(List.of("one"), before.templateIds(),
                "THE POINT: a wish mid-replay is holding `before` and keeps the recipe it matched");
        assertEquals(List.of("two"), after.templateIds());
        assertSame(after, WishLibrary.snapshot());
    }

    @Test
    void aDamagedTemplatesFileCostsItsEntriesAndNothingElse(@TempDir Path tmp) throws Exception {
        Path dir = library(tmp, "{ this is not json");
        Files.createDirectories(dir.resolve("species").resolve("spikor"));
        Files.writeString(dir.resolve("species").resolve("spikor").resolve("geometry.json"), "{}");
        System.setProperty(PROP, dir.toString());
        LibrarySnapshot snap = WishLibrary.reload();
        assertTrue(snap.templates().isEmpty());
        assertFalse(snap.problems().isEmpty(), "and it says so");
        assertTrue(snap.has("species", "spikor"), "the creatures still serve");
    }

    @Test
    void anIdWrittenTwiceKeepsTheFirstAndIsReported(@TempDir Path tmp) throws Exception {
        System.setProperty(PROP, library(tmp, """
                {"templates":[
                  {"id":"dup","all":["dup"],"commands":["say first"]},
                  {"id":"dup","all":["dup"],"commands":["say second"]}
                ]}
                """).toString());
        LibrarySnapshot snap = WishLibrary.reload();
        assertEquals(List.of("dup"), snap.templateIds());
        assertTrue(snap.problems().stream().anyMatch(p -> p.contains("dup")));
    }

    // ── artifacts ───────────────────────────────────────────────────────────────────────────

    @Test
    void anArtifactDirectoryOnlyCountsWhenItsMarkerFileIsThere(@TempDir Path tmp) throws Exception {
        Path dir = library(tmp, null);
        Files.createDirectories(dir.resolve("species").resolve("real"));
        Files.writeString(dir.resolve("species").resolve("real").resolve("geometry.json"), "{}");
        Files.createDirectories(dir.resolve("species").resolve("empty_one"));
        Files.createDirectories(dir.resolve("buildings").resolve("castle"));
        Files.writeString(dir.resolve("buildings").resolve("castle").resolve("build.json"), "{}");
        Files.createDirectories(dir.resolve("datapacks").resolve("rain"));
        Files.writeString(dir.resolve("datapacks").resolve("rain").resolve("rain.zip"), "x");
        System.setProperty(PROP, dir.toString());
        WishLibrary.reload();

        assertEquals(List.of("real"), WishLibrary.keys("species"),
                "a directory with no geometry is not a creature — an empty folder left by a "
                + "half-finished promote must not make /wishmob spawn offer a key that cannot draw");
        assertEquals(List.of("castle"), WishLibrary.keys("buildings"));
        assertEquals(List.of("rain"), WishLibrary.keys("datapacks"));
        assertNotNull(WishLibrary.artifactFile("species", "real", "geometry.json"));
        assertNull(WishLibrary.artifactFile("species", "real", "texture.png"),
                "a file that is not there resolves to null, not to a hopeful path");
        assertNull(WishLibrary.artifactDir("species", "empty_one"));
    }

    @Test
    void aDirectoryTheModCouldNeverNameIsNotAnArtifact(@TempDir Path tmp) throws Exception {
        // FOUND BY THE FIRST REAL AUTHORING RUN, not by imagining a case: a sculpt leaves its undo
        // snapshots beside the model as `<key>.undo-<nanos>`, each a complete creature directory
        // with its own geometry, texture and spec. `ModelRepository.isValidKey` refuses a dot, so
        // the mod can never spawn one — a library that offered it would print a key in
        // `/wishmob list` that `/wishmob spawn` then refuses, which reads as a typo.
        Path dir = library(tmp, null);
        for (String key : List.of("blip", "blip.undo-9484001985724", ".hidden",
                "WithCapitals", "a space")) {
            Files.createDirectories(dir.resolve("species").resolve(key));
            Files.writeString(dir.resolve("species").resolve(key).resolve("geometry.json"), "{}");
        }
        System.setProperty(PROP, dir.toString());
        WishLibrary.reload();
        assertEquals(List.of("blip"), WishLibrary.keys("species"));
        assertNull(WishLibrary.artifactDir("species", "blip.undo-9484001985724"));
        assertNotNull(WishLibrary.artifactFile("species", "blip", "geometry.json"));
    }

    @Test
    void theFingerprintMovesWhenTheLibraryDoesAndIsStableWhenItDoesNot(@TempDir Path tmp)
            throws Exception {
        Path dir = library(tmp, "{\"templates\":[]}");
        long first = LibrarySnapshot.fingerprintOf(dir);
        assertEquals(first, LibrarySnapshot.fingerprintOf(dir), "no change, no difference");

        Files.writeString(dir.resolve("templates.json"),
                "{\"templates\":[{\"id\":\"x\",\"all\":[\"x\"],\"commands\":[\"say x\"]}]}");
        Files.setLastModifiedTime(dir.resolve("templates.json"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertFalse(first == LibrarySnapshot.fingerprintOf(dir),
                "an edited templates.json is what the poll has to notice");

        long second = LibrarySnapshot.fingerprintOf(dir);
        Files.createDirectories(dir.resolve("species").resolve("newcomer"));
        assertFalse(second == LibrarySnapshot.fingerprintOf(dir),
                "and so is a creature arriving");
    }

    /**
     * The film found the poll reloading EVERY time it looked: it compared the owner
     * directory's fingerprint against the LAYERED snapshot's (owner + the jar's starters), which
     * never match, so a server re-read the library and re-pushed every button every 30 s.
     */
    @Test
    void thePollReloadsOnlyWhenTheOwnersLibraryChanges(@TempDir Path tmp) throws Exception {
        Path dir = library(tmp, "{\"templates\":[]}");
        StarterLibrary.disabledForTest(false);
        assertNotNull(StarterLibrary.dir(), "the bug needs the starter layer under the owner's");
        System.setProperty(PROP, dir.toString());
        System.setProperty("moddingfromamod.libraryPollMs", "100");
        java.util.concurrent.atomic.AtomicInteger reloads = new java.util.concurrent.atomic.AtomicInteger();
        try {
            WishLibrary.reload();
            assertFalse(WishLibrary.snapshot().fingerprint() == LibrarySnapshot.fingerprintOf(dir),
                    "the layered fingerprint is not the directory's — the trap this pins");
            WishLibrary.onReload(() -> { WishLibrary.reload(); reloads.incrementAndGet(); });
            WishLibrary.startPolling();
            Thread.sleep(800);
            assertEquals(0, reloads.get(), "nothing changed on disk, so nothing is reloaded");
            oneChange(dir.resolve("templates.json"),
                    "{\"templates\":[{\"id\":\"x\",\"all\":[\"x\"],\"commands\":[\"say x\"]}]}");
            long deadline = System.currentTimeMillis() + 3000;
            while (reloads.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            Thread.sleep(600);
            assertEquals(1, reloads.get(), "one change, one reload — not one per poll after it");
        } finally {
            WishLibrary.stopPolling();
            WishLibrary.onReload(null);
            System.clearProperty(PROP);
            System.clearProperty("moddingfromamod.libraryPollMs");
            WishLibrary.clear();
        }
    }

    /** …and a change written between the read and the poll thread's first look is still a change. */
    @Test
    void aChangeBeforeThePollStartsIsStillNoticed(@TempDir Path tmp) throws Exception {
        Path dir = library(tmp, "{\"templates\":[]}");
        System.setProperty(PROP, dir.toString());
        System.setProperty("moddingfromamod.libraryPollMs", "100");
        java.util.concurrent.atomic.AtomicInteger reloads = new java.util.concurrent.atomic.AtomicInteger();
        try {
            WishLibrary.reload();
            oneChange(dir.resolve("templates.json"),
                    "{\"templates\":[{\"id\":\"x\",\"all\":[\"x\"],\"commands\":[\"say x\"]}]}");
            // Count a reload once it has SWAPPED the snapshot: counted first, this thread
            // could see 1 and read the snapshot while the poll thread was still mid-reload.
            WishLibrary.onReload(() -> { WishLibrary.reload(); reloads.incrementAndGet(); });
            WishLibrary.startPolling();          // AFTER the write: the baseline is the read's
            long deadline = System.currentTimeMillis() + 3000;
            while (reloads.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(1, reloads.get(), "the change made before the poll started was noticed");
            assertTrue(WishLibrary.snapshot().hasTemplate("x"));
        } finally {
            WishLibrary.stopPolling();
            WishLibrary.onReload(null);
            System.clearProperty(PROP);
            System.clearProperty("moddingfromamod.libraryPollMs");
            WishLibrary.clear();
        }
    }

    @Test
    void thePollIntervalIsThirtySecondsUnlessSomethingSaysOtherwise() {
        assertEquals(WishLibrary.DEFAULT_POLL_MS, WishLibrary.pollMillis());
        System.setProperty("moddingfromamod.libraryPollMs", "1000");
        try {
            assertEquals(1000L, WishLibrary.pollMillis());
            System.setProperty("moddingfromamod.libraryPollMs", "nonsense");
            assertEquals(WishLibrary.DEFAULT_POLL_MS, WishLibrary.pollMillis(),
                    "a typo must not switch the poll off");
            System.setProperty("moddingfromamod.libraryPollMs", "1");
            assertEquals(100L, WishLibrary.pollMillis(), "and a silly value is floored");
        } finally {
            System.clearProperty("moddingfromamod.libraryPollMs");
        }
    }

    // ── The server owner's watch order ────────────────────────────────────────────────────

    @Test
    void theOwnersOrderIsTheWatchOrderAcrossPromotedEntriesAndStarters(@TempDir Path tmp)
            throws Exception {
        StarterLibrary.disabledForTest(false);
        System.setProperty(PROP, library(tmp, """
                {"order":["cake_party","starter_lava_castle","starter_pet_dragon"],
                 "templates":[
                  {"id":"cake_party","all":["cake","party"],"commands":["give @p minecraft:cake 5"]}
                ]}
                """).toString());
        WishLibrary.reload();
        List<String> ids = WishLibrary.asPresets().stream().map(WishCatalog.Preset::templateId).toList();
        assertEquals(List.of("cake_party", "starter_lava_castle", "starter_pet_dragon"),
                ids.subList(0, 3), "the ids he placed come first, in his order: " + ids);
        assertTrue(ids.size() > 3 && ids.get(3).startsWith("starter_"),
                "every button he did not place keeps its place after them, starters first: " + ids);
    }

    @Test
    void anOrderNamingSomethingGoneSkipsItAndHidesNothing() {
        var a = new WishCatalog.Preset("A", "a", "a");
        var b = new WishCatalog.Preset("B", "b", "b");
        var c = new WishCatalog.Preset("C", "c", "c");
        assertEquals(List.of(c, a, b), WishLibrary.ordered(List.of(a, b, c), List.of("gone", "c")),
                "a demoted id in the order is skipped; nobody is hidden by a stale order");
        assertEquals(List.of(a, b, c), WishLibrary.ordered(List.of(a, b, c), List.of()),
                "no order means the order it already had");
    }

    @Test
    void theOrderIsReadFromTheOwnersFileAndTheFirstLayerWithOneDecides(@TempDir Path tmp)
            throws Exception {
        Path owner = library(tmp.resolve("o"), """
                {"order":["x","y"],"templates":[]}
                """);
        Path lower = library(tmp.resolve("l"), """
                {"order":["y","x"],"templates":[]}
                """);
        assertEquals(List.of("x", "y"), LibrarySnapshot.layered(List.of(owner, lower)).order());
        assertEquals(List.of(), LibrarySnapshot.read(tmp.resolve("absent")).order());
    }
}
