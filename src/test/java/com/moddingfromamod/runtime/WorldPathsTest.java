package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gate A for ending the split brain.
 *
 * <p>A wish creature used to be stored in two places with two lifetimes: its species per-world, its
 * MODEL in one folder shared by every world. Re-sculpting an id therefore reached across worlds and
 * rewrote a creature the child was still playing with. Spikor was sculpted twice — 56 units tall in
 * one world, 52 in another — and the second sculpt left the first world's Spikor wearing a body it
 * had never had.
 *
 * <p>These pin the two halves of the fix: worlds are ISOLATED, and a world that predates the fix is
 * MIGRATED rather than silently emptied.
 */
class WorldPathsTest {

    @AfterEach
    void closeWorld() {
        WorldPaths.close();   // a leaked world root would poison the next test
    }

    /** Reflection-free stand-in: WorldPaths only ever asks the server for its root. */
    private static void openAt(Path worldRoot) {
        WorldPaths.openAt(worldRoot);
    }

    @Test
    void twoWorldsNeverShareAModelDirectory() {
        // The whole point. Same creature id, two worlds, two independent files on disk.
        openAt(Path.of("/saves/world-a"));
        Path a = WorldPaths.modelDir();
        openAt(Path.of("/saves/world-b"));
        Path b = WorldPaths.modelDir();
        assertNotEquals(a, b, "each world must own its models, or a re-sculpt crosses worlds");
        assertTrue(a.toString().contains("world-a") && b.toString().contains("world-b"));
    }

    @Test
    void modelsAndDatapacksLiveUnderTheWorldsWishesFolder() {
        openAt(Path.of("/saves/w"));
        assertEquals(Path.of("/saves/w/wishes/models"), WorldPaths.modelDir());
        assertEquals(Path.of("/saves/w/wishes/wish-datapacks"), WorldPaths.datapackDir());
    }

    @Test
    void withNoWorldOpenThereIsNoWorldPath() {
        // Callers fall back to a scratch location rather than writing into whichever save happened
        // to be open last — the stale-pointer bug this class is meant to make impossible.
        WorldPaths.close();
        assertNull(WorldPaths.modelDir());
        assertNull(WorldPaths.datapackDir());
        assertNull(WorldPaths.wishesDir());
    }

    @Test
    void aWorldFromBeforeTheFixKeepsWhatTheChildAlreadyMade(@TempDir Path tmp) throws Exception {
        // Without migration, every creature made before this change would still be LISTED (species
        // are already per-world) but would have no model to render.
        Path legacy = tmp.resolve("home/models");
        Files.createDirectories(legacy.resolve("spikor"));
        Files.writeString(legacy.resolve("spikor/geometry.json"), "{\"spikor\":true}");
        Files.writeString(legacy.resolve("spikor/texture.png"), "png");

        Path world = tmp.resolve("saves/old-world");
        openAt(world);
        WorldPaths.migrateLegacyFrom(legacy, WorldPaths.modelDir(), "creature model");

        Path moved = world.resolve("wishes/models/spikor/geometry.json");
        assertTrue(Files.exists(moved), "the model must follow the world it was used in");
        assertEquals("{\"spikor\":true}", Files.readString(moved));
    }

    @Test
    void migratingTwiceNeverOverwritesWhatTheWorldHasSinceMade(@TempDir Path tmp) throws Exception {
        // Re-running migration must not undo a later re-sculpt. If it did, every world load would
        // silently restore the old shared model — the split brain, resurrected once per session.
        Path legacy = tmp.resolve("home/models");
        Files.createDirectories(legacy.resolve("spikor"));
        Files.writeString(legacy.resolve("spikor/geometry.json"), "OLD");

        Path world = tmp.resolve("saves/w");
        openAt(world);
        WorldPaths.migrateLegacyFrom(legacy, WorldPaths.modelDir(), "creature model");

        Path mine = world.resolve("wishes/models/spikor/geometry.json");
        Files.writeString(mine, "MY NEW SPIKOR");
        WorldPaths.migrateLegacyFrom(legacy, WorldPaths.modelDir(), "creature model");
        assertEquals("MY NEW SPIKOR", Files.readString(mine),
                "a second migration must leave this world's own work alone");
    }

    @Test
    void twoWorldsMigratingFromTheSameLegacyFolderGetIndependentCopies(@TempDir Path tmp)
            throws Exception {
        Path legacy = tmp.resolve("home/models");
        Files.createDirectories(legacy.resolve("spikor"));
        Files.writeString(legacy.resolve("spikor/geometry.json"), "SHARED");

        Path a = tmp.resolve("saves/a");
        openAt(a);
        WorldPaths.migrateLegacyFrom(legacy, WorldPaths.modelDir(), "creature model");
        Path b = tmp.resolve("saves/b");
        openAt(b);
        WorldPaths.migrateLegacyFrom(legacy, WorldPaths.modelDir(), "creature model");

        // Editing A's copy must not touch B's — the property that was false before this change.
        Files.writeString(a.resolve("wishes/models/spikor/geometry.json"), "A ONLY");
        assertEquals("SHARED", Files.readString(b.resolve("wishes/models/spikor/geometry.json")),
                "worlds migrated from the same source must still be independent afterwards");
    }

    @Test
    void aBrandNewWorldInheritsNothing(@TempDir Path tmp) throws Exception {
        // The point of the whole change, from the child's side: a fresh world is fresh. Inheriting
        // every creature ever made on this machine is the shared state being removed, and it would
        // make a new world useless as a clean slate to test in.
        Path legacy = tmp.resolve("home/models");
        Files.createDirectories(legacy.resolve("spikor"));
        Files.writeString(legacy.resolve("spikor/geometry.json"), "OLD SPIKOR");

        openAt(tmp.resolve("saves/brand-new"));
        assertTrue(!WorldPaths.hasExistingWishData(),
                "a world with no species.json and no journal has never granted a wish");
    }

    @Test
    void aWorldWithAHistoryIsRecognisedAndMigrated(@TempDir Path tmp) throws Exception {
        Path world = tmp.resolve("saves/played-in");
        Files.createDirectories(world.resolve("wishes"));
        Files.writeString(world.resolve("wishes/species.json"), "[]");
        openAt(world);
        assertTrue(WorldPaths.hasExistingWishData(),
                "a world that already granted wishes must keep what the child made");
    }

    @Test
    void aMissingLegacyFolderIsSimplyNothingToDo(@TempDir Path tmp) {
        Path world = tmp.resolve("saves/fresh");
        openAt(world);
        // A brand-new world on a machine that never had the old layout — must not create anything
        // or throw. This is the path Steve's next test world takes.
        WorldPaths.migrateLegacyFrom(tmp.resolve("nope"), WorldPaths.modelDir(), "creature model");
        assertTrue(!Files.exists(world.resolve("wishes/models")),
                "nothing to migrate means nothing created");
    }
}
