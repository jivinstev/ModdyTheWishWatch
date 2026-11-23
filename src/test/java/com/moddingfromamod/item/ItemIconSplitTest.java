package com.moddingfromamod.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.WorldPaths;
import com.moddingfromamod.store.WishStoreFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>The wish and its icon, stored apart</b>, driven through the REAL store against a real
 * world directory: {@code item-specs.json} v2 holds the design, {@code item-icons.json} the look.
 *
 * <p>Every old shape here is read out of {@code corpus/store-shapes/} rather than typed, so the
 * migration is replayed against the same bytes {@code StoreShapeCorpusTest} pins at the envelope.
 */
class ItemIconSplitTest {

    private static final Path CORPUS = Path.of("corpus", "store-shapes");

    @TempDir
    Path world;

    private Path wishes() {
        return world.resolve("wishes");
    }

    /**
     * The corpus fixtures carry an owner ({@code a1b2c3d4}), and an owned design now
     * lives on its owner's shelf — so this test gets a shelf root of its own, beside the world.
     */
    private Path shelf() {
        return world.resolve("players").resolve("a1b2c3d4");
    }

    private static final String SHELF_PROP =
            com.moddingfromamod.ModdingFromAModMod.MODID + ".playersDir";
    private String shelfWas;

    @BeforeEach
    void open() throws Exception {
        shelfWas = System.getProperty(SHELF_PROP);
        System.setProperty(SHELF_PROP, world.resolve("players").toString());
        WorldPaths.openAt(world);
        Files.createDirectories(wishes());
        ItemSpecStore.clear();
    }

    @AfterEach
    void close() throws Exception {
        // A refused fixture latches BOTH stores' writes off, and they are process-wide statics.
        // A load of an empty world is the only thing that resets a latch, so do one before
        // leaving — or the next test class's put() inherits this one's refusal.
        try (var files = Files.list(wishes())) {
            for (Path p : files.toList()) {
                Files.delete(p);
            }
        }
        ItemSpecStore.load();
        ItemSpecStore.clear();
        WorldPaths.close();
        if (shelfWas == null) {
            System.clearProperty(SHELF_PROP);
        } else {
            System.setProperty(SHELF_PROP, shelfWas);
        }
    }

    private JsonObject onShelf(String name) throws Exception {
        return JsonParser.parseString(Files.readString(shelf().resolve(name))).getAsJsonObject();
    }

    private JsonObject onDisk(String name) throws Exception {
        return JsonParser.parseString(Files.readString(wishes().resolve(name))).getAsJsonObject();
    }

    private void fixture(String corpusFile, String as) throws Exception {
        Files.copy(CORPUS.resolve(corpusFile), wishes().resolve(as));
    }

    private static ItemSpecStore.Spec wand() {
        return new ItemSpecStore.Spec("zonkbolt_lightning", "Zonkbolt", "wand", "purple", "held", 1,
                List.of("lightning"), new JsonObject());
    }

    @Test
    @DisplayName("a saved design carries NO look, and the look is in item-icons.json")
    void theTwoHalvesLandInTwoFiles() throws Exception {
        ItemSpecStore.put(wand());

        JsonObject specs = onDisk("item-specs.json");
        assertEquals(ItemSpecStore.SCHEMA, specs.get("v").getAsInt(), "the design store is at the current schema (v2 split the look; v3 is #2216)");
        JsonObject design = specs.getAsJsonArray("specs").get(0).getAsJsonObject();
        assertFalse(design.has("icon") || design.has("colour"),
                "the design must not carry its look any more: " + design);
        assertEquals("Zonkbolt", design.get("name").getAsString());

        JsonObject icons = onDisk("item-icons.json");
        assertEquals("item-icons", icons.get("store").getAsString());
        assertEquals(1, icons.get("v").getAsInt());
        assertTrue(icons.has("icons"), "INLINE, never nested under a wrapper key: " + icons);
        JsonObject row = icons.getAsJsonArray("icons").get(0).getAsJsonObject();
        assertEquals("zonkbolt_lightning", row.get("id").getAsString());
        assertEquals("wand", row.get("sprite").getAsString());
        assertEquals("purple", row.get("tint").getAsString());
    }

    @Test
    @DisplayName("a load JOINS the halves, so every caller still sees one Spec")
    void aLoadJoinsTheHalves() {
        ItemSpecStore.put(wand());
        ItemSpecStore.clear();
        ItemSpecStore.load();

        ItemSpecStore.Spec back = ItemSpecStore.get("zonkbolt_lightning");
        assertNotNull(back);
        assertEquals("wand", back.icon());
        assertEquals("purple", back.colour());
        assertEquals(new ItemLook("wand", "purple"), back.look());
    }

    @Test
    @DisplayName("a v1 file is read, its looks peeled out, and the disk converged to v2")
    void aV1FileIsMigrated() throws Exception {
        fixture("item-specs-v1.json", "item-specs.json");
        ItemSpecStore.load();

        ItemSpecStore.Spec s = ItemSpecStore.get("bloop_slayer");
        assertNotNull(s, "the design survived");
        assertEquals("trident", s.icon(), "…wearing the look it carried inline");
        assertEquals("#33aaff", s.colour());
        assertEquals("a1b2c3d4", s.ownerId(), "…and still whose it was");

        // The design is a1b2c3d4's, so since the shelves it converges onto HIS shelf, and the world's
        // own file is stamped v2 and empty of it.
        assertEquals(ItemSpecStore.SCHEMA, onDisk("item-specs.json").get("v").getAsInt(),
                "the world file was converged on load");
        assertEquals(0, onDisk("item-specs.json").getAsJsonArray("specs").size(),
                "and no longer holds a child's design");
        JsonObject specs = onShelf("item-specs.json");
        assertEquals(ItemSpecStore.SCHEMA, specs.get("v").getAsInt(), "his shelf is written at the current schema");
        assertFalse(specs.getAsJsonArray("specs").get(0).getAsJsonObject().has("icon"));
        JsonObject icon = onShelf("item-icons.json").getAsJsonArray("icons").get(0)
                .getAsJsonObject();
        assertEquals("bloop_slayer", icon.get("id").getAsString());
        assertEquals("trident", icon.get("sprite").getAsString());
        assertEquals("#33aaff", icon.get("tint").getAsString());
    }

    @Test
    @DisplayName("a pre-T9.1 items.json is migrated into BOTH new files")
    void aPreT91WorldLandsInBothFiles() throws Exception {
        fixture("items-v0-specs.json", "items.json");
        ItemSpecStore.load();

        assertEquals("trident", ItemSpecStore.get("bloop_slayer").icon());
        // Before owners, so nobody's: a SHARED design stays with the world.
        assertEquals(ItemSpecStore.SCHEMA, onDisk("item-specs.json").get("v").getAsInt());
        assertEquals(1, onDisk("item-icons.json").getAsJsonArray("icons").size());
    }

    @Test
    @DisplayName("⚠ an unreadable icons file stops the DESIGN write too, and loses nothing")
    void aRefusedIconFileBlocksTheDesignWrite() throws Exception {
        // The one ordering that would lose data: the looks are still inline in a v1 design file,
        // the icon file cannot be read, and the design file gets rewritten as v2 — dropping the
        // looks from the only place they can still be read.
        fixture("item-specs-v1.json", "item-specs.json");
        Files.writeString(wishes().resolve("item-icons.json"), "{ this is half a file");
        String specsBefore = Files.readString(wishes().resolve("item-specs.json"));

        ItemSpecStore.load();
        assertEquals("trident", ItemSpecStore.get("bloop_slayer").icon(),
                "the inline look still serves while the icon file is refused");
        ItemSpecStore.put(wand());      // an ordinary write, which must not happen

        assertEquals(specsBefore, Files.readString(wishes().resolve("item-specs.json")),
                "the v1 design file — the looks' only readable copy — was left alone");
        assertEquals("{ this is half a file",
                Files.readString(wishes().resolve("item-icons.json")),
                "and the refused icon file was not written over");
    }

    @Test
    @DisplayName("a design whose icon row is gone still LOADS — the design is never lost for it")
    void aDesignWithoutItsIconStillLoads() throws Exception {
        ItemSpecStore.put(wand());
        Files.delete(wishes().resolve("item-icons.json"));
        ItemSpecStore.clear();
        ItemSpecStore.load();

        ItemSpecStore.Spec s = ItemSpecStore.get("zonkbolt_lightning");
        assertNotNull(s, "a missing look must not cost the child the thing itself");
        assertNull(s.look());
        assertEquals(List.of("lightning"), s.effects());
    }

    @Test
    @DisplayName("the icon file wins over an inline look (a migration that crashed half way)")
    void theIconFileWinsOverAnInlineLook() throws Exception {
        fixture("item-specs-v1.json", "item-specs.json");
        Files.writeString(wishes().resolve("item-icons.json"),
                "{\"store\":\"item-icons\",\"v\":1,\"icons\":"
                        + "[{\"id\":\"bloop_slayer\",\"sprite\":\"spear\",\"tint\":\"red\"}]}");
        ItemSpecStore.load();
        assertEquals(new ItemLook("spear", "red"), ItemSpecStore.get("bloop_slayer").look());
    }

    @Test
    @DisplayName("⚠ a v1 jar REFUSES the v2 file — the bump is what makes a downgrade safe")
    void aV1ReaderRefusesAV2File() throws Exception {
        // Unbumped, a v1 reader would have parsed these icon-less records, skipped every one as
        // damaged (its fromJson requires `icon`), found nothing REFUSED and saved an empty store
        // over them. Bumped, it latches its writes off. Asserted against the envelope because the
        // v1 jar's own reader is not in this build — the refusal is the envelope's rule 3 in reverse.
        ItemSpecStore.put(wand());
        String v2 = Files.readString(wishes().resolve("item-specs.json"));
        var read = assertInstanceOf(WishStoreFile.Read.Refused.class,
                WishStoreFile.read(v2, ItemSpecStore.STORE, 1));
        assertTrue(read.reason().contains("newer Moddy"), read.reason());
    }

    @Test
    @DisplayName("the corpus manifest's schema table agrees with the stores' own constants")
    void theCorpusKnowsEachStoresNewestSchema() throws Exception {
        // Without this the manifest could say item-specs is at v1 while the code writes v2, and
        // the v2 fixture would replay as REFUSED — or worse, a v3 bump would go unreplayed.
        JsonObject schemas = JsonParser.parseString(Files.readString(CORPUS.resolve("manifest.json")))
                .getAsJsonObject().getAsJsonObject("schemas");
        assertEquals(ItemSpecStore.SCHEMA, schemas.get(ItemSpecStore.STORE).getAsInt());
        assertEquals(ItemIconStore.SCHEMA, schemas.get(ItemIconStore.STORE).getAsInt());
        assertEquals(com.moddingfromamod.library.LibrarySnapshot.ICON_SCHEMA,
                schemas.get(com.moddingfromamod.library.LibrarySnapshot.ICON_STORE).getAsInt());
    }

    @Test
    @DisplayName("the split moves no icon NUMBER — a stack a child holds keeps its artwork")
    void theSplitMovesNoNumber() {
        // WishIcons' contract: a number a stack carries must never resolve to other artwork. The
        // look stores the sprite by NAME, so the number is still derived by IconSprites — this
        // pins that the name a design round-trips through the new store resolves where it did.
        int before = WishIcons.byName("wand").id();
        ItemSpecStore.put(wand());
        ItemSpecStore.clear();
        ItemSpecStore.load();
        String sprite = ItemSpecStore.get("zonkbolt_lightning").look().sprite();
        assertEquals(before, WishIcons.byName(sprite).id());
        assertEquals(before, IconSprites.numberFor("wand"));
    }
}
