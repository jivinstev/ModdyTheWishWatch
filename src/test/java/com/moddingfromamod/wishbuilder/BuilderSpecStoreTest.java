package com.moddingfromamod.wishbuilder;

import static com.moddingfromamod.wishbuilder.BuilderSpecTest.j;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** each Builder make's spec is kept on his shelf, one row per thing, and read back to edit it. */
class BuilderSpecStoreTest {

    static final String KID = "00000000-0000-0000-0000-000000000abc";

    static BuilderSpecStore.Made made(String id, String ref, String spec) {
        BuilderSpec s = BuilderSpec.read(j(spec));
        return new BuilderSpecStore.Made(id, ref, s.kind().id(), s.toJson(), "wish-1", 1000L);
    }

    @Test
    void aMakeIsKeptAndReadBackAsTheSameSpec(@TempDir Path root) throws Exception {
        String spec = BuilderExamples.ONE_PER_KIND.get(0);
        assertTrue(BuilderSpecStore.put(root, KID, made("blueberry_ab12cd", "blueberry_ab12cd", spec)));
        BuilderSpecStore.Made back = BuilderSpecStore.get(root, KID, "blueberry_ab12cd");
        assertEquals(BuilderSpec.read(j(spec)), back.read());
        assertEquals("creature", back.kind());
        String file = Files.readString(BuilderSpecStore.file(root, KID));
        assertTrue(file.contains("\"store\": \"builder-specs\"") || file.contains("\"store\":\"builder-specs\""), file);
    }

    @Test
    void anEditReplacesTheRowAndOtherMakesStay(@TempDir Path root) throws Exception {
        BuilderSpecStore.put(root, KID, made("cat_1", "cat_1", BuilderExamples.ONE_PER_KIND.get(0)));
        BuilderSpecStore.put(root, KID, made("fort_1", "fort_1", BuilderExamples.ONE_PER_KIND.get(6)));
        BuilderSpecStore.put(root, KID, made("cat_1", "cat_1",
                "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"size\":4}"));
        Map<String, BuilderSpecStore.Made> all = BuilderSpecStore.all(root, KID);
        assertEquals(2, all.size());
        assertEquals(4, all.get("cat_1").read().size());
        assertEquals(BuilderSpec.Kind.BUILDING, all.get("fort_1").read().kind());
    }

    // ──: a Magician edit marks the row, and the row stops being offered as a Builder edit ──────────────

    @Test
    void aMagicianEditMarksTheRowAndKeepsItsSpec(@TempDir Path root) throws Exception {
        String spec = BuilderExamples.ONE_PER_KIND.get(0);
        BuilderSpecStore.put(root, KID, made("cat_1", "cat_1", spec));
        BuilderSpecStore.put(root, KID, made("fort_1", "fort_1", BuilderExamples.ONE_PER_KIND.get(6)));
        assertEquals(java.util.List.of("cat_1"), BuilderSpecStore.touch(root, KID, java.util.Set.of("cat_1"), "wish-9"));
        BuilderSpecStore.Made cat = BuilderSpecStore.get(root, KID, "cat_1");
        assertTrue(cat.magicianTouched());
        assertEquals("wish-9", cat.touched());
        assertEquals(BuilderSpec.read(j(spec)), cat.read(), "the spec is kept for history");
        assertFalse(BuilderSpecStore.get(root, KID, "fort_1").magicianTouched(), "another make is untouched");
        assertNull(BuilderSpecStore.idFor(BuilderSpecStore.all(root, KID), "cat_1", "Cat"),
                "a touched make is no longer offered as a Builder edit");
        assertEquals("fort_1", BuilderSpecStore.idFor(BuilderSpecStore.all(root, KID), "fort_1", "Fort"));
    }

    @Test
    void anItemIsFoundByItsNameOrItsMintedId(@TempDir Path root) throws Exception {
        String item = BuilderExamples.ONE_PER_KIND.stream().filter(x -> x.contains("\"item\"")).findFirst().orElseThrow();
        BuilderSpec s = BuilderSpec.read(j(item));
        BuilderSpecStore.put(root, KID, new BuilderSpecStore.Made("zap_1", "Zappy Wand", s.kind().id(), s.toJson(),
                "wish-1", 1000L, "zappy_wand", ""));
        BuilderSpecStore.put(root, KID, new BuilderSpecStore.Made("zap_2", "Old Wand", s.kind().id(), s.toJson(),
                "wish-1", 1000L, "", ""));
        assertEquals(java.util.List.of("zap_1"),
                BuilderSpecStore.touch(root, KID, java.util.Set.of("zappy_wand"), "w2"), "by the id make_item minted");
        assertEquals(java.util.List.of("zap_2"),
                BuilderSpecStore.touch(root, KID, java.util.Set.of("some_id", "old wand"), "w3"), "an older row, by its name");
    }

    @Test
    void aTouchKeepsItsFirstMarkAndAWishThatTouchesNothingWritesNothing(@TempDir Path root) throws Exception {
        BuilderSpecStore.put(root, KID, made("cat_1", "cat_1", BuilderExamples.ONE_PER_KIND.get(0)));
        BuilderSpecStore.touch(root, KID, java.util.Set.of("cat_1"), "first");
        String before = Files.readString(BuilderSpecStore.file(root, KID));
        assertTrue(BuilderSpecStore.touch(root, KID, java.util.Set.of("cat_1"), "second").isEmpty());
        assertTrue(BuilderSpecStore.touch(root, KID, java.util.Set.of("dragon_9"), "third").isEmpty());
        assertEquals(before, Files.readString(BuilderSpecStore.file(root, KID)), "nothing changed, nothing written");
        assertEquals("first", BuilderSpecStore.get(root, KID, "cat_1").touched());
    }

    @Test
    void aChildWithNoBuilderMakesGetsNoFile(@TempDir Path root) throws Exception {
        assertTrue(BuilderSpecStore.touch(root, KID, java.util.Set.of("cat_1"), "w").isEmpty());
        assertFalse(Files.exists(BuilderSpecStore.file(root, KID)), "a Magician wish must not create an empty shelf file");
    }

    @Test
    void anOlderFileWithoutTheNewFieldsReadsAsUntouched(@TempDir Path root) throws Exception {
        Path f = BuilderSpecStore.file(root, KID);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{\"store\":\"builder-specs\",\"v\":1,\"specs\":[{\"id\":\"cat_1\",\"ref\":\"cat_1\","
                + "\"kind\":\"creature\",\"spec\":" + BuilderExamples.ONE_PER_KIND.get(0) + ",\"wish\":\"w\",\"at\":1}]}");
        BuilderSpecStore.Made cat = BuilderSpecStore.get(root, KID, "cat_1");
        assertFalse(cat.magicianTouched());
        assertEquals("", cat.alias());
    }

    @Test
    void aNewerModdysFileIsRefusedNeverOverwritten(@TempDir Path root) throws Exception {
        Path f = BuilderSpecStore.file(root, KID);
        Files.createDirectories(f.getParent());
        String newer = "{\"store\":\"builder-specs\",\"v\":99,\"specs\":[]}";
        Files.writeString(f, newer);
        assertFalse(BuilderSpecStore.put(root, KID, made("cat_1", "cat_1", BuilderExamples.ONE_PER_KIND.get(0))));
        assertEquals(newer, Files.readString(f));
        assertNull(BuilderSpecStore.get(root, KID, "cat_1"));
    }

    @Test
    void anOwnerThatCannotNameADirectoryKeepsNothing(@TempDir Path root) throws Exception {
        assertFalse(BuilderSpecStore.put(root, "../escape", made("cat_1", "cat_1", BuilderExamples.ONE_PER_KIND.get(0))));
        assertTrue(BuilderSpecStore.all(root, "../escape").isEmpty());
    }

    @Test
    void aWatchTileFindsItsMakeByIdOrAnItemByItsName(@TempDir Path root) throws Exception {
        BuilderSpecStore.put(root, KID, made("cat_1", "cat_1", BuilderExamples.ONE_PER_KIND.get(0)));
        BuilderSpecStore.put(root, KID, made("zappy_1", "Zappy", BuilderExamples.ONE_PER_KIND.get(3)));
        Map<String, BuilderSpecStore.Made> mine = BuilderSpecStore.all(root, KID);
        assertEquals("cat_1", BuilderSpecStore.idFor(mine, "cat_1", "Blueberry"));
        assertEquals("zappy_1", BuilderSpecStore.idFor(mine, "id:lightning_sword_9", "Zappy"));
        assertNull(BuilderSpecStore.idFor(mine, "someone_elses", "Blueberry"), "a creature is matched by id, never by name");
    }
}
