package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>A pack the world was MADE with is not a wish pack.</b>
 *
 * <p>A SkyBlock world's three packs are world-folder packs, so their ids start {@code file/} like
 * every wish pack's. Everything that decided "is this one of ours?" by that prefix alone treated them
 * as wish packs the store had forgotten: a disable dropped them, the boot seed kept them off. These are
 * the pure halves; {@code DatapackDisableGameTest} drives the real disable and reload.
 */
class WorldCreationPacksTest {

    private static final String SKYBLOCK = "file/skyvoid_standard_skyblock_v2_0_4-MC_1_21.zip";
    private static final String ADDITIONS = "file/skyvoid_additions_v1_2_7-MC_1_21.zip";
    private static final String WISH = "file/ice_bomb.zip";
    private static final Set<String> STORE = Set.of("ice_bomb", "fire_breath");

    @Test
    void onlyAPackTheStoreHoldsIsAWishPack() {
        assertTrue(DatapackReloader.isStorePack(WISH, STORE));
        assertFalse(DatapackReloader.isStorePack(SKYBLOCK, STORE),
                "a world-creation pack was taken for a wish pack — the #1828 switch-off");
        assertFalse(DatapackReloader.isStorePack("vanilla", STORE));
        assertFalse(DatapackReloader.isStorePack("mod_data", STORE));
        assertFalse(DatapackReloader.isStorePack(WISH, null));
        assertEquals("ice_bomb", DatapackReloader.featureIdOf(WISH));
    }

    @Test
    void theBootAlertNamesEveryWorldPackThatIsOffAndNothingElse() {
        List<String> available = List.of("vanilla", SKYBLOCK, ADDITIONS, WISH, "file/fire_breath.zip");
        // A live SkyBlock world: both SkyBlock packs off, one wish pack off (undone), vanilla on.
        List<String> selected = List.of("vanilla", "file/fire_breath.zip");

        assertEquals(List.of(ADDITIONS, SKYBLOCK), DatapackReloader.worldPacksOff(available, selected, STORE),
                "an undone wish pack is not an alert; a world-creation pack that is off is");
    }

    @Test
    void noAlertWhenTheWorldsPacksAreOn() {
        List<String> available = List.of("vanilla", SKYBLOCK, WISH);
        assertTrue(DatapackReloader.worldPacksOff(available, List.of("vanilla", SKYBLOCK), STORE).isEmpty());
        assertTrue(DatapackReloader.worldPacksOff(List.of("vanilla"), List.of("vanilla"), STORE).isEmpty(),
                "a world with no packs of its own has nothing to say");
    }

    @Test
    void aRejectedWishsCulpritIsNeverAWorldCreationPack(@TempDir Path dir) throws Exception {
        Path skyblock = dir.resolve("skyvoid_standard_skyblock_v2_0_4-MC_1_21.zip");
        Path wish = dir.resolve("ice_bomb.zip");
        Files.writeString(skyblock, "x");
        Files.writeString(wish, "y");
        Files.setLastModifiedTime(wish, FileTime.fromMillis(1_000_000L));
        Files.setLastModifiedTime(skyblock, FileTime.fromMillis(2_000_000L));   // newer, and not ours

        assertEquals("skyvoid_standard_skyblock_v2_0_4-MC_1_21",
                DatapackReloader.newestInstalledPack(dir).orElseThrow().featureId(),
                "the unrestricted pick sees every zip (the control)");
        assertEquals("ice_bomb",
                DatapackReloader.newestInstalledPack(dir, STORE::contains).orElseThrow().featureId(),
                "a rejected wish's culprit was a world-creation pack");
    }

    @Test
    void onlyAPackThatWasOnAtTheLastBootPages() {
        var states = DatapackReloader.judgeWorldPacks(List.of(ADDITIONS, SKYBLOCK), Set.of(SKYBLOCK));
        assertEquals(List.of(new DatapackReloader.WorldPackState(ADDITIONS, false),
                        new DatapackReloader.WorldPackState(SKYBLOCK, true)), states,
                "a pack switched off since the last boot pages; one already off is said, not paged");
        assertTrue(DatapackReloader.judgeWorldPacks(List.of(), Set.of(SKYBLOCK)).isEmpty(),
                "nothing off, nothing said");
    }

    /** A zip in {@code dir} named {@code <id>.zip} holding the given entries. */
    private static void zip(Path dir, String id, String... entries) throws Exception {
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(dir.resolve(id + ".zip")))) {
            for (String e : entries) {
                out.putNextEntry(new java.util.zip.ZipEntry(e));
                out.write("x".getBytes());
                out.closeEntry();
            }
        }
    }

    @Test
    void aReadyMadePackTheStoreDoesNotListIsStillOurs(@TempDir Path dir) throws Exception {
        // Measured on a SkyBlock world's backup: 10 of its 19 wish packs (ready-made items and abilities) are not
        // in the world's store. Each is namespaced by its own id; a third-party pack never is.
        zip(dir, "breath_cone_pet_dragon", "pack.mcmeta", "data/breath_cone_pet_dragon/function/tick.mcfunction");
        zip(dir, "skyvoid_additions_v1_4_1-MC_1_21_6-1_21_8", "pack.mcmeta",
                "data/skyvoid_additions/function/technical/load.mcfunction");
        zip(dir, "empty_pack", "pack.mcmeta");
        Files.writeString(dir.resolve("broken.zip"), "not a zip");

        assertEquals(List.of("breath_cone_pet_dragon"), DatapackReloader.selfNamespaced(dir));
        List<String> available = List.of("vanilla", "file/breath_cone_pet_dragon.zip",
                "file/skyvoid_additions_v1_4_1-MC_1_21_6-1_21_8.zip");
        assertEquals(List.of("file/skyvoid_additions_v1_4_1-MC_1_21_6-1_21_8.zip"),
                DatapackReloader.worldPacksOff(available, List.of("vanilla"), DatapackReloader.selfNamespaced(dir)),
                "an undone ready-made pack is not 'a pack this world was made with' — only the third-party one is");
    }
}
