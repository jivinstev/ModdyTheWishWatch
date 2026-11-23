package com.moddingfromamod.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>The starter nine ship in the jar as a library, layered under the server owner's.</b>
 *
 * <p>The rules that are invisible until they are wrong: the index and the resource tree agree (a
 * file the index forgot would silently not ship), the server owner's library WINS on a shared id, and a
 * starter artifact is found through the same {@code artifactDir} every server path already uses.
 */
class StarterLibraryTest {

    private static final Path SRC = Path.of("src", "main", "resources", "starter-library");

    @Test
    void theIndexNamesExactlyTheFilesThatShip() throws Exception {
        List<String> indexed = StarterLibrary.files(Files.readString(SRC.resolve("index.txt")));
        TreeSet<String> onDisk = new TreeSet<>();
        try (var walk = Files.walk(SRC)) {
            walk.filter(Files::isRegularFile)
                .map(p -> SRC.relativize(p).toString().replace('\\', '/'))
                .filter(p -> !p.equals("index.txt"))
                .forEach(onDisk::add);
        }
        assertEquals(onDisk, new TreeSet<>(indexed),
                "starter-library/index.txt and the directory disagree — a file missing from the "
                        + "index never ships, and one missing from disk fails the unpack at boot");
    }

    @Test
    void theShippedStarterLibraryReadsCleanlyAndEveryRecipeHasAnIcon() {
        LibrarySnapshot snap = LibrarySnapshot.read(SRC);
        assertTrue(snap.problems().isEmpty(), "the shipped starter library has problems: "
                + snap.problems());
        assertFalse(snap.templates().isEmpty());
        for (LibrarySnapshot.Entry e : snap.templates()) {
            assertTrue(e.id().startsWith("starter_"), "a starter id is namespaced: " + e.id());
            assertFalse(e.icon().isEmpty(),
                    e.id() + " has no icon — the first row a child sees must never keyword-guess");
            assertTrue(JsonParser.parseString(e.json()).getAsJsonObject().has("label"),
                    e.id() + " has no label, so its button would read as its id");
        }
    }

    @Test
    void theOwnersLibraryWinsOnASharedIdAndTheStarterFillsTheRest(@TempDir Path tmp)
            throws Exception {
        Path owner = tmp.resolve("owner");
        Path starter = tmp.resolve("starter");
        Files.createDirectories(owner.resolve("species").resolve("dragon"));
        Files.createDirectories(starter.resolve("species").resolve("dragon"));
        Files.createDirectories(starter.resolve("species").resolve("axolotl"));
        Files.writeString(owner.resolve("species/dragon/geometry.json"), "{}");
        Files.writeString(starter.resolve("species/dragon/geometry.json"), "{}");
        Files.writeString(starter.resolve("species/axolotl/geometry.json"), "{}");
        Files.writeString(owner.resolve("templates.json"),
                "{\"templates\":[{\"id\":\"starter_dragon\",\"all\":[\"dragon\"],"
                        + "\"commands\":[\"say owner\"]}]}");
        Files.writeString(starter.resolve("templates.json"),
                "{\"templates\":[{\"id\":\"starter_dragon\",\"all\":[\"dragon\"],"
                        + "\"commands\":[\"say starter\"],\"icon\":\"minecraft:egg\"},"
                        + "{\"id\":\"starter_axolotl\",\"all\":[\"axolotl\"],"
                        + "\"commands\":[\"say axolotl\"]}]}");
        Files.writeString(owner.resolve("icons.json"), "{\"store\":\"library-icons\",\"v\":1,"
                + "\"icons\":[{\"id\":\"starter_axolotl\",\"item\":\"minecraft:axolotl_bucket\"}]}");

        LibrarySnapshot snap = LibrarySnapshot.layered(List.of(owner, starter));
        assertEquals(List.of("starter_dragon", "starter_axolotl"), snap.templateIds(),
                "one entry per id, the owner's first");
        assertTrue(snap.templates().get(0).json().contains("say owner"),
                "the OWNER's recipe serves a shared id");
        assertEquals(owner.resolve("species").resolve("dragon"), snap.artifactDir("species", "dragon"),
                "…and the owner's artifact");
        assertEquals(starter.resolve("species").resolve("axolotl"),
                snap.artifactDir("species", "axolotl"), "a starter-only artifact is still found");
        assertEquals("minecraft:axolotl_bucket", snap.iconFor("starter_axolotl"),
                "the owner can re-dress a starter button in their own icons.json");
        assertTrue(snap.problems().isEmpty(),
                "one id across two layers is the override WORKING, not a duplicate: "
                        + snap.problems());
    }

    @Test
    void theOwnerCanRetireAStarterWithoutANewJar(@TempDir Path tmp) throws Exception {
        // The code has promised "the owner can retire any of the nine without a new jar"
        // from the start, and nothing implemented it — an owner row could only REPLACE a starter.
        Path owner = tmp.resolve("owner");
        Path starter = tmp.resolve("starter");
        Files.createDirectories(owner);
        Files.createDirectories(starter);
        Files.writeString(owner.resolve("templates.json"),
                "{\"templates\":[{\"id\":\"starter_dragon\",\"retired\":true},"
                        + "{\"id\":\"spikor\",\"all\":[\"spikor\"],\"commands\":[\"say b\"]}]}");
        Files.writeString(starter.resolve("templates.json"),
                "{\"templates\":[{\"id\":\"starter_dragon\",\"all\":[\"dragon\"],"
                        + "\"commands\":[\"say starter\"]},"
                        + "{\"id\":\"starter_axolotl\",\"all\":[\"axolotl\"],"
                        + "\"commands\":[\"say axolotl\"]}]}");

        LibrarySnapshot snap = LibrarySnapshot.layered(List.of(owner, starter));
        assertEquals(List.of("spikor", "starter_axolotl"), snap.templateIds(),
                "a retired starter serves from NO layer, and the others are untouched");
        assertTrue(snap.problems().isEmpty(), "a retirement is not a problem: " + snap.problems());
        LibrarySnapshot alone = LibrarySnapshot.read(owner);
        assertEquals(List.of("spikor"), alone.templateIds(), "the retirement row itself serves nothing");
        assertEquals(java.util.Set.of("starter_dragon"), alone.retired());
    }

    private static Map<String, String> jar(String templates) {
        return Map.of(
                StarterLibrary.INDEX, "# comment\ntemplates.json\nspecies/x/geometry.json\n",
                StarterLibrary.ROOT + "templates.json", templates,
                StarterLibrary.ROOT + "species/x/geometry.json", "{}");
    }

    @Test
    void unpackWritesEveryIndexedFileIntoThisJarsOwnDirectory(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("home").resolve(StarterLibrary.BASE);
        Map<String, String> jar = jar("{\"templates\":[]}");
        Path out = StarterLibrary.unpack(base, name -> stream(jar.get(name)));
        assertEquals(base, out.getParent(), "a jar's starter library lives in its own directory");
        assertTrue(Files.isRegularFile(out.resolve("species/x/geometry.json")));
        assertTrue(Files.isRegularFile(out.resolve(StarterLibrary.COMPLETE)));
        assertEquals(out, StarterLibrary.unpack(base, name -> stream(jar.get(name))),
                "the same jar booting again uses the directory it already has");
    }

    /**
     * two jars on one machine — a server mid-upgrade beside one that is not, or a gate beside
     * another checkout's — each keep their own starter library. The shared directory let the second
     * to boot overwrite the first's under a running server.
     */
    @Test
    void twoDifferentJarsNeverTouchEachOthersStarterLibrary(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve(StarterLibrary.BASE);
        Map<String, String> older = jar("{\"templates\":[\"old\"]}");
        Map<String, String> newer = jar("{\"templates\":[\"new\"]}");
        Path a = StarterLibrary.unpack(base, name -> stream(older.get(name)));
        Path b = StarterLibrary.unpack(base, name -> stream(newer.get(name)));
        assertFalse(a.equals(b), "two different starter libraries were given one directory");
        assertEquals("{\"templates\":[\"old\"]}", Files.readString(a.resolve("templates.json")),
                "the second jar's unpack changed the first jar's starter library");
        assertEquals(a, StarterLibrary.unpack(base, name -> stream(older.get(name))),
                "the first jar re-booting after the second finds its own copy");
        assertEquals("{\"templates\":[\"old\"]}", Files.readString(a.resolve("templates.json")));
    }

    @Test
    void pruningKeepsTheDirectoryInUseAndTheNewestOthers(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve(StarterLibrary.BASE);
        Path first = null;
        for (int i = 0; i < StarterLibrary.KEEP + 3; i++) {
            Map<String, String> j = jar("{\"templates\":[" + i + "]}");
            Path d = StarterLibrary.unpack(base, name -> stream(j.get(name)));
            Files.setLastModifiedTime(d.resolve(StarterLibrary.COMPLETE),
                    java.nio.file.attribute.FileTime.fromMillis(1_000_000L + i * 1000L));
            if (first == null) {
                first = d;
            }
        }
        long left;
        try (var list = Files.list(base)) {
            left = list.count();
        }
        assertEquals(StarterLibrary.KEEP + 1, left, "the one in use plus the " + StarterLibrary.KEEP + " newest");
        assertFalse(Files.exists(first), "the oldest starter library should have been pruned");
    }

    @Test
    void unpackRefusesAnIndexThatNamesAMissingFileAndWritesNothing(@TempDir Path tmp)
            throws Exception {
        Map<String, String> jar = Map.of(StarterLibrary.INDEX, "templates.json\nmissing.json\n",
                StarterLibrary.ROOT + "templates.json", "{}");
        Path base = tmp.resolve(StarterLibrary.BASE);
        assertThrows(java.io.IOException.class,
                () -> StarterLibrary.unpack(base, name -> stream(jar.get(name))));
        assertFalse(Files.exists(base) && Files.list(base).findAny().isPresent(),
                "a failed unpack must leave no starter library behind");
    }

    @Test
    void aJarWithNoStarterLibraryIsNoneRatherThanAnError(@TempDir Path tmp) throws Exception {
        assertNull(StarterLibrary.unpack(tmp.resolve("s"), name -> null));
    }

    @Test
    void anIndexPathThatEscapesTheDirectoryIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> StarterLibrary.files("../../evil"));
        assertThrows(IllegalArgumentException.class, () -> StarterLibrary.files("/etc/passwd"));
    }

    @Test
    void everyStarterCommandIsAllowedAndNamesAnArtifactThatShips() {
        // A template is replayed with no agent in the loop, so a command CommandGuard refuses, or
        // one naming an artifact the jar does not carry, is a button that silently does nothing.
        LibrarySnapshot snap = LibrarySnapshot.read(SRC);
        Map<String, String> kindOf = Map.of("wishpaint", "pictures", "wishbuild", "buildings",
                "wishdatapack", "datapacks");
        for (LibrarySnapshot.Entry e : snap.templates()) {
            var cmds = JsonParser.parseString(e.json()).getAsJsonObject().getAsJsonArray("commands");
            for (var c : cmds) {
                String cmd = c.getAsString();
                assertTrue(com.moddingfromamod.wish.CommandGuard.isAllowed(cmd),
                        e.id() + ": CommandGuard refuses '" + cmd + "'");
                String[] w = cmd.split(" ");
                String kind = kindOf.get(w[0]);
                if (w[0].equals("moddyevent") && w.length > 2) {
                    kind = "events";
                    w = new String[] {w[0], w[2]};
                }
                if (w[0].equals("wishmob") && w.length > 2) {
                    kind = "species";
                    w = new String[] {w[0], w[2]};
                }
                if (kind != null) {
                    assertTrue(snap.has(kind, w[1]), e.id() + ": '" + cmd + "' names a "
                            + kind + " artifact the starter library does not ship");
                }
            }
        }
    }

    private static InputStream stream(String s) {
        return s == null ? null : new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
