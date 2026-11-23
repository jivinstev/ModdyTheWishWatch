package com.moddingfromamod.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.marshal.ArtifactOwner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PlayerShelf}, the split of one store between a world file and each child's shelf.
 *
 * <p>Most of these are about the MERGE, because that is the half whose mistakes are silent: a
 * second server that overwrites a child's shelf from what it read at boot loses his newest things
 * with every log line green. Each test drives two {@link Server}s — two shelves, two in-memory
 * maps, one disk — exactly as Creative and Survival will.
 */
class PlayerShelfTest {

    private static final String KID = "1f2e3d4c-0000-0000-0000-00000000000a";
    private static final String SIS = "2a2b2c2d-0000-0000-0000-00000000000b";

    @TempDir
    Path tmp;

    /** One server: its own shelf instance and its own in-memory store, over a shared disk. */
    private final class Server {
        final PlayerShelf shelf = new PlayerShelf("things", "things.json", "things",
                PlayerShelf.byField("id"));
        final Map<String, JsonObject> memory = new LinkedHashMap<>();
        final Path world;
        final List<String> warnings = new ArrayList<>();

        Server(String worldName) {
            this.world = tmp.resolve(worldName).resolve("wishes");
        }

        WishStoreFile.Read load() {
            memory.clear();
            WishStoreFile.Read r = shelf.read(world, players(), 1);
            JsonObject root = r instanceof WishStoreFile.Read.Ok ok ? ok.root()
                    : r instanceof WishStoreFile.Read.Legacy l ? l.root() : null;
            if (root != null) {
                for (JsonElement el : root.getAsJsonArray("things")) {
                    JsonObject o = el.getAsJsonObject();
                    memory.put(o.get("id").getAsString(), o);
                }
            }
            shelf.remember(payload(), ArtifactOwner::of);
            return r;
        }

        void put(String id, String owner, String colour) {
            memory.put(id, thing(id, owner, colour));
        }

        boolean save() {
            return shelf.write(world, players(), 1, payload(), ArtifactOwner::of, warnings::add);
        }

        JsonObject payload() {
            JsonArray arr = new JsonArray();
            memory.values().forEach(arr::add);
            JsonObject p = new JsonObject();
            p.add("things", arr);
            return p;
        }
    }

    private Path players() {
        return tmp.resolve("players");
    }

    private static JsonObject thing(String id, String owner, String colour) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("colour", colour);
        ArtifactOwner.stamp(o, owner);
        return o;
    }

    private Map<String, String> onShelf(String owner) throws IOException {
        return idsToColour(players().resolve(owner).resolve("things.json"));
    }

    private static Map<String, String> idsToColour(Path f) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(f)) {
            return out;
        }
        JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
        for (JsonElement el : root.getAsJsonArray("things")) {
            JsonObject o = el.getAsJsonObject();
            out.put(o.get("id").getAsString(), o.get("colour").getAsString());
        }
        return out;
    }

    private static void write(Path f, String text) throws IOException {
        Files.createDirectories(f.getParent());
        Files.writeString(f, text, StandardCharsets.UTF_8);
    }

    // ── the split itself ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("nothing anywhere is ABSENT, the only case an empty store is right")
    void absentEverywhere() {
        assertInstanceOf(WishStoreFile.Read.Absent.class, new Server("w").load());
    }

    @Test
    @DisplayName("an owned record goes to its child's shelf; an unowned one stays in the world")
    void theSplit() throws IOException {
        Server a = new Server("creative");
        a.load();
        a.put("spikor", KID, "green");
        a.put("slime", "", "lime");
        assertTrue(a.save());

        assertEquals(Map.of("spikor", "green"), onShelf(KID));
        assertEquals(Map.of("slime", "lime"), idsToColour(a.world.resolve("things.json")));
        JsonObject stamped = JsonParser.parseString(Files.readString(
                players().resolve(KID).resolve("things.json"))).getAsJsonObject();
        assertEquals("things", stamped.get("store").getAsString(), "the shelf is enveloped too");
        assertEquals(1, stamped.get("v").getAsInt());
    }

    @Test
    @DisplayName("D6: a thing he made in Creative is there when Survival loads — the whole point")
    void itFollowsHimToTheOtherWorld() {
        Server creative = new Server("creative");
        creative.load();
        creative.put("spikor", KID, "green");
        creative.save();

        Server survival = new Server("survival");
        survival.load();
        assertTrue(survival.memory.containsKey("spikor"));
        assertEquals(KID, ArtifactOwner.of(survival.memory.get("spikor")));
    }

    @Test
    @DisplayName("migration: owned records in a pre-T9.1b world file move out at the next save")
    void migratesAnOldWorld() throws IOException {
        Server a = new Server("old");
        write(a.world.resolve("things.json"), "{\"things\":["
                + thing("spikor", KID, "green") + "," + thing("slime", "", "lime") + "]}");
        assertInstanceOf(WishStoreFile.Read.Legacy.class, a.load());
        assertTrue(a.shelf.needsConverge(), "an owned record in the world file must be converged");
        a.save();
        assertEquals(Map.of("spikor", "green"), onShelf(KID));
        assertEquals(Map.of("slime", "lime"), idsToColour(a.world.resolve("things.json")));
        assertFalse(a.shelf.needsConverge());
    }

    @Test
    @DisplayName("the FOLDER says whose it is, whatever a hand-copied record claims")
    void theFolderIsTheOwner() throws IOException {
        write(players().resolve(KID).resolve("things.json"),
                "{\"store\":\"things\",\"v\":1,\"things\":[" + thing("wand", SIS, "red") + "]}");
        Server a = new Server("w");
        a.load();
        assertEquals(KID, ArtifactOwner.of(a.memory.get("wand")));
    }

    @Test
    @DisplayName("an owner that cannot name a directory stays in the world rather than become a path")
    void aStrangeOwnerStaysInTheWorld() throws IOException {
        Server a = new Server("w");
        a.load();
        a.put("rock", "../escape", "grey");
        a.save();
        assertEquals(Map.of("rock", "grey"), idsToColour(a.world.resolve("things.json")));
        assertFalse(Files.exists(tmp.resolve("escape")));
    }

    // ── the merge: two servers, one shelf ───────────────────────────────────────────────────────

    @Test
    @DisplayName("two servers each add a thing: neither overwrites the other's")
    void twoAddsBothSurvive() throws IOException {
        Server creative = new Server("creative");
        Server survival = new Server("survival");
        creative.load();
        survival.load();                    // both booted before either wrote
        creative.put("wand", KID, "purple");
        creative.save();
        survival.put("sword", KID, "blue"); // survival still holds the breakfast-time shelf
        survival.save();
        assertEquals(Map.of("wand", "purple", "sword", "blue"), onShelf(KID));
    }

    @Test
    @DisplayName("an edit on one server is not undone by the other saving something unrelated")
    void anEditIsNotReverted() throws IOException {
        Server creative = new Server("creative");
        creative.load();
        creative.put("wand", KID, "purple");
        creative.save();
        Server survival = new Server("survival");
        survival.load();                    // survival sees purple
        creative.put("wand", KID, "green"); // creative recolours it
        creative.save();
        survival.put("sword", KID, "blue"); // survival saves, still holding purple, untouched
        survival.save();
        assertEquals("green", onShelf(KID).get("wand"),
                "survival never touched the wand, so the disk's newer colour must stand");
    }

    @Test
    @DisplayName("a deletion on one server is not resurrected by the other")
    void aDeleteStaysDeleted() throws IOException {
        Server creative = new Server("creative");
        creative.load();
        creative.put("wand", KID, "purple");
        creative.save();
        Server survival = new Server("survival");
        survival.load();
        creative.memory.remove("wand");
        creative.save();
        survival.put("sword", KID, "blue");
        survival.save();
        assertEquals(Map.of("sword", "blue"), onShelf(KID));
    }

    @Test
    @DisplayName("our own delete wins, and the child's last thing going still rewrites his shelf")
    void ourDeleteOfTheLastThing() throws IOException {
        Server a = new Server("w");
        a.load();
        a.put("wand", KID, "purple");
        a.save();
        a.memory.remove("wand");
        a.save();
        assertEquals(Map.of(), onShelf(KID));
    }

    @Test
    @DisplayName("a thing the OTHER server added survives every later save here, not just the first")
    void theirAdditionSurvivesTwoSaves() throws IOException {
        Server creative = new Server("creative");
        Server survival = new Server("survival");
        creative.load();
        survival.load();
        creative.put("wand", KID, "purple");
        creative.save();
        survival.put("sword", KID, "blue");
        survival.save();
        survival.put("shield", KID, "gold");
        survival.save();                    // the save that would lose it if the base were the merge
        assertEquals(Map.of("wand", "purple", "sword", "blue", "shield", "gold"), onShelf(KID));
    }

    @Test
    @DisplayName("changedOnDisk tells a server its view of a child is stale")
    void changedOnDisk() {
        Server creative = new Server("creative");
        Server survival = new Server("survival");
        creative.load();
        survival.load();
        assertFalse(survival.shelf.changedOnDisk(players(), KID));
        creative.put("wand", KID, "purple");
        creative.save();
        assertTrue(survival.shelf.changedOnDisk(players(), KID));
        survival.load();
        assertFalse(survival.shelf.changedOnDisk(players(), KID));
    }

    // ── refusals ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a damaged shelf latches THAT child only: never loaded, never written, others save")
    void aDamagedShelfLatchesOnlyItsChild() throws IOException {
        Path broken = players().resolve(KID).resolve("things.json");
        write(broken, "{ this is not json");
        Server a = new Server("w");
        a.load();
        assertTrue(a.shelf.refusedShelves().containsKey(KID));
        assertTrue(a.shelf.mayWrite(), "the world is fine, so the store is not latched");
        a.put("wand", KID, "purple");
        a.put("sword", SIS, "blue");
        a.save();
        assertEquals("{ this is not json", Files.readString(broken), "his bytes are left alone");
        assertEquals(Map.of("sword", "blue"), onShelf(SIS));
        assertFalse(a.warnings.isEmpty(), "and the skip is said out loud");
    }

    @Test
    @DisplayName("a shelf written by a newer Moddy is refused, not read as empty and overwritten")
    void aNewerShelfIsRefused() throws IOException {
        Path newer = players().resolve(KID).resolve("things.json");
        String text = "{\"store\":\"things\",\"v\":9,\"things\":[]}";
        write(newer, text);
        Server a = new Server("w");
        a.load();
        a.put("wand", KID, "purple");
        a.save();
        assertEquals(text, Files.readString(newer));
    }

    @Test
    @DisplayName("a damaged WORLD file latches the whole store, as the store latch always did")
    void aDamagedWorldLatchesEverything() throws IOException {
        Server a = new Server("w");
        write(a.world.resolve("things.json"), "not json");
        assertInstanceOf(WishStoreFile.Read.Refused.class, a.load());
        a.put("wand", KID, "purple");
        assertFalse(a.save());
        assertFalse(Files.exists(players().resolve(KID).resolve("things.json")));
    }

    // ── collisions ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a shelf key the world already uses: the world's wins here, and his stays on disk")
    void aCollisionLosesNothing() throws IOException {
        write(players().resolve(KID).resolve("things.json"),
                "{\"store\":\"things\",\"v\":1,\"things\":[" + thing("dragon", KID, "red") + "]}");
        Server a = new Server("w");
        write(a.world.resolve("things.json"),
                "{\"store\":\"things\",\"v\":1,\"things\":[" + thing("dragon", "", "blue") + "]}");
        a.load();
        assertEquals("blue", a.memory.get("dragon").get("colour").getAsString());
        assertEquals(List.of(KID + ":dragon"), a.shelf.collisions());
        a.put("wand", KID, "purple");
        a.save();
        assertEquals(Map.of("dragon", "red", "wand", "purple"), onShelf(KID),
                "his dragon was never in this server's memory, so the merge must keep it");
        assertEquals(Map.of("dragon", "blue"), idsToColour(a.world.resolve("things.json")),
                "and the shared one is still the world's");
    }

    // ── the merge table, cell by cell ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("merge: every cell of base × current × fresh")
    void mergeTable() {
        var keyOf = PlayerShelf.byField("id");
        JsonObject a1 = thing("a", KID, "1");
        JsonObject a2 = thing("a", KID, "2");
        JsonObject a3 = thing("a", KID, "3");
        // untouched here, changed there → theirs
        assertEquals("3", only(PlayerShelf.merge(List.of(a1), Map.of("a", a1.toString()),
                Map.of("a", a3), keyOf)));
        // changed here, changed there → ours
        assertEquals("2", only(PlayerShelf.merge(List.of(a2), Map.of("a", a1.toString()),
                Map.of("a", a3), keyOf)));
        // added here → ours
        assertEquals("2", only(PlayerShelf.merge(List.of(a2), Map.of(), Map.of(), keyOf)));
        // untouched here, deleted there → gone
        assertEquals(0, PlayerShelf.merge(List.of(a1), Map.of("a", a1.toString()), Map.of(),
                keyOf).size());
        // deleted here → gone even though it is still on disk
        assertEquals(0, PlayerShelf.merge(List.of(), Map.of("a", a1.toString()), Map.of("a", a1),
                keyOf).size());
        // never seen here, on disk → kept
        assertEquals("3", only(PlayerShelf.merge(List.of(), Map.of(), Map.of("a", a3), keyOf)));
    }

    private static String only(JsonArray arr) {
        assertEquals(1, arr.size());
        return arr.get(0).getAsJsonObject().get("colour").getAsString();
    }
}
